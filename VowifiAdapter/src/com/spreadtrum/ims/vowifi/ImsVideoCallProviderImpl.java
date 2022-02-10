
package com.spreadtrum.ims.vowifi;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.SystemProperties;
import android.preference.PreferenceManager;
import android.telecom.Connection.VideoProvider;
import android.telecom.VideoProfile;
import android.telecom.VideoProfile.CameraCapabilities;
import android.telephony.ims.ImsCallProfile;
import android.telephony.ims.ImsStreamMediaProfile;
import android.telephony.ims.ImsVideoCallProvider;
import android.util.Log;
import android.view.Display;
import android.view.OrientationEventListener;
import android.view.Surface;
import android.view.WindowManager;

import com.spreadtrum.ims.vowifi.Utilities.Camera;
import com.spreadtrum.ims.vowifi.Utilities.Result;
import com.spreadtrum.ims.vowifi.Utilities.VideoQuality;
import com.spreadtrum.ims.vowifi.Utilities.VideoType;

public class ImsVideoCallProviderImpl extends ImsVideoCallProvider {
    private static final String TAG =
            Utilities.getTag(ImsVideoCallProviderImpl.class.getSimpleName());

    private static final String PROP_KEY_SUPPORT_TXRX_UPDATE = "persist.sys.txrx_vt";

    private Context mContext;

    private ImsCallSessionImpl mCallSession;

    private int mAngle = -1;
    private int mScreenRotation = -1;
    private int mDeviceOrientation = -1;
    private int mVideoQualityLevel = -1;
    private boolean mWaitForModifyResponse = false;

    private String mCameraId = null;
    private Display mDisplay = null;
    private MyHandler mHandler = null;
    private Surface mPreviewSurface = null;
    private Surface mDisplaySurface = null;
    private VideoProfile mVideoProfile = null;
    private VideoProfile mFromProfile = null;
    private VideoProfile mToProfile = null;
    private SharedPreferences mPreferences = null;
    private CameraCapabilities mCameraCapabilities = null;
    private MyOrientationListener mOrientationListener = null;

    private static final int MODIFY_REQUEST_TIMEOUT = 15 * 1000; // 15s

    private static final int MSG_ROTATE = 0;
    private static final int MSG_START_CAMERA = 1;
    private static final int MSG_STOP_CAMERA = 2;
    private static final int MSG_SWITCH_CAMERA = 3;
    private static final int MSG_SET_DISPLAY_SURFACE = 4;
    private static final int MSG_SET_PREVIEW_SURFACE = 5;
    private static final int MSG_REQUEST_CAMERA_CAPABILITIES = 6;
    private static final int MSG_STOP_REMOTE_RENDER = 7;
    private static final int MSG_SET_PAUSE_IMAGE = 8;

    private static final int MSG_SEND_MODIFY_REQUEST = 9;
    private static final int MSG_SEND_MODIFY_RESPONSE = 10;
    private static final int MSG_SEND_MODIFY_SUCCESS_RESPONSE = 11;
    private static final int MSG_REJECT_MODIFY_REQUEST = 12;

    private class MyHandler extends Handler {
        private int mRotateRetryTimes = 0;

        public MyHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            if (Utilities.DEBUG) Log.i(TAG, "Handle the message: " + msg.what);

