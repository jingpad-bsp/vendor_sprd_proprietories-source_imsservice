package com.spreadtrum.ims.vt;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.UserHandle;
import android.telecom.VideoProfile;
import android.view.Surface;
import android.view.WindowManager;
import android.util.Log;
import com.android.internal.telephony.Connection;
import com.spreadtrum.ims.ImsCallSessionImpl;
import com.spreadtrum.ims.ImsRadioInterface;
import com.spreadtrum.ims.ImsService;
import com.spreadtrum.ims.ImsServiceCallTracker;
import android.telephony.ims.ImsCallProfile;
import com.android.internal.telephony.CommandsInterface;
import android.app.AlertDialog;
import android.telecom.VideoProfile.CameraCapabilities;
import android.telecom.Connection.VideoProvider;
import android.os.PowerManager;
import android.os.Message;
import android.widget.Toast;
import com.spreadtrum.ims.R;
import com.spreadtrum.ims.ImsDriverCall;
import android.telephony.ims.ImsCallSession;
import android.os.SystemClock;
import android.app.KeyguardManager;
import android.os.AsyncResult;
import android.telephony.VoLteServiceState;
import android.telephony.TelephonyManager;
import android.telephony.CarrierConfigManager;
import android.telephony.CarrierConfigManagerEx;
import android.telephony.SubscriptionManager;
import com.android.ims.ImsManager;
import com.android.ims.internal.ImsManagerEx;

public class ImsVideoCallProvider extends android.telephony.ims.ImsVideoCallProvider {
    private static final String TAG = ImsVideoCallProvider.class.getSimpleName();
    private VTManagerProxy mVTManagerProxy;
    private Handler mHandler;
    private ImsCallProfile mNegotiatedCallProfile = new ImsCallProfile();
    private VideoProfile mLocalRequestProfile;
    private ImsCallSessionImpl mImsCallSessionImpl;
    private Context mContext;
    private ImsRadioInterface mCi;
    private AlertDialog mVolteMediaUpdateDialog;
    private AlertDialog mVolteMediaDegradeDialog;
    private ImsCallSessionImplListner mImsCallSessionImplListner;
    private boolean mIsVideo;//SPRD:add for bug563112
    public boolean mIsVoiceRingTone = false;//SPRD: add for bug677255
    public boolean mIsOrigionVideo = false;
    private Message mCallIdMessage;
    private VideoProfile mRemoteRequestProfile;

    /** volte media event code. */
    private static final int EVENT_VOLTE_CALL_REMOTE_REQUEST_MEDIA_CHANGED_TIMEOUT = 500;
    private static final long EVENT_MT_CALL_REQUEST_MEDIA_CHANGED_TIMEOUT = 10000;
    private static final int EVENT_SRVCC_STATE_CHANGED = 100;
    private static final int EVENT_VOLTE_CALL_REQUEST_MEDIA_CHANGED_TIMEOUT = 101;//SPRD: add for bug674565
    private static final long EVENT_MO_CALL_REQUEST_MEDIA_CHANGED_TIMEOUT = 30000;//UNISOC:modify for bug1031767
    //SPRD: add for bug 846738, 905754
    private boolean mIsSupportTxRxVideo;

    //media request change
    /*public static final int MEDIA_REQUEST_DEFAULT = 0;
    public static final int MEDIA_REQUEST_AUDIO_UPGRADE_VIDEO_BIDIRECTIONAL = 1;
    public static final int MEDIA_REQUEST_AUDIO_UPGRADE_VIDEO_TX = 2;
    public static final int MEDIA_REQUEST_AUDIO_UPGRADE_VIDEO_RX = 3;
    public static final int MEDIA_REQUEST_VIDEO_TX_UPGRADE_VIDEO_BIDIRECTIONAL = 4;
    public static final int MEDIA_REQUEST_VIDEO_RX_UPGRADE_VIDEO_BIDIRECTIONAL = 5;
    public static final int MEDIA_REQUEST_VIDEO_BIDIRECTIONAL_DOWNGRADE_AUDIO = 6;
    public static final int MEDIA_REQUEST_VIDEO_TX_DOWNGRADE_AUDIO = 7;
    public static final int MEDIA_REQUEST_VIDEO_RX_DOWNGRADE_AUDIO = 8;
    public static final int MEDIA_REQUEST_VIDEO_BIDIRECTIONAL_DOWNGRADE_VIDEO_TX = 9;
    public static final int MEDIA_REQUEST_VIDEO_BIDIRECTIONAL_DOWNGRADE_VIDEO_RX = 10;*/

