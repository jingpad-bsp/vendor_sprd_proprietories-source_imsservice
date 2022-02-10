package com.spreadtrum.ims.vowifi;

import android.content.Context;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.telephony.SmsManager;
import android.telephony.SmsManagerEx;
import android.telephony.ims.stub.ImsSmsImplBase;
import android.text.TextUtils;
import android.util.Log;

import com.android.ims.internal.IVoWifiSms;
import com.android.ims.internal.IVoWifiSmsCallback;
import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.gsm.SmsMessage;
import com.android.internal.telephony.uicc.IccUtils;
import com.android.sprd.telephony.RadioInteractor;

import com.spreadtrum.ims.vowifi.Utilities.JSONUtils;
import com.spreadtrum.ims.vowifi.Utilities.RegisterState;
import com.spreadtrum.ims.vowifi.Utilities.Result;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Objects;

public class VoWifiSmsManager extends ServiceManager {
    public interface ISmsChangedListener {
        public void onChanged(IVoWifiSms newInterface);
    }

    private static final String TAG = Utilities.getTag(VoWifiSmsManager.class.getSimpleName());

    private Context mContext;
    private IVoWifiSms mISms;
    private MySmsCallback mSmsCallback = new MySmsCallback();
    private ArrayList<Sms> mSmsList= new ArrayList<Sms>();
    private HashMap<Integer, ImsSmsImpl> mSmsImpls = new HashMap<Integer, ImsSmsImpl>();

    private static final int GET_TPMR_TIMEOUT = 5 * 1000; // 5s

