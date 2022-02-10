
package com.spreadtrum.ims.vowifi;

import android.content.Context;
import android.os.Message;
import android.os.RemoteException;
import android.text.TextUtils;
import android.util.Log;

import com.android.ims.internal.IVoWifiRegister;
import com.android.ims.internal.IVoWifiRegisterCallback;

import com.spreadtrum.ims.vowifi.Utilities.CellularNetInfo;
import com.spreadtrum.ims.vowifi.Utilities.JSONUtils;
import com.spreadtrum.ims.vowifi.Utilities.PendingAction;
import com.spreadtrum.ims.vowifi.Utilities.RegisterConfig;
import com.spreadtrum.ims.vowifi.Utilities.RegisterState;
import com.spreadtrum.ims.vowifi.Utilities.Result;
import com.spreadtrum.ims.vowifi.Utilities.VowifiNetworkType;

import org.json.JSONException;
import org.json.JSONObject;

public class VoWifiRegisterManager extends ServiceManager {
    private static final String TAG = Utilities.getTag(VoWifiRegisterManager.class.getSimpleName());

    public interface RegisterListener {
        void onLoginFinished(boolean success, int stateCode, int retryAfter);

        void onLogout(int stateCode);

        void onPrepareFinished(boolean success, boolean nativeCrash);

        /**
         * Refresh the registration result callback
         */
        void onRefreshRegFinished(boolean success, int errorCode);

        void onRegisterStateChanged(int newState, int errorCode);

        void onResetBlocked();

        void onDisconnected();
    }

    // Only handle less than 2 minutes retry-after error here.
    // TODO: handle the retry-after later.
    private static final int HANDLE_RETRY_AFTER_MILLIS = 0 /* 5 * 60 * 1000 */;

    // TODO: Request CNI timeout, handle the register go on or failed?
    private static final int REQUEST_CNI_TIMEOUT_MILLIS = 2 * 1000;

    private static final int MSG_ACTION_LOGIN = 1;
    private static final int MSG_ACTION_DE_REGISTER = 2;
    private static final int MSG_ACTION_RE_REGISTER = 3;
    private static final int MSG_ACTION_FORCE_STOP = 4;
    private static final int MSG_ACTION_PREPARE_FOR_LOGIN = 5;
    private static final int MSG_ACTION_REQUEST_CNI_TIMEOUT = 6;

    private RegisterRequest mRequest = null;
    private IVoWifiRegister mIRegister = null;
    private RegisterCallback mCallback = null;
    private String mRegPAssociatedUri = null;

    protected VoWifiRegisterManager(Context context) {
        this(context, Utilities.SERVICE_PACKAGE, Utilities.SERVICE_CLASS_REG,
                Utilities.SERVICE_ACTION_REG);
    }

    protected VoWifiRegisterManager(Context context, String pkg, String cls, String action) {
        super(context, pkg, cls, action);
        mCallback = new RegisterCallback(this);
    }

    @Override
    protected void onNativeReset() {
        Log.d(TAG, "The register service reset. Notify as the service disconnected.");
        // As the register service disconnected, we'd like to update the register
        // state and notify as service disconnected
        synchronized (this) {
            if (mRequest != null && mRequest.mListener != null) {
                mRequest.mListener.onDisconnected();
            }
            updateRegisterState(RegisterState.STATE_IDLE);

            mIRegister = null;
            mRequest = null;
        }
    }