    private Handler mVTHandler = new Handler(){
        @Override
        public void handleMessage(Message msg) {
            log("handleMessage msg = " + msg.what);
            AsyncResult ar;
            switch (msg.what) {
                case EVENT_VOLTE_CALL_REMOTE_REQUEST_MEDIA_CHANGED_TIMEOUT:
                    if(mImsCallSessionImpl != null && mImsCallSessionImpl.mImsDriverCall != null){
                        int videoCallMediaDirection = mImsCallSessionImpl.mImsDriverCall.getVideoCallMediaDirection();
                        if( videoCallMediaDirection != mImsCallSessionImpl.mImsDriverCall.VIDEO_CALL_MEDIA_DIRECTION_INVALID){
                            mCallIdMessage.arg1 = videoCallMediaDirection;
                        }

                        ImsRadioInterface mCi = (ImsRadioInterface)msg.obj;
                        mCi.responseVolteCallMediaChange(false, Integer.parseInt(mImsCallSessionImpl.getCallId()), mCallIdMessage);
                        //Unisoc: add for bug 610607 931789 1053271
                        showRequestStateToast();
                        showCapabilityChangeToast();
                        onVTConnectionDisconnected(mImsCallSessionImpl);
                        mRemoteRequestProfile = null;
                    }
                    break;
                 /* SPRD:add for bug563112 @{ */
                 case EVENT_SRVCC_STATE_CHANGED:
                     ar = (AsyncResult)msg.obj;
                     if (ar.exception == null) {
                         handleSrvccStateChanged((int[]) ar.result);
                     } else {
                         log("Srvcc exception: " + ar.exception);
                     }
                    break;
                /* @} */
                case EVENT_VOLTE_CALL_REQUEST_MEDIA_CHANGED_TIMEOUT:{
                    log("handle message EVENT_VOLTE_CALL_REQUEST_MEDIA_CHANGED_TIMEOUT");
                    if (mImsCallSessionImpl != null
                            && mImsCallSessionImpl.mImsCallProfile != null
                            && mImsCallSessionImpl.mImsCallProfile.mCallType != ImsCallProfile.CALL_TYPE_VT
                            && mContext != null) {

                        ImsRadioInterface imsCi = (ImsRadioInterface)msg.obj;
                        if(imsCi != null){
                            imsCi.callMediaChangeRequestTimeOut(Integer.parseInt(mImsCallSessionImpl.getCallId()), null);
                            Toast.makeText(mContext.getApplicationContext(), //Unisoc: add for bug967909
                                    mContext.getString(R.string.request_of_upgrade_vt_call_timeout), Toast.LENGTH_SHORT).show();
                        }
                    }
                    mImsCallSessionImpl.getLocalRequestProfile().mCallType = ImsCallProfile.CALL_TYPE_VOICE_N_VIDEO;
                    //Unisoc: add for bug931789 1053271
                    showRequestStateToast();
                    onVTConnectionDisconnected(mImsCallSessionImpl);
                }
                break;
                default:
                    break;
            }
        }
    };

    /* SPRD:add for bug563112 @{ */
    private void handleSrvccStateChanged(int[] ret) {
        log("handleSrvccStateChanged");
        if (ret != null && ret.length != 0) {
            int state = ret[0];
            log("handleSrvccStateChanged..state:"+state+"   mIsVideo="+mIsVideo+"   mContext:"+mContext);
            switch(state) {
                case VoLteServiceState.HANDOVER_COMPLETED:
                    if(mIsVideo && (mContext != null)){
                        //after SRVCC completed, disconnected VT call
                       onVTConnectionDisconnected(mImsCallSessionImpl); //Unisoc: change for bug 610607 1053271
                       //Toast.makeText(mContext,mContext.getResources().getString(R.string.videophone_fallback_title),Toast.LENGTH_LONG).show();
                       showCapabilityChangeToast();
                    }
                    break;
                default:
                    return;
            }
        }
    }
    /* @} */

    public ImsVideoCallProvider(ImsCallSessionImpl imsCallSessionImpl,ImsRadioInterface ci,Context context) {
        super();
        mVTManagerProxy = VTManagerProxy.getInstance();
        mHandler = mVTManagerProxy.mHandler;
        mImsCallSessionImpl = imsCallSessionImpl;
        mContext = context;
        mCi = ci;
        mImsCallSessionImplListner = new ImsCallSessionImplListner();
        mImsCallSessionImpl.addListener(mImsCallSessionImplListner);
        if(isVideoCall(mImsCallSessionImpl.mImsCallProfile.mCallType)){
           //establish VT connection when making VT call.
           onVTConnectionEstablished(mImsCallSessionImpl);
            log("ImsVideoCallProvider mIsOrigionVideo = true");
            mIsOrigionVideo = true;
            mIsVoiceRingTone = false;
        }
        mNegotiatedCallProfile.mCallType = mImsCallSessionImpl.mImsCallProfile.mCallType;
        mNegotiatedCallProfile.mMediaProfile.mAudioQuality =  mImsCallSessionImpl.mImsCallProfile.mMediaProfile.mAudioQuality;
        mNegotiatedCallProfile.mMediaProfile.mAudioDirection =  mImsCallSessionImpl.mImsCallProfile.mMediaProfile.mAudioDirection;
        mNegotiatedCallProfile.mMediaProfile.mVideoQuality =  mImsCallSessionImpl.mImsCallProfile.mMediaProfile.mVideoQuality;
        mNegotiatedCallProfile.mMediaProfile.mVideoDirection =  mImsCallSessionImpl.mImsCallProfile.mMediaProfile.mVideoDirection;
        mCi.registerForSrvccStateChanged(mVTHandler, EVENT_SRVCC_STATE_CHANGED, null);//SPRD:add for bug563112
        mCallIdMessage = new Message();
        /* SPRD: add for bug 905754 @{*/
        mIsSupportTxRxVideo = mImsCallSessionImpl.mIsSupportTxRxVideo;
        /* @} */
    }

