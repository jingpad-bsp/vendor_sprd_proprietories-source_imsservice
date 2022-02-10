/**
 * version 1.
 */

package com.spreadtrum.ims.vowifi;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.telecom.VideoProfile;
import android.telephony.PhoneNumberUtils;
import android.telephony.ServiceState;
import android.telephony.emergency.EmergencyNumber;
import android.telephony.ims.ImsCallProfile;
import android.telephony.ims.ImsCallSession.State;
import android.telephony.ims.ImsConferenceState;
import android.telephony.ims.ImsReasonInfo;
import android.telephony.ims.ImsStreamMediaProfile;
import android.telephony.ims.aidl.IImsCallSessionListener;
import android.text.TextUtils;
import java.util.Arrays;
import android.util.Log;
import android.view.Surface;
import android.widget.Toast;

import com.android.ims.internal.IImsCallSession;
import com.android.ims.internal.IImsVideoCallProvider;
import com.android.ims.internal.ImsSrvccCallInfo;
import com.android.ims.internal.IVoWifiCall;

import com.spreadtrum.ims.R;
import com.spreadtrum.ims.vowifi.Utilities.CallCursor;
import com.spreadtrum.ims.vowifi.Utilities.Camera;
import com.spreadtrum.ims.vowifi.Utilities.ECBMRequest;
import com.spreadtrum.ims.vowifi.Utilities.EMUtils;
import com.spreadtrum.ims.vowifi.Utilities.FDNHelper;
import com.spreadtrum.ims.vowifi.Utilities.JSONUtils;
import com.spreadtrum.ims.vowifi.Utilities.MyToast;
import com.spreadtrum.ims.vowifi.Utilities.PendingAction;
import com.spreadtrum.ims.vowifi.Utilities.ProviderUtils;
import com.spreadtrum.ims.vowifi.Utilities.Result;
import com.spreadtrum.ims.vowifi.Utilities.SRVCCSyncInfo;
import com.spreadtrum.ims.vowifi.Utilities.VideoType;
import com.spreadtrum.ims.vowifi.VoWifiCallManager.ICallChangedListener;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map.Entry;

public class ImsCallSessionImpl extends IImsCallSession.Stub {
    private static final String TAG = Utilities.getTag(ImsCallSessionImpl.class.getSimpleName());

    // Used to force phone number as emergency call.
    private static final String PROP_KEY_FORCE_SOS_CALL = "persist.vowifi.force.soscall";
    private static final String PROP_KEY_COULD_UPDATE = "persist.vowifi.could.update";

    private static final int MERGE_TIMEOUT = 10 * 1000;

    private static final boolean SUPPORT_START_CONFERENCE = false;

    private static final String PARTICIPANTS_SEP = ";";

    private int mCallId = -1;
    // TODO: Do not support update cni when the call now.
    private boolean mRequiredCNI = false;
    private boolean mIsAlive = false;
    private boolean mIsFocus = false;
    private boolean mIsConfHost = false;
    private boolean mAudioStart = false;
    private boolean mIsEmergency = false;
    private boolean mIsForwarded = false;
    private boolean mIsIncomingNotify = false;

    private String mPrimaryCallee = null;
    private String mSecondaryCallee = null;

    private Context mContext;
    private CallCursor mCursor;
    private ECBMRequest mECBMRequest;
    private VoWifiCallStateTracker mCallStateTracker;
    private VoWifiCallManager mCallManager;
    private NextAction mNextAction = null;
    private IVoWifiCall mICall = null;

    private IImsCallSessionListener mListener = null;
    private ImsVideoCallProviderImpl mVideoCallProvider = null;

    private ImsCallSessionImpl mConfCallSession = null;
    private ImsCallSessionImpl mHostCallSession = null;
    private ImsCallSessionImpl mInInviteSession = null;
    // The key will be build as this "phoneNumber@callId" which same the ImsConferenceState.USER.
    private HashMap<String, ImsCallSessionImpl> mParticipantSessions =
            new HashMap<String, ImsCallSessionImpl>();
    // The key will be build as this "phoneNumber@callId" which same the ImsConferenceState.USER.
    private HashMap<String, Bundle> mConfParticipantStates = new HashMap<String, Bundle>();
    private LinkedList<ImsCallSessionImpl> mWaitForInviteSessions =
            new LinkedList<ImsCallSessionImpl>();

    private ImsCallProfile mCallProfile;
    private ImsCallProfile mLocalCallProfile = new ImsCallProfile(
            ImsCallProfile.SERVICE_TYPE_NORMAL, ImsCallProfile.CALL_TYPE_VIDEO_N_VOICE);
    private ImsCallProfile mRemoteCallProfile = new ImsCallProfile(
            ImsCallProfile.SERVICE_TYPE_NORMAL, ImsCallProfile.CALL_TYPE_VIDEO_N_VOICE);

    private HashMap<String, PendingAction> mPendingActions = new HashMap<String, PendingAction>();

    private static final int MSG_SET_MUTE       = 1;
    private static final int MSG_START          = 2;
    private static final int MSG_START_CONF     = 3;
    private static final int MSG_ACCEPT         = 4;
    private static final int MSG_REJECT         = 5;
    private static final int MSG_TERMINATE      = 6;
    private static final int MSG_HOLD           = 7;
    private static final int MSG_RESUME         = 8;
    private static final int MSG_MERGE          = 9;
    private static final int MSG_UPDATE         = 10;
    private static final int MSG_EXTEND_TO_CONF = 11;
    private static final int MSG_START_FAIL     = 12;
    private static final int MSG_MERGE_FAILED   = 13;
    private static final int MSG_SEND_DTMF_FINISHED = 14;
    private static final int MSG_HOLD_FAILED    = 15;
    private Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            Log.d(TAG, "Handle the msg: " + msg.toString());

            if (msg.what == MSG_START_FAIL) {
                String failMessage = (String) msg.obj;
                Log.e(TAG, failMessage);
                if (mListener != null) {
                    try {
                        mListener.callSessionInitiatedFailed(
                                new ImsReasonInfo(msg.arg1, msg.arg2, failMessage));
                    } catch (RemoteException e) {
                        Log.e(TAG, "Failed to give the call session start failed callback.");
                        Log.e(TAG, "Catch the RemoteException e: " + e);
                    }
                }
                return;
            } else if (msg.what == MSG_MERGE_FAILED) {
                try {
                    // Give a toast for connect timeout.
                    String text = mContext.getString(R.string.vowifi_conf_connect_timeout);
                    MyToast.makeText(mContext, text, Toast.LENGTH_LONG).show();
                    // Handle as merge action failed.
                    handleMergeActionFailed(text);
                    // If the call is held, resume this call.
                    if (!mIsAlive) {
                        resume(getResumeMediaProfile());
                    }

                    // Terminate the conference call.
                    ImsCallSessionImpl confSession = mCallManager.getConfCallSession();
                    if (confSession != null) {
                        confSession.terminate(ImsReasonInfo.CODE_USER_TERMINATED);
                    }
                } catch (RemoteException e) {
                    Log.e(TAG, "Failed to handle merge failed event as catch the ex.");
                }
                return;
            } else if (msg.what == MSG_SEND_DTMF_FINISHED) {
                Message result = (Message) msg.obj;
                result.sendToTarget();
                return;
            } else if (msg.what == MSG_HOLD_FAILED) {
                String failMessage = (String) msg.obj;
                Log.e(TAG, failMessage);

                MyToast.makeText(mContext, R.string.vowifi_call_retry, Toast.LENGTH_LONG).show();
                if (mListener != null) {
                    try {
                        mListener.callSessionHoldFailed(
                                new ImsReasonInfo(ImsReasonInfo.CODE_UNSPECIFIED,
                                        ImsReasonInfo.CODE_UNSPECIFIED, failMessage));
                    } catch (RemoteException e) {
                        Log.e(TAG, "Failed to give the call hold failed callback.");
                        Log.e(TAG, "Catch the RemoteException e: " + e);
                    }
                }
                return;
            }

            String key = (String) msg.obj;
            PendingAction action = null;
            synchronized (mPendingActions) {
                action = mPendingActions.get(key);

                if (action == null) {
                    Log.w(TAG, "Try to handle the pending action, but the action is null.");
                    // The action is null, remove it from the HashMap.
                    mPendingActions.remove(key);

                    // If the action is null, do nothing.
                    return;
                }
            }

