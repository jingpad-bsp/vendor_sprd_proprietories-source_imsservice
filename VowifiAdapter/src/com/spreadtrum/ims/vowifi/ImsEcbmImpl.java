package com.spreadtrum.ims.vowifi;

import android.content.Context;
import android.os.RemoteException;
import android.telephony.ims.ImsReasonInfo;
import android.util.Log;

import com.android.ims.internal.IImsEcbm;
import com.android.ims.internal.IImsEcbmListener;

public class ImsEcbmImpl extends IImsEcbm.Stub {
    private static final String TAG = Utilities.getTag(ImsEcbmImpl.class.getSimpleName());

    private boolean mInEcbm;

    private Context mContext;
    private IImsEcbmListener mListener;
    private ImsCallSessionImpl mEmergencyCallSession;

    protected ImsEcbmImpl(Context context) {
        mContext = context;
    }

    @Override
    public void exitEmergencyCallbackMode() throws RemoteException {
        if (mEmergencyCallSession != null) {
            if (mEmergencyCallSession.isInCall()) {
                mEmergencyCallSession.terminate(ImsReasonInfo.CODE_USER_TERMINATED);
            }
            mEmergencyCallSession = null;
        }
    }

    @Override
    public void setListener(IImsEcbmListener listener) {
        mListener = listener;
    }

    public void updateEcbm(boolean isEcbm, ImsCallSessionImpl emergencyCallSession) {
        mInEcbm = isEcbm;

        try {
            if (mInEcbm) {
                mEmergencyCallSession = emergencyCallSession;
                if (mListener != null) mListener.enteredECBM();
            } else {
                exitEmergencyCallbackMode();
                if (mListener != null) mListener.exitedECBM();
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to give the ecbm callback as catch the RemoteException e: " + e);
        }
    }

    public boolean isEcbm() {
        return mInEcbm;
    }

    public ImsCallSessionImpl getEmergencyCall() {
        return mEmergencyCallSession;
    }

}