    public void onVTConnectionEstablished(ImsCallSessionImpl mImsCallSessionImpl){
        mIsVideo = true;  //Unisoc: add for bug 1053271
        mHandler.obtainMessage(mVTManagerProxy.EVENT_ON_VT_ESTABLISH, mImsCallSessionImpl).sendToTarget();
    }

    public void onVTConnectionDisconnected(ImsCallSessionImpl mImsCallSessionImpl){
        mCi.unregisterForSrvccStateChanged(mVTHandler);
        //Unisoc: change for bug 1053271
        if( mIsVideo && mImsCallSessionImpl.isForegroundCall() || (mLocalRequestProfile != null)) {

            mCi.unregisterForSrvccStateChanged(mVTHandler);
            /* SPRD: fix for bug547597 @{ */
            if (mLocalRequestProfile != null) {
                ImsCallProfile imsCallProfile = mImsCallSessionImpl.getCallProfile();
                int result = android.telecom.Connection.VideoProvider.SESSION_MODIFY_REQUEST_FAIL;
                //Unisoc: change for bug 1132148
                if(this.mImsCallSessionImpl.getLocalRequestProfile() != null && imsCallProfile != null) {
                    if (this.mImsCallSessionImpl.getLocalRequestProfile().mCallType == imsCallProfile.mCallType) {
                        result = android.telecom.Connection.VideoProvider.SESSION_MODIFY_REQUEST_SUCCESS;
                    }
                }
                VideoProfile responseProfile = new VideoProfile(VideoProfile.STATE_AUDIO_ONLY);
                //Unisoc: change for bug 1053271
                receiveSessionModifyResponse(result, mLocalRequestProfile, responseProfile);
            }
            /* @} */
            /* SPRD: fix for bug662570@{ */
            receiveSessionModifyResponse(android.telecom.Connection.VideoProvider.SESSION_MODIFY_REQUEST_INVALID,
                    null, null); //SPRD:add for bug610607
            /* @} */
        }
        mIsVideo = false;
        mHandler.obtainMessage(mVTManagerProxy.EVENT_ON_VT_DISCONNECT, mImsCallSessionImpl).sendToTarget();
    }
    /**
     * Sets the camera to be used for video recording in a video call.
     * @param cameraId The id of the camera.
     */
    @Override
    public void onSetCamera(String cameraId) {
        mHandler.obtainMessage(mVTManagerProxy.EVENT_ON_SET_CAMERA, cameraId).sendToTarget();
    }

    /**
     * Sets the surface to be used for displaying a preview of what the user's camera is
     * currently capturing. When video transmission is enabled, this is the video signal which
     * is sent to the remote device.
     * @param surface The surface.
     */
    @Override
    public void onSetPreviewSurface(Surface surface) {
        mHandler.obtainMessage(mVTManagerProxy.EVENT_ON_SET_PREVIEW_SURFACE, surface).sendToTarget();
    }

    /**
     * Sets the surface to be used for displaying the video received from the remote device.
     * @param surface The surface.
     */
    @Override
    public void onSetDisplaySurface(Surface surface) {
        mHandler.obtainMessage(mVTManagerProxy.EVENT_ON_SET_DISPLAY_SURFACE, surface).sendToTarget();
    }

    /**
     * Sets the device orientation, in degrees. Assumes that a standard portrait orientation of
     * the device is 0 degrees.
     * @param rotation The device orientation, in degrees.
     */
    @Override
    public void onSetDeviceOrientation(int rotation) {
        mHandler.obtainMessage(mVTManagerProxy.EVENT_ON_SET_DEVICE_ORIENTATION, new Integer(rotation))
                .sendToTarget();
    }

    /**
     * Sets camera zoom ratio.
     * @param value The camera zoom ratio.
     */
    @Override
    public void onSetZoom(float value) {

    }

