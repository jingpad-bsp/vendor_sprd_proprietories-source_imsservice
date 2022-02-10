package com.spreadtrum.ims.vt;

import java.lang.ref.WeakReference;
import android.os.Handler;
import android.os.Bundle;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.SystemProperties;
import android.os.Parcel;
import android.util.Log;
import com.android.ims.ImsManager;
import android.hardware.Camera;
import android.view.Surface;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.preference.PreferenceManager;
import android.text.TextUtils;

import com.android.internal.telephony.RIL;
import com.spreadtrum.ims.ImsConfigImpl;
import com.spreadtrum.ims.ImsRadioInterface;

import static com.android.internal.telephony.RILConstants.*;

public class VideoCallEngine {
    private static String TAG = "ImsVideoCallEngine";
    private static VideoCallEngine gInstance = null;
    private EventHandler mEventHandler;
    private Context mContext;
    private ImsRadioInterface mCm;
    private int mCameraResolution = ImsConfigImpl.VT_RESOLUTION_VGA_REVERSED_15;
    private ImsConfigImpl mImsConfigImpl;

    Surface mRemoteSurface;
    Surface mLocalSurface;

    public static final int VCE_EVENT_NONE               = 1000;
    public static final int VCE_EVENT_INIT_COMPLETE      = 1001;
    public static final int VCE_EVENT_START_ENC          = 1002;
    public static final int VCE_EVENT_START_DEC          = 1003;
    public static final int VCE_EVENT_STOP_ENC           = 1004;
    public static final int VCE_EVENT_STOP_DEC           = 1005;
    public static final int VCE_EVENT_SHUTDOWN           = 1006;
    public static final int VCE_EVENT_REMOTE_ROTATE_CHANGE           = 1011;
    public static final int VC_EVENT_LOCAL_RESOLUTION_CHANGED        = 1010; //Unisoc: add for bug1016664
    public static final int VCE_EVENT_STRM_RTP_FIRST_PKT = 1013;
    public static final int VCE_EVENT_STRM_RTP_BREAK     = 1014;
    public static final int VCE_EVENT_STRM_RTP_RECVED    = 1015;
    /* Do not change these values without updating their counterparts
     * in include/media/mediaphone.h
     */
    private static final int MEDIA_PREPARED = 1;
    private static final int MEDIA_SET_VIDEO_SIZE = 2;
    private static final int MEDIA_ERROR = 100;
    private static final int MEDIA_INFO = 200;

    private final static String CAMERA_OPEN_STR = "open_:camera_";
    private final static String CAMERA_CLOSE_STR = "close_:camera_";

    private native final int native_closePipe();
    private native final int native_waitRequestForAT();

    // const define
    static final int MAX_BUFFER_SIZE = 256*1024;
    static final int MAX_FRAME_SIZE = 176*144*3/2;

    static final int VIDEO_TYPE_H263 = 1;
    static final int VIDEO_TYPE_MPEG4 = 2;

    // codec request type
    public static final int CODEC_OPEN = 1;
    public static final int CODEC_CLOSE = 2;
    public static final int CODEC_SET_PARAM = 3;

    private int mCodecCount = 0;
    private int mCurrentCodecType = VIDEO_TYPE_H263;
    private int mStrmRtpBreakTimes = 0;

    public enum CodecState  {
        CODEC_IDLE ,
        CODEC_OPEN,
        CODEC_START,
        CODEC_CLOSE;

        public String toString() {
            switch(this) {
                case CODEC_IDLE:
                    return "CODEC_IDLE";
                case CODEC_OPEN:
                    return "CODEC_OPEN";
                case CODEC_START:
                    return "CODEC_START";
                case CODEC_CLOSE:
                    return "CODEC_CLOSE";
            }
            return "CODEC_IDLE";
        }
    };

    static {
        System.loadLibrary("video_call_engine_jni");
    }

