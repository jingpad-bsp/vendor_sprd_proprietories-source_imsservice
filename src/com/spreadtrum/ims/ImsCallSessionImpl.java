package com.spreadtrum.ims;

import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Iterator;

import com.android.internal.telephony.CallFailCause;
import com.android.internal.telephony.CommandsInterface;
import com.spreadtrum.ims.ImsDriverCall;
import com.android.internal.telephony.LastCallFailCause;

import android.telephony.CarrierConfigManager;
import android.telephony.CarrierConfigManagerEx;
import android.telephony.ServiceState;
import android.telephony.PhoneNumberUtils;
import android.telephony.emergency.EmergencyNumber;

import com.android.sprd.telephony.RadioInteractor;
import com.android.sprd.telephony.RadioInteractorCallbackListener;

import android.telephony.ims.ImsStreamMediaProfile;
import android.telephony.ims.ImsCallProfile;
import android.telephony.ims.ImsReasonInfo;
import android.telephony.ims.ImsConferenceState;
import android.telephony.ims.ImsCallSession;
import com.android.ims.internal.IImsCallSession;
import android.telephony.ims.aidl.IImsCallSessionListener;
import com.android.ims.internal.IImsVideoCallProvider;
import com.android.internal.telephony.PhoneFactory;
import com.android.internal.telephony.Phone;

import com.spreadtrum.ims.vt.ImsVideoCallProvider;
import com.spreadtrum.ims.vt.VTManagerUtils;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.AsyncResult;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.telephony.SubscriptionManager;
import android.text.TextUtils;
import android.util.Log;
import java.util.concurrent.CopyOnWriteArrayList;
import android.telecom.VideoProfile;

import com.android.internal.telephony.gsm.SuppServiceNotification;
import com.android.internal.telephony.emergency.EmergencyNumberTracker;

public class ImsCallSessionImpl extends IImsCallSession.Stub {
    private static final String TAG = ImsCallSessionImpl.class.getSimpleName();

    private static final String IMS_CONFERENCE_ID = "id";

    private static final int ACTION_COMPLETE_DIAL = 1;
    private static final int ACTION_COMPLETE_HOLD = 2;
    private static final int ACTION_COMPLETE_RESUME = 3;
    private static final int ACTION_COMPLETE_ACCEPT = 4;
    private static final int ACTION_COMPLETE_HANGUP = 5;
    private static final int ACTION_COMPLETE_REJECT = 6;
    private static final int ACTION_COMPLETE_DEFLECT = 7;
    private static final int ACTION_COMPLETE_MERGE = 8;
    private static final int ACTION_COMPLETE_CONFERENCE = 9;
    private static final int ACTION_COMPLETE_ADD_PARTICIPANT = 10;
    private static final int ACTION_COMPLETE_RINGBACK_TONE = 11;
    private static final int ACTION_COMPLETE_REMOVE_PARTICIPANT = 12;
    private static final int ACTION_COMPLETE_GET_CALL_FAIL_CAUSE = 13;
    private static final int ACTION_COMPLETE_SEND_USSD = 14;
    private static final int ACTION_COMPLETE_ACCEPT_AS_AUDIO = 15;
    private static final int ACTION_COMPLETE_DIAL_USSD = 16;

    public static final int CODE_LOCAL_CALL_IMS_HANDOVER_RETRY = 151;

    public static final String EXTRA_MT_CONFERENCE_CALL = "is_mt_conf_call";

    private static final int EVENT_SSN = 101;//SPRD:Add for bug582072

    public enum MergeStatus {
        MERGE_UNKNOWN,
        MERGE_START,
        MERGE_COMPLETED,
        MERGE_END,
    }

    private List<Listener>  mCallSessionImplListeners = new CopyOnWriteArrayList<Listener>();
    private int mState = ImsCallSession.State.IDLE;
    private ImsHandler mHandler;
    public ImsCallProfile mImsCallProfile = new ImsCallProfile();
    private ImsCallProfile mLocalCallProfile = new ImsCallProfile(
            ImsCallProfile.SERVICE_TYPE_NORMAL, ImsCallProfile.CALL_TYPE_VIDEO_N_VOICE);
    private ImsCallProfile mRemoteCallProfile = new ImsCallProfile(
            ImsCallProfile.SERVICE_TYPE_NORMAL, ImsCallProfile.CALL_TYPE_VIDEO_N_VOICE);
    private ImsCallProfile mLocalRequestProfile = new ImsCallProfile();
    private ImsCallProfile mRemoteRequestProfile = new ImsCallProfile();
    private IImsCallSessionListener mIImsCallSessionListener;
    private Context mContext;
    public ImsRadioInterface mCi;
    public ImsServiceCallTracker mImsServiceCallTracker;
    public ImsDriverCall mImsDriverCall;
    private int mDisconnCause = ImsReasonInfo.CODE_UNSPECIFIED;
    private boolean mDesiredMute = false;
    private String mCallee;
    private ImsVideoCallProvider mImsVideoCallProvider;
    // SPRD: add for bug524928
    private MergeStatus mIsMegerAction = MergeStatus.MERGE_UNKNOWN;
    private ImsConferenceState mImsConferenceState;
    private Object mConferenceLock = new Object();
    private boolean mConferenceHost = false;
    private boolean mIsConferenceHeld = false;
    private boolean mIsConferenceActived = false;
    private boolean mIsTxDisable = false;
    private boolean mIsRxDisable = false;
    private boolean mIsLocalHold;
    private boolean mLocalConferenceUpdate;
    // SPRD: add for bug676047
    private boolean mIsInLocalConference = false;
    private boolean mIsRemoteHold;//SPRD: modify by bug666088

    private boolean mIsPendingTerminate;   // BUG 616259
    private int mVideoState;
    public  boolean mInLocalCallForward = false;
    public  boolean mMultiVideoCallDisabled;
    private boolean mAllowEmergencyVideoCalls = false;
    /* SPRD Feature Porting: Volte Local Tone Feature. @{ */
    private static final String ACTION_SUPP_SERVICE_NOTIFICATION =
            "com.android.ACTION_SUPP_SERVICE_NOTIFICATION";
    private static final String SUPP_SERV_CODE_EXTRA = "supp_serv_code";
    private static final String SUPP_SERV_NOTIFICATION_TYPE_EXTRA = "supp_serv_notification_type";
    /* @} */
    //SPRD: add for bug 846738, 905754
    public boolean mIsSupportTxRxVideo;
  //SPRD: add for bug858168
    boolean mVideoHasDowngrade;
    private static final int SPECIAL_CALL_TYPE = 99;
    private boolean mConferenceDriverCallUpdated = false;
    private static final int CALL_ANSWERED_ELSEWHERE = 61441;//UNISOC:add for feature
    public boolean mIsNewConfSession = false;

    // UNISOC: Local RingBackTone Feature.
    private RadioInteractor mRadioInteractor;
    private RadioInteractorCallbackListener mRadioInteractorCallbackListener;
    private int mAudioDirection = ImsStreamMediaProfile.DIRECTION_INVALID;

    // UNISOC: Fix bug1210629
    private enum UpdateRequest {
        UPDATE_NONE,
        UPDATE_HOLD,
        UPDATE_RESUME,
    }
    private UpdateRequest mUpdateRequest = UpdateRequest.UPDATE_NONE;
    /* UNISOC: add for bug 1113109 @{ */
    private String mCnapCallingName = "";
    private int mCnapPresentation = ImsCallProfile.OIR_DEFAULT;
    /* @} */

    public ImsCallSessionImpl(ImsCallProfile profile, IImsCallSessionListener listener, Context context,
            ImsRadioInterface ci, ImsServiceCallTracker callTracker){
        this(null, profile, listener, context, ci, callTracker);
    }

    public ImsCallSessionImpl(ImsCallProfile profile,  Context context,
                              ImsRadioInterface ci, ImsServiceCallTracker callTracker){
        this(null, profile, null, context, ci, callTracker);
    }

    public ImsCallSessionImpl(ImsDriverCall dc, IImsCallSessionListener listener, Context context,
            ImsRadioInterface ci, ImsServiceCallTracker callTracker){
        this(dc, null, listener, context, ci, callTracker);
    }

    public ImsCallSessionImpl(ImsDriverCall dc, Context context,
                              ImsRadioInterface ci, ImsServiceCallTracker callTracker){
        this(dc, null, null, context, ci, callTracker);
    }

    public ImsCallSessionImpl(ImsDriverCall dc, ImsCallProfile profile, IImsCallSessionListener listener, Context context,
                              ImsRadioInterface ci, ImsServiceCallTracker callTracker){
        mImsCallProfile = profile;
        mIImsCallSessionListener = listener;
        mContext = context;
        mCi = ci;
        mImsServiceCallTracker = callTracker;
        if(dc != null){
            mImsDriverCall = new ImsDriverCall(dc);
            updateImsCallProfileFromDC(dc);
        }
        if(mImsCallProfile != null){
            mVideoState = profile.getVideoStateFromImsCallProfile(mImsCallProfile);
        }
        mHandler = new ImsHandler(context.getMainLooper());
        mCi.setOnSuppServiceNotification(mHandler,EVENT_SSN,null);//SPRD:Add for bug582072
        //add for unisoc bug 900271
        mMultiVideoCallDisabled = !(ImsService.getBooleanCarrierConfigByServiceId(getServiceId(),
                                             CarrierConfigManagerEx.KEY_CARRIER_SUPPORT_MULTI_VIDEO_CALL, mContext));
        /* SPRD: add for bug 905754 @{*/
        mIsSupportTxRxVideo = ImsService.getBooleanCarrierConfigByServiceId(getServiceId(),
                                         CarrierConfigManagerEx.KEY_CARRIER_SUPPORT_VIDEO_CALL_TX_RX_CONTROL, mContext);
        mAllowEmergencyVideoCalls = ImsService.getBooleanCarrierConfigByServiceId(getServiceId(),
                CarrierConfigManager.KEY_ALLOW_EMERGENCY_VIDEO_CALLS_BOOL, mContext);
        /* @}*/
        // UNISOC:Local RingBackTone Feature
        mImsVideoCallProvider = new ImsVideoCallProvider(this,ci,mContext);
        registerOnEarlyMediaEvent();
    }