    /**
     * Issues a request to modify the properties of the current session. The request is sent to
     * the remote device where it it handled by the In-Call UI. Some examples of session
     * modification requests: upgrade call from audio to video, downgrade call from video to
     * audio, pause video.
     * @param requestProfile The requested call video properties.
     */
    @Override
    public void onSendSessionModifyRequest(VideoProfile fromProfile, VideoProfile toProfile) {
        if (fromProfile == null || toProfile == null || fromProfile.getVideoState() == toProfile.getVideoState()) {
            log("onSendSessionModifyRequest fromProfile or toProfile is null or no change, return");
            return;
        }
        if (mImsCallSessionImpl == null || mImsCallSessionImpl.mImsCallProfile == null) {
            log("onSendSessionModifyRequest mImsCallSessionImpl or mImsCallProfile is null, return");
            return ;
        }
        log("onSendSessionModifyRequest from "+ fromProfile + " to " + toProfile);
        ImsCallProfile requestImsCallProfile = new ImsCallProfile();
        int mediaRequest = ImsRadioInterface.MEDIA_REQUEST_DEFAULT;
        boolean isUpgrade = false;
        if (fromProfile.isAudioOnly(fromProfile.getVideoState()) && toProfile.isVideo(toProfile.getVideoState())) {
            isUpgrade = true;
            if(toProfile.isBidirectional(toProfile.getVideoState())){
                //audio upgrade to video bidirectional
                requestImsCallProfile.mCallType = ImsCallProfile.CALL_TYPE_VT;
                mediaRequest = ImsRadioInterface.MEDIA_REQUEST_AUDIO_UPGRADE_VIDEO_BIDIRECTIONAL;
            }else if(toProfile.isTransmissionEnabled(toProfile.getVideoState())){
                //audio upgrade to video Tx
                requestImsCallProfile.mCallType = ImsCallProfile.CALL_TYPE_VT_TX;
                mediaRequest = ImsRadioInterface.MEDIA_REQUEST_AUDIO_UPGRADE_VIDEO_TX;
            }else if(toProfile.isReceptionEnabled(toProfile.getVideoState())){
                //audio upgrade to video Rx
                requestImsCallProfile.mCallType = ImsCallProfile.CALL_TYPE_VT_RX;
                mediaRequest = ImsRadioInterface.MEDIA_REQUEST_AUDIO_UPGRADE_VIDEO_RX;
            }
        } else if (fromProfile.isVideo(fromProfile.getVideoState()) && toProfile.isBidirectional(toProfile.getVideoState())) {
            isUpgrade = true;
            requestImsCallProfile.mCallType = ImsCallProfile.CALL_TYPE_VT;
            if (fromProfile.isTransmissionEnabled(fromProfile.getVideoState())) {
                //video Tx upgrade to video bidirectional
                mediaRequest = ImsRadioInterface.MEDIA_REQUEST_VIDEO_TX_UPGRADE_VIDEO_BIDIRECTIONAL;
            } else if (fromProfile.isReceptionEnabled(fromProfile.getVideoState())) {
                //video Rx upgrade to video bidirectional
                mediaRequest = ImsRadioInterface.MEDIA_REQUEST_VIDEO_RX_UPGRADE_VIDEO_BIDIRECTIONAL;
            }
        }else if (fromProfile.isVideo(fromProfile.getVideoState()) && toProfile.isAudioOnly(toProfile.getVideoState())) {
            isUpgrade = false;
            requestImsCallProfile.mCallType = ImsCallProfile.CALL_TYPE_VOICE_N_VIDEO;
            if(fromProfile.isBidirectional(fromProfile.getVideoState())){
                //video bidirectional to audio downgrade
                mediaRequest = ImsRadioInterface.MEDIA_REQUEST_VIDEO_BIDIRECTIONAL_DOWNGRADE_AUDIO;
            } else if (fromProfile.isTransmissionEnabled(fromProfile.getVideoState())) {
                //video Tx to audio downgrade
                mediaRequest = ImsRadioInterface.MEDIA_REQUEST_VIDEO_TX_DOWNGRADE_AUDIO;
            } else if (fromProfile.isReceptionEnabled(fromProfile.getVideoState())) {
                //video Rx to audio downgrade
                mediaRequest = ImsRadioInterface.MEDIA_REQUEST_VIDEO_RX_DOWNGRADE_AUDIO;
            }
        } else if (fromProfile.isBidirectional(fromProfile.getVideoState()) && toProfile.isVideo(toProfile.getVideoState())) {
            isUpgrade = false;
            if (toProfile.isTransmissionEnabled(toProfile.getVideoState())) {
                //video bidirectional downgrade to video Tx
                requestImsCallProfile.mCallType = ImsCallProfile.CALL_TYPE_VT_TX;
                mediaRequest = ImsRadioInterface.MEDIA_REQUEST_VIDEO_BIDIRECTIONAL_DOWNGRADE_VIDEO_TX;
            } else if (toProfile.isReceptionEnabled(toProfile.getVideoState())) {
                //video bidirectional downgrade to video Rx
                requestImsCallProfile.mCallType = ImsCallProfile.CALL_TYPE_VT_RX;
                mediaRequest = ImsRadioInterface.MEDIA_REQUEST_VIDEO_BIDIRECTIONAL_DOWNGRADE_VIDEO_RX;
            }
        }

        log("mediaRequest = "+mediaRequest);
        if (mediaRequest != ImsRadioInterface.MEDIA_REQUEST_DEFAULT) {
            /* SPRD: add for bug 846738 @{ */
            if(!mIsSupportTxRxVideo &&
                    (mediaRequest == ImsRadioInterface.MEDIA_REQUEST_VIDEO_BIDIRECTIONAL_DOWNGRADE_VIDEO_RX ||
                            mediaRequest == ImsRadioInterface.MEDIA_REQUEST_VIDEO_RX_UPGRADE_VIDEO_BIDIRECTIONAL)){

                if (mediaRequest == ImsRadioInterface.MEDIA_REQUEST_VIDEO_RX_UPGRADE_VIDEO_BIDIRECTIONAL) {
                    receiveSessionModifyResponse(android.telecom.Connection.VideoProvider.SESSION_MODIFY_REQUEST_SUCCESS,
                            null, null);
                }
                mImsCallSessionImpl.updateVideoTxRxState(!toProfile.isTransmissionEnabled(toProfile.getVideoState()),
                        !toProfile.isTransmissionEnabled(toProfile.getVideoState()));
                return;
            }
            /* @}*/
            mLocalRequestProfile = toProfile;
            mImsCallSessionImpl.getLocalRequestProfile().mCallType = requestImsCallProfile.mCallType;
            mCi.requestVolteCallMediaChange(mediaRequest, Integer.parseInt(mImsCallSessionImpl.getCallId()),null);
            if (isUpgrade) {
                //establish VT connection when upgrading to VT call.
                onVTConnectionEstablished(mImsCallSessionImpl);//SPRD:add for bug864361
                //SPRD: add for bug674565
                if (mContext != null) {
                    Message msg = new Message();
                    msg.what = EVENT_VOLTE_CALL_REQUEST_MEDIA_CHANGED_TIMEOUT;
                    msg.obj = mCi;
                    mVTHandler.removeMessages(EVENT_VOLTE_CALL_REQUEST_MEDIA_CHANGED_TIMEOUT);
                    mVTHandler.sendMessageDelayed(msg,EVENT_MO_CALL_REQUEST_MEDIA_CHANGED_TIMEOUT);
                }
            }
        }
    }