    public VideoCallEngine(ImsRadioInterface ril,Context context, ImsConfigImpl imsConfigImpl) {
        mCm = ril;
        mContext = context;
        mImsConfigImpl = imsConfigImpl;
        initContext();
        gInstance = this;
        Log.i(TAG, "VideoCallEngine create.");
        init();
        postEventFromNative(new WeakReference(this), 0, 0, 0, null);
        setup(new WeakReference(this));
    }

    public static VideoCallEngine getInstance(){
        return gInstance;
    }

    private void initContext() {
        log("initContext() E");
        Looper looper;
        if ((looper = Looper.myLooper()) != null) {
            mEventHandler = new EventHandler(looper);
        } else if ((looper = Looper.getMainLooper()) != null) {
            mEventHandler = new EventHandler(looper);
        } else {
            mEventHandler = null;
        }
        mVideoState = VideoState.AUDIO_ONLY;
        initCameraResolution();
        log("initContext() X");
    }

    public void initCameraResolution(){
        mCameraResolution = mImsConfigImpl.getVideoQualityFromPreference();
        Log.i(TAG, "initCameraResolution():"+mCameraResolution);
    }

    public int getCameraResolution(){
        Log.i(TAG, "getCameraResolution():"+mCameraResolution);
        return mCameraResolution;
    }

    private static void postEventFromNative(Object vce_ref,
            int what, int arg1, int arg2, Object obj) {
        if (vce_ref == null) {
            return;
        }
        what = what + 1000;
        Log.i(TAG, "postEventFromNative what=" + what);

        VideoCallEngine vce = (VideoCallEngine) ((WeakReference) vce_ref).get();
        if (vce.mEventHandler != null) {
            Message msg = vce.mEventHandler.obtainMessage(what, arg1, arg2, obj);
            vce.mEventHandler.sendMessage(msg);
        }
    }

    public void releaseVideocallEngine() {
        log("release() E");
        mVideoState = VideoState.AUDIO_ONLY;
        mStrmRtpBreakTimes = 0;
        this.release();
        log("release() X");
    }

    public void setImsLocalSurface(Surface sf){
        Log.i(TAG, "setImsLocalSurface->sf is null " + (sf == null));
        mLocalSurface = sf;
        this.setLocalSurface(sf);
    }

    public void setImsRemoteSurface(Surface sf){
        log("setImsRemoteSurface() Surface:" + sf);
        mRemoteSurface = sf;
        this.setRemoteSurface(sf);
        log("setRemoteSurface() mVideoState:" + mVideoState);
    }

    public void setImsCamera(Camera cam,int quality){
        this.setCamera(cam, quality);
    }

    public void setImsCamera(Camera cam){
        //SPRD:add fot bug 732419
        if(cam == null) {
            log("setImsRemoteSurface() setCameraId as -1, local surface set to null.");
            this.setCameraId(-1);
        }else
        this.setCamera(cam, mCameraResolution);
    }

    public void setImsPauseImage(Uri uri){
        log("setImsPauseImage() uri:" + uri);
        boolean enable = false;
        String uriString = "";
        if (uri != null && !TextUtils.isEmpty(uri.toString())) {
            enable = true;
            uriString = uri.getPath().toString();
        }
        log("setImsPauseImage() enable:" + enable + " uriString:" + uriString);
        this.hideLocalImage(enable, uriString);
    }
    public static final int MEDIA_CALLEVENT_CAMERACLOSE = 100;
    public static final int MEDIA_CALLEVENT_CAMERAOPEN = 101;
    public static final int MEDIA_CALLEVENT_STRING = 102;
    public static final int MEDIA_CALLEVENT_CODEC_OPEN = 103;
    public static final int MEDIA_CALLEVENT_CODEC_SET_PARAM_DECODER = 104;
    public static final int MEDIA_CALLEVENT_CODEC_SET_PARAM_ENCODER = 105;
    public static final int MEDIA_CALLEVENT_CODEC_START = 106;
    public static final int MEDIA_CALLEVENT_CODEC_CLOSE = 107;
    public static final int MEDIA_CALLEVENT_MEDIA_START = 108;

