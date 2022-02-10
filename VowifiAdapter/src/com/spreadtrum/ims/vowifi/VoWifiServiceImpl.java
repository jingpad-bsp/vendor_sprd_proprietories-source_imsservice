
package com.spreadtrum.ims.vowifi;

import android.annotation.TargetApi;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.preference.PreferenceManager;
import android.telephony.ims.ImsCallProfile;
import android.telephony.ims.ImsCallSession.State;
import android.telephony.ims.aidl.IImsCallSessionListener;
import android.telephony.ims.stub.ImsSmsImplBase;
import android.telephony.CarrierConfigManager;
import android.telephony.ServiceState;
import android.util.Log;
import android.text.TextUtils;

import com.android.ims.ImsManager;
import com.android.ims.internal.IImsCallSession;
import com.android.ims.internal.IImsServiceEx;
import com.android.ims.internal.ImsManagerEx;

import com.spreadtrum.ims.ImsConfigImpl;
import com.spreadtrum.ims.vowifi.Utilities.CallStateForDataRouter;
import com.spreadtrum.ims.vowifi.Utilities.CallType;
import com.spreadtrum.ims.vowifi.Utilities.ECBMRequest;
import com.spreadtrum.ims.vowifi.Utilities.IPVersion;
import com.spreadtrum.ims.vowifi.Utilities.NativeErrorCode;
import com.spreadtrum.ims.vowifi.Utilities.RegisterConfig;
import com.spreadtrum.ims.vowifi.Utilities.RegisterState;
import com.spreadtrum.ims.vowifi.Utilities.Result;
import com.spreadtrum.ims.vowifi.Utilities.S2bType;
import com.spreadtrum.ims.vowifi.Utilities.SecurityConfig;
import com.spreadtrum.ims.vowifi.Utilities.UnsolicitedCode;
import com.spreadtrum.ims.vowifi.VoWifiCallManager.CallListener;
import com.spreadtrum.ims.vowifi.VoWifiRegisterManager.RegisterListener;
import com.spreadtrum.ims.vowifi.VoWifiSOSManager.SOSListener;
import com.spreadtrum.ims.vowifi.VoWifiSecurityManager.SecurityListener;

/**
 * The VoWifi only enabled for primary SIM card.
 */
@TargetApi(23)
public class VoWifiServiceImpl implements OnSharedPreferenceChangeListener {

    private static boolean sDozeMgrStarted = false;

    protected static final int CMD_STATE_INVALID = 0;
    protected static final int CMD_STATE_PROGRESS = 1;
    protected static final int CMD_STATE_FINISHED = 2;

    protected static final int RESET_TIMEOUT = 5000; // 5s
    protected static final int RESET_STEP_INVALID = 0;
    protected static final int RESET_STEP_DEREGISTER = 1;
    protected static final int RESET_STEP_DEATTACH = 2;
    protected static final int RESET_STEP_FORCE_RESET = 3;

    protected int mPhoneId = -1;
    protected int mSubId = -1;

    protected int mResetStep = RESET_STEP_INVALID;
    protected int mEcbmStep = ECBMRequest.ECBM_STEP_INVALID;
    protected int mCmdAttachState = CMD_STATE_INVALID;
    protected int mCmdRegisterState = CMD_STATE_INVALID;

    protected int mCurRatType = -1;
    protected int mCurCallType = -1;

    protected boolean mIsSRVCCSupport = true;
    protected boolean mNeedLogoutAfterCall = false;
    protected String mUsedLocalAddr = null;
    protected String mTag = null;

    protected Context mContext;
    protected SharedPreferences mPreferences = null;
    protected VoWifiCallback mCallback = null;
    protected ECBMRequest mECBMRequest = null;


    protected ImsEcbmImpl mImsEcbm;
    protected UtSyncManager mUtSyncMgr;
    protected VoWifiCallManager mCallMgr;
    protected VoWifiSmsManager mSmsMgr;
    protected VoWifiUTManager mUTMgr;
    protected VoWifiRegisterManager mRegisterMgr;
    protected VoWifiSecurityManager mSecurityMgr;
    protected VoWifiSOSManager mSOSMgr;

    protected MyHandler mHandler = null;
    protected MyCallListener mCallListener = new MyCallListener();
    protected MyRegisterListener mRegisterListener = new MyRegisterListener();
    protected MySecurityListener mSecurityListener = new MySecurityListener();

    protected static final int ECBM_TIMEOUT = 15 * 1000; // 15s

    protected static final int MSG_RESET = 1;
    protected static final int MSG_RESET_FORCE = 2;
    protected static final int MSG_ATTACH = 3;
    protected static final int MSG_DEATTACH = 4;
    protected static final int MSG_START_MOBIKE = 5;
    protected static final int MSG_REGISTER = 6;
    protected static final int MSG_DEREGISTER = 7;
    protected static final int MSG_REREGISTER = 8;
    protected static final int MSG_UPDATE_RAT_STATE = 9;
    protected static final int MSG_UPDATE_DATA_ROUTER_STATE = 10;
    protected static final int MSG_TERMINATE_CALLS = 11;
    protected static final int MSG_ECBM = 12;
    protected static final int MSG_ECBM_TIMEOUT = 13;
    protected static final int MSG_SRVCC_SUCCESS = 14;
    protected static final int MSG_ENTER_ECBM = 15;
    protected static final int MSG_EXIT_ECBM = 16;

    // Used to handle attach/register failed as do not init.
    protected static final int MSG_ATTACH_FAILED = 100;
    protected static final int MSG_REGISTER_FAILED = 101;
    protected static final int MSG_RESET_FINISHED = 102;

    protected class MyHandler extends Handler {
        public MyHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            if (Utilities.DEBUG) Log.i(mTag, "The handler get the message: " + msg.what);

            // If do not init, but user start attach or register, handle as failed.
            if (msg.what == MSG_ATTACH_FAILED) {
                if (mCallback != null) mCallback.onAttachFinished(false, 0);
                return;
            } else if (msg.what == MSG_REGISTER_FAILED) {
                registerFailed();
                return;
            } else if (msg.what == MSG_RESET_FINISHED) {
                resetFinished();
                return;
            }

            // Handle the others normal message.
            if (mSecurityMgr == null) {
                // All the action based on the VOWIFI attach, do nothing if null.
                Log.w(mTag, "There isn't security manager. Do nothing, please check the process!");
                return;
            }