    /**
     * Provides a response to a request to change the current call session video properties.
     * This is in response to a request the InCall UI has received via the InCall UI.
     * @param responseProfile The response call video properties.
     */
    @Override
    public void onSendSessionModifyResponse(VideoProfile responseProfile) {
        if (mImsCallSessionImpl == null || mImsCallSessionImpl.mImsDriverCall == null) {
            log("mImsCallSessionImpl or mImsDriverCall is null, return");
            return;
        }
        log("onSendSessionModifyResponse->responseProfile:" + responseProfile +" callId:"+mImsCallSessionImpl.getCallId());
        mVTHandler.removeMessages(EVENT_VOLTE_CALL_REMOTE_REQUEST_MEDIA_CHANGED_TIMEOUT);

        int videoCallMediaDirection = mImsCallSessionImpl.mImsDriverCall.getVideoCallMediaDirection();
        if (videoCallMediaDirection != mImsCallSessionImpl.mImsDriverCall.VIDEO_CALL_MEDIA_DIRECTION_INVALID) {
            mCallIdMessage.arg1 = videoCallMediaDirection;
        }

        if (mRemoteRequestProfile != null && responseProfile.getVideoState() == mRemoteRequestProfile.getVideoState()) {
            mCi.responseVolteCallMediaChange(true, Integer.parseInt(mImsCallSessionImpl.getCallId()), mCallIdMessage);
//            receiveSessionModifyResponse(android.telecom.Connection.VideoProvider.SESSION_MODIFY_REQUEST_INVALID,
//                    null,null);
        } else {
            mCi.responseVolteCallMediaChange(false, Integer.parseInt(mImsCallSessionImpl.getCallId()), mCallIdMessage);
            receiveSessionModifyResponse(android.telecom.Connection.VideoProvider.SESSION_MODIFY_REQUEST_INVALID,
                    null,null);//UNISOC:modify for bug905689
        }
        mRemoteRequestProfile = null;
    }

    public void updateVideoQuality(VideoProfile responseProfile) {
        log("updateVideoQuality->responseProfile:" + responseProfile);
        if(responseProfile != null){
            log("updateVideoQuality.updateVideoQuality-> quality:"+ responseProfile.getQuality());
            mHandler.obtainMessage(mVTManagerProxy.EVENT_ON_UPDATE_DEVICE_QUALITY, new Integer(responseProfile.getQuality())).sendToTarget();
        }
    }
    /**
     * Issues a request to the video provider to retrieve the camera capabilities. Camera
     * capabilities are reported back to the caller via the In-Call UI.
     */
    @Override
    public void onRequestCameraCapabilities() {

    }

    /**
     * Issues a request to the video telephony framework to retrieve the cumulative data usage
     * for the current call. Data usage is reported back to the caller via the InCall UI.
     */
    @Override
    public void onRequestCallDataUsage() {

    }


    /**
     * Provides the video telephony framework with the URI of an image to be displayed to remote
     * devices when the video signal is paused.
     * @param uri URI of image to display.
     */
    @Override
    public void onSetPauseImage(Uri uri) {
        mHandler.obtainMessage(mVTManagerProxy.EVENT_ON_SET_PAUSE_IMAGE, uri).sendToTarget();
    }