            Log.d(TAG, "Handle the pending action: " + action._name);
            try {
                switch (msg.what) {
                    case MSG_SET_MUTE:
                        setMute((Boolean) action._params.get(0));
                        break;
                    case MSG_START:
                        start((String) action._params.get(0),
                                (ImsCallProfile) action._params.get(1));
                        break;
                    case MSG_START_CONF:
                        startConference((String[]) action._params.get(0),
                                (ImsCallProfile) action._params.get(1));
                        break;
                    case MSG_ACCEPT:
                        accept((Integer) action._params.get(0),
                                (ImsStreamMediaProfile) action._params.get(1));
                        break;
                    case MSG_REJECT:
                        reject((Integer) action._params.get(0));
                        break;
                    case MSG_TERMINATE:
                        terminate((Integer) action._params.get(0));
                        break;
                    case MSG_HOLD:
                        hold((ImsStreamMediaProfile) action._params.get(0));
                        break;
                    case MSG_RESUME:
                        resume((ImsStreamMediaProfile) action._params.get(0));
                        break;
                    case MSG_MERGE:
                        merge();
                        break;
                    case MSG_UPDATE:
                        update((Integer) action._params.get(0),
                                (ImsStreamMediaProfile) action._params.get(1));
                        break;
                    case MSG_EXTEND_TO_CONF:
                        String participants = (String) action._params.get(0);
                        extendToConference(participants.split(PARTICIPANTS_SEP));
                        break;
                }
            } catch (RemoteException e) {
                Log.e(TAG, "Catch the RemoteException when handle the action " + action._name);
            } finally {
                // TODO: If we catch the remote exception, need remove it from the HashMap?
                synchronized (mPendingActions) {
                    mPendingActions.remove(key);
                }
            }
        }
    };

    // To listener the IVoWifiCall changed.
    private ICallChangedListener mICallChangedListener = new ICallChangedListener() {
        @Override
        public void onChanged(IVoWifiCall newCallInterface) {
            if (newCallInterface != null) {
                mICall = newCallInterface;
                // If the pending action is not null, we need to handle the them.
                synchronized (mPendingActions) {
                    if (mPendingActions.size() < 1) {
                        if (Utilities.DEBUG) Log.d(TAG, "The pending action is null.");
                        return;
                    }

                    Iterator<Entry<String, PendingAction>> iterator = mPendingActions.entrySet()
                            .iterator();
                    while (iterator.hasNext()) {
                        Entry<String, PendingAction> entry = iterator.next();
                        Message msg = new Message();
                        msg.what = entry.getValue()._action;
                        msg.obj = entry.getKey();
                        mHandler.sendMessage(msg);
                    }
                }
            } else if (mICall != null) {
                Log.w(TAG, "The call interface changed to null, terminate the call.");
                mICall = newCallInterface;
                // It means the call interface disconnect. If the current call do not close,
                // we'd like to terminate this call.
                terminateCall(ImsReasonInfo.CODE_USER_TERMINATED);
            }
        }
    };

    protected ImsCallSessionImpl(Context context, VoWifiCallManager callManager,
            ImsCallProfile profile, IImsCallSessionListener listener,
            ImsVideoCallProviderImpl videoCallProvider, int callDir, int callRatType,
            boolean requiredCNI) {
        mContext = context;
        mCursor = getCallCursor();
        mCallStateTracker = new VoWifiCallStateTracker(State.IDLE, callDir);
        mRequiredCNI = requiredCNI;
        mCallManager = callManager;
        mCallProfile = profile;
        mListener = listener;
        mVideoCallProvider = videoCallProvider;
        if (mVideoCallProvider == null) {
            // The video call provider is null, create it.
            mVideoCallProvider = new ImsVideoCallProviderImpl(mContext, callManager, this);
        }
        VideoProfile videoProfile = new VideoProfile(Utilities.isVideoCall(mCallProfile.mCallType)
                ? VideoProfile.STATE_BIDIRECTIONAL : VideoProfile.STATE_AUDIO_ONLY);
        mVideoCallProvider.updateVideoProfile(videoProfile);

        // Set radio technology to WLAN.
        mCallProfile.setCallExtra(ImsCallProfile.EXTRA_CALL_RAT_TYPE, String.valueOf(callRatType));

        // Register the service changed to get the IVowifiService.
        mCallManager.registerCallInterfaceChanged(mICallChangedListener);
    }

    @Override
    protected void finalize() throws Throwable {
        mCallManager.unregisterCallInterfaceChanged(mICallChangedListener);
        super.finalize();
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder()
                .append("[callId = " + mCallId)
                .append(", state = " + mCallStateTracker)
                .append(", isAlive = " + mIsAlive + "]");
        return builder.toString();
    }

    /**
     * Closes the object. This object is not usable after being closed.
     */
    @Override
    public void close() {
        if (Utilities.DEBUG) Log.i(TAG, "The call session(" + this + ") will be closed.");

        if (mCallManager.isInSRVCC()) {
            Log.d(TAG, "In SRVCC process, this call session will be closed after SRVCC success.");
            return;
        }

        updateState(State.INVALID);

        if (mCursor != null) {
            mCursor.close();
        }
    }

    /**
     * Gets the call ID of the session.
     */
    @Override
    public String getCallId() {
        if (Utilities.DEBUG) Log.i(TAG, "Get the call id: " + mCallId);

        return String.valueOf(mCallId);
    }

    /**
     * Gets the call profile that this session is associated with
     */
    @Override
    public ImsCallProfile getCallProfile() {
        if (Utilities.DEBUG) Log.i(TAG, "Get the call profile: " + mCallProfile);

        return mCallProfile;
    }

    /**
     * Gets the local call profile that this session is associated with
     */
    @Override
    public ImsCallProfile getLocalCallProfile() {
        if (Utilities.DEBUG) Log.i(TAG, "Get the local call profile: " + mLocalCallProfile);

        return mLocalCallProfile;
    }

    /**
     * Gets the remote call profile that this session is associated with
     */
    @Override
    public ImsCallProfile getRemoteCallProfile() {
        if (Utilities.DEBUG) Log.i(TAG, "Get the remote call profile: " + mRemoteCallProfile);

        return mRemoteCallProfile;
    }

    /**
     * Gets the value associated with the specified property of this session.
     */
    @Override
    public String getProperty(String name) {
        if (Utilities.DEBUG) Log.i(TAG, "Get the property by this name: " + name);

        return mCallProfile.getCallExtra(name, null);
    }

    /**
     * Gets the session state. The value returned must be one of the states in
     * {@link ImsCallSession#State}
     */
    @Override
    public int getState() {
        return mCallStateTracker != null ? mCallStateTracker.getCallState() : State.INVALID;
    }

    /**
     * Checks if the session is in a call.
     */
    @Override
    public boolean isInCall() {
        if (mICall == null) {
            // The ser service is null, so this call shouldn't be in call.
            return false;
        }

        int state = getState();
        return state > State.INITIATED && state < State.TERMINATED;
    }

    /**
     * Sets the listener to listen to the session events. A {@link IImsCallSession}
     * can only hold one listener at a time. Subsequent calls to this method
     * override the previous listener.
     *
     * @param listener to listen to the session events of this object
     */
    @Override
    public void setListener(IImsCallSessionListener listener) {
        mListener = listener;
    }

    /**
     * Mutes or unmutes the mic for the active call.
     *
     * @param muted true if the call is muted, false otherwise
     * @throws RemoteException
     */
    @Override
    public void setMute(boolean muted) throws RemoteException {
        if (Utilities.DEBUG) Log.i(TAG, "Mutes(" + muted + ") the mic for the active call.");

        if (mICall == null) {
            // As the vowifi service is null, need add this action to pending action.
            synchronized (mPendingActions) {
                String key = String.valueOf(System.currentTimeMillis());
                PendingAction action = new PendingAction("setMute", MSG_SET_MUTE, (Boolean) muted);
                mPendingActions.put(key, action);
            }
            return;
        }

        // The vowifi service is not null, mute or unmute the active call.
        int res = Result.FAIL;
        if (isConferenceCall()) {
            res = mICall.confSetMute(mCallId, muted);
        } else {
            res = mICall.sessSetMicMute(mCallId, muted);
        }

        if (res != Result.SUCCESS) {
            // Set mute action failed.
            Log.e(TAG, "Native set mute failed, res = " + res);
        }
    }

    /**
     * Initiates an IMS call with the specified target and call profile.
     * The session listener is called back upon defined session events.
     * The method is only valid to call when the session state is in
     *
     * @param callee  dialed string to make the call to
     * @param profile call profile to make the call with the specified service type,
     *                call type and media information
     * @throws RemoteException
     */
    @Override
    public void start(String callee, ImsCallProfile profile) throws RemoteException {
        if (Utilities.DEBUG) Log.i(TAG, "Initiates an ims call with " + callee);

        if (TextUtils.isEmpty(callee) || profile == null) {
            handleStartActionFailed("Start the call failed. Check the callee or profile.");
            MyToast.makeText(mContext, R.string.vowifi_call_retry, Toast.LENGTH_LONG).show();
            return;
        }

        // Update the participants and call profile.
        setCallee(callee);

        // TODO: Update the profile but not replace.
        mCallProfile = profile;
        mCallProfile.setCallExtra(ImsCallProfile.EXTRA_OI, callee);
        mCallProfile.setCallExtra(ImsCallProfile.EXTRA_CNA, null);
        mCallProfile.setCallExtraInt(
                ImsCallProfile.EXTRA_CNAP, ImsCallProfile.OIR_PRESENTATION_NOT_RESTRICTED);
        mCallProfile.setCallExtra(ImsCallProfile.EXTRA_CALL_RAT_TYPE,
                String.valueOf(mCallManager.getCallRatType()));

        // Check if emergency call.
        int serviceType = profile.getServiceType();
        int emRouting = profile.getEmergencyCallRouting();
        boolean isRealEmergency = (serviceType == ImsCallProfile.SERVICE_TYPE_EMERGENCY
                && emRouting != EmergencyNumber.EMERGENCY_CALL_ROUTING_NORMAL);
        String emCategory = mCallProfile.getCallExtra(ImsCallProfile.EXTRA_ADDITIONAL_CALL_INFO);
        String sosNumber = SystemProperties.get(PROP_KEY_FORCE_SOS_CALL, null);
        boolean forceSos = callee.equals(sosNumber);

        if (isRealEmergency || !TextUtils.isEmpty(emCategory) || forceSos) {
            startEmergencyCall(callee);
        } else {
            startCall(callee);
        }
    }

    @Override
    public void deflect(String deflectNumber){
        // TODO:
    }

    /**
     * Initiates an IMS call with the specified participants and call profile.
     * The session listener is called back upon defined session events.
     * The method is only valid to call when the session state is in
     *
     * @param participants participant list to initiate an IMS conference call
     * @param profile      call profile to make the call with the specified service type,
     *                     call type and media information
     */
    @Override
    public void startConference(String[] participants, ImsCallProfile profile)
            throws RemoteException {
        if (Utilities.DEBUG) {
            Log.i(TAG, "Initiates an ims conference call with participants: "
                    + Utilities.getStringFromArray(participants));
        }

        // As do not support now. Handle it as action failed.
        if (!SUPPORT_START_CONFERENCE) {
            handleStartActionFailed("Do not support this action now.");
            MyToast.makeText(mContext, R.string.vowifi_conf_do_not_support, Toast.LENGTH_LONG).show();
            return;
        }

        if (participants == null) {
            handleStartActionFailed("Start the conference failed, the participants is null.");
            return;
        }

        if (participants.length < 1 || profile == null) {
            handleStartActionFailed("Start the conference failed. Check the parts or profile.");
            return;
        }

        // As the vowifi service is null, need add this action to pending action.
        if (mICall == null) {
            synchronized (mPendingActions) {
                String key = String.valueOf(System.currentTimeMillis());
                PendingAction action = new PendingAction("startConference", MSG_START_CONF,
                        participants, profile);
                mPendingActions.put(key, action);
            }
            return;
        }

        // TODO: update the profile
        mCallProfile = profile;
        mCallProfile.setCallExtra(ImsCallProfile.EXTRA_CALL_RAT_TYPE,
                String.valueOf(ServiceState.RIL_RADIO_TECHNOLOGY_IWLAN));

        StringBuilder phoneNumbers = new StringBuilder();
        for (int i = 0; i < participants.length; i++) {
            if (i > 0) phoneNumbers.append(PARTICIPANTS_SEP);
            phoneNumbers.append(participants[i]);
        }
        Log.d(TAG, "Start the conference with phone numbers: " + phoneNumbers);

        // TODO: if need return the error code
        // FIXME: Couldn't understand, if the the call id = 1, what does it mean?
        int res = mICall.confCall(participants, null /* cookie, do not use now */, false);
        if (Utilities.DEBUG) Log.d(TAG, "Start the conference call, and get the call id: " + res);
        if (res == Result.INVALID_ID) {
            handleStartActionFailed("Native start the conference call failed.");
        } else {
            mCallId = res;
            updateState(State.INITIATED);
        }
    }

    /**
     * Accepts an incoming call or session update.
     *
     * @param callType call type specified in {@link ImsCallProfile} to be answered
     * @param profile  stream media profile {@link ImsStreamMediaProfile} to be answered
     * @throws RemoteException
     */
    @Override
    public void accept(int callType, ImsStreamMediaProfile profile) throws RemoteException {
        if (Utilities.DEBUG) Log.i(TAG, "Accept an incoming call with call type is " + callType);

        if (callType < ImsCallProfile.CALL_TYPE_VOICE_N_VIDEO
                || callType > ImsCallProfile.CALL_TYPE_VS_RX) {
            Log.e(TAG, "Try to accept an incoming call, but the call type is invalid, "
                    + "ignore this call type: " + callType);
            return;
        }

        if (profile == null) {
            Log.e(TAG, "Try to accept an incoming call, but the media profile is null.");
            handleStartActionFailed("Can not accept the call as the media profile is null.");
            return;
        }

        // As the vowifi service is null, need add this action to pending action.
        if (mICall == null) {
            synchronized (mPendingActions) {
                String key = String.valueOf(System.currentTimeMillis());
                PendingAction action =
                        new PendingAction("accept", MSG_ACCEPT, (Integer) callType, profile);
                mPendingActions.put(key, action);
            }
            return;
        }

        int res = Result.FAIL;
        if (isConferenceCall()) {
            res = mICall.confAcceptInvite(mCallId);
        } else {
            boolean isVideoCall = Utilities.isVideoCall(callType);
            res = mICall.sessAnswer(mCallId, null, true, isVideoCall);
        }

        if (res == Result.SUCCESS) {
            mIsAlive = true;
            startAudio();

            // Accept action success, update the last call action to accept.
            updateRequestAction(VoWifiCallStateTracker.ACTION_ACCEPT);
        } else {
            // Accept action failed.
            Log.e(TAG, "Native accept the incoming call failed, res = " + res);
            handleStartActionFailed("Native accept the incoming call failed.");
        }
    }

    private void handleStartActionFailed(String failMessage) {
        handleStartActionFailed(
                ImsReasonInfo.CODE_SIP_REQUEST_CANCELLED,
                ImsReasonInfo.CODE_UNSPECIFIED,
                failMessage);
    }

    private void handleStartActionFailed(int code, int extraCode, String failMessage) {
        // As start action failed, remove the call first.
        mCallManager.removeCall(this);

        // When #ImsCall received the call session start failed callback will set the call session
        // to null. Then sometimes it will meet the NullPointerException. So we'd like to delay
        // 500ms to send this callback to let the ImsCall handle the left logic.
        Message msg = mHandler.obtainMessage(MSG_START_FAIL, code, extraCode, failMessage);
        mHandler.sendMessageDelayed(msg, 500);
    }

    /**
     * Rejects an incoming call or session update.
     *
     * @param reason reason code to reject an incoming call
     */
    @Override
    public void reject(int reason) throws RemoteException {
        if (Utilities.DEBUG) Log.i(TAG, "Reject an incoming call as the reason is " + reason);

        // As the vowifi service is null, need add this action to pending action.
        if (mICall == null) {
            synchronized (mPendingActions) {
                String key = String.valueOf(System.currentTimeMillis());
                PendingAction action = new PendingAction("reject", MSG_REJECT, (Integer) reason);
                mPendingActions.put(key, action);
            }
            return;
        }

        int res = Result.FAIL;
        if (isConferenceCall()) {
            res = mICall.confTerm(mCallId, reason);
        } else {
            res = mICall.sessTerm(mCallId, reason);
        }

        if (res == Result.SUCCESS) {
            // Reject action success, update the last call action as reject.
            updateRequestAction(VoWifiCallStateTracker.ACTION_REJECT);

            // As the result is OK, terminate the call session.
            if (mListener != null) {
                mListener.callSessionTerminated(
                        new ImsReasonInfo(reason, reason, "reason: " + reason));
            }
            mCallManager.removeCall(this);
        } else {
            // Reject the call failed.
            Log.e(TAG, "Native reject the incoming call failed, res = " + res);
        }
    }

    /**
     * Terminates a call.
     */
    @Override
    public void terminate(int reason) throws RemoteException {
        if (Utilities.DEBUG) Log.i(TAG, "Terminate a call as the reason is " + reason);

        // As the vowifi service is null, need add this action to pending action.
        if (mICall == null) {
            synchronized (mPendingActions) {
                String key = String.valueOf(System.currentTimeMillis());
                PendingAction action =
                        new PendingAction("terminate", MSG_TERMINATE, (Integer) reason);
                mPendingActions.put(key, action);
            }
            return;
        }

        // As user terminate the call, we need to check if there is conference call.
        ImsCallSessionImpl confSession = mCallManager.getConfCallSession();
        if (confSession != null) {
            ImsCallSessionImpl hostSession = confSession.getHostCallSession();
            if (this.equals(confSession)) {
                // If the conference terminte, we'd like to terminate all the child session.
                terminateChildCalls(reason);
            } else if (this.equals(hostSession)
                    && confSession != null
                    && confSession.getState() > State.INVALID
                    && confSession.getState() < State.TERMINATED) {
                // The conference call already outgoing but not connect. If terminate the host
                // call, we need terminate the conference call and the close it.
                confSession.terminate(reason);
                confSession.close();
            }
        }

        // Terminate the call
        terminateCall(reason);
    }

    /**
     * Puts a call on hold. When it succeeds,
     *
     * @param profile stream media profile {@link ImsStreamMediaProfile} to hold the call
     */
    @Override
    public void hold(ImsStreamMediaProfile profile) throws RemoteException {
        if (Utilities.DEBUG) Log.i(TAG, "Hold a call with the media profile: " + profile);

        if (profile == null) {
            handleHoldActionFailed("Hold the call failed, the media profile is null.");
            return;
        }

        // As the vowifi service is null, need add this action to pending action.
        if (mICall == null) {
            synchronized (mPendingActions) {
                String key = String.valueOf(System.currentTimeMillis());
                PendingAction action = new PendingAction("hold", MSG_HOLD, profile);
                mPendingActions.put(key, action);
            }
            return;
        }

        int res = Result.FAIL;
        if (isConferenceCall()) {
            res = mICall.confHold(mCallId);
        } else {
            res = mICall.sessHold(mCallId);
        }

        if (res == Result.SUCCESS) {
            // Hold success will be handled in the callback.
            mCallProfile.mMediaProfile = profile;

            // Hold action success, update the last call action as hold.
            updateRequestAction(VoWifiCallStateTracker.ACTION_HOLD);
        } else {
            // Hold action failed.
            handleHoldActionFailed("Native hold the call failed, res = " + res);
        }
    }

    private void handleHoldActionFailed(String failMessage) throws RemoteException {
        // Similar as handle call start failed, we'd like to delay 500ms to send this callback
        // as ImsCall need handle the left logic.
        mHandler.sendMessageDelayed(mHandler.obtainMessage(MSG_HOLD_FAILED, failMessage), 500);
    }

    /**
     * Continues a call that's on hold. When it succeeds,
     * is called.
     *
     * @param profile stream media profile {@link ImsStreamMediaProfile} to resume the call
     */
    @Override
    public void resume(ImsStreamMediaProfile profile) throws RemoteException {
        if (Utilities.DEBUG) Log.i(TAG, "Continues a call with the media profile: " + profile);

        if (profile == null) {
            handleResumeActionFailed("Resume the call failed, the media profile is null.");
            return;
        }

        // As the vowifi service is null, need add this action to pending action.
        if (mICall == null) {
            synchronized (mPendingActions) {
                String key = String.valueOf(System.currentTimeMillis());
                PendingAction action = new PendingAction("resume", MSG_RESUME, profile);
                mPendingActions.put(key, action);
            }
            return;
        }

        int res = Result.FAIL;
        if (isConferenceCall()) {
            res = mICall.confResume(mCallId);
        } else {
            res = mICall.sessResume(mCallId);
        }

        if (res == Result.SUCCESS) {
            // Resume result will be handled in the callback.
            mCallProfile.mMediaProfile = profile;

            // Resume action success, update the last call action as resume.
            updateRequestAction(VoWifiCallStateTracker.ACTION_RESUME);
        } else {
            // Resume the call failed.
            handleResumeActionFailed("Native resume the call failed, res = " + res);
        }
    }

    private void handleResumeActionFailed(String failMessage) throws RemoteException {
        Log.e(TAG, failMessage);
        if (mListener != null) {
            mListener.callSessionResumeFailed(new ImsReasonInfo(
                    ImsReasonInfo.CODE_UNSPECIFIED, ImsReasonInfo.CODE_UNSPECIFIED, failMessage));
        }
    }

    /**
     * Merges the active & hold call. When it succeeds,
     * {@link Listener#callSessionMergeStarted} is called.
     */
    @Override
    public void merge() throws RemoteException {
        if (Utilities.DEBUG) Log.i(TAG, "Merge the active & hold call.");

        // As the vowifi service is null, need add this action to pending action.
        if (mICall == null) {
            synchronized (mPendingActions) {
                String key = String.valueOf(System.currentTimeMillis());
                PendingAction action = new PendingAction("merge", MSG_MERGE);
                mPendingActions.put(key, action);
            }
            return;
        }

        if (!isHeld()) {
            // If this host call do not hold, we'd like to hold the call first.
            // And then if the call hold success, start the merge action.
            // If hold failed, we need handle it as merge failed.
            NextAction nextAction = new NextAction(NextAction.FLAG_MERGE);
            setNextAction(nextAction);

            // Hold this call.
            hold(getHoldMediaProfile());
            return;
        }

        int sessionSize = mCallManager.getCallCount();
        if (sessionSize < 2) {
            handleMergeActionFailed(
                    "The number of active & hold call is " + sessionSize + ". Can not merge!");
            return;
        }

        // To check if there is conference, if yes, we needn't init a new call session.
        ImsCallSessionImpl confSession = mCallManager.getConfCallSession();
        if (confSession != null) {
            if (confSession.getParticipantsCount() >= 5) {
                // It means the conference already contains 6 person. Can not invite more.
                // Give a toast to alert user.
                String failMessage = mContext.getString(R.string.vowifi_conf_can_not_invite_more);
                MyToast.makeText(mContext, failMessage, Toast.LENGTH_LONG).show();
                // Give the merge failed callback.
                handleMergeActionFailed(failMessage);
            } else {
                if (confSession != this) {
                    // If there is conference call, we need invite this call to the conference.
                    inviteThisCallToConference(confSession, mListener);
                } else {
                    ImsCallSessionImpl callSession = mCallManager.getCouldInviteCallSession();
                    if (callSession == null) {
                        // Give the merge failed callback.
                        handleMergeActionFailed("There isn't could invite call.");
                    } else {
                        callSession.merge(confSession, mListener);
                    }
                }
            }
        } else {
            // If there isn't conference call, we need create a new call session for it.
            createConfCall();
        }
    }

    private void handleMergeActionFailed(String failMessage) throws RemoteException {
        Log.e(TAG, failMessage);
        if (mListener != null) {
            mListener.callSessionMergeFailed(new ImsReasonInfo(ImsReasonInfo.CODE_UNSPECIFIED,
                    ImsReasonInfo.CODE_UNSPECIFIED, failMessage));

            // FIXME: As the call may be held or resumed before merge which can not tracked by
            //        ImsCallTracker, so before we give the merge failed callback, we'd like to
            //        give this callback refer to current state.
            //        Another, if this issue should be fixed by framework?
            if (mIsAlive) {
                mListener.callSessionResumed(mCallProfile);
            } else {
                mListener.callSessionHeld(mCallProfile);
            }
        }
    }

    /**
     * Updates the current call's properties (ex. call mode change: video upgrade / downgrade).
     *
     * @param callType call type specified in {@link ImsCallProfile} to be updated
     * @param profile  stream media profile {@link ImsStreamMediaProfile} to be updated
     */
    @Override
    public void update(int callType, ImsStreamMediaProfile profile) throws RemoteException {
        if (Utilities.DEBUG) Log.i(TAG, "Update the current call's type to " + callType + ".");

        // TODO: If the media profile will be update?
        if (callType == mCallProfile.mCallType) {
            handleUpdateActionFailed("This session's old call type is same as the new.");
            return;
        }

        // As the vowifi service is null, need add this action to pending action.
        if (mICall == null) {
            synchronized (mPendingActions) {
                String key = String.valueOf(System.currentTimeMillis());
                PendingAction action = new PendingAction("update", MSG_UPDATE, callType, profile);
                mPendingActions.put(key, action);
            }
            return;
        }

        int res = mICall.sessUpdate(mCallId, VideoType.getNativeVideoType(callType));
        if (res == Result.FAIL) {
            handleUpdateActionFailed("Native update result is " + res);
        } else {
            // TODO: change to update the media profile.
            mCallProfile.mMediaProfile = profile;
        }
    }

    private void handleUpdateActionFailed(String failMessage) throws RemoteException {
        Log.e(TAG, failMessage);
        if (mListener != null) {
            mListener.callSessionUpdateFailed(new ImsReasonInfo(
                    ImsReasonInfo.CODE_UNSPECIFIED, ImsReasonInfo.CODE_UNSPECIFIED, failMessage));
        }
    }

    /**
     * Extends this call to the conference call with the specified recipients.
     *
     * @param participants participant list to be invited to the conference call after extending
     *        the call
     */
    @Override
    public void extendToConference(String[] participants) throws RemoteException {
        Log.w(TAG, "Extends this call to conference call: " + Arrays.toString(participants)
                + ", do not support.");
        // Do not support now.
    }

    /**
     * Requests the conference server to invite an additional participants to the conference.
     *
     * @param participants participant list to be invited to the conference call
     */
    @Override
    public void inviteParticipants(String[] participants) throws RemoteException {
        if (Utilities.DEBUG) {
            Log.i(TAG, "Invite participants: " + Utilities.getStringFromArray(participants));
        }

        // TODO: if need check this participants contains this session's callee.
        if (participants == null || participants.length < 1) {
            handleInviteParticipantsFailed("The participant to invite is null or empty.");
            return;
        }

        // TODO: Need change.
//        StringBuilder ids = new StringBuilder();
//        StringBuilder callees = new StringBuilder();
//        if (mICall != null) {
//            for (int i = 0; i < participants.length; i++) {
//                String id = mCallManager.getCallSessionId(participants[i]);
//                if (id == null) {
//                    if (callees.length() > 0) {
//                        callees.append(",");
//                    }
//                    callees.append(participants[i]);
//                } else {
//                    if (ids.length() > 0) {
//                        ids.append(",");
//                    }
//                    ids.append(id);
//                }
//            }
//            int ret = mICall.confAddMembers(mCallId, callees.toString(), ids.toString());
//            if (ret == Result.INVALID_ID) {
//                handleInviteParticipantsFailed("Failed");
//            }
//        }
    }

    private void handleInviteParticipantsFailed(String failMessage) throws RemoteException {
        Log.e(TAG, failMessage);
        if (mListener != null) {
            mListener.callSessionInviteParticipantsRequestFailed(new ImsReasonInfo(
                    ImsReasonInfo.CODE_UNSPECIFIED, ImsReasonInfo.CODE_UNSPECIFIED, failMessage));
        }
    }

    /**
     * Requests the conference server to remove the specified participants from the conference.
     *
     * @param participants participant list to be removed from the conference call
     */
    @Override
    public void removeParticipants(String[] participants) throws RemoteException {
        if (Utilities.DEBUG) {
            Log.i(TAG, "Remove the participants: " + Utilities.getStringFromArray(participants));
        }

        if (participants == null || participants.length < 1) {
            handleRemoveParticipantsFailed("The participants need to removed is null or empty.");
            return;
        }

        synchronized (mParticipantSessions) {
            ArrayList<String> needRemoveUser = findNeedRemoveUser(participants);
            if (needRemoveUser == null || needRemoveUser.size() < 1) {
                Log.d(TAG, "There isn't any participant need remove.");
                return;
            }

            if (needRemoveUser.size() == getParticipantsCount()) {
                Log.d(TAG, "All the user will be removed from the conference call.");
                // As all the user will be removed from this conference, we'd like to terminate
                // this conference call instead.
                MyToast.makeText(mContext, R.string.vowifi_conf_none_participant,
                        Toast.LENGTH_LONG).show();
                terminate(ImsReasonInfo.CODE_USER_TERMINATED);
                return;
            }

            if (mICall == null) {
                handleRemoveParticipantsFailed("The call interface is null.");
                return;
            }

            String[] needRemoveParticipants = buildKickParticipants(needRemoveUser);
            int ret = mICall.confKickMembers(mCallId, needRemoveParticipants);
            if (ret == Result.SUCCESS) {
                // We'd like to handle as kick off action success.
                for (String user : needRemoveUser) {
                    ImsCallSessionImpl callSession = removeParticipant(user);
                    Bundle bundle = new Bundle();
                    bundle.putString(ImsConferenceState.USER, user);
                    bundle.putString(ImsConferenceState.DISPLAY_TEXT,
                            callSession == null ? "" : callSession.getCallee());
                    bundle.putString(ImsConferenceState.STATUS,
                            ImsConferenceState.STATUS_DISCONNECTED);
                    updateConfParticipants(user, bundle);
                }

                // Give the state update notify.
                if (mListener != null) {
                    mListener.callSessionConferenceStateUpdated(getConfParticipantsState());
                }
            } else if (ret == Result.FAIL) {
                MyToast.makeText(mContext, R.string.vowifi_conf_kick_failed, Toast.LENGTH_LONG)
                        .show();
                handleRemoveParticipantsFailed("Native failed to remove the participants.");
            }
        }
    }

    private void handleRemoveParticipantsFailed(String failMessage) throws RemoteException {
        Log.e(TAG, failMessage);
        // Show the failed toast.
        MyToast.makeText(mContext, R.string.vowifi_conf_kick_failed, Toast.LENGTH_LONG).show();
        if (mListener != null) {
            mListener.callSessionRemoveParticipantsRequestFailed(new ImsReasonInfo(
                    ImsReasonInfo.CODE_UNSPECIFIED, ImsReasonInfo.CODE_UNSPECIFIED, failMessage));
        }
    }

    /**
     * Sends a DTMF code. According to <a href="http://tools.ietf.org/html/rfc2833">RFC 2833</a>,
     * event 0 ~ 9 maps to decimal value 0 ~ 9, '*' to 10, '#' to 11, event 'A' ~ 'D' to 12 ~ 15,
     * and event flash to 16. Currently, event flash is not supported.
     *
     * @param c      the DTMF to send. '0' ~ '9', 'A' ~ 'D', '*', '#' are valid inputs.
     * @param result
     */
    @Override
    public void sendDtmf(char c, Message result) throws RemoteException {
        // TODO: need change
        Log.i(TAG, "sendDtmf: " + c);
        if (mICall != null) {
            if (isConferenceCall()) {
                mICall.confDtmf(mCallId, getDtmfType(c));
            } else {
                mICall.sessDtmf(mCallId, getDtmfType(c));
            }
        }

        // Send event finished, send the result to target.
        Message msg = new Message();
        msg.what = MSG_SEND_DTMF_FINISHED;
        msg.obj = result;
        mHandler.sendMessageDelayed(msg, 500);
    }

    /**
     * Start a DTMF code. According to <a href="http://tools.ietf.org/html/rfc2833">RFC 2833</a>,
     * event 0 ~ 9 maps to decimal value 0 ~ 9, '*' to 10, '#' to 11, event 'A' ~ 'D' to 12 ~ 15,
     * and event flash to 16. Currently, event flash is not supported.
     *
     * @param c the DTMF to send. '0' ~ '9', 'A' ~ 'D', '*', '#' are valid inputs.
     */
    @Override
    public void startDtmf(char c) throws RemoteException {
        // TODO: need change
        Log.i(TAG, "startDtmf: " + c);
        if (mICall != null) {
            if (isConferenceCall()) {
                mICall.confDtmf(mCallId, getDtmfType(c));
            } else {
                mICall.sessDtmf(mCallId, getDtmfType(c));
            }
        }
    }

    /**
     * Stop a DTMF code.
     */
    @Override
    public void stopDtmf() {
        Log.i(TAG, "stopDtmf");
        // TODO:
    }

    /**
     * Sends an USSD message.
     *
     * @param ussdMessage USSD message to send
     */
    @Override
    public void sendUssd(String ussdMessage) throws RemoteException {
        if (Utilities.DEBUG) Log.i(TAG, "Send an USSD message: " + ussdMessage);
        // TODO: need change
        if (mICall != null) {
            mICall.sendUSSDMessage(mCallId, ussdMessage);
        }
    }

    /**
     * Sends Rtt Message
     */
    @Override
    public void sendRttMessage(String rttMessage) {
        // TODO
    }

    /**
     * Sends RTT Upgrade request
     */
    @Override
    public void sendRttModifyRequest(ImsCallProfile to) {
        // TODO
    }

    /**
     * Sends RTT Upgrade response
     */
    @Override
    public void sendRttModifyResponse(boolean response) {
        // TODO
    }

    /**
     * Returns a binder for the video call provider implementation contained within the IMS service
     * process. This binder is used by the VideoCallProvider subclass in Telephony which
     * intermediates between the propriety implementation and Telecomm/InCall.
     */
    @Override
    public IImsVideoCallProvider getVideoCallProvider() {
        if (Utilities.DEBUG) Log.i(TAG, "Get the video call provider: " + mVideoCallProvider);
        return mVideoCallProvider == null ? null : mVideoCallProvider.getInterface();
    }

    /**
     * Determines if the current session is multiparty.
     * @return {@code True} if the session is multiparty.
     */
    @Override
    public boolean isMultiparty() {
        return mCallProfile == null ? false
                : mCallProfile.getCallExtraBoolean(ImsCallProfile.EXTRA_CONFERENCE, false);
    }

    public void updateCallManager(VoWifiCallManager callMgr) {
        if (!mIsEmergency
                || mECBMRequest == null
                || getState() >= State.INITIATED) {
            Log.w(TAG, "The call already initiated, can not change the call interface.");
            return;
        }

        // As this call will be added to another call manager, needn't notify.
        mCallManager.removeCall(this, false);
        // If this call is emergency call, it should be already enter ECBM,
        // we need remove the ECBM request before changed to use the new call manager.
        mCallManager.removeECBMRequest();
        // Remove the call interface changed listener.
        mCallManager.unregisterCallInterfaceChanged(mICallChangedListener);
        mICall = null;

        // Update the values.
        callMgr.registerCallInterfaceChanged(mICallChangedListener);
        callMgr.enterECBMWithCallSession(mECBMRequest);
        // As call manager changed, we need add this call to new manager,
        // and remove it from the old manager.
        callMgr.addCall(this);
        mCallManager = callMgr;
    }

    public ImsVideoCallProviderImpl getVideoCallProviderImpl() {
        return mVideoCallProvider;
    }

    public ImsStreamMediaProfile getMediaProfile() {
        return mCallProfile.mMediaProfile;
    }

    public ImsCallProfile setCallee(String phoneNumber) {
        mPrimaryCallee = phoneNumber;
        mCallProfile.setCallExtra(ImsCallProfile.EXTRA_OI, mPrimaryCallee);

        return mCallProfile;
    }

    public String getCallee() {
        return mPrimaryCallee;
    }

    /**
     * Find the user for the given phone number.
     * @param phoneNumber
     * @return user which used as {@link ImsConferenceState#USER} build as phoneNumber@callId.
     */
    public String findUser(String phoneNumber) {
        if (TextUtils.isEmpty(phoneNumber)) return null;

        if (mParticipantSessions.get(phoneNumber) != null) {
            // It means the phone number is the key.
            return phoneNumber;
        }

        Iterator<Entry<String, ImsCallSessionImpl>> it = mParticipantSessions.entrySet().iterator();
        while(it.hasNext()) {
            Entry<String, ImsCallSessionImpl> entry = it.next();
            String user = entry.getKey();
            ImsCallSessionImpl callSession = entry.getValue();
            if (callSession.isMatched(phoneNumber)) {
                return user;
            }
        }
        return null;
    }

    public void setSecondaryCallee(String phoneNumber) {
        mSecondaryCallee = phoneNumber;
    }

    public String getSecondaryCallee() {
        return mSecondaryCallee;
    }

    public void updateMediaProfile(ImsStreamMediaProfile profile) {
        // TODO: update or replace?
        mCallProfile.mMediaProfile = profile;
    }

    public IImsCallSessionListener getListener() {
        return mListener;
    }

    public void setCallId(int id) {
        mCallId = id;
    }

    public VoWifiCallStateTracker getCallStateTracker() {
        return mCallStateTracker;
    }

    public void updateState(int state) {
        if (mCallStateTracker != null) mCallStateTracker.updateCallState(state);
    }

    public void updateCallType(int newType) {
        if (mCallProfile == null || mCallProfile.mCallType == newType) {
            return;
        }

        try {
            mCallProfile.mCallType = newType;
            updateDataRouterState();

            if (mListener != null) {
                mListener.callSessionUpdated(mCallProfile);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to update the call type as catch the RemoteException e: " + e);
        }
    }

    public void updateDataRouterState() {
        if (!isAlive()) {
            Log.d(TAG, "The call" + this + " isn't alive, needn't update data router state.");
            return;
        }

        try {
            int state = Utilities.CallStateForDataRouter.VOLTE;
            if (mCallManager.getCallRatType() == ServiceState.RIL_RADIO_TECHNOLOGY_IWLAN) {
                state = mCallProfile.isVideoCall() ? Utilities.CallStateForDataRouter.VOWIFI_VIDEO
                        : Utilities.CallStateForDataRouter.VOWIFI;
            }
            mICall.updateDataRouterState(mCallManager.getAttachSessionId(), state);
            Log.d(TAG, "Update the call state to data router. state: "
                    + Utilities.CallStateForDataRouter.getDRStateString(state));
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to update the data router state as catch the ex: " + e);
        }
    }

    public void updateCallRatType(int ratType) {
        if (mCallProfile == null) return;

        mCallProfile.setCallExtra(ImsCallProfile.EXTRA_CALL_RAT_TYPE, String.valueOf(ratType));
        try {
            if (mListener != null) {
                mListener.callSessionUpdated(mCallProfile);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to update the call's rat type as catch the RemoteException e: " + e);
        }
    }

    public void updateVoiceQuality(int quality) {
        if (mCallProfile == null) return;

        if (quality == mCallProfile.mMediaProfile.mAudioQuality
                && quality == mLocalCallProfile.mMediaProfile.mAudioQuality
                && quality == mRemoteCallProfile.mMediaProfile.mAudioQuality) {
            Log.d(TAG, "Same as current audio quality is " + quality + ", needn't update.");
            return;
        }

        mCallProfile.mMediaProfile.mAudioQuality = quality;
        mLocalCallProfile.mMediaProfile.mAudioQuality = quality;
        mRemoteCallProfile.mMediaProfile.mAudioQuality = quality;

        try {
            if (mListener != null) {
                mListener.callSessionUpdated(mCallProfile);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to update the call type as catch the RemoteException e: " + e);
        }
    }

    public void updateRequestAction(int requestAction){
        if (mCallStateTracker != null)  mCallStateTracker.updateRequestAction(requestAction);
    }

    public void updateAliveState(boolean alive) {
        mIsAlive = alive;

        // As alive state changed, we'd like to update the data router state.
        updateDataRouterState();
    }

    public boolean isAlive() {
        return mIsAlive;
    }

    public boolean isHeld() {
        return mCallProfile.mMediaProfile.mAudioDirection == ImsStreamMediaProfile.DIRECTION_SEND;
    }

    public boolean couldUpgradeOrDowngrade() {
        Log.d(TAG, "Current audio dir is: " + mLocalCallProfile.mMediaProfile.mAudioDirection);
        // The local call profile will be update when the call hold/resume or
        // hold received/resume received. And we'd like to handle as can not
        // upgrade or downgrade when the call do not in send & receive state.
        int acceptDirection = SystemProperties.getInt(PROP_KEY_COULD_UPDATE,
                ImsStreamMediaProfile.DIRECTION_SEND_RECEIVE);
        return mLocalCallProfile.mMediaProfile.mAudioDirection == acceptDirection;
    }

    public void updateIsConfHost(boolean isHost) {
        mIsConfHost = isHost;
    }

    public void startAudio() {
        mAudioStart = true;
        mCallManager.startAudioStream();
    }

    public boolean isAudioStart() {
        return mAudioStart;
    }

    public boolean isEmergencyCall() {
        return mIsEmergency;
    }

    public void updateAsIsFocus() {
        mIsFocus = true;
    }

    public boolean isFocus() {
        return mIsFocus;
    }

    public void updateAsCallIsForwarded() {
        mIsForwarded = true;
    }

    public boolean isCallForwarded() {
        return mIsForwarded;
    }

    public boolean isConferenceCall() {
        return mIsConfHost && (mCallProfile == null ? false
                : mCallProfile.getCallExtraBoolean(ImsCallProfile.EXTRA_CONFERENCE, false));
    }

    public ImsCallSessionImpl removeParticipant(String phoneNumber) {
        if (TextUtils.isEmpty(phoneNumber)) return null;

        String user = findUser(phoneNumber);
        if (!TextUtils.isEmpty(user)) {
            ImsCallSessionImpl callSession = mParticipantSessions.get(user);
            mParticipantSessions.remove(user);
            return callSession;
        }

        return null;
    }

    public boolean isMatched(String phoneNumber) {
        return Utilities.isSameCallee(mPrimaryCallee, phoneNumber)
                || Utilities.isSameCallee(mSecondaryCallee, phoneNumber);
    }

    public int getParticipantsCount() {
        return mParticipantSessions.size();
    }

    public void updateConfParticipants(String user, Bundle state) {
        mConfParticipantStates.put(user, state);
    }

    public Bundle getParticipantBundle(String user) {
        if (TextUtils.isEmpty(user)) return null;

        return mConfParticipantStates.get(user);
    }

    public ImsConferenceState getConfParticipantsState() {
        ImsConferenceState state = new ImsConferenceState();
        state.mParticipants.clear();
        state.mParticipants.putAll(mConfParticipantStates);
        return state;
    }

    public ImsCallSessionImpl getConfCallSession() {
        return mConfCallSession;
    }

    public void setConfCallSession(ImsCallSessionImpl confCallSession) {
        mConfCallSession = confCallSession;
        if (confCallSession != null) {
            // It means the conference connected to server.
            mHandler.removeMessages(MSG_MERGE_FAILED);
        }
    }

    public ImsCallSessionImpl getHostCallSession() {
        return mHostCallSession;
    }

    public void setHostCallSession(ImsCallSessionImpl hostCallSession) {
        mHostCallSession = hostCallSession;
    }

    public void addAsWaitForInvite(ImsCallSessionImpl callSession) {
        if (callSession != null) mWaitForInviteSessions.add(callSession);
    }

    public ImsCallSessionImpl getNeedInviteCall() {
        mInInviteSession = mWaitForInviteSessions.pollFirst();
        return mInInviteSession;
    }

    public void setInInviteCall(ImsCallSessionImpl callSession) {
        mInInviteSession = callSession;
    }

    public ImsCallSessionImpl getInInviteCall() {
        return mInInviteSession;
    }

    public void addParticipant(ImsCallSessionImpl callSession) {
        if (callSession == null) {
            Log.e(TAG, "Failed to add this call: " + callSession + " as one participant.");
            return;
        }

        mParticipantSessions.put(callSession.getConfUSER(), callSession);
    }

    public void prepareSRVCCSyncInfo(ArrayList<ImsSrvccCallInfo> infoList, int multipartyOrder) {
        if (Utilities.DEBUG) Log.i(TAG, "Prepare the SRVCC sync info for the call: " + this);

        if (infoList == null) return;

        if (mParticipantSessions.size() > 0) {
            // If this call is conference, we need prepare she SRVCC call info for each child.
            Iterator<Entry<String, ImsCallSessionImpl>> iterator =
                    mParticipantSessions.entrySet().iterator();
            int order = 0;
            while (iterator.hasNext()) {
                Entry<String, ImsCallSessionImpl> entry = iterator.next();
                ImsCallSessionImpl callSession = entry.getValue();
                callSession.prepareSRVCCSyncInfo(infoList, order);
                order = order + 1;
            }
        } else {
            // Prepare this call session's call info.
            ImsSrvccCallInfo info = new ImsSrvccCallInfo();
            // Call id only accept from 0 to 5.
            info.mCallId = infoList.size();
            info.mDir = mCallStateTracker.getSRVCCCallDirection();
            info.mCallState = mCallStateTracker.getSRVCCCallState();
            info.mHoldState = multipartyOrder >= 0 ? SRVCCSyncInfo.HoldState.IDLE
                    : mCallStateTracker.getSRVCCCallHoldState();
            info.mMptyState = multipartyOrder >= 0 ? SRVCCSyncInfo.MultipartyState.YES
                    : (isConferenceCall() ? SRVCCSyncInfo.MultipartyState.YES
                            : SRVCCSyncInfo.MultipartyState.NO);
            info.mMptyOrder = multipartyOrder >= 0 ? multipartyOrder : 0 /* reset as 0 */;
            info.mCallType = getSRVCCCallType();
            info.mNumType = SRVCCSyncInfo.PhoneNumberType.NATIONAL;
            info.mNumber = getCallee();
            infoList.add(info);
        }
    }

    public ImsStreamMediaProfile getHoldMediaProfile() {
        ImsStreamMediaProfile mediaProfile = new ImsStreamMediaProfile();

        if (mCallProfile == null) {
            return mediaProfile;
        }

        mediaProfile.mAudioQuality = mCallProfile.mMediaProfile.mAudioQuality;
        mediaProfile.mVideoQuality = mCallProfile.mMediaProfile.mVideoQuality;
        mediaProfile.mAudioDirection = ImsStreamMediaProfile.DIRECTION_SEND;

        if (mediaProfile.mVideoQuality != ImsStreamMediaProfile.VIDEO_QUALITY_NONE) {
            mediaProfile.mVideoDirection = ImsStreamMediaProfile.DIRECTION_SEND;
        }

        return mediaProfile;
    }

    public ImsStreamMediaProfile getResumeMediaProfile() {
        ImsStreamMediaProfile mediaProfile = new ImsStreamMediaProfile();

        if (mCallProfile == null) {
            return mediaProfile;
        }

        mediaProfile.mAudioQuality = mCallProfile.mMediaProfile.mAudioQuality;
        mediaProfile.mVideoQuality = mCallProfile.mMediaProfile.mVideoQuality;
        mediaProfile.mAudioDirection = ImsStreamMediaProfile.DIRECTION_SEND_RECEIVE;

        if (mediaProfile.mVideoQuality != ImsStreamMediaProfile.VIDEO_QUALITY_NONE) {
            mediaProfile.mVideoDirection = ImsStreamMediaProfile.DIRECTION_SEND_RECEIVE;
        }

        return mediaProfile;
    }

    public int releaseCall() {
        if (Utilities.DEBUG) Log.i(TAG, "Try to release the call: " + mCallId);

        if (mICall == null) {
            Log.e(TAG, "Can not release the call as the call interface is null.");
            return Result.FAIL;
        }

        try {
            int res = Result.FAIL;
            if (isConferenceCall()) {
                res = mICall.confRelease(mCallId);
            } else {
                res = mICall.sessRelease(mCallId);
            }

            if (res == Result.SUCCESS) {
                boolean isVideoCall = Utilities.isVideoCall(mCallProfile.mCallType);
                if (isVideoCall && mVideoCallProvider != null) {
                    Log.d(TAG, "Need to stop all the video as SRVCC success.");
                    // Stop all the video
                    mVideoCallProvider.stopAll();
                }
            } else {
                Log.e(TAG, "Native failed to release the call.");
            }

            // Even native failed, we'd like to remove this call from the list.
            updateState(State.TERMINATED);
            mCallManager.removeCall(this);
            return res;
        } catch (RemoteException e) {
            Log.e(TAG, "Can not release as catch the RemoteException e: " + e);
            return Result.FAIL;
        }
    }

    public int updateSRVCCResult(int srvccResult) {
        if (Utilities.DEBUG) {
            Log.i(TAG, "Try to update the SRVCC result for the call: " + mCallId);
        }

        if (mICall == null) {
            Log.e(TAG, "Can not update the SRVCC result as the call interface is null.");
            return Result.FAIL;
        }

        try {
            int res = Result.FAIL;
            if (isConferenceCall()) {
                res = mICall.confUpdateSRVCCResult(mCallId, srvccResult);
            } else {
                res = mICall.sessUpdateSRVCCResult(mCallId, srvccResult);
            }
            if (res == Result.FAIL) {
                Log.e(TAG, "Native failed to update the SRVCC result.");
            }
            return res;
        } catch (RemoteException e) {
            Log.e(TAG, "Can not update the SRVCC result as catch the RemoteException e: " + e);
            return Result.FAIL;
        }
    }

    public int getDefaultVideoLevel() {
        if (Utilities.DEBUG) {
            Log.i(TAG, "Try to get the default video level for call: " + mCallId);
        }

        if (mICall == null) {
            Log.e(TAG, "Can not start the camera as the call interface is null.");
            return -1;
        }

        try {
            return mICall.getDefaultVideoLevel();
        } catch (RemoteException e) {
            Log.e(TAG, "Can not get the camera capabilities as catch the RemoteException e: " + e);
            return -1;
        }
    }

    public int startCamera(String cameraId) {
        if (Utilities.DEBUG) {
            Log.i(TAG, "Try to start the camera: " + cameraId + " for the call: " + mCallId);
        }
        if (TextUtils.isEmpty(cameraId)) {
            Log.e(TAG, "Can not start the camera as the camera id is null.");
            return Result.FAIL;
        }

        if (mICall == null) {
            Log.e(TAG, "Can not start the camera as the call interface is null.");
            return Result.FAIL;
        }

        try {
            int res = mICall.cameraAttach(isConferenceCall(), mCallId, Camera.isFront(cameraId));
            if (res == Result.FAIL) {
                Log.w(TAG, "Can not start the camera as " + cameraId);
            }
            return res;
        } catch (RemoteException e) {
            Log.e(TAG, "Can not start the camera as catch the RemoteException e: " + e);
            return Result.FAIL;
        }
    }

    public int stopCamera() {
        if (Utilities.DEBUG) Log.i(TAG, "Try to stop the camera for the call: " + mCallId);

        if (mICall == null) {
            Log.e(TAG, "Can not stop the camera as the call interface is null.");
            return Result.FAIL;
        }

        try {
            int res = mICall.cameraDetach(isConferenceCall(), mCallId);
            if (res == Result.FAIL) {
                Log.w(TAG, "Can not stop the camera.");
            }
            return res;
        } catch (RemoteException e) {
            Log.e(TAG, "Can not stop the camera as catch the RemoteException e: " + e);
            return Result.FAIL;
        }
    }

    public int startLocalRender(Surface previewSurface, String cameraId) {
        if (Utilities.DEBUG) Log.i(TAG, "Try to start the local render for the call: " + mCallId);

        if (mICall == null) {
            Log.e(TAG, "Can not start the local render as the call interface is null.");
            return Result.FAIL;
        }

        try {
            int res = mICall.localRenderAdd(previewSurface, Camera.isFront(cameraId));
            if (res == Result.FAIL) {
                Log.w(TAG, "Can not start the local render.");
            }
            return res;
        } catch (RemoteException e) {
            Log.e(TAG, "Can not start the local render as catch the RemoteException e: " + e);
            return Result.FAIL;
        }
    }

    public int stopLocalRender(Surface previewSurface, String cameraId) {
        if (Utilities.DEBUG) Log.i(TAG, "Try to stop the local render for the call: " + mCallId);

        if (mICall == null) {
            Log.e(TAG, "Can not stop the local render as the call interface is null.");
            return Result.FAIL;
        }

        try {
            int res = mICall.localRenderRemove(previewSurface, Camera.isFront(cameraId));
            if (res == Result.FAIL) {
                Log.w(TAG, "Can not stop the local render.");
            }
            return res;
        } catch (RemoteException e) {
            Log.e(TAG, "Can not stop the local render as catch the RemoteException e: " + e);
            return Result.FAIL;
        }
    }

    public int startRemoteRender(Surface displaySurface) {
        if (Utilities.DEBUG) Log.i(TAG, "Try to start the remote render for the call: " + mCallId);

        if (!mIsAlive) {
            Log.w(TAG, "As this call" + this + " is not alive, needn't start the remote render.");
            return Result.FAIL;
        }

        if (mICall == null) {
            Log.e(TAG, "Can not start the remote render as the call interface is null.");
            return Result.FAIL;
        }

        try {
            int res = mICall.remoteRenderAdd(displaySurface, isConferenceCall(), mCallId);
            if (res == Result.FAIL) {
                Log.w(TAG, "Can not start the remote render.");
            }
            return res;
        } catch (RemoteException e) {
            Log.e(TAG, "Can not start the remote render as catch the RemoteException e: " + e);
            return Result.FAIL;
        }
    }

    public int stopRemoteRender(Surface displaySurface, boolean isAsync) {
        if (Utilities.DEBUG) Log.i(TAG, "Try to stop the remote render for the call: " + mCallId);

        if (mICall == null) {
            Log.e(TAG, "Can not stop the remote render as the call interface is null.");
            return Result.FAIL;
        }

        try {
            int res = mICall.remoteRenderRemove(displaySurface, isConferenceCall(), mCallId);
            if (res == Result.FAIL) {
                Log.w(TAG, "Can not stop the remote render.");
            }
            return res;
        } catch (RemoteException e) {
            Log.e(TAG, "Can not stop the remote render as catch the RemoteException e: " + e);
            return Result.FAIL;
        }
    }

    public int startCapture(String cameraId, int width, int height, int frameRate) {
        if (Utilities.DEBUG) {
            Log.i(TAG, "Try to start capture for the call: " + mCallId + ", cameraId: " + cameraId
                    + ", width: " + width + ", height: " + height + ", frameRate: " + frameRate);
        }

        if (mICall == null) {
            Log.e(TAG, "Can not start capture as the call interface is null.");
            return Result.FAIL;
        }

        try {
            int res = mICall.captureStart(Camera.isFront(cameraId), width, height, frameRate);
            if (res == Result.FAIL) {
                Log.w(TAG, "Can not start capture.");
            }
            return res;
        } catch (RemoteException e) {
            Log.e(TAG, "Can not start capture as catch the RemoteException e: " + e);
            return Result.FAIL;
        }
    }

    public int stopCapture(String cameraId) {
        if (Utilities.DEBUG) Log.i(TAG, "Try to stop capture for the call: " + mCallId);

        if (mICall == null) {
            Log.e(TAG, "Can not stop capture as the call interface is null.");
            return Result.FAIL;
        }

        try {
            int res = mICall.captureStop(Camera.isFront(cameraId));
            if (res == Result.FAIL) {
                Log.w(TAG, "Can not stop capture.");
            }
            return res;
        } catch (RemoteException e) {
            Log.e(TAG, "Can not stop capture as catch the RemoteException e: " + e);
            return Result.FAIL;
        }
    }

    public int localRenderRotate(String cameraId, int angle, int deviceOrientation) {
        if (Utilities.DEBUG) Log.i(TAG, "Try to rotate local render for the call: " + mCallId);

        if (mICall == null) {
            Log.e(TAG, "Can not rotate local render as call interface is null.");
            return Result.FAIL;
        }

        try {
            int res = mICall.localRenderRotate(Camera.isFront(cameraId), angle, deviceOrientation);
            if (res == Result.FAIL) {
                Log.w(TAG, "Can not rotate local render for the call: " + mCallId);
            }
            return res;
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to rotate local render as catch RemoteException e: " + e);
            return Result.FAIL;
        }
    }

    public int remoteRenderRotate(int angle) {
        if (Utilities.DEBUG) Log.i(TAG, "Try to rotate remote render for the call: " + mCallId);

        if (mICall == null) {
            Log.e(TAG, "Can not rotate remote render as call interface is null.");
            return Result.FAIL;
        }

        try {
            int res = mICall.remoteRenderRotate(isConferenceCall(), mCallId, angle);
            if (res == Result.FAIL) {
                Log.w(TAG, "Can not rotate remote render for the call: " + mCallId);
            }
            return res;
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to rotate remote render as catch RemoteException e: " + e);
            return Result.FAIL;
        }
    }

    public int sendModifyRequest(int newVideoType) {
        if (Utilities.DEBUG) {
            Log.i(TAG, "Try to send the modify request, new video type: " + newVideoType);
        }

        if (mICall == null) {
            Log.e(TAG, "Can not send the modify request as call interface is null.");
            return Result.FAIL;
        }

        try {
            return mICall.sendSessionModifyRequest(mCallId, newVideoType);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to send the modify request as catch RemoteException e: " + e);
            return Result.FAIL;
        }
    }

    public int sendModifyResponse(int videoType) {
        if (Utilities.DEBUG) {
            Log.i(TAG, "Send session modify response as video type is: " + videoType);
        }

        if (mICall == null) {
            Log.e(TAG, "Can not send session modify response as call interface is null.");
            return Result.FAIL;
        }

        try {
            return mICall.sendSessionModifyResponse(mCallId, videoType);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to send the modify response. e: " + e);
            return Result.FAIL;
        }
    }

    public int setPauseImage(Uri uri) {
        if (Utilities.DEBUG) {
            Log.i(TAG, "Set the pause image to " + uri + " for the call: " + mCallId);
        }

        if (mICall == null) {
            Log.e(TAG, "Can not set the pause image as call interface is null.");
            return Result.FAIL;
        }

        try {
            boolean start = false;
            String uriString = "";
            if (uri != null && !TextUtils.isEmpty(uri.toString())) {
                start = true;
                uriString = uri.getPath().toString();
            }

            int res = mICall.confSetLocalImageForTrans(mCallId, uriString, start);
            if (res == Result.FAIL) {
                Log.w(TAG, "Can not set the pause image to " + uri + " as "
                        + (start ? "start." : "stop."));
            }
            return res;
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to sset the pause image as catch RemoteException e: " + e);
            return Result.FAIL;
        }
    }

    public int getPacketLose() {
        if (Utilities.DEBUG) Log.i(TAG, "Try to get the packet lose.");
        if (mICall == null) {
            Log.e(TAG, "Can not get the packet lose as the call interface is null.");
            return 0;
        }

        try {
            boolean isVideo = Utilities.isVideoCall(mCallProfile.mCallType);
            return mICall.getMediaLostRatio(mCallId, isConferenceCall(), isVideo);
        } catch (RemoteException e) {
            Log.e(TAG, "Catch the remote exception when get the media lose, e: " + e);
        }

        return Result.FAIL;
    }

    public int getJitter() {
        if (Utilities.DEBUG) Log.i(TAG, "Try to get the jitter.");
        if (mICall == null) {
            Log.e(TAG, "Can not get the jitter as the call interface is null.");
            return 0;
        }

        try {
            boolean isVideo = Utilities.isVideoCall(mCallProfile.mCallType);
            return mICall.getMediaJitter(mCallId, isConferenceCall(), isVideo);
        } catch (RemoteException e) {
            Log.e(TAG, "Catch the remote exception when get the media jitter, e: " + e);
        }

        return Result.FAIL;
    }

    public int getRtt() {
        if (Utilities.DEBUG) Log.i(TAG, "Try to get the rtt.");
        if (mICall == null) {
            Log.e(TAG, "Can not get the rtt as the call interface is null.");
            return 0;
        }

        try {
            boolean isVideo = Utilities.isVideoCall(mCallProfile.mCallType);
            return mICall.getMediaRtt(mCallId, isConferenceCall(), isVideo);
        } catch (RemoteException e) {
            Log.e(TAG, "Catch the remote exception when get the media rtt, e: " + e);
        }

        return Result.FAIL;
    }

    public void dialEmergencyCall() {
        if (Utilities.DEBUG) Log.i(TAG, "Try to dial this emergency call: " + this);

        try {
            startCall(mPrimaryCallee);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to dial the emergency call as catch the RemoteException e: " + e);
        }
    }

    public void terminateCall(int reason) {
        if (Utilities.DEBUG) {
            Log.i(TAG, "Terminate this call: " + this + " for reason: " + reason);
        }

        try {
            int res = Result.SUCCESS;
            if (mICall != null && mCallId > 0) {
                if (isConferenceCall()) {
                    res = mICall.confTerm(mCallId, reason);
                } else {
                    res = mICall.sessTerm(mCallId, reason);
                }
            } else {
                Log.w(TAG, "Call interface is null, can not send the terminate action.");
            }

            if (res == Result.SUCCESS) {
                // Handle next action as failed.
                handleNextAction(false);

                // As call terminated, stop all the video.
                // Note: This stop action need before call session terminated callback. Otherwise,
                //       the video call provider maybe changed to null.
                if (mVideoCallProvider != null) {
                    mVideoCallProvider.stopAll();
                }

                int oldState = getState();
                updateState(State.TERMINATED);
                if (mListener != null) {
                    ImsReasonInfo info = new ImsReasonInfo(reason, reason, "reason: " + reason);
                    if (oldState < State.NEGOTIATING) {
                        // It means the call do not ringing now, so we need give the call session
                        // start failed call back.
                        mListener.callSessionInitiatedFailed(info);
                    } else {
                        mListener.callSessionTerminated(info);
                    }
                }
                mCallManager.removeCall(this);
            } else {
                Log.e(TAG, "Native terminate a call failed, res = " + res);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Can not terminate the call as catch the RemoteException: " + e);
        }
    }

    public void incomingNotified() {
        mIsIncomingNotify = true;
    }

    public boolean isUserAcknowledge() {
        // If the call is MO, the user should be acknowledged it.
        return mCallStateTracker.isMOCall() || mIsIncomingNotify;
    }

    public void processNoResponseAction() {
        if (Utilities.DEBUG) Log.i(TAG, "Process no response action.");

        if (mListener == null) return;

        try {
            ImsReasonInfo failedReason = new ImsReasonInfo(
                    ImsReasonInfo.CODE_LOCAL_HO_NOT_FEASIBLE,
                    ImsReasonInfo.CODE_UNSPECIFIED,
                    "No response when SRVCC");

            switch (mCallStateTracker.getSRVCCNoResponseAction()) {
                case VoWifiCallStateTracker.ACTION_ACCEPT:
                    mListener.callSessionInitiatedFailed(failedReason);
                    break;
                case VoWifiCallStateTracker.ACTION_REJECT:
                case VoWifiCallStateTracker.ACTION_TERMINATE:
                    mListener.callSessionTerminated(failedReason);
                    break;
                case VoWifiCallStateTracker.ACTION_HOLD:
                    mListener.callSessionHoldFailed(failedReason);
                    break;
                case VoWifiCallStateTracker.ACTION_RESUME:
                    mListener.callSessionResumeFailed(failedReason);
                    break;
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to process the no response action as catch the exception: " + e);
        }
    }

    public String getConfUSER() {
        return mPrimaryCallee + "@" + mCallId;
    }

    public void autoAnswer() throws RemoteException {
        accept(mCallProfile.mCallType, mCallProfile.mMediaProfile);
    }

    public void setNextAction(NextAction nextAction) {
        mNextAction = nextAction;
    }

    public void handleNextAction(boolean preActionSuccess) throws RemoteException {
        if (mNextAction == null) {
            Log.d(TAG, "There isn't next action, do nothing.");
            return;
        }

        switch (mNextAction._flag) {
            case NextAction.FLAG_INVITE_THIS_CALL:
                // Ignore the pre-action if success, we'd like to handle the next action.
                /*
                ImsCallSessionImpl confSession = (ImsCallSessionImpl) mNextAction._params.get(0);
                ImsCallSessionImpl inviteSession = (ImsCallSessionImpl) mNextAction._params.get(1);
                IImsCallSessionListener hostlistener =
                        (IImsCallSessionListener) mNextAction._params.get(2);

                mNextAction = null;
                inviteCallToConference(confSession, inviteSession, hostlistener);
                */
                break;
            case NextAction.FLAG_MERGE:
                mNextAction = null;
                if (preActionSuccess) {
                    merge();
                } else {
                    // As pre-action failed, we'd like to handle it as merge failed.
                    handleMergeActionFailed("Hold failed, lead to merge failed.");
                }
                break;
        }
    }

    public void terminateChildCalls(int reason) throws RemoteException {
        if (isConferenceCall() && getState() > State.ESTABLISHING) {
            if (mInInviteSession != null
                    && mInInviteSession.getState() > State.INVALID
                    && mInInviteSession.getState() < State.TERMINATED) {
                // The conference call will be terminated, we need terminate the
                // in invite call.
                Log.d(TAG, "Terminate the in invite call: " + mInInviteSession);
                mInInviteSession.terminate(reason);
                mInInviteSession.close();
            }
            if (mWaitForInviteSessions != null && mWaitForInviteSessions.size() > 0) {
                // The conference call will be terminated, we need terminate the
                // wait for invite call.
                for (ImsCallSessionImpl participant : mWaitForInviteSessions) {
                    if (participant != null
                            && participant.getState() > State.INVALID
                            && participant.getState() < State.TERMINATED) {
                        Log.d(TAG, "Terminate the wait for invite call: " + participant);
                        participant.terminate(reason);
                        participant.close();
                    }
                }
            }
        }
    }

    public boolean isUssdCall() {
        int dialType = mCallProfile.getCallExtraInt(ImsCallProfile.EXTRA_DIALSTRING);
        return dialType == ImsCallProfile.DIALSTRING_USSD;
    }

    public boolean supportVideoCall() {
        return mCursor == null ? true : mCursor.supportVideoCall();
    }

    private CallCursor getCallCursor() {
        Uri callUri = Uri.parse(ProviderUtils.CONTENT_URI + "/" + ProviderUtils.FUN_CALL);
        Cursor cursor = mContext.getContentResolver().query(callUri, null, null, null, null);
        if (cursor == null) return null;

        if (cursor.getCount() < 1) {
            cursor.close();
            return null;
        }

        return new CallCursor(cursor);
    }

    private void startEmergencyCall(String callee) throws RemoteException {
        if (Utilities.DEBUG) Log.i(TAG, "Try to start the emergency call.");

        // Set this call is emergency call.
        mIsEmergency = true;

        boolean sosAsNormal = mCursor != null ? mCursor.sosAsNormalCall() : true;
        boolean needRemoveOldS2b = true;
        if (sosAsNormal) {
            // Start an emergency call directly.
            startCall(callee);
        } else {
            // Check if need remove the old s2b for sos.
            needRemoveOldS2b = mCursor != null ? mCursor.sosNeedRemoveOldS2b() : true;
            Log.d(TAG, "Get the need remove old s2b as: " + needRemoveOldS2b);
            if (!needRemoveOldS2b) {
                if (!Utilities.isSupportSOSSingleProcess(mContext)) {
                    // Do not support sos single process, we'd like to handle
                    // the emergency call as need remove old s2b.
                    needRemoveOldS2b = true;
                }
            }
        }
        Log.d(TAG, "start the emergency call. sosAsNormal = " + sosAsNormal
                + ", needRemoveOldS2b = " + needRemoveOldS2b);
        mECBMRequest = ECBMRequest.get(this, sosAsNormal, needRemoveOldS2b);
        mCallManager.enterECBMWithCallSession(mECBMRequest);
    }

    private void startCall(String callee) throws RemoteException {
        if (Utilities.DEBUG) {
            Log.i(TAG, "Start the call with the callee: " + callee);
        }

        if (!mCallManager.isCallFunEnabled()) {
            handleStartActionFailed("Start the call failed. Call function disabled.");
            MyToast.makeText(mContext, R.string.vowifi_call_retry, Toast.LENGTH_LONG).show();
            return;
        }

        if (isUssdCall()) {
            // It means the call need start as USSD.
            startUssdCall(callee);
            return;
        }

        if (!mIsEmergency) {
            // To check if FDN enabled and accept. Only support primary card now.
            FDNHelper fdnHelper = new FDNHelper(mContext, Utilities.getPrimaryCardSubId(mContext));
            if (fdnHelper.isEnabled() && !fdnHelper.isAccept(callee)) {
                // If the FDN enabled, but the callee do not accept. Handle it as start failed.
                handleStartActionFailed(
                        ImsReasonInfo.CODE_FDN_BLOCKED,
                        ImsReasonInfo.CODE_UNSPECIFIED,
                        "FDN enabled, but the callee do not accept.");
                return;
            }
        }

        String peerNumber = callee;
        if (mIsEmergency) {
            int category = mCallProfile.getEmergencyServiceCategories();
            String categoryStr = null;
            if (EMUtils.isValidCategory(category)) {
                categoryStr = String.valueOf(category);
            } else {
                // Get the category as the secondary.
                categoryStr = mCallProfile.getCallExtra(ImsCallProfile.EXTRA_ADDITIONAL_CALL_INFO);
            }

            if (TextUtils.isEmpty(categoryStr)) {
                peerNumber = EMUtils.getUrnWithPhoneNumber(mContext, callee);
            } else {
                peerNumber = EMUtils.getEmergencyCallUrn(categoryStr);
            }
            Log.d(TAG, "Start an emergency call with urn: " + peerNumber);
        }
        // Start the call.
        int clirMode = mCallProfile.getCallExtraInt(ImsCallProfile.EXTRA_OIR,
                ImsCallProfile.OIR_DEFAULT);
        String cookie = buildCookieString(clirMode, -1, "", -1);
        boolean isVideoCall = Utilities.isVideoCall(mCallProfile.mCallType);
        int id = mICall.sessCall(peerNumber, cookie, true, isVideoCall, false, mIsEmergency);
        Log.d(TAG, "Start a call, and get the call id: " + id);
        if (id == Result.INVALID_ID) {
            handleStartActionFailed("Native start the call failed.");
        } else {
            mCallId = id;
            mIsAlive = true;
            // FIXME: As {link ImsPhoneConnection#updateAddressDisplay} function removed
            //        incoming call checking, so we need always set the EXTRA_OIR as
            //        OIR_PRESENTATION_NOT_RESTRICTED used to display the phone number.
            mCallProfile.setCallExtraInt(
                    ImsCallProfile.EXTRA_OIR, ImsCallProfile.OIR_PRESENTATION_NOT_RESTRICTED);
            updateState(State.INITIATED);
            startAudio();

            // Start action success, update the last call action as start.
            updateRequestAction(VoWifiCallStateTracker.ACTION_START);
        }
    }

    /**
     * Will be used when accept the call is enabled CNI update when calling.
     */
//    private String buildCookieString(int type, String info, int age) {
//        try {
//            JSONObject jObject = new JSONObject();
//            jObject.put(JSONUtils.COOKIE_ITEM_CNI_TYPE, String.valueOf(type));
//            jObject.put(JSONUtils.COOKIE_ITEM_CNI_INFO, info);
//            jObject.put(JSONUtils.COOKIE_ITEM_CNI_AGE, String.valueOf(age));
//            return jObject.toString();
//        } catch (JSONException e) {
//            Log.e(TAG, "Failed to build the cookie json as catch the ex: " + e.toString());
//            return "";
//        }
//    }

    private String buildCookieString(int clirMode, int type, String info, int age) {
        try {
            JSONObject jObject = new JSONObject();
            jObject.put(JSONUtils.COOKIE_ITEM_CLIR, String.valueOf(clirMode));
            jObject.put(JSONUtils.COOKIE_ITEM_CNI_TYPE, String.valueOf(type));
            jObject.put(JSONUtils.COOKIE_ITEM_CNI_INFO, info);
            jObject.put(JSONUtils.COOKIE_ITEM_CNI_AGE, String.valueOf(age));
            return jObject.toString();
        } catch (JSONException e) {
            Log.e(TAG, "Failed to build the cookie json as catch the ex: " + e.toString());
            return "";
        }
    }

    /**
     * Initiate an USSD call.
     *
     * @param ussd uri to send
     */
    private void startUssdCall(String ussdMessage) throws RemoteException {
        if (Utilities.DEBUG) Log.i(TAG, "Start as the ussd call: " + ussdMessage);

        int id = mICall.sessCall(ussdMessage, null, true, false, true, false);
        if (id == Result.INVALID_ID) {
            handleStartActionFailed("Native start the ussd call failed.");
        } else {
            mCallId = id;
            updateState(State.INITIATED);
            mIsAlive = true;

            // Start action success, update the last call action as start.
            updateRequestAction(VoWifiCallStateTracker.ACTION_START);
        }
    }

    private void inviteThisCallToConference(ImsCallSessionImpl confSession,
            IImsCallSessionListener hostlistener) throws RemoteException {
        if (Utilities.DEBUG) {
            Log.i(TAG, "Try to invite this call " + mCallId + " to the conference call "
                    + confSession.getCallId());
        }

        // Invite this call session as the participants.
        int res = mICall.confAddMembers(
                Integer.valueOf(confSession.getCallId()), null, new int[] { mCallId });
        if (res == Result.SUCCESS) {
            // Invite this call success, set this call session as the in invite.
            confSession.setInInviteCall(this);

            // Notify merge complete.
            if (hostlistener != null) {
                // As there is a conference call at background or foreground. As this callback
                // defined in the framework, we need give this callback with null object.
                hostlistener.callSessionMergeComplete(null);
            }

            IImsCallSessionListener confListener = confSession.getListener();
            if (confListener != null) {
                // Invite participants success.
                confListener.callSessionInviteParticipantsRequestDelivered();
            }

            mConfCallSession = confSession;
            // As this call session will be invited to the conference, update the state and
            // stop all the video.
            mIsAlive = false;
            updateState(State.TERMINATING);
            mVideoCallProvider.stopAll();

            // Notify the conference participants' state.
            Bundle bundle = new Bundle();
            String user = getConfUSER();
            bundle.putString(ImsConferenceState.USER, user);
            bundle.putString(ImsConferenceState.DISPLAY_TEXT, getCallee());
            bundle.putString(ImsConferenceState.ENDPOINT, getCallee());
            bundle.putString(ImsConferenceState.STATUS, ImsConferenceState.STATUS_PENDING);
            confSession.updateConfParticipants(user, bundle);
            if (confListener != null) {
                confListener.callSessionConferenceStateUpdated(
                        confSession.getConfParticipantsState());
            }
        } else {
            // Failed to invite this call to conference. Prompt the toast to alert the user.
            Log.w(TAG, "Failed to invite this call " + mCallId + " to conference.");
            String errorText =
                    mContext.getString(R.string.vowifi_conf_invite_failed) + getCallee();
            MyToast.makeText(mContext, errorText, Toast.LENGTH_LONG).show();
        }
    }

    private void merge(ImsCallSessionImpl confSession, IImsCallSessionListener hostlistener)
            throws RemoteException {
        inviteThisCallToConference(confSession, hostlistener);
    }

    private void createConfCall() throws RemoteException {
        if (Utilities.DEBUG) Log.i(TAG, "Try to create the conference call.");

        boolean isVideoConference = mCallProfile.isVideoCall();
        if (!isVideoConference) {
            // If there is the established video call, we need start the conference
            // as video conference.
            ArrayList<ImsCallSessionImpl> sessions = mCallManager.getVideoCallSessions();
            if (sessions != null && sessions.size() > 0) {
                for (ImsCallSessionImpl session : sessions) {
                    int state = session.getState();
                    if (state > State.ESTABLISHING && state < State.TERMINATING) {
                        isVideoConference = true;
                        break;
                    }
                }
            }
        }

        // For merge action, we need setup the conference as init and setup.
        int confId = mICall.confInit(isVideoConference);
        if (confId == Result.INVALID_ID) {
            // It means init the conference failed, give the callback.
            handleMergeActionFailed("Init the conference call failed.");
            return;
        }

        int res = mICall.confSetup(confId, null /* cookie, do not use now */);
        if (res == Result.FAIL) {
            // Failed to setup the conference call.
            Log.e(TAG, "Failed to setup the conference call with the conference id: " + confId);
            handleMergeActionFailed("Failed to setup the conference call.");
        } else {
            // Create the new call session for the conference.
            int callType = isVideoConference ? ImsCallProfile.CALL_TYPE_VT
                    : ImsCallProfile.CALL_TYPE_VOICE;
            ImsCallProfile imsCallProfile = new ImsCallProfile(mCallProfile.mServiceType, callType);
            imsCallProfile.setCallExtra(ImsCallProfile.EXTRA_OI, getCallee());
            imsCallProfile.setCallExtra(ImsCallProfile.EXTRA_CNA, null);
            // TODO: why not {@link ImsCallProfile#OIR_DEFAULT}
            imsCallProfile.setCallExtraInt(
                    ImsCallProfile.EXTRA_CNAP, ImsCallProfile.OIR_PRESENTATION_NOT_RESTRICTED);
            imsCallProfile.setCallExtraInt(
                    ImsCallProfile.EXTRA_OIR, ImsCallProfile.OIR_PRESENTATION_NOT_RESTRICTED);
            imsCallProfile.setCallExtra(ImsCallProfile.EXTRA_CALL_RAT_TYPE,
                        String.valueOf(mCallManager.getCallRatType()));

            imsCallProfile.setCallExtraBoolean(ImsCallProfile.EXTRA_CONFERENCE, true);
            ImsCallSessionImpl newConfSession =
                    mCallManager.createMOCallSession(imsCallProfile, null);
            newConfSession.getLocalCallProfile().mCallType = callType;
            newConfSession.getRemoteCallProfile().mCallType = callType;
            newConfSession.setCallId(confId);
            newConfSession.updateMediaProfile(mCallProfile.mMediaProfile);
            newConfSession.setHostCallSession(this);
            newConfSession.updateState(State.INITIATED);
            newConfSession.updateIsConfHost(true);
            newConfSession.startAudio();

            if (mListener != null) {
                mListener.callSessionMergeStarted(newConfSession, imsCallProfile);
            }
            mHandler.sendEmptyMessageDelayed(MSG_MERGE_FAILED, MERGE_TIMEOUT);
        }
    }

    private int getDtmfType(char c) {
        int type = -1;
        switch (c) {
            case '0':
                type = 0;
                break;
            case '1':
                type = 1;
                break;
            case '2':
                type = 2;
                break;
            case '3':
                type = 3;
                break;
            case '4':
                type = 4;
                break;
            case '5':
                type = 5;
                break;
            case '6':
                type = 6;
                break;
            case '7':
                type = 7;
                break;
            case '8':
                type = 8;
                break;
            case '9':
                type = 9;
                break;
            case '*':
                type = 10;
                break;
            case '#':
                type = 11;
                break;
            case 'A':
                type = 12;
                break;
            case 'B':
                type = 13;
                break;
            case 'C':
                type = 14;
                break;
            case 'D':
                type = 15;
                break;
            default:
                break;
        }
        return type;
    }

    private ArrayList<String> findNeedRemoveUser(String[] participants) {
        if (participants == null
                || participants.length < 1
                || getParticipantsCount() < 1) {
            return null;
        }

        ArrayList<String> removeList = new ArrayList<String>();
        Iterator<Entry<String, ImsCallSessionImpl>> it = mParticipantSessions.entrySet().iterator();
        while(it.hasNext()) {
            Entry<String, ImsCallSessionImpl> entry = it.next();
            String user = entry.getKey();
            ImsCallSessionImpl callSession = entry.getValue();
            for (String participant : participants) {
                if (callSession.isMatched(participant)) {
                    removeList.add(user);
                }
            }
        }

        return removeList;
    }

    private String[] buildKickParticipants(ArrayList<String> needKickUsers) {
        if (needKickUsers == null || needKickUsers.size() < 1) {
            return null;
        }

        ArrayList<String> participantList = new ArrayList<String>();
        for (String user : needKickUsers) {
            ImsCallSessionImpl callSession = mParticipantSessions.get(user);
            if (callSession == null) continue;

            String participant = callSession.getSecondaryCallee();
            if (TextUtils.isEmpty(participant)) {
                participant = callSession.getCallee();
            }
            if (!TextUtils.isEmpty(participant)) {
                participantList.add(participant);
            }
        }

        if (participantList.size() < 1) return null;

        String[] participants = new String[participantList.size()];
        participantList.toArray(participants);
        return participants;
    }

    private int getSRVCCCallType() {
        if (mIsEmergency) {
            return SRVCCSyncInfo.CallType.EMERGENCY;
        } else {
            // CP only supports audio SRVCC now, so set callType to NORMAL here.
            return SRVCCSyncInfo.CallType.NORMAL;
        }
    }

    private static class NextAction {
        private static final int FLAG_INVITE_THIS_CALL = 1;
        private static final int FLAG_MERGE            = 2;

        private int _flag;
        private ArrayList<Object> _params;

        public NextAction(int flag, Object... params) {
            _flag = flag;
            _params = new ArrayList<Object>();
            if (params != null) {
                for (Object param : params) {
                    _params.add(param);
                }
            }
        }
    }

}
