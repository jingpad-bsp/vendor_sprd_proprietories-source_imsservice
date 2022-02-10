package com.spreadtrum.ims.vowifi;

import android.annotation.TargetApi;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.telecom.VideoProfile;
import android.telecom.Connection.VideoProvider;
import android.telephony.PhoneNumberUtils;
import android.telephony.ServiceState;
import android.telephony.ims.ImsCallProfile;
import android.telephony.ims.ImsCallSession.State;
import android.telephony.ims.ImsConferenceState;
import android.telephony.ims.ImsReasonInfo;
import android.telephony.ims.ImsStreamMediaProfile;
import android.telephony.ims.aidl.IImsCallSessionListener;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import com.android.ims.internal.IImsServiceEx;
import com.android.ims.internal.ImsSrvccCallInfo;
import com.android.ims.internal.ImsManagerEx;
import com.android.ims.internal.IVoWifiCall;
import com.android.ims.internal.IVoWifiCallCallback;
import com.android.internal.telephony.CommandsInterface;

import com.spreadtrum.ims.R;
import com.spreadtrum.ims.vowifi.Utilities.CellularNetInfo;
import com.spreadtrum.ims.vowifi.Utilities.CallStateForDataRouter;
import com.spreadtrum.ims.vowifi.Utilities.ECBMRequest;
import com.spreadtrum.ims.vowifi.Utilities.EMUtils;
import com.spreadtrum.ims.vowifi.Utilities.JSONUtils;
import com.spreadtrum.ims.vowifi.Utilities.MyToast;
import com.spreadtrum.ims.vowifi.Utilities.RegisterState;
import com.spreadtrum.ims.vowifi.Utilities.Result;
import com.spreadtrum.ims.vowifi.Utilities.SRVCCResult;
import com.spreadtrum.ims.vowifi.Utilities.SRVCCSyncInfo;
import com.spreadtrum.ims.vowifi.Utilities.UnsolicitedCode;
import com.spreadtrum.ims.vowifi.Utilities.VideoQuality;
import com.spreadtrum.ims.vowifi.Utilities.VideoType;
import com.spreadtrum.ims.vowifi.VoWifiServiceImpl.IncomingCallAction;
import com.spreadtrum.ims.vowifi.VoWifiServiceImpl.WifiState;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;

@TargetApi(23)
public class VoWifiCallManager extends ServiceManager {

    public interface CallListener {
        public void onCallIncoming(ImsCallSessionImpl callSession);
        public void onCallEnd(ImsCallSessionImpl callSession);
        public void onCallRTPReceived(boolean isVideoCall, boolean isReceived);
        public void onCallRTCPChanged(boolean isVideoCall, int lose, int jitter, int rtt);
        public void onAliveCallUpdate(boolean isVideoCall);
        public void onEnterECBM(ECBMRequest request);
        public void onExitECBM();
        public void onSRVCCFinished(boolean isSuccess);
        public void onUnsolicitedRequest(int unsolicitedCode);
    }

    public interface ICallChangedListener {
        public void onChanged(IVoWifiCall newServiceInterface);
    }

    private static final String TAG = Utilities.getTag(VoWifiCallManager.class.getSimpleName());

    // FIXME: This call profile's used by UI to disabled the merge action.
    private static final String EXTRA_IS_FOCUS = "is_mt_conf_call";

    private static final String PROP_KEY_AUTO_ANSWER = "persist.sys.vowifi.autoanswer";

    private int mUseAudioStreamCount = 0;
    private int mCurAttachSessionId = -1;
    private int mRegisterState = RegisterState.STATE_IDLE;
    private int mCurRatType = ServiceState.RIL_RADIO_TECHNOLOGY_IWLAN;
    private boolean mInSRVCC = false;
    private boolean mCallWithCNI = false;

    private CallListener mListener = null;
    private ECBMRequest mECBMRequest = null;
    private CellularNetInfo mCellularNetInfo = null;
    private IncomingCallAction mIncomingCallAction = IncomingCallAction.NORMAL;
    private ArrayList<ImsCallSessionImpl> mSessionList = new ArrayList<ImsCallSessionImpl>();
    private ArrayList<ImsCallSessionImpl> mSRVCCSessionList = new ArrayList<ImsCallSessionImpl>();

    private IVoWifiCall mICall;
    private ArrayList<ICallChangedListener> mICallChangedListeners =
            new ArrayList<ICallChangedListener>();
    private MyCallCallback mCallCallback = new MyCallCallback();

    private static final int TERM_CHILD_CALL_DELAY = 10 * 1000;