    private void log(String string) {
        android.util.Log.i(TAG, string);
    }

    class ImsCallSessionImplListner implements ImsCallSessionImpl.Listener{
        @Override
        public void onDisconnected(ImsCallSessionImpl session){
            log("onDisconnected->session="+session);
            //disconnecting VT connection when VT call is end.
            onVTConnectionDisconnected(session);
            mImsCallSessionImpl.removeListener(mImsCallSessionImplListner);
            //Unisoc: change for 1129435
            if (mVTHandler.hasMessages(EVENT_VOLTE_CALL_REQUEST_MEDIA_CHANGED_TIMEOUT)) {
                mVTHandler.removeMessages(EVENT_VOLTE_CALL_REQUEST_MEDIA_CHANGED_TIMEOUT);
            }
        }

        @Override
        public void onUpdate(ImsCallSessionImpl session){
            if(mImsCallSessionImpl != session){
                log("onUpdate->session is not match.");
                return;
            }
            updateNegotiatedCallProfile(session);
            handleClearLocalCallProfile(session);
            handleVolteCallMediaChange(session);
        }
     };

     public void updateNegotiatedCallProfile(ImsCallSessionImpl session){
         ImsCallProfile imsCallProfile = session.getCallProfile();
         log("updateNegotiatedCallProfilee->mCallType="+imsCallProfile.mCallType+" session state = "+session.getState()+ "mIsVoiceRingTone ="+mIsVoiceRingTone +" mIsVideo = "+mIsVideo);

         //SPRD:fix for bug 597075
         if(isVideoCall(imsCallProfile.mCallType)
                 && (session.mImsDriverCall != null && session.mImsDriverCall.state != ImsDriverCall.State.HOLDING)){
             //make or receive a request of VT call,and it is success.
             onVTConnectionEstablished(session);
             //SPRD:add for bug669739 & bug677255
             if (session.getState() == ImsCallSession.State.NEGOTIATING && !mIsOrigionVideo && !session.mImsDriverCall.isMT) {
                 log("updateNegotiatedCallProfilee->set mIsVoiceRingTone = true");
                 mIsVoiceRingTone = true;
             }
             //Unisoc: change for bug 827280 1053271
             if (mLocalRequestProfile != null) {
                 showRequestStateToast();
             }
         } else {
             /* SPRD:add for bug563112 & 677255 & 916545@{ */
             if(!isVideoCall(imsCallProfile.mCallType) && session.mImsDriverCall != null) {
                 //Unisoc: change for bug 900332 957157 967874 1053271
                 if (mIsVideo) {
                    if (session.mImsDriverCall.state != ImsDriverCall.State.HOLDING
                            && (session.mImsDriverCall.isRequestUpgrade() ||
                               (mLocalRequestProfile != null && mLocalRequestProfile.isVideo(mLocalRequestProfile.getVideoState())
                                && !session.mImsDriverCall.isReuestReject()))) {
                         log("updateNegotiatedCallProfile, it is upgrading and return, not degrade to audio");
                         return;
                     }
                     if (!mIsVoiceRingTone && !session.getConferenceDriverCallUpdated()) {
                         //Toast.makeText(mContext, mContext.getResources().getString(R.string.videophone_fallback_title), Toast.LENGTH_LONG).show();
                         showCapabilityChangeToast();
                     }
                     onVTConnectionDisconnected(session);
                 }
                 //Unisoc: change for bug 827280 1053271
                 if (mLocalRequestProfile != null) {
                     showRequestStateToast();
                 }
             }
             /* @} */
         }

         if (session.getState() == ImsCallSession.State.ESTABLISHED && mIsVoiceRingTone) {
             //SPRD: add for bug677255
             log("updateNegotiatedCallProfilee->set mIsVoiceRingTone false");
             mIsVoiceRingTone = false;
         }

         if(session.getConferenceDriverCallUpdated()){
             session.updateConferenceDriverCallChange(false);
         }

         if(mNegotiatedCallProfile.mCallType != imsCallProfile.mCallType){
             mNegotiatedCallProfile.mCallType = imsCallProfile.mCallType;
         }
         if(mNegotiatedCallProfile.mMediaProfile.mAudioQuality !=
                 mImsCallSessionImpl.mImsCallProfile.mMediaProfile.mAudioQuality){
             mNegotiatedCallProfile.mMediaProfile.mAudioQuality =
                     mImsCallSessionImpl.mImsCallProfile.mMediaProfile.mAudioQuality;
         }
         if(mNegotiatedCallProfile.mMediaProfile.mAudioDirection !=
                 mImsCallSessionImpl.mImsCallProfile.mMediaProfile.mAudioDirection){
             mNegotiatedCallProfile.mMediaProfile.mAudioDirection =
                     mImsCallSessionImpl.mImsCallProfile.mMediaProfile.mAudioDirection;
         }
         if(mNegotiatedCallProfile.mMediaProfile.mVideoQuality !=
                 mImsCallSessionImpl.mImsCallProfile.mMediaProfile.mVideoQuality){
             mNegotiatedCallProfile.mMediaProfile.mVideoQuality =
                     mImsCallSessionImpl.mImsCallProfile.mMediaProfile.mVideoQuality;
         }
         if(mNegotiatedCallProfile.mMediaProfile.mVideoDirection !=
                 mImsCallSessionImpl.mImsCallProfile.mMediaProfile.mVideoDirection){
             mNegotiatedCallProfile.mMediaProfile.mVideoDirection =
                     mImsCallSessionImpl.mImsCallProfile.mMediaProfile.mVideoDirection;
         }
     }