    private void updateImsCallProfileFromDC(ImsDriverCall dc){
        if(dc == null){
            return;
        }
        if(dc.negStatusPresent == dc.PRESENTATION_VALID && dc.negStatus == dc.PRESENTATION_REQUEST){
            if(dc.state != ImsDriverCall.State.INCOMING){
                return;
            }
        }
        if(mImsCallProfile == null) {
            mImsCallProfile = new ImsCallProfile();
            if(mImsServiceCallTracker != null){
                mImsCallProfile.setCallExtra(ImsCallProfile.EXTRA_CALL_RAT_TYPE, mImsServiceCallTracker.getCurRatType());
            }
        }
        mImsCallProfile.setCallExtra(ImsCallProfile.EXTRA_OI, dc.number);
        mImsCallProfile.setCallExtra(ImsCallProfile.EXTRA_CNA, mCnapCallingName);
        mImsCallProfile.setCallExtraInt(ImsCallProfile.EXTRA_OIR,
                ImsCallProfile.presentationToOIR(dc.numberPresentation));
        mImsCallProfile.setCallExtraInt(ImsCallProfile.EXTRA_CNAP,
                ImsCallProfile.presentationToOIR(mCnapPresentation));
        /* SPRD: add for bug 846738 @{ */
        if(dc.isVideoCall()){
            if(!mIsSupportTxRxVideo){
                if(mIsTxDisable){
                    mImsCallProfile.mCallType = ImsCallProfile.CALL_TYPE_VT_RX;
                } else if(mIsRxDisable){
                    mImsCallProfile.mCallType = ImsCallProfile.CALL_TYPE_VT_TX;
                }else {
                    if (dc.getVideoCallMediaDirection() == dc.VIDEO_CALL_MEDIA_DIRECTION_SENDONLY) {
                        mImsCallProfile.mCallType = ImsCallProfile.CALL_TYPE_VT_TX;
                    } else if (dc.getVideoCallMediaDirection() == dc.VIDEO_CALL_MEDIA_DIRECTION_RECVONLY) {
                        mImsCallProfile.mCallType = ImsCallProfile.CALL_TYPE_VT_RX;
                    } else {
                        mImsCallProfile.mCallType = ImsCallProfile.CALL_TYPE_VT;
                    }
                }
            }else if(!mIsRemoteHold){ //SPRD: add for bug 851187
                if (dc.getVideoCallMediaDirection() == dc.VIDEO_CALL_MEDIA_DIRECTION_SENDRECV) {
                    mImsCallProfile.mCallType = ImsCallProfile.CALL_TYPE_VT;
                } else if (dc.getVideoCallMediaDirection() == dc.VIDEO_CALL_MEDIA_DIRECTION_SENDONLY) {
                    mImsCallProfile.mCallType = ImsCallProfile.CALL_TYPE_VT_TX;
                } else if (dc.getVideoCallMediaDirection() == dc.VIDEO_CALL_MEDIA_DIRECTION_RECVONLY) {
                    mImsCallProfile.mCallType = ImsCallProfile.CALL_TYPE_VT_RX;
                } else {
                    Log.i(TAG,"dc.getVideoCallMediaDirection() = "+dc.getVideoCallMediaDirection());
                }
            }
        }else{
            if(!mIsSupportTxRxVideo){
                mIsTxDisable = false;//SPRD:modify for bug602883
                mIsRxDisable = false;
            }
            mImsCallProfile.mCallType = ImsCallProfile.CALL_TYPE_VOICE_N_VIDEO;
        }
        Log.d(TAG,"mImsCallProfile.mCallType = " + mImsCallProfile.mCallType
                + ", mCnapCallingName = " + mCnapCallingName
                + ", mCnapPresentation = " + mCnapPresentation);
        /* @}*/
        /* SPRD: add for new feature for bug 589158/607084 @{ */
        if(dc != null && dc.mediaDescription != null && dc.mediaDescription.contains("hd=1")){
            mImsCallProfile.mMediaProfile.mAudioQuality = ImsStreamMediaProfile.AUDIO_QUALITY_AMR_WB;
            mLocalCallProfile.mMediaProfile.mAudioQuality = ImsStreamMediaProfile.AUDIO_QUALITY_AMR_WB;
        } else {
            mImsCallProfile.mMediaProfile.mAudioQuality = ImsStreamMediaProfile.AUDIO_QUALITY_NONE;
            mLocalCallProfile.mMediaProfile.mAudioQuality = ImsStreamMediaProfile.AUDIO_QUALITY_NONE;
        }
        //UNISOC: add for bug 1153313
        boolean isEmergencyCall = PhoneNumberUtils.isEmergencyNumber(dc.number);
        if(isEmergencyCall && !mAllowEmergencyVideoCalls
                && mImsCallProfile.mCallType == ImsCallProfile.CALL_TYPE_VOICE_N_VIDEO) {
            mLocalCallProfile.mCallType = ImsCallProfile.CALL_TYPE_VOICE;
        }
        /* @}*/
        /* SPRD: add for new feature for bug 602040 and bug 666088@{ */
        if((dc!= null) && (dc.mediaDescription != null) && (dc.mediaDescription.contains("cap:"))){
            String media = dc.mediaDescription.substring(dc.mediaDescription.indexOf("cap:"));
            if(media != null && media.contains("video") && !mIsRemoteHold
               && !(mMultiVideoCallDisabled && isHasBackgroundCallAndActiveCall())){
                mRemoteCallProfile = new ImsCallProfile(ImsCallProfile.SERVICE_TYPE_NORMAL, ImsCallProfile.CALL_TYPE_VIDEO_N_VOICE);//SPRD:modify by bug641686
            }else{
                mRemoteCallProfile = new ImsCallProfile(ImsCallProfile.SERVICE_TYPE_NORMAL, ImsCallProfile.CALL_TYPE_VOICE);
            }
            Log.i(TAG,"vdc.mediaDescription: " + dc.mediaDescription + " mRemoteCallProfile:"+mRemoteCallProfile);
        }
        // SPRD: Fix bug#651203 & 807149
        if (dc != null && isMtConference(dc)) {
            mImsCallProfile.setCallExtraBoolean(EXTRA_MT_CONFERENCE_CALL, true);
        }
        /* @}*/
    }

    public void updateVideoTxRxState(boolean disableTX ,boolean disableRX){
        Log.d(TAG, "updateVideoTxRxState-> mIsTxDisable:" + mIsTxDisable +" ->"+disableTX);
        Log.d(TAG, "updateVideoTxRxState-> mIsRxDisable:" + mIsRxDisable +" ->"+disableRX);
        if(mImsDriverCall == null || (mIsTxDisable == disableTX && mIsRxDisable == disableRX)){
            Log.d(TAG, "updateVideoTxRxState-> dc:" + mImsDriverCall);
            return;
        }
        mIsTxDisable = disableTX;
        mIsRxDisable = disableRX;
        if(mImsDriverCall.isVideoCall()){
            if(mIsTxDisable){
                mImsCallProfile.mCallType = ImsCallProfile.CALL_TYPE_VT_RX;
            }else if(mIsRxDisable){
                mImsCallProfile.mCallType = ImsCallProfile.CALL_TYPE_VT_TX;
            }
            else {
                mImsCallProfile.mCallType = ImsCallProfile.CALL_TYPE_VT;
            }
        }
        Log.d(TAG, "updateVideoTxRxState-> mImsCallProfile.mCallType:" + mImsCallProfile.mCallType);
        updateVideoState();
        try{
            if(mIImsCallSessionListener != null){
                mIImsCallSessionListener.callSessionUpdated(mImsCallProfile);
            }
        } catch(RemoteException e){
            e.printStackTrace();
        }
    }

    public boolean updateFromDc(ImsDriverCall dc){
        if(isImsSessionInvalid() || dc == null){
            Log.d(TAG, "updateFromDc->ImsSessionInvalid! dc:" + dc);
            return false;
        }
        boolean knownState = mImsDriverCall != null && dc != null &&
                mImsDriverCall.state == dc.state;

        updateImsCallProfileFromDC(dc);
        updateVideoState();
        ImsDriverCall.State state = dc.state;
        boolean conferenceHeldStateChange = false;
        boolean conferenceActiveStateChange = false;
        boolean conferenceResumed = false;
        if(mConferenceHost){
            boolean conferenceHeld = false;
            boolean conferenceActive = false;
            for (Map.Entry<String, Bundle> entry : mImsConferenceState.mParticipants.entrySet()) {
                String stateString = entry.getValue().getString(ImsConferenceState.STATUS);
                ImsDriverCall.State conferenceState = ImsDriverCall.ConferenceStringToState(stateString);
                if(conferenceState == ImsDriverCall.State.HOLDING){
                    conferenceHeld = true;
                } else if(conferenceState == ImsDriverCall.State.ACTIVE){
                    conferenceActive = true;
                }
            }
            if(conferenceHeld && conferenceActive){
                conferenceActive = false;
                Log.d(TAG, "updateFromDc->conferenceHeld and conferenceActive both true!");
            }
            if(conferenceActive && mIsConferenceHeld){
                conferenceResumed = true;
            }
            if(conferenceHeld != mIsConferenceHeld){
                mIsConferenceHeld = conferenceHeld;
                conferenceHeldStateChange = true;
            }
            if(mIsConferenceActived != conferenceActive){
                mIsConferenceActived = conferenceActive;
                conferenceActiveStateChange = true;
            }

            Log.d(TAG, "updateFromDc->conferenceHeld:" + conferenceHeld + " conferenceResumed:"+conferenceResumed);
            if(mIsConferenceHeld){
                state = ImsDriverCall.State.HOLDING;
            } else if(mIsConferenceActived){
                state = ImsDriverCall.State.ACTIVE;
            }
        }
        Log.d(TAG, "updateFromDc->mIsConferenceHeld:" + mIsConferenceHeld + " mIsConferenceActived:"+mIsConferenceActived
                +" mIsMegerAction:"+mIsMegerAction);
        if(mIsLocalHold){
            if(dc != null){
                if(dc.state == ImsDriverCall.State.ACTIVE){
                    // SPRD: add for bug667038
                    state = ImsDriverCall.State.HOLDING;
                } else if(dc.state == ImsDriverCall.State.HOLDING){
                    mIsLocalHold = false;
                }
            }
        }
        switch(state){
            case DIALING:
                try{
                    if (mIImsCallSessionListener != null) {
                        mIImsCallSessionListener.callSessionProgressing(
                                mImsCallProfile.mMediaProfile);
                    }
                } catch(RemoteException e){
                    e.printStackTrace();
                }
                break;
            case ALERTING:
                try{
                    mState = ImsCallSession.State.NEGOTIATING;
                    if (!(mImsDriverCall != null && mImsDriverCall.state == ImsDriverCall.State.ALERTING)
                            && mIImsCallSessionListener != null) {
                        mIImsCallSessionListener.callSessionProgressing(
                                mImsCallProfile.mMediaProfile);
                    }
                } catch(RemoteException e){
                    e.printStackTrace();
                }
                break;
            case ACTIVE:
                mState = ImsCallSession.State.ESTABLISHED;
                if(mLocalConferenceUpdate && mIsInLocalConference){
                    mLocalConferenceUpdate = false;
                    mCi.imsEnableLocalConference(true,null);
                }
                try{
                    if (mIImsCallSessionListener != null) {
                        if(mImsDriverCall != null && mImsDriverCall.state == ImsDriverCall.State.HOLDING
                                || conferenceResumed){
                            mIImsCallSessionListener.callSessionResumed(mImsCallProfile);
                            mUpdateRequest = UpdateRequest.UPDATE_NONE;
                        } else if (mImsDriverCall != null && ((mImsDriverCall.state == ImsDriverCall.State.DIALING)
                                || (mImsDriverCall.state == ImsDriverCall.State.ALERTING)
                                || (mImsDriverCall.state == ImsDriverCall.State.INCOMING)
                                || (mImsDriverCall.state == ImsDriverCall.State.WAITING))
                                || (mImsDriverCall == null)) {
                            mIImsCallSessionListener.callSessionInitiated(mImsCallProfile);
                        } else if (mUpdateRequest == UpdateRequest.UPDATE_HOLD && mImsDriverCall != null
                               && mIImsCallSessionListener != null && mImsDriverCall.state == ImsDriverCall.State.ACTIVE) {
                                // Fix bug1210629
                                Log.i(TAG, "updateFromDc->callSessionHoldFailed!" );
                                mIImsCallSessionListener.callSessionHoldFailed(
                                        new ImsReasonInfo(ImsReasonInfo.CODE_UNSPECIFIED, 0,"Hold Failed"));
                           mUpdateRequest = UpdateRequest.UPDATE_NONE;
                       }
                    }
                } catch(RemoteException e){
                    e.printStackTrace();
                }
                break;
            case HOLDING:
                if(mLocalConferenceUpdate && !mIsInLocalConference){
                    mLocalConferenceUpdate = false;
                    mCi.imsEnableLocalConference(false,null);
                }
                try{
                    if (mImsDriverCall != null && mIImsCallSessionListener != null
                            && (mImsDriverCall.state != ImsDriverCall.State.HOLDING || conferenceHeldStateChange)) {
                        mIImsCallSessionListener.callSessionHeld(mImsCallProfile);
                        mUpdateRequest = UpdateRequest.UPDATE_NONE;
                    }
                    // Fix bug1210629
                    if (mUpdateRequest == UpdateRequest.UPDATE_RESUME && mImsDriverCall != null
                            && mIImsCallSessionListener != null && mImsDriverCall.state == ImsDriverCall.State.HOLDING) {
                        Log.i(TAG, "updateFromDc->callSessionResumeFailed!");
                        mIImsCallSessionListener.callSessionResumeFailed(
                                new ImsReasonInfo(ImsReasonInfo.CODE_UNSPECIFIED, 0, "Resume Failed"));
                        mUpdateRequest = UpdateRequest.UPDATE_NONE;
                    }
                } catch(RemoteException e){
                    e.printStackTrace();
                }
                break;
            case INCOMING:
                break;
            case WAITING:
                break;
            case DISCONNECTED:
                mState = ImsCallSession.State.TERMINATED;
                try{
                    if (mIImsCallSessionListener != null){
                        mIImsCallSessionListener.callSessionTerminated(
                                new ImsReasonInfo(mDisconnCause, 0));
                    }
                } catch(RemoteException e){
                    e.printStackTrace();
                }
                break;
            default:
                Log.w(TAG, "updateFromDc->unsupported state: "+dc.state);
                break;
        }
        boolean hasUpdate = false;
        if (mImsDriverCall == null) {
            mImsDriverCall = new ImsDriverCall(dc);
            hasUpdate = true;
        }
        hasUpdate = mImsDriverCall.update(dc) || hasUpdate;

        try{
            if(hasUpdate && knownState
                    && mIImsCallSessionListener != null){
                mIImsCallSessionListener.callSessionUpdated(mImsCallProfile);
            }
            if(hasUpdate || !knownState){
                notifySessionUpdate();
            }
        } catch(RemoteException e){
            e.printStackTrace();
        }
        hasUpdate = hasUpdate || conferenceHeldStateChange || conferenceActiveStateChange;
        Log.d(TAG, "updateFromDc->hasUpdate:"+hasUpdate+" dc:" + dc);

        // BUG 616259
        if (mIsPendingTerminate) {
            terminate(ImsReasonInfo.CODE_USER_TERMINATED);
        }
        setAliveVolteCallType();
        return hasUpdate;
    }