            synchronized (mContext) {
                switch (msg.what) {
                    case MSG_ROTATE: {
                        if (mCameraId != null) {
                            Log.d(TAG, "Handle the rotate message, the device orientation: "
                                    + mDeviceOrientation + ", the angle: " + mAngle);
                            mCallSession.localRenderRotate(mCameraId, mAngle, mDeviceOrientation);
                            mCallSession.remoteRenderRotate(mAngle);
                            mRotateRetryTimes = 0;
                        } else if (mRotateRetryTimes < 5){
                            mHandler.sendEmptyMessageDelayed(MSG_ROTATE, 500);
                            mRotateRetryTimes = mRotateRetryTimes + 1;
                        }
                        break;
                    }
                    case MSG_START_CAMERA: {
                        String cameraId = (String) msg.obj;
                        if (mCallSession.startCamera(cameraId) == Result.SUCCESS) {
                            mCameraId = cameraId;
                            // As set camera success, we'd like to request the camera capabilities.
                            mHandler.sendEmptyMessage(MSG_REQUEST_CAMERA_CAPABILITIES);
                        }

                        // Enable the rotate now.
                        mOrientationListener.enable();
                        break;
                    }
                    case MSG_STOP_CAMERA: {
                        if (mCameraId == null) {
                            Log.d(TAG, "Camera already stopped, do nothing.");
                            break;
                        }

                        int res = Result.SUCCESS;
                        res = res & mCallSession.stopLocalRender(mPreviewSurface, mCameraId);
                        res = res & mCallSession.stopCamera();
                        res = res & mCallSession.stopCapture(mCameraId);

                        if (res == Result.FAIL) {
                            // Sometimes, we will stop the camera failed as the camera already
                            // disconnect. For example, refer to this log:
                            // "Disconnect called on already disconnected client for device 1"
                            Log.w(TAG, "The camera can not stopped now, please check the reason.");
                        }

                        // Reset the values.
                        mCameraId = null;
                        mCameraCapabilities = null;
                        mPreviewSurface = null;

                        // Disable the rotate now.
                        mOrientationListener.disable();
                        break;
                    }
                    case MSG_SWITCH_CAMERA: {
                        // For switch the camera, we'd like to split this action to two step:
                        // 1. stop the old camera
                        // 2. start the new camera
                        mHandler.sendEmptyMessage(MSG_STOP_CAMERA);
                        mHandler.sendMessage(mHandler.obtainMessage(MSG_START_CAMERA, msg.obj));
                        break;
                    }
                    case MSG_SET_DISPLAY_SURFACE: {
                        Surface displaySurface = (Surface) msg.obj;
                        if (displaySurface == null) break;

                        if (mCallSession.startRemoteRender(displaySurface) == Result.SUCCESS) {
                            mDisplaySurface = displaySurface;
                        }
                        break;
                    }
                    case MSG_SET_PREVIEW_SURFACE: {
                        Surface previewSurface = (Surface) msg.obj;
                        if (previewSurface == null || mCameraId == null) break;

                        int res = Result.SUCCESS;
                        // Start the capture and start the render.
                        VideoQuality quality = Utilities.findVideoQuality(getVideoQualityLevel());
                        if (quality != null) {
                            res = res & mCallSession.startCapture(
                                    mCameraId, quality._width, quality._height, quality._frameRate);
                        }
                        res = res & mCallSession.startLocalRender(previewSurface, mCameraId);

                        if (res == Result.SUCCESS) {
                            mPreviewSurface = previewSurface;
                        } else {
                            Log.w(TAG, "Can not set the preview surface now.");
                        }
                        break;
                    }
                    case MSG_REQUEST_CAMERA_CAPABILITIES: {
                        if (mCameraId == null) {
                            Log.d(TAG, "The camera is null, needn't request camera capability.");
                            break;
                        }

                        CameraCapabilities cameraCapabilities = getCameraCapabilities();
                        // If the device rotate to 90 or 270, we need exchange the height and width.
                        if (cameraCapabilities != null && isLandscape()) {
                            Log.d(TAG, "The current orientation is 90 or 270, adjest capability.");
                            cameraCapabilities = new CameraCapabilities(
                                    cameraCapabilities.getHeight(), cameraCapabilities.getWidth());
                        }

                        if (mPreviewSurface == null
                                || !cameraCapabilitiesEquals(cameraCapabilities)) {
                            if (cameraCapabilities != null) {
                                Log.d(TAG, "Change the camera capability: width = "
                                        + cameraCapabilities.getWidth() + ", height = "
                                        + cameraCapabilities.getHeight());
                                changeCameraCapabilities(cameraCapabilities);
                            }
                            mCameraCapabilities = cameraCapabilities;
                        } else {
                            Log.d(TAG, "The old camera capabilities is same as the new one.");
                        }
                        break;
                    }
                    case MSG_STOP_REMOTE_RENDER: {
                        if (mDisplaySurface == null) {
                            Log.w(TAG, "Failed to stop remote render, display surface is null.");
                            break;
                        }

                        int res = mCallSession.stopRemoteRender(mDisplaySurface, false);
                        if (res == Result.SUCCESS) {
                            // Stop the remote render success, set the display surface to null.
                            mDisplaySurface = null;
                        } else {
                            Log.w(TAG, "Can not stop remote render now.");
                        }
                        break;
                    }
                    case MSG_SET_PAUSE_IMAGE: {
                        mCallSession.setPauseImage((Uri) msg.obj);
                        break;
                    }
                }
            }

