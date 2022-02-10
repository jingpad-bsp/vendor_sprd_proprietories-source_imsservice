
package com.spreadtrum.ims.vt;

import android.content.Context;
import android.hardware.Camera;
import android.hardware.Camera.Parameters;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.view.OrientationEventListener;
import android.view.Surface;
import android.view.WindowManager;
import com.spreadtrum.ims.ImsConfigImpl;
import android.widget.Toast;
import com.spreadtrum.ims.R;
import android.content.ContentResolver;
import android.database.ContentObserver;
import android.provider.Settings;

public class VideoCallCameraManager {
    private static String TAG = VideoCallCameraManager.class.getSimpleName();

    private static final int BRIGHTNESS_CONTRAST_ERROR = -1;
    private static final int START_CAMERA_TIMES = 2;
    private static final int EVENT_DELAYED_CREATE_CAMERA = 999;
    private static final int EVENT_CHANGE_ORIENTATION = 1000;
    private static final short OPERATION_SUCCESS = 0;
    private static final int EVENT_CAMERA_FAIL = 100;// SPRD:Add for bug571839
    private static final int EVENT_HANDLE_SET_CAMERA = 10001;// Unisoc: add for 1083735

    // ON: settings put system accelerometer_rotation 1
    // OFF: settings put system accelerometer_rotation 0
    private static final int ORIENTATION_SETTING_ON = 1; // SPRD: bug846042
    private static final int ORIENTATION_SETTING_OFF = 0;

    public static final String CAMERA_PARAMETERS_BRIGHTNESS = "brightness";
    public static final String CAMERA_PARAMETERS_CONTRAST = "contrast";

    public enum WorkerTaskType {
        NONE, CAMERA_SWITCH, CAMERA_CLOSE, CAMERA_OPEN, VIEW_SWTICH
    };

    /**
     * The camera ID for the front facing camera.
     */
    private String mFrontFacingCameraId;

    /**
     * The camera ID for the rear facing camera.
     */
    private String mRearFacingCameraId;

    private Parameters mParameters;
    private Object mCameraLock = new Object();
    private Object mThreadLock = new Object();
    private VideoCallEngine mVideoCallEngine;
    private VTManagerProxy mVTManagerProxy;
    private Thread mOperateCameraThread;
    private MyOrientationEventListener mOrientationListener;
    private RotationObserver mOrientationSettingListener;

    private String mCameraId;
    private int mCameraTimes = START_CAMERA_TIMES;
    private int mCameraNumbers = 0;
    private boolean mThreadRunning;
    public int mWidth = 176;
    public int mHeight = 144;
    private int mDeviceRotation = 0;
    private boolean mIsFirstInit = true;
    private boolean mIsSurfacePreviewFailed = false;
    private int mVideoQuality;
    private boolean mIsVideoQualityReceived = false;
    private boolean mIsOpened;
    private boolean mIsPreviewing;
    private boolean mIsRecording;
    private Context mContext;// SPRD:Add for bug571839
    /* SPRD: bug729242 @{ */
    private WindowManager mWinMana;
    private int mScreenRotation = 0;
    /* @} */
    private int mOrientationSetting = ORIENTATION_SETTING_ON; // SPRD: bug846042