    private boolean isImsSessionInvalid() {
        boolean invalid = (mState == ImsCallSession.State.INVALID);
        if (invalid) {
            Log.w(TAG, "Session is invalid");
        }
        return invalid;
    }

    public interface Listener {
        void onDisconnected(ImsCallSessionImpl session);
        void onUpdate(ImsCallSessionImpl session);
    }

    public void addListener(Listener listener) {
        if (isImsSessionInvalid()) return;

        if (listener == null) {
            Log.w(TAG,"addListener->Listener is null!");
        }
        synchronized (mCallSessionImplListeners) {
            if (!mCallSessionImplListeners.contains(listener)) {
                mCallSessionImplListeners.add(listener);
            } else {
                Log.w(TAG,"Listener already add :" + listener);
            }
        }
    }

    public void removeListener(Listener listener) {
        if (isImsSessionInvalid()) return;

        if (listener == null) {
            Log.w(TAG,"removeListener->Listener is null!");
        }

        synchronized (mCallSessionImplListeners) {
            if (mCallSessionImplListeners.contains(listener)) {
                mCallSessionImplListeners.remove(listener);
            } else {
                Log.w(TAG,"Listener not find " + listener);
            }
        }
    }

    private void notifySessionUpdate(){
        if (isImsSessionInvalid()) return;
        synchronized (mCallSessionImplListeners) {
            for(Listener l : mCallSessionImplListeners) {
                l.onUpdate(this);
            }
        }
    }

    public void notifySessionDisconnected() {
        mState = ImsCallSession.State.TERMINATED;

        /* SPRD: add for bug713220 @{ */
        mCi.getLastCallFailCause(mHandler.obtainMessage(ACTION_COMPLETE_GET_CALL_FAIL_CAUSE,this));
        /* @} */

        synchronized (mCallSessionImplListeners) {
            for (Listener l : mCallSessionImplListeners) {
                l.onDisconnected(this);
                Log.i(TAG, "notifySessionDisconnected..l=" + l);
            }
        }
    }

    private String messageToString(int message){
        switch(message){
            case ACTION_COMPLETE_DIAL:
                return "ACTION_COMPLETE_DIAL";
            case ACTION_COMPLETE_HOLD:
                return "ACTION_COMPLETE_HOLD";
            case ACTION_COMPLETE_RESUME:
                return "ACTION_COMPLETE_RESUME";
            case ACTION_COMPLETE_ACCEPT:
                return "ACTION_COMPLETE_ACCEPT";
            case ACTION_COMPLETE_HANGUP:
                return "ACTION_COMPLETE_HANGUP";
            case ACTION_COMPLETE_REJECT:
                return "ACTION_COMPLETE_REJECT";
            case ACTION_COMPLETE_DEFLECT:
                return "ACTION_COMPLETE_DEFLECT";
            case ACTION_COMPLETE_MERGE:
                return "ACTION_COMPLETE_MERGE";
            case ACTION_COMPLETE_CONFERENCE:
                return "ACTION_COMPLETE_CONFERENCE";
            case ACTION_COMPLETE_ADD_PARTICIPANT:
                return "ACTION_COMPLETE_ADD_PARTICIPANT";
            case ACTION_COMPLETE_RINGBACK_TONE:
                return "ACTION_COMPLETE_RINGBACK_TONE";
            case ACTION_COMPLETE_REMOVE_PARTICIPANT:
                return "ACTION_COMPLETE_REMOVE_PARTICIPANT";
            case ACTION_COMPLETE_GET_CALL_FAIL_CAUSE:
                return "ACTION_COMPLETE_GET_CALL_FAIL_CAUSE";
            case ACTION_COMPLETE_SEND_USSD:
                return "ACTION_COMPLETE_SEND_USSD";
            case ACTION_COMPLETE_ACCEPT_AS_AUDIO:
                return "ACTION_COMPLETE_ACCEPT_AS_AUDIO";
            default:
                return "unkwon message:"+message;
        }
    }


