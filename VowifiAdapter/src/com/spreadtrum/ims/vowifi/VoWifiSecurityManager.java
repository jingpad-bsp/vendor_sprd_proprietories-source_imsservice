
package com.spreadtrum.ims.vowifi;

import android.content.Context;
import android.os.Message;
import android.os.RemoteException;
import android.text.TextUtils;
import android.util.Log;

import com.android.ims.internal.IVoWifiSecurity;
import com.android.ims.internal.IVoWifiSecurityCallback;

import com.spreadtrum.ims.vowifi.Utilities.IPVersion;
import com.spreadtrum.ims.vowifi.Utilities.JSONUtils;
import com.spreadtrum.ims.vowifi.Utilities.NativeErrorCode;
import com.spreadtrum.ims.vowifi.Utilities.PendingAction;
import com.spreadtrum.ims.vowifi.Utilities.Result;
import com.spreadtrum.ims.vowifi.Utilities.S2bType;
import com.spreadtrum.ims.vowifi.Utilities.SecurityConfig;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Objects;


public class VoWifiSecurityManager extends ServiceManager {
    private static final String TAG = Utilities.getTag(VoWifiSecurityManager.class.getSimpleName());

    public interface SecurityListener {
        /**
         * Attachment process callback
         */
        void onProgress(int state);

        /**
         * Attachment success callback
         */
        void onSuccessed(int sessionId);

        /**
         * Attachment failure callback
         */
        void onFailed(int reason);

        /**
         * Attached to stop callback, call stop IS2b () will trigger the callback
         */
        void onStopped(boolean forHandover, int errorCode);

        /**
         * Security service disconnect callback.
         */
        void onDisconnected();
    }

    private static final int MSG_ACTION_ATTACH = 1;

    private IVoWifiSecurity mISecurity = null;
    private SecurityCallback mCallback = new SecurityCallback();
    private HashMap<Integer, SecurityRequest> mRequestMap =
            new HashMap<Integer, SecurityRequest>();

    protected VoWifiSecurityManager(Context context) {
        this(context, Utilities.SERVICE_PACKAGE, Utilities.SERVICE_CLASS_SEC,
                Utilities.SERVICE_ACTION_SEC);
    }

    protected VoWifiSecurityManager(Context context, String pkg, String cls, String action) {
        super(context, pkg, cls, action);
    }

    @Override
    protected void onNativeReset() {
        Log.d(TAG, "The security service reset. Notify as the service disconnected.");
        notifyAllDisconnected();

        mISecurity = null;
        mRequestMap.clear();
    }

