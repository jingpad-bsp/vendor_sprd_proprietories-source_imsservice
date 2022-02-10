package com.android.internal.telephony.dataconnection;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

import android.os.AsyncResult;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.LinkProperties;
import android.net.LinkAddress;
import android.net.ConnectivityManager.NetworkCallback;
import android.content.Context;
import android.telephony.data.ApnSetting;
import android.telephony.DataFailCause;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import java.net.InetAddress;
import java.net.Inet4Address;
import java.net.Inet6Address;
import com.android.sprd.telephony.RadioInteractor;
import android.telephony.PreciseDataConnectionState;
import android.telephony.ServiceState;
import android.telephony.PhoneStateListener;
import android.util.Log;

public class DcNetworkManager {
    private static final String TAG = "DcNetworkManager";
    private Context mContext = null;
    private Network mNetwork = null;
    private NetworkRequest mNetworkRequest = null;
    private NetworkRequestCallback mNetworkCallback = null;
    private ConnectivityManager mCm = null;
    private TelephonyManager mTm = null;
    private List<Message> mPendingMessages = new ArrayList<Message>();
    private static final int NETWORK_REQUEST_TIMEOUT_MILLIS = 125 * 1000;
    private static final int RELEASE_NETWORK_DELAY_MILLIS = 5 * 1000;
    private static final int CLEAR_ERROR_CAUSE_DELAY_MILLIS = 60 * 1000;
    private static final int TIMER_EXPIRE_MILLIS = 4 * 60 * 1000;
    private static final int ERROR_CAUSE_REQUEST_NETWORK_TIMEOUT = 100;
    private static final int EVENT_REQUEST_NETWORK_TIMEOUT = 1;
    private static final int EVENT_CLEAR_ERROR_CAUSE = 2;
    private static final int EVENT_DELAY_RELEASE_NETWORK = 3;
    private static final int EVENT_SET_XCAP_IP_ADDR_DONE = 4;
    private Handler mHandler = null;
    private Object mLock = new Object();
    private int mErrorCause = 0;
    private int mRequstNum = 0;
    private boolean mTimerExpire = true;

    public DcNetworkManager(Context context) {
        mContext = context;
        mCm = (ConnectivityManager) mContext.getSystemService(
                Context.CONNECTIVITY_SERVICE);
        mHandler = new DcHandler(mContext.getMainLooper());
        mTm = (TelephonyManager) mContext.getSystemService(Context.TELEPHONY_SERVICE);
    }

    class DcHandler extends Handler{
        DcHandler(Looper looper) {
            super(looper);
        }
        @Override
        public void handleMessage(Message msg) {
            Log.d(TAG, "DcHandler msg = " + msg);
            switch (msg.what) {
            case EVENT_REQUEST_NETWORK_TIMEOUT:
                onRequestNetworkTimeout();
                break;
            case EVENT_CLEAR_ERROR_CAUSE:
                mErrorCause = 0;
                break;
            case EVENT_DELAY_RELEASE_NETWORK:
                if (mRequstNum == 0 && mNetwork != null) {
                    onReleaseNetworkRequest();
                }
                break;
            case EVENT_SET_XCAP_IP_ADDR_DONE:
                onRequestNetworkDone();
                break;
            default:
                break;
            }
        }
    }