    /**
     * Interface definition of a callback to be invoked to communicate some
     * info and/or warning about the h324 or call control.
     */
    public interface OnCallEventListener
    {
        boolean onCallEvent(VideoCallEngine videoCallEngine, int what, Object extra);
    }

    /**
     * Register a callback to be invoked when an info/warning is available.
     *
     * @param listener the callback that will be run
     */
    public void setOnCallEventListener(OnCallEventListener listener)
    {
        mOnCallEventListener = listener;
    }

    private OnCallEventListener mOnCallEventListener;


    private class EventHandler extends Handler {

        public EventHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            Log.d(TAG, "handleMessage " + msg);
            switch(msg.what) {
                case VCE_EVENT_NONE:{
                    return;
                }
                case VCE_EVENT_INIT_COMPLETE:{
                    mVideoState = VideoState.AUDIO_ONLY;
                    return;
                }
                case VCE_EVENT_START_ENC:{
                    log("VCE_EVENT_START_ENC, mVideoState" + mVideoState);
                    if(!VideoState.isPaused(mVideoState)){
                        mVideoState = mVideoState | VideoState.TX_ENABLED;
                    }
                    return;
                }
                case VCE_EVENT_START_DEC:{
                    log("VCE_EVENT_START_DEC, mVideoState" + mVideoState);
                    if(!VideoState.isPaused(mVideoState)){
                        mVideoState = mVideoState | VideoState.RX_ENABLED;
                    }
                    return;
                }
                case VCE_EVENT_STOP_ENC:{
                    log("VCE_EVENT_STOP_ENC, mVideoState" + mVideoState);
                    mVideoState = mVideoState & ~VideoState.TX_ENABLED;
                    return;
                }
                case VCE_EVENT_STOP_DEC:{
                    log("VCE_EVENT_STOP_ENC, mVideoState" + mVideoState);
                    mVideoState = mVideoState & ~VideoState.RX_ENABLED;
                    return;
                }
                case VCE_EVENT_SHUTDOWN:{
                    mVideoState = VideoState.AUDIO_ONLY;
                    return;
                }
                case VCE_EVENT_REMOTE_ROTATE_CHANGE:{
                    Log.d(TAG, "handleMessage VCE_EVENT_REMOTE_ROTATE_CHANGE");
                    if(msg.obj != null){
                        Parcel data = (Parcel)msg.obj;
                        int width = data.readInt();
                        int height = data.readInt();
                        if(VTManagerProxy.getInstance() != null){
                            VTManagerProxy.getInstance().setPeerDimensions(width, height);
                        }
                        log("VCE_EVENT_REMOTE_ROTATE_CHANGE, width = " + width + " height = " + height);
                    }
                    return;
                }
                case VC_EVENT_LOCAL_RESOLUTION_CHANGED:  //Unisoc: add for bug1016664
                    if(msg.obj != null) {
                        Parcel data = (Parcel) msg.obj;
                        int width = data.readInt();
                        int height = data.readInt();
                        if (VTManagerProxy.getInstance() != null) {
                            VTManagerProxy.getInstance().setPreviewSize(width, height);
                        }
                        log("VC_EVENT_LOCAL_RESOLUTION_CHANGED, width = " + width + " height = " + height);
                    }
                    return;
                /* UNISOC:add for bug1067245 @{ */
                case VCE_EVENT_STRM_RTP_BREAK:{
                     mStrmRtpBreakTimes++;
                     if(VTManagerProxy.getInstance() != null && mStrmRtpBreakTimes >= 4){
                        VTManagerProxy.getInstance().requestDownToAudio();
                     }
                     log("VCE_EVENT_STRM_RTP_BREAK, mStrmRtpBreakTimes = " + mStrmRtpBreakTimes);
                     return;
                    }
                case VCE_EVENT_STRM_RTP_RECVED:{
                     mStrmRtpBreakTimes = 0;
                     return;
                    }
                /* @} */
                default:
                    Log.e(TAG, "Unknown message type " + msg.what);
                    return;
            }
        }
    }

    private void log(String msg) {
        Log.d(TAG, msg);
    }

    private int mVideoState = VideoState.AUDIO_ONLY;
    /**
    * The video state of the call, stored as a bit-field describing whether video transmission and
    * receipt it enabled, as well as whether the video is currently muted.
    */
    private static class VideoState {
        /**
         * Call is currently in an audio-only mode with no video transmission or receipt.
         */
        public static final int AUDIO_ONLY = 0x0;

        /**
         * Video transmission is enabled.
         */
        public static final int TX_ENABLED = 0x1;

        /**
         * Video reception is enabled.
         */
        public static final int RX_ENABLED = 0x2;

        /**
         * Video signal is bi-directional.
         */
        public static final int BIDIRECTIONAL = TX_ENABLED | RX_ENABLED;

        /**
         * Video is paused.
         */
        public static final int PAUSED = 0x4;

        /**
         * Whether the video state is audio only.
         * @param videoState The video state.
         * @return Returns true if the video state is audio only.
         */
        public static boolean isAudioOnly(int videoState) {
            return !hasState(videoState, TX_ENABLED) && !hasState(videoState, RX_ENABLED);
        }

        /**
         * Whether the video transmission is enabled.
         * @param videoState The video state.
         * @return Returns true if the video transmission is enabled.
         */
        public static boolean isTransmissionEnabled(int videoState) {
            return hasState(videoState, TX_ENABLED);
        }

        /**
         * Whether the video reception is enabled.
         * @param videoState The video state.
         * @return Returns true if the video transmission is enabled.
         */
        public static boolean isReceptionEnabled(int videoState) {
            return hasState(videoState, RX_ENABLED);
        }

        /**
         * Whether the video signal is bi-directional.
         * @param videoState
         * @return Returns true if the video signal is bi-directional.
         */
        public static boolean isBidirectional(int videoState) {
            return hasState(videoState, BIDIRECTIONAL);
        }

        /**
         * Whether the video is paused.
         * @param videoState The video state.
         * @return Returns true if the video is paused.
         */
        public static boolean isPaused(int videoState) {
            return hasState(videoState, PAUSED);
        }

        /**
         * Determines if a specified state is set in a videoState bit-mask.
         *
         * @param videoState The video state bit-mask.
         * @param state The state to check.
         * @return {@code True} if the state is set.
         * {@hide}
         */
        private static boolean hasState(int videoState, int state) {
            return (videoState & state) == state;
        }
    }

    private int mLocalNativeSurfaceTexture; // accessed by native methods
    private int mRemoteNativeSurfaceTexture; // accessed by native methods

    public static native void init();

    public static native void setup(Object weak_this);

    public static native void reset();

    public static native void release();

    public static native void jfinalize();

    public static native void setRemoteSurface(Object surface);

    public static native void setLocalSurface(Object surface);

    public static native void setCamera(Object camera, int resolution);

    public static native void prepare();

    public static native void startUplink();

    public static native void stopUplink();

    public static native void startDownlink();

    public static native void stopDownlink();

    public static native void setUplinkImageFileFD(Object fileDescriptor, long offset, long length);

    public static native void selectRecordSource(int source);

    public static native void selectRecordFileFormat(int format);

    public static native void startRecord();

    public static native void stopRecord();

    public static native void setRecordFileFD(Object fileDescriptor, long offset, long length);

    public static native void setRecordMaxFileSize(long max_filesize_bytes);

    public static native int setCameraId(int id);

    public static native void setCameraPreviewSize(int size);

    public static native void setPreviewDisplayOrientation(int rotation, int screenRotation,int orientationSetting);//SPRD: bug729242, bug846042

    public static native void startPreview();

    public static native void stopPreview();

    public static native void setUplinkQos(int qos);

    public static native void hideLocalImage(boolean enable, String path);
}