    public void handleClearLocalCallProfile(ImsCallSessionImpl session){
          /* SPRD: Modify for Bug538938 @{ */
          if(session.mImsDriverCall != null && (session.mImsDriverCall.isReuestAccept() || session.mImsDriverCall.isReuestReject() || session.mImsDriverCall.isRequestUpgrade() || session.mImsDriverCall.isRequestDowngradeToVoice()) ||
             mImsCallSessionImpl.getLocalRequestProfile().mCallType == mImsCallSessionImpl.mImsCallProfile.mCallType){
             mImsCallSessionImpl.getLocalRequestProfile().mCallType = ImsCallProfile.CALL_TYPE_VOICE_N_VIDEO;
              /*SPRD: add for bug674565*/
              if (mVTHandler != null && mVTHandler.hasMessages(EVENT_VOLTE_CALL_REQUEST_MEDIA_CHANGED_TIMEOUT) && mContext != null) {
                  log("handleClearLocalCallProfile remove EVENT_VOLTE_CALL_REQUEST_MEDIA_CHANGED_TIMEOUT");

                  mVTHandler.removeMessages(EVENT_VOLTE_CALL_REQUEST_MEDIA_CHANGED_TIMEOUT);
              }/* @} */
          }
          /* @} */
    }

    public void handleVolteCallMediaChange(ImsCallSessionImpl session){
        log("handleVolteCallMediaChange->session="+session);
            if(session.mImsDriverCall != null && session.mImsDriverCall.isRequestUpgrade()){
                int videoCallMediaDirection = session.mImsDriverCall.getVideoCallMediaDirection();
                if(videoCallMediaDirection != session.mImsDriverCall.VIDEO_CALL_MEDIA_DIRECTION_INVALID){
                    mCallIdMessage.arg1 = videoCallMediaDirection;
                }

                /*SPRD: add for bug606122, 605475, 676047,896103@{*/
                ImsManager imsManager = ImsManager.getInstance(mContext, (mImsCallSessionImpl.getServiceId()-1));
                if(!(imsManager.isVtEnabledByUser() && imsManager.isVtEnabledByPlatform())
                        || mImsCallSessionImpl.getCurrentUserId() != UserHandle.USER_OWNER
                        || mImsCallSessionImpl.getIsInLocalConference()||mImsCallSessionImpl.mInLocalCallForward
                        || mImsCallSessionImpl.mImsServiceCallTracker.hasRingingCall()){
                    //SPRD:add for bug682362
                    log("handleVolteCallMediaChange reject");
                    mCi.responseVolteCallMediaChange(false, Integer.parseInt(mImsCallSessionImpl.getCallId()), mCallIdMessage);
                    return;
                }/*@}*/
                else if(rejectMediaChange(mImsCallSessionImpl)){
                    log("handleVolteCallMediaChange-is cmcc project, has one active adn one hold call reject MediaChange");
                    mCi.responseVolteCallMediaChange(false, Integer.parseInt(mImsCallSessionImpl.getCallId()), mCallIdMessage);
                    return;
                }
                /* SPRD: add for bug 846738 @{ */
                //UNISOC: add for bug1510544 video customer service
                else if((!mIsSupportTxRxVideo
                        && isVideoCall(mImsCallSessionImpl.mImsCallProfile.mCallType)
                        && videoCallMediaDirection == session.mImsDriverCall.VIDEO_CALL_MEDIA_DIRECTION_SENDRECV)
                        || (!isVideoCall(mImsCallSessionImpl.mImsCallProfile.mCallType)
                                && videoCallMediaDirection == session.mImsDriverCall.VIDEO_CALL_MEDIA_DIRECTION_RECVONLY)){
                    mCi.responseVolteCallMediaChange(true, Integer.parseInt(mImsCallSessionImpl.getCallId()), mCallIdMessage);
                }/* @}*/


                Message msg = new Message();
                msg.what = EVENT_VOLTE_CALL_REMOTE_REQUEST_MEDIA_CHANGED_TIMEOUT;
                msg.obj = mCi;
                mVTHandler.removeMessages(EVENT_VOLTE_CALL_REMOTE_REQUEST_MEDIA_CHANGED_TIMEOUT);
                mVTHandler.sendMessageDelayed(msg, getMeidaChangeTimeout());
                /* SPRD: add for bug543928 and bug601503@{ */
                PowerManager powerManager = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
                if(powerManager != null && !powerManager.isScreenOn()){
                    powerManager.wakeUp(SystemClock.uptimeMillis(), "android.phone:WAKEUP");
                }

                if(mCallIdMessage.arg1 == session.mImsDriverCall.VIDEO_CALL_MEDIA_DIRECTION_SENDRECV){
                    mRemoteRequestProfile = new VideoProfile(VideoProfile.STATE_BIDIRECTIONAL);
                }else if(mCallIdMessage.arg1 == session.mImsDriverCall.VIDEO_CALL_MEDIA_DIRECTION_SENDONLY){
                    mRemoteRequestProfile = new VideoProfile(VideoProfile.STATE_TX_ENABLED);
                }else if(mCallIdMessage.arg1 == session.mImsDriverCall.VIDEO_CALL_MEDIA_DIRECTION_RECVONLY){
                    mRemoteRequestProfile = new VideoProfile(VideoProfile.STATE_RX_ENABLED);
                }else{
                    log("handleVolteCallMediaChange->mCallIdMessage.arg1 = "+mCallIdMessage.arg1);
                    return;
                }
                //make a request of VT call upgrade.
                onVTConnectionEstablished(mImsCallSessionImpl);//SPRD: add fro bug864391
                receiveSessionModifyRequest(mRemoteRequestProfile);

            }
    }