    private static final int MSG_HANDLE_EVENT = 0;
    private static final int MSG_INVITE_CALL = 1;
    private static final int MSG_TERM_CHILD_CALL = 2;
    private static final int MSG_AUTO_ANSWER = 3;
    private static final int MSG_RETRY_TERMINATE_CALL = 4;
    private Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_HANDLE_EVENT:
                    handleEvent((String) msg.obj);
                    break;
                case MSG_INVITE_CALL:
                    inviteCall((ImsCallSessionImpl) msg.obj);
                    break;
                case MSG_TERM_CHILD_CALL:
                    String callId = (String) msg.obj;
                    ImsCallSessionImpl callSession = getCallSession(callId);
                    if (callSession != null) {
                        Log.d(TAG, "The call do not receive BYE until now, terminate the call.");
                        callSession.terminateCall(ImsReasonInfo.CODE_USER_TERMINATED_BY_REMOTE);
                        callSession.close();
                    }
                    break;
                case MSG_AUTO_ANSWER:
                    answerCall((ImsCallSessionImpl) msg.obj);
                    break;
                case MSG_RETRY_TERMINATE_CALL:
                    try {
                        handleCallTermed((ImsCallSessionImpl) msg.obj, msg.arg1);
                    } catch (RemoteException ex) {
                        Log.e(TAG, "Failed to handle the call term when retry as ex: "
                                + ex.toString());
                    }
                    break;
            }
        }
    };

    // Please do not change this defined, it samed as the state change value.
    private static final int MSG_SRVCC_START   = 0;
    private static final int MSG_SRVCC_SUCCESS = 1;
    private static final int MSG_SRVCC_CANCEL  = 2;
    private static final int MSG_SRVCC_FAILED  = 3;
    private SRVCCHandler mSRVCCHandler = null;
    private class SRVCCHandler extends Handler {
        private ArrayList<ImsSrvccCallInfo> mInfoList = new ArrayList<ImsSrvccCallInfo>();

        public SRVCCHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            if (mSessionList == null || mSessionList.isEmpty()) {
                Log.d(TAG, "There isn't any call, ignore the SRVCC event: " + msg.what);
                return;
            }

            switch(msg.what) {
                case MSG_SRVCC_START:
                    Log.d(TAG, "Will handle the SRVCC start event.");
                    // Set as in SRVCC process.
                    mInSRVCC = true;

                    // When SRVCC start, put all the call session to SRVCC session list.
                    mInfoList.clear();
                    mSRVCCSessionList.clear();
                    mSRVCCSessionList.addAll(mSessionList);

                    // Prepare the call state which will need sync to modem if SRVCC success.
                    for (ImsCallSessionImpl session : mSRVCCSessionList) {
                        session.prepareSRVCCSyncInfo(mInfoList, -1);
                    }

                    break;
                case MSG_SRVCC_SUCCESS:
                    Log.d(TAG, "Will handle the SRVCC success event.");
                    // Set as leave the SRVCC process.
                    mInSRVCC = false;

                    // If SRVCC success, we need do as this:
                    // 1. Sync the calls info to CP.
                    // 2. Release the native call resource.
                    // 3. Give the callback to telephony if there is no response action,
                    //    and the failed reason should be ImsReasonInfo.CODE_LOCAL_HO_NOT_FEASIBLE.
                    // 4. Close the call session.
                    // 5. Clear the session list.

                    // Sync the calls' info.
                    IImsServiceEx imsServiceEx = ImsManagerEx.getIImsServiceEx();
                    if (imsServiceEx != null) {
                        try {
                            Log.d(TAG, "Notify the SRVCC call infos.");
                            imsServiceEx.notifySrvccCallInfos(mInfoList);
                        } catch (RemoteException e) {
                            Log.e(TAG, "Failed to sync the infos as catch the exception: " + e);
                        }
                    } else {
                        Log.e(TAG, "Can not get the ims ex service.");
                    }

                    for (ImsCallSessionImpl session : mSRVCCSessionList) {
                        // Release the native call resource, but do not terminate the UI.
                        session.releaseCall();
                        // Give the callback if there is pending action.
                        session.processNoResponseAction();
                        // Close this call session.
                        session.close();
                    }
                    // Clear the SRVCC session list.
                    mInfoList.clear();
                    mSRVCCSessionList.clear();

                    if (mListener != null) {
                        mListener.onSRVCCFinished(true);
                    }
                    break;
                case MSG_SRVCC_FAILED:
                case MSG_SRVCC_CANCEL:
                    Log.d(TAG, "Will handle the SRVCC failed/cancel event.");
                    // Set as leave the SRVCC process.
                    mInSRVCC = false;

                    for (ImsCallSessionImpl session : mSRVCCSessionList) {
                        int result = (msg.what == MSG_SRVCC_FAILED ? SRVCCResult.FAILURE
                                : SRVCCResult.CANCEL);
                        session.updateSRVCCResult(result);
                    }
                    mInfoList.clear();
                    mSRVCCSessionList.clear();

                    if (mListener != null) {
                        mListener.onSRVCCFinished(false);
                    }
                    break;
            }
        }
    }

    protected VoWifiCallManager(Context context) {
        this(context, Utilities.SERVICE_PACKAGE, Utilities.SERVICE_CLASS_CALL,
                Utilities.SERVICE_ACTION_CALL);
    }

    protected VoWifiCallManager(Context context, String pkg, String cls, String action) {
        super(context, pkg, cls, action);

        // New a thread to handle the SRVCC event.
        HandlerThread thread = new HandlerThread("SRVCC");
        thread.start();
        Looper looper = thread.getLooper();
        mSRVCCHandler = new SRVCCHandler(looper);
    }

    @Override
    protected void finalize() throws Throwable {
        if (mICall != null) mICall.unregisterCallback(mCallCallback);
        super.finalize();
    }

    @Override
    protected void onNativeReset() {
        try {
            for (ImsCallSessionImpl callSession : mSessionList) {
                handleCallTermed(callSession, ImsReasonInfo.CODE_USER_TERMINATED);
            }
        } catch (RemoteException ex) {
            Log.e(TAG, "Failed to handle as calls term as catch the ex: " + ex.toString());
        }

        mRegisterState = RegisterState.STATE_IDLE;
        mECBMRequest = null;
        mICall = null;
        for (ICallChangedListener listener : mICallChangedListeners) {
            listener.onChanged(mICall);
        }
    }

    @Override
    protected void onServiceChanged() {
        try {
            mICall = null;
            if (mServiceBinder != null) {
                mICall = IVoWifiCall.Stub.asInterface(mServiceBinder);
                mICall.registerCallback(mCallCallback);
            } else {
                clearPendingList();
            }

            // Notify the call interface changed.
            for (ICallChangedListener listener : mICallChangedListeners) {
                listener.onChanged(mICall);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Can not register callback as catch the RemoteException. e: " + e);
        }
    }

    public void registerListener(CallListener listener) {
        if (listener == null) {
            Log.e(TAG, "Can not register the listener as it is null.");
            return;
        }

        mListener = listener;
    }

    public void unregisterListener() {
        if (Utilities.DEBUG) Log.i(TAG, "Unregister the listener: " + mListener);
        mListener = null;
    }

    public boolean registerCallInterfaceChanged(ICallChangedListener listener) {
        if (listener == null) {
            Log.w(TAG, "Can not register the call interface changed as the listener is null.");
            return false;
        }

        mICallChangedListeners.add(listener);

        // Notify the service changed immediately when register the listener.
        listener.onChanged(mICall);
        return true;
    }

    public boolean unregisterCallInterfaceChanged(ICallChangedListener listener) {
        if (listener == null) {
            Log.w(TAG, "Can not register the call interface changed as the listener is null.");
            return false;
        }

        return mICallChangedListeners.remove(listener);
    }

    public ImsCallSessionImpl createMOCallSession(ImsCallProfile profile,
            IImsCallSessionListener listener) {
        return createCallSession(profile, listener, null, SRVCCSyncInfo.CallDirection.MO,
                mCallWithCNI);
    }

    public ImsCallSessionImpl createMTCallSession(ImsCallProfile profile,
            IImsCallSessionListener listener) {
        return createCallSession(profile, listener, null, SRVCCSyncInfo.CallDirection.MT,
                mCallWithCNI);
    }

    public void terminateCalls(WifiState state) throws RemoteException {
        if (Utilities.DEBUG) {
            Log.i(TAG, "Try to terminate all the calls with wifi state: "
                    + (WifiState.CONNECTED.equals(state) ? "connect" : "disconnect"));
        }

        ArrayList<ImsCallSessionImpl> callList =
                (ArrayList<ImsCallSessionImpl>) mSessionList.clone();
        for (ImsCallSessionImpl callSession : callList) {
            Log.d(TAG, "Terminate the call: " + callSession);
            callSession.terminate(ImsReasonInfo.CODE_USER_TERMINATED);
            handleCallTermed(callSession, ImsReasonInfo.CODE_USER_TERMINATED);
        }
    }

    public int getCallRatType() {
        return mCurRatType;
    }

    public void resetCallRatType() {
        mCurRatType = ServiceState.RIL_RADIO_TECHNOLOGY_IWLAN;
    }

    public void updateCallsRatType(int type) {
        if (Utilities.DEBUG) Log.i(TAG, "Try to update all the calls' type to: " + type);

        mCurRatType = type;
        for (ImsCallSessionImpl callSession : mSessionList) {
            callSession.updateCallRatType(type);
        }
    }

    public int getAttachSessionId() {
        return mCurAttachSessionId;
    }

    public void updateAttachSessionId(int newAttachSessionId) {
        Log.d(TAG, "Update attach session id: " + newAttachSessionId);
        mCurAttachSessionId = newAttachSessionId;
    }

    public void updateRegisterState(int newState) {
        mRegisterState = newState;
    }

    public void updateRequiredCNI(boolean requiredCNI) {
        mCallWithCNI = requiredCNI;
    }

    public void updateCellularNetInfo(int type, String info, int age) {
        mCellularNetInfo = new CellularNetInfo(type, info, age);
    }

    public void updateIncomingCallAction(IncomingCallAction action) {
        if (Utilities.DEBUG) {
            Log.i(TAG, "Update the incoming call action to: "
                    + (action == IncomingCallAction.NORMAL ? "normal" : "reject"));
        }
        mIncomingCallAction = action;
    }

    public boolean updateCurCallSlot(int slotId) {
        if (Utilities.DEBUG) {
            Log.i(TAG, "Update the call slot: " + slotId);
        }

        // As this used to update the call state, if failed to update, needn't add to pending list.
        if (mICall == null) {
            return false;
        }

        try {
            int res = mICall.updateCurCallSlot(slotId);
            if (res == Result.SUCCESS) return true;
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to update the call's slot as catch the ex: " + e.toString());
        }
        return false;
    }

    public void updateDataRouterState(int state) {
        if (Utilities.DEBUG) {
            Log.i(TAG, "Update the call state to data router. state: "
                    + Utilities.CallStateForDataRouter.getDRStateString(state));
        }

        // As this used to update the call state, if failed to update, needn't add to pending list.
        if (mICall != null) {
            try {
                if (state == Utilities.CallStateForDataRouter.VOWIFI) {
                    ImsCallSessionImpl session = getCurrentCallSession();
                    if (session != null
                            && session.getCallProfile().isVideoCall()) {
                        state = Utilities.CallStateForDataRouter.VOWIFI_VIDEO;
                    }
                }
                Log.d(TAG, "Update data router state for session: " + mCurAttachSessionId);
                int res = mICall.updateDataRouterState(mCurAttachSessionId, state);
                if (res == Result.FAIL) {
                    Log.e(TAG, "Failed to update the data router state, please check!");
                }
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to update the data router state for RemoteException e: " + e);
            }
        }
    }

    public int getPacketLose() {
        if (Utilities.DEBUG) Log.i(TAG, "Try to get the packet lose.");
        if (mICall == null) {
            Log.e(TAG, "Can not get the packet lose as the service do not bind success now.");
            return 0;
        }

        ImsCallSessionImpl callSession = getAliveCallSession();
        if (callSession == null) {
            Log.d(TAG, "Can not found the actived call, return packet lose as 0.");
            return 0;
        }

        return callSession.getPacketLose();
    }

    public int getJitter() {
        if (Utilities.DEBUG) Log.i(TAG, "Try to get the jitter.");
        if (mICall == null) {
            Log.e(TAG, "Can not get the jitter as the service do not bind success now.");
            return 0;
        }

        ImsCallSessionImpl callSession = getAliveCallSession();
        if (callSession == null) {
            Log.d(TAG, "Can not found the actived call, return jitter as 0.");
            return 0;
        }

        return callSession.getJitter();
    }

    public int getRtt() {
        if (Utilities.DEBUG) Log.i(TAG, "Try to get the rtt.");
        if (mICall == null) {
            Log.e(TAG, "Can not get the rtt as the service do not bind success now.");
            return 0;
        }

        ImsCallSessionImpl callSession = getAliveCallSession();
        if (callSession == null) {
            Log.d(TAG, "Can not found the actived call, return rtt as 0.");
            return 0;
        }

        return callSession.getRtt();
    }

    public void startAudioStream() {
        if (Utilities.DEBUG) {
            Log.i(TAG, "Try to start the audio stream, current use audio stream count: "
                    + mUseAudioStreamCount);
        }

        // Only start the audio stream on the first call accept or start.
        mUseAudioStreamCount = mUseAudioStreamCount + 1;
        if (mICall != null && mUseAudioStreamCount == 1) {
            try {
                mICall.startAudioStream();
            } catch (RemoteException e) {
                Log.e(TAG, "Catch the remote exception when start the audio stream, e: " + e);
            }
        }
    }

    public void stopAudioStream() {
        if (Utilities.DEBUG) {
            Log.i(TAG, "Try to stop the audio stream, the current use audio stream count: "
                    + mUseAudioStreamCount);
        }

        // If there is any call used the audio stream, we need reduce the count, else do nothing.
        mUseAudioStreamCount =
                mUseAudioStreamCount > 0 ? mUseAudioStreamCount - 1 : mUseAudioStreamCount;
        if (mUseAudioStreamCount != 0) {
            Log.d(TAG, "There is call need audio stream, needn't stop audio stream, exist number: "
                    + mUseAudioStreamCount);
            return;
        }

        Log.d(TAG, "There isn't any call use the audio stream, need stop audio stream now.");
        if (mICall != null) {
            try {
                mICall.stopAudioStream();
            } catch (RemoteException e) {
                Log.e(TAG, "Catch the remote exception when stop the audio stream, e: " + e);
            }
        }
    }

    public void updateVideoQuality(VideoQuality quality) {
        if (Utilities.DEBUG) Log.i(TAG, "Set the video quality as index is: " + quality);

        if (!isCallFunEnabled() || mICall == null || quality == null) {
            // As call function is disabled. Do nothing.
            return;
        }

        try {
            mICall.setDefaultVideoLevel(quality._level);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to set the video quality as catch the RemoteException e: " + e);
        }
    }

    public void addCall(ImsCallSessionImpl callSession) {
        if (Utilities.DEBUG) Log.i(TAG, "Add the call[" + callSession + "] to list.");
        if (callSession == null) {
            Log.e(TAG, "Can not add this call[" + callSession + "] to list as it is null.");
            return;
        }

        mSessionList.add(callSession);
    }

    public void removeCall(ImsCallSessionImpl callSession) {
        synchronized (mSessionList) {
            removeCall(callSession, true);
        }
    }

    public void removeCall(ImsCallSessionImpl callSession, boolean needNotify) {
        if (Utilities.DEBUG) Log.i(TAG, "Remove the call[" + callSession + "] from the list.");
        if (callSession == null) {
            Log.e(TAG, "Can not remove this call[" + callSession + "] from list as it is null.");
            return;
        }

        // Remove the call session from the list.
        if (mSessionList.remove(callSession)) {
            Log.d(TAG, "The call[" + callSession + "] removed from the list.");

            if (mListener == null) return;

            if (needNotify) {
                // It means the call is end now.
                if (mECBMRequest != null
                        && callSession.equals(mECBMRequest.getCallSession())) {
                    // It means the emergency call end. Exit the ECBM.
                    mListener.onExitECBM();
                }

                if (callSession.isUserAcknowledge()) {
                    // If the user acknowledge the call, notify the call end event.
                    mListener.onCallEnd(callSession);
                }
            }

            // After remove the session, if the session list is empty, we need stop the audio.
            if (callSession.isAudioStart()) stopAudioStream();
        } else {
            Log.d(TAG, "Do not remove the call[" + callSession + "] from the list.");
        }
    }

    public void removeECBMRequest() {
        mECBMRequest = null;
    }

    public void enterECBMWithCallSession(ECBMRequest request) {
        mECBMRequest = request;
        if (mListener != null) mListener.onEnterECBM(mECBMRequest);
    }

    /**
     * Return the call session list. This list may be empty if there isn't any call.
     */
    public ArrayList<ImsCallSessionImpl> getCallSessionList() {
        return mSessionList;
    }

    public boolean isCallFunEnabled() {
        return mRegisterState == RegisterState.STATE_CONNECTED;
    }

    public int getCallCount() {
        return mSessionList.size();
    }

    public boolean isInSRVCC() {
        return mInSRVCC;
    }

    /**
     * Get the call session relate to this special call id.
     * @param id the session with this call id.
     * @return The call session for the given id. If couldn't found the session for given id or the
     *         given id is null, return null.
     */
    public ImsCallSessionImpl getCallSession(String id) {
        if (TextUtils.isEmpty(id)) {
            Log.w(TAG, "The param id is null, return null");
            return null;
        }

        for (ImsCallSessionImpl session : mSessionList) {
            if (id.equals(session.getCallId())) {
                Log.d(TAG, "Found the call session for this id: " + id);
                return session;
            }
        }

        // Can not found the call session relate to this id.
        Log.d(TAG, "Can not found the call session for this id: " + id);
        return null;
    }

    /**
     * Get the video call session list. Return null if can not found.
     */
    public ArrayList<ImsCallSessionImpl> getVideoCallSessions() {
        ArrayList<ImsCallSessionImpl> videoSessionList = new ArrayList<ImsCallSessionImpl>();
        for (ImsCallSessionImpl session : mSessionList) {
            if (Utilities.isVideoCall(session.getCallProfile().mCallType)) {
                videoSessionList.add(session);
            }
        }

        // If there isn't any video call session, return null.
        return videoSessionList.size() > 0 ? videoSessionList : null;
    }

    public ImsCallSessionImpl getAliveVideoCallSession() {
        ImsCallSessionImpl session = null;
        session = getAliveCallSession();
        if (session == null) return null;

        if (Utilities.isVideoCall(session.getCallProfile().mCallType)) {
            return session;
        }

        Log.d(TAG, "Can not found any video call in alive state, return null.");
        return null;
    }

    public ImsCallSessionImpl getAliveCallSession() {
        for (ImsCallSessionImpl session : mSessionList) {
            if (session.isAlive()) return session;
        }

        Log.w(TAG, "Can not found any call in active state, return null.");
        return null;
    }

    /**
     * The current call:
     * 1. The incoming call or out going call.
     * 2. The alive call.
     * 3. The first call if all the call isn't alive.
     */
    public ImsCallSessionImpl getCurrentCallSession() {
        ImsCallSessionImpl aliveCall = null;
        for (ImsCallSessionImpl session : mSessionList) {
            if (session.isAlive()) {
                aliveCall = session;
            }
            if (session.getState() < State.ESTABLISHED) {
                // As it is the incoming call or out going call, return it.
                return session;
            }
        }

        // Do not find the incoming call or out going call.
        if (aliveCall != null) {
            return aliveCall;
        } else if (mSessionList.size() > 0){
            return mSessionList.get(0);
        } else {
            return null;
        }
    }

    public ImsCallSessionImpl getConfCallSession() {
        for (ImsCallSessionImpl session : mSessionList) {
            if (session.isMultiparty()) return session;
        }

        Log.w(TAG, "Can not found any conference call.");
        return null;
    }

    public ImsCallSessionImpl getCouldInviteCallSession() {
        for (ImsCallSessionImpl session : mSessionList) {
            if (session.isMultiparty()) {
                continue;
            } else if (session.getState() > State.NEGOTIATING) {
                return session;
            }
        }

        return null;
    }

    public void onSRVCCStateChanged(int state) {
        if (!isCallFunEnabled()) {
            Log.d(TAG, "As call function disabled, do not handle the SRVCC state changed.");
            return;
        }

        Log.d(TAG, "The SRVCC state changed, state: " + state);
        mSRVCCHandler.sendEmptyMessage(state);
    }

    private ImsCallSessionImpl createCallSession(ImsCallProfile profile,
            IImsCallSessionListener listener, ImsVideoCallProviderImpl videoCallProvider,
            int callDir, boolean requiredCNI) {
        synchronized (mSessionList) {
            ImsCallSessionImpl session = new ImsCallSessionImpl(mContext, this, profile, listener,
                    videoCallProvider, callDir, mCurRatType, requiredCNI);

            // Add this call session to the list.
            addCall(session);

            return session;
        }
    }

    private void inviteCall(ImsCallSessionImpl confSession) {
        if (Utilities.DEBUG) Log.i(TAG, "Try to invite call for the conference: " + confSession);

        if (confSession == null) {
            Log.e(TAG, "Failed to invite the call for the conference as it is null.");
            return;
        }

        // Invite participant.
        boolean success = false;
        try {
            ImsCallSessionImpl callSession = confSession.getNeedInviteCall();
            if (callSession == null) {
                Log.d(TAG, "All the calls already finish invite. Resume the conference call.");
                IImsCallSessionListener confListener = confSession.getListener();
                if (confListener != null) {
                    boolean isHeld = confSession.isHeld() || confSession.isAlive();
                    Log.d(TAG, "The conference call session is held: " + isHeld);
                    if (isHeld) {
                        // As conference connected, update it as resumed.
                        confSession.resume(confSession.getResumeMediaProfile());

                        confSession.updateAliveState(true);
                        confListener.callSessionResumed(confSession.getCallProfile());
                    }
                }
                return;
            }

            if (callSession.getState() < State.ESTABLISHED
                    || callSession.getState() > State.TERMINATING) {
                // It means this call is terminated or do not setup finished.
                // We'd like to ignore this call.
                Log.w(TAG, "As state error, ignore this call" + callSession + " to conference"
                        + confSession);
            } else {
                int res = mICall.confAddMembers(Integer.valueOf(confSession.getCallId()), null,
                        new int[] { Integer.valueOf(callSession.getCallId()) });
                if (res == Result.FAIL) {
                    // Invite this call failed.
                    Log.w(TAG, "Failed to invite the call " + callSession);
                    String text = mContext.getString(R.string.vowifi_conf_invite_failed)
                            + callSession.getCallee();
                    MyToast.makeText(mContext, text, Toast.LENGTH_LONG).show();
                } else {
                    success = true;
                }
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to invite the call as catch the RemoteException e: " + e);
        }

        if (!success) {
            // If the invite request send failed, we need try to invite other calls.
            mHandler.sendMessage(mHandler.obtainMessage(MSG_INVITE_CALL, confSession));
        }
    }

    private void answerCall(ImsCallSessionImpl callSession) {
        Log.d(TAG, "Auto answer the call: " + callSession);

        if (callSession == null) {
            Log.e(TAG, "Failed to answer the call as it is null.");
            return;
        }

        // To check the current call count.
        // 1. If there isn't any call, we could answer it immediately.
        // 2. If there is only one active call, we need hold the active call first.
        // 3. If there are two calls, need terminate the hold call, and hold the active call first.
        try {
            switch (getCallCount()) {
                case 1:
                    // There is one call, it should be the new incoming call.
                    callSession.autoAnswer();
                    break;
                case 2:
                    // Hold the active call.
                    ImsCallSessionImpl aliveCall = getAliveCallSession();
                    if (aliveCall != null) {
                        aliveCall.hold(aliveCall.getHoldMediaProfile());
                    }

                    // Answer the incoming call.
                    callSession.autoAnswer();
                    break;
                case 3:
                    for (ImsCallSessionImpl call : mSessionList) {
                        if (!call.isAlive()) {
                            // Terminate the hold call.
                            call.terminate(ImsReasonInfo.CODE_USER_TERMINATED);
                        } else if (call != callSession) {
                            // It is the active call, hold it.
                            call.hold(call.getHoldMediaProfile());
                        }
                    }
                    callSession.autoAnswer();
                    break;
                default:
                    Log.e(TAG, "Shouldn't be here, the call count: " + getCallCount());
                    break;
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to auto answer the call as catch the ex: " + e.toString());
        }
    }

    private void handleEvent(String json) {
        try {
            JSONObject jObject = new JSONObject(json);
            int sessionId = jObject.optInt(JSONUtils.KEY_ID, -1);
            if (sessionId < 0) {
                Log.w(TAG, "The call session id is " + sessionId + ", need check.");
            }

            ImsCallSessionImpl callSession = getCallSession(Integer.toString(sessionId));
            if (callSession == null) {
                Log.w(TAG, "Can not found the call session for this call id: " + sessionId);
            }

            String eventName = jObject.optString(JSONUtils.KEY_EVENT_NAME, "");
            Log.d(TAG, "Handle the event '" + eventName + "' for the call: " + sessionId);

            int eventCode = jObject.optInt(JSONUtils.KEY_EVENT_CODE, -1);
            if (callSession != null) {
                VoWifiCallStateTracker tracker = callSession.getCallStateTracker();
                if (tracker != null) {
                    tracker.updateActionResponse(eventCode);
                }
            }

            switch (eventCode) {
                case JSONUtils.EVENT_CODE_CALL_INCOMING: {
                    boolean isVideo = jObject.optBoolean(JSONUtils.KEY_IS_VIDEO, false);
                    String callee = jObject.optString(JSONUtils.KEY_PHONE_NUM, "");
                    handleCallIncoming(sessionId, false /* not conference */, isVideo, callee);
                    break;
                }
                case JSONUtils.EVENT_CODE_CALL_OUTGOING:
                case JSONUtils.EVENT_CODE_CONF_OUTGOING:
                    Log.d(TAG, "Do nothing when the call or conference outgoing.");
                    break;
                case JSONUtils.EVENT_CODE_CALL_ALERTED: {
                    String phoneNumber = jObject.optString(JSONUtils.KEY_PHONE_NUM);
                    boolean isVideo = jObject.optBoolean(JSONUtils.KEY_IS_VIDEO, false);
                    String alertType = jObject.optString(JSONUtils.KEY_ALERT_TYPE);
                    handleCallAlerted(callSession, phoneNumber, isVideo, alertType);
                    break;
                }
                case JSONUtils.EVENT_CODE_CALL_TALKING: {
                    String phoneNumber = jObject.optString(JSONUtils.KEY_PHONE_NUM);
                    boolean isVideo = jObject.optBoolean(JSONUtils.KEY_IS_VIDEO, false);
                    boolean isPeerSupportVideo =
                            jObject.optBoolean(JSONUtils.KEY_PEER_SUPPORT_VIDEO, true);
                    handleCallTalking(callSession, phoneNumber, isVideo, isPeerSupportVideo);
                    break;
                }
                case JSONUtils.EVENT_CODE_CALL_TERMINATE: {
                    int stateCode = jObject.optInt(
                            JSONUtils.KEY_STATE_CODE, ImsReasonInfo.CODE_USER_TERMINATED);
                    handleCallTermed(callSession, stateCode);
                    break;
                }
                case JSONUtils.EVENT_CODE_CALL_HOLD_OK:
                case JSONUtils.EVENT_CODE_CALL_HOLD_FAILED:
                case JSONUtils.EVENT_CODE_CALL_RESUME_OK:
                case JSONUtils.EVENT_CODE_CALL_RESUME_FAILED:
                case JSONUtils.EVENT_CODE_CALL_HOLD_RECEIVED:
                case JSONUtils.EVENT_CODE_CALL_RESUME_RECEIVED:
                case JSONUtils.EVENT_CODE_CONF_HOLD_OK:
                case JSONUtils.EVENT_CODE_CONF_HOLD_FAILED:
                case JSONUtils.EVENT_CODE_CONF_RESUME_OK:
                case JSONUtils.EVENT_CODE_CONF_RESUME_FAILED:
                case JSONUtils.EVENT_CODE_CONF_HOLD_RECEIVED:
                case JSONUtils.EVENT_CODE_CONF_RESUME_RECEIVED: {
                    handleCallHoldOrResume(eventCode, callSession);
                    break;
                }
                case JSONUtils.EVENT_CODE_CALL_UPDATE_VIDEO_OK: {
                    int videoType = jObject.optInt(JSONUtils.KEY_VIDEO_TYPE);
                    handleCallUpdate(callSession, videoType);
                    break;
                }
                case JSONUtils.EVENT_CODE_CALL_UPDATE_VIDEO_FAILED: {
                    int stateCode = jObject.optInt(JSONUtils.KEY_STATE_CODE);
                    handleCallUpdateFailed(callSession, stateCode);
                    break;
                }
                case JSONUtils.EVENT_CODE_CALL_ADD_VIDEO_REQUEST: {
                    int videoType = jObject.optInt(JSONUtils.KEY_VIDEO_TYPE);
                    handleCallAddVideoRequest(callSession, videoType);
                    break;
                }
                case JSONUtils.EVENT_CODE_CALL_ADD_VIDEO_CANCEL: {
                    handleCallAddVideoCancel(callSession);
                    break;
                }
                case JSONUtils.EVENT_CODE_CALL_RTP_RECEIVED:
                case JSONUtils.EVENT_CODE_CONF_RTP_RECEIVED: {
                    boolean isVideo = jObject.optBoolean(JSONUtils.KEY_IS_VIDEO, false);
                    boolean isReceived = jObject.optBoolean(JSONUtils.KEY_RTP_RECEIVED, true);
                    handleRTPReceived(callSession, isVideo, isReceived);
                    break;
                }
                case JSONUtils.EVENT_CODE_CALL_IS_FOCUS: {
                    handleCallIsFocus(callSession);
                    break;
                }
                case JSONUtils.EVENT_CODE_CALL_IS_EMERGENCY: {
                    String urnUri = jObject.optString(JSONUtils.KEY_ECALL_IND_URN_URI, "");
                    int type = jObject.optInt(JSONUtils.KEY_ECALL_IND_ACTION_TYPE, -1);
                    String reason = jObject.optString(JSONUtils.KEY_ECALL_IND_REASON, "");
                    handleCallIsEmergency(callSession, urnUri, reason, type);
                    break;
                }
                case JSONUtils.EVENT_CODE_CONF_ALERTED: {
                    handleConfAlerted(callSession);
                    break;
                }
                case JSONUtils.EVENT_CODE_CONF_CONNECTED: {
                    boolean isVideo = jObject.optBoolean(JSONUtils.KEY_IS_VIDEO, false);
                    handleConfConnected(callSession, isVideo);
                    break;
                }
                case JSONUtils.EVENT_CODE_CONF_DISCONNECTED: {
                    int stateCode = jObject.optInt(
                            JSONUtils.KEY_STATE_CODE, ImsReasonInfo.CODE_USER_TERMINATED);
                    handleConfDisconnected(callSession, stateCode);
                    break;
                }
                case JSONUtils.EVENT_CODE_CONF_INVITE_ACCEPT:
                case JSONUtils.EVENT_CODE_CONF_INVITE_FAILED:
                case JSONUtils.EVENT_CODE_CONF_KICK_ACCEPT:
                case JSONUtils.EVENT_CODE_CONF_KICK_FAILED: {
                    String phoneNumber = jObject.optString(JSONUtils.KEY_PHONE_NUM, "");
                    String phoneUri = jObject.optString(JSONUtils.KEY_SIP_URI, "");
                    handleConfParticipantsChanged(
                            eventCode, callSession, phoneNumber, phoneUri, null);
                    break;
                }
                case JSONUtils.EVENT_CODE_CONF_PART_UPDATE: {
                    String phoneNumber = jObject.optString(JSONUtils.KEY_PHONE_NUM, "");
                    String phoneUri = jObject.optString(JSONUtils.KEY_SIP_URI, "");
                    String newStatus = jObject.optString(JSONUtils.KEY_CONF_PART_NEW_STATUS, "");
                    handleConfParticipantsChanged(
                            eventCode, callSession, phoneNumber, phoneUri, newStatus);
                    break;
                }
                case JSONUtils.EVENT_CODE_CONF_REFER_NOTIFIED: {
                    Message msg = new Message();
                    msg.what = MSG_TERM_CHILD_CALL;
                    msg.obj = String.valueOf(jObject.optInt(JSONUtils.KEY_CONF_CHILD_ID));
                    mHandler.sendMessageDelayed(msg, TERM_CHILD_CALL_DELAY);
                    break;
                }
                case JSONUtils.EVENT_CODE_LOCAL_VIDEO_RESIZE:
                case JSONUtils.EVENT_CODE_REMOTE_VIDEO_RESIZE: {
                    // FIXME: This callback do not give the call session id, so need find the
                    // alive video call from the call list.
                    if (callSession == null) {
                        callSession = getAliveVideoCallSession();
                    }
                    int width = jObject.optInt(JSONUtils.KEY_VIDEO_WIDTH, -1);
                    int height = jObject.optInt(JSONUtils.KEY_VIDEO_HEIGHT, -1);

                    handleVideoResize(eventCode, callSession, width, height);
                    break;
                }
                case JSONUtils.EVENT_CODE_LOCAL_VIDEO_LEVEL_UPDATE: {
                    int level = jObject.optInt(JSONUtils.KEY_VIDEO_LEVEL, -1);
                    handleVideoLevelUpdate(callSession, level);
                    break;
                }
                case JSONUtils.EVENT_CODE_CALL_RTCP_CHANGED:
                case JSONUtils.EVENT_CODE_CONF_RTCP_CHANGED: {
                    ImsCallSessionImpl aliveCallSession = getAliveCallSession();
                    if (aliveCallSession != null && aliveCallSession.equals(callSession)) {
                        int lose = jObject.optInt(JSONUtils.KEY_RTCP_LOSE, -1);
                        int jitter = jObject.optInt(JSONUtils.KEY_RTCP_JITTER, -1);
                        int rtt = jObject.optInt(JSONUtils.KEY_RTCP_RTT, -1);
                        boolean isVideo = jObject.optBoolean(JSONUtils.KEY_IS_VIDEO, false);
                        handleRTCPChanged(callSession, lose, jitter, rtt, isVideo);
                    } else {
                        Log.w(TAG, "The alive call do not same as the rtcp changed. Ignore this: "
                                + callSession + ", and the alive call is: " + aliveCallSession);
                    }
                    break;
                }
                case JSONUtils.EVENT_CODE_USSD_INFO_RECEIVED: {
                    String info = jObject.optString(JSONUtils.KEY_USSD_INFO_RECEIVED, "");
                    int mode = jObject.optInt(JSONUtils.KEY_USSD_MODE, -1);
                    handleUssdInfoReceived(callSession, info, mode);
                    break;
                }
                case JSONUtils.EVENT_CODE_VOICE_CODEC: {
                    String codecName = jObject.optString(JSONUtils.KEY_VOICE_CODEC, "");
                    handleVoiceCodecNegociated(callSession, codecName);
                    break;
                }
                case JSONUtils.EVENT_CODE_CALL_IS_FORWARDED: {
                    if (callSession != null) {
                        callSession.updateAsCallIsForwarded();
                        MyToast.makeText(mContext, R.string.vowifi_call_forwarded, Toast.LENGTH_LONG)
                                .show();
                    }
                    break;
                }
                case JSONUtils.EVENT_CODE_CALL_REQUIRE_DEREGISTER: {
                    // The call session maybe already termed, but we need handle this error.
                    if (mListener != null) {
                        mListener.onUnsolicitedRequest(UnsolicitedCode.DEREGISTER_AND_RETRY_AFTER);
                    }
                    break;
                }
                case JSONUtils.EVENT_CODE_CALL_REQUIRE_ALERT_INFO: {
                    String infoType = jObject.optString(JSONUtils.KEY_ALERT_TYPE, "");
                    if (JSONUtils.ALERT_INFO_CALL_FAILURE.equals(infoType)) {
                        MyToast.makeText(mContext, R.string.vowifi_call_failure, Toast.LENGTH_LONG)
                                .show();
                    }
                    break;
                }
                default:
                    Log.w(TAG, "The event '" + eventName + "' do not handle, please check!");
            }
        } catch (JSONException e) {
            Log.e(TAG, "Can not handle the json, catch the JSONException e: " + e);
        } catch (RemoteException e) {
            Log.e(TAG, "Can not handle the event, catch the RemoteException e: " + e);
        }
    }

    private void handleCallAlerted(ImsCallSessionImpl callSession, String phoneNumber,
            boolean isVideo, String alertType) throws RemoteException {
        if (Utilities.DEBUG) Log.i(TAG, "Handle the alerted or outgoing call.");
        if (callSession == null) {
            Log.w(TAG, "[handleAlertedOrOutgoing] The call session is null");
            return;
        }

        // Bug 1093173: add supplementary notification for vowifi.
        if (Utilities.DEBUG) Log.i(TAG, "handleCallAlerted alertType is " + alertType);
        if (JSONUtils.ALERT_TYPE_CALL_WAITING.equals(alertType)) {
            MyToast.makeText(mContext, R.string.vowifi_call_waiting, Toast.LENGTH_LONG)
                    .show();
        } else if(JSONUtils.ALERT_TYPE_CALL_FORWARD.equals(alertType)) {
            MyToast.makeText(mContext, R.string.vowifi_call_forwarded, Toast.LENGTH_LONG)
                    .show();
        }

        ImsStreamMediaProfile mediaProfile = callSession.getMediaProfile();
        mediaProfile.mAudioDirection = ImsStreamMediaProfile.DIRECTION_SEND_RECEIVE;
        if (isVideo) {
            mediaProfile.mVideoDirection = ImsStreamMediaProfile.DIRECTION_SEND_RECEIVE;
            mediaProfile.mVideoQuality = ImsStreamMediaProfile.VIDEO_QUALITY_QVGA_PORTRAIT;
        } else {
            mediaProfile.mVideoDirection = ImsStreamMediaProfile.DIRECTION_INVALID;
            mediaProfile.mVideoQuality = ImsStreamMediaProfile.VIDEO_QUALITY_NONE;
        }
        callSession.updateState(State.NEGOTIATING);

        ImsCallProfile newCallProfile = null;
        String callee = callSession.getCallee();
        if (!callee.equals(phoneNumber) && callSession.isCallForwarded()) {
            // The call is forwarded, and the callee do not same as the phone number.
            // We need set the callee as the new phone number, and notify the update.
            newCallProfile = callSession.setCallee(phoneNumber);
        }

        IImsCallSessionListener listener = callSession.getListener();
        if (listener != null) {
            listener.callSessionProgressing(mediaProfile);

            if (newCallProfile != null) {
                String oi = newCallProfile.getCallExtra(ImsCallProfile.EXTRA_OI);
                Log.d(TAG, "Update the callee as EXTRA_OI to " + oi);

                listener.callSessionUpdated(newCallProfile);
            }
        }
    }

    private void handleCallIncoming(int sessionId, boolean isConference, boolean isVideo,
            String callee) throws RemoteException {
        if (Utilities.DEBUG) Log.i(TAG, "Handle the incoming call.");

        // Create the profile for this incoming call.
        ImsCallProfile callProfile = null;
        ImsStreamMediaProfile mediaProfile = null;
        if (isVideo && !isConference) {
            callProfile = new ImsCallProfile(ImsCallProfile.SERVICE_TYPE_NORMAL,
                    ImsCallProfile.CALL_TYPE_VT);
            mediaProfile = new ImsStreamMediaProfile(
                    ImsStreamMediaProfile.AUDIO_QUALITY_AMR_WB,
                    ImsStreamMediaProfile.DIRECTION_SEND_RECEIVE,
                    ImsStreamMediaProfile.VIDEO_QUALITY_QCIF,
                    ImsStreamMediaProfile.DIRECTION_SEND_RECEIVE);
        } else {
            callProfile = new ImsCallProfile(ImsCallProfile.SERVICE_TYPE_NORMAL,
                    ImsCallProfile.CALL_TYPE_VOICE);
            mediaProfile = new ImsStreamMediaProfile();
        }

        callProfile.setCallExtra(ImsCallProfile.EXTRA_OI, callee);
        callProfile.setCallExtra(ImsCallProfile.EXTRA_CNA, null);
        callProfile.setCallExtraInt(
                ImsCallProfile.EXTRA_CNAP, ImsCallProfile.OIR_PRESENTATION_NOT_RESTRICTED);
        callProfile.setCallExtraInt(ImsCallProfile.EXTRA_OIR,
                PhoneNumberUtils.isGlobalPhoneNumber(callee)
                        ? ImsCallProfile.OIR_PRESENTATION_NOT_RESTRICTED
                        : ImsCallProfile.OIR_PRESENTATION_RESTRICTED);
        callProfile.setCallExtraBoolean(ImsCallProfile.EXTRA_CONFERENCE, isConference);

        ImsCallSessionImpl callSession = createMTCallSession(callProfile, null);
        callSession.setCallee(callee);
        callSession.setCallId(sessionId);
        callSession.updateMediaProfile(mediaProfile);
        callSession.updateState(State.NEGOTIATING);
        callSession.updateRequestAction(VoWifiCallStateTracker.ACTION_INCOMING);

        // If the current incoming call action is reject or call function is disabled,
        // we need reject the incoming call and give the reason as the local call is busy.
        // Note: The reject action will be set when the secondary card is in the calling,
        //       then we need reject all the incoming call from the VOWIFI.
        if (!isCallFunEnabled()
                || mIncomingCallAction == IncomingCallAction.REJECT
                || (!Utilities.isCallWaitingEnabled() && getCallCount() > 1)
                || !canAcceptIncomingCall()) {
            callSession.reject(ImsReasonInfo.CODE_USER_DECLINE);
        } else {
            // Send the incoming call callback.
            if (mListener != null) {
                mListener.onCallIncoming(callSession);
                callSession.incomingNotified();
            }

            // If the user enable the auto answer prop, we need answer this call immediately.
            boolean isAutoAnswer = SystemProperties.getBoolean(PROP_KEY_AUTO_ANSWER, false);
            if (isAutoAnswer) {
                mHandler.sendMessage(mHandler.obtainMessage(MSG_AUTO_ANSWER, callSession));
            }
        }
    }

    private void handleCallTermed(ImsCallSessionImpl callSession, int termReasonCode)
            throws RemoteException {
        if (Utilities.DEBUG) Log.i(TAG, "Handle the termed call.");
        if (callSession == null) {
            Log.w(TAG, "[handleCallTermed] The call session is null.");
            return;
        }

        // Handle next action as failed.
        callSession.handleNextAction(false);

        if (callSession.isConferenceCall()) {
            // If the conference call terminate, we'd like to terminate all the child calls.
            callSession.terminateChildCalls(termReasonCode);
        }

        // As call terminated, stop all the video.
        // Note: This stop action need before call session terminated callback. Otherwise,
        //       the video call provider maybe changed to null.
        ImsVideoCallProviderImpl videoCallProvider = callSession.getVideoCallProviderImpl();
        if (videoCallProvider != null) {
            videoCallProvider.stopAll();
        }

        int oldState = callSession.getState();
        callSession.updateState(State.TERMINATED);
        IImsCallSessionListener listener = callSession.getListener();
        ImsCallSessionImpl confSession = callSession.getConfCallSession();
        if (listener != null) {
            if (callSession.isUssdCall()) {
                Log.d(TAG, "As ussd start failed, give the ussd msg received as not support.");
                listener.callSessionUssdMessageReceived(
                        CommandsInterface.USSD_MODE_NOT_SUPPORTED, "Start ussd failed.");
            }

            if (callSession.isEmergencyCall()
                    && termReasonCode == ImsReasonInfo.CODE_LOCAL_CALL_CS_RETRY_REQUIRED) {
                // For the emergency call, if the terminate reason is CS retry, we need
                // reset the terminate reason as the emergency call needn't CS retry.
                termReasonCode = ImsReasonInfo.CODE_USER_TERMINATED_BY_REMOTE;
            }

            ImsReasonInfo info = new ImsReasonInfo(termReasonCode, termReasonCode,
                    "The call terminated.");
            if (oldState < State.NEGOTIATING) {
                // It means the call do not ringing now, so we need give the call session start
                // failed call back.
                listener.callSessionInitiatedFailed(info);
            } else {
                if (confSession != null) {
                    // If the conference session is not null, it means this call session was
                    // invite as the participant, and when it received the terminate action,
                    // need set the info code as CODE_LOCAL_ENDED_BY_CONFERENCE_MERGE.
                    info.mCode = ImsReasonInfo.CODE_LOCAL_ENDED_BY_CONFERENCE_MERGE;
                }

                // Terminate the call as normal.
                if (confSession != null
                        && !confSession.equals(callSession)
                        && listener.equals(confSession.getListener())) {
                    // FIXME: This case is added for sequence in time.
                    // After the user start the merge action, but not notify as merge complete,
                    // the host call was invited into another conference call, then the host call
                    // listener can not set as null.

                    // If the current call isn't conference call, but the listener is same as
                    // the conference call's listener, we needn't notify the call terminated.
                    Log.w(TAG, "This call's listener is same as the conference call's listener.");
                } else {
                    listener.callSessionTerminated(info);
                }
            }
        } else if (!callSession.getCallStateTracker().isMOCall()) {
            // The call is MT call, but listener is null. It means the call do not attach
            // session now. We'd like to handle the terminate after 500ms.
            mHandler.sendMessageDelayed(mHandler.obtainMessage(MSG_RETRY_TERMINATE_CALL,
                    termReasonCode, -1, callSession), 500);
            Log.d(TAG, "The incoming call do not attach now, handle the terminate event later.");
            return;
        }

        // After give the callback, close this call session as it is a participant
        // of the conference.
        if (confSession != null && !confSession.equals(callSession)) {
            callSession.close();
        }

        removeCall(callSession);
    }

    private void handleCallTalking(ImsCallSessionImpl callSession, String phoneNumber,
            boolean isVideo, boolean isPeerSupportVideo) throws RemoteException {
        if (Utilities.DEBUG) Log.i(TAG, "Handle the talking call.");
        if (callSession == null) {
            Log.w(TAG, "[handleCallTalking] The call session is null.");
            return;
        }

        callSession.updateState(State.ESTABLISHED);

        // Update the call type, as if the user accept the video call as audio call,
        // isVideo will be false. Then we need update this to call profile.
        ImsCallProfile profile = callSession.getCallProfile();
        ImsCallProfile remoteProfile = callSession.getRemoteCallProfile();
        boolean wasVideo = Utilities.isVideoCall(profile.mCallType);
        if (!isVideo) {
            profile.mCallType = ImsCallProfile.CALL_TYPE_VOICE;
            // Update remote call type.
            if (isPeerSupportVideo && callSession.supportVideoCall()) {
                // Peer support video.
                remoteProfile.mCallType = ImsCallProfile.CALL_TYPE_VIDEO_N_VOICE;
            } else {
                // Peer do not support video.
                remoteProfile.mCallType = ImsCallProfile.CALL_TYPE_VOICE;
            }

            if (wasVideo) {
                // It means we start as video call, but remote accept as voice call.
                // Prompt the toast to alert the user.
                MyToast.makeText(mContext, R.string.vowifi_remove_video_success,
                        Toast.LENGTH_LONG).show();
            }
        }
        // Update the data router state when the call talking.
        callSession.updateDataRouterState();

        // Set the new phone number as the secondary callee.
        String callee = callSession.getCallee();
        if (!callee.equals(phoneNumber)) {
            Log.d(TAG, "The number changed to " + phoneNumber + ", save as secondary callee.");
            callSession.setSecondaryCallee(phoneNumber);
        }

        IImsCallSessionListener listener = callSession.getListener();
        if (listener != null) {
            listener.callSessionInitiated(profile);
        }
    }

    private void handleCallHoldOrResume(int eventCode, ImsCallSessionImpl callSession)
            throws RemoteException {
        if (Utilities.DEBUG) Log.i(TAG, "Handle the hold or resume event.");
        if (callSession == null) {
            Log.w(TAG, "[handleCallHoldOrResume] The call session is null.");
            return;
        }

        IImsCallSessionListener listener = callSession.getListener();
        if (listener == null) {
            Log.w(TAG, "The call session's listener is null, can't alert the hold&resume result");
            return;
        }

        int toastTextResId = -1;
        switch (eventCode) {
            case JSONUtils.EVENT_CODE_CALL_HOLD_OK:
            case JSONUtils.EVENT_CODE_CONF_HOLD_OK: {
                // As the call hold, if the call is video call, we need stop all the video.
                ImsVideoCallProviderImpl videoProvider = callSession.getVideoCallProviderImpl();
                if (videoProvider != null) videoProvider.stopAll();

                callSession.updateAliveState(false /* held, do not alive */);
                listener.callSessionHeld(callSession.getCallProfile());
                callSession.handleNextAction(true);

                // Update the local call profile to mark the hold/hold received state.
                ImsStreamMediaProfile localMP = callSession.getLocalCallProfile().mMediaProfile;
                localMP.mAudioDirection =
                        localMP.mAudioDirection - ImsStreamMediaProfile.DIRECTION_RECEIVE;
                break;
            }
            case JSONUtils.EVENT_CODE_CALL_HOLD_FAILED:
            case JSONUtils.EVENT_CODE_CONF_HOLD_FAILED: {
                toastTextResId = R.string.vowifi_hold_fail;
                ImsReasonInfo holdFailedInfo = new ImsReasonInfo(ImsReasonInfo.CODE_UNSPECIFIED,
                        ImsReasonInfo.CODE_UNSPECIFIED, "Unknown reason");
                listener.callSessionHoldFailed(holdFailedInfo);
                callSession.handleNextAction(false);
                break;
            }
            case JSONUtils.EVENT_CODE_CALL_RESUME_OK:
            case JSONUtils.EVENT_CODE_CONF_RESUME_OK: {
                callSession.updateAliveState(true /* resumed, alive now */);
                listener.callSessionResumed(callSession.getCallProfile());

                // Update the local call profile to mark the hold/hold received state.
                ImsStreamMediaProfile localMP = callSession.getLocalCallProfile().mMediaProfile;
                localMP.mAudioDirection =
                        localMP.mAudioDirection + ImsStreamMediaProfile.DIRECTION_RECEIVE;
                break;
            }
            case JSONUtils.EVENT_CODE_CALL_RESUME_FAILED:
            case JSONUtils.EVENT_CODE_CONF_RESUME_FAILED: {
                toastTextResId = R.string.vowifi_resume_fail;
                ImsReasonInfo resumeFailedInfo = new ImsReasonInfo(ImsReasonInfo.CODE_UNSPECIFIED,
                        ImsReasonInfo.CODE_UNSPECIFIED, "Unknown reason");
                listener.callSessionResumeFailed(resumeFailedInfo);
                break;
            }
            case JSONUtils.EVENT_CODE_CALL_HOLD_RECEIVED:
                // Only the alert info for the normal call.
                toastTextResId = R.string.vowifi_hold_received;
            case JSONUtils.EVENT_CODE_CONF_HOLD_RECEIVED: {
                listener.callSessionHoldReceived(callSession.getCallProfile());

                // Update the local call profile to mark the hold/hold received state.
                ImsStreamMediaProfile localMP = callSession.getLocalCallProfile().mMediaProfile;
                localMP.mAudioDirection =
                        localMP.mAudioDirection - ImsStreamMediaProfile.DIRECTION_SEND;
                break;
            }
            case JSONUtils.EVENT_CODE_CALL_RESUME_RECEIVED:
                // Only the alert info for the normal call.
                toastTextResId = R.string.vowifi_resume_received;
            case JSONUtils.EVENT_CODE_CONF_RESUME_RECEIVED: {
                listener.callSessionResumeReceived(callSession.getCallProfile());

                // Update the local call profile to mark the hold/hold received state.
                ImsStreamMediaProfile localMP = callSession.getLocalCallProfile().mMediaProfile;
                localMP.mAudioDirection =
                        localMP.mAudioDirection + ImsStreamMediaProfile.DIRECTION_SEND;
                break;
            }
            default: {
                Log.w(TAG, "The event " + eventCode + " do not belongs to hold or resume.");
                break;
            }
        }

        // If set the toast text, we need show the toast.
        if (toastTextResId > 0) {
            MyToast.makeText(mContext, toastTextResId, Toast.LENGTH_LONG).show();
        }
    }

    private void handleCallUpdate(ImsCallSessionImpl callSession, int newVideoType)
            throws RemoteException {
        if (Utilities.DEBUG) Log.i(TAG, "Handle the call update ok.");
        if (callSession == null) {
            Log.w(TAG, "[handleCallUpdate] The call session is null.");
            return;
        }

        ImsVideoCallProviderImpl videoCallProvider = callSession.getVideoCallProviderImpl();
        if (videoCallProvider == null) {
            Log.e(TAG, "The video call profile is null. Shouldn't be here, please check!!!");
            return;
        }

        int newCallType = VideoType.getCallType(newVideoType);
        int oldCallType = callSession.getCallProfile().mCallType;
        if (newCallType == oldCallType) {
            Log.e(TAG, "It means there isn't any update. Please check videoType: " + newVideoType);
            handleCallUpdateFailed(callSession, 0);
            return;
        }

        // The new call type is different from the old call type. Update the call type.
        callSession.updateCallType(newCallType);

        if (videoCallProvider.isWaitForModifyResponse()) {
            int oldVideoType = VideoType.getNativeVideoType(oldCallType);
            if (oldVideoType < newVideoType) {
                // It means the new call type upgrade, and we'd like to prompt the toast
                // as remote accept the upgrade request.
                MyToast.makeText(mContext, R.string.vowifi_request_update_success,
                        Toast.LENGTH_LONG).show();
            }
        }

        if (Utilities.isAudioCall(oldCallType)) {
            // Notify the call update from audio call to video call.
            if (callSession.isAlive() && mListener != null) {
                mListener.onAliveCallUpdate(true /* is video now */);
            }
        } else {
            // The old call type should be video call.

            // As remove video, we'd like to stop all the video before the response.
            // If the surface destroyed, the remove render action will be blocked, and
            // the remove action will be failed actually. So we'd like to stop all the video
            // or stop the reception or transmission before give the response.
            if (Utilities.isAudioCall(newCallType)) {
                // It means the new call type is audio call, and the old call type is video call.
                // We'd like to prompt the toast as video call fall-back.
                MyToast.makeText(mContext, R.string.vowifi_remove_video_success, Toast.LENGTH_LONG)
                        .show();
                videoCallProvider.stopAll();
            } else if (Utilities.isVideoTX(newCallType)) {
                // Change from VT to VT_TX. It means we need stop the reception.
                MyToast.makeText(mContext, R.string.vowifi_update_to_tx_success, Toast.LENGTH_LONG)
                        .show();
                videoCallProvider.stopReception();
            } else if (Utilities.isVideoRX(newCallType)) {
                // Change from VT to VT_RX. It means we need stop the transmission.
                MyToast.makeText(mContext, R.string.vowifi_update_to_rx_success, Toast.LENGTH_LONG)
                        .show();
                videoCallProvider.stopTransmission();
            }
        }

        VideoProfile newProfile = VideoType.getVideoProfile(newVideoType);
        // As the response is success, the request profile will be same as the response.
        videoCallProvider.receiveSessionModifyResponse(
                VideoProvider.SESSION_MODIFY_REQUEST_SUCCESS,
                newProfile /* request profile */,
                newProfile /* response profile */);
    }

    private void handleCallUpdateFailed(ImsCallSessionImpl callSession, int stateCode)
            throws RemoteException {
        if (Utilities.DEBUG) Log.i(TAG, "Handle the call update failed.");
        if (callSession == null) {
            Log.w(TAG, "[handleCallUpdateFailed] The call session is null.");
            return;
        }

        // Failed to update the call type.
        IImsCallSessionListener listener = callSession.getListener();
        if (listener != null) {
            ImsReasonInfo errorInfo = new ImsReasonInfo(ImsReasonInfo.CODE_MEDIA_UNSPECIFIED,
                    stateCode, "Update failed as error code: " + stateCode);
            listener.callSessionUpdateFailed(errorInfo);
        }

        ImsVideoCallProviderImpl videoCallProvider = callSession.getVideoCallProviderImpl();
        if (videoCallProvider != null) {
            if (videoCallProvider.isWaitForModifyResponse()) {
                // Show toast for failed action.
                MyToast.makeText(mContext, R.string.vowifi_request_update_failed, Toast.LENGTH_LONG)
                        .show();
            }

            videoCallProvider.stopAll();
            videoCallProvider.receiveSessionModifyResponse(
                    VideoProvider.SESSION_MODIFY_REQUEST_FAIL, null, null);
        }
    }

    private void handleCallAddVideoRequest(ImsCallSessionImpl callSession, int videoType) {
        if (Utilities.DEBUG) Log.i(TAG, "Handle the call add video request.");
        if (callSession == null) {
            Log.w(TAG, "[handleCallAddVideoRequest] The call session is null.");
            return;
        }

        if (callSession.couldUpgradeOrDowngrade()) {
            ImsVideoCallProviderImpl videoProvider = callSession.getVideoCallProviderImpl();
            if (videoProvider != null) {
                VideoProfile newProfile = VideoType.getVideoProfile(videoType);
                videoProvider.receiveSessionModifyRequest(newProfile);
            }
        } else {
            // Send the modify response as reject to keep as voice call.
            int nativeCallType =
                    VideoType.getNativeVideoType(callSession.getCallProfile().mCallType);
            callSession.sendModifyResponse(nativeCallType);
        }
    }

    private void handleCallAddVideoCancel(ImsCallSessionImpl callSession) {
        if (Utilities.DEBUG) Log.i(TAG, "Handle the call add video cancel.");
        if (callSession == null) {
            Log.w(TAG, "[handleCallAddVideoCancel] The call session is null.");
            return;
        }

        ImsVideoCallProviderImpl videoProvider = callSession.getVideoCallProviderImpl();
        if (videoProvider != null) {
            videoProvider.receiveSessionModifyResponse(
                    VideoProvider.SESSION_MODIFY_REQUEST_FAIL, null, null);
        }
    }

    private void handleCallIsFocus(ImsCallSessionImpl callSession) throws RemoteException {
        if (Utilities.DEBUG) Log.i(TAG, "Handle the call is focus.");
        if (callSession == null) {
            Log.w(TAG, "[handleCallIsFocus] The call session is null.");
            return;
        }

        if (callSession.isFocus()) {
            Log.d(TAG, "Already added to conference, needn't update the is_focus state.");
            return;
        }

        callSession.updateAsIsFocus();

        ImsCallProfile callProfile = callSession.getCallProfile();
        // FIXME: Add a new extra as "EXTRA_IS_FOCUS" to marked is focus. And needn't
        //        set the original extra "EXTRA_CONFERENCE".
        callProfile.setCallExtraBoolean(EXTRA_IS_FOCUS, true);

        IImsCallSessionListener listener = callSession.getListener();
        if (listener != null) {
            listener.callSessionUpdated(callProfile);
        }

        MyToast.makeText(mContext, R.string.vowifi_call_is_focus, Toast.LENGTH_LONG).show();
    }

    private void handleCallIsEmergency(ImsCallSessionImpl callSession, String urnUri, String reason,
            int actionType) throws RemoteException {
        if (Utilities.DEBUG) Log.i(TAG, "Handle the call is emergency.");
        if (callSession == null) {
            Log.w(TAG, "[handleCallIsEmergency] The call session is null.");
            return;
        }

        IImsCallSessionListener listener = callSession.getListener();
        if (listener != null) {
            if (callSession.isEmergencyCall()) {
                ImsReasonInfo info = new ImsReasonInfo(ImsReasonInfo.CODE_EMERGENCY_PERM_FAILURE,
                        ImsReasonInfo.CODE_EMERGENCY_PERM_FAILURE, reason);
                listener.callSessionInitiatedFailed(info);
            } else {
                // Receive 380 from service for a normal call
                Log.d(TAG, "Start a normal call, but get 380 from service, urnUri: " + urnUri);
                int category = EMUtils.getEmergencyCallCategory(urnUri);
                int reasonCode = ImsReasonInfo.CODE_SIP_ALTERNATE_EMERGENCY_CALL;
                String emMessage = String.valueOf(category);
                if (category < EMUtils.CATEGORY_VALUE_NONE) {
                    // Do not find the matched category.
                    if (TextUtils.isEmpty(urnUri)) {
                        reasonCode = ImsReasonInfo.CODE_LOCAL_CALL_CS_RETRY_REQUIRED;
                        emMessage = "";
                    } else {
                        emMessage = urnUri;
                    }
                    Log.w(TAG, "Do not find matched category, response with em msg: " + emMessage);
                }
                ImsReasonInfo info = new ImsReasonInfo(
                        reasonCode, ImsReasonInfo.EXTRA_CODE_CALL_RETRY_NORMAL, emMessage);

                listener.callSessionInitiatedFailed(info);
            }
        }

        callSession.close();
        removeCall(callSession);
    }

    private void handleVideoResize(int eventCode, ImsCallSessionImpl callSession, int width,
            int height) {
        if (Utilities.DEBUG) Log.i(TAG, "Handle video resize.");
        if (callSession == null) {
            Log.w(TAG, "[handleCallRemoteVideoResize] The call session is null.");
            return;
        }

        if (width < 0 || height < 0) {
            Log.e(TAG, "The width is: " + width + ", the height is: " + height + ", invalid.");
            return;
        }

        ImsVideoCallProviderImpl videoProvider = callSession.getVideoCallProviderImpl();
        if (videoProvider == null) {
            Log.e(TAG, "Failed to update the video size as video provider is null.");
            return;
        }

        if (eventCode == JSONUtils.EVENT_CODE_REMOTE_VIDEO_RESIZE) {
            Log.d(TAG, "The call " + callSession.getCallId() + " remote video resize: "
                    + width + "," + height);
            videoProvider.changePeerDimensions(width, height);
        } else if (eventCode == JSONUtils.EVENT_CODE_LOCAL_VIDEO_RESIZE) {
            Log.w(TAG, "Shouldn't handle the local video resize here. Please check!");
        }
    }

    private void handleVideoLevelUpdate(ImsCallSessionImpl callSession, int level) {
        if (Utilities.DEBUG) Log.i(TAG, "Handle video level update to " + level);
        if (callSession == null) {
            Log.w(TAG, "[handleVideoLevelUpdate] The call session is null.");
            return;
        }

        ImsVideoCallProviderImpl videoProvider = callSession.getVideoCallProviderImpl();
        if (videoProvider == null) {
            Log.e(TAG, "Failed to update the video level as video provider is null.");
            return;
        }

        videoProvider.updateVideoQualityLevel(level);
    }

    private void handleRTPReceived(ImsCallSessionImpl callSession, boolean isVideo,
            boolean isReceived) {
        if (Utilities.DEBUG) Log.i(TAG, "Handle the call RTP received: " + isReceived);
        if (callSession == null) {
            Log.e(TAG, "[handleRTPReceived] The call session is null.");
            return;
        }

        if (!callSession.isAlive()) {
            Log.d(TAG, "The call " + callSession.getCallId() + " isn't alive, do nothing.");
            return;
        }

        ImsCallSessionImpl confSession = callSession.getConfCallSession();
        if (confSession != null) {
            Log.d(TAG, "The call " + callSession.getCallId() + " will be invited to conference: "
                    + confSession.getCallId() + ", do nothing.");
            return;
        }

        if (mListener != null) mListener.onCallRTPReceived(isVideo, isReceived);
    }

    private void handleRTCPChanged(ImsCallSessionImpl callSession, int lose, int jitter, int rtt,
            boolean isVideo) {
        if (Utilities.DEBUG) Log.i(TAG, "Handle the rtcp changed.");
        if (callSession == null) {
            Log.w(TAG, "[handleRTCPChanged] The call session is null.");
            return;
        }

        if (mListener != null) {
            mListener.onCallRTCPChanged(isVideo, lose, jitter, rtt);
        }
    }

    private void handleUssdInfoReceived(ImsCallSessionImpl callSession, String info, int mode) {
        if (Utilities.DEBUG) Log.i(TAG, "Handle the received ussd info.");
        if (callSession == null) {
            Log.w(TAG, "[handleUssdInfoReceived] The call session is null");
            return;
        }

        callSession.updateState(State.ESTABLISHED);
        IImsCallSessionListener listener = callSession.getListener();
        if (listener != null) {
            try {
                listener.callSessionUssdMessageReceived(mode, info);
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to send the ussd info. e: " + e);
            }
        }
    }

    private void handleVoiceCodecNegociated(ImsCallSessionImpl callSession, String codecName) {
        if (Utilities.DEBUG) Log.i(TAG, "Handle the voice codec negociated.");
        if (callSession == null || TextUtils.isEmpty(codecName)) {
            Log.w(TAG, "[handleVoiceCodecNegociated] The call session is null, or codecName is: "
                    + codecName);
            return;
        }

        // If the codec name contains "WB", it means the call quality is high quality
        // and update the call's voice quality.
        int quality = codecName.toUpperCase().contains("WB")
                ? ImsStreamMediaProfile.AUDIO_QUALITY_AMR_WB
                : ImsStreamMediaProfile.AUDIO_QUALITY_AMR;
        callSession.updateVoiceQuality(quality);
    }

    private void handleConfAlerted(ImsCallSessionImpl confSession) {
        if (Utilities.DEBUG) Log.i(TAG, "Handle the conference alerted.");
        if (confSession == null) {
            Log.w(TAG, "[handleConfConnected] The conference session is null ");
            return;
        }

        confSession.updateState(State.NEGOTIATING);
    }

    private void handleConfConnected(ImsCallSessionImpl confSession, boolean isVideo)
            throws NumberFormatException, RemoteException {
        if (Utilities.DEBUG) Log.i(TAG, "Handle the conference connected.");
        if (confSession == null || mICall == null) {
            Log.w(TAG, "[handleConfConnected] The conference session or call interface is null ");
            return;
        }

        confSession.updateState(State.ESTABLISHED);
        confSession.updateAliveState(true);
        ImsCallSessionImpl hostCallSession = confSession.getHostCallSession();
        if (hostCallSession == null) {
            // It means this conference is start with the participants. Needn't notify merge
            // complete and invite the peers.
            return;
        }

        IImsCallSessionListener hostListener = hostCallSession.getListener();
        if (hostListener != null) {
            Log.d(TAG, "Notify the merge complete.");
            hostListener.callSessionMergeComplete(confSession);
            // As merge complete, set the host call session as null.
            confSession.setHostCallSession(null);
        }

        IImsCallSessionListener confListener = confSession.getListener();
        // Notify the multi-party state changed.
        if (confListener != null) {
            confListener.callSessionResumed(confSession.getCallProfile());
            confListener.callSessionUpdated(confSession.getCallProfile());
            confListener.callSessionMultipartyStateChanged(true);
        }

        boolean hostIsVideo = Utilities.isVideoCall(hostCallSession.getCallProfile().mCallType);
        if (!hostIsVideo && isVideo) {
            // It means the host isn't video call, but the conference is video call. We need
            // update the conference to video here.
            confSession.updateCallType(ImsCallProfile.CALL_TYPE_VT);

            ImsVideoCallProviderImpl videoCallProvider = confSession.getVideoCallProviderImpl();
            if (videoCallProvider != null) {
                VideoProfile videoProfile = new VideoProfile(VideoProfile.STATE_BIDIRECTIONAL);
                videoCallProvider.receiveSessionModifyResponse(
                        VideoProvider.SESSION_MODIFY_REQUEST_SUCCESS,
                        videoProfile /* request profile */,
                        videoProfile /* response profile */);
            }
        } else if (hostIsVideo && !isVideo) {
            // It means the host is video call, but the conference is downgrade. We need
            // update the conference call to audio here.
            confSession.updateCallType(ImsCallProfile.CALL_TYPE_VOICE);

            ImsVideoCallProviderImpl videoCallProvider = confSession.getVideoCallProviderImpl();
            if (videoCallProvider != null) {
                VideoProfile videoProfile = new VideoProfile(VideoProfile.STATE_AUDIO_ONLY);
                videoCallProvider.receiveSessionModifyResponse(
                        VideoProvider.SESSION_MODIFY_REQUEST_SUCCESS,
                        videoProfile /* request profile */,
                        videoProfile /* response profile */);
            }
        }

        // The conference call session is connected to the service. And now we could invite the
        // other call session as the participants and update the states.
        for (ImsCallSessionImpl callSession : getCallSessionList()) {
            if (callSession == confSession
                    || callSession.getState() < State.ESTABLISHED
                    || callSession.getState() >= State.TERMINATING) {
                // This call session is the conference call session or do not alive, do nothing.
                Log.d(TAG, "Ignore add this call " + callSession + " to conference.");
                continue;
            }

            Log.d(TAG, "This call " + callSession + " will be invite to this conference call.");
            // Update the need merged call session.
            callSession.setConfCallSession(confSession);
            // As this call session will be invited to this conference, update the state and
            // stop all the video.
            callSession.updateAliveState(false);
            callSession.updateState(State.TERMINATING);
            callSession.getVideoCallProviderImpl().stopAll();
            confSession.addAsWaitForInvite(callSession);

            Bundle bundle = new Bundle();
            bundle.putString(ImsConferenceState.USER, callSession.getConfUSER());
            bundle.putString(ImsConferenceState.DISPLAY_TEXT, callSession.getCallee());
            bundle.putString(ImsConferenceState.ENDPOINT, callSession.getCallee());
            bundle.putString(ImsConferenceState.STATUS, ImsConferenceState.STATUS_PENDING);
            confSession.updateConfParticipants(callSession.getConfUSER(), bundle);
        }

        // Notify the conference participants' state.
        if (confListener != null) {
            confListener.callSessionConferenceStateUpdated(
                    confSession.getConfParticipantsState());
        }

        // Start to invite the calls.
        mHandler.sendMessage(mHandler.obtainMessage(MSG_INVITE_CALL, confSession));
    }

    private void handleConfDisconnected(ImsCallSessionImpl confSession, int stateCode)
            throws RemoteException {
        if (Utilities.DEBUG) Log.i(TAG, "Handle the conference disconnected.");
        if (confSession == null) {
            Log.w(TAG, "[handleConfDisconnected] The conference session is null ");
            return;
        }

        // Notify the merge failed.
        ImsCallSessionImpl hostCallSession = confSession.getHostCallSession();
        // The host call session may be null.
        if (hostCallSession != null) {
            // It means this conference merge failed
            IImsCallSessionListener hostListener = hostCallSession.getListener();
            if (hostListener != null) {
                Log.d(TAG, "Notify the merge failed.");
                ImsReasonInfo info = new ImsReasonInfo(ImsReasonInfo.CODE_UNSPECIFIED, stateCode);
                hostListener.callSessionMergeFailed(info);

                // FIXME: As the call may be held or resumed before merge which can not tracked by
                //        ImsCallTracker, so before we give the merge failed callback, we'd like to
                //        give this callback refer to current state.
                //        Another, if this issue should be fixed by framework?
                ImsCallProfile callProfile = hostCallSession.getCallProfile();
                if (hostCallSession.isAlive()) {
                    hostListener.callSessionResumed(callProfile);
                } else {
                    hostListener.callSessionHeld(callProfile);
                }
            }
        }

        if (confSession.getState() >= State.ESTABLISHED) {
            // It means the conference already connect before. Now finished and disconnect.
            MyToast.makeText(mContext, R.string.vowifi_conf_finished, Toast.LENGTH_LONG).show();
        } else {
            MyToast.makeText(mContext, R.string.vowifi_conf_disconnect, Toast.LENGTH_LONG).show();
        }

        // Terminate this conference call.
        handleCallTermed(confSession, ImsReasonInfo.CODE_UNSPECIFIED);
    }

    private void handleConfParticipantsChanged(int eventCode, ImsCallSessionImpl confSession,
            String phoneNumber, String phoneUri, String newStatus) throws RemoteException {
        if (Utilities.DEBUG) Log.i(TAG, "Handle the conference participant update result.");

        if (confSession == null) {
            Log.w(TAG, "[handleConfParticipantsChanged] The conference session is null ");
            return;
        }

        if (TextUtils.isEmpty(phoneNumber) || TextUtils.isEmpty(phoneUri)) {
            Log.e(TAG, "Faile to handle the parts changed as the phone number or uri is empty.");
            return;
        }

        boolean needUpdateState = true;
        boolean needInviteNext = false;
        String bundleKey = null;
        Bundle bundle = new Bundle();

        switch (eventCode) {
            case JSONUtils.EVENT_CODE_CONF_INVITE_ACCEPT: {
                Log.d(TAG, "Get the invite accept result for the user: " + phoneNumber);
                // It means the call accept to join the conference.
                ImsCallSessionImpl callSession = confSession.getInInviteCall();
                if (callSession == null
                        || !callSession.isMatched(phoneNumber)) {
                    Log.w(TAG, "Can not find in invite call or phoneNumber mis-match.");
                    return;
                }

                String user = callSession.getConfUSER();
                // Add this call session as the conference's participant.
                confSession.addParticipant(callSession);

                bundleKey = user;
                bundle.putString(ImsConferenceState.USER, user);
                bundle.putString(ImsConferenceState.DISPLAY_TEXT, callSession.getCallee());
                bundle.putString(ImsConferenceState.ENDPOINT, callSession.getCallee());
                bundle.putString(ImsConferenceState.STATUS, ImsConferenceState.STATUS_CONNECTED);

                // As invite success, need invite the next participant.
                needInviteNext = true;

                // Sometimes, can not receive the "BYE", and it will leader to the call can
                // not receive the terminate callback. We'd like to release the call.
                Message msg = new Message();
                msg.what = MSG_TERM_CHILD_CALL;
                msg.obj = callSession.getCallId();
                mHandler.sendMessageDelayed(msg, TERM_CHILD_CALL_DELAY);
                break;
            }
            case JSONUtils.EVENT_CODE_CONF_INVITE_FAILED: {
                Log.d(TAG, "Get the invite failed result for the user: " + phoneNumber);
                // It means failed to invite the call to this conference.
                ImsCallSessionImpl callSession = confSession.getInInviteCall();
                if (callSession == null
                        || !callSession.isMatched(phoneNumber)) {
                    Log.w(TAG, "Can not find in invite call or phoneNumber mis-match.");
                    return;
                }

                String user = callSession.getConfUSER();

                bundleKey = user;
                bundle.putString(ImsConferenceState.USER, user);
                bundle.putString(ImsConferenceState.DISPLAY_TEXT, callSession.getCallee());
                bundle.putString(ImsConferenceState.ENDPOINT, callSession.getCallee());
                bundle.putString(ImsConferenceState.STATUS, ImsConferenceState.STATUS_DISCONNECTED);

                // As invite failed, need invite the next participant.
                needInviteNext = true;
                // If this call invite failed, remove the callee and terminate the failed call.
                callSession.terminate(ImsReasonInfo.CODE_USER_TERMINATED);

                // Show the notify toast to the user.
                String text = mContext.getString(R.string.vowifi_conf_invite_failed) + phoneNumber;
                MyToast.makeText(mContext, text, Toast.LENGTH_LONG).show();
                break;
            }
            case JSONUtils.EVENT_CODE_CONF_KICK_ACCEPT:
            case JSONUtils.EVENT_CODE_CONF_KICK_FAILED: {
                // The kick off action will be handled when remove participant.
                Log.d(TAG, "Get the kick accept or failed for the user: " + phoneNumber);
                needUpdateState = false;
                break;
            }
            case JSONUtils.EVENT_CODE_CONF_PART_UPDATE: {
                Log.d(TAG, "Get the new status for the user: " + phoneNumber);
                if (!TextUtils.isEmpty(newStatus)) {
                    // If the new status is disconnected, we need remove it from the participants.
                    String user = confSession.findUser(phoneNumber);
                    if (TextUtils.isEmpty(user)) {
                        Log.w(TAG, "Can not find the phoneNumber from callee list.");
                        user = phoneNumber;
                    }
                    bundleKey = user;
                    Bundle existBundle = confSession.getParticipantBundle(user);
                    if (existBundle != null) {
                        existBundle.putString(ImsConferenceState.STATUS, newStatus);
                        bundle = existBundle;
                    } else {
                        bundle.putString(ImsConferenceState.USER, user);
                        bundle.putString(ImsConferenceState.DISPLAY_TEXT, user);
                        bundle.putString(ImsConferenceState.ENDPOINT, phoneUri);
                        bundle.putString(ImsConferenceState.STATUS, newStatus);
                    }

                    // If the new status is disconnect, need remove the participant.
                    if (ImsConferenceState.STATUS_DISCONNECTED.equals(newStatus)) {
                        confSession.removeParticipant(user);
                    }
                } else {
                    needUpdateState = false;
                    Log.w(TAG, "Do not update the participant with new status: " + newStatus);
                }
                break;
            }
        }

        // After update the participants, if there isn't any participant, need terminate it.
        if (confSession.getParticipantsCount() < 1) {
            Log.d(TAG, "There isn't any participant, terminate this conference call.");
            // Terminate this conference call.
            confSession.terminate(ImsReasonInfo.CODE_USER_TERMINATED);
            MyToast.makeText(mContext, R.string.vowifi_conf_none_participant, Toast.LENGTH_LONG)
                    .show();
        } else if (needUpdateState) {
            confSession.updateConfParticipants(bundleKey, bundle);

            IImsCallSessionListener confListener = confSession.getListener();
            if (confListener != null) {
                confListener.callSessionConferenceStateUpdated(
                        confSession.getConfParticipantsState());
            }
        }

        if (needInviteNext) {
            // Send the message to invite the next participant.
            mHandler.sendMessage(mHandler.obtainMessage(MSG_INVITE_CALL, confSession));
        }
    }

    private boolean canAcceptIncomingCall() {
        if (mSessionList.size() < 2) {
            // It means there is only one call as the new incoming call.
            return true;
        } else {
            // There is more than one call, we need to check all the calls if wait for update
            // response. If there is update request, the hold action will be failed, and can't
            // accept the incoming call.
            boolean hasUpdate = false;
            for (ImsCallSessionImpl session : mSessionList) {
                ImsVideoCallProviderImpl provider = session.getVideoCallProviderImpl();
                if (provider.isWaitForModifyResponse()) {
                    hasUpdate = true;
                }
            }

            // If there isn't update request, the user could accept the incoming call.
            Log.d(TAG, "There is the update request? hasUpdate: " + hasUpdate);
            return !hasUpdate;
        }
    }

    private class MyCallCallback extends IVoWifiCallCallback.Stub {
        @Override
        public void onEvent(String json) {
            if (Utilities.DEBUG) Log.i(TAG, "Get the vowifi ser event callback.");
            if (TextUtils.isEmpty(json)) {
                Log.e(TAG, "Can not handle the ser callback as the json is null.");
                return;
            }

            Message msg = mHandler.obtainMessage(MSG_HANDLE_EVENT);
            msg.obj = json;
            mHandler.sendMessage(msg);
        }
    }

}