    private class ImsHandler extends Handler {
        ImsHandler(Looper looper) {
            super(looper);
        }
        @Override
        public void handleMessage(Message msg) {
            AsyncResult ar = (AsyncResult) msg.obj;
            Log.i(TAG,"handleMessage->message:"+ messageToString(msg.what));
            switch (msg.what) {
                case ACTION_COMPLETE_DIAL:
                    if (ar != null && ar.exception != null && mIImsCallSessionListener != null) {
                        Log.w(TAG,"handleMessage->ACTION_COMPLETE_DIAL error!");
                        mCi.getLastCallFailCause(mHandler.obtainMessage(ACTION_COMPLETE_GET_CALL_FAIL_CAUSE,this));
                    }
                    mImsServiceCallTracker.operationComplete();//UNISOC:add for bug1011305
                    break;
                case ACTION_COMPLETE_HOLD:
                    if (ar != null && ar.exception != null) {
                        Log.w(TAG,"handleMessage->ACTION_COMPLETE_HOLD error! mIsLocalHold:"+mIsLocalHold);
                        if(mIsLocalHold){
                            return;
                        }
                        if(ar.userObj != null && mIImsCallSessionListener != null) {
                            try{
                                mIImsCallSessionListener.callSessionHoldFailed(
                                        new ImsReasonInfo(ImsReasonInfo.CODE_UNSPECIFIED, 0,"Hold Failed"));
                            } catch(RemoteException e){
                                e.printStackTrace();
                            }
                        }
                    } else {
                        // Fix bug1210629
                        mUpdateRequest = UpdateRequest.UPDATE_HOLD;
                        mIsLocalHold = false;
                    }
                    mImsServiceCallTracker.operationComplete();//UNISOC:add for bug1011305
                    break;
                case ACTION_COMPLETE_RESUME:
                    if (ar != null && ar.exception != null) {
                        Log.w(TAG,"handleMessage->ACTION_COMPLETE_RESUME error!");
                        if(ar.userObj != null && mIImsCallSessionListener != null) {
                            try{
                                mIImsCallSessionListener.callSessionResumeFailed(
                                        new ImsReasonInfo(ImsReasonInfo.CODE_UNSPECIFIED, 0,"Resume Failed"));
                            } catch(RemoteException e){
                                e.printStackTrace();
                            }
                        }
                    } else {
                        // Fix bug1210629
                        mUpdateRequest = UpdateRequest.UPDATE_RESUME;
                    }
                    break;
                case ACTION_COMPLETE_ACCEPT:
                    if (ar != null && ar.exception != null) {
                        Log.w(TAG,"handleMessage->ACTION_COMPLETE_ACCEPT error!");
                    }
                    break;
                case ACTION_COMPLETE_HANGUP:
                    if (ar != null && ar.exception != null) {
                        Log.w(TAG,"handleMessage->ACTION_COMPLETE_HANGUP error!");
                    }
                    mImsServiceCallTracker.pollCallsWhenSafe();
                    break;
                case ACTION_COMPLETE_REJECT:
                    if (ar != null && ar.exception != null) {
                        Log.w(TAG,"handleMessage->ACTION_COMPLETE_REJECT error!");
                        if(ar.userObj != null && mIImsCallSessionListener != null) {
                            try{
                                mIImsCallSessionListener.callSessionInitiatedFailed(
                                        new ImsReasonInfo(ImsReasonInfo.CODE_UNSPECIFIED, 0,"Reject Failed"));
                            } catch(RemoteException e){
                                e.printStackTrace();
                            }
                        }
                    }
                    break;
                case ACTION_COMPLETE_DEFLECT:
                    if (ar != null && ar.exception != null) {
                        Log.w(TAG,"handleMessage->ACTION_COMPLETE_DEFLECT error!");

                    }
                    break;
                case ACTION_COMPLETE_MERGE:
                    if (ar != null && ar.exception != null) {
                        Log.w(TAG,"handleMessage->ACTION_COMPLETE_MERGE error!");
                        mImsServiceCallTracker.onCallMergeFailed((ImsCallSessionImpl)ar.userObj);
                        if(ar.userObj != null && mIImsCallSessionListener != null) {
                            try{
                                mIImsCallSessionListener.callSessionMergeFailed(
                                        new ImsReasonInfo(ImsReasonInfo.CODE_UNSPECIFIED, 0,
                                                "Merge Failed"));
                            } catch(RemoteException e){
                                e.printStackTrace();
                            }
                        }
                    }else {
                        mImsServiceCallTracker.updateSessionMergeState(MergeStatus.MERGE_COMPLETED);//conference process end
                    }
                    break;
                case ACTION_COMPLETE_CONFERENCE:
                    if(ar != null){
                        Log.i(TAG,"ACTION_COMPLETE_CONFERENCE->ar:"+ar+" ar.exception:"+ar.exception
                                +"  ar.userObj:"+ar.userObj);
                    }
                    if (ar != null && ar.exception != null) {
                        if(ar.userObj != null && mIImsCallSessionListener != null) {
                            try{
                                mIImsCallSessionListener.callSessionInitiatedFailed(
                                        new ImsReasonInfo(ImsReasonInfo.CODE_UNSPECIFIED, 0,
                                                "Dial Conference Failed"));
                            } catch(RemoteException e){
                                e.printStackTrace();
                            }
                        }
                    }
                    break;
                case ACTION_COMPLETE_ADD_PARTICIPANT:
                    try{
                        if (ar != null && ar.userObj != null && mIImsCallSessionListener != null) {
                            if (ar.exception != null) {
                                Log.w(TAG,"handleMessage->ACTION_COMPLETE_ADD_PARTICIPANT error!");
                                mIImsCallSessionListener.callSessionInviteParticipantsRequestFailed(
                                                new ImsReasonInfo(
                                                ImsReasonInfo.CODE_UNSPECIFIED, 0,
                                                "Dial Conference Failed"));
                            } else {
                                mIImsCallSessionListener.callSessionInviteParticipantsRequestDelivered();
                            }
                        }
                    } catch(RemoteException e){
                        e.printStackTrace();
                    }
                    break;
                case ACTION_COMPLETE_RINGBACK_TONE:
                    if (ar != null && ar.exception != null) {
                        Log.w(TAG,"handleMessage->ACTION_COMPLETE_RINGBACK_TONE error!");

                    }
                    break;
                case ACTION_COMPLETE_REMOVE_PARTICIPANT:
                    if (ar != null && ar.exception != null) {
                        Log.w(TAG,"handleMessage->ACTION_COMPLETE_REMOVE_PARTICIPANT error!");

                    }
                    break;
                case ACTION_COMPLETE_GET_CALL_FAIL_CAUSE:
                   //UNISOC: add for bug751898
                   LastCallFailCause failCause = null;
                   int causeCode = ImsReasonInfo.CODE_UNSPECIFIED;
                   String vendorCause = null;
                   if(mIImsCallSessionListener != null){
                        //UNISOC: add for bug751898
                        if(ar != null && (ar.exception != null || ar.result == null)) {
                            Log.w(TAG, "handleMessage->ACTION_COMPLETE_GET_CALL_FAIL_CAUSE error!");
                        } else if (ar != null) {
                            failCause = (LastCallFailCause) ar.result;
                            //UNISOC: add for bug993551
                            if(failCause != null){
                                vendorCause = failCause.vendorCause;
                                causeCode = failCause.causeCode;
                            }
                        }

                        /* UNISOC: add for bug713220 @{ */
                        try {
                            if (mImsDriverCall != null) {
                                if(failCause != null
                                        && (mImsDriverCall.state == ImsDriverCall.State.INCOMING || mImsDriverCall.state == ImsDriverCall.State.WAITING)
                                        && failCause.causeCode == CALL_ANSWERED_ELSEWHERE){
                                    mDisconnCause = ImsReasonInfo.CODE_ANSWERED_ELSEWHERE;
                                } else if ((mImsDriverCall.state == ImsDriverCall.State.INCOMING || mImsDriverCall.state == ImsDriverCall.State.WAITING)
                                        && (mDisconnCause != ImsReasonInfo.CODE_USER_DECLINE)) { ////add for set cause when reject incoming call
                                    mDisconnCause = ImsReasonInfo.CODE_USER_TERMINATED_BY_REMOTE;
                                //UNISOC: add for bug751898
                                } else if (failCause != null && failCause.causeCode == 17){//AT< ^CEND: 1,,104,17
                                    mDisconnCause = ImsReasonInfo.CODE_SIP_BUSY;
                                }
                                Log.w(TAG, "callSessionTerminated  mDisconnCause=" + mDisconnCause);
                                mIImsCallSessionListener.callSessionTerminated(
                                        new ImsReasonInfo(mDisconnCause, 0, vendorCause));
                                return;
                            }
                        } catch (RemoteException e) {
                            e.printStackTrace();
                        }
                        /* @} */

                        //UNISOC: add for bug993551
                        ImsReasonInfo reasonInfo = new ImsReasonInfo(0, 0, vendorCause);
                        //UNISOC: add for bug751898
                        if(failCause != null){
                            mDisconnCause = failCause.causeCode;
                            /*UNISOC: add for emergency call redial @{ */
                            if (causeCode == 0xf003) {
                                mDisconnCause = ImsReasonInfo.CODE_EMERGENCY_TEMP_FAILURE;
                                reasonInfo.mCode = mDisconnCause;
                            } else if (causeCode == 0xf004) {
                                mDisconnCause = ImsReasonInfo.CODE_EMERGENCY_PERM_FAILURE;
                                reasonInfo.mCode = mDisconnCause;
                            }
                            /* @} */
                            //UNISOC: add for bug541710
                            if (mDisconnCause == VTManagerUtils.VODEO_CALL_FDN_BLOCKED) {
                                reasonInfo = new ImsReasonInfo(mDisconnCause, 0);
                            } else if (failCause.causeCode == CallFailCause.REDIAL_WHEN_IMS_REGISTERING) { //UNISOC: add for bug663110 mo failed handover vowifi,^CEND: 1,,104,501
                                reasonInfo = new ImsReasonInfo(CODE_LOCAL_CALL_IMS_HANDOVER_RETRY, 0);
                            }
                        }

                        try {
                            mIImsCallSessionListener.callSessionInitiatedFailed(reasonInfo);
                        } catch (RemoteException e) {
                            e.printStackTrace();
                        }
                    }
                    break;
                /* SPRD:add for bug582072 @{ */
                case EVENT_SSN:
                    notifyRemoteVideoProfile(ar);
                    break;
                /* @} */
                case ACTION_COMPLETE_SEND_USSD:
                    if(ar != null && (ar.exception != null || ar.result == null)){
                        Log.w(TAG,"handleMessage->ACTION_COMPLETE_SEND_USSD error!");
                    }
                    break;
                case ACTION_COMPLETE_DIAL_USSD:
                    Log.i(TAG, "handleMessage-> isUssdCall():" + isUssdCall());
                    if (mIImsCallSessionListener != null && isUssdCall()) {
                        try {
                            ImsReasonInfo reasonInfo = new ImsReasonInfo();
                            if (ar != null && ar.exception != null) {
                                mIImsCallSessionListener.callSessionInitiatedFailed(reasonInfo);
                            } else {
                                mIImsCallSessionListener.callSessionInitiated(mImsCallProfile);
                                mIImsCallSessionListener.callSessionTerminated(reasonInfo);
                            }
                        } catch (RemoteException e) {
                            e.printStackTrace();
                        }
                    }
                    break;
                default:
                    Log.w(TAG,"handleMessage->unsupport message:"+msg.what);
                    break;
            }
        }
    }

    @Override
    public void deflect(String deflectNumber){
        mCi.explicitCallTransfer(mHandler.obtainMessage(ACTION_COMPLETE_DEFLECT,this));
    }

    /**
     * Closes the object. This object is not usable after being closed.
     */
    @Override
    public void close(){
        Log.i(TAG, "session close!");
        mState = ImsCallSession.State.INVALID;
        mImsCallProfile = null;
        mLocalCallProfile = null;
        mRemoteCallProfile = null;
        mImsDriverCall = null;
        mCallee = null;
        mImsVideoCallProvider = null;
        mCi.unSetOnSuppServiceNotification(mHandler);//SPRD:Add for bug582072
        mImsConferenceState = null; //SPRD:Add for bug672538
        // UNISOC:Local RingBackTone Feature
        unRegisterEarlyMediaEvent();
    }

    /**
     * Gets the call ID of the session.
     *
     * @return the call ID
     */
    @Override
    public String getCallId() {
        if (isConferenceHost() &&
                (getMergeState() == MergeStatus.MERGE_START
                        || getMergeState() == MergeStatus.MERGE_COMPLETED)) {
            return "0";
        }
        if (mImsDriverCall != null) {
            return Integer.toString(mImsDriverCall.index);
        }
        return "0";
    }

    /**
     * Gets the call profile that this session is associated with
     *
     * @return the call profile that this session is associated with
     */
    @Override
    public ImsCallProfile getCallProfile(){
        return mImsCallProfile;
    }

    /**
     * Gets the local call profile that this session is associated with
     *
     * @return the local call profile that this session is associated with
     */
    @Override
    public ImsCallProfile getLocalCallProfile(){
        return mLocalCallProfile;
    }

    /**
     * Gets the remote call profile that this session is associated with
     *
     * @return the remote call profile that this session is associated with
     */
    @Override
    public ImsCallProfile getRemoteCallProfile(){
        return mRemoteCallProfile;
    }

    /**
     * Gets the local call profile that this session is associated with
     *
     * @return the local call profile that this session is associated with
     */
    public ImsCallProfile getLocalRequestProfile(){
        return mLocalRequestProfile;
    }

    /**
     * Gets the remote call profile that this session is associated with
     *
     * @return the remote call profile that this session is associated with
     */
    public ImsCallProfile getRemoteRequestProfile(){
        return mRemoteRequestProfile;
    }

    /**
     * Gets the value associated with the specified property of this session.
     *
     * @return the string value associated with the specified property
     */
    @Override
    public String getProperty(String name){
        if(isImsSessionInvalid()) return null;
        return mImsCallProfile.getCallExtra(name);
    }

    /**
     * Gets the session state. The value returned must be one of the states in
     * {@link ImsCallSession#State}.
     *
     * @return the session state
     */
    @Override
    public int getState(){
        if(isImsSessionInvalid()) return ImsCallSession.State.INVALID;
        return mState;
    }