    private Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            Log.i(TAG, "The message: " + msg.what);
            switch (msg.what) {
                case EVENT_DELAYED_CREATE_CAMERA:
                    initCameraAndStartPreview();
                    break;
                case EVENT_CHANGE_ORIENTATION:
                    handleOrientationChange();
                    break;
                /* SPRD: Modify for bug571839 @ { */
                case EVENT_CAMERA_FAIL:
                    Toast.makeText(mContext, R.string.camera_open_fail, Toast.LENGTH_SHORT).show();
                    break;
                /* @ } */

                case EVENT_HANDLE_SET_CAMERA: // Unisoc: add for 1083735
                    String cameraId = (String) msg.obj;
                    excuteSetCamera(cameraId);
                    break;
                default:
                    Log.w(TAG, "unsupport message: " + msg.what);
                    break;
            }
        }
    };

    public VideoCallCameraManager(VideoCallEngine videoCallEngine, Context context,
            VTManagerProxy vtManagerProxy) {
        mVideoCallEngine = videoCallEngine;
        mVTManagerProxy = vtManagerProxy;
        mVideoQuality = mVideoCallEngine.getCameraResolution();
        // SPRD: add for bug846042
        if (context != null) {
            mContext = context;// SPRD:Add for bug571839
            initializeCameraList(context);
            mOrientationListener = new MyOrientationEventListener(context);
            mOrientationListener.enable();
            mWinMana = ((WindowManager) context.getSystemService(Context.WINDOW_SERVICE));// SPRD:
                                                                                          // bug729242
            mOrientationSetting = Settings.System.getInt(context.getContentResolver(),
                    Settings.System.ACCELEROMETER_ROTATION, 0);
            mOrientationSettingListener = new RotationObserver(mHandler);
            mOrientationSettingListener.startObserver();
        }
    }

    /**
     * Gets the used camera ID.
     */
    private String getCamerID() {
        Log.d(TAG, "getCamerID(): " + mCameraId);
        return mCameraId;
    }

    public void setCameraID(String id) {
        mCameraId = id;
    }

    public boolean isSameCamera(String id) {
        if (mCameraId != null && mCameraId.equals(id)) {
            return true;
        } else {
            return false;
        }
    }

    /**
     * For example, suppose the natural orientation of the device is portrait. The device is rotated
     * 270 degrees clockwise, so the device orientation is 270. Suppose a back-facing camera sensor
     * is mounted in landscape and the top side of the camera sensor is aligned with the right edge
     * of the display in natural orientation. So the camera orientation is 90. The rotation should
     * be set to 0 (270 + 90).
     */
    private int getSensorRotation(String cameraId) {
        int cameraRotation;
        if (cameraId != null && cameraId.equals(mRearFacingCameraId)) {
            cameraRotation = mDeviceRotation + 90;
        } else {
            if (mDeviceRotation >= 270) {
                cameraRotation = 270 + mDeviceRotation;
            } else {
                cameraRotation = 270 - mDeviceRotation;
            }
        }
        Log.i(TAG, "getSensorRotation()->cameraId:" + cameraId + " cameraRotation:"
                + cameraRotation
                + " mDeviceRotation:" + mDeviceRotation);
        return cameraRotation;
    }

    /**
     * indicate async task is running, should disable camera-relative operations.
     */
    private boolean isThreadRunning() {
        return mThreadRunning;
    }

    /**
     * Creates a new Camera object to access a particular hardware camera, And starts capturing and
     * drawing preview frames to the screen.
     */
    public void initCameraAndStartPreview() {

        if (mOperateCameraThread != null) {
            try {
                mOperateCameraThread.join();
            } catch (InterruptedException ex) {
                Log.d(TAG, "mOperateCameraThread.quit() exception " + ex);
            }
        }
        mOperateCameraThread = new Thread(new Runnable() {
            public void run() {
                synchronized (mThreadLock) {
                    mThreadRunning = true;
                }
                Log.i(TAG, "mOperateCameraThread start. ");
                openCamera();
                synchronized (mThreadLock) {
                    mThreadRunning = false;
                }
                Log.d(TAG, "mOperateCameraThread end. ");
            }
        });
        mOperateCameraThread.start();
    }

    /**
     * Creates a new Camera object to access a particular hardware camera. If the same camera is
     * opened by other applications, this will throw a RuntimeException.
     */
    private void openCamera() {
        try {
            synchronized (mCameraLock) {
                Log.i(TAG, "openCamera(), mIsOpened: " + mIsOpened);
                if (!mIsOpened) {
                    // If the activity is paused and resumed, camera device has been
                    // released and we need to open the camera.
                    String cameraId = getCamerID();
                    try {
                        if (cameraId != null) {
                            if (Integer.parseInt(cameraId) == -1) {// SPRD: Modify for bug851181
                                return;
                            }
                            /* SPRD: Modify for bug571839 @ { */
                            else if (mVideoCallEngine
                                    .setCameraId(Integer.parseInt(cameraId)) != 0) {
                                mHandler.removeMessages(EVENT_CAMERA_FAIL);
                                mHandler.sendEmptyMessageDelayed(EVENT_CAMERA_FAIL, 200);
                                return;
                            } else {
                                // SPRD: bug729242, bug846042
                                mVideoCallEngine.setPreviewDisplayOrientation(
                                        mDeviceRotation, mScreenRotation, mOrientationSetting);
                            }
                            /* @ } */
                        }
                    } catch (NumberFormatException e) {
                        return;
                    }
                    // Unisoc: delete for bug1149356
                    // setPreviewSurfaceSize(mVideoQuality);
                    if (mVideoCallEngine.mLocalSurface != null) {
                        mVideoCallEngine.setImsLocalSurface(mVideoCallEngine.mLocalSurface);
                        mVideoCallEngine.startPreview();
                    }
                    Log.i(TAG, "openCamera(), cameraId: "
                            + cameraId + "   mVideoCallEngine =" + mVideoCallEngine);
                    mIsOpened = true;
                    mIsFirstInit = false;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Open Camera Fail: " + e);
            closeCamera();
        }
    }

    private void setPreviewSurfaceSize(int quality) {
        int frameRate = 30;
        switch (mVideoQuality) {
            case ImsConfigImpl.VT_RESOLUTION_720P:
                mWidth = 1280;
                mHeight = 720;
                break;
            case ImsConfigImpl.VT_RESOLUTION_VGA_REVERSED_15:
                mWidth = 480;
                mHeight = 640;
                frameRate = 15;
                break;
            case ImsConfigImpl.VT_RESOLUTION_VGA_REVERSED_30:
                mWidth = 480;
                mHeight = 640;
                break;
            case ImsConfigImpl.VT_RESOLUTION_QVGA_REVERSED_15:
                mWidth = 480;
                mHeight = 640;
                frameRate = 15;
                break;
            case ImsConfigImpl.VT_RESOLUTION_QVGA_REVERSED_30:
                mWidth = 240;
                mHeight = 320;
                break;
            case ImsConfigImpl.VT_RESOLUTION_CIF:
                mWidth = 352;
                mHeight = 288;
                break;
            case ImsConfigImpl.VT_RESOLUTION_QCIF:
                mWidth = 176;
                mHeight = 144;
                break;
            case ImsConfigImpl.VT_RESOLUTION_VGA_15:
                mWidth = 640;
                mHeight = 480;
                frameRate = 15;
                break;
            case ImsConfigImpl.VT_RESOLUTION_VGA_30:
                mWidth = 640;
                mHeight = 480;
                break;
            case ImsConfigImpl.VT_RESOLUTION_QVGA_15:
                mWidth = 320;
                mHeight = 240;
                frameRate = 15;
                break;
            case ImsConfigImpl.VT_RESOLUTION_QVGA_30:
                mWidth = 320;
                mHeight = 240;
                break;
            default:
                break;
        }
        // SPRD: modify by bug620813
        setPreviewSize(mWidth, mHeight);
        Log.i(TAG, "setPreviewSurfaceSize, mWidth = " + mWidth
                + " mHeight =" + mHeight + "   mVideoQuality = " + mVideoQuality);
    }

    private void tryReopenCamera() {
        if (mIsFirstInit) {
            if (mCameraTimes >= 0) {
                mHandler.removeMessages(EVENT_DELAYED_CREATE_CAMERA);
                mHandler.sendEmptyMessageDelayed(EVENT_DELAYED_CREATE_CAMERA, 700);
                mCameraTimes--;
                Log.d(TAG, "mCameraTimes: " + mCameraTimes);
            } else {
                Log.d(TAG, "Camera start progrom exit.");
                mHandler.removeMessages(EVENT_DELAYED_CREATE_CAMERA);
            }
        }
    }

    /**
     * Disconnects and releases the Camera object resources.
     */
    private void closeCamera() {
        Log.i(TAG, "closeCamera");
        mHandler.removeMessages(EVENT_DELAYED_CREATE_CAMERA);
        synchronized (mCameraLock) {
            try {
                mVideoCallEngine.stopPreview();
                mVideoCallEngine.setCameraId(-1);
                mIsOpened = false;
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public void handleSetCameraPreSurface(Surface surface) {
        Log.i(TAG, "handleSetCameraPreSurface surface is " + surface + " mIsOpened:" + mIsOpened);

        // SPRD: add for bug 732419
        if (mCameraId != null) {
            if (!mIsOpened && !mCameraId.equals(String.valueOf(-1))) {
                openVideoCamera();
            }
        }
    }

    public void setPreviewSize(int width, int height) {
        Log.i(TAG, "setPreviewSize-> width:" + width + " height=" + height);
        VTManagerProxy.getInstance().setPreviewSize(width, height);
    }

    public void openVideoCamera() {
        operateCamera(WorkerTaskType.CAMERA_OPEN);
    }

    public void switchVideoCamera() {
        operateCamera(WorkerTaskType.CAMERA_SWITCH);
    }

    public void releaseVideoCamera() {
        // SPRD: remove camera orientationListener for bug 427421
        mOrientationListener.disable();
        mOrientationSettingListener.stopObserver(); // SPRD: bug846042
        /* SPRD: modify for bug 546928 @ { */
        if (mOperateCameraThread != null) {
            try {
                mOperateCameraThread.join();
                Log.i(TAG, "closeCamera-> wait for mOperateCameraThread done.");
            } catch (InterruptedException ex) {
                Log.d(TAG, "mOperateCameraThread.quit() exception " + ex);
            }
        }
        /* @} */
        closeCamera();
    }

    public void closeVideoCamera() {
        operateCamera(WorkerTaskType.CAMERA_CLOSE);
    }

    public void handleSetCamera(String cameraId) {
        Log.i(TAG, "handleSetCamera()->isFirstInit:" + mIsFirstInit + " cameraId=" + cameraId
                + " mCameraId=" + mCameraId);
        // Unisoc: add for 1083735
        if (!mIsFirstInit) {
            mHandler.removeMessages(EVENT_HANDLE_SET_CAMERA);
            Message msg = mHandler.obtainMessage(EVENT_HANDLE_SET_CAMERA, cameraId);
            mHandler.sendMessageDelayed(msg, 200);
        } else {
            excuteSetCamera(cameraId);
        }
    }

    public void excuteSetCamera(String cameraId) {
        Log.i(TAG, "excuteSetCamera()->isFirstInit:" + mIsFirstInit + " cameraId=" + cameraId
                + " mCameraId=" + mCameraId);
        if (cameraId == null) {
            closeVideoCamera();
            setCameraID(String.valueOf(-1));
        } else {
            if (mIsFirstInit) {
                setCameraID(cameraId);
                initCameraAndStartPreview();
            } else if (isSameCamera(cameraId)) {
                onSetSameCameraId();
            } else {
                if (mCameraId.equals(String.valueOf(-1))) {
                    setCameraID(cameraId);
                    openVideoCamera();
                } else {
                    setCameraID(cameraId);
                    switchVideoCamera();
                }
            }
        }
    }

    public void onSetSameCameraId() {
        Log.i(TAG, "onSetSameCameraId->mIsPreviewing:" + mIsPreviewing + " mIsOpened:" + mIsOpened);
        if (!mIsOpened) {
            openVideoCamera();
        }
    }

    public void onSetDeviceRotation(int rotation) {
    }

    /**
     * When user click menu to control the camera, this method will be called.
     */
    private synchronized void operateCamera(final WorkerTaskType type) {
        if (mThreadRunning && mOperateCameraThread != null) {

            /*
             * if(WorkerTaskType.CAMERA_SWITCH == type){ Log.e(TAG,
             * "operateCamera(), CODEC is closed or work task locked!"); mIsSurfacePreviewFailed =
             * true; return; } else {
             * SPRD add for bug606383 and modify for bug1100918
             */
            Log.e(TAG, "operateCamera(), CODEC is closed or work task locked! operateCamera join");
            try {
                mOperateCameraThread.join();
            } catch (InterruptedException ex) {
                Log.e(TAG, "updateVideoCameraQuality.quit() exception " + ex);
            }
        }
        mOperateCameraThread = new Thread(new Runnable() {
            public void run() {
                Log.i(TAG, "operateCamera() E, type: " + type);
                mThreadRunning = true;
                if (WorkerTaskType.CAMERA_CLOSE == type || WorkerTaskType.CAMERA_OPEN == type) {
                    if (WorkerTaskType.CAMERA_CLOSE == type) {
                        closeCamera();
                    } else if (WorkerTaskType.CAMERA_OPEN == type) {
                        openCamera();
                    }
                } else if (WorkerTaskType.CAMERA_SWITCH == type) {
                    closeCamera();
                    openCamera();
                }
                Log.i(TAG, "closeOrSwitchCamera() X");
                mThreadRunning = false;
                return;
            }
        });

        Log.i(TAG, "mOperateCameraThread: " + mOperateCameraThread);
        mOperateCameraThread.start();
    }

    /**
     * Get the camera ID and aspect ratio for the front and rear cameras
     *
     * @param context The context.
     */
    private void initializeCameraList(Context context) {
        if (context == null) {
            return;
        }

        CameraManager cameraManager = null;
        try {
            cameraManager = (CameraManager) context.getSystemService(
                    Context.CAMERA_SERVICE);
        } catch (Exception e) {
            Log.e(TAG, "Could not get camera service.");
            return;
        }

        if (cameraManager == null) {
            return;
        }

        try {
            for (String cameraId : cameraManager.getCameraIdList()) {
                CameraCharacteristics cameraCharacteristics = cameraManager
                        .getCameraCharacteristics(cameraId);
                if (cameraCharacteristics.get(
                        cameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT) {
                    mFrontFacingCameraId = cameraId;
                } else if (cameraCharacteristics.get(
                        cameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK) {
                    mRearFacingCameraId = cameraId;
                }
            }
        } catch (CameraAccessException e) {
            Log.w(TAG, "Could not access camera: " + e);
        } catch (IllegalArgumentException e) {
            // Device Id is unknown.
            Log.w(TAG, "initializeCameraList fail: " + e);
        }
        Log.i(TAG, "initializeCameraList->mFrontFacingCameraId:" + mFrontFacingCameraId
                + "  mRearFacingCameraId:" + mRearFacingCameraId);
    }

    private class MyOrientationEventListener extends OrientationEventListener {
        public MyOrientationEventListener(Context context) {
            super(context);
        }

        @Override
        public void onOrientationChanged(int orientation) {
            int displayOrientation = mDeviceRotation;
            int screenRotation = mWinMana.getDefaultDisplay().getRotation();// SPRD: bug729242
            if (orientation != OrientationEventListener.ORIENTATION_UNKNOWN) {
                if (orientation >= 350 || orientation <= 10) {
                    displayOrientation = 0;
                } else if (orientation >= 80 && orientation <= 100) {
                    displayOrientation = 90;
                } else if (orientation >= 170 && orientation <= 190) {
                    displayOrientation = 180;
                } else if (orientation >= 260 && orientation <= 280) {
                    displayOrientation = 270;
                }
            }
            if ((displayOrientation != mDeviceRotation
                    && displayOrientation != OrientationEventListener.ORIENTATION_UNKNOWN)
                    || screenRotation != mScreenRotation) {// SPRD: bug729242
                Log.i(TAG, "onOrientationChanged: " + displayOrientation
                        + " screenRotation: " + screenRotation);
                mDeviceRotation = displayOrientation;
                mScreenRotation = screenRotation;
                mHandler.removeMessages(EVENT_CHANGE_ORIENTATION);
                mHandler.sendEmptyMessageDelayed(EVENT_CHANGE_ORIENTATION, 200);
            }
        }
    }

    private void handleOrientationChange() {
        Log.i(TAG, "handleOrientationChange->mDeviceRotation: "
                + mDeviceRotation + " mScreenRotation: " + mScreenRotation);
        if ((mDeviceRotation == 90) || (mDeviceRotation == 270)) {
            VTManagerProxy.getInstance().mPreviewWidth = mHeight;
            VTManagerProxy.getInstance().mPreviewHeight = mWidth;
        } else if ((mDeviceRotation == 0) || (mDeviceRotation == 180)) {
            VTManagerProxy.getInstance().mPreviewWidth = mWidth;
            VTManagerProxy.getInstance().mPreviewHeight = mHeight;
        }
        updateCameraPara();
    }

    private void updateCameraPara() {
        // SPRD: bug729242,bug846042
        mVideoCallEngine.setPreviewDisplayOrientation(
                mDeviceRotation, mScreenRotation, mOrientationSetting);
    }

    public void updateVideoQuality(int videoQuality) {
        if (videoQuality < 0 || videoQuality > 10) {
            videoQuality = mVideoCallEngine.getCameraResolution();
        }
        boolean qualityChange = (mVideoQuality != videoQuality || !mIsVideoQualityReceived);
        mVideoQuality = videoQuality;
        mIsVideoQualityReceived = true;
        Log.i(TAG, "updateVideoQuality->qualityChange:" + qualityChange
                + " mCameraId:" + mCameraId + " mLocalSurface:" + mVideoCallEngine.mLocalSurface);
        // add for SPRD:Bug 833989, 842966
        if (qualityChange && mCameraId != null && !mCameraId.equals(String.valueOf(-1))) {
            if (!mIsOpened) {
                openVideoCamera();
            } else {
                setPreviewSurfaceSize(mVideoQuality);
            }
        }
    }

    public void updateVideoCameraQuality() {
        if (mOperateCameraThread != null) {
            try {
                mOperateCameraThread.join();
            } catch (InterruptedException ex) {
                Log.d(TAG, "updateVideoCameraQuality.quit() exception " + ex);
            }
        }
        mOperateCameraThread = new Thread(new Runnable() {
            public void run() {
                mThreadRunning = true;
                closeCamera();
                openCamera();
                mThreadRunning = false;
                return;
            }
        });

        Log.i(TAG, "updateVideoCameraQuality: " + mOperateCameraThread);
        mOperateCameraThread.start();
    }

    // SPRD: add for bug846042
    private class RotationObserver extends ContentObserver {

        ContentResolver mResolver;
        boolean mIsStartObserver;

        public RotationObserver(Handler handler) {
            super(handler);
            if (mContext != null) {
                mResolver = mContext.getContentResolver();
            }
        }

        // called when rotation setting changed
        @Override
        public void onChange(boolean selfChange) {
            // TODO Auto-generated method stub
            super.onChange(selfChange);

            // rotation update
            Log.i(TAG, "onChange selfChange=" + selfChange);
            // get rotation setting 1-open 0-close
            mOrientationSetting = Settings.System.getInt(mContext.getContentResolver(),
                    Settings.System.ACCELEROMETER_ROTATION, 0);
            if (mOrientationSetting == ORIENTATION_SETTING_OFF) {
                mScreenRotation = 0;
                Log.i(TAG, "OrientationSettingClosed(0), force change mScreenRotation to 0");
            } else {
                mScreenRotation = mWinMana.getDefaultDisplay().getRotation();
            }
            Log.i(TAG, "OrientationSettingChanged->setPreviewDisplayOrientation,"
                    + "mDeviceRotation: " + mDeviceRotation + " mScreenRotation: "
                    + mScreenRotation + " mOrientationSetting: " + mOrientationSetting);
            mVideoCallEngine.setPreviewDisplayOrientation(
                    mDeviceRotation, mScreenRotation, mOrientationSetting);
        }

        public void startObserver() {
            Log.i(TAG, "startObserver mResolver=" + mResolver + " mIsStartObserver ="
                    + mIsStartObserver);
            if (mResolver != null && !mIsStartObserver) {
                mIsStartObserver = true;
                mResolver.registerContentObserver(
                        Settings.System.getUriFor(Settings.System.ACCELEROMETER_ROTATION), false,
                        this);
            }
        }

        public void stopObserver() {
            Log.i(TAG, "stopObserver mIsStartObserver =" + mIsStartObserver);
            if (mIsStartObserver) {
                mResolver.unregisterContentObserver(this);
                mIsStartObserver = false;
            }
        }
    }
}
