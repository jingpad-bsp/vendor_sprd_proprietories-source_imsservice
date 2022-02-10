package com.spreadtrum.ims.vowifi;

import android.content.Context;
import android.os.RemoteException;
import android.telephony.ims.ImsReasonInfo;
import android.util.Log;

import com.spreadtrum.ims.vowifi.Utilities.ECBMRequest;

public class VoWifiSOSManager extends VoWifiServiceImpl {
    private static final String TAG = Utilities.getTag(VoWifiSOSManager.class.getSimpleName());

    public interface SOSListener {
        public void onSOSCallTerminated();
        public void onSOSRequestFinished();
        public void onSOSRequestError();
    }

    private SOSListener mListener;

    protected VoWifiSOSManager(Context context) {
        super(context, TAG, false /* needn't init here */);
    }

    public boolean startRequest(ECBMRequest request, SOSListener listener) {
        if (request == null
                || request.getCallSession() == null
                || request.getCurStep() != ECBMRequest.ECBM_STEP_ATTACH_SOS) {
            Log.e(TAG, "Failed to start ECBM request, request = " + request);
            return false;
        }

        mListener = listener;

        // Before start the emergency call process, we need bind all the service.
        init();

        // Update the call manager, then it will enter the ECBM.
        request.getCallSession().updateCallManager(mCallMgr);
        return true;
    }

    @Override
    protected void onSOSCallTerminated() {
        if (mListener != null) {
            mListener.onSOSCallTerminated();
        }
    }

    @Override
    protected boolean onSOSProcessFinished() {
        if (mListener != null) {
            mListener.onSOSRequestFinished();
        }
        unInit();
        return true;
    }

    @Override
    protected void onSOSMsgTimeout() {
        // As sos timeout, we like to notify as request error.
        if (mListener != null) {
            mListener.onSOSRequestError();
        }

        // Terminate the call.
        try {
            mECBMRequest.getCallSession().terminate(ImsReasonInfo.CODE_EMERGENCY_TEMP_FAILURE);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to terminate the call when SOS msg timeout.");
        }

        unInit();
    }

    @Override
    protected void onSOSError(int failedStep) {
        // As sos error, we like to notify as request error.
        if (mListener != null) {
            mListener.onSOSRequestError();
        }

        // Terminate the call.
        try {
            mECBMRequest.getCallSession().terminate(ImsReasonInfo.CODE_EMERGENCY_TEMP_FAILURE);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to terminate the call when SOS error.");
        }

        unInit();
    }

    private void init() {
        Log.d(TAG, "Init the sos manager.");

        mCallMgr = new VoWifiCallManager(mContext,
                Utilities.SERVICE_PACKAGE_SEC,
                Utilities.SERVICE_PACKAGE_SEC + ".service.CallService",
                Utilities.SERVICE_ACTION_CALL);
        mSecurityMgr = new VoWifiSecurityManager(mContext,
                Utilities.SERVICE_PACKAGE,
                Utilities.SERVICE_PACKAGE + ".service.SecurityService",
                Utilities.SERVICE_ACTION_SEC);
        mRegisterMgr = new VoWifiRegisterManager(mContext,
                Utilities.SERVICE_PACKAGE_SEC,
                Utilities.SERVICE_PACKAGE_SEC + ".service.RegisterService",
                Utilities.SERVICE_ACTION_REG);

        mCallMgr.registerListener(mCallListener);

        mCallMgr.bindService();
        mSecurityMgr.bindService();
        mRegisterMgr.bindService();
    }

    private void unInit() {
        Log.d(TAG, "Un-init the sos manager.");

        mListener = null;

        mCallMgr.unregisterListener();
        mCallMgr.unbindService();
        mSecurityMgr.unbindService();
        mRegisterMgr.unbindService();

        mCallMgr = null;
        mSecurityMgr = null;
        mRegisterMgr = null;
    }
}