    @Override
    public void changeCameraCapabilities(CameraCapabilities CameraCapabilities) {
         super.changeCameraCapabilities(CameraCapabilities);
    }

    @Override
    public void receiveSessionModifyResponse(
            int status, VideoProfile requestedProfile, VideoProfile responseProfile) {
        super.receiveSessionModifyResponse(status,requestedProfile,responseProfile);
    }

    public void showRequestStateToast(){
        if (mImsCallSessionImpl.mImsDriverCall != null && mImsCallSessionImpl.mImsDriverCall.isReuestAccept()) {
            Toast.makeText(mContext.getApplicationContext(),
                    mContext.getString(R.string.remote_accept_request), Toast.LENGTH_SHORT).show();
            mLocalRequestProfile = null; //Unisoc:fix for bug917060
        } else if(mImsCallSessionImpl.mImsDriverCall != null && mImsCallSessionImpl.mImsDriverCall.isReuestReject()){
            Toast.makeText(mContext.getApplicationContext(),
                    mContext.getString(R.string.remote_reject_request), Toast.LENGTH_SHORT).show();
            mLocalRequestProfile = null; //Unisoc:fix for bug917060
        }
    }

    public boolean isVideoCall(int calltype){
        return calltype == ImsCallProfile.CALL_TYPE_VT
                || calltype == ImsCallProfile.CALL_TYPE_VT_RX
                || calltype == ImsCallProfile.CALL_TYPE_VT_TX;
    }
    /* SPRD: add for bug809098 */
    public void showCapabilityChangeToast(){
         boolean mShowToast = true;
         int primeSubId = SubscriptionManager.getDefaultDataSubscriptionId();
        CarrierConfigManager configManager = (CarrierConfigManager) mContext.getSystemService(
                Context.CARRIER_CONFIG_SERVICE);
         if (configManager.getConfigForSubId(primeSubId) != null) {
             mShowToast = configManager.getConfigForSubId(primeSubId).getBoolean(
                                CarrierConfigManagerEx.KEY_SHOW_IMS_CAPABILITY_CHANGE_TOAST);
             log("ImsVideoCallProvider_mShowCapabilityChangeToast :" + mShowToast);
         }
         if(mShowToast){
             //SPRD:fix for bug 825045
             makeText(mContext,mContext.getResources().getString(R.string.videophone_fallback_title),Toast.LENGTH_LONG).show();
         }
    }

    /**
     * Add method to show toast at LockScreen.
     */
    public Toast makeText(Context context, CharSequence text, int duration) {
        Toast toast = Toast.makeText(context, text, duration);
        toast.getWindowParams().flags = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED;
        return toast;
    }

    /* UNISOC: add for bug958646 */
    public long getMeidaChangeTimeout(){
        if(mImsCallSessionImpl != null){
            int subId = ImsService.getSubIdByServiceId(mImsCallSessionImpl.getServiceId());
            CarrierConfigManager configManager = (CarrierConfigManager) mContext.getSystemService(
                    Context.CARRIER_CONFIG_SERVICE);
             if (configManager.getConfigForSubId(subId) != null) {
                return configManager.getConfigForSubId(subId).getLong(
                               CarrierConfigManagerEx.KEY_MT_REQUEST_MEDIA_CHANGE_TIMER);
            }
        }
        return EVENT_MT_CALL_REQUEST_MEDIA_CHANGED_TIMEOUT;
    }
    /* @}*/

    public boolean rejectMediaChange(ImsCallSessionImpl session) {
        if (session != null && session.mMultiVideoCallDisabled && session.isHasBackgroundCallAndActiveCall()) {
            return true;
        }
        return false;
    }
}