    private static final int MSG_HANDLE_EVENT = 1;
    private static final int MSG_GET_TPMR = 2;
    private static final int MSG_SET_TPMR = 3;
    private static final int MSG_GET_TPMR_TIMEOUT = 4;
    private class MyHandler extends Handler {
        public MyHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_HANDLE_EVENT:
                    handleEvent((String) msg.obj);
                    break;
                case MSG_GET_TPMR: {
                    Log.d(TAG, "Handle the get Tp-Mr result now.");
                    mHandler.removeMessages(MSG_GET_TPMR_TIMEOUT);

                    ImsSmsImpl smsImpl = (ImsSmsImpl) getSmsImplementation(msg.arg1);
                    AsyncResult ar = (AsyncResult) msg.obj;
                    if (smsImpl != null && ar != null) {
                        Sms sms = (Sms) ar.userObj;
                        smsImpl.onGetTpMr(sms, ar);
                    }
                    break;
                }
                case MSG_SET_TPMR: {
                    Log.d(TAG, "Set Tp-Mr finished, ar: " + ((AsyncResult) msg.obj));
                    break;
                }
                case MSG_GET_TPMR_TIMEOUT: {
                    ImsSmsImpl smsImpl = (ImsSmsImpl) getSmsImplementation(msg.arg1);
                    if (smsImpl != null) {
                        smsImpl.onGetTpMrError((Sms) msg.obj);
                    }
                    break;
                }
            }
        }
    }

    protected VoWifiSmsManager(Context context) {
        this(context, Utilities.SERVICE_PACKAGE, Utilities.SERVICE_CLASS_SMS,
                Utilities.SERVICE_ACTION_SMS);
    }

    protected VoWifiSmsManager(Context context, String pkg, String cls, String action) {
        super(context, pkg, cls, action);

        mContext = context;

        HandlerThread thread = new HandlerThread("SmsManager");
        thread.start();
        Looper looper = thread.getLooper();
        mHandler = new MyHandler(looper);
    }

    @Override
    protected void onNativeReset() {
        mISms = null;
        mSmsList.clear();
    }

    @Override
    protected void onServiceChanged() {
        try {
            mISms = null;
            if (mServiceBinder != null) {
                mISms = IVoWifiSms.Stub.asInterface(mServiceBinder);
                mISms.registerCallback(mSmsCallback);
            } else {
                clearPendingList();
            }
        } catch (RemoteException ex) {
            Log.e(TAG, "Can not register callback as catch the RemoteException. ex: " + ex);
        }
    }

    public ImsSmsImplBase getSmsImplementation(int phoneId) {
        ImsSmsImpl smsImpl = null;
        if (mSmsImpls.size() < 1) {
            smsImpl = new ImsSmsImpl(phoneId);
            mSmsImpls.put(Integer.valueOf(phoneId), smsImpl);
        } else {
            smsImpl = mSmsImpls.get(Integer.valueOf(phoneId));
            if (smsImpl == null) {
                smsImpl = new ImsSmsImpl(phoneId);
                mSmsImpls.put(Integer.valueOf(phoneId), smsImpl);
            }
        }

        return smsImpl;
    }

    private void handleEvent(String json) {
        // As the VOWIFI enabled on the primary card. So handle the events on the primary card.
        ImsSmsImpl smsImpl = mSmsImpls.get(Integer.valueOf(Utilities.getPrimaryCard(mContext)));
        if (smsImpl == null || !smsImpl.mReady) {
            Log.e(TAG, "Can not handle the sms event now! SmsImpl is null or do not ready.");
            return;
        }

        try {
            JSONObject jObject = new JSONObject(json);
            String eventName = jObject.optString(JSONUtils.KEY_EVENT_NAME, "");
            Log.d(TAG, "Handle the event '" + eventName + "'.");

            int eventCode = jObject.optInt(JSONUtils.KEY_EVENT_CODE, -1);
            switch (eventCode) {
                case JSONUtils.EVENT_CODE_SMS_SEND_FINISHED: {
                    int token = jObject.optInt(JSONUtils.KEY_SMS_TOKEN);
                    boolean success = jObject.optBoolean(JSONUtils.KEY_SMS_RESULT);
                    int sendStatus = ImsSmsImplBase.SEND_STATUS_OK;
                    int errorCode = SmsManager.RESULT_ERROR_NONE;

                    Sms sms = findSmsByToken(token);
                    if (sms == null) {
                        Log.e(TAG, "Failed to find the sms by the token: " + token);
                        break;
                    }

                    if (success) {
                        sms._status = Sms.STATUS_SEND_FINISHED;
                        if (!sms._requireStatusReport) {
                            mSmsList.remove(sms);
                        }
                    } else {
                        sendStatus = ImsSmsImplBase.SEND_STATUS_ERROR_FALLBACK;
                        errorCode = jObject.optInt(JSONUtils.KEY_SMS_REASON);
                        // Handle as error and it will fallback to CS, remove from the map.
                        mSmsList.remove(new Sms(token));
                    }
                    smsImpl.onSendSmsResult(token, sms._messageRef, sendStatus, errorCode);
                    break;
                }
                case JSONUtils.EVENT_CODE_SMS_RECEIVED: {
                    String pduHexString = jObject.optString(JSONUtils.KEY_SMS_PDU);
                    if (TextUtils.isEmpty(pduHexString)) {
                        Log.e(TAG, "Failed to notify sms received as pdu is empty.");
                        return;
                    }

                    int token = generateToken();
                    byte[] pdu = IccUtils.hexStringToBytes(pduHexString);
                    SmsMessage smsMsg = SmsMessage.newFromCDS(pdu);
                    Sms sms = new Sms(token, smsMsg.mMessageRef, Sms.DIR_RECEIVED,
                            Sms.STATUS_RECEIVED, pdu);
                    mSmsList.add(sms);
                    smsImpl.onSmsReceived(token, smsImpl.getSmsFormat(), pdu);
                    break;
                }
                case JSONUtils.EVENT_CODE_SMS_STATUS_REPORT_RECEIVED: {
                    String pduHexString = jObject.optString(JSONUtils.KEY_SMS_PDU);

                    if (TextUtils.isEmpty(pduHexString)) {
                        Log.e(TAG, "Failed to notify sms received as pdu is empty.");
                        return;
                    }

                    byte[] pdu = IccUtils.hexStringToBytes(pduHexString);
                    SmsMessage smsMsg = SmsMessage.newFromCDS(pdu);
                    Sms sms = findSmsByMessageRef(smsMsg.mMessageRef);
                    int token = -1;
                    if (sms == null) {
                        Log.w(TAG, "Do not send the sms before, please check the messageRef: "
                                + smsMsg.mMessageRef);
                        token = generateToken();
                    } else {
                        token = sms._token;
                        sms._status = Sms.STATUS_REPORT_RECEIVED;
                    }
                    smsImpl.onSmsStatusReportReceived(
                            token, smsMsg.mMessageRef, smsImpl.getSmsFormat(), pdu);
                    break;
                }
            }
        } catch (JSONException e) {
            Log.e(TAG, "As catch the exception, failed to handle the event for the json: " + json);
        }
    }

    private int generateToken() {
        String tokenStr = String.valueOf(System.currentTimeMillis());
        return Integer.valueOf(tokenStr.substring(4));
    }

    private Sms findSmsByToken(int token) {
        return findSms(Sms.FLAG_TOKEN, token);
    }

    private Sms findSmsByMessageRef(int messageRef) {
        return findSms(Sms.FLAG_MSGREF, messageRef);
    }

    private Sms findSms(int flag, int value) {
        if (mSmsList == null || mSmsList.size() < 1) {
            // There isn't any sms, return null.
            return null;
        }

        for (Sms sms : mSmsList) {
            int compareValue = -1000;
            switch (flag) {
                case Sms.FLAG_TOKEN:
                    compareValue = sms._token;
                    break;
                case Sms.FLAG_MSGREF:
                    compareValue = sms._messageRef;
                    break;
            }
            if (compareValue == value) {
                return sms;
            }
        }

        // Do not find the matched sms, return null.
        return null;

    }

    public void updateRegisterState(int state) {
        int priCard = Utilities.getPrimaryCard(mContext);
        ImsSmsImpl smsImpl = (ImsSmsImpl)getSmsImplementation(priCard);
        smsImpl.mReady = state == RegisterState.STATE_CONNECTED;
        if (Utilities.DEBUG) {
            Log.d(TAG, "Update primary card[" + priCard + "] mReady to " + smsImpl.mReady);
        }
    }

    private class ImsSmsImpl extends ImsSmsImplBase {

        private int mPhoneId;
        private boolean mReady = false;
        private RadioInteractor mRadioInteractor;

        protected ImsSmsImpl(int phoneId) {
            mPhoneId = phoneId;
            mRadioInteractor = new RadioInteractor(mContext);
            if (Utilities.DEBUG) Log.i(TAG, "new ImsSmsImpl[" + phoneId + "]");
        }

        @Override
        public void acknowledgeSms(int token, int messageRef, int result) {
            if (Utilities.DEBUG) {
                Log.i(TAG, "Acknowledge the sms: token[" + token + "], messageRef[" + messageRef
                        + "], result[" + result + "]");
            }

            if (!mReady || mISms == null) {
                Log.e(TAG, "Failed to acknowledge sms as mReady: " + mReady + ", mISms: " + mISms);
                onSendSmsResult(token, messageRef, ImsSmsImplBase.SEND_STATUS_ERROR_FALLBACK,
                        SmsManager.RESULT_ERROR_NO_SERVICE);
                mSmsList.remove(new Sms(token));
                return;
            }

            try {
                int id = mISms.acknowledgeSms(token, messageRef, getCauseFromResult(result));
                if (id != Result.INVALID_ID) {
                    mSmsList.remove(new Sms(token));
                    return;
                }
            } catch (RemoteException ex) {
                Log.e(TAG, "Failed to acknowledge the sms as catch the ex: " + ex);
            }

            // It means ACK failed, handle it as retry.
            onSendSmsResult(token, messageRef, ImsSmsImplBase.SEND_STATUS_ERROR_FALLBACK,
                    SmsManager.RESULT_ERROR_GENERIC_FAILURE);
        }

        @Override
        public void acknowledgeSmsReport(int token, int messageRef, int result) {
            if (Utilities.DEBUG) {
                Log.i(TAG, "Acknowledge the sms report: token[" + token + "], messageRef["
                        + messageRef + "], result[" + result + "]");
            }

            if (!mReady || mISms == null) {
                Log.e(TAG, "Failed to acknowledge sms report as mReady: " + mReady
                        + ", mISms: " + mISms);
                onSendSmsResult(token, messageRef, ImsSmsImplBase.SEND_STATUS_ERROR_FALLBACK,
                        SmsManager.RESULT_ERROR_NO_SERVICE);
                mSmsList.remove(new Sms(token));
                return;
            }

            try {
                int id = mISms.acknowledgeSmsReport(token, messageRef, getCauseFromResult(result));
                if (id != Result.INVALID_ID) {
                    mSmsList.remove(new Sms(token));
                    return;
                }
            } catch (RemoteException ex) {
                Log.e(TAG, "Failed to acknowledge the sms report as catch the ex: " + ex);
            }

            // It means ACK failed, handle it as retry.
            onSendSmsResult(token, messageRef, ImsSmsImplBase.SEND_STATUS_ERROR_FALLBACK,
                    SmsManager.RESULT_ERROR_GENERIC_FAILURE);
        }

        @Override
        public void onReady() {
            mReady = true;
        }

        @Override
        public void sendSms(int token, int messageRef, String format, String smsc, boolean retry,
                byte[] pdu) {
            if (Utilities.DEBUG) {
                Log.i(TAG, "Send the sms: token[" + token + "], messageRef[" + messageRef
                        + "], smsc[" + smsc + "], retry[" + retry + "], pdu["
                        + Arrays.toString(pdu) + "]");
            }

            if (!mReady || mISms == null || mRadioInteractor == null) {
                Log.e(TAG, "Failed to send sms as mReady: " + mReady + ", mISms: " + mISms
                        + ", mRadioInteractor: " + mRadioInteractor);
                onSendSmsResult(token, messageRef, ImsSmsImplBase.SEND_STATUS_ERROR_FALLBACK,
                        SmsManager.RESULT_ERROR_NO_SERVICE);
                return;
            }

            // Get the TP-MR from SIM, after get the response, send the SMS actually.
            Sms sms = new Sms(
                    token, messageRef, Sms.STATUS_SEND, Sms.DIR_SEND, pdu);
            Message getTpMr = mHandler.obtainMessage(MSG_GET_TPMR, mPhoneId, token, sms);
            mRadioInteractor.getTPMRState(getTpMr, mPhoneId);

            Message timeout = mHandler.obtainMessage(MSG_GET_TPMR_TIMEOUT, mPhoneId, token, sms);
            mHandler.sendMessageDelayed(timeout, GET_TPMR_TIMEOUT);
        }

        private int getCauseFromResult(int result) {
            switch (result) {
                case ImsSmsImplBase.STATUS_REPORT_STATUS_OK:
                    // Cause code is ignored if ok.
                    return 0;
                case ImsSmsImplBase.DELIVER_STATUS_ERROR_NO_MEMORY:
                    return CommandsInterface.GSM_SMS_FAIL_CAUSE_MEMORY_CAPACITY_EXCEEDED;
                default:
                    return CommandsInterface.GSM_SMS_FAIL_CAUSE_UNSPECIFIED_ERROR;
            }
        }

        private void onGetTpMr(Sms sms, AsyncResult ar) {
            if (sms == null || ar.exception != null || ar.result == null) {
                Log.e(TAG, "Error to get the TP-MR. sms: " + sms + ", exception: " + ar.exception
                        + ", TP-MR: " + ar.result);
                onGetTpMrError(sms);
                return;
            }

            int[] tpmr = (int[]) ar.result;
            // if SMS over IP of Vowifi still can be used,use the TpMr value for SMS over IP:
            Log.d(TAG, "Get the TP-MR from SIM, current TP-MR: " + tpmr[0]);
            if ((tpmr[0] < 0) || (tpmr[0] >= 255)) {
                sms._messageRef = 0;
                sms._pdu[1] = (byte) 0;
            } else {
                sms._messageRef = tpmr[0] + 1;
                sms._pdu[1] = (byte) sms._messageRef;
            }

            // Send the SMS with the actually TP-MR.
            if (sendSmsInternal(sms)) {
                Log.d(TAG, "Start send sms process, set the new Tp-Mr to SIM.");
                if (mRadioInteractor != null){
                    Message setTpMr = mHandler.obtainMessage(MSG_SET_TPMR);
                    mRadioInteractor.setTPMRState(sms._messageRef, setTpMr, mPhoneId);
                }
            } else {
                Log.e(TAG, "Give the failed callback as need retry to send the sms: " + sms);
                onSendSmsError(sms, ImsSmsImplBase.SEND_STATUS_ERROR_FALLBACK);
            }
        }

        private void onGetTpMrError(Sms sms) {
            onSendSmsError(sms, ImsSmsImplBase.SEND_STATUS_ERROR_FALLBACK);
        }

        private void onSendSmsError(Sms sms, int status) {
            if (sms == null) return;

            Log.e(TAG, "Error to send the sms: " + sms);
                // Handle it as send SMS failed and try to fallback.
            onSendSmsResult(
                    sms._token, sms._messageRef, status, SmsManager.RESULT_ERROR_GENERIC_FAILURE);
        }

        private boolean sendSmsInternal(Sms sms) {
            try {
                // FIXME: As normal, it should use IccUtils.bytesToHexString, and the smsc number
                // could get as this:
                // byte[] smscByte = IccUtils.hexStringToBytes(smsc);
                // int length = smscByte[0];
                // String numberSMSC = PhoneNumberUtils.calledPartyBCDToString(smscByte, 1, length);

                // But the smsc string will be create as "new String(smsc)" by
                // {@link ImsSmsDispatcher}, so we'd like to get the smsc from SmsManagerEx now.
                int subId = Utilities.getSubId(mPhoneId);
                String numberSMSC = SmsManagerEx.getDefault().getSmscForSubscriber(subId);
                int id = mISms.sendSms(sms._token, sms._messageRef,
                        0 /* Retry will be handled by framework, native needn't retry. */,
                        numberSMSC, IccUtils.bytesToHexString(sms._pdu));
                if (id != Result.INVALID_ID) {
                    Log.d(TAG, "Sent the sms, smsc: " + numberSMSC + ", Tp-Mr: " + sms._messageRef
                            + ", requireStatusReport: " + sms._requireStatusReport);
                    sms._id = id;
                    mSmsList.add(sms);
                    return true;
                }
            } catch (RemoteException ex) {
                Log.e(TAG, "Failed to send the sms as catch the ex: " + ex);
            }

            return false;
        }
    }

    private static class Sms {
        // TP-Message-Type-Indicator: TP-Status-Report-Request bit.
        public static final int INDICATOR_STATUS_REPORT_REQUEST = 0x20;

        public static final int FLAG_TOKEN = 1;
        public static final int FLAG_MSGREF = 2;

        public static final int DIR_SEND = 1;
        public static final int DIR_RECEIVED = 2;

        public static final int STATUS_SEND = 1;
        public static final int STATUS_SEND_FINISHED = 2;
        public static final int STATUS_REPORT_RECEIVED = 3;

        public static final int STATUS_RECEIVED = -1;

        public int _token;
        public int _messageRef;
        public int _status;
        public int _dir;
        public int _id;
        public byte[] _pdu;
        public boolean _requireStatusReport;

        public Sms(int token) {
            _token = token;
        }

        public Sms(int token, int messageRef, int dir, int status, byte[] pdu) {
            _token = token;
            _messageRef = messageRef;
            _status = status;
            _dir = dir;
            _pdu = pdu;
            _requireStatusReport = (pdu[0] & Sms.INDICATOR_STATUS_REPORT_REQUEST) > 0;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj instanceof Sms) {
                Sms sms = (Sms) obj;
                if (sms == this) {
                    return true;
                } else {
                    return sms._token == this._token;
                }
            } else {
                return false;
            }
        }

        @Override
        public int hashCode()  {
            return Objects.hash(_token, _messageRef, _status, _dir, _id, _requireStatusReport);
        }

        @Override
        public String toString() {
            return "Sms[token:" + _token + ", messageRef:" + _messageRef + ", dir:" + _dir
                    + ", status:" + _status + ", id:" + _id + ", _requireStatusReport:"
                    + _requireStatusReport + "]";
        }
    }

    private class MySmsCallback extends IVoWifiSmsCallback.Stub {
        @Override
        public void onEvent(String json) throws RemoteException {
            if (Utilities.DEBUG) Log.i(TAG, "Get the vowifi sms event callback: " + json);
            if (TextUtils.isEmpty(json)) {
                Log.e(TAG, "Can not handle the ser callback as the json is null.");
                return;
            }

            mHandler.sendMessage(mHandler.obtainMessage(MSG_HANDLE_EVENT, json));
        }
    }
}
