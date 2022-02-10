
package com.spreadtrum.ims;


import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import android.telephony.NetworkService;
import android.telephony.NetworkService.NetworkServiceProvider;
import android.telephony.NetworkServiceCallback;
import android.telephony.TelephonyManager;
import android.telephony.NetworkRegistrationInfo;
import android.telephony.AccessNetworkConstants;
import com.android.ims.ImsManager;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SdkConstant;
import android.annotation.SystemApi;
import android.app.Service;
import android.content.Intent;
import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.telephony.NetworkRegistrationInfo.Domain;
import android.util.SparseArray;
import android.util.Log;

import com.android.ims.internal.IImsServiceEx;
import com.android.ims.internal.IImsRegisterListener;
import com.android.ims.internal.ImsManagerEx;

import android.os.ServiceManager;
import android.os.SystemProperties;

import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.List;

public class IwlanNetworkService extends NetworkService {

    private final String TAG = IwlanNetworkService.class.getSimpleName();
    private final static int IWLAN_REGISTRATION_STATE_CHANGED =   1001;


    private final NetworkServiceHandler mHandler = new NetworkServiceHandler();

    private Map<Integer, IwlanNetworkServiceProvider> mIwlanProviderMap = new HashMap<Integer, IwlanNetworkServiceProvider>();

    private TelephonyManager mTelephonyManager;
    private int mPhoneCount = 2;
    private boolean[] mIsIwlanRegistered;
    private boolean mIsImsListenerRegistered;
    private IImsServiceEx mIImsServiceEx;

    /**
     * Default constructor.
     */
    public IwlanNetworkService() {
        super();
    }

    @Override
    public void onCreate() {
        log("IWLAN Service onCreate.");
        super.onCreate();
        mTelephonyManager = (TelephonyManager)getApplicationContext().getSystemService(Context.TELEPHONY_SERVICE);
        mPhoneCount = mTelephonyManager.getPhoneCount();
        mIsIwlanRegistered = new boolean[mPhoneCount];
        for(int i = 0; i < mPhoneCount; i++){
            mIwlanProviderMap.put(i, new IwlanNetworkServiceProvider(i));
            mIsIwlanRegistered[i] = false;
        }
        tryRegisterImsListener();
        IntentFilter filter = new IntentFilter();
        filter.addAction(ImsManager.ACTION_IMS_SERVICE_UP);
        getApplicationContext().registerReceiver(mImsIntentReceiver, filter);
    }