    /**
     * Checks if the session is in a call.
     *
     * @return true if the session is in a call
     */
    @Override
    public boolean isInCall(){
        if(isImsSessionInvalid() || mImsDriverCall == null){
            return false;
        } else if(mImsDriverCall.state != ImsDriverCall.State.DISCONNECTED){
            return true;
        }
        return false;
    }

    /**
     * Sets the listener to listen to the session events. A {@link IImsCallSession}
     * can only hold one listener at a time. Subsequent calls to this method
     * override the previous listener.
     *
     * @param listener to listen to the session events of this object
     */
    @Override
    public void setListener(IImsCallSessionListener listener){
        mIImsCallSessionListener = listener;
    }

    /**
     * Mutes or unmutes the mic for the active call.
     *
     * @param muted true if the call is muted, false otherwise
     */
    @Override
    public void setMute(boolean muted){
        Log.w(TAG, "setMute->muted state: "+muted);
        mDesiredMute = muted;
        mCi.setMute(mDesiredMute, null);
    }

    /**
     * Initiates an IMS call with the specified target and call profile.
     * The session listener is called back upon defined session events.
     * The method is only valid to call when the session state is in
     * {@link ImsCallSession#State#IDLE}.
     *
     * @param callee dialed string to make the call to
     * @param profile call profile to make the call with the specified service type,
     *      call type and media information
     * @see Listener#callSessionStarted, Listener#callSessionStartFailed
     */
    @Override
    public void start(String callee, ImsCallProfile profile){
        if(isImsSessionInvalid()){
            Log.w(TAG, "start-> ImsSessionInvalid!");
            return;
        }
        mImsCallProfile.mCallType = profile.mCallType;
        mImsCallProfile.mMediaProfile = profile.mMediaProfile;
        mState = ImsCallSession.State.INITIATED;
        mCallee = callee;
        int clir = profile.getCallExtraInt(ImsCallProfile.EXTRA_OIR);
        boolean isEmergencyCall = PhoneNumberUtils.isLocalEmergencyNumber(mContext,mCallee);
        Phone phone = PhoneFactory.getPhone(getServiceId() -1);
        EmergencyNumberTracker tracker = phone.getEmergencyNumberTracker();
        EmergencyNumber emergencyNumberInfo = null;
        if (tracker != null) {
            emergencyNumberInfo = tracker.getEmergencyNumber(mCallee);
        }

        if (isUssdCall()) {
            clir = CommandsInterface.CLIR_DEFAULT;
            mCi.dial(mCallee, isEmergencyCall, emergencyNumberInfo, false, clir, null,
                    mHandler.obtainMessage(ACTION_COMPLETE_DIAL_USSD, this));
            return;
        }

        mImsServiceCallTracker.countPendingOperations();//UNISOC:add for bug1011305
        if(mImsCallProfile.mCallType == ImsCallProfile.CALL_TYPE_VT
                || mImsCallProfile.mCallType == ImsCallProfile.CALL_TYPE_VIDEO_N_VOICE){
            mCi.dialVP(mCallee,null,0,mHandler.obtainMessage(ACTION_COMPLETE_DIAL,this));
        } else {
            mCi.dial(mCallee, isEmergencyCall, emergencyNumberInfo, false, clir,null,mHandler.obtainMessage(ACTION_COMPLETE_DIAL,this));
        }
        mVideoState = ImsCallProfile
                .getVideoStateFromImsCallProfile(mImsCallProfile);
        //add for unisoc 973687
        mImsServiceCallTracker.removeMessageAfterDial();
    }

    //UNISOC:fix for bug 1552058
    private boolean isUssdCall() {
        boolean isUssd = false;
        if (mImsCallProfile != null) {
            isUssd = mImsCallProfile.getCallExtraInt(ImsCallProfile.EXTRA_DIALSTRING,
                    ImsCallProfile.DIALSTRING_NORMAL) == ImsCallProfile.DIALSTRING_USSD;
        }
        return isUssd;
    }


    /**
     * Initiates an IMS call with the specified participants and call profile.
     * The session listener is called back upon defined session events.
     * The method is only valid to call when the session state is in
     * {@link ImsCallSession#State#IDLE}.
     *
     * @param participants participant list to initiate an IMS conference call
     * @param profile call profile to make the call with the specified service type,
     *      call type and media information
     * @see Listener#callSessionStarted, Listener#callSessionStartFailed
     */
    @Override
    public void startConference(String[] participants, ImsCallProfile profile){
        if(isImsSessionInvalid() || participants == null){
            Log.w(TAG, "startConference-> participants is null or invalid session");
            return;
        }
        synchronized(mConferenceLock){
            mConferenceHost = true;
            mImsConferenceState = new ImsConferenceState();
        }
        String participantsNumber = formatParticipantsNumber(participants);
        Log.d(TAG, "startConference-> participantsNumber : " + participantsNumber);
        mImsServiceCallTracker.countPendingOperations();//UNISOC:add for bug1011305
        mImsCallProfile.setCallExtraBoolean(ImsCallProfile.EXTRA_CONFERENCE, true);
        mIsNewConfSession = true;
        mCi.requestInitialGroupCall(participantsNumber,
                mHandler.obtainMessage(ACTION_COMPLETE_DIAL,this));
    }

    /**
     * Accepts an incoming call or session update.
     *
     * @param callType call type specified in {@link ImsCallProfile} to be answered
     * @param profile stream media profile {@link ImsStreamMediaProfile} to be answered
     * @see Listener#callSessionStarted
     */
    @Override
    public void accept(int callType, ImsStreamMediaProfile profile){
        if(isImsSessionInvalid()){
            Log.w(TAG, "accept-> ImsSessionInvalid!");
            return;
        }
        /*SPRD:bug523375 add voice accept video call @{*/
        if(mVideoHasDowngrade && (callType != SPECIAL_CALL_TYPE)){
           mVideoHasDowngrade = false;
        }else if((callType == SPECIAL_CALL_TYPE) || (callType == ImsCallProfile.CALL_TYPE_VOICE) && (mImsCallProfile.mCallType == ImsCallProfile.CALL_TYPE_VT)){
            Log.i(TAG, "voice accept video call!");
            mCi.requestVolteCallFallBackToVoice(Integer.valueOf(getCallId()),mHandler.obtainMessage(ACTION_COMPLETE_ACCEPT_AS_AUDIO,this));
            if(callType == SPECIAL_CALL_TYPE){
               mVideoHasDowngrade = true;
               return;
            }
        }/*@}*/

        mImsCallProfile.mMediaProfile = profile;
        mCi.acceptCall(mHandler.obtainMessage(ACTION_COMPLETE_ACCEPT,this));
    }

    /**
     * Rejects an incoming call or session update.
     *
     * @param reason reason code to reject an incoming call
     * @see Listener#callSessionStartFailed
     */
    @Override
    public void reject(int reason){
        if(isImsSessionInvalid()){
            Log.w(TAG, "reject-> ImsSessionInvalid!");
            return;
        }
        mDisconnCause = reason; //add for set cause when reject incoming call
        mCi.rejectCall(mHandler.obtainMessage(ACTION_COMPLETE_REJECT,this));
    }

    /**
     * Terminates a call.
     *
     * @see Listener#callSessionTerminated
     */
    @Override
    public void terminate(int reason){
        if(isImsSessionInvalid()){
            Log.w(TAG, "terminate-> ImsSessionInvalid!");
            return;
        }
        mDisconnCause = reason;
        if(mConferenceHost){
            boolean hasRinging = mImsServiceCallTracker.hasRingingCall();
            boolean conferenceActive = isConferenceCallActive();
            boolean allHeld = isAllConferenceCallHeld();
            Log.i(TAG, "terminate conference->hasRinging:"+hasRinging
                    +" isConferenceCallActive():"+conferenceActive
                    +" isAllConferenceCallHeld():"+allHeld);
            if(isForegroundCall()){
                boolean seperateDisconnect = mContext.getResources()
                                            .getBoolean(com.android.internal.R.bool.seperate_disconnect_sub_call);
                // UNISOC: modify for bug 1210505
                if(conferenceActive && !hasRinging && !seperateDisconnect){
                    mCi.hangupForegroundResumeBackground(mHandler.obtainMessage(ACTION_COMPLETE_HANGUP,this));
                } else {
                    hangupAllConferenceCall();
                }
            } else if(isBackgroundCall()){
                if(allHeld && !hasRinging){
                    mCi.hangupWaitingOrBackground(mHandler.obtainMessage(ACTION_COMPLETE_HANGUP,this));
                } else {
                    hangupAllConferenceCall();
                }
            }
            return;
        }
        if(mImsDriverCall != null){
            mCi.hangupConnection(mImsDriverCall.index,
                    mHandler.obtainMessage(ACTION_COMPLETE_HANGUP,this));
            mIsPendingTerminate = false;
        } else {
            mIsPendingTerminate = true;
            Log.w(TAG, "terminate-> mImsDriverCall is null!");
        }
    }

    /**
     * Puts a call on hold. When it succeeds, {@link Listener#callSessionHeld} is called.
     *
     * @param profile stream media profile {@link ImsStreamMediaProfile} to hold the call
     * @see Listener#callSessionHeld, Listener#callSessionHoldFailed
     */
    @Override
    public void hold(ImsStreamMediaProfile profile){
        if(isImsSessionInvalid()){
            Log.w(TAG, "hold-> ImsSessionInvalid!");
            return;
        }
        if(mIsLocalHold){
            Log.i(TAG, "hold-> clear mIsLocalHold!");
            mIsLocalHold = false;
        } else {
            String value = SystemProperties.get("gsm.sys.volte.localhold","0");
            if(value != null && value.equalsIgnoreCase("1")){
                SystemProperties.set("gsm.sys.volte.localhold","0");
                Log.i(TAG,"localhold is true.");
                mIsLocalHold = true;
                enableLocalHold(true);
                mImsServiceCallTracker.pollCallsWhenSafe();
            }

        }
        if(mIsMegerAction == MergeStatus.MERGE_START){
            try{
                Log.w(TAG, "hold-> mIsMegerAction!");
                if(mIImsCallSessionListener != null){
                    mIImsCallSessionListener.callSessionHoldFailed(
                            new ImsReasonInfo(ImsReasonInfo.CODE_UNSPECIFIED, 0,"Hold Failed"));
                }
            } catch(RemoteException e){
                e.printStackTrace();
            }
            return;
        }
        mImsServiceCallTracker.countPendingOperations();//UNISOC:add for bug1011305
        mCi.switchWaitingOrHoldingAndActive(mHandler.obtainMessage(ACTION_COMPLETE_HOLD,this));
    }