    @Override
    protected void onServiceChanged() {
        try {
            mIRegister = null;
            if (mServiceBinder != null) {
                mIRegister = IVoWifiRegister.Stub.asInterface(mServiceBinder);
                mIRegister.registerCallback(mCallback);
            } else {
                clearPendingList();
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Can not register callback as catch the RemoteException. e: " + e);
        }
    }

    @Override
    protected boolean handleNormalMessage(Message msg) {
        if (msg != null && msg.what == MSG_ACTION_REQUEST_CNI_TIMEOUT) {
            // Handle request CNI timeout as go-on.
            synchronized (this) {
                if (mRequest != null) {
                    Log.w(TAG, "Do not get the CNI info now, handle as go-on register process.");
                    mRequest.mWaitForCNIResponse = false;
                    mRequest.mNeedPLCI = false;
                    mRequest.mNeedCNI = false;

                    if (mRequest.mListener != null) {
                        mRequest.mListener.onPrepareFinished(true, false);
                    }
                }
            }
            return true;
        }

        return super.handleNormalMessage(msg);
    }

    @Override
    protected boolean handlePendingAction(Message msg) {
        if (Utilities.DEBUG) Log.i(TAG, "Handle the pending action, msg: " + msg);

        boolean handle = false;
        switch (msg.what) {
            case MSG_ACTION_LOGIN: {
                PendingAction action = (PendingAction) msg.obj;
                login((Boolean) action._params.get(0), (Boolean) action._params.get(1),
                        (String) action._params.get(2), (String) action._params.get(3),
                        (String) action._params.get(4), (Boolean)action._params.get(5));
                handle = true;
                break;
            }
            case MSG_ACTION_DE_REGISTER: {
                PendingAction action = (PendingAction) msg.obj;
                deregister((RegisterListener) action._params.get(0));
                handle = true;
                break;
            }
            case MSG_ACTION_RE_REGISTER: {
                PendingAction action = (PendingAction) msg.obj;
                reRegister((Integer) action._params.get(0), (String) action._params.get(1));
                handle = true;
                break;
            }
            case MSG_ACTION_PREPARE_FOR_LOGIN: {
                PendingAction action = (PendingAction) msg.obj;
                prepareForLogin((Integer) action._params.get(0), (Boolean) action._params.get(1),
                        (RegisterConfig) action._params.get(2),
                        (RegisterListener) action._params.get(3));
                break;
            }
        }

        return handle;
    }

    public synchronized void prepareForLogin(int subId, boolean isSupportSRVCC, RegisterConfig config,
            RegisterListener listener) {
        if (Utilities.DEBUG) {
            Log.i(TAG, "Prepare before login, subId: " + subId + ", config: " + config);
        }
        if (subId < 0 || mIRegister == null) {
            Log.e(TAG, "Can not get the account info as sub id[" + subId
                    + "] or mIRegister is null.");
            if (listener != null) listener.onPrepareFinished(false, false);
            return;
        }

        try {
            // Reset first, then prepare.
            mRequest = null;

            int res = mIRegister.cliReset();
            if (res == Result.FAIL) {
                Log.w(TAG, "Reset action failed, notify as prepare failed.");
                if (listener != null) listener.onPrepareFinished(false, true);
                return;
            }

            // Prepare for login, need open account, start client and update settings.
            if (cliOpen(subId) && cliStart() && cliUpdateSettings(isSupportSRVCC)) {
                mRequest = new RegisterRequest(config, listener);
                mRequest.mNeedPLCI = VoWifiConfiguration.isRegRequestPLCI(mContext);
                mRequest.mNeedCNI = VoWifiConfiguration.isRegRequestCNI(mContext);

                if (mRequest.mNeedPLCI || mRequest.mNeedCNI) {
                    // Need notify as prepare finished after update the access net info.
                    CellularNetInfo.requestCellularNetInfo();
                    mRequest.mWaitForCNIResponse = true;
                    mHandler.sendEmptyMessageDelayed(
                            MSG_ACTION_REQUEST_CNI_TIMEOUT, REQUEST_CNI_TIMEOUT_MILLIS);
                } else {
                    listener.onPrepareFinished(true, false);
                }
            } else {
                listener.onPrepareFinished(false, false);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to prepare for login as catch the RemoteException, e: " + e);
            if (listener != null) listener.onPrepareFinished(false, true);
        }
    }

    public synchronized void login(boolean forSos, boolean isIPv4, String localIP, String pcscfIP,
            String dnsSerIP, boolean isRelogin) {
        if (mRequest == null) {
            // Make sure already prepare for login, otherwise the login process can not start.
            Log.e(TAG, "Do not prepare for login, please check!");
            return;
        }

        if (Utilities.DEBUG) {
            Log.i(TAG, "Try to login to the ims, for sos: " + forSos + ", is IPv4: " + isIPv4
                    + ", current register state: " + mRequest.mState);
            Log.i(TAG, "Login with the local ip: " + localIP + ", pcscf ip: " + pcscfIP
                    + ", dns server ip: " + dnsSerIP);
        }

        if (!isRelogin && mRequest.mState == RegisterState.STATE_CONNECTED) {
            // Already registered notify the register state.
            if (mRequest.mListener != null) mRequest.mListener.onLoginFinished(true, 0, 0);
            return;
        } else if (mRequest.mState == RegisterState.STATE_PROGRESSING) {
            // Already in the register process, do nothing.
            return;
        } else {
            mRequest.mIsSOS = forSos;
        }

        // The current register status is false.
        if (mIRegister == null) {
            Log.e(TAG, "When login, the IRegister is null!");
            if (mRequest.mListener != null) mRequest.mListener.onLoginFinished(false, 0, 0);
            return;
        }

        try {
            updateRegisterState(RegisterState.STATE_PROGRESSING);
            int type = VowifiNetworkType.IEEE_802_11;
            String info = "";
            int age = -1;
            if (mRequest.mNetInfo != null
                    && (mRequest.mNeedPLCI || mRequest.mNeedCNI) ) {
                type = mRequest.mNetInfo._type;
                info = mRequest.mNetInfo._info;
                age = mRequest.mNetInfo._age;
            }

            int res = mIRegister.cliLogin(
                    forSos, isIPv4, localIP, pcscfIP, dnsSerIP, type, info, age, isRelogin);
            if (res == Result.FAIL) {
                Log.e(TAG, "Login to the ims service failed, Please check!");
                updateRegisterState(RegisterState.STATE_IDLE);
                // Register failed, give the callback.
                if (mRequest.mListener != null) {
                    mRequest.mListener.onLoginFinished(false, 0, 0);
                }
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Catch the remote exception when login, e: " + e);
            updateRegisterState(RegisterState.STATE_IDLE);
            // After the state update finished, set the request to null.
            mRequest = null;
        }
    }

    public synchronized void deregister(RegisterListener listener) {
        if (Utilities.DEBUG) {
            Log.i(TAG, "Try to logout from the ims.");
        }

        if (mRequest == null
                || mRequest.mState == RegisterState.STATE_IDLE
                || mRequest.mState == RegisterState.STATE_PROGRESSING) {
            // If there isn't register request, or the current state is idle or progressing,
            // we'd like to force stop current process.
            Log.d(TAG, "Deregister, handle as force stop as the current status is: "
                    + (mRequest == null ? "null" : mRequest.mState));
            forceStop(listener);
            if (listener != null) listener.onLogout(0);
        } else if (mRequest.mState == RegisterState.STATE_CONNECTED) {
            // The current register status is true;
            if (mIRegister != null) {
                try {
                    int res = mIRegister.cliLogout();
                    if (res == Result.FAIL) {
                        // Logout failed, shouldn't be here.
                        Log.w(TAG, "Logout from the ims service failed. Please check!");
                    } else {
                        updateRegisterState(RegisterState.STATE_PROGRESSING);
                    }
                } catch (RemoteException e) {
                    Log.e(TAG, "Catch the remote exception when unregister, e: " + e);
                    if (listener != null) listener.onLogout(0);

                    updateRegisterState(RegisterState.STATE_IDLE);
                    mRequest = null;
                }
            }
        } else {
            // Shouldn't be here.
            Log.e(TAG, "Try to logout from the ims, shouldn't be here. register state: "
                    + mRequest.mState);
        }
    }

    public synchronized void reRegister(int type, String info) {
        if (Utilities.DEBUG) {
            Log.i(TAG, "Re-register, with the type: " + type + ", info: " + info);
        }

        if (mRequest == null
                || mRequest.mState != RegisterState.STATE_CONNECTED
                || TextUtils.isEmpty(info)) {
            // The current register state is false, can not re-register.
            Log.e(TAG, "Failed to re-register, please check!");
            return;
        }

        if (mIRegister == null) {
            Log.e(TAG, "Failed to re-register as IRegister is null!");
            return;
        }

        try {
            CellularNetInfo netInfo = new CellularNetInfo(type, info);
            int res = mIRegister.cliRefresh(netInfo._type, netInfo._info);
            if (res == Result.FAIL) {
                // Logout failed, shouldn't be here.
                Log.w(TAG, "Re-register to the ims service failed. Please check!");
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Catch the remote exception when re-register, e: " + e);
            if (mRequest.mListener != null) mRequest.mListener.onLogout(0);
        }
    }

    public synchronized boolean forceStop(RegisterListener listener) {
        if (Utilities.DEBUG) {
            Log.i(TAG, "Force stop current register process.");
        }

        if (mIRegister != null) {
            try {
                int res = mIRegister.cliReset();
                if (res == Result.FAIL) {
                    Log.e(TAG, "Failed to reset the sip stack, notify as reset block.");
                    if (listener != null) listener.onResetBlocked();
                }
            } catch (RemoteException e) {
                Log.e(TAG, "Catch the remote exception when unregister, e: " + e);
                if (listener != null) listener.onResetBlocked();
            }
        }

        // For force stop, we'd like do not handle the failed action, and set the register
        // state to idle immediately.
        updateRegisterState(RegisterState.STATE_IDLE);
        mRequest = null;
        return true;
    }

    public synchronized int getCurRegisterState() {
        return mRequest == null ? RegisterState.STATE_IDLE : mRequest.mState;
    }

    public synchronized RegisterConfig getCurRegisterConfig() {
        return mRequest == null ? null : mRequest.mRegisterConfig;
    }

    public synchronized boolean getCurRequiredCNI() {
        return mRequest == null ? false : (mRequest.mNeedCNI || mRequest.mNeedPLCI);
    }

    public synchronized void updateCellularNetInfo(int type, String info, int age) {
        if (mRequest == null) {
            Log.e(TAG, "Update Cellular NetInfo fail because of NULL Request.");
            return;
        }

        Log.d(TAG, "As needCellularNetInfo[mNeedPLCI: " + mRequest.mNeedPLCI + ", mNeedCNI: "
                + mRequest.mNeedCNI + "] update the type as: " + type + ", info as: " + info
                + ", age as: " + age);
        if (mRequest.mWaitForCNIResponse
                && (mRequest.mNeedPLCI || mRequest.mNeedCNI)) {
            mHandler.removeMessages(MSG_ACTION_REQUEST_CNI_TIMEOUT);

            mRequest.mWaitForCNIResponse = false;
            mRequest.mNetInfo = new CellularNetInfo(type, info, age);
            if (mRequest.mListener != null) {
                mRequest.mListener.onPrepareFinished(true, false);
            }
        }
    }

    public String getPAssociatedUri() {
        return mRegPAssociatedUri;
    }

    private boolean cliOpen(int subId) throws RemoteException {
        if (Utilities.DEBUG) Log.i(TAG, "Try to open the account.");

        if (mIRegister == null || subId < 0) {
            Log.e(TAG, "Failed open account as register interface or the account info is null.");
            return false;
        }

        return mIRegister.cliOpen(subId) == Result.SUCCESS;
    }

    private boolean cliStart() throws RemoteException {
        if (Utilities.DEBUG) Log.i(TAG, "Try to start the client.");

        if (mIRegister == null) {
            Log.e(TAG, "Failed start client as register interface is null.");
            return false;
        }

        return mIRegister.cliStart() == Result.SUCCESS;
    }

    private boolean cliUpdateSettings(boolean isSupportSRVCC)
            throws RemoteException {
        if (Utilities.DEBUG) Log.i(TAG, "Try to update the account settings.");

        if (mIRegister == null) {
            Log.e(TAG, "Failed update as register interface or the account info is null.");
            return false;
        }

        int res = mIRegister.cliUpdateSettings(isSupportSRVCC);
        return res == Result.SUCCESS;
    }

    private void updateRegisterState(int newState) {
        updateRegisterState(newState, 0);
    }

    private synchronized void updateRegisterState(int newState, int errorCode) {
        if (mRequest == null) return;

        mRequest.mState = newState;
        if (mRequest.mListener != null) {
            mRequest.mListener.onRegisterStateChanged(newState, errorCode);
        }
    }

    private static class RegisterRequest {
        public int mState;
        public boolean mIsSOS;
        public boolean mNeedPLCI = false;
        public boolean mNeedCNI = false;
        public boolean mWaitForCNIResponse = false;
        public CellularNetInfo mNetInfo = null;
        public RegisterConfig mRegisterConfig;
        public RegisterListener mListener;

        public RegisterRequest(RegisterConfig config, RegisterListener listener) {
            mRegisterConfig = config;
            mListener = listener;
        }
    }

    private class RegisterCallback extends IVoWifiRegisterCallback.Stub {
        private VoWifiRegisterManager mRegisterMgr = null;

        public RegisterCallback(VoWifiRegisterManager mgr) {
            mRegisterMgr = mgr;
        }

        @Override
        public void onRegisterStateChanged(String json) throws RemoteException {
            synchronized (mRegisterMgr) {
                if (Utilities.DEBUG) Log.i(TAG, "Get the register state changed callback: " + json);

                if (mRequest == null || TextUtils.isEmpty(json)) {
                    Log.e(TAG, "Can not handle the callback, please check the request or response.");
                    return;
                }

                try {
                    JSONObject jObject = new JSONObject(json);
                    int eventCode = jObject.optInt(
                            JSONUtils.KEY_EVENT_CODE, JSONUtils.REGISTER_EVENT_CODE_BASE);
                    String eventName = jObject.optString(JSONUtils.KEY_EVENT_NAME);
                    Log.d(TAG, "Handle the register event: " + eventName);

                    switch (eventCode) {
                        case JSONUtils.EVENT_CODE_LOGIN_OK:
                            mRegPAssociatedUri = jObject.optString(JSONUtils.KEY_P_ASSOCIATED_URI);
                            // Update the register state to connected, and notify the state changed.
                            updateRegisterState(RegisterState.STATE_CONNECTED);
                            if (mRequest.mListener != null) {
                                mRequest.mListener.onLoginFinished(true, 0, 0);
                            }
                            break;
                        case JSONUtils.EVENT_CODE_LOGIN_FAILED:
                            // Update the register state to unknown, and notify the state changed.
                            updateRegisterState(RegisterState.STATE_IDLE);
                            int retryAfter = jObject.optInt(JSONUtils.KEY_RETRY_AFTER, 0) * 1000;
                            if (mRequest != null
                                    && retryAfter > 0
                                    && retryAfter <= HANDLE_RETRY_AFTER_MILLIS) {
                                Log.d(TAG, "Handle as login failed for retry after: " + retryAfter);
                                PendingAction action = new PendingAction(
                                        retryAfter,
                                        "login",
                                        MSG_ACTION_LOGIN,
                                        Boolean.valueOf(mRequest.mIsSOS),
                                        Boolean.valueOf(mRequest.mRegisterConfig.isCurUsedIPv4()),
                                        mRequest.mRegisterConfig.getCurUsedLocalIP(),
                                        mRequest.mRegisterConfig.getCurUsedPcscfIP(),
                                        mRequest.mRegisterConfig.getCurUsedDnsSerIP(),
                                        Boolean.valueOf(false));
                                addToPendingList(action);
                            } else if (mRequest != null && mRequest.mListener != null) {
                                int stateCode = jObject.optInt(JSONUtils.KEY_STATE_CODE, 0);
                                mRequest.mListener.onLoginFinished(false, stateCode, 0);
                            }
                            break;
                        case JSONUtils.EVENT_CODE_LOGOUTED:
                            // Update the register state to idle, and reset the sip stack.
                            updateRegisterState(RegisterState.STATE_IDLE);
                            if (mRequest.mListener != null) {
                                int stateCode = jObject.optInt(JSONUtils.KEY_STATE_CODE, 0);
                                mRequest.mListener.onLogout(stateCode);
                            }
                            mRequest = null;
                            break;
                        case JSONUtils.EVENT_CODE_REREGISTER_OK:
                            if (mRequest.mListener != null) {
                                mRequest.mListener.onRefreshRegFinished(true, 0);
                            }
                            break;
                        case JSONUtils.EVENT_CODE_REREGISTER_FAILED:
                            if (mRequest.mListener != null) {
                                int stateCode = jObject.optInt(JSONUtils.KEY_STATE_CODE, 0);
                                mRequest.mListener.onRefreshRegFinished(false, stateCode);
                            }
                            break;
                        case JSONUtils.EVENT_CODE_REGISTER_STATE_UPDATE:
                            // Update the register state, and notify the state changed.
                            if (mRequest.mListener != null) {
                                int code = jObject.optInt(JSONUtils.KEY_STATE_CODE, 0);
                                mRequest.mListener.onRegisterStateChanged(mRequest.mState, code);
                            }
                            break;
                    }
                } catch (JSONException e) {
                    Log.e(TAG, "Failed to handle register state changed callback as ex: " + e);
                }
            }
        }

    }

}