            switch (msg.what) {
                case MSG_RESET:
                    mResetStep = msg.arg1;
                    handleMsgReset(mResetStep);
                    break;
                case MSG_RESET_FORCE:
                    // Force stop the register and s2b.
                    if (msg.arg1 == Integer.MAX_VALUE && mCallback != null) {
                        // It means need notify the reset start action.
                        mCallback.onResetStarted();
                    }

                    mResetStep = RESET_STEP_FORCE_RESET;
                    registerForceStop();
                    securityForceStop();
                    break;
                case MSG_ATTACH:
                    attachInternal((Boolean) msg.obj, mUsedLocalAddr);
                    break;
                case MSG_DEATTACH:
                    boolean forHandover = (Boolean) msg.obj;
                    deattachInternal(forHandover);
                    break;
                case MSG_START_MOBIKE:
                    startMobikeInternal();
                    break;
                case MSG_REGISTER:
                    registerPrepare();
                    break;
                case MSG_DEREGISTER:
                    deregisterInternal();
                    break;
                case MSG_REREGISTER:
                    int type = msg.arg1;
                    String info = (String) msg.obj;
                    reRegisterInternal(type, info);
                    break;
                case MSG_UPDATE_RAT_STATE:
                    // Update the calls' rat type.
                    if (mCallMgr != null
                            && mCurRatType > ServiceState.RIL_RADIO_TECHNOLOGY_UNKNOWN) {
                        mCallMgr.updateCallsRatType(mCurRatType);
                    }
                    break;
                case MSG_UPDATE_DATA_ROUTER_STATE:
                    // Update the data router state.
                    int dataRouterState = msg.arg1;
                    int notifyFinished = msg.arg2;
                    if (mCallMgr != null) {
                        mCallMgr.updateDataRouterState(dataRouterState);
                    }
                    if (mCallback != null && notifyFinished > 0) {
                        mCallback.onUpdateDRStateFinished();
                    }
                    break;
                case MSG_TERMINATE_CALLS:
                    WifiState wifiState = (WifiState) msg.obj;
                    try {
                        if (mCallMgr != null) {
                            mCallMgr.terminateCalls(wifiState);
                        }
                    } catch (RemoteException e) {
                        Log.e(mTag, "Catch the RemoteException when terminate calls. e: " + e);
                    }
                    break;
                case MSG_ECBM:
                    mEcbmStep = msg.arg1;
                    handleMsgECBM(mEcbmStep);
                    break;
                case MSG_ECBM_TIMEOUT:
                    handleMsgECBMTimeout(msg.arg1);
                    break;
                case MSG_SRVCC_SUCCESS:
                    // If the SRVCC success, handle it as register logout.
                    Log.d(mTag, "Handle the SRVCC success as register logout now.");
                    registerLogout(0);
                    break;
                case MSG_ENTER_ECBM:
                    int enterStep = mECBMRequest.getCurStep();
                    if (enterStep > ECBMRequest.ECBM_STEP_INVALID) {
                        Log.d(mTag, "Handle enter ECBM, and the enter step is: " + enterStep);
                        // As enter the ECBM, need reject all the incoming call.
                        if (mCallMgr != null) {
                            mCallMgr.updateIncomingCallAction(IncomingCallAction.REJECT);
                        }

                        // Update the ECBM state.
                        getEcbmInterface().updateEcbm(true, mECBMRequest.getCallSession());

                        sendECBMTimeoutMsg(enterStep);
                        Message enterMsg = mHandler.obtainMessage(MSG_ECBM);
                        enterMsg.arg1 = enterStep;
                        mHandler.sendMessage(enterMsg);
                    }
                    break;
                case MSG_EXIT_ECBM:
                    int exitStep = mECBMRequest.getExitECBMStep();
                    if (exitStep > ECBMRequest.ECBM_STEP_INVALID) {
                        Log.d(mTag, "Handle exit ECBM, and the exit step is: " + exitStep);
                        sendECBMTimeoutMsg(exitStep);
                        Message exitMsg = mHandler.obtainMessage(MSG_ECBM);
                        exitMsg.arg1 = exitStep;
                        mHandler.sendMessage(exitMsg);
                    }
                    break;
            }
        }

        private void handleMsgReset(int step) {
            Log.d(mTag, "Handle the reset message as step: " + step);

            if (mResetStep == RESET_STEP_DEREGISTER) {
                // Give the callback when the reset action start.
                if (mCallback != null) mCallback.onResetStarted();

                if (mRegisterMgr.getCurRegisterState() == RegisterState.STATE_CONNECTED) {
                    deregisterInternal();
                    // If the de-register is timeout, force reset.
                    mHandler.sendEmptyMessageDelayed(MSG_RESET_FORCE, RESET_TIMEOUT);
                } else {
                    if (mCmdRegisterState > CMD_STATE_INVALID) {
                        // It means already in register process, but do not register success now.
                        // But we need reset the sip stack now, so we'd like to give the callback
                        // as register failed here.
                        registerFailed();
                    }
                    // As do not register, we'd like to force reset.
                    Log.d(mTag, "Do not register now, transfer to force reset.");
                    mHandler.sendEmptyMessage(MSG_RESET_FORCE);
                }
            } else if (mResetStep == RESET_STEP_DEATTACH) {
                // Remove the reset force msg from the handler.
                mHandler.removeMessages(MSG_RESET_FORCE);

                // De-attach from the EPDG.
                deattachInternal(false);
            } else {
                Log.e(mTag, "Shouldn't be here. reset step is: " + mResetStep);
                resetFinished();
            }
        }

        private void handleMsgECBM(int step) {
            // For enter ECBM, we need de-register & de-attach. Then attach & register for
            // emergency. After register success finished, start the emergency call.
            // Then try to exit ECBM, we need de-register & de-attach. Then attach & register
            // for normal.
            Log.d(mTag, "Handle the ECBM message as step: " + step);

            switch (step) {
                case ECBMRequest.ECBM_STEP_DEREGISTER_NORMAL:
                    deregisterInternal();
                    break;
                case ECBMRequest.ECBM_STEP_DEATTACH_SOS:
                case ECBMRequest.ECBM_STEP_DEATTACH_NORMAL:
                    deattachInternal(false);
                    break;
                case ECBMRequest.ECBM_STEP_ATTACH_SOS:
                    if (Utilities.isSupportSOSSingleProcess(mContext)
                            && mSOSMgr != null
                            && mSOSMgr.startRequest(mECBMRequest, new MySOSListener())) {
                        mHandler.removeMessages(MSG_ECBM_TIMEOUT);
                        break;
                    }
                case ECBMRequest.ECBM_STEP_ATTACH_NORMAL:
                    attachInternal(false /* is not handover */, "");
                    break;
                case ECBMRequest.ECBM_STEP_REGISTER_SOS:
                case ECBMRequest.ECBM_STEP_REGISTER_NORMAL:
                    registerPrepare();
                    break;
                case ECBMRequest.ECBM_STEP_START_EMERGENCY_CALL:
                    ImsCallSessionImpl callSession = mECBMRequest.getCallSession();
                    if (callSession == null || callSession.getState() > State.IDLE) {
                        Log.e(mTag, "Failed to start the emergency call: " + callSession);
                        // Handle this error as meet ecbm timeout.
                        mHandler.sendEmptyMessage(MSG_ECBM_TIMEOUT);
                        break;
                    }

                    callSession.dialEmergencyCall();
                    break;
                case ECBMRequest.ECBM_STEP_INVALID:
                    exitECBM();
                    break;
            }
        }