            switch (msg.what) {
                case MSG_SEND_MODIFY_REQUEST: {
                    if (mCallSession.sendModifyRequest(msg.arg1) == Result.FAIL) {
                        Log.w(TAG, "Can not send the modify request now.");
                        receiveSessionModifyResponse(
                                VideoProvider.SESSION_MODIFY_REQUEST_FAIL, null, null);
                    } else {
                        // Send the modify request successfully.
                        mWaitForModifyResponse = true;
                    }
                    break;
                }
                case MSG_SEND_MODIFY_RESPONSE: {
                    int res = mCallSession.sendModifyResponse(msg.arg1);
                    if (res == Result.FAIL) {
                        Log.w(TAG, "Can not send the modify response now.");
                        receiveSessionModifyResponse(
                                VideoProvider.SESSION_MODIFY_REQUEST_FAIL, null, null);
                    }
                    break;
                }
                case MSG_SEND_MODIFY_SUCCESS_RESPONSE: {
                    VideoProfile profile = (VideoProfile) msg.obj;
                    receiveSessionModifyResponse(
                            VideoProvider.SESSION_MODIFY_REQUEST_SUCCESS, profile, profile);

                    // If the video type do not changed. we need handle the transmission changed.
                    boolean isTrans =
                            VideoProfile.isTransmissionEnabled(profile.getVideoState());
                    if (isTrans) {
                        mCallSession.updateCallType(ImsCallProfile.CALL_TYPE_VT);
                    } else {
                        mCallSession.updateCallType(ImsCallProfile.CALL_TYPE_VT_RX);
                    }
                    break;
                }
                case MSG_REJECT_MODIFY_REQUEST: {
                    // Handle the request as timeout.
                    receiveSessionModifyResponse(
                            VideoProvider.SESSION_MODIFY_REQUEST_TIMED_OUT, null, null);

                    // Send the modify response as reject to keep as voice call.
                    mCallSession.sendModifyResponse(VideoType.NATIVE_VIDEO_TYPE_NONE);
                    break;
                }
            }
        }
    }

    protected ImsVideoCallProviderImpl(Context context, VoWifiCallManager callManager,
            ImsCallSessionImpl callSession) {
        mContext = context;
        mCallSession = callSession;
        mOrientationListener = new MyOrientationListener(mContext);
        mPreferences = PreferenceManager.getDefaultSharedPreferences(mContext);

        WindowManager wm = ((WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE));
        mDisplay = wm.getDefaultDisplay();

        HandlerThread thread = new HandlerThread("VideoCall");
        thread.start();
        Looper looper = thread.getLooper();
        mHandler = new MyHandler(looper);
    }

    @Override
    protected void finalize() throws Throwable {
        super.finalize();

        if (mHandler != null) {
            mHandler.removeCallbacksAndMessages(null);
            Looper looper = mHandler.getLooper();
            if (looper != null) {
                looper.quit();
            }
        }
    }

    @Override
    public void receiveSessionModifyRequest(VideoProfile VideoProfile) {
        super.receiveSessionModifyRequest(VideoProfile);

        // When we received the modify request, we suppose the user will handle this
        // request in 15s. If the user do not give the response, we'd like to handle
        // this request as reject.
        mHandler.sendEmptyMessageDelayed(MSG_REJECT_MODIFY_REQUEST, MODIFY_REQUEST_TIMEOUT);
    }

    @Override
    public void receiveSessionModifyResponse(int status, VideoProfile requestProfile,
            VideoProfile responseProfile) {
        if (status == VideoProvider.SESSION_MODIFY_REQUEST_FAIL) {
            // It means update failed, update the request profile and response profile.
            requestProfile = mToProfile;
            responseProfile = mFromProfile;
        }
        super.receiveSessionModifyResponse(status, requestProfile, responseProfile);

        mWaitForModifyResponse = false;
        mVideoProfile = responseProfile;
    }

    /**
     * Issues a request to retrieve the data usage (in bytes) of the video portion of the
     * {@link RemoteConnection} for the {@link RemoteConnection.VideoProvider}.
     *
     * @see Connection.VideoProvider#onRequestConnectionDataUsage()
     */
    @Override
    public void onRequestCallDataUsage() {
        Log.d(TAG, "On request call data usage. Do not handle now.");
    }

    @Override
    public void onRequestCameraCapabilities() {
        if (Utilities.DEBUG) Log.i(TAG, "On request camera capabilities.");
        mHandler.sendEmptyMessage(MSG_REQUEST_CAMERA_CAPABILITIES);
    }

    @Override
    public void onSendSessionModifyRequest(VideoProfile fromProfile, VideoProfile toProfile) {
        if (Utilities.DEBUG) {
            Log.i(TAG, "On send session modify request. From profile: " + fromProfile
                    + ", to profile: " + toProfile);
        }

        if (isWaitForModifyResponse()) {
            // It means we already send the modify request, but do not receive the response now.
            // So we'd like to ignore this request.
            Log.d(TAG, "As there is a modify request in porcess, ignore this new request.");
            return;
        }

        int oldState = fromProfile.getVideoState();
        int newState = toProfile.getVideoState();
        boolean wasPaused = VideoProfile.isPaused(oldState);
        boolean isPaused = VideoProfile.isPaused(newState);
        Log.d(TAG, "oldState:" + oldState + ", newState:" + newState + ", wasPaused:" + wasPaused
                + ", isPaused:" + isPaused);
        if (oldState != newState && !wasPaused && !isPaused) {
            if (!isSupportTXRXUpdate()
                    && oldState > VideoProfile.STATE_AUDIO_ONLY
                    && newState > VideoProfile.STATE_AUDIO_ONLY) {
                Log.d(TAG, "As do not support TXRX update, handle as pause state change action.");
                // Need handle the transmission changed as pause state change.
                boolean wasTrans = VideoProfile.isTransmissionEnabled(oldState);
                boolean isTrans = VideoProfile.isTransmissionEnabled(newState);
                if (wasTrans == isTrans) {
                    // FIXME: There must be some error in telephony, when the user press
                    //        "resume video", the wasTrans is same as isTrans.
                    Log.w(TAG, "The fromProfile is same as the toProfile's trans. It's abnormal.");
                    Log.w(TAG, "Ignore this abnormal, change the status as toProfile.");
                }

                // For pause state changed, needn't send the modify request. So give the
                // response immediately.
                if (isTrans) {
                    // It means start the video transmission. And "setCamera" will start the
                    // camera, so we need request the camera capabilities
                    Log.d(TAG, "Start the video transmission successfully.");
                    mHandler.sendMessage(
                            mHandler.obtainMessage(MSG_SEND_MODIFY_SUCCESS_RESPONSE, toProfile));
                } else {
                    // It means stop the video transmission. And this action will be handled
                    // when the camera set to null.
                    Log.d(TAG, "Stop the video transmission successfully.");
                    mHandler.sendMessage(
                            mHandler.obtainMessage(MSG_SEND_MODIFY_SUCCESS_RESPONSE, toProfile));
                }
            } else {
                // For video type update, we need send the modify request to server.
                mFromProfile = fromProfile;
                mToProfile = toProfile;

                int nativeVideoType = VideoType.getNativeVideoType(toProfile);
                Message msg = mHandler.obtainMessage(MSG_SEND_MODIFY_REQUEST);
                msg.arg1 = nativeVideoType;
                mHandler.sendMessage(msg);
            }
        } else if (wasPaused != isPaused) {
            // We'd like to handle it as pause/start the video.
            Log.d(TAG, "The new video paused state changed to " + isPaused + ", DO NOT HANDLE!");
        } else {
            Log.e(TAG, "There isn't any update for the video profile. Please check!!!");
        }
    }

    @Override
    public void onSendSessionModifyResponse(VideoProfile responseProfile) {
        if (Utilities.DEBUG) {
            Log.i(TAG, "On send session modify response. response profile: " + responseProfile);
        }

        int nativeVideoType = VideoType.getNativeVideoType(responseProfile);
        mHandler.removeMessages(MSG_REJECT_MODIFY_REQUEST);
        mHandler.sendMessage(mHandler.obtainMessage(MSG_SEND_MODIFY_RESPONSE, nativeVideoType, -1));

        switch (nativeVideoType) {
            case VideoType.NATIVE_VIDEO_TYPE_NONE:
                stopAll();
                break;
            case VideoType.NATIVE_VIDEO_TYPE_RECEIVED_ONLY:
                stopTransmission();
                break;
            case VideoType.NATIVE_VIDEO_TYPE_BROADCAST_ONLY:
                stopReception();
                break;
            case VideoType.NATIVE_VIDEO_TYPE_BIDIRECT:
                Log.d(TAG, "Response as normal video call, do nothing.");
                break;
        }
    }

    @Override
    public void onSetCamera(String cameraId) {
        synchronized (mContext) {
            if (Utilities.DEBUG) {
                Log.i(TAG, "On set the camera from " + Camera.toString(mCameraId) + " to "
                        + Camera.toString(cameraId));
            }

            if (mCameraId != null && cameraId == null) {
                // Set the camera to null, it means stop the camera capture.
                mHandler.sendEmptyMessage(MSG_STOP_CAMERA);
            } else if (cameraId != null && !cameraId.equals(mCameraId)) {
                // Start the camera or switch the camera.
                if (mCameraId == null) {
                    // Start the camera.
                    mHandler.sendMessage(mHandler.obtainMessage(MSG_START_CAMERA, cameraId));
                } else {
                    mHandler.sendMessage(mHandler.obtainMessage(MSG_SWITCH_CAMERA, cameraId));
                }
            } else {
                // case: mCameraId == null && cameraId == null or cameraId equals mCameraId
                Log.d(TAG, "Set the camera to " + Camera.toString(cameraId)
                        + ", but the old camera is " + Camera.toString(mCameraId));
                if (cameraId == null) {
                    // If the new camera is null, and the old camera is null, it means the start
                    // camera action do not handle now. So we'd like to remove the start camera
                    // action to keep the last camera state as null.
                    mHandler.removeMessages(MSG_START_CAMERA);
                } else {
                    // If the new camera is front or back, and it is same as the old, it means the
                    // stop camera action do not handle now. So we'd like to remove the stop camera
                    // action to keep the last camera state.
                    mHandler.removeMessages(MSG_STOP_CAMERA);
                }
            }
        }
    }

    @Override
    public void onSetDeviceOrientation(int deviceOrientation) {
        // If the device orientation do not init, calculate the angle with this
        // given device orientation.
        if (mDeviceOrientation < 0) {
            calculateAngle(deviceOrientation);
        }
    }

    @Override
    public void onSetDisplaySurface(Surface surface) {
        synchronized (mContext) {
            if (Utilities.DEBUG) Log.i(TAG, "On set the display surface to: " + surface);
            if (surface == null) {
                Log.d(TAG, "Set the display surface to null, ignore this request.");
                return;
            }

            if (mDisplaySurface != null) {
                mHandler.sendEmptyMessage(MSG_STOP_REMOTE_RENDER);
            }
            mHandler.sendMessage(mHandler.obtainMessage(MSG_SET_DISPLAY_SURFACE, surface));
        }
    }

    @Override
    public void onSetPauseImage(Uri uri) {
        if (Utilities.DEBUG) Log.i(TAG, "On set the pause image to: " + uri);
        mHandler.sendMessage(mHandler.obtainMessage(MSG_SET_PAUSE_IMAGE, uri));
    }

    @Override
    public void onSetPreviewSurface(Surface surface) {
        synchronized (mContext) {
            if (Utilities.DEBUG) Log.i(TAG, "On set the preview surface as: " + surface);
            if (surface == null) {
                Log.d(TAG, "Set the preview surface to null, ignore this request.");
                return;
            }

            mHandler.sendMessage(mHandler.obtainMessage(MSG_SET_PREVIEW_SURFACE, surface));
        }
    }

    @Override
    public void onSetZoom(float arg0) {
        if (Utilities.DEBUG) Log.i(TAG, "On set the zoom to: " + arg0);
        // Do not support now.
    }

    public String getCameraId() {
        synchronized (mContext) {
            return mCameraId;
        }
    }

    public Surface getDisplaySurface() {
        synchronized (mContext) {
            return mDisplaySurface;
        }
    }

    public Surface getPreviewSurface() {
        synchronized (mContext) {
            return mPreviewSurface;
        }
    }

    public VideoProfile getVideoProfile() {
        return mVideoProfile;
    }

    public int getVideoState() {
        return mVideoProfile == null ? -1 : mVideoProfile.getVideoState();
    }

    public void updateVideoProfile(VideoProfile profile) {
        mVideoProfile = profile;
    }

    public CameraCapabilities getCurCameraCapabilities() {
        return mCameraCapabilities;
    }

    public boolean isWaitForModifyResponse() {
        return mWaitForModifyResponse;
    }

    public void stopReception() {
        synchronized (mContext) {
            Log.d(TAG, "Stop the reception.");
            if (mDisplaySurface != null) {
                mHandler.sendEmptyMessage(MSG_STOP_REMOTE_RENDER);
            }
        }
    }

    public void stopTransmission() {
        synchronized (mContext) {
            Log.d(TAG, "Stop the transmission.");
            if (mCameraId != null) {
                mHandler.sendEmptyMessage(MSG_STOP_CAMERA);
            }
        }
    }

    public void stopAll() {
        Log.d(TAG, "Stop all the video action.");
        stopReception();
        stopTransmission();
    }

    public void updateVideoQualityLevel(int newLevel) {
        synchronized (mContext) {
            Log.d(TAG, "Update the video quality level from " + mVideoQualityLevel
                    + " to " + newLevel);
            if (newLevel > 0 && newLevel != mVideoQualityLevel) {
                mVideoQualityLevel = newLevel;
                // As video quality level changed, we need stop camera & start camera again.
                String oldCameraId = mCameraId;
                if (mCameraId != null) {
                    mHandler.sendEmptyMessage(MSG_STOP_CAMERA);
                }

                // Start the camera with old camera.
                if (oldCameraId != null) {
                    mHandler.sendMessage(mHandler.obtainMessage(MSG_START_CAMERA, oldCameraId));
                }
            }
        }
    }

    public int getCurImsVideoQuality() {
        switch (mVideoQualityLevel) {
            case 31:
            case 22:
            case 30:
                if (isLandscape()) {
                    return ImsStreamMediaProfile.VIDEO_QUALITY_VGA_LANDSCAPE;
                } else {
                    return ImsStreamMediaProfile.VIDEO_QUALITY_VGA_PORTRAIT;
                }
            case 12:
            case 13:
            case 14:
                if (isLandscape()) {
                    return ImsStreamMediaProfile.VIDEO_QUALITY_QVGA_LANDSCAPE;
                } else {
                    return ImsStreamMediaProfile.VIDEO_QUALITY_QVGA_PORTRAIT;
                }
            case 11:
                return ImsStreamMediaProfile.VIDEO_QUALITY_QCIF;
        }

        return ImsStreamMediaProfile.VIDEO_QUALITY_NONE;
    }

    private boolean isLandscape() {
        return mDeviceOrientation == 90 || mDeviceOrientation == 270;
    }

    private boolean isSupportTXRXUpdate() {
        return SystemProperties.getBoolean(PROP_KEY_SUPPORT_TXRX_UPDATE, false);
    }

    private boolean cameraCapabilitiesEquals(CameraCapabilities capabilities) {
        if ((mCameraCapabilities == null && capabilities == null)
                || (mCameraCapabilities == capabilities)) {
            return true;
        } else if (mCameraCapabilities == null && capabilities != null) {
            return false;
        } else if (mCameraCapabilities != null && capabilities == null) {
            return false;
        } else {
            return mCameraCapabilities.getWidth() == capabilities.getWidth()
                    && mCameraCapabilities.getHeight() == capabilities.getHeight()
                    && mCameraCapabilities.getMaxZoom() == capabilities.getMaxZoom()
                    && mCameraCapabilities.isZoomSupported() == capabilities.isZoomSupported();
        }
    }

    private float getVideoQualityLevel() {
        if (mVideoQualityLevel < 0) {
            mVideoQualityLevel = mCallSession.getDefaultVideoLevel();
            if (mVideoQualityLevel < 0) {
                Log.w(TAG, "Can not get the default video level, set it as default.");
                mVideoQualityLevel = Utilities.getDefaultVideoQuality(mPreferences)._level;
            }
        }

        return mVideoQualityLevel;
    }

    private CameraCapabilities getCameraCapabilities() {
        float videoLevel = getVideoQualityLevel();
        VideoQuality quality = Utilities.findVideoQuality(videoLevel);
        if (quality == null) {
            quality = Utilities.getDefaultVideoQuality(mPreferences);
        }

        return new CameraCapabilities(quality._width, quality._height);
    }

    private void calculateAngle(int deviceOrientation) {
        int screenRotation = mDisplay.getRotation();
        if (deviceOrientation == mDeviceOrientation
                && screenRotation == mScreenRotation) {
            // The old device orientation is same as the new. Do nothing.
            return;
        }

        // Update the device orientation and angle.
        if (Utilities.DEBUG) {
            Log.i(TAG, "Orientation changed, deviceOrientation = " + deviceOrientation
                    + ", screenRotation = " + screenRotation);
        }

        mDeviceOrientation = deviceOrientation;
        mScreenRotation = screenRotation;
        int angle = ((360 - mDeviceOrientation) + (360 - mScreenRotation * 90)) % 360;
        Log.d(TAG, "Get the new angle: " + angle + ", and the old angle is: " + mAngle);

        if (mAngle != angle) {
            mAngle = angle;
            mHandler.sendEmptyMessage(MSG_ROTATE);
            Log.d(TAG, "Send the rotate message for new angle: " + mAngle);
        }
    }

    private class MyOrientationListener extends OrientationEventListener {
        public MyOrientationListener(Context context) {
            super(context);
        }

        @Override
        public void disable() {
            super.disable();

            // If this listener is disable, reset the device orientation, screen rotation and angle.
            mAngle = -1;
            mScreenRotation = -1;
            mDeviceOrientation = -1;
        }

        @Override
        public void onOrientationChanged(int orientation) {
            // FIXME: Sometimes, the orientation is -1. Do nothing.
            if (orientation < 0) {
                return;
            }

            // This will be same as the VT manager.
            int deviceOrientation = mDeviceOrientation;
            if (orientation >= 350 || orientation <= 10) {
                deviceOrientation = 0;
            } else if (orientation >= 80 && orientation <= 110) {
                deviceOrientation = 90;
            } else if (orientation >= 170 && orientation <= 190) {
                deviceOrientation = 180;
            } else if (orientation >= 260 && orientation <= 280) {
                deviceOrientation = 270;
            }

            calculateAngle(deviceOrientation);
        }
    }

}