    /**
     * Continues a call that's on hold. When it succeeds, {@link Listener#callSessionResumed}
     * is called.
     *
     * @param profile stream media profile {@link ImsStreamMediaProfile} to resume the call
     * @see Listener#callSessionResumed, Listener#callSessionResumeFailed
     */
    @Override
    public void resume(ImsStreamMediaProfile profile){
        if(isImsSessionInvalid()){
            Log.w(TAG, "resume-> ImsSessionInvalid!");
            return;
        }
        if(mIsLocalHold){
            mIsLocalHold = false;
            Log.i(TAG, "resume-> clear mIsLocalHold!");
            mImsServiceCallTracker.pollCallsWhenSafe();
            return;
        }
        //Modify by bug1174856
        if (mIsMegerAction == MergeStatus.MERGE_START) {
            try {
                Log.w(TAG, "resume-> mIsMegerAction!");
                if (mIImsCallSessionListener != null) {
                    mIImsCallSessionListener.callSessionResumeFailed(
                            new ImsReasonInfo(ImsReasonInfo.CODE_UNSPECIFIED, 0, "resume Failed"));
                }
            } catch (RemoteException e) {
                e.printStackTrace();
            }
            return;
        }
        mCi.switchWaitingOrHoldingAndActive(
                mHandler.obtainMessage(ACTION_COMPLETE_RESUME,this));
    }

    /**
     * Merges the active & hold call. When the merge starts,
     * {@link Listener#callSessionMergeStarted} is called.
     * {@link Listener#callSessionMergeComplete} is called if the merge is successful, and
     * {@link Listener#callSessionMergeFailed} is called if the merge fails.
     *
     * @see Listener#callSessionMergeStarted, Listener#callSessionMergeComplete,
     *      Listener#callSessionMergeFailed
     */
    @Override
    public void merge(){
        mImsServiceCallTracker.onCallMergeStart(this);
        mCi.conference(mHandler.obtainMessage(ACTION_COMPLETE_MERGE,this));
    }

    /**
     * Updates the current call's properties (ex. call mode change: video upgrade / downgrade).
     *
     * @param callType call type specified in {@link ImsCallProfile} to be updated
     * @param profile stream media profile {@link ImsStreamMediaProfile} to be updated
     * @see Listener#callSessionUpdated, Listener#callSessionUpdateFailed
     */
    @Override
    public void update(int callType, ImsStreamMediaProfile profile){
        if(mImsDriverCall == null){
            Log.w(TAG, "update-> ImsSessionInvalid!");
            return;
        }
        if(callType == ImsCallProfile.CALL_TYPE_VS_RX){
             if(profile.mAudioDirection == ImsStreamMediaProfile.DIRECTION_SEND){
                //do not allow speak
                mCi.imsSilenceSingleCall(mImsDriverCall.index, true, null);
            } else if(profile.mAudioDirection == ImsStreamMediaProfile.DIRECTION_SEND_RECEIVE){
                //normal
                mCi.imsSilenceSingleCall(mImsDriverCall.index, false, null);
            }

        } else if(callType == ImsCallProfile.CALL_TYPE_VS_TX){
            if(profile.mAudioDirection == ImsStreamMediaProfile.DIRECTION_RECEIVE){
                //mute, do not send audio data
                mCi.imsMuteSingleCall(mImsDriverCall.index, true, null);
            } else if(profile.mAudioDirection == ImsStreamMediaProfile.DIRECTION_SEND_RECEIVE){
                //normal
                mCi.imsMuteSingleCall(mImsDriverCall.index, false, null);
            }

        }
    }

    /**
     * Extends this call to the conference call with the specified recipients.
     *
     * @param participants participant list to be invited to the conference call after extending the call
     * @see Listener#sessionConferenceExtened, Listener#sessionConferenceExtendFailed
     */
    @Override
    public void extendToConference(String[] participants){
        if(participants != null && participants[0] != null) {
            //SPRD:add for bug682362
            Log.i(TAG, "extendToConference-> inLocalCallForward:" + participants[0]);
            if (participants[0].contains("inLocalCallForward")) {
                if(participants[0].contains("true")){
                    mInLocalCallForward = true;
                }else{
                    mInLocalCallForward = false;
                }
            } else if (mImsDriverCall != null) {
                Log.i(TAG, "extendToConference-> action:" + participants[0]);
                if (participants[0].contentEquals("hold")) {
                    mCi.imsHoldSingleCall(mImsDriverCall.index, true,
                            mHandler.obtainMessage(ACTION_COMPLETE_HOLD, this));
                    mLocalConferenceUpdate = true;
                    // SPRD: add for bug676047
                    mIsInLocalConference = false;
                } else if (participants[0].contentEquals("resume")) {
                    mCi.imsHoldSingleCall(mImsDriverCall.index, false,
                            mHandler.obtainMessage(ACTION_COMPLETE_HOLD, this));
                    mLocalConferenceUpdate = true;
                    // SPRD: add for bug676047
                    mIsInLocalConference = true;
                }
            }
        } else {
            Log.w(TAG, "extendToConference: participants is null,mImsDriverCall:"+mImsDriverCall);
        }
    }

    /**
     * Requests the conference server to invite an additional participants to the conference.
     *
     * @param participants participant list to be invited to the conference call
     * @see Listener#sessionInviteParticipantsRequestDelivered,
     *      Listener#sessionInviteParticipantsRequestFailed
     */
    @Override
    public void inviteParticipants(String[] participants){
        if(isImsSessionInvalid() || participants == null){
            Log.w(TAG, "inviteParticipants-> participants is null or invalid session");
            return;
        }
        String participantsNumber = formatParticipantsNumber(participants);
        Log.d(TAG, "inviteParticipants-> participantsNumber :" + participantsNumber);
        mCi.requestAddGroupCall(participantsNumber,
                mHandler.obtainMessage(ACTION_COMPLETE_ADD_PARTICIPANT));
    }

    /**
     * Requests the conference server to remove the specified participants from the conference.
     *
     * @param participants participant list to be removed from the conference call
     * @see Listener#sessionRemoveParticipantsRequestDelivered,
     *      Listener#sessionRemoveParticipantsRequestFailed
     */
    @Override
    public void removeParticipants(String[] participants){
        if(isImsSessionInvalid() || participants == null){
            Log.w(TAG, "removeParticipants error: invalid session or participants is null");
            return;
        }
        for(String s : participants){
            removeSessionFromConference(s);
        }
    }

    /**
     * Sends a DTMF code. According to <a href="http://tools.ietf.org/html/rfc2833">RFC 2833</a>,
     * event 0 ~ 9 maps to decimal value 0 ~ 9, '*' to 10, '#' to 11, event 'A' ~ 'D' to 12 ~ 15,
     * and event flash to 16. Currently, event flash is not supported.
     *
     * @param c the DTMF to send. '0' ~ '9', 'A' ~ 'D', '*', '#' are valid inputs.
     * @param result.
     */
    @Override
    public void sendDtmf(char c, Message result){
        if(isImsSessionInvalid()){
            Log.w(TAG, "sendDtmf-> ImsSessionInvalid!");
            return;
        }
        //SPRD:modify for bug597496
        mCi.sendDtmf(c, result);
    }

    /**
     * Start a DTMF code. According to <a href="http://tools.ietf.org/html/rfc2833">RFC 2833</a>,
     * event 0 ~ 9 maps to decimal value 0 ~ 9, '*' to 10, '#' to 11, event 'A' ~ 'D' to 12 ~ 15,
     * and event flash to 16. Currently, event flash is not supported.
     *
     * @param c the DTMF to send. '0' ~ '9', 'A' ~ 'D', '*', '#' are valid inputs.
     */
    @Override
    public void startDtmf(char c){
        if(isImsSessionInvalid()){
            Log.w(TAG, "startDtmf-> ImsSessionInvalid!");
            return;
        }
        mCi.startDtmf(c, null);
    }

    /**
     * Stop a DTMF code.
     */
    @Override
    public void stopDtmf(){
        if(isImsSessionInvalid()){
            Log.w(TAG, "stopDtmf-> ImsSessionInvalid!");
            return;
        }
        mCi.stopDtmf(null);
    }

    /**
     * Sends an USSD message.
     *
     * @param ussdMessage USSD message to send
     */
    @Override
    public void sendUssd(String ussdMessage){
        mCi.sendUSSD(ussdMessage, mHandler.obtainMessage(ACTION_COMPLETE_SEND_USSD));
    }

    /**
     * Returns a binder for the video call provider implementation contained within the IMS service
     * process. This binder is used by the VideoCallProvider subclass in Telephony which
     * intermediates between the propriety implementation and Telecomm/InCall.
     */
    @Override
    public IImsVideoCallProvider getVideoCallProvider(){
        return (IImsVideoCallProvider)(mImsVideoCallProvider.getInterface());
    }

    public ImsVideoCallProvider getImsVideoCallProvider(){
        return mImsVideoCallProvider;
    }
    /**
     * Determines if the current session is multiparty.
     * @return {@code True} if the session is multiparty.
     */
    @Override
    public boolean isMultiparty() {
        if (isImsSessionInvalid()) {
            Log.w(TAG, "isMultiparty->session is invalid");
            return false;
        }
        return mImsCallProfile == null ? false
                : mImsCallProfile.getCallExtraBoolean(ImsCallProfile.EXTRA_CONFERENCE, false);
    }

    /**
     * Sends Rtt Message
     */
    @Override
    public void sendRttMessage(String rttMessage) {
    }

    /**
     * Sends RTT Upgrade request
     */
    @Override
    public void sendRttModifyRequest(ImsCallProfile to) {
    }

    /**
     * Sends RTT Upgrade response
     */
    @Override
    public void sendRttModifyResponse(boolean response) {
    }

    public void hangup(){
        if(isImsSessionInvalid()){
            Log.w(TAG, "hangup-> ImsSessionInvalid!");
            return;
        }
        if(mImsDriverCall != null){
            Log.i(TAG, "hangup-> isMultiparty state:"+mImsDriverCall.state);
            mCi.hangupConnection(mImsDriverCall.index,
                    mHandler.obtainMessage(ACTION_COMPLETE_HANGUP,this));
        } else {
            Log.w(TAG, "terminate-> mImsDriverCall is null!");
        }
    }
    public int getServiceId(){
        return mImsServiceCallTracker.getServiceId();
    }

    public boolean isForegroundCall(){
        return (mImsDriverCall != null &&
                (mImsDriverCall.state == ImsDriverCall.State.ACTIVE
                || mImsDriverCall.state == ImsDriverCall.State.DIALING
                || mImsDriverCall.state == ImsDriverCall.State.ALERTING));
    }

    public boolean isBackgroundCall(ImsDriverCall dc){
        return (dc != null && dc.state == ImsDriverCall.State.HOLDING);
    }

    public boolean isForegroundCall(ImsDriverCall dc){
        return (dc != null &&
                (dc.state == ImsDriverCall.State.ACTIVE
                || dc.state == ImsDriverCall.State.DIALING
                || dc.state == ImsDriverCall.State.ALERTING));
    }

    public boolean isBackgroundCall(){
        return (mImsDriverCall != null && mImsDriverCall.state == ImsDriverCall.State.HOLDING);
    }

    /* SPRD: add for bug 552691 & bug 596461 @{ */
    public void setMergeState(MergeStatus isMerge) {
        mIsMegerAction = isMerge;
    }