    @Override
    protected void onServiceChanged() {
        try {
            mISecurity = null;
            if (mServiceBinder != null) {
                mISecurity = IVoWifiSecurity.Stub.asInterface(mServiceBinder);
                mISecurity.registerCallback(mCallback);
            } else {
                clearPendingList();
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Can not register callback as catch the RemoteException. e: " + e);
        }
    }

    @Override
    protected boolean handlePendingAction(Message msg) {
        if (Utilities.DEBUG) Log.i(TAG, "Handle the pending action, msg: " + msg);

        if (msg.what == MSG_ACTION_ATTACH) {
            PendingAction action = (PendingAction) msg.obj;
            attach((Boolean) action._params.get(0), (Integer) action._params.get(1),
                    (Integer) action._params.get(2), (String) action._params.get(3),
                    (SecurityListener) action._params.get(4));
            return true;
        }

        return false;
    }

    public void attach(boolean isHandover, int subId, int type, String localAddr,
            SecurityListener listener) {
        if (Utilities.DEBUG) {
            Log.i(TAG, "Start the s2b attach. type: " + type + ", subId: " + subId
                    + ", localAddr: " + localAddr);
        }

        if (mISecurity == null) {
            // Do not handle the attach action, add to pending list.
            addToPendingList(new PendingAction(2 * 1000, "attach", MSG_ACTION_ATTACH,
                    Boolean.valueOf(isHandover), Integer.valueOf(subId), Integer.valueOf(type),
                    TextUtils.isEmpty(localAddr) ? "" : localAddr, listener));
            return;
        }

        try {
            // If the s2b state is idle, start the attach action.
            int sessionId = mISecurity.startWithAddr(isHandover, type, subId,
                    TextUtils.isEmpty(localAddr) ? "" : localAddr);
            if (sessionId == Result.INVALID_ID) {
                // It means attach failed.
                listener.onFailed(0);
            } else {
                SecurityRequest request = new SecurityRequest(sessionId, subId, type, listener);
                mRequestMap.put(Integer.valueOf(sessionId), request);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Catch the remote exception when start the s2b attach. e: " + e);
            listener.onFailed(0);
        }
    }

    public void deattach(int subId, int s2bType, boolean isHandover, SecurityListener listener) {
        SecurityRequest request = findRequest(subId, s2bType);
        if (request == null) {
            Log.d(TAG, "Do not find the matched request for sub[" + subId + "], type[" + s2bType
                    + "], and it means it already stopped, give the result now.");
            listener.onStopped(false, 0);
        } else {
            deattach(request, isHandover);
        }
    }

    public void deattach(int sessionId, boolean isHandover) {
        deattach(findRequest(sessionId), isHandover);
    }

    private void deattach(SecurityRequest request, boolean isHandover) {
        if (Utilities.DEBUG) Log.i(TAG, "Try to de-attach, is handover: " + isHandover);
        if (request == null) {
            Log.e(TAG, "Failed to deattach as the request is null.");
            return;
        }

        if (mISecurity == null) {
            Log.e(TAG, "Failed to deattach as the security interface is null.");
            request.mListener.onStopped(isHandover, 0);
            return;
        }

        try {
            mISecurity.stop(request.mSessionId, isHandover);
        } catch (RemoteException e) {
            Log.e(TAG, "Catch the remote exception when start the s2b deattach. e: " + e);
            request.mListener.onStopped(isHandover, 0);
        }
    }

    public void forceStop(int subId, SecurityListener listener) {
        if (Utilities.DEBUG) Log.i(TAG, "Force stop the s2b. Do as de-attach for normal type.");

        SecurityRequest request = findRequest(subId, S2bType.NORMAL);
        if (request == null) {
            Log.d(TAG, "Do not find the matched request for sub[" + subId
                    + "], and it means it already stopped, give the result now.");
            listener.onStopped(false, 0);
        } else {
            deattach(findRequest(subId, S2bType.NORMAL), false);
        }
    }

    public void startMobike(int subId) {
        if (Utilities.DEBUG) Log.i(TAG, "Start mobike for sub[" + subId + "] as normal type.");

        SecurityRequest request = findRequest(subId, S2bType.NORMAL);
        if (mISecurity == null || request == null) return;

        try {
            mISecurity.startMobike(request.mSessionId);
        } catch (RemoteException e) {
            Log.e(TAG, "Catch the remote exception when start mobike. e: " + e);
        }
    }

    public boolean setIPVersion(int subId, int s2bType, int ipVersion) {
        SecurityRequest request = findRequest(subId, s2bType);
        return request != null ? setIPVersion(request.mSessionId, ipVersion) : false;
    }

    public boolean setIPVersion(int sessionId, int ipVersion) {
        if (Utilities.DEBUG) Log.i(TAG, "Set the IP version: " + ipVersion);

        if (mISecurity == null) {
            Log.e(TAG, "Failed to set the IP version as the security interface is null.");
            return false;
        }

        try {
            return mISecurity.switchLoginIpVersion(sessionId, ipVersion);
        } catch (RemoteException e) {
            Log.e(TAG, "Catc the remote exception when set the IP version. e: " + e);
            return false;
        }
    }

    public SecurityConfig getConfig(int subId, int type) {
        SecurityRequest request = findRequest(subId, type);
        return request == null ? null : request.mConfig;
    }

    public SecurityConfig getConfig(int sessionId) {
        SecurityRequest request = findRequest(sessionId);
        return request == null ? null : request.mConfig;
    }

    private SecurityRequest findRequest(int sessionId) {
        if (mRequestMap.size() < 1) return null;

        return mRequestMap.get(Integer.valueOf(sessionId));
    }

    private SecurityRequest findRequest(int subId, int type) {
        if (mRequestMap.size() < 1) return null;

        Iterator<SecurityRequest> it = mRequestMap.values().iterator();
        while (it.hasNext()) {
            SecurityRequest request = it.next();
            if (request.matched(subId, type)) {
                return request;
            }
        }

        // Do not find any matched request, return null.
        return null;
    }

    private void notifyAllDisconnected() {
        Iterator<SecurityRequest> it = mRequestMap.values().iterator();
        while (it.hasNext()) {
            SecurityRequest request = it.next();
            if (request != null) {
                if (request.mListener != null) {
                    request.mListener.onDisconnected();
                }
            }
        }
        mRequestMap.clear();
    }

    private void attachSuccess(SecurityRequest request) {
        if (request != null && request.mListener != null) {
            request.mListener.onSuccessed(request.mSessionId);
        }
    }

    private void attachFailed(SecurityRequest request, int errorCode) {
        if (request != null) {
            if (request.mListener != null) {
                request.mListener.onFailed(errorCode);
            }
            mRequestMap.remove(Integer.valueOf(request.mSessionId));
        }
    }

    private void attachProcessing(SecurityRequest request, int stateCode) {
        if (request != null && request.mListener != null) {
            request.mListener.onProgress(stateCode);
        }
    }

    private void attachStopped(SecurityRequest request, boolean forHandover, int errorCode) {
        if (request != null) {
            if (request.mListener != null) {
                request.mListener.onStopped(forHandover, errorCode);
            }
            mRequestMap.remove(Integer.valueOf(request.mSessionId));
        }
    }

    private class SecurityCallback extends IVoWifiSecurityCallback.Stub {

        @Override
        public void onS2bStateChanged(String json) throws RemoteException {
            if (Utilities.DEBUG) Log.i(TAG, "Get the security callback: " + json);

            if (TextUtils.isEmpty(json)) {
                Log.e(TAG, "Can not handle the security callback as the json is null.");
                return;
            }

            try {
                JSONObject jObject = new JSONObject(json);
                int sessionId = jObject.optInt(JSONUtils.KEY_SESSION_ID, -1);
                SecurityRequest request = findRequest(sessionId);
                if (request == null) {
                    Log.e(TAG, "Can not find request for the sessionId: " + sessionId);
                    return;
                }

                int eventCode = jObject.optInt(JSONUtils.KEY_EVENT_CODE);
                switch (eventCode) {
                    case JSONUtils.EVENT_CODE_ATTACH_SUCCESSED: {
                        Log.d(TAG, "Attach success for session: " + sessionId);
                        String localIP4 = jObject.optString(JSONUtils.KEY_LOCAL_IP4, null);
                        String localIP6 = jObject.optString(JSONUtils.KEY_LOCAL_IP6, null);
                        String pcscfIP4 = jObject.optString(JSONUtils.KEY_PCSCF_IP4, null);
                        String pcscfIP6 = jObject.optString(JSONUtils.KEY_PCSCF_IP6, null);
                        String dnsIP4 = jObject.optString(JSONUtils.KEY_DNS_IP4, null);
                        String dnsIP6 = jObject.optString(JSONUtils.KEY_DNS_IP6, null);
                        boolean prefIPv4 = jObject.optBoolean(JSONUtils.KEY_PREF_IP4, false);
                        boolean supportMobike =
                                jObject.optBoolean(JSONUtils.KEY_SUPPORT_MOBIKE, false);

                        SecurityConfig config = new SecurityConfig(pcscfIP4, pcscfIP6, dnsIP4,
                                dnsIP6, localIP4, localIP6, prefIPv4, supportMobike);
                        // For handover attach or ut attach, we need set the IP version as
                        // preferred first, then notify the result and update the state.
                        int useIPVersion = prefIPv4 ? IPVersion.IP_V4 : IPVersion.IP_V6;
                        if (setIPVersion(sessionId, useIPVersion)) {
                            config._useIPVersion = useIPVersion;
                            request.updateConfig(config);
                            attachSuccess(request);
                        } else {
                            // Set IP version failed.
                            attachFailed(request, 0);
                        }

                        break;
                    }
                    case JSONUtils.EVENT_CODE_ATTACH_FAILED: {
                        int errorCode = jObject.optInt(JSONUtils.KEY_STATE_CODE);
                        Log.d(TAG, "Attach failed, errorCode: " + errorCode);
                        attachFailed(request, errorCode);
                        break;
                    }
                    case JSONUtils.EVENT_CODE_ATTACH_PROGRESSING: {
                        int state = jObject.optInt(JSONUtils.KEY_PROGRESS_STATE);
                        Log.d(TAG, "Attach progress state changed to " + state);
                        attachProcessing(request, state);
                        break;
                    }
                    case JSONUtils.EVENT_CODE_ATTACH_STOPPED: {
                        int errorCode = jObject.optInt(JSONUtils.KEY_STATE_CODE);
                        boolean forHandover = (errorCode == NativeErrorCode.IKE_HANDOVER_STOP);
                        Log.d(TAG, "Attach stopped, errorCode: " + errorCode + ", for handover: "
                                + forHandover);
                        attachStopped(request, forHandover, errorCode);
                        break;
                    }
                }
            } catch (JSONException e) {
                Log.e(TAG, "Failed to parse the security callback as catch the JSONException, e: "
                        + e);
            }
        }
    }

    private static class SecurityRequest {
        public int mSessionId;
        public int mSubId;
        public int mType;

        public SecurityConfig mConfig;
        public SecurityListener mListener;

        public SecurityRequest(int sessionId, int subId, int type, SecurityListener listener) {
            mSessionId = sessionId;
            mSubId = subId;
            mType = type;
            mListener = listener;
        }

        public void updateConfig(SecurityConfig config) {
            mConfig = config;
        }

        public boolean matched(int subId, int type) {
            return mSubId == subId && mType == type;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj instanceof SecurityRequest) {
                SecurityRequest request = (SecurityRequest) obj;
                if (request == this) {
                    return true;
                } else if (this.mSessionId == request.mSessionId) {
                    return true;
                } else if (this.mSubId == request.mSubId && this.mType == request.mType) {
                    return true;
                }
            }

            return super.equals(obj);
        }

        @Override
        public int hashCode()  {
            return Objects.hash(mSessionId, mSubId, mType, mListener);
        }
    }

}