    public void requestNetwork(int subId, Message result) {
        Log.d(TAG, "requestNetwork mRequstNum = " + mRequstNum + ", mErrorCause = " + mErrorCause);
        if (mHandler.hasMessages(EVENT_DELAY_RELEASE_NETWORK)) {
            mHandler.removeMessages(EVENT_DELAY_RELEASE_NETWORK);
        }
        infRequestNum();
        if (mNetwork != null) {
            Log.d(TAG, "requestNetwork mNetwork is ready");
            sendRequestNetworkResult(result);
            return;
        }
        /*: bug664629 do not wait data connection under 2G call @{*/
        int networkType = mTm.getNetworkType(subId);
        int callState = mTm.getCallState(subId);
        if (mTm.getNetworkClass(networkType) == TelephonyManager.NETWORK_CLASS_2_G &&
                callState != TelephonyManager.CALL_STATE_IDLE) {
            Log.d(TAG, "there have phone call on subid" + subId + " under 2G network");
            sendRequestNetworkResult(result);
            return;
        }
        /* @} */

        //Unisoc: Add for bug 1057641 & 1082942 & 1113342  [begin]
        ServiceState ss = mTm.getServiceStateForSubscriber(subId);
        if (ss == null) {
            Log.d(TAG, "requestNetwork, ServiceState is null!");
            sendRequestNetworkResult(result);
            return;
        }

        int dataRegState = ss.getDataRegState();
        int voiceRegState = ss.getState();
        Log.d(TAG, "requestNetwork, voiceRegState : " + voiceRegState + ", dataRegState = " + dataRegState);
        if (voiceRegState != ServiceState.STATE_IN_SERVICE && dataRegState != ServiceState.STATE_IN_SERVICE) {
            sendRequestNetworkResult(result);
            return;
        }
        //Unisoc: Add for bug 1057641 & 1082942  [end]

        int phoneCount = mTm.getPhoneCount();
        int phoneId = SubscriptionManager.getPhoneId(subId);
        if(phoneId != SubscriptionManager.INVALID_PHONE_INDEX) {
            for (int i = 0; i < phoneCount; i++) {
                if (i != phoneId && mTm.getCallStateForSlot(i) != TelephonyManager.CALL_STATE_IDLE) {
                    Log.d(TAG, "other phone in calling!");
                    sendRequestNetworkResult(result);
                    return;
                }
            }
        }
        if (SubscriptionManager.isValidSubscriptionId(subId) &&
                //request xcap network time out, not do it again in 60s
                mErrorCause != ERROR_CAUSE_REQUEST_NETWORK_TIMEOUT) {
            mPendingMessages.add(result);
            synchronized (mLock) {
                if (mCm != null && mNetworkRequest == null
                        && mNetworkCallback == null) {
                    //Unisoc: Add for bug 1057641
                    startListenDataConn(subId);

                    mNetworkRequest = new NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_XCAP)
                        .setNetworkSpecifier(String.valueOf(subId))
                    .   build();
                    mNetworkCallback = new NetworkRequestCallback(subId);
                    mCm.requestNetwork(mNetworkRequest, mNetworkCallback);
                    mHandler.sendMessageDelayed(mHandler.obtainMessage(EVENT_REQUEST_NETWORK_TIMEOUT),
                            NETWORK_REQUEST_TIMEOUT_MILLIS);
                }
            }
        } else {
            sendRequestNetworkResult(result);
        }
    }

    public void releaseNetworkRequest() {
        Log.d(TAG, "releaseNetworkRequest mRequestNum = " + mRequstNum);
        decRequestNum();
        if(!mTimerExpire) return;
        if (mRequstNum == 0 && mNetwork != null) {
            if (mHandler.hasMessages(EVENT_DELAY_RELEASE_NETWORK)) {
                mHandler.removeMessages(EVENT_DELAY_RELEASE_NETWORK);
            }
            mHandler.sendMessageDelayed(mHandler.obtainMessage(EVENT_DELAY_RELEASE_NETWORK),
                    RELEASE_NETWORK_DELAY_MILLIS);
        }
    }

    private void onReleaseNetworkRequest() {
        //Unisoc: Modify for coverity cid 181455
        synchronized (mLock) {
            mNetwork = null;
            mNetworkRequest = null;
            if (mNetworkCallback != null) {
                mCm.unregisterNetworkCallback(mNetworkCallback);
                mNetworkCallback = null;
            }
            //Unisoc: Add for bug 1057641
            stopListenDataConn();
        }
    }
    private void onRequestNetworkDone() {
        Iterator iterator = mPendingMessages.iterator();
        while(iterator.hasNext()) {
            Message msg = (Message)iterator.next();
            sendRequestNetworkResult(msg);
            iterator.remove();
        }
    }

    private void onRequestNetworkTimeout() {
        mErrorCause = ERROR_CAUSE_REQUEST_NETWORK_TIMEOUT;
        Iterator iterator = mPendingMessages.iterator();
        while(iterator.hasNext()) {
            Message msg = (Message)iterator.next();
            sendRequestNetworkResult(msg);
            iterator.remove();
        }
        onReleaseNetworkRequest();
        mHandler.sendMessageDelayed(mHandler.obtainMessage(EVENT_CLEAR_ERROR_CAUSE),
                CLEAR_ERROR_CAUSE_DELAY_MILLIS);
    }

    private void sendRequestNetworkResult(Message msg) {
        if (msg == null) {
            return;
        }
        AsyncResult.forMessage(msg);
        msg.sendToTarget();
    }

    private void infRequestNum() {
        synchronized (mLock) {
            mRequstNum++;
        }
    }

    private void decRequestNum() {
        synchronized (mLock) {
            if(mRequstNum > 0){
                mRequstNum--;
            }
        }
    }

    private void setXcapIPAddress(int subId, Network network) {
        String IPV4AddrString = null;
        String IPV6AddrString = null;
        String interfaceName = null;
        if (network != null) {
            LinkProperties lp= mCm.getLinkProperties(network);
            if (lp != null) {
                InetAddress tmp = null;
                List<LinkAddress> addresses = lp.getLinkAddresses();
                for (int i = 0; i < addresses.size(); i++) {
                    tmp = addresses.get(i).getAddress();
                    if (tmp instanceof Inet4Address) {
                        IPV4AddrString = tmp.getHostAddress();
                    } else if(tmp instanceof Inet6Address) {
                        IPV6AddrString = tmp.getHostAddress();
                    }
                }
                interfaceName = lp.getInterfaceName();
            } else {
                Log.d(TAG, "linkProperties is null");
            }
        }
        RadioInteractor ri = new RadioInteractor(mContext);
        int phoneId = SubscriptionManager.getPhoneId(subId);
        //Unisoc: Modify for bug#1188909
        if (ri != null && interfaceName != null && phoneId != SubscriptionManager.INVALID_PHONE_INDEX) {
            ri.setXcapIPAddress(interfaceName, IPV4AddrString, IPV6AddrString,
                   mHandler.obtainMessage(EVENT_SET_XCAP_IP_ADDR_DONE), phoneId);
        }
    }

    class NetworkRequestCallback extends ConnectivityManager.NetworkCallback {
        private int mSubId;
        public NetworkRequestCallback(int subId) {
            mSubId = subId;
        }
        @Override
        public void onAvailable(Network network) {
            if (network != null) {
                Log.d(TAG, "onAvailable");
                mNetwork = network;
                if (mHandler.hasMessages(EVENT_REQUEST_NETWORK_TIMEOUT)) {
                    mHandler.removeMessages(EVENT_REQUEST_NETWORK_TIMEOUT);
                }
                setXcapIPAddress(mSubId, mNetwork);
                if(isEENetWork(mTm.getNetworkOperator(mSubId)) && mTimerExpire){
                    TimerTask task = new TimerTask() {
                        public void run() {
                            Log.d(TAG, "timer expire");
                            mTimerExpire = true;
                            if(mRequstNum == 0){
                                releaseNetworkRequest();
                            }
                        }
                    };
                    mTimerExpire = false;
                    Log.d(TAG, "start timer");
                    Timer timer = new Timer();
                    timer.schedule(task, TIMER_EXPIRE_MILLIS);
                }
            }
        }

        @Override
        public void onLost(Network network) {
            Log.d(TAG, "onLost");
            onReleaseNetworkRequest();
        }
    }

    private boolean isEENetWork(String Operator){
        Log.d(TAG, "isEENetWork Operator = " + Operator);
        if(!Operator.isEmpty()){
            if(Operator.equals("23430") || Operator.equals("23433")){
                return true;
            }
        }
        return false;
    }

    //Unisoc: Add for bug 1057641 [begin]
    private int mTryCount = 0;
    //Unisoc: Modify for bug# 1161094
    private static final int TRY_TIME = 3;
    private PhoneStateListener mPhoneStateListener = null;

    private void startListenDataConn(int subId) {
        mTryCount = 0;
        Log.d(TAG, "startListenDataConn subId = " + subId);

        mPhoneStateListener = new PhoneStateListener() {
            /*
            1. check apn type if xcap or not
            2. if xcap, check data connection
            3. if disconnected, check fail case
            4. if fail case is permanent, release network request directly;
               or else, count for disconnected state, when mTryCount equals TRY_TIME, release request.
            * */
            @Override
            public void onPreciseDataConnectionStateChanged(PreciseDataConnectionState dataConnectionState) {
                int apnType = ApnSetting.TYPE_NONE;
                if (dataConnectionState != null) {
                    apnType = dataConnectionState.getDataConnectionApnTypeBitMask();
                }
                Log.d(TAG, "onPreciseDataConnectionStateChanged apnType = " + ApnSetting.getApnTypesStringFromBitmask(apnType));

                if(apnType == ApnSetting.TYPE_XCAP) {
                    int dataState = dataConnectionState.getDataConnectionState();
                    Log.d(TAG, "onPreciseDataConnectionStateChanged dataState = " + dataState);

                    if(dataState == TelephonyManager.DATA_UNKNOWN || dataState == TelephonyManager.DATA_DISCONNECTED) {
                        //Unisoc: Modify for bug 1082942 & 1113342
                        Log.d(TAG, "onPreciseDataConnectionStateChanged subId = " + subId);
                        ServiceState ss = mTm.getServiceStateForSubscriber(subId);
                        if (ss == null) {
                            Log.d(TAG, "onPreciseDataConnectionStateChanged ServiceState is null!");
                            releaseNetworkRequestForErrorService();
                            return;
                        }
                        int dataRegState = ss.getDataRegState();
                        int voiceRegState = ss.getState();
                        Log.d(TAG, "onPreciseDataConnectionStateChanged, voiceRegState : " + voiceRegState + ", dataRegState = " + dataRegState);
                        if (voiceRegState != ServiceState.STATE_IN_SERVICE && dataRegState != ServiceState.STATE_IN_SERVICE) {
                            releaseNetworkRequestForErrorService();
                            return;
                        }

                        int failCause = dataConnectionState.getDataConnectionFailCause();
                        Log.d(TAG, "onPreciseDataConnectionStateChanged failCause = " + DataFailCause.toString(failCause));

                        if(DataFailCause.NONE == failCause) {
                            return;
                        }

                         mTryCount++;

                        Log.d(TAG, "onPreciseDataConnectionStateChanged ,mTryCount = " + mTryCount);

                        if(DataFailCause.isPermanentFailure(mContext, DataFailCause.getFailCause(failCause), subId) || (mTryCount == TRY_TIME)){
                            tryToReleaseNetworkRequest();
                        }
                    }
                }

            }
        };
        mTm.listen(mPhoneStateListener, PhoneStateListener.LISTEN_PRECISE_DATA_CONNECTION_STATE);
    }

    /*Reuse timeout logic:
    1. if mErrorCause is ERROR_CAUSE_REQUEST_NETWORK_TIMEOUT, do not request network again
    2. clear mErrorCause after 60S

    To resolve the issue: request network again in 60S
    * */
    private void tryToReleaseNetworkRequest() {
        Log.d(TAG, "tryToReleaseNetworkRequest, reuse timeout logic!");
        onRequestNetworkTimeout();
    }

    private void stopListenDataConn(){
        Log.d(TAG, "stopListenDataConn, mPhoneStateListener = " + mPhoneStateListener);
        if(mPhoneStateListener != null) {
            mTm.listen(mPhoneStateListener, PhoneStateListener.LISTEN_NONE);
            mTryCount = 0;
            mPhoneStateListener = null;
        }
    }
    //Unisoc: Add for bug 1057641 [end]

    //Unisoc: Modify for bug#1113342
    private void releaseNetworkRequestForErrorService(){
        Log.d(TAG, "releaseNetworkRequestForErrorService");
        Iterator iterator = mPendingMessages.iterator();
        while (iterator.hasNext()) {
            Message msg = (Message) iterator.next();
            sendRequestNetworkResult(msg);
            iterator.remove();
        }
        onReleaseNetworkRequest();
    }
}