    public MergeStatus getMergeState(){
        return mIsMegerAction;
    }

    public void notifyMergeComplete() {
        try {
            if (mIImsCallSessionListener != null) {
                Log.d(TAG, "notifyMergeComplete->this: " + this + " mIImsCallSessionListener: " + mIImsCallSessionListener);
                ImsCallSessionImpl conferenceSession = mImsServiceCallTracker.getConSession();
                if (conferenceSession != null) {
                    if (conferenceSession.isNewConfSesson()) {
                        conferenceSession = null;
                    } else {
                        conferenceSession.mIsNewConfSession = true;
                    }
                }
                Log.d(TAG, "notifyMergeComplete->conferenceSession: " + conferenceSession);
                mIImsCallSessionListener.callSessionMergeComplete((IImsCallSession) conferenceSession);
            }
            mIsMegerAction = MergeStatus.MERGE_END;
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }
    /* @} */

    public boolean isConferenceHost(){
        return mConferenceHost;
    }

    public boolean inSameConference(ImsDriverCall dc) {
        if (!mConferenceHost || mImsDriverCall == null || dc == null) {
            Log.d(TAG, "inSameConference-> mConferenceHost:" + mConferenceHost
                    + " mImsDriverCall is null:" + (mImsDriverCall == null)
                    + " dc is null:" + (dc == null));
            return false;
        }
        Log.d(TAG, "inSameConference-> mImsDriverCall.mptyState:" + dc.mptyState);

        if (mImsConferenceState != null && mImsConferenceState.mParticipants.containsKey(Integer.toString(dc.index))) {
            return true;
        }
        return false;
    }

    public boolean isPsMode() {
        if (isImsSessionInvalid()) {
            return false;
        }
        if (mImsDriverCall == null) {
            return false;
        }
        return mImsDriverCall.csMode == 0;
    }

    public boolean isDialingCall() {
        return (mImsDriverCall != null && (mImsDriverCall.state == ImsDriverCall.State.DIALING
                || mImsDriverCall.state == ImsDriverCall.State.ALERTING));
    }

    public boolean updateImsConfrenceMember(ImsDriverCall dc){
        synchronized(mConferenceLock){
            if(isImsSessionInvalid() || mImsConferenceState == null){
                Log.w(TAG, "updateImsConfrenceMember-> isImsSessionInvalid or mImsConferenceState is null:"
                        +(mImsConferenceState == null));
                return false;
            }
            Bundle b;
            boolean isChanged = false;
            if(mImsConferenceState.mParticipants.containsKey(Integer.toString(dc.index))){
                b = mImsConferenceState.mParticipants.get(Integer.toString(dc.index));
                String number = b.getString(ImsConferenceState.ENDPOINT);
                if(number == null || !number.equals(dc.number)){
                    b.remove(ImsConferenceState.ENDPOINT);
                    b.remove(ImsConferenceState.USER);
                    /* UNISOC: Fix wrong status of ConferenceParticipant's presentation. &&
                       Fix createConferenceParticipantConnection fail issue.@{ */
                    if (!TextUtils.isEmpty(dc.number)) {
                        b.putString(ImsConferenceState.ENDPOINT, dc.number);
                        b.putString(ImsConferenceState.USER, dc.number + "@" + dc.index);
                    } else {
                        b.putString(ImsConferenceState.USER, dc.number);
                        b.putString(ImsConferenceState.ENDPOINT, dc.number + "@" + dc.index);
                    }
                    /* @} */
                    isChanged = true;
                }
                String stateString = b.getString(ImsConferenceState.STATUS);
                if(stateString != null) {
                    ImsDriverCall.State confState = ImsDriverCall.ConferenceStringToState(stateString);
                    if(confState != dc.state){
                        b.remove(ImsConferenceState.STATUS);
                        b.putString(ImsConferenceState.STATUS,ImsDriverCall.stateToConferenceString(dc.state));
                        isChanged = true;
                    }
                }

            } else {
                b = new Bundle();
                b.putString(IMS_CONFERENCE_ID,Integer.toString(dc.index));
                /* UNISOC: Fix wrong status of ConferenceParticipant's presentation. &&
                   Fix createConferenceParticipantConnection fail issue. @{ */
                if (!TextUtils.isEmpty(dc.number)) {
                    b.putString(ImsConferenceState.ENDPOINT, dc.number);
                    b.putString(ImsConferenceState.USER, dc.number + "@" + dc.index);
                } else {
                    b.putString(ImsConferenceState.ENDPOINT, dc.number + "@" + dc.index);
                    b.putString(ImsConferenceState.USER, dc.number);
                }
                /* @} */

                b.putString(ImsConferenceState.STATUS,ImsDriverCall.stateToConferenceString(dc.state));
                mImsConferenceState.mParticipants.put(Integer.toString(dc.index),b);
                isChanged = true;
            }
            Log.i(TAG, "updateImsConfrenceMember cid:"+dc.index
                    +" state:"+dc.state+ " mImsConferenceState:"+mImsConferenceState+"this:"+this);
            return isChanged;
        }
    }

    public void notifyConferenceStateChange() {
        if (mImsConferenceState == null) {
            Log.w(TAG, "notifyConferenceStateChange->mImsConferenceState is null!");
            return;
        }
        Log.d(TAG, "notifyConferenceStateChange->mImsConferenceState:" + mImsConferenceState);
        try {
            if (mIImsCallSessionListener != null) {
                mIImsCallSessionListener.callSessionConferenceStateUpdated(mImsConferenceState);
            }
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    public void initConferenceDc(ImsDriverCall dc){
        if (mImsDriverCall == null) {
            Log.i(TAG, "initConferenceDc-> dc is null!");
            mImsDriverCall = new ImsDriverCall(dc);
        }
    }

    public void removeInvalidSessionFromConference(Map <String, ImsDriverCall> validConfDriverCall) {
        if (mImsConferenceState == null) {
            Log.w(TAG, "removeInvalidSessionFromConference->mImsConferenceState is null!");
            return;
        }
        for (Iterator<Map.Entry<String, Bundle>> it =
             mImsConferenceState.mParticipants.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, Bundle> e = it.next();
            String index = e.getValue().getString(IMS_CONFERENCE_ID);
            if (index != null && validConfDriverCall.get(index) == null) {
                it.remove();
                Log.i(TAG, "removeInvalidSessionFromConference-> index:" + index);
            }
        }
    }

    public void removeSessionFromConference(String number){
        if(mImsConferenceState == null || number == null){
            Log.w(TAG, "removeSessionFromConference->mImsConferenceState is null:"+(mImsConferenceState == null)
                    +" number is null:"+(number == null));
            return;
        }
        for (Iterator<Map.Entry<String, Bundle>> it =
                mImsConferenceState.mParticipants.entrySet().iterator(); it.hasNext();) {
            Map.Entry<String, Bundle> e = it.next();
            String index = e.getValue().getString(IMS_CONFERENCE_ID);
            String address = e.getValue().getString(ImsConferenceState.USER);
            Log.d(TAG, "removeSessionFromConference-> number:"+number+" address:"+address);
            if (number.equalsIgnoreCase(address)) {
                mCi.hangupConnection(Integer.parseInt(index),
                        mHandler.obtainMessage(ACTION_COMPLETE_HANGUP,this));
            }
        }
    }

    public boolean isConferenceAlive(){
        if(mImsConferenceState == null){
            Log.w(TAG, "isConferenceDisconnected->mImsConferenceState is null!");
            return false;
        }
        return mImsConferenceState.mParticipants.size() > 0;
    }

    public void hangupAllConferenceCall(){
        if(mImsConferenceState == null){
            Log.w(TAG, "removeSessionFromConference->mImsConferenceState is null:"+(mImsConferenceState == null));
            return;
        }
        for (Iterator<Map.Entry<String, Bundle>> it =
                mImsConferenceState.mParticipants.entrySet().iterator(); it.hasNext();) {
            Map.Entry<String, Bundle> e = it.next();
            String index = e.getValue().getString(IMS_CONFERENCE_ID);
            mCi.hangupConnection(Integer.parseInt(index),
                    mHandler.obtainMessage(ACTION_COMPLETE_HANGUP,this));
        }
    }

    public boolean isConferenceCallActive(){
        if(mImsConferenceState == null){
            Log.w(TAG, "isConferenceCallActive->mImsConferenceState is null:"+(mImsConferenceState == null));
            return false;
        }
        for (Iterator<Map.Entry<String, Bundle>> it =
                mImsConferenceState.mParticipants.entrySet().iterator(); it.hasNext();) {
            Map.Entry<String, Bundle> e = it.next();
            String status = e.getValue().getString(ImsConferenceState.STATUS);
            if(status != null &&
                    ImsDriverCall.ConferenceStringToState(status) == ImsDriverCall.State.ACTIVE){
                return true;
            }
        }
        return false;
    }

    public boolean isAllConferenceCallHeld(){
        if(mImsConferenceState == null){
            Log.w(TAG, "isAllConferenceCallHeld->mImsConferenceState is null:"+(mImsConferenceState == null));
            return false;
        }
        for (Iterator<Map.Entry<String, Bundle>> it =
                mImsConferenceState.mParticipants.entrySet().iterator(); it.hasNext();) {
            Map.Entry<String, Bundle> e = it.next();
            String status = e.getValue().getString(ImsConferenceState.STATUS);
            if(status != null &&
                    ImsDriverCall.ConferenceStringToState(status) != ImsDriverCall.State.HOLDING &&
                    ImsDriverCall.ConferenceStringToState(status) != ImsDriverCall.State.DISCONNECTED){
                return false;
            }
        }
        return true;
    }

    public boolean isRingingCall(){
        return (mImsDriverCall != null &&
                (mImsDriverCall.state == ImsDriverCall.State.INCOMING
                || mImsDriverCall.state == ImsDriverCall.State.WAITING));
    }

    public void disconnectForConferenceMember() {
        mState = ImsCallSession.State.TERMINATED;
        notifySessionDisconnectedByMerge();
        mImsServiceCallTracker.removeConferenceMemberSession(this);
    }

    /*UNISOC: add for bug1104005 @{ */
    public void notifySessionDisconnectedByMerge() {
        mState = ImsCallSession.State.TERMINATED;
        synchronized (mCallSessionImplListeners) {
            for (Listener l : mCallSessionImplListeners) {
                l.onDisconnected(this);
                Log.i(TAG, "notifySessionDisconnectedByMerge..l=" + l);
            }
        }
    }
    /* @} */

    /* SPRD:Add for bug582072 @{ */
    public void notifyRemoteVideoProfile(AsyncResult ar) {
        SuppServiceNotification notification = (SuppServiceNotification) ar.result;

        /* SPRD Feature Porting: Volte Local Tone Feature. @{ */
        if (notification != null) {
            int code = notification.code;
            int notificationType = notification.notificationType;
            Intent intent = new Intent();
            intent.setAction(ACTION_SUPP_SERVICE_NOTIFICATION);
            intent.putExtra(SUPP_SERV_CODE_EXTRA, code);
            intent.putExtra(SUPP_SERV_NOTIFICATION_TYPE_EXTRA, notificationType);
            mContext.sendBroadcast(intent);
        }
        /* @} */

        //SPRD:modify by bug 786859.869743.880656
        if (notification == null || notification.notificationType == 0
                || (notification.index != 0 && (Integer.valueOf(getCallId()) != notification.index))) {
            return;
        }
        switch (notification.code) {
            case SuppServiceNotification.CODE_2_CALL_ON_HOLD:
                if (notification.notificationType == 1) {
                    mIsRemoteHold = true;//SPRD: modify by bug666088 and modify for bug801672
                }
                mRemoteCallProfile = new ImsCallProfile(
                        ImsCallProfile.SERVICE_TYPE_NORMAL, ImsCallProfile.CALL_TYPE_VOICE);
                /* SPRD: add for bug 851187@*/
                try {
                    if (mIImsCallSessionListener != null) {
                        mIImsCallSessionListener.callSessionHoldReceived(mImsCallProfile);
                        mIImsCallSessionListener.callSessionUpdated(mImsCallProfile);
                    }
                } catch (RemoteException e) {
                    e.printStackTrace();
                }
                /*@}*/
                break;
            case SuppServiceNotification.CODE_2_CALL_RETRIEVED:
                mIsRemoteHold = false;//SPRD: modify by bug666088
                mRemoteCallProfile = new ImsCallProfile(
                        ImsCallProfile.SERVICE_TYPE_NORMAL, ImsCallProfile.CALL_TYPE_VIDEO_N_VOICE);
                /* SPRD: add for bug 851187@*/
                try {
                    if (mIImsCallSessionListener != null) {
                        mIImsCallSessionListener.callSessionResumeReceived(mImsCallProfile);
                        mIImsCallSessionListener.callSessionUpdated(mImsCallProfile);
                    }
                } catch (RemoteException e) {
                    e.printStackTrace();
                }
                /*@}*/
                break;
        }
    }
    /* @} */

    /*SPRD:add for bug664628 @*/
    public int getOneConferenceMember() {
        if (mImsConferenceState == null) {
            Log.w(TAG, "getOneConferenceMember->mImsConferenceState is null!");
            return -1;
        }
        for (Iterator<Map.Entry<String, Bundle>> it =
             mImsConferenceState.mParticipants.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, Bundle> e = it.next();
            String index = e.getValue().getString(IMS_CONFERENCE_ID);
            if (index != null) {
                return Integer.parseInt(index);
            }
        }
        return -1;
    }/*@}*/

    //SPRD: add for bug579560
    public boolean isHasBackgroundCallAndActiveCall(){
        Log.i(TAG, "isHasBackgroundCallAndActiveCall()");
        return mImsServiceCallTracker.isHasBackgroundCallAndActiveCall();
    }
    /*SPRD: add for 605475@{*/
    public int getCurrentUserId(){
        return mImsServiceCallTracker.getCurrentUserId();
    }
    /* @} */

    public boolean isActiveCall(){
        return (mImsDriverCall != null &&
                mImsDriverCall.state == ImsDriverCall.State.ACTIVE);
    }

    public void enableLocalHold(boolean enable){
        mCi.enableLocalHold(enable,null);
    }

    public void updateVideoState(){
        if (mImsCallProfile == null) {
            return;
        }
        int newVideoState = ImsCallProfile
                .getVideoStateFromImsCallProfile(mImsCallProfile);
        if (mVideoState != newVideoState) {
            mVideoState = newVideoState;
            mImsServiceCallTracker.onVideoStateChanged(mVideoState);
            mImsServiceCallTracker.setAliveVolteCallType(mImsCallProfile.getCallType());
        }
    }

    /* SPRD: add for bug676047 @{ */
    public boolean isInLocalConference(){
        return mIsInLocalConference;
    }

    public boolean getIsInLocalConference(){
        return mImsServiceCallTracker.isHasInLocalConferenceSession();
    }
    /* @} */

    /* SPRD: add for bug837323 @{ */
    public void updateCallRatType(boolean wifiEnable) {
        if (mImsCallProfile != null) {
            if (wifiEnable) {
                mImsCallProfile.setCallExtra(ImsCallProfile.EXTRA_CALL_RAT_TYPE,
                        String.valueOf(ServiceState.RIL_RADIO_TECHNOLOGY_IWLAN));
            } else {
                mImsCallProfile.setCallExtra(ImsCallProfile.EXTRA_CALL_RAT_TYPE, "");
            }
            try {
                if (mIImsCallSessionListener != null) {
                    mIImsCallSessionListener.callSessionUpdated(mImsCallProfile);
                    if (isMultiparty()) {
                        mIImsCallSessionListener
                                .callSessionConferenceStateUpdated(mImsConferenceState);
                    }
                }
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
    }
    /* @} */
    /* SPRD: add for bug850940 @{ */
    public boolean getIsPendingTerminate(){
        return mIsPendingTerminate;
    }
    public void terminatePendingCall(int reason, int index){
        if(isImsSessionInvalid()){
            Log.w(TAG, "terminate-> ImsSessionInvalid!");
            return;
        }
        mDisconnCause = reason;

        mCi.hangupConnection(index,
                mHandler.obtainMessage(ACTION_COMPLETE_HANGUP,this));
        mIsPendingTerminate = false;
    }
    /* @} */
    public int getMediaRequest(){
        int mediaRequest = ImsRadioInterface.MEDIA_REQUEST_DEFAULT;
        if(mImsCallProfile != null){
            if(mImsCallProfile.mCallType == ImsCallProfile.CALL_TYPE_VT){
                mediaRequest = ImsRadioInterface.MEDIA_REQUEST_VIDEO_BIDIRECTIONAL_DOWNGRADE_AUDIO;
            }else if(mImsCallProfile.mCallType == ImsCallProfile.CALL_TYPE_VT_TX){
                mediaRequest = ImsRadioInterface.MEDIA_REQUEST_VIDEO_TX_DOWNGRADE_AUDIO;
            }else if(mImsCallProfile.mCallType == ImsCallProfile.CALL_TYPE_VT_RX){
                mediaRequest = ImsRadioInterface.MEDIA_REQUEST_VIDEO_RX_DOWNGRADE_AUDIO;
            }
        }
        return mediaRequest;
    }

    private boolean isMtConference(ImsDriverCall dc){
        if(mImsDriverCall == null || dc == null){
            return false;
        }
        if(mImsConferenceState != null
                && mImsConferenceState.mParticipants != null
                && mImsConferenceState.mParticipants.containsKey(Integer.toString(dc.index))) {
            return false;
        }
        if(dc.isMpty){
            return true;
        }
        return false;
    }

    /* UNISOC:Local RingBackTone Feature @{ */
    private void registerOnEarlyMediaEvent() {
        mRadioInteractor = new RadioInteractor(mContext);
            mRadioInteractorCallbackListener = new RadioInteractorCallbackListener(
                    getServiceId()-1, Looper.getMainLooper()) {
                @Override
                public void onEarlyMediaEvent(Object data) {
                    if (data != null) {
                        AsyncResult ar;
                        ar = (AsyncResult) data;
                        int earlyMediaState = (int) ar.result;
                        Log.d(TAG, "RadioInteractorCallbackListener onEarlyMediaEvent. earlyMediaState : " + earlyMediaState);
                        // 0: ap play local ringbacktone, 1: cp or network play ringbacktone.
                        if (1 == earlyMediaState) {
                            if (mImsCallProfile != null) {
                                ImsStreamMediaProfile mediaProfile = mImsCallProfile.mMediaProfile;
                                if (mAudioDirection != ImsStreamMediaProfile.DIRECTION_INVALID) {
                                    mediaProfile.mAudioDirection = mAudioDirection;
                                }
                            }
                        } else {
                            if (mImsCallProfile != null) {
                                ImsStreamMediaProfile mediaProfile = mImsCallProfile.mMediaProfile;
                                mAudioDirection = mediaProfile.mAudioDirection;
                                mediaProfile.mAudioDirection = ImsStreamMediaProfile.DIRECTION_INACTIVE;
                            }
                        }
                    }
                }
            };
            if (mRadioInteractor != null) {
                mRadioInteractor.listen(mRadioInteractorCallbackListener,
                        RadioInteractorCallbackListener.LISTEN_EARLY_MEDIA_EVENT,
                        false);
            }
    }

    private void unRegisterEarlyMediaEvent() {
        if (mRadioInteractor != null && mRadioInteractorCallbackListener != null) {
            mRadioInteractor.listen(mRadioInteractorCallbackListener,
                                    RadioInteractorCallbackListener.LISTEN_NONE);
        }
    }
    /* @} */

    /* SPRD:add for bug916545@{ */
    public void updateConferenceDriverCallChange(boolean conferenceDriverCallUpdated){
        mConferenceDriverCallUpdated = conferenceDriverCallUpdated;
    }
    public boolean getConferenceDriverCallUpdated(){
        return mConferenceDriverCallUpdated;
    }
    /* @} */
    public void updateState(int state) {
        mState = state;
    }

    public void createConferenceState() {
        mConferenceHost = true;
        mImsConferenceState = new ImsConferenceState();
    }

    public boolean isNewConfSesson(){
        return mIsNewConfSession;
    }

    public void updateConfHeldState(boolean conferenceHeld) {
        Log.d(TAG,"updateConfHeldState conferenceHeld= "+conferenceHeld);
        mIsConferenceHeld = conferenceHeld;
    }

    public void setAliveVolteCallType() {
        if(mImsServiceCallTracker != null && mImsDriverCall != null
                && mImsDriverCall.state == ImsDriverCall.State.ACTIVE
                && mImsCallProfile != null){
            mImsServiceCallTracker.setAliveVolteCallType(mImsCallProfile.getCallType());
        }
    }

    /* UNISOC: Add for bug Bug 1113109 @{ */
    public void updateImsCallProfileFromCnap(String cnapCallingName, int cnapPresentation){
        mCnapCallingName = cnapCallingName;
        mCnapPresentation = cnapPresentation;

        mImsCallProfile.setCallExtra(ImsCallProfile.EXTRA_CNA, mCnapCallingName);
        mImsCallProfile.setCallExtraInt(ImsCallProfile.EXTRA_CNAP,
                ImsCallProfile.presentationToOIR(mCnapPresentation));

        try{
            if(mIImsCallSessionListener != null){
                mIImsCallSessionListener.callSessionUpdated(mImsCallProfile);
            }
        } catch(RemoteException e){
            e.printStackTrace();
        }
    }
    /* @} */

    /* UNISOC: Add for bug Bug 1176322 @{ */
    public String formatParticipantsNumber(String[] participants) {
        StringBuilder participantsNumber = new StringBuilder();
        for (int i = 0; i < participants.length; i++) {
            participantsNumber.append(PhoneNumberUtils.extractNetworkPortionAlt(participants[i]));
            if (i < (participants.length - 1)) {
                participantsNumber.append(",");
            }
        }
        return participantsNumber.toString();
    }
    /* @} */
}