    private BroadcastReceiver mImsIntentReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                tryRegisterImsListener();
            }
        };

    /**
     * The abstract class of the actual network service implementation. The network service provider
     * must extend this class to support network connection. Note that each instance of network
     * service is associated with one physical SIM slot.
     */
    class IwlanNetworkServiceProvider extends NetworkServiceProvider {
        ImsManager mImsManager;

        /**
         * Constructor
         * @param slotIndex SIM slot id the data service provider associated with.
         */
        public IwlanNetworkServiceProvider(int slotIndex) {
            super(slotIndex);
            mImsManager = ImsManager.getInstance(getApplicationContext(), slotIndex);
        }

        /**
         * Request network registration info. The result will be passed to the callback.
         *
         * @param domain Network domain
         * @param callback The callback for reporting network registration info
         */
        @Override
        public void requestNetworkRegistrationInfo(@Domain int domain,
                                                   @NonNull NetworkServiceCallback callback) {
            log("requestNetworkRegistrationInfo,domain:"+domain +" callback:"+callback);
            List<Integer> availableServices = new ArrayList<>();
            availableServices.add(NetworkRegistrationInfo.SERVICE_TYPE_VOICE);
            availableServices.add(NetworkRegistrationInfo.SERVICE_TYPE_SMS);
            availableServices.add(NetworkRegistrationInfo.SERVICE_TYPE_EMERGENCY);
            if(mImsManager != null && mImsManager.isVtEnabledByUser()
               && mImsManager.isVtEnabledByPlatform()){
                availableServices.add(NetworkRegistrationInfo.SERVICE_TYPE_VIDEO);
            }
            NetworkRegistrationInfo info = new NetworkRegistrationInfo.Builder()
                .setTransportType(AccessNetworkConstants.TRANSPORT_TYPE_WLAN)
                .setAccessNetworkTechnology(TelephonyManager.NETWORK_TYPE_IWLAN)
                .setAvailableServices(availableServices)
                .setRegistrationState(mIsIwlanRegistered[getSlotIndex()] ?
                                      NetworkRegistrationInfo.REGISTRATION_STATE_HOME :
                                      NetworkRegistrationInfo.REGISTRATION_STATE_NOT_REGISTERED_OR_SEARCHING)
                .setDomain(NetworkRegistrationInfo.DOMAIN_PS).build();
            callback.onRequestNetworkRegistrationInfoComplete(NetworkServiceCallback.RESULT_SUCCESS, info);
        }

        /**
         * Called when the instance of network service is destroyed (e.g. got unbind or binder died)
         * or when the network service provider is removed. The extended class should implement this
         * method to perform cleanup works.
         */
        @Override
        public void close(){
            log("Provider:"+getSlotIndex()+" close!");
        }
    }

    private IImsRegisterListener.Stub  mImsListenerBinder = new IImsRegisterListener.Stub() {
            @Override
            public void imsRegisterStateChange(boolean isRegistered) {
                for(int i = 0; i < mPhoneCount; i++){
                    boolean isVoWifiRegistered  = isVoWiFiRegisteredForPhone(i);
                    if(mIsIwlanRegistered[i] != isVoWifiRegistered){
                        log("imsRegisterStateChange, slot:"+i+" vowifi:"+isVoWifiRegistered);
                        mIsIwlanRegistered[i] = isVoWifiRegistered;
                        mHandler.sendMessage(mHandler.obtainMessage(
                                 IWLAN_REGISTRATION_STATE_CHANGED, i , -1));
                    }
                }
            }
        };

    private synchronized void tryRegisterImsListener() {
        mIImsServiceEx = ImsManagerEx.getIImsServiceEx();
        if(mIImsServiceEx != null){
            try{
                if(!mIsImsListenerRegistered){
                    mIsImsListenerRegistered = true;
                    mIImsServiceEx.registerforImsRegisterStateChanged(mImsListenerBinder);
                }
            } catch (RemoteException e){
                Log.e(TAG, "regiseterforImsException: "+ e);
            }
        }
    }

    private synchronized void tryUnregisterImsListener() {
        try{
            if(mIsImsListenerRegistered){
                mIsImsListenerRegistered = false;
                mIImsServiceEx.unregisterforImsRegisterStateChanged(mImsListenerBinder);
            }
        }catch(RemoteException e){
            Log.e(TAG, "finalize: " + e);
        }
    }
    public boolean isVoWiFiRegisteredForPhone(int phoneId) {
        boolean isVoWifiRegistered  = false;
        String voWifiState = SystemProperties.get("gsm.sys.vowifi.state");
        if(voWifiState != null){
            isVoWifiRegistered = (phoneId == 0 ? voWifiState.startsWith("1")
                                   : ( phoneId == 1 ? voWifiState.endsWith(",1") : false));
        }
        return isVoWifiRegistered;
    }

    private class NetworkServiceHandler extends Handler {
        @Override
        public void handleMessage(Message message) {
            log("handleMessage:"+message.what);
            switch (message.what) {
                case IWLAN_REGISTRATION_STATE_CHANGED: {
                    int slotIndex = message.arg1;
                    notifyVoWiFiRegistrationChanged(slotIndex);
                    }
                    break;
                default:
                    break;
            }
        }
    }

    public void notifyVoWiFiRegistrationChanged (int slotIndex){
        if(slotIndex < 0 || slotIndex >= mPhoneCount){
            log("notifyVoWiFiRegistrationChanged, invalid slotIndex:"+slotIndex);
            return;
        }
        log("notifyVoWiFiRegistrationChanged, valid slotIndex:"+slotIndex);
        IwlanNetworkServiceProvider provider = mIwlanProviderMap.get(new Integer(slotIndex));
        provider.notifyNetworkRegistrationInfoChanged();
    }


    /**
     * Create the instance of {@link NetworkServiceProvider}. Network service provider must override
     * this method to facilitate the creation of {@link NetworkServiceProvider} instances. The system
     * will call this method after binding the network service for each active SIM slot id.
     *
     * @param slotIndex SIM slot id the network service associated with.
     * @return Network service object. Null if failed to create the provider (e.g. invalid slot
     * index)
     */
    @Nullable
    public NetworkServiceProvider onCreateNetworkServiceProvider(int slotIndex){
        if(slotIndex < 0 || slotIndex >= mPhoneCount){
            log("onCreateNetworkServiceProvider, invalid slotIndex:"+slotIndex);
            return null;
        }
        log("onCreateNetworkServiceProvider, valid slotIndex:"+slotIndex);
        return mIwlanProviderMap.get(new Integer(slotIndex));
    }

    @Override
    public IBinder onBind(Intent intent) {
        return super.onBind(intent);
    }

    @Override
    public boolean onUnbind(Intent intent) {
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        tryUnregisterImsListener();
        getApplicationContext().unregisterReceiver(mImsIntentReceiver);
    }

    private final void log(String s) {
        Log.d(TAG, s);
    }

    private final void loge(String s) {
        Log.e(TAG, s);
    }
}