        private void handleMsgECBMTimeout(int forStep) {
            Log.d(mTag, "Handle the ECBM timeout message, for step: " + forStep
                    + ", and current ECBM step: " + mEcbmStep);
            onSOSMsgTimeout();

            // Exit the ECBM, and it will terminate the emergency call session.
            exitECBM();

            // Notify as register logout, and reset the security and sip stack.
            resetAll(WifiState.DISCONNECTED);
            registerLogout(0);
        }

    }

    private boolean mRegisterReceiver = false;
    private BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.d(mTag, "Receive the carrier config changed intent: " + intent.toString());
            if (CarrierConfigManager.ACTION_CARRIER_CONFIG_CHANGED.equals(intent.getAction())) {
                init();
            }
        }
    };

    public VoWifiServiceImpl(Context context) {
        this(context, Utilities.getTag(VoWifiServiceImpl.class.getSimpleName()), true);
    }

    public VoWifiServiceImpl(Context context, String logTag, boolean init) {
        mContext = context;
        mTag = logTag;

        HandlerThread thread = new HandlerThread(logTag);
        thread.start();
        Looper looper = thread.getLooper();
        mHandler = new MyHandler(looper);

        // Start the ims_doze_manager service.
        if (!sDozeMgrStarted) {
            sDozeMgrStarted = true;
            mContext.startService(new Intent(mContext, ImsDozeManagerService.class));

            // Sometimes, the ImsCM create too late, we'd like to start the ImsCM here.
            Intent startIntent = new Intent();
            startIntent.setPackage("com.sprd.ImsConnectionManager");
            startIntent.setAction("com.spreadtrum.ims.vowifi.action.IMS_READY");
            mContext.sendBroadcast(startIntent);
        }

        if (init) init();
    }

    @Override
    protected void finalize() throws Throwable {
        uninit();

        if (mHandler != null) {
            mHandler.removeCallbacksAndMessages(null);
            Looper looper = mHandler.getLooper();
            if (looper != null) {
                looper.quit();
            }
        }

        super.finalize();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        // If the video quality changed, we need update the video quality.
        if (ImsConfigImpl.VT_RESOLUTION_VALUE.equals(key) && mCallMgr != null) {
            // The video quality is changed.
            mCallMgr.updateVideoQuality(Utilities.getDefaultVideoQuality(mPreferences));
        }
    }

    protected void onSOSCallTerminated() {
        // Do nothing.
    }

    protected boolean onSOSProcessFinished() {
        // Do not handle here, and return false.
        // It will be handled by subclass.
        return false;
    }

    protected void onSOSMsgTimeout() {
        // DO nothing.
    }

    protected void onSOSError(int failedStep) {
        // Do nothing.
    }

    public void registerCallback(VoWifiCallback callback) {
        if (Utilities.DEBUG) Log.i(mTag, "Register the vowifi service callback: " + callback);
        if (callback == null) {
            Log.e(mTag, "Can not register the callback as it is null.");
            return;
        }

        mCallback = callback;
    }

    public void unregisterCallback() {
        if (Utilities.DEBUG) Log.i(mTag, "Unregister the vowifi service callback: " + mCallback);
        mCallback = null;
    }

    /**
     * Ut interface for the supplementary service configuration.
     */
    public ImsUtImpl getUtInterface() {
        return getUtInterface(Utilities.getPrimaryCard(mContext));
    }

    public ImsUtImpl getUtInterface(int phoneId) {
        if (mUTMgr == null) {
            return null;
        }

        return mUTMgr.getUtImpl(phoneId);
    }

    public ImsEcbmImpl getEcbmInterface() {
        if (mImsEcbm == null) {
            mImsEcbm = new ImsEcbmImpl(mContext);
        }

        return mImsEcbm;
    }

    public ImsSmsImplBase getSmsImplementation(int phoneId) {
        return mSmsMgr == null ? new ImsSmsImplBase() : mSmsMgr.getSmsImplementation(phoneId);
    }

    public void onVoLTEPDNActivated() {
        Log.d(mTag, "VoLTE PDN activated, will redefine the reset process. Current reset step: "
                + mResetStep);
        if (mHandler.hasMessages(MSG_RESET)
                && mResetStep < RESET_STEP_DEREGISTER ) {
            // It means there is reset action, and do not execute de-attach action.
            mHandler.removeMessages(MSG_RESET);

            Message msg = mHandler.obtainMessage(MSG_RESET_FORCE);
            // As do not execute the de-register action, need notify reset start.
            msg.arg1 = Integer.MAX_VALUE; // Marked as need notify reset start.
            mHandler.sendMessage(msg);
        }
    }

    public void resetAll(WifiState state) {
        resetAll(state, 0/* Do not delay */);
    }

    /**
     * Reset the s2b and sip stack. If WIFI is disable, the de-register and de-attach couldn't
     * be sent. So we'd like to force reset the states.
     *
     * @param wifiState disable or enable.
     */
    public void resetAll(WifiState state, int delayMillis) {
        if (Utilities.DEBUG) {
            Log.i(mTag, "Reset the security and sip stack with wifi state: "
                    + (WifiState.CONNECTED.equals(state) ? "connect" : "disconnect")
                    + ", delay millis: " + delayMillis);
        }

        if (state == null) {
            throw new NullPointerException("The wifi state couldn't be null.");
        }

        if (mCallMgr == null || mSmsMgr == null) {
            Log.w(mTag, "Do not init now, handle as reset finished.");
            mHandler.sendEmptyMessage(MSG_RESET_FINISHED);
            return;
        }

        // Update the register state to call manager as it will be reset later.
        mCallMgr.updateRegisterState(RegisterState.STATE_IDLE);
        mSmsMgr.updateRegisterState(RegisterState.STATE_IDLE);

        // Before reset the sip stack, we'd like to terminate all the calls.
        terminateCalls(WifiState.CONNECTED);

        // Do not care the wifi state, we will try to de-register for reset first.
        Message msg = null;
        if (mCmdAttachState != CMD_STATE_FINISHED) {
            // If there isn't attach request, we'd like to force reset process.
            // For example, there is a call already HANDOVER to VOLTE, and then the
            // call terminate. For this case, we needn't send the de-register as it
            // maybe disturb the VOLTE register process.
            msg = mHandler.obtainMessage(MSG_RESET_FORCE);
            msg.arg1 = Integer.MAX_VALUE; // Marked as need notify reset start.
            Log.d(mTag, "As there isn't attach, force reset.");
        } else {
            msg = mHandler.obtainMessage(MSG_RESET);
            msg.arg1 = RESET_STEP_DEREGISTER;
        }
        mHandler.sendMessageDelayed(msg, delayMillis);
    }

    public void attach() {
        attach(false);
    }

    public void attach(boolean isHandover) {
        if (mSecurityMgr != null) {
            // It means already init, could accept the attach cmd.
            mCmdAttachState = CMD_STATE_PROGRESS;
            mHandler.sendMessage(mHandler.obtainMessage(MSG_ATTACH, isHandover));
        } else {
            Log.w(mTag, "Failed to start attach process as do not init now.");
            init();
            mHandler.sendEmptyMessage(MSG_ATTACH_FAILED);
        }
    }

    private void attachInternal(boolean isHandover, String localAddr) {
        Log.d(mTag, "Attach on the version: " + Utilities.Version.getVersionInfo());

        // Before start attach process, need get the SIM account info.
        // We will always use the primary card to attach and register now.
        mPhoneId = Utilities.getPrimaryCard(mContext);
        mSubId = Utilities.getSubId(mPhoneId);
        if (mSubId < 0) {
            Log.e(mTag, "Can not get the sub id.");
            if (mCallback != null) mCallback.onAttachFinished(false, 0);
            return;
        }

        int s2bType = mEcbmStep == ECBMRequest.ECBM_STEP_ATTACH_SOS ? S2bType.SOS : S2bType.NORMAL;
        mSecurityMgr.attach(isHandover, mSubId, s2bType, localAddr, mSecurityListener);
    }

    public void deattach() {
        deattach(false);
    }

    public void deattach(boolean forHandover) {
        mHandler.sendMessage(mHandler.obtainMessage(MSG_DEATTACH, forHandover));
    }

    private void deattachInternal(boolean forHandover) {
        int s2bType =
                mEcbmStep == ECBMRequest.ECBM_STEP_DEATTACH_SOS ? S2bType.SOS : S2bType.NORMAL;
        mSecurityMgr.deattach(mSubId, s2bType, forHandover, mSecurityListener);
    }

    /**
     * To force stop the s2b even it in the connect progress.
     */
    private void securityForceStop() {
        mSecurityMgr.forceStop(mSubId, mSecurityListener);
    }

    public void startMobike() {
        mHandler.sendEmptyMessage(MSG_START_MOBIKE);
    }

    private void startMobikeInternal() {
        mSecurityMgr.startMobike(mSubId);
    }

    public boolean isSupportMobike() {
        SecurityConfig config = getSecurityConfig();
        return config == null ? false : config._isSupportMobike;
    }

    public SecurityConfig getSecurityConfig() {
        return mSecurityMgr == null ? null : mSecurityMgr.getConfig(mSubId, S2bType.NORMAL);
    }

    public RegisterConfig getRegisterConfig() {
        return mRegisterMgr == null ? null : mRegisterMgr.getCurRegisterConfig();
    }

    public void register() {
        if (mRegisterMgr != null) {
            // It means already init, could accept the register cmd.
            mCmdRegisterState = CMD_STATE_PROGRESS;
            mHandler.sendEmptyMessage(MSG_REGISTER);
        } else {
            Log.w(mTag, "Failed to start register process as do not init now. Shouldn't be here.");
            mHandler.sendEmptyMessage(MSG_REGISTER_FAILED);
        }
    }

    /**
     * Register to the IMS service.
     */
    private void registerPrepare() {
        // Check the security state, if it is attached. We could start the register process.
        int s2bType =
                mEcbmStep == ECBMRequest.ECBM_STEP_REGISTER_SOS ? S2bType.SOS : S2bType.NORMAL;
        SecurityConfig securityConfir = mSecurityMgr.getConfig(mSubId, s2bType);
        if (securityConfir == null) {
            Log.e(mTag, "Before register, need attach success, please check the attach state!");
            registerFailed();
            return;
        }

        // For register process, step as this:
        // 1. Prepare for login.
        // 2. If prepare finished, start the login process with the first local IP and PCSCF IP.
        // 3. If login failed, need try the left local IP and PCSCF IP until all of them already
        //    failed, then need notify the user login failed.
        RegisterConfig regConf = RegisterConfig.getInstance(securityConfir._ip4,
                securityConfir._ip6, securityConfir._pcscf4, securityConfir._pcscf6,
                getUsedPcscfAddr(), securityConfir._dns4, securityConfir._dns6);
        mRegisterMgr.prepareForLogin(mSubId, mIsSRVCCSupport, regConf, mRegisterListener);
    }

    public void deregister() {
        // Before de-register action, terminate all the calls.
        terminateCalls(WifiState.CONNECTED);
        // Handle the de-register action.
        mHandler.sendEmptyMessage(MSG_DEREGISTER);
    }

    /**
     * Unregister to the IMS service.
     */
    private void deregisterInternal() {
        mRegisterMgr.deregister(mRegisterListener);
    }

    /**
     * Refresh the register status.
     */
    public void reRegister() {
        reRegister(-1, null);
    }

    public void reRegister(int type, String info) {
        mHandler.sendMessage(mHandler.obtainMessage(MSG_REREGISTER, type, -1, info));
    }

    /**
     * Refresh the register status with the given network info.
     */
    private void reRegisterInternal(int type, String info) {
        mRegisterMgr.reRegister(type, info);
    }

    /**
     * To cancel the current register process. If already register, it will stop and close the
     * sip stack but not de-register.
     *
     * @return true if stop and close the sip stack, otherwise false.
     */
    private boolean registerForceStop() {
        return mRegisterMgr.forceStop(mRegisterListener);
    }

    public String getCurPAssociatedUri() {
        if (getRegisterConfig() != null
                && mRegisterMgr != null
                && mRegisterMgr.getCurRegisterState() == RegisterState.STATE_CONNECTED) {
            return mRegisterMgr.getPAssociatedUri();
        }
        return "";
    }

    public String getCurLocalAddress() {
        if (mRegisterMgr != null
                && mRegisterMgr.getCurRegisterState() == RegisterState.STATE_CONNECTED
                && getRegisterConfig() != null) {
            return getRegisterConfig().getCurUsedLocalIP();
        }
        return "";
    }

    public String getCurPcscfAddress() {
        if (mRegisterMgr != null
                && mRegisterMgr.getCurRegisterState() == RegisterState.STATE_CONNECTED
                && getRegisterConfig() != null) {
            return getRegisterConfig().getCurUsedPcscfIP();
        }
        return "";
    }

    /**
     * Create a new call session for the given profile.
     */
    public ImsCallSessionImpl createCallSession(ImsCallProfile profile,
            IImsCallSessionListener listener) {
        return mCallMgr == null ? null : mCallMgr.createMOCallSession(profile, listener);
    }

    public ImsCallSessionImpl getPendingCallSession(String callId) {
        return mCallMgr == null ? null : mCallMgr.getCallSession(callId);
    }

    public void terminateCalls(WifiState state) {
        if (state == null) {
            throw new NullPointerException("The wifi state couldn't be null.");
        }

        mHandler.sendMessage(mHandler.obtainMessage(MSG_TERMINATE_CALLS, state));
    }

    public int getCallCount() {
        return mCallMgr == null ? 0 : mCallMgr.getCallCount();
    }

    public int getCurrentImsVideoState() {
        if (Utilities.DEBUG) Log.i(mTag, "Get alive call type.");

        if (mCallMgr == null) return -1;

        ImsCallSessionImpl callSession = mCallMgr.getCurrentCallSession();
        if (callSession == null) {
            Log.w(mTag, "There isn't any call, return unknown.");
            return -1;
        }

        ImsVideoCallProviderImpl videoProvider = callSession.getVideoCallProviderImpl();
        if (videoProvider == null) {
            Log.w(mTag, "There isn't video provider, return unknown.");
            return -1;
        }

        return videoProvider.getVideoState();
    }

    public int getAliveCallType() {
        if (Utilities.DEBUG) Log.i(mTag, "Get alive call type.");

        if (mCallMgr == null) return CallType.CALL_TYPE_UNKNOWN;

        ImsCallSessionImpl callSession = mCallMgr.getAliveCallSession();
        if (callSession == null) {
            Log.w(mTag, "There isn't any call in alive state, return unknown.");
            return CallType.CALL_TYPE_UNKNOWN;
        }

        boolean isVideo = Utilities.isVideoCall(callSession.getCallProfile().mCallType);
        return isVideo ? CallType.CALL_TYPE_VIDEO : CallType.CALL_TYPE_VOICE;
    }

    public int getAliveCallLose() {
        return mCallMgr == null ? 0 : mCallMgr.getPacketLose();
    }

    public int getAliveCallJitter() {
        return mCallMgr == null ? 0 : mCallMgr.getJitter();
    }

    public int getAliveCallRtt() {
        return mCallMgr == null ? 0 : mCallMgr.getRtt();
    }

    public void updateIncomingCallAction(IncomingCallAction action) {
        if (action == null) {
            throw new NullPointerException("The incoming call action couldn't be null.");
        }

        if (mCallMgr != null) mCallMgr.updateIncomingCallAction(action);
    }

    /**
     * Used to update the current call's slot id even the call is volte call.
     */
    public boolean updateCurCallSlot(int slotId) {
        if (mCallMgr != null) {
            return mCallMgr.updateCurCallSlot(slotId);
        }

        return false;
    }

    public void updateCurCallType(int callType) {
        Log.d(mTag, "Update the current call type from " + mCurCallType + " to " + callType
                + ", and current rat type is: " + mCurRatType);

        if (mCurCallType != callType) {
            mCurCallType = callType;

            if (mCurRatType == ServiceState.RIL_RADIO_TECHNOLOGY_IWLAN) {
                // If current rat type is WIFI, we need update the call state for data router.
                int callStateForDataRouter = CallStateForDataRouter.NONE;
                if (Utilities.isAudioCall(mCurCallType)) {
                    callStateForDataRouter = CallStateForDataRouter.VOWIFI;
                } else {
                    callStateForDataRouter = CallStateForDataRouter.VOWIFI_VIDEO;
                }
                mHandler.sendMessage(mHandler.obtainMessage(MSG_UPDATE_DATA_ROUTER_STATE,
                        callStateForDataRouter, -1 /* needn't notify onUpdateDRStateFinished */));
            }
        }
    }

    public void updateCallRatState(CallRatState state) {
        if (state == null) {
            throw new NullPointerException("The call rat type couldn't be null.");
        }

        int updateDataRouterDelay = 0;
        int callStateForDataRouter = CallStateForDataRouter.NONE;
        switch (state) {
            case CALL_VOLTE:
                callStateForDataRouter = CallStateForDataRouter.VOLTE;
                mCurRatType = ServiceState.RIL_RADIO_TECHNOLOGY_LTE;
                break;
            case CALL_VOWIFI:
                if (Utilities.isAudioCall(mCurCallType)) {
                    callStateForDataRouter = CallStateForDataRouter.VOWIFI;
                } else {
                    callStateForDataRouter = CallStateForDataRouter.VOWIFI_VIDEO;
                }
                mCurRatType = ServiceState.RIL_RADIO_TECHNOLOGY_IWLAN;
                break;
            case CALL_NONE:
                callStateForDataRouter = CallStateForDataRouter.NONE;
                // For call none state will be delay 1s, and it is caused by sometimes the "BYE"
                // can not be sent successfully as data router switch after this state update.
                updateDataRouterDelay = 1000; // 1s

                // Reset current call type and current rat type.
                mCurCallType = -1;
                mCurRatType = -1;
                break;
        }

        mHandler.sendEmptyMessage(MSG_UPDATE_RAT_STATE);
        mHandler.sendMessageDelayed(mHandler.obtainMessage(MSG_UPDATE_DATA_ROUTER_STATE,
                callStateForDataRouter, 1 /* need notify onUpdateDRStateFinished */),
                updateDataRouterDelay);
    }

    public void setUsedLocalAddr(String addr) {
        Log.d(mTag, "Update the used local addr to: " + addr);
        mUsedLocalAddr = addr;
    }

    public void setSRVCCSupport(boolean support) {
        mIsSRVCCSupport = support;
    }

    public void onSRVCCStateChanged(int state) {
        if (mCallMgr != null) mCallMgr.onSRVCCStateChanged(state);
    }

    public void notifyImsCNIInfo(int type, String info, int age) {
        if (mRegisterMgr != null) {
            mRegisterMgr.updateCellularNetInfo(type, info, age);
        }
        if (mCallMgr != null) {
            mCallMgr.updateCellularNetInfo(type, info, age);
        }
    }

    private void init() {
        if (mRegisterMgr != null) {
            // Already init, do nothing.
            return;
        }

        // New instances of UtSyncManager, VoWifiCallManager, VoWifiSmsManager,
        // VoWifiUTManager, VoWifiSOSManager without setting.
        if (mPreferences == null) {
            Log.d(mTag, "New instances of preference, ut, call, sms, sos manager.");
            mPreferences = PreferenceManager.getDefaultSharedPreferences(mContext);
            mPreferences.registerOnSharedPreferenceChangeListener(this);
            mUtSyncMgr = UtSyncManager.getInstance(mContext);
            mCallMgr = new VoWifiCallManager(mContext);
            mSmsMgr = new VoWifiSmsManager(mContext);
            mUTMgr = new VoWifiUTManager(mContext);
            mSOSMgr = new VoWifiSOSManager(mContext);
        }

        boolean wfcEnabled = ImsManager.isWfcEnabledByPlatform(mContext);
        Log.d(mTag, "Init the vowifi service as WFC enabled[" + wfcEnabled + "].");

        if (wfcEnabled) {
            // Register and Security is base bussiness of vowifi,
            // need new instance of them if wfc enabled.
            mRegisterMgr = new VoWifiRegisterManager(mContext);
            mSecurityMgr = new VoWifiSecurityManager(mContext);
            mUTMgr.updateSecurityManager(mSecurityMgr);

            mCallMgr.bindService();
            mSmsMgr.bindService();
            mUTMgr.bindService();
            mRegisterMgr.bindService();
            mSecurityMgr.bindService();
            mCallMgr.registerListener(mCallListener);

            // Un-register the carrier config changed receiver.
            if (mRegisterReceiver) {
                mRegisterReceiver = false;
                mContext.unregisterReceiver(mReceiver);
            }
        } else if (!mRegisterReceiver) {
            // As WFC do not enabled now, we'd like to handle the CARRIER_CONFIG_CHANGED notify.
            mRegisterReceiver = true;
            mContext.registerReceiver(mReceiver,
                    new IntentFilter(CarrierConfigManager.ACTION_CARRIER_CONFIG_CHANGED));
            Log.d(mTag, "Register carrier config changed receiver.");
        }
    }

    private void uninit() {
        // Unbind the service.
        if (mCallMgr != null) {
            mCallMgr.unbindService();
            mCallMgr.unregisterListener();
        }

        if (mSmsMgr != null) {
            mSmsMgr.unbindService();
        }

        if (mUTMgr != null) {
            mUTMgr.unbindService();
        }

        if (mRegisterMgr != null) {
            mRegisterMgr.unbindService();
        }

        if (mSecurityMgr != null) {
            mSecurityMgr.unbindService();
        }

        if (mPreferences != null) {
            mPreferences.unregisterOnSharedPreferenceChangeListener(this);
        }
    }

    private void onNativeReset() {
        if (mCallMgr != null) {
            mCallMgr.onNativeReset();
        }

        if (mSmsMgr != null) {
            mSmsMgr.onNativeReset();
        }

        if (mRegisterMgr != null) {
            mRegisterMgr.onNativeReset();
        }

        if (mSecurityMgr != null) {
            mSecurityMgr.onNativeReset();
        }

        if (mUTMgr != null) {
            mUTMgr.onNativeReset();
        }
    }

    private String getUsedPcscfAddr() {
        try {
            IImsServiceEx imsServiceEx = ImsManagerEx.getIImsServiceEx();
            if (imsServiceEx != null) {
                return imsServiceEx.getImsPcscfAddress();
            }
        } catch (RemoteException e) {
            Log.e(mTag, "Failed to get the volte register ip as catch the RemoteException: "
                    + e.toString());
        }

        return "";
    }

    private void resetFinished() {
        mResetStep = RESET_STEP_INVALID;
        mHandler.removeMessages(MSG_RESET);
        mHandler.removeMessages(MSG_RESET_FORCE);

        if (mEcbmStep == ECBMRequest.ECBM_STEP_FORCE_RESET) {
//            Message msg = mHandler.obtainMessage(MSG_ECBM);
//            msg.arg1 = ECBM_STEP_ATTACH;
//            mHandler.sendMessage(msg);
        } else if (mCallback != null) {
            mCallback.onResetFinished(Result.SUCCESS, 0);
        }
    }

    private void registerLogin(boolean isRelogin) {
        if (Utilities.DEBUG) {
            Log.i(mTag, "Try to start the register login process, re-login: " + isRelogin);
        }

        int s2bType =
                mEcbmStep == ECBMRequest.ECBM_STEP_REGISTER_SOS ? S2bType.SOS : S2bType.NORMAL;
        SecurityConfig secConf = mSecurityMgr.getConfig(mSubId, s2bType);
        RegisterConfig regConf = mRegisterMgr.getCurRegisterConfig();
        if (regConf == null || secConf == null) {
            // Can not get the register IP.
            Log.e(mTag, "Failed to login as the register IP is null.");
            registerFailed();
            return;
        }

        boolean startRegister = false;
        int regVersion = regConf.getValidIPVersion(secConf._prefIPv4);
        if (regVersion != IPVersion.NONE) {
            if (regVersion == secConf._useIPVersion
                    || mSecurityMgr.setIPVersion(mSubId, s2bType, regVersion)) {
                startRegister = true;
                secConf._useIPVersion = regVersion;
                boolean useIPv4 = regVersion == IPVersion.IP_V4;
                String localIP = regConf.getLocalIP(useIPv4);
                String pcscfIP = regConf.getPcscfIP(useIPv4);
                String dnsSerIP = regConf.getDnsSerIP(useIPv4);
                boolean forSos = (mEcbmStep == ECBMRequest.ECBM_STEP_REGISTER_SOS);
                mRegisterMgr.login(forSos, useIPv4, localIP, pcscfIP, dnsSerIP, isRelogin);
            }
        }

        if (!startRegister) {
            // Do not start register as there isn't valid IPv6 & IPv4 address,
            // it means login failed. Notify the result.
            if (isRelogin) {
                // If is re-login, it means already notify register success before.
                // So we'd like to notify as register logout here.
                registerLogout(NativeErrorCode.REG_TIMEOUT);
            } else {
                registerFailed();
            }
        }
    }

    private void registerSuccess(int stateCode) {
        // When register success, sync the UT items.
        if (mUtSyncMgr != null) mUtSyncMgr.sync(mSubId);
        // When register success, initialize the queried state.
        if (mUTMgr != null) mUTMgr.initState(mPhoneId);

        if (mCallMgr != null) {
            // As login success, we need set the video quality, and reset the rat type.
            mCallMgr.updateVideoQuality(Utilities.getDefaultVideoQuality(mPreferences));
            mCallMgr.resetCallRatType();
            mCallMgr.updateRequiredCNI(mRegisterMgr.getCurRequiredCNI());
        }

        mCmdRegisterState = CMD_STATE_FINISHED;
        if (mCallback != null) {
            mCallback.onRegisterStateChanged(RegisterState.STATE_CONNECTED, stateCode);
        }
    }

    private void registerFailed() {
        mCmdRegisterState = CMD_STATE_INVALID;

        if (mEcbmStep != ECBMRequest.ECBM_STEP_INVALID) {
            // Exit the ECBM.
            onSOSError(mEcbmStep);
        } else if (mCallback != null) {
            mCallback.onRegisterStateChanged(RegisterState.STATE_IDLE, 0);
        }

        if (mRegisterMgr != null) {
            // For sos manager, the register manger will be null.
            mRegisterMgr.forceStop(mRegisterListener);
        }
    }

    private void registerLogout(int stateCode) {
        mCmdRegisterState = CMD_STATE_INVALID;
        if (mCallback != null) {
            mCallback.onRegisterStateChanged(RegisterState.STATE_IDLE, 0);
            mCallback.onUnsolicitedUpdate(stateCode == NativeErrorCode.REG_TIMEOUT
                    ? UnsolicitedCode.SIP_TIMEOUT : UnsolicitedCode.SIP_LOGOUT);
        }

        if (mRegisterMgr != null) {
            mRegisterMgr.forceStop(mRegisterListener);
        }
        if (mSecurityMgr != null) {
            mSecurityMgr.forceStop(mSubId, mSecurityListener);
        }
    }

    protected void sendECBMTimeoutMsg(int forStep) {
        mHandler.removeMessages(MSG_ECBM_TIMEOUT);

        Message msg = mHandler.obtainMessage(MSG_ECBM_TIMEOUT);
        msg.arg1 = forStep;
        mHandler.sendMessageDelayed(msg, ECBM_TIMEOUT);
    }

    private void exitECBM() {
        if (mCallMgr != null) {
            mCallMgr.updateIncomingCallAction(IncomingCallAction.NORMAL);
        }

        if (getEcbmInterface() != null) {
            getEcbmInterface().updateEcbm(false, null);
        }

        mHandler.removeMessages(MSG_ECBM_TIMEOUT);
        mEcbmStep = ECBMRequest.ECBM_STEP_INVALID;
        mECBMRequest = null;
    }

    private class MyCallListener implements CallListener {
        @Override
        public void onCallIncoming(ImsCallSessionImpl callSession) {
            if (mCallback != null && callSession != null) {
                mCallback.onCallIncoming(callSession);
            }
        }

        @Override
        public void onCallEnd(ImsCallSessionImpl callSession) {
            if (mECBMRequest != null && mECBMRequest.getCallSession().equals(callSession)) {
                // It means the emergency call end.
                onSOSCallTerminated();
            }

            if (mCallback != null && mCallMgr.getCallCount() < 1) {
                Log.d(mTag, "The call[" + callSession + "] end, and there isn't any other call.");
                mCallback.onAllCallsEnd();

                // Check if need reset after all the calls end.
                if (mNeedLogoutAfterCall) {
                    mNeedLogoutAfterCall = false;
                    // Notify as register logout.
                    registerLogout(0);
                }
            }
        }

        @Override
        public void onCallRTPReceived(boolean isVideoCall, boolean isReceived) {
            if (mCallback != null) {
                if (isReceived) {
                    mCallback.onRtpReceived(isVideoCall);
                } else {
                    mCallback.onNoRtpReceived(isVideoCall);
                }
            }
        }

        @Override
        public void onCallRTCPChanged(boolean isVideoCall, int lose, int jitter, int rtt) {
            if (mCallback != null) {
                mCallback.onMediaQualityChanged(isVideoCall, lose, jitter, rtt);
            }
        }

        @Override
        public void onAliveCallUpdate(boolean isVideo) {
            if (mCallback != null) {
                mCallback.onAliveCallUpdate(isVideo);
            }
        }

        @Override
        public void onEnterECBM(ECBMRequest request) {
            Log.d(mTag, "Enter ECBM for the emergency call: " + request.getCallSession());

            ImsEcbmImpl imsEcbm = getEcbmInterface();
            if (imsEcbm != null) {
                mECBMRequest = request;
                mHandler.sendEmptyMessage(MSG_ENTER_ECBM);
            } else {
                Log.w(mTag, "Need enter ECBM, but imsEcbm is null.");
            }
        }

        @Override
        public void onExitECBM() {
            ImsEcbmImpl imsEcbm = getEcbmInterface();
            if (imsEcbm != null && imsEcbm.isEcbm()) {
                Log.d(mTag, "Exit ECBM.");
                mHandler.sendEmptyMessage(MSG_EXIT_ECBM);
            } else {
                Log.w(mTag, "Exit ECBM: But not in Ecbm.");
            }
        }

        @Override
        public void onSRVCCFinished(boolean isSuccess) {
            if (isSuccess) {
                mHandler.sendEmptyMessageDelayed(MSG_SRVCC_SUCCESS, 5 * 1000);
            }
        }

        @Override
        public void onUnsolicitedRequest(int unsolicitedCode) {
            if (mCallback != null) {
                mCallback.onUnsolicitedUpdate(unsolicitedCode);
            }
        }
    }

    private class MyRegisterListener implements RegisterListener {
        @Override
        public void onLoginFinished(boolean success, int stateCode, int retryAfter) {
            if (success) {
                // If login success or get retry-after, notify the register state changed.
                if (success && mEcbmStep != ECBMRequest.ECBM_STEP_INVALID) {
                    if (mEcbmStep == ECBMRequest.ECBM_STEP_REGISTER_SOS
                            || mEcbmStep == ECBMRequest.ECBM_STEP_REGISTER_NORMAL) {
                        mHandler.removeMessages(MSG_ECBM_TIMEOUT);

                        Message msg = mHandler.obtainMessage(MSG_ECBM);
                        msg.arg1 = mECBMRequest.getNextStep();
                        mHandler.sendMessage(msg);
                    } else {
                        // Shouldn't be here.
                        Log.e(mTag, "[onLoginFinished] Shouldn't be here, please check! "
                                + "The ECBM step: " + mEcbmStep);
                    }
                } else {
                    registerSuccess(stateCode);
                }
            } else if (!success && stateCode == NativeErrorCode.REG_SERVER_FORBIDDEN) {
                // If failed caused by server forbidden, set register failed.
                Log.e(mTag, "Login failed as server forbidden. state code: " + stateCode);
                registerFailed();
            } else {
                // As the PCSCF address may be not only one. For example, there are two IPv6
                // addresses and two IPv4 addresses. So we will try to login again.
                Log.d(mTag, "Last login action is failed, try to use exist address to login again");
                registerLogin(false);
            }
        }

        @Override
        public void onLogout(int stateCode) {
            if (mResetStep != RESET_STEP_INVALID) {
                Log.d(mTag, "Get the register state changed to logout for reset action.");
                if (mResetStep == RESET_STEP_DEREGISTER) {
                    // Register state changed caused by reset. Update the step and send the message.
                    Message msg = mHandler.obtainMessage(MSG_RESET);
                    msg.arg1 = RESET_STEP_DEATTACH;
                    mHandler.sendMessage(msg);
                } else {
                    // Shouldn't be here, do nothing, ignore this event.
                    Log.w(mTag, "Do nothing for reset action as reset step is: " + mResetStep);
                }
            } else if (mEcbmStep != ECBMRequest.ECBM_STEP_INVALID) {
                Log.d(mTag, "Get the register state changed to logout in ECBM.");
                if (mEcbmStep == ECBMRequest.ECBM_STEP_DEREGISTER_NORMAL) {
                    int nextStep = mECBMRequest.getNextStep();
                    sendECBMTimeoutMsg(nextStep);
                    Message msg = mHandler.obtainMessage(MSG_ECBM);
                    msg.arg1 = nextStep;
                    mHandler.sendMessage(msg);
                } else {
                    // Normal, shouldn't be here.
                    // If logout from the IMS service, but in ECBM, we'd like to handle it as
                    // emergency call failed. And notify this register state changed.
                    Log.w(mTag, "Logout from IMS service, but current ecbm step is: " + mEcbmStep);

                    // Exit the ECBM.
                    exitECBM();
                    registerLogout(stateCode);
                }
            } else {
                registerLogout(stateCode);
            }
        }

        @Override
        public void onPrepareFinished(boolean success, boolean isResetFailed) {
            if (success) {
                // As prepare success, start the login process now.
                registerLogin(false);
            } else {
                if (isResetFailed) {
                    Log.w(mTag, "Prepare login failed caused by reset failed, native will reset.");
                    onNativeReset();
                }
                // Prepare failed, give the register result as failed.
                registerFailed();
            }
        }

        @Override
        public void onRefreshRegFinished(boolean success, int errorCode) {
            if (success) {
                if (mCallback != null) mCallback.onReregisterFinished(success, errorCode);
            } else {
                int callCount = mCallMgr.getCallCount();
                if (callCount > 0) {
                    // It means there is call, and we'd like to notify as register logout after
                    // all the call ends.
                    mNeedLogoutAfterCall = true;
                } else {
                    // There isn't any calls. As get the re-register failed, we'd like to notify
                    // as register logout now.
                    errorCode = (errorCode == NativeErrorCode.REG_EXPIRED_TIMEOUT)
                            ? NativeErrorCode.REG_TIMEOUT : errorCode;
                    registerLogout(errorCode);
                }
            }
        }

        @Override
        public void onRegisterStateChanged(int newState, int errorCode) {
            // If the register state changed, update the register state to
            // call manager & ut manager.
            if (mCallMgr != null) mCallMgr.updateRegisterState(newState);
            if (mSmsMgr != null) mSmsMgr.updateRegisterState(newState);
            if (mUTMgr != null) {
                RegisterConfig regConfig = mRegisterMgr.getCurRegisterConfig();
                int ipVersion = IPVersion.NONE;
                if (regConfig != null && newState == RegisterState.STATE_CONNECTED) {
                    ipVersion = regConfig.isCurUsedIPv4() ? IPVersion.IP_V4 : IPVersion.IP_V6;
                }
                mUTMgr.updateRegisterState(newState, ipVersion);
            }

            if (errorCode == NativeErrorCode.SERVER_TIMEOUT) {
                // If register state changed caused by server return 504 when UE is calling,
                // need to re-login.
                Log.d(mTag, "Re-login as UE send invite and server response 504, stateCode: "
                        + errorCode);
                registerLogin(true /* re-login */);
            }
        }

        @Override
        public void onResetBlocked() {
            // As the reset blocked, and the vowifi service will be kill soon,
            // We'd like to notify as reset finished if in reset step.
            if (mResetStep != RESET_STEP_INVALID) {
                Log.d(mTag, "Reset blocked, and the current reset step is " + mResetStep);
                resetFinished();
            }

            Log.w(mTag, "Reset blocked, native will reset.");
            onNativeReset();
        }

        @Override
        public void onDisconnected() {
            Log.d(mTag, "Register service disconnected, and current register cmd state is: "
                    + mCmdRegisterState);

            if (mCallback != null) {
                switch (mCmdRegisterState) {
                    case CMD_STATE_INVALID:
                        // Do nothing as there isn't any register command.
                        break;
                    case CMD_STATE_FINISHED:
                        mCallback.onUnsolicitedUpdate(UnsolicitedCode.SIP_LOGOUT);
                        // And same as in progress, need notify the register state change to idle.
                        // Needn't break here.
                    case CMD_STATE_PROGRESS:
                        mCallback.onRegisterStateChanged(RegisterState.STATE_IDLE, 0);
                        break;
                }
            }
            mCmdRegisterState = CMD_STATE_INVALID;
        }

    }

    private class MySecurityListener implements SecurityListener {
        @Override
        public void onProgress(int state) {
            if (mCallback != null) mCallback.onAttachStateChanged(state);
        }

        @Override
        public void onSuccessed(int sessionId) {
            if (mEcbmStep != ECBMRequest.ECBM_STEP_INVALID) {
                if (mEcbmStep == ECBMRequest.ECBM_STEP_ATTACH_SOS
                        || mEcbmStep == ECBMRequest.ECBM_STEP_ATTACH_NORMAL) {
                    int nextStep = mECBMRequest.getNextStep();
                    sendECBMTimeoutMsg(nextStep);
                    Message msg = mHandler.obtainMessage(MSG_ECBM);
                    msg.arg1 = nextStep;
                    mHandler.sendMessage(msg);
                } else {
                    // Shouldn't be here.
                    Log.e(mTag, "[onSuccessed] Shouldn't be here, please check! The ECBM step: "
                            + mEcbmStep);
                }
            } else if (mCallback != null) {
                SecurityConfig config = mSecurityMgr.getConfig(sessionId);
                String usedPcscfAddr = getUsedPcscfAddr();
                if (config != null
                        && (TextUtils.isEmpty(config._ip4) || TextUtils.isEmpty(config._pcscf4))
                        && (TextUtils.isEmpty(config._ip6) || TextUtils.isEmpty(config._pcscf6))
                        && TextUtils.isEmpty(usedPcscfAddr)) {
                    // Handle as attach failed.
                    Log.d(mTag, "Handle as attach failed, localIPv4: " + config._ip4
                            + ", localIPv6: " + config._ip6 + ", pcscfIPv4: " + config._pcscf4
                            + ", pcscfIPv6: " + config._pcscf6 + ", usedPcscfAddr: "
                            + usedPcscfAddr);
                    mCmdAttachState = CMD_STATE_INVALID;
                    mCallback.onAttachFinished(false, 0);
                } else {
                    if (mCallMgr != null) mCallMgr.updateAttachSessionId(sessionId);

                    mCmdAttachState = CMD_STATE_FINISHED;
                    mCallback.onAttachFinished(true, 0);
                }
            }
        }

        @Override
        public void onFailed(int reason) {
            if (mEcbmStep != ECBMRequest.ECBM_STEP_INVALID) {
                // Failed in ECBM. Exit the ECBM, and notify the state.
                onSOSError(mEcbmStep);
                exitECBM();
                if (mCallback != null) {
                    mCallback.onAttachStopped(0);
                    mCallback.onUnsolicitedUpdate(UnsolicitedCode.SECURITY_STOP);
                }
            } else if (mCallback != null) {
                mCmdAttachState = CMD_STATE_INVALID;
                mCallback.onAttachFinished(false, reason);
                if (mResetStep != RESET_STEP_INVALID) {
                    Log.d(mTag, "Attached failed but in reset process. It means reset finished.");
                    resetFinished();
                }
            }
        }

        @Override
        public void onStopped(boolean forHandover, int errorCode) {
            // As attach stopped, and if the platform do not support VOLTE, we'd like to
            // update the used local address to null.
            if (!ImsManager.isVolteEnabledByPlatform(mContext)) {
                Log.d(mTag, "The platform do not support VOLTE, set the local addr to null.");
                setUsedLocalAddr(null);
            }

            if (!forHandover && mResetStep != RESET_STEP_INVALID) {
                // If the stop action is for reset, we will notify the reset finished result.
                if (mResetStep == RESET_STEP_DEREGISTER) {
                    // It means the de-register do not finished. We'd like to force stop register.
                    registerForceStop();
                }
                Log.d(mTag, "S2b stopped, it means the reset action finished. Notify the result.");
                // In reset process, and reset finished, we need reset the attach state to idle.
                mCmdAttachState = CMD_STATE_INVALID;
                resetFinished();
            } else if (!forHandover && mEcbmStep != ECBMRequest.ECBM_STEP_INVALID) {
                if (mEcbmStep == ECBMRequest.ECBM_STEP_DEATTACH_SOS
                        || mEcbmStep == ECBMRequest.ECBM_STEP_DEATTACH_NORMAL) {
                    boolean handle = false;
                    if (mEcbmStep == ECBMRequest.ECBM_STEP_DEATTACH_SOS) {
                        // It means SOS process finished.
                        handle = onSOSProcessFinished();
                    }

                    if (handle) {
                        mHandler.removeMessages(MSG_ECBM_TIMEOUT);
                    } else {
                        int nextStep = mECBMRequest.getNextStep();
                        sendECBMTimeoutMsg(nextStep);

                        Message msg = mHandler.obtainMessage(MSG_ECBM);
                        msg.arg1 = nextStep;
                        mHandler.sendMessage(msg);
                    }
                } else {
                    // Normal, shouldn't be here.
                    // If stopped connect to EPDG, but in ECBM, we'd like to handle it as
                    // emergency call failed. And notify this register state changed.
                    exitECBM();
                    if (mCallback != null) {
                        mCallback.onAttachStopped(0);
                        mCallback.onUnsolicitedUpdate(UnsolicitedCode.SECURITY_STOP);
                    }
                }
            } else if (mCallback != null) {
                mCmdAttachState = CMD_STATE_INVALID;
                mCallback.onAttachStopped(errorCode);
                if (errorCode == NativeErrorCode.DPD_DISCONNECT) {
                    mCallback.onUnsolicitedUpdate(UnsolicitedCode.SECURITY_DPD_DISCONNECTED);
                } else if (errorCode == NativeErrorCode.IPSEC_REKEY_FAIL
                        || errorCode == NativeErrorCode.IKE_REKEY_FAIL) {
                    mCallback.onUnsolicitedUpdate(UnsolicitedCode.SECURITY_REKEY_FAILED);
                } else {
                    mCallback.onUnsolicitedUpdate(UnsolicitedCode.SECURITY_STOP);
                }
            }
        }

        @Override
        public void onDisconnected() {
            Log.d(mTag, "Security service disconnected, and current attach cmd state is: "
                    + mCmdAttachState);

            // If the service disconnect, security and register will get this callback.
            // We'd like to handle the reset finished only once.
            if (mResetStep != RESET_STEP_INVALID) {
                Log.d(mTag, "Service disconnect, and the current reset step is " + mResetStep);
                resetFinished();
            }

            if (mCallback != null) {
                switch (mCmdAttachState) {
                    case CMD_STATE_INVALID:
                        // There isn't attach cmd, do nothing.
                        break;
                    case CMD_STATE_PROGRESS:
                        mCallback.onAttachFinished(false, 0);
                        break;
                    case CMD_STATE_FINISHED:
                        mCallback.onAttachStopped(0);
                        mCallback.onUnsolicitedUpdate(UnsolicitedCode.SECURITY_STOP);
                        break;
                }
            }
            mCmdAttachState = CMD_STATE_INVALID;
        }
    }

    private class MySOSListener implements SOSListener {

        @Override
        public void onSOSCallTerminated() {
            // As sos call already terminated, we need check the normal call if exist.
            if (mCallMgr.getCallCount() < 1 && mCallback != null) {
                mCallback.onAllCallsEnd();
            }
        }

        @Override
        public void onSOSRequestFinished() {
            // As sos request finished, we'd like to handle the next step.
            int nextStep = mECBMRequest.getNextStep();
            sendECBMTimeoutMsg(nextStep);

            Message msg = mHandler.obtainMessage(MSG_ECBM);
            msg.arg1 = nextStep;
            mHandler.sendMessage(msg);
        }

        @Override
        public void onSOSRequestError() {
            // As sos request error, exit ECBM, and reset all the stack.
            exitECBM();
            resetAll(WifiState.DISCONNECTED);

            // Notify as SOS failed.
            if (mCallback != null) {
                mCallback.onUnsolicitedUpdate(UnsolicitedCode.SOS_FAILED);
            }
        }
    }

    public enum IncomingCallAction {
        NORMAL, REJECT
    }

    public enum WifiState {
        CONNECTED, DISCONNECTED
    }

    public enum CallRatState {
        CALL_VOLTE, CALL_VOWIFI, CALL_NONE
    }

    public interface VoWifiCallback {
        public void onAttachFinished(boolean success, int errorCode);

        public void onAttachStopped(int stoppedReason);

        public void onAttachStateChanged(int state);

        public void onDPDDisconnected();

        public void onRegisterStateChanged(int state, int stateCode);

        public void onReregisterFinished(boolean isSuccess, int errorCode);

        public void  onResetStarted();

        /**
         * release result callback
         *
         * @param result
         * @param errorCode
         */
        public void onResetFinished(int result, int errorCode);

        public void onUpdateDRStateFinished();

        /**
         * Call callback
         *
         * @param callId The only identity of the phone
         * @param type See the definition of ImsCallProfile
         */
        public void onCallIncoming(IImsCallSession callSession);

        public void onAliveCallUpdate(boolean isVideoCall);

        /**
         * Call this interface after all calls end.
         */
        public void onAllCallsEnd();

        /**
         * Media quality callback
         *
         * @param lose:packet loss rate(Molecular value in percentage)
         * @param jitter:jitter(millisecond)
         * @param rtt:delay(millisecond)
         */
        public void onMediaQualityChanged(boolean isVideo, int lose, int jitter, int rtt);

        /**
         * 5 seconds no RTP package callback
         *
         * @param video:Whether it is video
         */
        public void onNoRtpReceived(boolean isVideo);

        public void onRtpReceived(boolean isVideo);

        public void onUnsolicitedUpdate(int stateCode);
    }

}
