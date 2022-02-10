
package com.spreadtrum.ims;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.os.RemoteException;
import android.telephony.ServiceState;
import android.telephony.RadioAccessFamily;
import android.telephony.TelephonyManager;
import android.telephony.PhoneStateListener;
import android.telephony.VoLteServiceState;
import android.telephony.CarrierConfigManager;
import android.telephony.CarrierConfigManagerEx;
import android.telephony.SubscriptionManager;
import android.telephony.ims.feature.ImsFeature;
import android.telephony.ims.feature.MmTelFeature;
import android.util.Log;
import android.util.SparseArray;
import android.widget.Toast;
import android.telecom.VideoProfile;
import android.provider.Settings;

import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneFactory;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.CallManager;
import com.android.internal.telephony.Call;

import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.telephony.IccCardConstants;
import android.telephony.PhoneNumberUtils;


import com.android.ims.ImsException;
import com.android.ims.ImsManager;
import com.android.ims.ImsServiceClass;
import com.android.ims.ImsConfig;
import com.android.ims.internal.IImsCallSession;
import android.telephony.ims.aidl.IImsCallSessionListener;
import com.android.ims.internal.IImsRegistrationListener;
import com.android.ims.internal.IImsEcbm;
import com.android.ims.internal.IImsService;
import com.android.ims.internal.IImsUt;
import com.android.ims.internal.IImsUtEx;
import com.android.ims.internal.IImsServiceEx;
import com.android.ims.internal.IImsPdnStateListener;
import com.android.ims.internal.IImsServiceListenerEx;
import com.android.ims.internal.IImsRegisterListener;
import com.android.ims.internal.IImsUtListenerEx;
import com.android.ims.internal.ImsManagerEx;
import com.android.ims.internal.IImsMultiEndpoint;
import com.android.ims.internal.IImsExternalCallStateListener;
import com.android.ims.internal.ImsSrvccCallInfo;
import com.android.ims.internal.IImsFeatureStatusCallback;

import android.telephony.ims.ImsCallProfile;
import android.telephony.ims.ImsCallSession;
import android.telephony.ims.ImsCallForwardInfo;
import android.telephony.ims.ImsConferenceState;
import android.telephony.ims.ImsReasonInfo;
import android.telephony.ims.ImsSsData;
import android.telephony.ims.ImsStreamMediaProfile;
import android.telephony.ims.ImsSuppServiceNotification;
import android.telephony.ims.ImsVideoCallProvider;
import android.telephony.ims.aidl.IImsConfig;
import android.telephony.ims.aidl.IImsConfigCallback;
import android.telephony.ims.aidl.IImsMmTelFeature;
import android.telephony.ims.aidl.IImsRcsFeature;
import android.telephony.ims.aidl.IImsRegistration;
import android.telephony.ims.aidl.IImsRegistrationCallback;
import android.telephony.ims.aidl.IImsServiceController;
import android.telephony.ims.aidl.IImsSmsListener;
import android.telephony.ims.stub.ImsFeatureConfiguration;
import android.telephony.ims.aidl.IImsServiceControllerListener;

import com.spreadtrum.ims.ImsCallSessionImpl.Listener;
import com.spreadtrum.ims.vt.VTManagerProxy;
import com.spreadtrum.ims.vowifi.Utilities.RegisterState;
import com.spreadtrum.ims.vowifi.Utilities.SecurityConfig;
import com.spreadtrum.ims.vowifi.VoWifiConfiguration;
import com.spreadtrum.ims.vowifi.VoWifiServiceImpl;
import com.spreadtrum.ims.vowifi.VoWifiServiceImpl.CallRatState;
import com.spreadtrum.ims.vowifi.VoWifiServiceImpl.IncomingCallAction;
import com.spreadtrum.ims.vowifi.VoWifiServiceImpl.VoWifiCallback;
import com.spreadtrum.ims.vowifi.VoWifiServiceImpl.WifiState;
import com.spreadtrum.ims.ut.ImsUtImpl;
import com.spreadtrum.ims.ut.ImsUtProxy;
import static android.Manifest.permission.MODIFY_PHONE_STATE;
import static android.Manifest.permission.READ_PHONE_STATE;
import static android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE;
import android.app.NotificationChannel;

public class ImsService extends Service {
    private static final String TAG = ImsService.class.getSimpleName();
    /** IMS registered state code. */
    public static final int IMS_REG_STATE_INACTIVE = 0;
    public static final int IMS_REG_STATE_REGISTERED = 1;
    public static final int IMS_REG_STATE_REGISTERING = 2;
    public static final int IMS_REG_STATE_REG_FAIL = 3;
    public static final int IMS_REG_STATE_UNKNOWN = 4;
    public static final int IMS_REG_STATE_ROAMING = 5;
    public static final int IMS_REG_STATE_DEREGISTERING = 6;
    /** IMS service code. */
    private static final int ACTION_SWITCH_IMS_FEATURE = 100;
    private static final int ACTION_START_HANDOVER = 101;
    private static final int ACTION_NOTIFY_NETWORK_UNAVAILABLE = 102;
    private static final int ACTION_NOTIFY_VOWIFI_UNAVAILABLE = 103;
    private static final int ACTION_CANCEL_CURRENT_REQUEST = 104;
    private static final int ACTION_RELEASE_WIFI_RESOURCE = 105;
    private static final int ACTION_NOTIFY_VIDEO_CAPABILITY_CHANGE = 106;

    /** WIFI service event. */
    private static final int EVENT_WIFI_ATTACH_STATE_UPDATE = 200;
    private static final int EVENT_WIFI_ATTACH_SUCCESSED = 201;
    private static final int EVENT_WIFI_ATTACH_FAILED = 202;
    private static final int EVENT_WIFI_ATTACH_STOPED = 203;
    private static final int EVENT_WIFI_INCOMING_CALL = 204;
    private static final int EVENT_WIFI_ALL_CALLS_END = 205;
    private static final int EVENT_WIFI_REFRESH_RESAULT = 206;
    private static final int EVENT_WIFI_REGISTER_RESAULT = 207;
    private static final int EVENT_WIFI_RESET_RESAULT = 208;
    private static final int EVENT_WIFI_DPD_DISCONNECTED = 209;
    private static final int EVENT_WIFI_NO_RTP = 210;
    private static final int EVENT_WIFI_UNSOL_UPDATE = 211;
    private static final int EVENT_WIFI_RTP_RECEIVED = 212;
    private static final int EVENT_UPDATE_DATA_ROUTER_FINISHED = 213;
    private static final int EVENT_NOTIFY_CP_VOWIFI_ATTACH_SUCCESSED = 214;
    private static final int EVENT_WIFI_RESET_START  = 215;  // UNISOC: Add for bug1007100
    private static final int SUB_PROPERTY_NOT_INITIALIZED = -1; //UNISOC:add for bug1126104

    static class ImsOperationType {
        public static final int IMS_OPERATION_SWITCH_TO_VOWIFI = 0;
        public static final int IMS_OPERATION_SWITCH_TO_VOLTE = 1;
        public static final int IMS_OPERATION_HANDOVER_TO_VOWIFI = 2;
        public static final int IMS_OPERATION_HANDOVER_TO_VOLTE = 3;
        public static final int IMS_OPERATION_SET_VOWIFI_UNAVAILABLE = 4;
        public static final int IMS_OPERATION_CANCEL_CURRENT_REQUEST = 5;
        public static final int IMS_OPERATION_CP_REJECT_SWITCH_TO_VOWIFI = 6;
        public static final int IMS_OPERATION_CP_REJECT_HANDOVER_TO_VOWIFI = 7;
        public static final int IMS_OPERATION_RELEASE_WIFI_RESOURCE = 8;
    }

    public static final int IMS_HANDOVER_ACTION_CONFIRMED = 999;

    public static final int IMS_INVALID_SERVICE_ID = -1;  // UNISOC: Add for bug950573

    static class ImsHandoverType {
        public static final int IDEL_HANDOVER_TO_VOWIFI = 1;
        public static final int IDEL_HANDOVER_TO_VOLTE = 2;
        public static final int INCALL_HANDOVER_TO_VOWIFI = 3;
        public static final int INCALL_HANDOVER_TO_VOLTE = 4;
    }

    static class ImsPDNStatus {
        public static final int IMS_PDN_ACTIVE_FAILED = 0;
        public static final int IMS_PDN_READY = 1;
        public static final int IMS_PDN_START = 2;
    }

    static class ImsHandoverResult {
        public static final int IMS_HANDOVER_REGISTER_FAIL = 0;
        public static final int IMS_HANDOVER_SUCCESS = 1;
        public static final int IMS_HANDOVER_PDN_BUILD_FAIL = 2;
        public static final int IMS_HANDOVER_RE_REGISTER_FAIL = 3;
        public static final int IMS_HANDOVER_ATTACH_FAIL = 4;
        public static final int IMS_HANDOVER_ATTACH_SUCCESS = 5;
        public static final int IMS_HANDOVER_SRVCC_FAILED = 6;
    }

    /** Call end event. */
    public static class CallEndEvent {
        public static final int INVALID_CALL_END = -1;
        public static final int WIFI_CALL_END = 1;
        public static final int VOLTE_CALL_END = 2;
    }

    public static class UnsolicitedCode {
        public static final int SECURITY_DPD_DISCONNECTED = 1;
        public static final int SIP_TIMEOUT = 2;
        public static final int SIP_LOGOUT = 3;
        public static final int SECURITY_REKEY_FAILED = 4;
        public static final int SECURITY_STOP = 5;
    }

    /** Call type. */
    public static class CallType {
        public static final int NO_CALL = -1;
        public static final int VOLTE_CALL = 0;
        public static final int WIFI_CALL = 2;
    }

    /** S2b event code. */
    public static class S2bEventCode {
        public static final int S2b_STATE_IDLE = 0;
        public static final int S2b_STATE_PROGRESS = 1;
        public static final int S2b_STATE_CONNECTED = 2;
    }

    public static class ImsStackResetResult {
        public static final int INVALID_ID = -1;
        public static final int FAIL = 0;
        public static final int SUCCESS = 1;
    }

    /**
     * AndroidP start@{:
     */
    private IImsServiceControllerListener mIImsServiceControllerListener;
    /* AndroidP end@} */

    // Only add for cmcc test, if this prop is FALSE, we needn't s2b function.
    // TODO: Need remove this after development.
    private static final String PROP_S2B_ENABLED = "persist.sys.s2b.enabled";

    private Map<Integer, ImsServiceImpl> mImsServiceImplMap = new HashMap<Integer, ImsServiceImpl>();

    private ConcurrentHashMap<IBinder, IImsRegisterListener> mImsRegisterListeners = new ConcurrentHashMap<IBinder, IImsRegisterListener>();

    private int mRequestId = -1;
    private Object mRequestLock = new Object();
    private IImsServiceListenerEx mImsServiceListenerEx;

    private int mInCallHandoverFeature = ImsConfig.FeatureConstants.FEATURE_TYPE_UNKNOWN;
    private TelephonyManager mTelephonyManager;
    // add for Dual LTE
    private PhoneStateListener mPhoneStateListener;
    private int mPhoneCount = 2;
    private ImsServiceRequest mFeatureSwitchRequest;
    private ImsServiceRequest mReleaseVowifiRequest;
    private VoWifiServiceImpl mWifiService;
    private MyVoWifiCallback mVoWifiCallback;
    private VoLTERegisterListener mVoLTERegisterListener = new VoLTERegisterListener();
    private boolean mWifiRegistered = false;
    private boolean mVolteAvailable = false;// SPRD:Add for bug596304,bug1065583, either SIM is volte registered and radioType support VoPS
    private boolean mIsWifiCalling = false;
    private boolean mIsCalling = false;
    private NotificationManager mNotificationManager;
    private String mVowifiRegisterMsg = "Wifi not register";
    private int mCurrentVowifiNotification = 100;
    private boolean mIsPendingRegisterVowifi;
    private boolean mIsPendingRegisterVolte;
    private boolean mIsVowifiCall;// This means call is started by Vowifi.
    private boolean mIsVolteCall;// This means call is started by Volte.
    private int mNetworkType = -1;
    private String mNetworkInfo = "Network info is null";
    private boolean mPendingAttachVowifiSuccess = false;// SPRD:Add for
                                                        // bug595321
    private boolean mPendingVowifiHandoverVowifiSuccess = false;// SPRD:Add for
                                                                // bug595321
    private boolean mPendingVolteHandoverVolteSuccess = false;
    private boolean mPendingActivePdnSuccess = false;// SPRD:Add for bug595321
    private boolean mAttachVowifiSuccess = false;// SPRD:Add for bug604833
    private boolean mPendingReregister = false;
    private boolean mIsS2bStopped = false;
    private boolean mIsCPImsPdnActived = false;
    private boolean mIsAPImsPdnActived = false;
    private boolean mIsLoggingIn = false;
    private boolean mPendingCPSelfManagement = false;
    private int mCallEndType = CallEndEvent.INVALID_CALL_END;
    private int mInCallPhoneId = -1;
    private NotificationChannel mVowifiChannel;
    private boolean mVowifiNotificationShown = false; // UNISOC: add for bug1153427
    private int mMakeCallPrimaryCardServiceId = -1;
    private int mVowifiAttachedServiceId = IMS_INVALID_SERVICE_ID; // UNISOC: Add for bug950573

    private boolean mIsEmergencyCallonIms = false;//UNisoc: add for bug941037
    private String  mVoWifiLocalAddr = ""; // UNISOC: Add for bug1008539
    private int mWifiState = -1; // UNISOC: Add for bug1285106
    private static class ImsServiceRequest {
        public int mRequestId;
        public int mEventCode;
        public int mServiceId;
        public int mTargetType;

        public ImsServiceRequest(int requestId, int eventCode, int serviceId,
                int targetType) {
            mRequestId = requestId;
            mEventCode = eventCode;
            mServiceId = serviceId;
            mTargetType = targetType;
        }

        @Override
        public String toString() {
            return "ImsServiceRequest->mRequestId:" + mRequestId
                    + " mEventCode:" + mEventCode + " mServiceId:" + mServiceId
                    + " mTargetType:" + mTargetType;
        }
    }

    /**
     * Used to listen to events.
     */
    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            try {
                switch (msg.what) {
                    case ACTION_SWITCH_IMS_FEATURE:
                        if (mFeatureSwitchRequest != null) {
                            if (mImsServiceListenerEx != null) {
                                mImsServiceListenerEx
                                        .operationFailed(
                                                msg.arg1,
                                                "Repetitive operation",
                                                (msg.arg2 == ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_LTE) ? ImsOperationType.IMS_OPERATION_SWITCH_TO_VOLTE
                                                        : ImsOperationType.IMS_OPERATION_SWITCH_TO_VOWIFI);
                            }
                            Log.w(TAG,
                                    "ACTION_SWITCH_IMS_FEATURE-> mFeatureSwitchRequest is exist!");
                        } else {
                            onReceiveHandoverEvent(false, msg.arg1/* requestId */,
                                    msg.arg2/* targetType */);
                        }
                        Log.i(TAG,
                                "ACTION_SWITCH_IMS_FEATURE->mFeatureSwitchRequest:"
                                        + mFeatureSwitchRequest
                                        + " mAttachVowifiSuccess:"
                                        + mAttachVowifiSuccess);
                        break;
                    case ACTION_START_HANDOVER:
                        Log.i(TAG,
                                "ACTION_START_HANDOVER->mIsPendingRegisterVowifi: "
                                        + mIsPendingRegisterVowifi
                                        + " mIsPendingRegisterVolte: "
                                        + mIsPendingRegisterVolte
                                        + " mAttachVowifiSuccess:"
                                        + mAttachVowifiSuccess);
                        if (mIsPendingRegisterVowifi) {
                            mIsPendingRegisterVowifi = false;
                            mFeatureSwitchRequest = null;
                        } else if (mIsPendingRegisterVolte) {
                            mIsPendingRegisterVolte = false;
                            mFeatureSwitchRequest = null;
                        }
                        if (mFeatureSwitchRequest != null) {
                            if (mImsServiceListenerEx != null) {
                                mImsServiceListenerEx
                                        .operationFailed(
                                                msg.arg1,
                                                "Already handle one request.",
                                                (msg.arg2 == ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_LTE) ? ImsOperationType.IMS_OPERATION_HANDOVER_TO_VOLTE
                                                        : ImsOperationType.IMS_OPERATION_HANDOVER_TO_VOWIFI);
                            }
                            Log.w(TAG,
                                    "ACTION_START_HANDOVER-> mFeatureSwitchRequest is exist!");
                        } else {
                            onReceiveHandoverEvent(true, msg.arg1/* requestId */,
                                    msg.arg2/* targetType */);
                        }
                        break;
                    case ACTION_NOTIFY_NETWORK_UNAVAILABLE:
                        break;
                    case EVENT_WIFI_ATTACH_STATE_UPDATE:
                        int state = msg.arg1;
                        Log.i(TAG, "EVENT_WIFI_ATTACH_STATE_UPDATE-> state:"
                                + state + ", mWifiRegistered:" + mWifiRegistered
                                + ", mIsS2bStopped:" + mIsS2bStopped);
                        if (state != S2bEventCode.S2b_STATE_CONNECTED) {
                            mAttachVowifiSuccess = false;// SPRD:Add for bug604833
                        }
                        if (state != S2bEventCode.S2b_STATE_IDLE) {
                            mIsS2bStopped = false;
                        }
                        break;
                    case EVENT_WIFI_ATTACH_SUCCESSED:
                        Log.i(TAG,
                                "EVENT_WIFI_ATTACH_SUCCESSED-> mFeatureSwitchRequest:"
                                        + mFeatureSwitchRequest + " mIsCalling:"
                                        + mIsCalling
                                        + " mPendingAttachVowifiSuccess:"
                                        + mPendingAttachVowifiSuccess
                                        + " mPendingVowifiHandoverVowifiSuccess:"
                                        + mPendingVowifiHandoverVowifiSuccess
                                        + " mIsS2bStopped" + mIsS2bStopped
                                        + " mAttachVowifiSuccess:"
                                        + mAttachVowifiSuccess);
                        if (mFeatureSwitchRequest != null) {
                            notifyCPVowifiAttachSucceed();
                            mVowifiAttachedServiceId = mFeatureSwitchRequest.mServiceId;    //UNISOC:add for bug950573

                            if (mFeatureSwitchRequest.mEventCode == ACTION_START_HANDOVER) {
                                /* SPRD: Modify for bug595321 and 610503{@ */
                                if (mIsCalling) {
                                    mInCallHandoverFeature = ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_WIFI;
                                    updateImsFeature(mFeatureSwitchRequest.mServiceId);
                                    mWifiService.updateCallRatState(CallRatState.CALL_VOWIFI);
                                    mIsPendingRegisterVowifi = true;
                                    if (mImsServiceListenerEx != null) {
                                        Log.i(TAG,
                                                "EVENT_WIFI_ATTACH_SUCCESSED -> operationSuccessed -> IMS_OPERATION_HANDOVER_TO_VOWIFI");
                                        mImsServiceListenerEx
                                                .operationSuccessed(
                                                        mFeatureSwitchRequest.mRequestId,
                                                        ImsOperationType.IMS_OPERATION_HANDOVER_TO_VOWIFI);
                                    }
                                    if (mIsVolteCall) {
                                        ImsServiceImpl service = mImsServiceImplMap.get(
                                                Integer.valueOf(mFeatureSwitchRequest.mServiceId));
                                        if (service != null) {
                                            service.enableWiFiParamReport();
                                        }
                                        setAliveVolteCallType(ImsCallProfile.getCallTypeFromVideoState(mImsServiceExBinder.getCurrentImsVideoState()));
                                    }
                                } else {
                                    mInCallHandoverFeature = ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_WIFI;  // UNISOC: Add for bug950573
                                    updateImsFeature(mFeatureSwitchRequest.mServiceId);
                                    if (mImsServiceListenerEx != null) {
                                        Log.i(TAG,
                                                "EVENT_WIFI_ATTACH_SUCCESSED -> operationSuccessed -> IMS_OPERATION_HANDOVER_TO_VOWIFI");
                                        mImsServiceListenerEx
                                                .operationSuccessed(
                                                        mFeatureSwitchRequest.mRequestId,
                                                        ImsOperationType.IMS_OPERATION_HANDOVER_TO_VOWIFI);
                                    }
                                    /* UNISOC: modify for bug978846 @{ */
                                    if (mWifiService != null && !mWifiRegistered) {
                                        mWifiService.register();
                                        mIsLoggingIn = true;  // SPRD:Add for bug950573
                                        mFeatureSwitchRequest = null;
                                    }
                                    /*@}*/
                                    mInCallHandoverFeature = ImsConfig.FeatureConstants.FEATURE_TYPE_UNKNOWN;  // UNISOC: Add for bug950573
                                    mPendingAttachVowifiSuccess = false;
                                    if (mWifiRegistered) {
                                        mPendingVowifiHandoverVowifiSuccess = true;  // UNISOC: add for bug978846
                                    }
                                    mWifiService
                                            .updateCallRatState(CallRatState.CALL_NONE);
                                }
                                Log.i(TAG,
                                        "EVENT_WIFI_ATTACH_SUCCESSED ->mFeatureSwitchRequest.mEventCode:"
                                                + ACTION_START_HANDOVER
                                                + " currentImsFeature:"
                                                + getImsFeature(mVowifiAttachedServiceId)    // UNISOC: Modify for bug950573
                                                + " mIsCalling:" + mIsCalling
                                                + " mIsVowifiCall:" + mIsVowifiCall
                                                + " mIsVolteCall:" + mIsVolteCall
                                                + " mWifiRegistered:"
                                                + mWifiRegistered
                                                + " volteRegistered:"
                                                + isVoLTERegisted(mVowifiAttachedServiceId)); // UNISOC: modify for bug1008539
                                /* @} */
                            } else if (mFeatureSwitchRequest.mEventCode == ACTION_SWITCH_IMS_FEATURE) {
                                mWifiService.register();
                            }
                        }
                        mIsAPImsPdnActived = true;
                        mIsS2bStopped = false;
                        mAttachVowifiSuccess = true;// SPRD:Add for bug604833
                        break;
                    case EVENT_WIFI_ATTACH_FAILED:
                        Log.i(TAG,
                                "EVENT_WIFI_ATTACH_FAILED-> mFeatureSwitchRequest:"
                                        + mFeatureSwitchRequest
                                        + " mAttachVowifiSuccess:"
                                        + mAttachVowifiSuccess
                                        + " error code:"+ msg.arg1);
                        if (mImsServiceListenerEx != null) {
                            if (mFeatureSwitchRequest != null) {
                                mImsServiceListenerEx
                                        .operationFailed(
                                                mFeatureSwitchRequest.mRequestId,
                                                "" + msg.arg1,
                                                (mFeatureSwitchRequest.mEventCode == ACTION_SWITCH_IMS_FEATURE) ? ImsOperationType.IMS_OPERATION_SWITCH_TO_VOWIFI
                                                        : ImsOperationType.IMS_OPERATION_HANDOVER_TO_VOWIFI);
                                ImsServiceImpl service = mImsServiceImplMap
                                        .get(Integer
                                                .valueOf(mFeatureSwitchRequest.mServiceId));
                                service.notifyImsHandoverStatus(ImsHandoverResult.IMS_HANDOVER_ATTACH_FAIL);
                                if (mPendingAttachVowifiSuccess
                                        && !mIsCalling
                                        && mFeatureSwitchRequest.mEventCode == ACTION_START_HANDOVER) {
                                    mPendingAttachVowifiSuccess = false;
                                    mWifiService
                                            .updateCallRatState(CallRatState.CALL_NONE);
                                }
                                Log.i(TAG,
                                        "EVENT_WIFI_ATTACH_FAILED-> operationFailed, clear mFeatureSwitchRequest.");
                                mIsPendingRegisterVowifi = false;
                                mFeatureSwitchRequest = null;
                                if ((msg.arg1 == 53766 || msg.arg1 == 53765)&& !mIsCalling) {// SPRD: add
                                                                       // for bug661375 661372 808280
                                    setVoWifiLocalAddr(null); // UNISOC: Modify for bug1008539
                                }
                            }
                        }
                        mIsAPImsPdnActived = false;
                        mAttachVowifiSuccess = false;// SPRD:Add for bug604833
                        // SPRD:add for bug720289
                        if (mIsCalling
                                && mInCallHandoverFeature == ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_WIFI) { // UNISOC: Modify for bug950573
                            Log.i(TAG,
                                    "EVENT_WIFI_ATTACH_FAILED-> handover to vowifi attach failed, set mInCallHandoverFeature unknow");
                            mInCallHandoverFeature = ImsConfig.FeatureConstants.FEATURE_TYPE_UNKNOWN;
                            updateImsFeature();
                        }
                        break;
                    case EVENT_WIFI_ATTACH_STOPED:
                        Log.i(TAG, "EVENT_WIFI_ATTACH_STOPED, mWifiRegistered:"
                                + mWifiRegistered);
                        mIsAPImsPdnActived = false;
                        mAttachVowifiSuccess = false;// SPRD:Add for bug604833
                        break;
                    case EVENT_WIFI_INCOMING_CALL:
                        /* UNISOC: Modify for bug1041919 @{*/
                        int callServiceId = getVoWifiServiceId();
                        if ((callServiceId != IMS_INVALID_SERVICE_ID) && (callServiceId != (ImsRegister.getPrimaryCard(mPhoneCount) + 1))) {
                            Log.i(TAG, "primaryCard is not same as Vowifi service Card. callServiceId = " + callServiceId);
                            break;
                        }

                        ImsServiceImpl service = mImsServiceImplMap
                                .get(Integer.valueOf(ImsRegister.getPrimaryCard(mPhoneCount) + 1));
                        if (service != null) {
                            IImsCallSession callSession = (IImsCallSession) msg.obj;
                            service.sendIncomingCallIntent(callSession, callSession.getCallId(), false, false); // UNISOC: Modify for bug909030
                        }
                        /*@}*/

                        Log.i(TAG, "EVENT_WIFI_INCOMING_CALL-> callId:" + msg.obj);
                        break;
                    case EVENT_WIFI_ALL_CALLS_END:
                        Log.i(TAG,
                                "EVENT_WIFI_ALL_CALLS_END-> mFeatureSwitchRequest:"
                                        + mFeatureSwitchRequest
                                        + " mIsVowifiCall:" + mIsVowifiCall
                                        + " mIsVolteCall:" + mIsVolteCall
                                        + " mInCallHandoverFeature:"
                                        + mInCallHandoverFeature
                                        + " mIsPendingRegisterVolte:"
                                        + mIsPendingRegisterVolte
                                        + " mIsPendingRegisterVowifi:"
                                        + mIsPendingRegisterVowifi);
                        if (mImsServiceListenerEx != null) { // UNISOC: Modify for bug1041919
                            if (mFeatureSwitchRequest != null) {
                                if (mFeatureSwitchRequest.mEventCode == ACTION_START_HANDOVER) {
                                    /* SPRD: Add for bug586758,595321,610799{@ */
                                    ImsServiceImpl currentService = mImsServiceImplMap
                                            .get(Integer
                                                    .valueOf(mFeatureSwitchRequest.mServiceId));
                                    if (currentService != null) {
                                        int currentImsFeature = currentService.getCurrentImsFeature(); // UNISOC: Add for bug950573
                                        if (currentService
                                                .isVolteSessionListEmpty()
                                                && currentService
                                                        .isVowifiSessionListEmpty()) {
                                            mCallEndType = CallEndEvent.WIFI_CALL_END;
                                            if (mInCallHandoverFeature != mFeatureSwitchRequest.mTargetType) {
                                                if (mFeatureSwitchRequest.mTargetType == ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_WIFI) {
                                                    mPendingAttachVowifiSuccess = true;
                                                } else if (mFeatureSwitchRequest.mTargetType == ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_LTE) {
                                                    mPendingActivePdnSuccess = true;
                                                }
                                            }
                                            Log.i(TAG,
                                                    "EVENT_WIFI_ALL_CALLS_END->mPendingAttachVowifiSuccess:"
                                                            + mPendingAttachVowifiSuccess
                                                            + " mPendingActivePdnSuccess:"
                                                            + mPendingActivePdnSuccess
                                                            + " mIsVolteCall = "
                                                            + mIsVolteCall
                                                            + " mIsVowifiCall = "
                                                            + mIsVowifiCall);
                                            if (mFeatureSwitchRequest.mTargetType == ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_WIFI) {
                                                if (mIsVolteCall
                                                        && mIsPendingRegisterVowifi) {
                                                    mWifiService.register();
                                                }
                                                mIsPendingRegisterVowifi = false;
                                            }
                                            mInCallHandoverFeature = ImsConfig.FeatureConstants.FEATURE_TYPE_UNKNOWN;
                                            if (!mPendingAttachVowifiSuccess
                                                    && !mPendingActivePdnSuccess) {
                                                mWifiService
                                                        .updateCallRatState(CallRatState.CALL_NONE);
                                            }
                                            if (mIsVowifiCall
                                                    && currentImsFeature == ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_WIFI
                                                    && mFeatureSwitchRequest != null
                                                    && !mPendingAttachVowifiSuccess
                                                    && !mPendingActivePdnSuccess) {
                                                mFeatureSwitchRequest = null;
                                            }
                                            if (currentImsFeature != ImsConfig.FeatureConstants.FEATURE_TYPE_UNKNOWN) {
                                                Log.i(TAG,
                                                        "EVENT_WIFI_ALL_CALLS_END->currentImsFeature:"
                                                                + currentImsFeature);
                                                updateInCallState(false);
                                            }
                                        }
                                    } else {
                                        Log.i(TAG,
                                                "EVENT_WIFI_ALL_CALLS_END->ImsServiceImpl is null");
                                    }
                                    /* @} */
                                }
                            }
                        }

                        /* UNISOC: Modify for bug1041919 @{*/
                        int VoWifiCallServiceId = getVoWifiServiceId();
                        if(VoWifiCallServiceId == IMS_INVALID_SERVICE_ID) {
                            VoWifiCallServiceId = ImsRegister.getPrimaryCard(mPhoneCount) + 1;
                            Log.i(TAG,"main sim serviceId: " + VoWifiCallServiceId);
                        }

                        ImsServiceImpl currentService = mImsServiceImplMap
                                        .get(Integer.valueOf(VoWifiCallServiceId));
                        if (currentService != null) {
                            if (currentService.isVolteSessionListEmpty() && currentService.isVowifiSessionListEmpty()) {
                                int currentImsFeature = currentService.getCurrentImsFeature(); // UNISOC: Add for bug950573
                                Log.i(TAG,
                                        "EVENT_WIFI_ALL_CALLS_END->currentImsFeature:"
                                                + currentImsFeature);
                                if (mFeatureSwitchRequest == null) {
                                    updateInCallState(false);
                                    mCallEndType = CallEndEvent.WIFI_CALL_END;
                                    mInCallHandoverFeature = ImsConfig.FeatureConstants.FEATURE_TYPE_UNKNOWN;
                                    mWifiService
                                            .updateCallRatState(CallRatState.CALL_NONE);
                                }

                                /* SPRD: Modify for bug595321{@ */
                                if (mIsVowifiCall) {
                                    mIsVowifiCall = false;
                                } else if (mIsVolteCall) {
                                    mIsVolteCall = false;
                                }
                                /*@}*/
                            }
                            else {
                                Log.i(TAG,
                                        "session list not empty,isVolteSessionListEmpty: " + currentService.isVolteSessionListEmpty()
                                        + "isVowifiSessionListEmpty: " + currentService.isVowifiSessionListEmpty());
                            }
                        }
                        else {
                            Log.i(TAG,
                                    "EVENT_WIFI_ALL_CALLS_END->ImsServiceImpl is null");
                        }
                        /* @} */

                        break;
                    case EVENT_WIFI_REFRESH_RESAULT:
                        break;
                    case EVENT_WIFI_REGISTER_RESAULT:
                        Boolean result = (Boolean) msg.obj;
                        if (result == null)
                            break;
                        Log.i(TAG,
                                "EVENT_WIFI_REGISTER_RESAULT -> mWifiRegistered:"
                                        + result.booleanValue()
                                        + ", mFeatureSwitchRequest:"
                                        + mFeatureSwitchRequest + " mIsLoggingIn:"
                                        + mIsLoggingIn);
                        mWifiRegistered = result.booleanValue();

                        /* UNISOC: Add for bug950573 @{*/
                        int VoWifiServiceId = getVoWifiServiceId();
                        if(VoWifiServiceId == IMS_INVALID_SERVICE_ID)
                        {
                            VoWifiServiceId = ImsRegister.getPrimaryCard(mPhoneCount) + 1;
                            Log.i(TAG,"EVENT_WIFI_REGISTER_RESAULT, use main sim serviceId: " + VoWifiServiceId);
                        }
                        /*@}*/

                        mIsLoggingIn = false;
                        updateImsFeature(VoWifiServiceId); // UNISOC: Modify for bug950573
                        if (mFeatureSwitchRequest != null) {
                            ImsServiceImpl requestService = mImsServiceImplMap
                                    .get(Integer
                                            .valueOf(mFeatureSwitchRequest.mServiceId));
                            if (result.booleanValue()) {
                                Log.i(TAG,
                                        "EVENT_WIFI_REGISTER_RESAULT -> operationSuccessed -> VoWifi register success");
                                if (mImsServiceListenerEx != null) {
                                    mImsServiceListenerEx
                                            .operationSuccessed(
                                                    mFeatureSwitchRequest.mRequestId,
                                                    (mFeatureSwitchRequest.mEventCode == ACTION_SWITCH_IMS_FEATURE) ? ImsOperationType.IMS_OPERATION_SWITCH_TO_VOWIFI
                                                            : ImsOperationType.IMS_OPERATION_HANDOVER_TO_VOWIFI);
                                }
                                requestService
                                        .notifyImsHandoverStatus(ImsHandoverResult.IMS_HANDOVER_SUCCESS);

                                cancelVowifiNotification(); // UNISOC: add for bug1153427
                                /* SPRD: Add for bug604833{@ */
                                if (mFeatureSwitchRequest.mEventCode == ACTION_SWITCH_IMS_FEATURE) {
                                    requestService
                                            .setIMSRegAddress(mWifiService
                                                    .getCurLocalAddress());
                                }
                                /* @} */
                            } else {
                                Log.i(TAG,
                                        "EVENT_WIFI_REGISTER_RESAULT -> operationFailed -> VoWifi register failed");
                                if (mImsServiceListenerEx != null) {
                                    mImsServiceListenerEx
                                            .operationFailed(
                                                    mFeatureSwitchRequest.mRequestId,
                                                    "VoWifi register failed",
                                                    (mFeatureSwitchRequest.mEventCode == ACTION_SWITCH_IMS_FEATURE) ? ImsOperationType.IMS_OPERATION_SWITCH_TO_VOWIFI
                                                            : ImsOperationType.IMS_OPERATION_HANDOVER_TO_VOWIFI);
                                }
                                requestService
                                        .notifyImsHandoverStatus(ImsHandoverResult.IMS_HANDOVER_REGISTER_FAIL);
                                // UNISOC: delete for bug1107112
                                /*Toast.makeText(ImsService.this,
                                        R.string.vowifi_regist_fail_content,// SPRD: Modify for bug746036
                                        Toast.LENGTH_LONG).show();*/
                                /* SPRD: Modify for bug604833{@ */
                                if (mFeatureSwitchRequest.mEventCode == ACTION_SWITCH_IMS_FEATURE) {
                                    requestService.setIMSRegAddress(null);
                                }
                                mAttachVowifiSuccess = false;
                                /* @} */
                            }
                            if (mFeatureSwitchRequest.mTargetType == ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_WIFI) {
                                mFeatureSwitchRequest = null;
                            }
                        }
                        break;
                    case ACTION_NOTIFY_VOWIFI_UNAVAILABLE:
                        Boolean isOnlySendAT = (Boolean) msg.obj;
                        Log.w(TAG,
                                "ACTION_NOTIFY_VOWIFI_UNAVAILABLE-> isOnlySendAT:"
                                        + isOnlySendAT + " mFeatureSwitchRequest:"
                                        + mFeatureSwitchRequest
                                        + " mIsCPImsPdnActived:"
                                        + mIsCPImsPdnActived);
                        if (isOnlySendAT == null)
                            break;
                        if (mReleaseVowifiRequest != null) {
                            if (mImsServiceListenerEx != null) {
                                mImsServiceListenerEx
                                        .operationFailed(
                                                msg.arg1,
                                                "Already handle one request.",
                                                ImsOperationType.IMS_OPERATION_SET_VOWIFI_UNAVAILABLE);
                            }
                            Log.w(TAG,
                                    "ACTION_NOTIFY_VOWIFI_UNAVAILABLE-> mReleaseVowifiRequest is exist!");
                        } else {
                            /* UNISOC: Add for bug950573 @{*/
                            int vowifiServiceId = getVoWifiServiceId();
                            if (vowifiServiceId == IMS_INVALID_SERVICE_ID) {
                                vowifiServiceId = ImsRegister.getPrimaryCard(mPhoneCount) + 1;
                                Log.i(TAG,"ACTION_NOTIFY_VOWIFI_UNAVAILABLE, use main sim serviceId: " + vowifiServiceId);
                            }
                            /*@}*/

                            mReleaseVowifiRequest = new ImsServiceRequest(
                                    msg.arg1/* requestId */,
                                    ACTION_NOTIFY_VOWIFI_UNAVAILABLE /* eventCode */,
                                    vowifiServiceId /* serviceId */,                    // UNISOC: Modify for bug950573
                                    ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_LTE);

                            if (!isOnlySendAT) {
                                mWifiState =  msg.arg2; // UNISOC: Add for bug1285106

                                /* UNISOC: Modify for bug1281165 @{*/
                                ImsManager mImsManager = ImsManager.getInstance(getApplicationContext(), vowifiServiceId - 1);
                                // SPRD: add for bug645935
                                int delaySend = isAirplaneModeOn() ? 0 : 500;
                                if (mFeatureSwitchRequest == null
                                        || mFeatureSwitchRequest.mEventCode != ACTION_START_HANDOVER) {
                                    mWifiService
                                            .resetAll(
                                                    msg.arg2 == 0 ? WifiState.DISCONNECTED
                                                            : WifiState.CONNECTED,
                                                    delaySend);
                                } else {
                                    mWifiService
                                            .resetAll(msg.arg2 == 0 ? WifiState.DISCONNECTED
                                                    : WifiState.CONNECTED);
                                }
                            } else {
                                if (mImsServiceListenerEx != null) { //UNISOC: modify by bug968960
                                    Log.i(TAG,
                                            "ACTION_NOTIFY_VOWIFI_UNAVAILABLE -> operationSuccessed -> IMS_OPERATION_SET_VOWIFI_UNAVAILABLE");
                                    mImsServiceListenerEx
                                            .operationSuccessed(
                                                    mReleaseVowifiRequest.mRequestId,
                                                    ImsOperationType.IMS_OPERATION_SET_VOWIFI_UNAVAILABLE);
                                }
                                mReleaseVowifiRequest = null;
                            }
                            Log.i(TAG,
                                    "ACTION_NOTIFY_VOWIFI_UNAVAILABLE-> wifi state: "
                                            + msg.arg2);

                            //UNISOC: modify by bug947058, bug950573
                            ImsServiceImpl imsService = mImsServiceImplMap.get(new Integer(vowifiServiceId));
                            if (imsService != null) {
                                imsService.notifyVoWifiEnable(false);
                                mPendingCPSelfManagement = true;
                                Log.i(TAG,
                                        "ACTION_NOTIFY_VOWIFI_UNAVAILABLE-> notifyVoWifiUnavaliable. mPendingCPSelfManagement:"
                                                + mPendingCPSelfManagement);
                            }

                            if (mFeatureSwitchRequest != null) {
                                mFeatureSwitchRequest = null;
                            }
                            mIsPendingRegisterVolte = false;// SPRD: add for bug723080
                        }
                        if (mPendingVowifiHandoverVowifiSuccess) { // UNISOC: modify for bug978846
                            Log.i(TAG,
                                    "ACTION_NOTIFY_VOWIFI_UNAVAILABLE->mPendingVowifiHandoverVowifiSuccess is true->mCallEndType:"
                                            + mCallEndType
                                            + " mIsCalling:"
                                            + mIsCalling);
                            mPendingVowifiHandoverVowifiSuccess = false;
                            if (mCallEndType != CallEndEvent.INVALID_CALL_END && !mIsCalling) {
                                Log.i(TAG,
                                        "ACTION_NOTIFY_VOWIFI_UNAVAILABLE-> mCallEndType:"
                                                + mCallEndType);
                                notifyCpCallEnd();
                            }
                        }
                        mAttachVowifiSuccess = false;// SPRD:Add for bug604833
                        break;

                    /* UNISOC: Add for bug1007100{@ */
                    case EVENT_WIFI_RESET_START:

                        /* UNISOC: Modify for bug1068538 @{*/
                        if (mReleaseVowifiRequest != null) {
                            int serviceId = mReleaseVowifiRequest.mServiceId;

                            if (mWifiRegistered && !isVoLTERegisted(serviceId) && (getImsFeature(serviceId) == ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_LTE)) {
                                Log.i(TAG, "EVENT_WIFI_RESET_START-> currentImsFeature is VolTE due to Handover,not update Ims Feature");
                            } else {
                                mWifiRegistered = false;
                                updateImsFeatureForAllService();
                            }
                        }
                        /*@}*/
                        Log.i(TAG, "EVENT_WIFI_RESET_START");
                        break;
                    /* @} */

                    case EVENT_WIFI_RESET_RESAULT:
                        int releaseResult = msg.arg1;
                        int errorCode = msg.arg2;
                        if (releaseResult == ImsStackResetResult.SUCCESS) {
                            mWifiRegistered = false;
                            mVowifiAttachedServiceId = IMS_INVALID_SERVICE_ID;  // UNISOC: Add for bug950573
                            updateImsFeatureForAllService();// SPRD:Add for bug816979
                        }
                        if (mReleaseVowifiRequest != null) {
                            if (mImsServiceListenerEx != null) {
                                int actionType;
                                if (mReleaseVowifiRequest.mEventCode == ACTION_RELEASE_WIFI_RESOURCE) {
                                    actionType = ImsOperationType.IMS_OPERATION_RELEASE_WIFI_RESOURCE;
                                } else if (mReleaseVowifiRequest.mEventCode == ACTION_NOTIFY_VOWIFI_UNAVAILABLE) {
                                    actionType = ImsOperationType.IMS_OPERATION_SET_VOWIFI_UNAVAILABLE;
                                } else {
                                    actionType = ImsOperationType.IMS_OPERATION_CANCEL_CURRENT_REQUEST;
                                }
                                if (releaseResult == ImsStackResetResult.SUCCESS) {
                                    Log.i(TAG,
                                            "EVENT_WIFI_RESET_RESAULT -> operationSuccessed -> wifi release success");
                                    mImsServiceListenerEx.operationSuccessed(
                                            mReleaseVowifiRequest.mRequestId,
                                            actionType);
                                } else {
                                    Log.i(TAG,
                                            "EVENT_WIFI_RESET_RESAULT -> operationFailed -> wifi release fail ");
                                    mImsServiceListenerEx.operationFailed(
                                            mReleaseVowifiRequest.mRequestId,
                                            "wifi release fail:" + errorCode,
                                            actionType);
                                }
                            }
                            mReleaseVowifiRequest = null;
                            mWifiState = -1;// UNISOC: Add for bug1285106
                        }
                        Log.i(TAG, "EVENT_WIFI_RESET_RESAULT-> result:"
                                + releaseResult + " errorCode:" + errorCode);
                        break;
                    case ACTION_RELEASE_WIFI_RESOURCE:
                        if (mReleaseVowifiRequest != null) {
                            if (mImsServiceListenerEx != null) {
                                mImsServiceListenerEx
                                        .operationFailed(
                                                msg.arg1,
                                                "Already handle one request.",
                                                ImsOperationType.IMS_OPERATION_RELEASE_WIFI_RESOURCE);
                            }
                            Log.w(TAG,
                                    "ACTION_RELEASE_WIFI_RESOURCE-> mReleaseVowifiRequest is exist!");
                        } else {
                            if (mWifiService != null) {
                                mWifiService.resetAll(WifiState.DISCONNECTED);
                            }

                            /* UNISOC: Add for bug950573 @{*/
                            int vowifiServiceId = getVoWifiServiceId();
                            if (vowifiServiceId == IMS_INVALID_SERVICE_ID) {
                                vowifiServiceId = ImsRegister.getPrimaryCard(mPhoneCount) + 1;
                                Log.i(TAG,"ACTION_RELEASE_WIFI_RESOURCE, use main sim serviceId: " + vowifiServiceId);
                            }
                            /*@}*/

                            mReleaseVowifiRequest = new ImsServiceRequest(
                                    msg.arg1/* requestId */,
                                    ACTION_RELEASE_WIFI_RESOURCE /* eventCode */,
                                    vowifiServiceId /* serviceId */, // UNISOC: Modify for bug950573
                                    ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_LTE);
                            Log.w(TAG,
                                    "ACTION_RELEASE_WIFI_RESOURCE-> wifi state: DISCONNECTED");
                        }
                        mAttachVowifiSuccess = false;// SPRD:Add for bug604833
                        break;
                    case ACTION_CANCEL_CURRENT_REQUEST:
                        Log.i(TAG,
                                "ACTION_CANCEL_CURRENT_REQUEST-> mFeatureSwitchRequest: "
                                        + mFeatureSwitchRequest
                                        + " mAttachVowifiSuccess:"
                                        + mAttachVowifiSuccess);
                        if (mFeatureSwitchRequest != null
                                && mFeatureSwitchRequest.mTargetType == ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_WIFI) {
                            if (mReleaseVowifiRequest != null) {
                                if (mImsServiceListenerEx != null) {
                                    mImsServiceListenerEx
                                            .operationFailed(
                                                    msg.arg1,
                                                    "Already handle one request.",
                                                    ImsOperationType.IMS_OPERATION_CANCEL_CURRENT_REQUEST);
                                }
                                Log.w(TAG,
                                        "ACTION_CANCEL_CURRENT_REQUEST-> mReleaseVowifiRequest is exist!");
                                return;
                            } else {
                                mReleaseVowifiRequest = new ImsServiceRequest(
                                        msg.arg1/* requestId */,
                                        ImsOperationType.IMS_OPERATION_CANCEL_CURRENT_REQUEST /* eventCode */,
                                        mFeatureSwitchRequest.mServiceId /* serviceId */,  // UNISOC: Modify for bug950573
                                        ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_LTE);
                            }
                            mWifiService.resetAll(WifiState.DISCONNECTED /*
                                                                          * Do not attached now,
                                                                          * same as wifi do not
                                                                          * connected
                                                                          */);
                            /* SPRD: Modify for bug604833{@ */
                            ImsServiceImpl imsService = mImsServiceImplMap
                                    .get(Integer.valueOf(mFeatureSwitchRequest.mServiceId));  // UNISOC: Modify for bug950573
                            if (mFeatureSwitchRequest.mEventCode == ACTION_SWITCH_IMS_FEATURE
                                    && mAttachVowifiSuccess) {
                                if (imsService != null) {
                                    imsService.setIMSRegAddress(null);
                                }
                            }
                            if (imsService != null) {
                                imsService.notifyVoWifiEnable(false);
                                mPendingCPSelfManagement = true;
                                Log.i(TAG,
                                        "ACTION_CANCEL_CURRENT_REQUEST-> notifyVoWifiUnavaliable. mPendingCPSelfManagement:"
                                                + mPendingCPSelfManagement);
                            }
                            mAttachVowifiSuccess = false;
                            mFeatureSwitchRequest = null;
                            if (mPendingAttachVowifiSuccess || mPendingVowifiHandoverVowifiSuccess) { // UNISOC: modify for bug978846
                                mPendingAttachVowifiSuccess = false;
                                mPendingVowifiHandoverVowifiSuccess = false;
                                Log.i(TAG,
                                        "ACTION_CANCEL_CURRENT_REQUEST-> mPendingAttachVowifiSuccess is true!");
                                if (mCallEndType != CallEndEvent.INVALID_CALL_END && !mIsCalling) {
                                    Log.i(TAG,
                                            "ACTION_CANCEL_CURRENT_REQUEST-> mPendingAttachVowifiSuccess:"
                                                    + mPendingAttachVowifiSuccess
                                                    + " mPendingVowifiHandoverVowifiSuccess:"
                                                    + mPendingVowifiHandoverVowifiSuccess
                                                    + " mCallEndType:"
                                                    + mCallEndType);
                                    notifyCpCallEnd();
                                }
                            }
                            /* @} */
                            Log.i(TAG,
                                    "ACTION_CANCEL_CURRENT_REQUEST-> mIsCalling:"
                                            + mIsCalling);
                            if (!mIsCalling) {
                                mWifiService
                                        .updateCallRatState(CallRatState.CALL_NONE);
                            }
                        } else {
                            if (mImsServiceListenerEx != null) {
                                mImsServiceListenerEx
                                        .operationFailed(
                                                msg.arg1/* requestId */,
                                                "Invalid Request",
                                                ImsOperationType.IMS_OPERATION_CANCEL_CURRENT_REQUEST);
                            } else {
                                Log.i(TAG,
                                        "ACTION_CANCEL_CURRENT_REQUEST -> mImsServiceListenerEx is null ");
                            }
                        }
                        break;
                    case EVENT_WIFI_DPD_DISCONNECTED:
                        if (mImsServiceListenerEx != null) {
                            mImsServiceListenerEx.onDPDDisconnected();
                        }
                        break;
                    case EVENT_WIFI_NO_RTP:
                        Boolean isVideo = (Boolean) msg.obj;
                        if (isVideo == null)
                            break;
                        if (mImsServiceListenerEx != null) {
                            mImsServiceListenerEx.onNoRtpReceived(isVideo);
                        }
                        break;
                    case EVENT_WIFI_UNSOL_UPDATE:
                        Log.i(TAG,
                                "EVENT_WIFI_UNSOL_UPDATE-> mFeatureSwitchRequest: "
                                        + mFeatureSwitchRequest
                                        + " UnsolicitedCode:" + msg.arg1
                                        + " mIsVowifiCall:" + mIsVowifiCall
                                        + " mIsVolteCall:" + mIsVolteCall
                                        + " mIsS2bStopped:" + mIsS2bStopped
                                        + " mPendingReregister:"
                                        + mPendingReregister);
                        if (msg.arg1 == UnsolicitedCode.SECURITY_STOP) {
                            mIsS2bStopped = true;
                        }
                        if (mFeatureSwitchRequest != null
                                && mFeatureSwitchRequest.mEventCode == ACTION_START_HANDOVER
                                && (msg.arg1 == UnsolicitedCode.SECURITY_DPD_DISCONNECTED
                                        || msg.arg1 == UnsolicitedCode.SECURITY_REKEY_FAILED || msg.arg1 == UnsolicitedCode.SECURITY_STOP)) {
                            int currentImsFeature = getImsFeature(mFeatureSwitchRequest.mServiceId);  // UNISOC: Add for bug950573
                            if (mFeatureSwitchRequest.mTargetType == ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_LTE
                                    && currentImsFeature == ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_LTE
                                    && msg.arg1 == UnsolicitedCode.SECURITY_STOP) {
                                if (mIsS2bStopped && mPendingReregister) {
                                    if (mInCallHandoverFeature != ImsConfig.FeatureConstants.FEATURE_TYPE_UNKNOWN) {
                                        ImsServiceImpl imsService = mImsServiceImplMap
                                                .get(Integer
                                                        .valueOf(mFeatureSwitchRequest.mServiceId));
                                        if (mIsVolteCall) {
                                            imsService.notifyDataRouter();
                                        } else if (mIsVowifiCall) {
                                            mWifiService.reRegister(mNetworkType,
                                                    mNetworkInfo);
                                        }
                                        mPendingReregister = false;
                                    }
                                }
                            }
                        }
                        notifyWiFiError(msg.arg1);
                        break;
                    case EVENT_WIFI_RTP_RECEIVED:
                        Boolean rtpIsVideo = (Boolean) msg.obj;
                        if (rtpIsVideo == null)
                            break;
                        if (mImsServiceListenerEx != null) {
                            mImsServiceListenerEx.onRtpReceived(rtpIsVideo);
                        }
                        break;
                    case EVENT_UPDATE_DATA_ROUTER_FINISHED:
                        if (mCallEndType != CallEndEvent.INVALID_CALL_END) {
                            Log.i(TAG,
                                    "EVENT_UPDATE_DATA_ROUTER_FINISHED-> mCallEndType:"
                                            + mCallEndType);
                        }
                        notifyCpCallEnd();
                        Log.i(TAG,
                                "EVENT_UPDATE_DATA_ROUTER_FINISHED-> mFeatureSwitchRequest: "
                                        + mFeatureSwitchRequest
                                        + " mInCallHandoverFeature: "
                                        + mInCallHandoverFeature
                                        + " mPendingVowifiHandoverVowifiSuccess:"
                                        + mPendingVowifiHandoverVowifiSuccess
                                        + " mPendingVolteHandoverVolteSuccess:"
                                        + mPendingVolteHandoverVolteSuccess);
                        if (mFeatureSwitchRequest != null
                                && (mInCallHandoverFeature != ImsConfig.FeatureConstants.FEATURE_TYPE_UNKNOWN
                                        || mPendingVolteHandoverVolteSuccess || mPendingVowifiHandoverVowifiSuccess)) {
                            ImsServiceImpl imsService = mImsServiceImplMap
                                    .get(Integer
                                            .valueOf(mFeatureSwitchRequest.mServiceId));
                            int currentImsFeature = getImsFeature(mFeatureSwitchRequest.mServiceId); // UNISOC: Add for bug950573
                            if (mIsVolteCall || mPendingVolteHandoverVolteSuccess) {
                                if (mFeatureSwitchRequest.mEventCode == ACTION_START_HANDOVER
                                        && mFeatureSwitchRequest.mTargetType == ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_LTE
                                        && currentImsFeature == ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_LTE) {
                                    if (mIsS2bStopped) {
                                        Log.i(TAG,
                                                "EVENT_UPDATE_DATA_ROUTER_FINISHED->notifyDataRouter");
                                        imsService.notifyDataRouter();
                                    } else if (!mPendingVolteHandoverVolteSuccess) { //UNISOC: modify for bug978846
                                        mPendingReregister = true;
                                    }
                                    Log.i(TAG,
                                            "EVENT_UPDATE_DATA_ROUTER_FINISHED->mIsS2bStopped:"
                                                    + mIsS2bStopped
                                                    + " mPendingReregister"
                                                    + mPendingReregister);
                                } else if (mFeatureSwitchRequest.mEventCode == ACTION_START_HANDOVER
                                        && mFeatureSwitchRequest.mTargetType == ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_WIFI
                                        && currentImsFeature == ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_WIFI) {
                                    int type = 6;
                                    StringBuffer info = new StringBuffer();
                                    WifiInfo wifiInfo;
                                    String wifiInfoHead = "IEEE-802.11;i-wlan-node-id=";
                                    info.append(wifiInfoHead);
                                    WifiManager wifiManager = (WifiManager) ImsService.this
                                            .getSystemService(Context.WIFI_SERVICE);
                                    if (wifiManager != null) {
                                        Log.i(TAG,
                                                "EVENT_UPDATE_DATA_ROUTER_FINISHED-> wifiManager :"
                                                        + wifiManager);
                                        wifiInfo = wifiManager.getConnectionInfo();
                                        if (wifiInfo != null
                                                && wifiInfo.getBSSID() != null) {
                                            Log.i(TAG,
                                                    "EVENT_UPDATE_DATA_ROUTER_FINISHED-> wifiInfo.getBSSID(): "
                                                            + wifiInfo.getBSSID());
                                            info.append(wifiInfo.getBSSID()
                                                    .replace(":", ""));
                                        }
                                    }
                                    /* UNISOC: Add for bug1198655{@ */
                                    String countryCode = VoWifiConfiguration.getRegPANICountryCode(getApplicationContext());
                                    if(countryCode != null && countryCode.length() > 0) {
                                        info.append(";country=" + countryCode);
                                    }
                                    /* @} */
                                    Log.i(TAG,
                                            "EVENT_UPDATE_DATA_ROUTER_FINISHED->notifyImsNetworkInfo->type: "
                                                    + type + " info: "
                                                    + info.toString());
                                    imsService.notifyImsNetworkInfo(type,
                                            info.toString());
                                }
                                if (mPendingVolteHandoverVolteSuccess) {
                                    if (mFeatureSwitchRequest != null) {
                                        mFeatureSwitchRequest = null;
                                    }
                                    mPendingVolteHandoverVolteSuccess = false;
                                }
                            } else if (mIsVowifiCall
                                    || mPendingVowifiHandoverVowifiSuccess) {
                                if (mFeatureSwitchRequest.mEventCode == ACTION_START_HANDOVER
                                        && mFeatureSwitchRequest.mTargetType == ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_LTE
                                        && currentImsFeature == ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_LTE) {
                                    Log.i(TAG,
                                            "EVENT_UPDATE_DATA_ROUTER_FINISHED->reRegister->type: "
                                                    + mNetworkType + " info:"
                                                    + mNetworkInfo);
                                    if (mIsS2bStopped) {
                                        mWifiService.reRegister(mNetworkType,
                                                mNetworkInfo);
                                    } else {
                                        mPendingReregister = true;
                                    }
                                    Log.i(TAG,
                                            "EVENT_UPDATE_DATA_ROUTER_FINISHED->mIsS2bStopped:"
                                                    + mIsS2bStopped
                                                    + " mPendingReregister"
                                                    + mPendingReregister);
                                } else if (mFeatureSwitchRequest.mEventCode == ACTION_START_HANDOVER
                                        && mFeatureSwitchRequest.mTargetType == ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_WIFI
                                        && currentImsFeature == ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_WIFI) {
                                    int type = -1;// SPRD:"-1" means
                                                  // "EN_MTC_ACC_NET_IEEE_802_11"
                                    StringBuffer info = new StringBuffer();
                                    WifiInfo wifiInfo;
                                    WifiManager wifiManager = (WifiManager) ImsService.this
                                            .getSystemService(Context.WIFI_SERVICE);
                                    if (wifiManager != null) {
                                        Log.i(TAG,
                                                "EVENT_UPDATE_DATA_ROUTER_FINISHED-> wifiManager :"
                                                        + wifiManager);
                                        wifiInfo = wifiManager.getConnectionInfo();
                                        if (wifiInfo != null) {
                                            Log.i(TAG,
                                                    "EVENT_UPDATE_DATA_ROUTER_FINISHED-> wifiInfo.getBSSID(): "
                                                            + wifiInfo.getBSSID());
                                            if (wifiInfo.getBSSID() != null) {
                                                info.append(wifiInfo.getBSSID()
                                                        .replace(":", ""));
                                            }
                                        }
                                    }
                                    /* UNISOC: Add for bug1198655{@ */
                                    String countryCode = VoWifiConfiguration.getRegPANICountryCode(getApplicationContext());
                                    if(countryCode != null && countryCode.length() > 0) {
                                        info.append(";country=" + countryCode);
                                    }
                                    /* @} */
                                    Log.i(TAG,
                                            "EVENT_UPDATE_DATA_ROUTER_FINISHED->reRegister->type: "
                                                    + type + " info: "
                                                    + info.toString());
                                    mWifiService.reRegister(type, info.toString());
                                }
                                if (mPendingVowifiHandoverVowifiSuccess) {
                                    if (mFeatureSwitchRequest != null) {
                                        mFeatureSwitchRequest = null;
                                    }
                                    mPendingVowifiHandoverVowifiSuccess = false;
                                }
                            }
                            /* UNISOC: add for bug978846 @{ */
                        } else if (mPendingVolteHandoverVolteSuccess) {
                            Log.i(TAG,
                                    "EVENT_UPDATE_DATA_ROUTER_FINISHED->mPendingVolteHandoverVolteSuccess is true");
                            if((mFeatureSwitchRequest == null) && !mIsCalling) {
                                if (mIsS2bStopped) {
                                    Log.i(TAG,
                                            "EVENT_UPDATE_DATA_ROUTER_FINISHED->notifyDataRouter");

                                    int vowifiServiceId = getVoWifiServiceId();
                                    if(vowifiServiceId == IMS_INVALID_SERVICE_ID)
                                    {
                                        vowifiServiceId = ImsRegister.getPrimaryCard(mPhoneCount) + 1;
                                        Log.i(TAG,"EVENT_UPDATE_DATA_ROUTER_FINISHED, use main sim serviceId: " + vowifiServiceId);
                                    }
                                    ImsServiceImpl imsService = mImsServiceImplMap.get(new Integer(vowifiServiceId));
                                    if ((imsService != null) && (isVoLTERegisted(vowifiServiceId))) // UNISOC: modify for bug1008539
                                        imsService.notifyDataRouter();
                                }

                                Log.i(TAG,
                                        "EVENT_UPDATE_DATA_ROUTER_FINISHED->mIsS2bStopped:"
                                                + mIsS2bStopped
                                                + " mPendingReregister"
                                                + mPendingReregister);
                            }
                            mPendingVolteHandoverVolteSuccess = false;
                        }
                        /*@}*/
                        break;
                    case EVENT_NOTIFY_CP_VOWIFI_ATTACH_SUCCESSED:
                        Log.i(TAG,
                                "EVENT_NOTIFY_CP_VOWIFI_ATTACH_SUCCESSED-> notifyImsHandoverStatus:"
                                        + ImsHandoverResult.IMS_HANDOVER_ATTACH_SUCCESS);
                        ImsServiceImpl Impl = mImsServiceImplMap
                                .get(Integer.valueOf(ImsRegister
                                        .getPrimaryCard(mPhoneCount) + 1));
                        Impl.notifyImsHandoverStatus(ImsHandoverResult.IMS_HANDOVER_ATTACH_SUCCESS);
                        break;
                    case ACTION_NOTIFY_VIDEO_CAPABILITY_CHANGE:
                        notifyImsRegisterState();  // UNISOC: Modify for bug988585
                        break;
                    default:
                        break;
                }
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
    };

    @Override
    public void onCreate() {
        iLog("Ims Service onCreate.");
        if (!ImsConfigImpl.isImsEnabledBySystemProperties() && !ImsConfigImpl.isVoWiFiEnabledByBoard(this)) {
            Log.w(TAG,
                    "Could Not Start Ims Service because volte disabled by system properties!");
            return;
        }
        super.onCreate();
        ServiceManager.addService(ImsManagerEx.IMS_SERVICE_EX,
                mImsServiceExBinder);
        ServiceManager.addService(ImsManagerEx.IMS_UT_EX, mImsUtExBinder);

        mVoWifiCallback = new MyVoWifiCallback();
        mWifiService = new VoWifiServiceImpl(getApplicationContext());
        mWifiService.registerCallback(mVoWifiCallback);

        Phone[] phones = PhoneFactory.getPhones();
        VTManagerProxy.init(this);
        synchronized (mImsServiceImplMap) {
            if (phones != null) {
                for (Phone phone : phones) {
                    ImsServiceImpl impl = new ImsServiceImpl(phone, this,
                            mWifiService);
                    impl.addListener(mVoLTERegisterListener);
                    mImsServiceImplMap.put(impl.getServiceId(), impl);
                }
            }
        }
        mTelephonyManager = (TelephonyManager)this.getSystemService(Context.TELEPHONY_SERVICE);
        mPhoneCount = mTelephonyManager.getPhoneCount();
        mPhoneStateListener = new PhoneStateListener() {
            @Override
            public void onCallStateChanged(int state, String incomingNumber) {
                boolean isInCall = false;
                mInCallPhoneId = -1;
                if (state != TelephonyManager.CALL_STATE_IDLE) {
                    isInCall = true;
                    Phone[] phones = PhoneFactory.getPhones();
                    if (phones != null) {
                        for (Phone phone : phones) {
                            if (phone.getState() != PhoneConstants.State.IDLE) {
                                mInCallPhoneId = phone.getPhoneId();
                                break;
                            }
                        }
                    }
                }else {
                    //SPRD: add for bug825528
                    if (mMakeCallPrimaryCardServiceId != -1)
                        mMakeCallPrimaryCardServiceId = -1;
                }
                /*SPRD: Modify for bug941037{@*/
                boolean isEmergency = PhoneNumberUtils.isEmergencyNumber(incomingNumber);
                if (!mIsWifiCalling && isEmergency && isInCall) {
                    mIsEmergencyCallonIms = true;
                } else {
                    mIsEmergencyCallonIms = false;
                }/*@}*/
                updateInCallState(isInCall);
                iLog("onCallStateChanged->isInCall:" + isInCall
                        + " mIsWifiCalling:" + mIsWifiCalling
                        + " inCallPhoneId:" + mInCallPhoneId
                        + " mIsEmergencyCallonIms" + mIsEmergencyCallonIms);
            }
        };
        mTelephonyManager.listen(mPhoneStateListener,
                PhoneStateListener.LISTEN_CALL_STATE);
        createNotificationChannel();// SPRD: modify for bug762807
        //SPRD: add for bug825528
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(TelephonyIntents.ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED);
        intentFilter.addAction(TelephonyIntents.ACTION_SIM_STATE_CHANGED);
        intentFilter.setPriority(Integer.MAX_VALUE);
        this.registerReceiver(mReceiver, intentFilter);
    }

    public NotificationChannel createNotificationChannel(){// SPRD: modify for bug762807
        String id = "vowifi_regist_channel";
        mVowifiChannel = new NotificationChannel(id, "vowifi_msg", NotificationManager.IMPORTANCE_HIGH);
        mVowifiChannel.enableLights(true);
        mVowifiChannel.enableVibration(true);
        mVowifiChannel.setShowBadge(false);
        return mVowifiChannel;
    }
    @Override
    public void onDestroy() {
        iLog("Ims Service Destroyed.");
        super.onDestroy();
    }

    /**
     * @hide
     */
    @Override
    public IBinder onBind(Intent intent) {
        iLog("Ims Service onBind:" + intent.getAction());
        if (!ImsConfigImpl.isImsEnabledBySystemProperties() && !ImsConfigImpl.isVoWiFiEnabledByBoard(this)) {
            Log.w(TAG,
                    "Could Not Start Ims Service because volte disabled by system properties!");
            return null;
        }
        if ("android.telephony.ims.ImsService".equals(intent.getAction())) {
            return mImsServiceController;
        }
        return mImsServiceController;
    }

    public IImsCallSession createCallSessionInternal(int serviceId,
            ImsCallProfile profile, IImsCallSessionListener listener) {
        /* SPRD: Modify for bug586758{@ */
        Log.i(TAG, "createCallSession->mIsVowifiCall: " + mIsVowifiCall
                + " mIsVolteCall: " + mIsVolteCall + " isVoWifiEnabled(): "
                + isVoWifiEnabled() + " isVoLTEEnabled(): " + isVoLTEEnabled());
        mInCallPhoneId = serviceId - 1;// SPRD:add for bug635699

        /** UNISOC: add for bug1073304 @{ */
        Phone phone = PhoneFactory.getPhone(serviceId - 1);
        boolean dialEccOnIms = false;
        boolean dialViaIms =  getBooleanCarrierConfigByServiceId(serviceId,
                                  CarrierConfigManagerEx.KEY_CARRIER_ECC_VIA_IMS, getApplicationContext());
        Log.d(TAG,"createCallSessionInternal dialOnModemCarrier :" + dialViaIms);
        if(isVoWifiEnabled()
                && profile.getServiceType() == ImsCallProfile.SERVICE_TYPE_EMERGENCY
                && dialViaIms
                && (phone != null
                && phone.getServiceStateTracker() != null
                && phone.getServiceStateTracker().mSS != null
                && (phone.getServiceStateTracker().mSS.getState() == ServiceState.STATE_IN_SERVICE
                    || phone.getServiceStateTracker().mSS.isEmergencyOnly()))){
            mIsEmergencyCallonIms = true;
            dialEccOnIms = true;
            Log.d(TAG,"createCallSessionInternal dialEccOnModem :" + dialEccOnIms);
        }
        /** @} */

        updateInCallState(true);
        boolean isPrimaryCard = ImsRegister.getPrimaryCard(mPhoneCount) == (serviceId-1);
        if (!dialEccOnIms && ((isPrimaryCard && isVoWifiEnabled() && !mIsVowifiCall && !mIsVolteCall)
                || mIsVowifiCall)) {
            if (isVoWifiEnabled() && !mIsVowifiCall && !mIsVolteCall) {
                mIsVowifiCall = true;
                mWifiService.updateCurCallSlot(serviceId - 1); // UNISOC: Add for bug1138223
                mWifiService.updateCallRatState(CallRatState.CALL_VOWIFI);
            }
            return mWifiService.createCallSession(profile, listener);
        }
        /* @} */
        ImsServiceImpl service = mImsServiceImplMap.get(new Integer(serviceId));
        if (service == null) {
            Log.e(TAG, "Invalid ServiceId ");
            return null;
        }
        if (isVoLTEEnabled() && !mIsVowifiCall && !mIsVolteCall) {
            mIsVolteCall = true;
            mWifiService.updateCurCallSlot(serviceId - 1); // UNISOC: Add for bug1138223
            mWifiService.updateCallRatState(CallRatState.CALL_VOLTE);
        }
        Log.e(TAG, "createCallSession-> startVoLteCall");
        return service.createCallSessionInternal(profile);
    }

    public IImsCallSession getPendingCallSessionInternal(int serviceId,
            String callId) {
        Log.i(TAG, " getPendingCallSession-> serviceId: " + serviceId
                + "  callId: " + callId + " mIsVowifiCall: " + mIsVowifiCall
                + " mIsVolteCall: " + mIsVolteCall + " isVoWifiEnabled(): "
                + isVoWifiEnabled() + " isVoLTEEnabled(): " + isVoLTEEnabled());
        mInCallPhoneId = serviceId - 1;// SPRD:add for bug635699
        /* SPRD: Modify for bug586758 and bug827022 {@ */
        boolean isPrimaryCard = ImsRegister.getPrimaryCard(mPhoneCount) == (serviceId-1);
        if ((isVoWifiEnabled() && !mIsVowifiCall && !mIsVolteCall && isPrimaryCard)
                || mIsVowifiCall) {
            mIsVowifiCall = true;
            IImsCallSession session = mWifiService
                    .getPendingCallSession(callId);
            Log.i(TAG, "getPendingCallSession-> session: " + session);

            // SPRD: add for bug650614
            ImsServiceImpl service = mImsServiceImplMap.get(new Integer(
                    serviceId));
            if (service != null && service.getPendingCallSession(callId) != null) {
                Log.i(TAG, "Volte unknow call");
                mIsVolteCall = true;
                return service.getPendingCallSession(callId);
            }
            return session;
        }
        /* @} */
        ImsServiceImpl service = mImsServiceImplMap.get(new Integer(serviceId));
        if (service == null || callId == null) {
            Log.e(TAG, "Invalid arguments " + service + " " + callId);
            return null;
        }// SPRD:modify by bug650141
        if ((isVoLTEEnabled() || service.getPendingCallSession(callId) != null)
                && !mIsVowifiCall && !mIsVolteCall) {
            mIsVolteCall = true;
        }
        Log.i(TAG,
                "getPendingCallSession->service.getPendingCallSession(callId): "
                        + service.getPendingCallSession(callId));
        return service.getPendingCallSession(callId);
    }

    /**
     * Called from the ImsResolver to create the requested ImsFeature, as defined by the slot and
     * featureType
     * 
     * @param slotId An integer representing which SIM slot the ImsFeature is assigned to.
     * @param featureType An integer representing the type of ImsFeature being created. This is
     *            defined in {@link ImsFeature}.
     */
    // Be sure to lock on mFeatures before accessing this method
    private IImsMmTelFeature onCreateImsFeatureInternal(int slotId, IImsFeatureStatusCallback c) {
        Log.i(TAG, "onCreateImsFeatureInternal-> slotId: " + slotId);
        ImsServiceImpl service = mImsServiceImplMap
                .get(new Integer(slotId + 1));
        return service.onCreateImsFeature(c);
    }

    /**
     * Called from the ImsResolver to remove an existing ImsFeature, as defined by the slot and
     * featureType.
     * 
     * @param slotId An integer representing which SIM slot the ImsFeature is assigned to.
     * @param featureType An integer representing the type of ImsFeature being removed. This is
     *            defined in {@link ImsFeature}.
     */
    // Be sure to lock on mFeatures before accessing this method
    private void onRemoveImsFeatureInternal(int slotId, int featureType) {
        Log.i(TAG, "onRemoveImsFeatureInternal-> slotId: " + slotId
                + " featureType:" + featureType);
    }

    // Be sure to lock on mFeatures before accessing this method
    private ImsServiceImpl getMMTelFeature(int slotId) {
        ImsServiceImpl service = mImsServiceImplMap
                .get(new Integer(slotId + 1));
        return service;
    }

    /**
     * Check for both READ_PHONE_STATE and READ_PRIVILEGED_PHONE_STATE. READ_PHONE_STATE is a public
     * permission and READ_PRIVILEGED_PHONE_STATE is only granted to system apps.
     */
    private void enforceReadPhoneStatePermission(String fn) {
        if (checkCallingOrSelfPermission(READ_PRIVILEGED_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            enforceCallingOrSelfPermission(READ_PHONE_STATE, fn);
        }
    }

    protected final IBinder mImsServiceController = new IImsServiceController.Stub() {
        @Override
        public void setListener(IImsServiceControllerListener l){
            Log.i(TAG, "mImsServiceController-> setListener: " + l);
            mIImsServiceControllerListener = l;
        }

        @Override
        public IImsMmTelFeature createMmTelFeature(int slotId,  IImsFeatureStatusCallback c){
            synchronized (mImsServiceImplMap) {
                return onCreateImsFeatureInternal(slotId, c);
            }
        }

        @Override
        public IImsRcsFeature createRcsFeature(int slotId,  IImsFeatureStatusCallback c){
            Log.i(TAG, "createRcsFeature-> slotId: " + slotId+ " IImsFeatureStatusCallback:"+c);
            return null;
        }

        @Override
        public ImsFeatureConfiguration querySupportedImsFeatures(){
            ImsFeatureConfiguration.Builder build = new ImsFeatureConfiguration.Builder();
            for(int i=0;i<mPhoneCount;i++) {
                build.addFeature(i, ImsFeature.FEATURE_MMTEL);
                build.addFeature(i, ImsFeature.FEATURE_EMERGENCY_MMTEL);
            }
            return build.build();
        }

        @Override
        public void notifyImsServiceReadyForFeatureCreation(){
            //onServiceConnected callback
        }

        @Override
        public void removeImsFeature(int slotId, int feature,
                IImsFeatureStatusCallback c) throws RemoteException {
            synchronized (mImsServiceImplMap) {
                enforceCallingOrSelfPermission(MODIFY_PHONE_STATE,
                        "removeImsFeature");
                onRemoveImsFeatureInternal(slotId, feature);
            }
        }

        @Override
        public IImsConfig getConfig(int slotId)
                throws RemoteException {
            synchronized (mImsServiceImplMap) {
                ImsServiceImpl feature = getMMTelFeature(slotId);
                if (feature != null) {
                    return feature.getConfigInterface();
                }
            }
            return null;
        }

        @Override
        public IImsRegistration getRegistration(int slotId)
                throws RemoteException {
            synchronized (mImsServiceImplMap) {
                ImsServiceImpl feature = getMMTelFeature(slotId);
                if (feature != null) {
                    return feature.getRegistration();
                }
            }
            return null;
        }

        @Override
        public void enableIms(int slotId)
                throws RemoteException {
            enforceCallingOrSelfPermission(MODIFY_PHONE_STATE, "turnOnIms");
            synchronized (mImsServiceImplMap) {
                ImsServiceImpl feature = getMMTelFeature(slotId);
                if (feature != null) {
                    feature.turnOnIms();
                }
            }
        }

        @Override
        public void disableIms(int slotId)
                throws RemoteException {
            enforceCallingOrSelfPermission(MODIFY_PHONE_STATE, "turnOffIms");
            synchronized (mImsServiceImplMap) {
                ImsServiceImpl feature = getMMTelFeature(slotId);
                if (feature != null) {
                    feature.turnOffIms();
                }
            }
        }
    };

    private final IImsUtEx.Stub mImsUtExBinder = new IImsUtEx.Stub() {
        /**
         * Retrieves the configuration of the call forward.
         */
        public int setCallForwardingOption(int phoneId,
                int commandInterfaceCFAction, int commandInterfaceCFReason,
                int serviceClass, String dialingNumber, int timerSeconds,
                String ruleSet) {
            ImsServiceImpl service = mImsServiceImplMap.get(new Integer(
                    phoneId + 1));
            if (service == null) {
                Log.e(TAG, "Invalid phoneId " + phoneId);
                return -1;
            }
            ImsUtProxy ut = (ImsUtProxy) service.getUTProxy();
            return ut.setCallForwardingOption(commandInterfaceCFAction,
                    commandInterfaceCFReason, serviceClass, dialingNumber,
                    timerSeconds, ruleSet);
        }

        /**
         * Updates the configuration of the call forward.
         */
        public int getCallForwardingOption(int phoneId,
                int commandInterfaceCFReason, int serviceClass, String ruleSet) {
            ImsServiceImpl service = mImsServiceImplMap.get(new Integer(
                    phoneId + 1));
            if (service == null) {
                Log.e(TAG, "Invalid phoneId " + phoneId);
                return -1;
            }
            ImsUtProxy ut = (ImsUtProxy) service.getUTProxy();
            return ut.getCallForwardingOption(commandInterfaceCFReason,
                    serviceClass, ruleSet);
        }

        /**
         * Sets the listener.
         */
        public void setListenerEx(int phoneId, IImsUtListenerEx listener) {
            ImsServiceImpl service = mImsServiceImplMap.get(new Integer(
                    phoneId + 1));
            if (service == null) {
                Log.e(TAG, "Invalid phoneId " + phoneId);
                return;
            }
            ImsUtProxy ut = (ImsUtProxy) service.getUTProxy();
            ut.setListenerEx(listener);
        }

        public int setFacilityLock(int phoneId, String facility,
                boolean lockState, String password, int serviceClass) {
            ImsServiceImpl service = mImsServiceImplMap.get(new Integer(
                    phoneId + 1));
            if (service == null) {
                Log.e(TAG, "Invalid phoneId " + phoneId);
                return -1;
            }
            ImsUtProxy ut = (ImsUtProxy) service.getUTProxy();
            return ut.setFacilityLock(facility, lockState, password,
                    serviceClass);
        }

        public int changeBarringPassword(int phoneId, String facility,
                String oldPwd, String newPwd) {
            ImsServiceImpl service = mImsServiceImplMap.get(new Integer(
                    phoneId + 1));
            if (service == null) {
                Log.e(TAG, "Invalid phoneId " + phoneId);
                return -1;
            }
            ImsUtProxy ut = (ImsUtProxy) service.getUTProxy();
            return ut.changeBarringPassword(facility, oldPwd, newPwd);
        }

        public int queryFacilityLock(int phoneId, String facility,
                String password, int serviceClass) {
            ImsServiceImpl service = mImsServiceImplMap.get(new Integer(
                    phoneId + 1));
            if (service == null) {
                Log.e(TAG, "Invalid phoneId " + phoneId);
                return -1;
            }
            ImsUtProxy ut = (ImsUtProxy) service.getUTProxy();
            return ut.queryFacilityLock(facility, password, serviceClass);
        }

        public int queryRootNode(int phoneId) {
            ImsServiceImpl service = mImsServiceImplMap.get(new Integer(
                    phoneId + 1));
            if (service == null) {
                Log.e(TAG, "Invalid phoneId " + phoneId);
                return -1;
            }
            ImsUtProxy ut = (ImsUtProxy) service.getUTProxy();
            return ut.queryRootNode();
        }
    };

    public IImsConfig getConfigInterface(int serviceId) {
        ImsServiceImpl service = mImsServiceImplMap.get(new Integer(serviceId));
        if (service == null) {
            Log.e(TAG, "getConfigInterface->Invalid serviceId " + serviceId);
            return null;
        }
        return service.getConfigInterface();
    }

    /*UNISOC: Add for bug909030{@*/
    public void setCallType(int callType) {
        if (callType == CallType.VOLTE_CALL) {
            mIsVolteCall = true;
        } else if (callType == CallType.WIFI_CALL) {
            mIsVowifiCall = true;
        }
    }

    public void setInCallPhoneId(int phoneId) {
        mInCallPhoneId = phoneId;
    }
    /*@}*/

    private final IImsServiceEx.Stub mImsServiceExBinder = new IImsServiceEx.Stub() {

        /**
         * Used for switch IMS feature.
         * 
         * @param type : ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_LTE = 0;
         *            ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_WIFI = 2;
         * @return: request id
         */
        @Override
        public int switchImsFeature(int type) {
            // If current ims register state is registed, and same as the switch
            // to, will do nothing.
            if ((mWifiRegistered && type == ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_WIFI)
                    || (isVoLTERegisted(ImsRegister.getPrimaryCard(mPhoneCount) + 1) && type == ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_LTE)) { // UNISOC: modify for bug1008539
                // Do nothing, return -1.
                Log.w(TAG, "Needn't switch to type " + type
                        + " as it already registed.");
                return -1;
            } else if ((mReleaseVowifiRequest != null) && (type == ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_WIFI)) { //UNISOC:add for bug1038496
                // Do nothing, return -1.
                Log.w(TAG, "Cann't switch to type " + type + " as release or cancel vowifi action ongoing.");
                return -1;
            } else {
                int id = getReuestId();
                mHandler.obtainMessage(ACTION_SWITCH_IMS_FEATURE, id, type)
                        .sendToTarget();
                return id;
            }
        }

        /**
         * Used for start IMS handover.
         * 
         * @param targetType : ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_LTE = 0;
         *            ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_WIFI = 2;
         * @return: request id
         */
        @Override
        public int startHandover(int targetType) {
            int id = getReuestId();
            mHandler.obtainMessage(ACTION_START_HANDOVER, id, targetType)
                    .sendToTarget();
            return id;
        }

        /**
         * Used for notify network unavailable.
         */
        @Override
        public void notifyNetworkUnavailable() {
            mHandler.obtainMessage(ACTION_NOTIFY_NETWORK_UNAVAILABLE)
                    .sendToTarget();
        }

        /* UNISOC: Modify for bug950573 @{*/
        /**
         * Used for get IMS feature for main sim card.
         *
         * @return: ImsConfig.FeatureConstants.FEATURE_TYPE_UNKNOWN = -1;
         *          ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_LTE = 0;
         *          ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_WIFI = 2;
         */
        @Override
        public int getCurrentImsFeature() {
            int currentImsFeature = getPrimaryCardImsFeature();
            return currentImsFeature;
        }
        /*@}*/

        /* UNISOC: Add for bug950573 @{*/
        /**
         * Used for get IMS feature for specific sim card.
         *
         * @param: phoneId:  phoneId to get currentImsFeature
         * @return: ImsConfig.FeatureConstants.FEATURE_TYPE_UNKNOWN = -1;
         *          ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_LTE = 0;
         *          ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_WIFI = 2;
         */

        @Override
        public int getCurrentImsFeatureForPhone(int phoneId) {
            int currentImsFeature = getImsFeature(phoneId + 1);
            return currentImsFeature;
        }
        /*@}*/

        /* UNISOC: Add for bug1119747 @{*/
        /**
         * Used for get vowifi attach status.
         */
        @Override
        public boolean isVoWifiAttached() {
            return mAttachVowifiSuccess;
        }
        /*@}*/

        /**
         * Used for set IMS service listener.
         */
        @Override
        public void setImsServiceListener(IImsServiceListenerEx listener) {
            mImsServiceListenerEx = listener;
        }

        /**
         * Used for set release VoWifi Resource.
         */
        @Override
        public int releaseVoWifiResource() {
            int id = getReuestId();
            mHandler.obtainMessage(ACTION_RELEASE_WIFI_RESOURCE, id, -1)
                    .sendToTarget();
            return id;
        }

        /**
         * Used for set VoWifi unavailable. param wifiState: wifi_disabled = 0; wifi_enabled = 1;
         * return: request id
         */
        @Override
        public int setVoWifiUnavailable(int wifiState, boolean isOnlySendAT) {
            int id = getReuestId();
            mHandler.obtainMessage(ACTION_NOTIFY_VOWIFI_UNAVAILABLE, id,
                    wifiState, new Boolean(isOnlySendAT)).sendToTarget();
            return id;
        }

        /**
         * Used for get IMS register address.
         */
        @Override
        public String getImsRegAddress() {
            ImsServiceImpl service = mImsServiceImplMap.get(Integer
                    .valueOf(ImsRegister.getPrimaryCard(mPhoneCount) + 1));
            if (service == null) {
                Log.e(TAG, "getImsRegAddress->Invalid service id = "
                        + ImsRegister.getPrimaryCard(mPhoneCount) + 1);
                return null;
            }
            return service.getIMSRegAddress();
        }

        /**
         * Used for cancel current switch or handover request. return: request id
         */
        @Override
        public int cancelCurrentRequest() {
            int id = getReuestId();
            mHandler.obtainMessage(ACTION_CANCEL_CURRENT_REQUEST, id, -1)
                    .sendToTarget();
            return id;
        }

        /**
         * Used for register IMS register listener.
         */
        @Override
        public void registerforImsRegisterStateChanged(
                IImsRegisterListener listener) {
            if (listener == null) {
                Log.w(TAG,
                        "registerforImsRegisterStateChanged->Listener is null!");
                Thread.dumpStack();
                return;
            }
            synchronized (mImsRegisterListeners) {
                if (!mImsRegisterListeners.keySet().contains(
                        listener.asBinder())) {
                    mImsRegisterListeners.put(listener.asBinder(), listener);
                    Log.i(TAG," registerforImsRegisterStateChanged() -> notifyListenerWhenRegister");
                    notifyListenerWhenRegister(listener);
                } else {
                    Log.w(TAG, "Listener already add :" + listener);
                }
            }
        }

        /**
         * Used for unregister IMS register listener.
         */
        @Override
        public void unregisterforImsRegisterStateChanged(
                IImsRegisterListener listener) {
            if (listener == null) {
                Log.w(TAG,
                        "unregisterforImsRegisterStateChanged->Listener is null!");
                Thread.dumpStack();
                return;
            }

            synchronized (mImsRegisterListeners) {
                if (mImsRegisterListeners.keySet()
                        .contains(listener.asBinder())) {
                    mImsRegisterListeners.remove(listener.asBinder());
                } else {
                    Log.w(TAG, "Listener not find " + listener);
                }
            }
        }

        /**
         * Used for terminate VoWifi/Volte calls. param wifiState: wifi_disabled = 0; wifi_enabled =
         * 1;
         */
        @Override
        public void terminateCalls(int wifiState) {
            terminateAllCalls(wifiState);
        }

        /**
         * Used for get P-CSCF address. return: P-CSCF address
         */
        @Override
        public String getCurPcscfAddress() {
            Log.i(TAG,"getCurPcscfAddress mIsVolteCall="+mIsVolteCall+" mIsVowifiCall="+mIsVowifiCall);
            String address = null;
            if(mIsVolteCall || (isVoLTEEnabled() && !mIsVowifiCall && !mIsVolteCall)){
                ImsServiceImpl imsService = mImsServiceImplMap.get(
                        Integer.valueOf(ImsRegister.getPrimaryCard(mPhoneCount)+1));
                if(imsService != null){
                    address = imsService.getImsPcscfAddress();
                }
            }else if(mIsVowifiCall || (isVoWifiEnabled() && !mIsVowifiCall && !mIsVolteCall)){
                if(mWifiService != null){
                    address = mWifiService.getCurPcscfAddress();
                }
            }
            return address;
        }

        /**
         * Used for set monitor period millis.
         */
        @Override
        public void setMonitorPeriodForNoData(int millis) {
            // Needn't, remove later.
        }

        /* SPRD: add for VoWiFi @{ */
        @Override
        public void showVowifiNotification() {
            Log.i(TAG, "showVowifiNotification");
            mNotificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            mNotificationManager.cancel(mCurrentVowifiNotification);
            /* SPRD: modify for bug762807 @{ */
            if(null == mVowifiChannel){
               createNotificationChannel();
            }
            mNotificationManager.createNotificationChannel(mVowifiChannel);
            /* @} */
            Intent intent = new Intent();
            intent.setClassName("com.android.settings",
                    "com.android.settings.Settings$WifiCallingSettingsActivity");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ImsManager.setWfcSetting(ImsService.this, false);
            CharSequence vowifiTitle = getText(R.string.vowifi_attation);
            CharSequence vowifiContent = getText(R.string.vowifi_regist_fail_content);
            /* SPRD: modify for bug762807 @{ */
            Notification notification =
                     new Notification.Builder(getApplicationContext())
                         .setContentTitle(vowifiTitle)
                         .setContentText(vowifiContent)
                         .setSmallIcon(android.R.drawable.stat_sys_warning)
                         .setWhen(System.currentTimeMillis())
                         .setChannelId(mVowifiChannel.getId())
                         .build();
            /* @} */
            mNotificationManager.notify(mCurrentVowifiNotification,
                    notification);
            mVowifiNotificationShown = true; // UNISOC: add for bug1153427
        }
        /* @} */

        /**
         * Used for get local address.
         */
        @Override
        public String getCurLocalAddress() {
            Log.i(TAG,"getCurLocalAddress mIsVolteCall="+mIsVolteCall+" mIsVowifiCall="+mIsVowifiCall);
            String address = null;
            if(mIsVolteCall || (isVoLTEEnabled() && !mIsVowifiCall && !mIsVolteCall)){
                ImsServiceImpl imsService = mImsServiceImplMap.get(
                        Integer.valueOf(ImsRegister.getPrimaryCard(mPhoneCount)+1));
                if(imsService != null){
                    address = imsService.getIMSRegAddress();
                }
            }else if(mIsVowifiCall || (isVoWifiEnabled() && !mIsVowifiCall && !mIsVolteCall)){
                if(mWifiService != null){
                    address = mWifiService.getCurLocalAddress();
                }
            }
            return address;
        }

        /**
         * Get current IMS video state. return: video state {VideoProfile#STATE_AUDIO_ONLY},
         * {VideoProfile#STATE_BIDIRECTIONAL}, {VideoProfile#STATE_TX_ENABLED},
         * {VideoProfile#STATE_RX_ENABLED}, {VideoProfile#STATE_PAUSED}.
         */
        @Override
        public int getCurrentImsVideoState() {
            CallManager cm = CallManager.getInstance();
            int videoState = VideoProfile.STATE_AUDIO_ONLY;
            Call call = null;

            if (cm.hasActiveRingingCall()) {
                call = cm.getFirstActiveRingingCall();
            } else if (cm.hasActiveFgCall()) {
                call = cm.getActiveFgCall();
            } else if (cm.hasActiveBgCall()) {
                call = cm.getFirstActiveBgCall();
            }

            if (call != null && call.getLatestConnection() != null) {
                videoState = call.getLatestConnection().getVideoState();
            }
            return videoState;
        }

        @Override
        public int getAliveCallLose() {
            Log.i(TAG, "getAliveCallLose->mIsVowifiCall:" + mIsVowifiCall
                    + " mIsVolteCall:" + mIsVolteCall);
            int aliveCallLose = -1;
            ImsServiceImpl imsService = mImsServiceImplMap.get(Integer
                    .valueOf(ImsRegister.getPrimaryCard(mPhoneCount) + 1));
            if (mIsVowifiCall) {
                if (mWifiService != null) {
                    aliveCallLose = mWifiService.getAliveCallLose();
                } else {
                    Log.i(TAG, "getAliveCallLose->VowifiServiceImpl is null");
                }
            } else if (mIsVolteCall) {
                if (imsService != null) {
                    aliveCallLose = imsService.getAliveCallLose();
                } else {
                    Log.i(TAG, "getAliveCallLose->ImsServiceImpl is null");
                }
            }
            return aliveCallLose;
        }

        @Override
        public int getAliveCallJitter() {
            Log.i(TAG, "getAliveCallJitter->mIsVowifiCall:" + mIsVowifiCall
                    + " mIsVolteCall:" + mIsVolteCall);
            int aliveCallJitter = -1;
            ImsServiceImpl imsService = mImsServiceImplMap.get(Integer
                    .valueOf(ImsRegister.getPrimaryCard(mPhoneCount) + 1));
            if (mIsVowifiCall) {
                if (mWifiService != null) {
                    aliveCallJitter = mWifiService.getAliveCallJitter();
                } else {
                    Log.i(TAG, "getAliveCallJitter->VowifiServiceImpl is null");
                }
            } else if (mIsVolteCall) {
                if (imsService != null) {
                    aliveCallJitter = imsService.getAliveCallJitter();
                } else {
                    Log.i(TAG, "getAliveCallJitter->ImsServiceImpl is null");
                }
            }
            return aliveCallJitter;
        }

        @Override
        public int getAliveCallRtt() {
            Log.i(TAG, "getAliveCallRtt->mIsVowifiCall:" + mIsVowifiCall
                    + " mIsVolteCall:" + mIsVolteCall);
            int aliveCallRtt = -1;
            ImsServiceImpl imsService = mImsServiceImplMap.get(Integer
                    .valueOf(ImsRegister.getPrimaryCard(mPhoneCount) + 1));
            if (mIsVowifiCall) {
                if (mWifiService != null) {
                    aliveCallRtt = mWifiService.getAliveCallRtt();
                } else {
                    Log.i(TAG, "getAliveCallRtt->VowifiServiceImpl is null");
                }
            } else if (mIsVolteCall) {
                if (imsService != null) {
                    aliveCallRtt = imsService.getAliveCallRtt();
                } else {
                    Log.i(TAG, "getAliveCallRtt->ImsServiceImpl is null");
                }
            }
            return aliveCallRtt;
        }

        @Override
        public int getVolteRegisterState() {
            int primaryPhoneId = ImsRegister.getPrimaryCard(mPhoneCount);
            int volteRegisterState = getVolteRegisterStateForPhone(primaryPhoneId);
            return volteRegisterState;
        }

        /* UNISOC: Add for bug972969  @{*/
        /**
         * Used for Volte register state for specific sim card.
         * param phoneId: identify specific sim card to get Volte register state
         */
        @Override
        public int getVolteRegisterStateForPhone(int phoneId) {
            ImsServiceImpl imsService = mImsServiceImplMap.get(Integer.valueOf(phoneId +1));
            int volteRegisterState = -1;
            if(imsService == null) {
                Log.i(TAG, "getVolteRegisterStateForPhone->ImsServiceImpl is null");
                return volteRegisterState;
            }
            /* UNISOC: Modify for bug1065583 @{ */
            if (imsService.isRadioAvailableForImsService()) {
                volteRegisterState = imsService.getVolteRegisterState();
            } else {
                volteRegisterState = IMS_REG_STATE_INACTIVE;
            }
            /*@}*/
            return volteRegisterState;
        }

        @Override
        public int getCallType() {
            if (mIsVolteCall) {
                return CallType.VOLTE_CALL;
            } else if (mIsVowifiCall) {
                return CallType.WIFI_CALL;
            }
            return CallType.NO_CALL;
        }

        /**
         * notify SRVCC Call Info
         */
        @Override
        public void notifySrvccCallInfos(List<ImsSrvccCallInfo> list) {
            ImsServiceImpl imsService = mImsServiceImplMap.get(Integer
                    .valueOf(ImsRegister.getPrimaryCard(mPhoneCount) + 1));
            if (list != null && imsService != null) {
                StringBuffer strBuf = new StringBuffer();
                for (int i = 0; i < list.size(); i++) {
                    strBuf.append(((ImsSrvccCallInfo) list.get(i)).toAtCommands()); // UNISOC: modify for bug1195534
                    if (i != list.size() - 1) {
                        strBuf.append(",");
                    }
                }
                String commands = strBuf.toString();
                Log.i(TAG, "notifySrvccCallInfos->commands:" + commands);
                imsService.notifyHandoverCallInfo(commands);
            }
        }

        /**
         * Used for get local address.
         */
        public String getImsPcscfAddress() {
            ImsServiceImpl imsService = mImsServiceImplMap.get(Integer
                    .valueOf(ImsRegister.getPrimaryCard(mPhoneCount) + 1));
            if (imsService != null) {
                return imsService.getImsPcscfAddress();
            }
            return "";
        }

        /**
         * used for set register or de-regesiter Vowifi para action 0 de-register before start call
         * 1 register after call end
         **/
        public void setVowifiRegister(int action) {
            try {
                if (mImsServiceListenerEx != null) {
                    mImsServiceListenerEx.onSetVowifiRegister(action);
                }
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }

        /**
         * Used for add IMS PDN State Listener.
         */
        public void addImsPdnStateListener(int slotId,
                IImsPdnStateListener listener) {
            if (slotId < 0 || slotId >= mPhoneCount) {
                Log.w(TAG, "addImsPdnStateListener->slotId:" + slotId);
                return;
            }
            ImsServiceImpl imsService = mImsServiceImplMap.get(slotId + 1);
            if(imsService != null) {
                imsService.addImsPdnStateListener(listener);
            }
        }

        /**
         * Used for remove IMS PDN State Listener.
         */
        public void removeImsPdnStateListener(int slotId,
                IImsPdnStateListener listener) {
            if (slotId < 0 || slotId >= mPhoneCount) {
                Log.w(TAG, "removeImsPdnStateListener->slotId:" + slotId);
                return;
            }
            ImsServiceImpl imsService = mImsServiceImplMap.get(slotId + 1);
            if(imsService != null) {
                imsService.removeImsPdnStateListener(listener);
            }
        }

        /**
         * used for VOWIFI get CLIR states from CP return: ut request id
         **/
        @Override
        public int getCLIRStatus(int phoneId) {
            ImsServiceImpl imsService = mImsServiceImplMap.get(Integer
                    .valueOf(phoneId + 1));
            int id = -1;
            Log.i(TAG, "getCLIRStatus phoneId = " + phoneId);
            if (imsService != null) {
                ImsUtImpl ut = imsService.getUtImpl();
                if (ut != null) {
                    id = ut.getCLIRStatus();
                    return id;
                }
            }
            return id;
        }

        public int updateCLIRStatus(int action) {
            Log.i(TAG, "updateCLIRStatus action = " + action);
            SystemProperties.set("gsm.ss.clir", String.valueOf(action));
            return 1;
        }

        @Override
        public void notifyVideoCapabilityChange() {
            mHandler.removeMessages(ACTION_NOTIFY_VIDEO_CAPABILITY_CHANGE);
            mHandler.sendMessageDelayed(mHandler
                    .obtainMessage(ACTION_NOTIFY_VIDEO_CAPABILITY_CHANGE), 100);
        }

        /**
         * used for get CW status for vowifi
         * para phone id
         *
         **/
        @Override
        public void getCallWaitingStatus(int phoneId){
            ImsServiceImpl imsService = mImsServiceImplMap.get(Integer
                    .valueOf(phoneId + 1));
            Log.i(TAG, "getCallWaitingStatus phoneId = " + phoneId);
            if (imsService != null) {
                ImsUtImpl ut = imsService.getUtImpl();
                if (ut != null) {
                    ut.getCallWaitingStatusForVoWifi();
                }
            }
        }

        /**
         * Used for start Mobike
         */
        @Override
        public void startMobike(){
            mWifiService.startMobike();
        }

        /**
         * Used for check Mobike whether support
         */
        @Override
        public boolean isSupportMobike(){
            return mWifiService.isSupportMobike();
        }
        /**
         * Used for get ims CNI infor
         */
        @Override
        public void getImsCNIInfor(){
            ImsServiceImpl imsService = mImsServiceImplMap.get(
                    Integer.valueOf(ImsRegister.getPrimaryCard(mPhoneCount)+1));
            if(imsService != null){
                imsService.getImsCNIInfo();
            }
        }
    };

    private final IImsMultiEndpoint.Stub mImsMultiEndpointBinder = new IImsMultiEndpoint.Stub() {
        /**
         * Sets the listener.
         */
        @Override
        public void setListener(IImsExternalCallStateListener listener) {
        }

        /**
         * Query api to get the latest Dialog Event Package information Should be invoked only after
         * setListener is done
         */
        @Override
        public void requestImsExternalCallStateInfo() {
        }

    };

    private void notifyListenerWhenRegister(IImsRegisterListener listener){
        int currentImsFeature = getPrimaryCardImsFeature(); // UNISOC: Add for bug950573
        boolean isImsRegistered = ((currentImsFeature == ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_LTE)
                || (currentImsFeature == ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_WIFI));
        //SPRD: add for bug 823104
        isImsRegistered  = isImsRegistered || mVolteAvailable; // UNISOC: Modify for bug1065583,bug1155169

        Log.i(TAG," notifyListenerWhenRegister() -> isImsRegistered: "+isImsRegistered+" mVolteAvailable:"+mVolteAvailable);
        synchronized (mImsRegisterListeners) {
            try {
                listener.imsRegisterStateChange(isImsRegistered);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
    }

    public void notifyImsRegisterState() {
        updateImsRegisterState();
        int currentImsFeature = getPrimaryCardImsFeature(); // UNISOC: Add for bug950573
        boolean isImsRegistered = ((currentImsFeature == ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_LTE)
                || (currentImsFeature == ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_WIFI));
        //SPRD: add for bug 771875
        isImsRegistered  = isImsRegistered || mVolteAvailable; // UNISOC: Modify for bug1065583,bug1155169

        Log.i(TAG," notifyImsRegisterState() -> isImsRegistered: "+isImsRegistered
                  + " primarycard currentImsFeature:" + currentImsFeature
                + " mVolteAvailable:" + mVolteAvailable);
        synchronized (mImsRegisterListeners) {
            /**
             * SPRD bug647508 & 815956
             */
            for (IImsRegisterListener l : mImsRegisterListeners.values()) {
                try {
                    l.imsRegisterStateChange(isImsRegistered);
                } catch (RemoteException e) {
                    iLog("DeadObjectException : l = " + l);
                    continue;
                } catch (NullPointerException e) {
                    iLog("NullPointerException : l = " + l);
                }
            }
        }
    }

    public void updateImsRegisterState() {
        synchronized (mImsRegisterListeners) {
            for (Entry<Integer, ImsServiceImpl> entry : mImsServiceImplMap.entrySet()) { // UNISOC: modify for bug1195534
                ImsServiceImpl service = entry.getValue();
                if (service != null && service.isImsRegisterState() && service.isRadioAvailableForImsService()) { // UNISOC: Modify for bug1065583
                    mVolteAvailable = true;
                    return;
                }
            }
            mVolteAvailable = false;
        }
    }

    private int getReuestId() {
        synchronized (mRequestLock) {
            mRequestId++;
            if (mRequestId > 100) {
                mRequestId = 0;
            }
            return mRequestId;
        }
    }

    class MyVoWifiCallback implements VoWifiCallback {
        @Override
        public void onAttachFinished(boolean success, int errorCode) {
            if (success) {
                // Attach success, send the event to handler.
                SecurityConfig config = mWifiService.getSecurityConfig();
                mHandler.obtainMessage(EVENT_WIFI_ATTACH_SUCCESSED, -1, -1,
                        config).sendToTarget();
            } else {
                // Attach failed, send the event to handler.
                mHandler.obtainMessage(EVENT_WIFI_ATTACH_FAILED, errorCode, -1,
                        null).sendToTarget();
            }
        }

        @Override
        public void onAttachStopped(int stoppedReason) {
            mHandler.obtainMessage(EVENT_WIFI_ATTACH_STOPED).sendToTarget();
        }

        @Override
        public void onAttachStateChanged(int state) {
            mHandler.obtainMessage(EVENT_WIFI_ATTACH_STATE_UPDATE, state, -1,
                    null).sendToTarget();
        }

        @Override
        public void onRegisterStateChanged(int state, int errorCode) {
            mHandler.obtainMessage(EVENT_WIFI_REGISTER_RESAULT, errorCode, -1,
                    new Boolean(state == RegisterState.STATE_CONNECTED))
                    .sendToTarget();
        }

        @Override
        public void onReregisterFinished(boolean isSuccess, int errorCode) {
            mHandler.obtainMessage(EVENT_WIFI_REFRESH_RESAULT, errorCode, -1,
                    new Boolean(isSuccess)).sendToTarget();
        }

        /* UNISOC: Add for bug1007100{@ */
        @Override
        public void onResetStarted() {
            mHandler.obtainMessage(EVENT_WIFI_RESET_START).sendToTarget();
        }
        /* @} */

        @Override
        public void onResetFinished(int result, int errorCode) {
            mHandler.obtainMessage(EVENT_WIFI_RESET_RESAULT, result, errorCode,
                    null).sendToTarget();
        }

        /* SPRD: Add for notify data router{@ */
        @Override
        public void onUpdateDRStateFinished() {
            mHandler.obtainMessage(EVENT_UPDATE_DATA_ROUTER_FINISHED)
                    .sendToTarget();
        }

        /* @} */

        @Override
        public void onCallIncoming(IImsCallSession callSession) {
            mHandler.obtainMessage(EVENT_WIFI_INCOMING_CALL, -1, -1, callSession)
                    .sendToTarget();
        }

        @Override
        public void onAliveCallUpdate(boolean isVideoCall) {
            onVideoStateChanged(isVideoCall ? VideoProfile.STATE_BIDIRECTIONAL
                    : VideoProfile.STATE_AUDIO_ONLY);
        }

        @Override
        public void onAllCallsEnd() {
            mHandler.obtainMessage(EVENT_WIFI_ALL_CALLS_END).sendToTarget();
        }

        @Override
        public void onMediaQualityChanged(boolean isVideo, int lose,
                int jitter, int rtt) {
            try {
                if (mImsServiceListenerEx != null) {
                    mImsServiceListenerEx.onMediaQualityChanged(isVideo, lose,
                            jitter, rtt);
                }
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }

        /**
         * notify the DPD disconnect event.
         */
        // @Override
        public void onDPDDisconnected() {
            mHandler.obtainMessage(EVENT_WIFI_DPD_DISCONNECTED).sendToTarget();
        }

        /**
         * notify no RTP event.
         */
        public void onNoRtpReceived(boolean isVideo) {
            mHandler.obtainMessage(EVENT_WIFI_NO_RTP, -1, -1,
                    new Boolean(isVideo)).sendToTarget();
        }

        public void onRtpReceived(boolean isVideo) {
            mHandler.obtainMessage(EVENT_WIFI_RTP_RECEIVED, -1, -1,
                    new Boolean(isVideo)).sendToTarget();
        }

        @Override
        public void onUnsolicitedUpdate(int stateCode) {
            mHandler.obtainMessage(EVENT_WIFI_UNSOL_UPDATE, stateCode, -1)
                    .sendToTarget();
        }
    }

    class VoLTERegisterListener implements ImsServiceImpl.Listener {
        @Override
        public void onRegisterStateChange(int serviceId) {
            try {
                /* SPRD: Modify for bug596304{@ */
                ImsServiceImpl service = mImsServiceImplMap.get(Integer
                        .valueOf(serviceId));
                if (service == null) {
                    Log.i(TAG, "VoLTERegisterListener service is null");
                    return;
                }
                Log.i(TAG,
                        "VoLTERegisterListener-> mFeatureSwitchRequest:"
                                + mFeatureSwitchRequest + " mIsCalling:"
                                + mIsCalling + " volteRegistered:"
                                + isVoLTERegisted(serviceId)
                                + " service.isImsRegistered():"
                                + service.isImsRegistered() + " mIsLoggingIn:"
                                + mIsLoggingIn + " mIsPendingRegisterVolte:"
                                + mIsPendingRegisterVolte);
                if (service.getVolteRegisterState() == IMS_REG_STATE_REGISTERING
                        || service.getVolteRegisterState() == IMS_REG_STATE_DEREGISTERING) {
                    Log.i(TAG,
                            "VoLTERegisterListener-> pending status service.getVolteRegisterState():"
                                    + service.getVolteRegisterState()
                                    + " mIsVolteCall:" + mIsVolteCall
                                    + " mIsWifiCalling:" + mIsWifiCalling);
                    return;
                }
                // SPRD:add for bug674494
                if (mFeatureSwitchRequest == null && mIsPendingRegisterVolte) {
                    mIsPendingRegisterVolte = false;
                }
                // If CP reports CIREGU as 1,3 , IMS Feature will be updated as
                // Volte registered state firstly.
                if (service.getVolteRegisterState() == IMS_REG_STATE_REGISTERED
                        || service.getVolteRegisterState() == IMS_REG_STATE_REG_FAIL) {
                    //mVolteAvailable = (service.getVolteRegisterState() == IMS_REG_STATE_REGISTERED); // UNISOC: modify for bug1008539
                    // SPRD: 730973
                    service.setVolteRegisterStateOld(service.isImsRegistered());
                    if (!mIsLoggingIn) {
                        updateImsFeature(serviceId);
                    }
                } else {
                    // SPRD: 730973
//                  if (mVolteAvailable != service.isImsRegistered()){
                    if (service.getVolteRegisterStateOld() != service.isImsRegistered()) {
                        service.setVolteRegisterStateOld(service.isImsRegistered());
                        //mVolteAvailable = service.isImsRegistered(); // UNISOC: modify for bug1008539
                        if (!mIsLoggingIn) {
                            updateImsFeature(serviceId);
                        }
                    }
                }
                /* @} */
                if (mFeatureSwitchRequest != null
                        && mFeatureSwitchRequest.mServiceId == serviceId
                        && mFeatureSwitchRequest.mTargetType == ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_LTE) {
                    if (service.isImsRegistered()) {
                        if (!(mIsVolteCall && mIsCalling && mFeatureSwitchRequest.mEventCode == ACTION_START_HANDOVER)) { // UNISOC: modify for bug1176896
                            if (mIsPendingRegisterVolte && mWifiService != null) {
                                mWifiService.resetAll(WifiState.DISCONNECTED);
                            } else if (mWifiService != null) {
                                mWifiService.deregister();
                                mWifiService.deattach();
                                mWifiRegistered = false;// Set wifi registered state as false when make
                                                        // de-register operation in handover.
                                updateImsFeature(serviceId);
                            }
                            mIsPendingRegisterVolte = false;
                        }
                        if (mImsServiceListenerEx != null) {
                            if (mFeatureSwitchRequest.mEventCode == ACTION_SWITCH_IMS_FEATURE) {
                                Log.i(TAG,
                                        "VoLTERegisterListener -> operationSuccessed -> IMS_OPERATION_SWITCH_TO_VOLTE");
                                mImsServiceListenerEx
                                        .operationSuccessed(
                                                mFeatureSwitchRequest.mRequestId,
                                                ImsOperationType.IMS_OPERATION_SWITCH_TO_VOLTE);
                                mFeatureSwitchRequest = null;
                            }
                        } else {
                            Log.w(TAG,
                                    "VoLTERegisterListener -> operationSuccessed, mImsServiceListenerEx is null!");
                        }
                    } else if (mImsServiceListenerEx != null) {
                        if (mFeatureSwitchRequest.mEventCode == ACTION_SWITCH_IMS_FEATURE) {
                            Log.i(TAG,
                                    "VoLTERegisterListener -> operationFailed -> IMS_OPERATION_SWITCH_TO_VOLTE");
                            mImsServiceListenerEx
                                    .operationFailed(
                                            mFeatureSwitchRequest.mRequestId,
                                            "VoLTE register failed",
                                            ImsOperationType.IMS_OPERATION_SWITCH_TO_VOLTE);
                            mFeatureSwitchRequest = null;
                            mIsPendingRegisterVolte = false;
                        }
                    } else {
                        Log.w(TAG,
                                "VoLTERegisterListener -> operationFailed, mImsServiceListenerEx is null!");
                    }
                    Log.i(TAG,
                            "VoLTERegisterListener-> mPendingActivePdnSuccess"
                                    + mPendingActivePdnSuccess
                                    + " mIsCPImsPdnActived:"
                                    + mIsCPImsPdnActived + " mIsCalling:"
                                    + mIsCalling);
                    if (!mPendingActivePdnSuccess
                            && mIsCPImsPdnActived
                            && mFeatureSwitchRequest != null
                            && mFeatureSwitchRequest.mEventCode == ACTION_START_HANDOVER
                            && !mIsCalling) {// SPRD:add for bug718074
                        Log.i(TAG,
                                "VoLTERegisterListener -> ACTION_START_HANDOVER, clear mFeatureSwitchRequest");
                        mFeatureSwitchRequest = null;
                        mIsPendingRegisterVolte = false;
                    }
                }
                int currentImsFeature = getImsFeature(serviceId); // UNISOC: Add for bug950573
                Log.i(TAG, "VoLTERegisterListener-> currentImsFeature:"
                        + currentImsFeature + "serviceId:" + serviceId
                        + "service.isImsRegistered():" + service.isImsRegistered());
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onSessionEmpty(int serviceId) {
            ImsServiceImpl impl = mImsServiceImplMap.get(Integer
                    .valueOf(serviceId));
            //SPRD: Modify for bug825528
            if((serviceId - 1) == ImsRegister.getPrimaryCard(mPhoneCount)){
                /* SPRD: Add for bug586758 and 595321{@ */
                if (impl != null) {
                    if (impl.isVolteSessionListEmpty()
                            && impl.isVowifiSessionListEmpty()) {
                        mCallEndType = CallEndEvent.VOLTE_CALL_END;
                        /* SPRD: Modify for bug595321 and 610799 and 1000346{@ */
                        Log.i(TAG, "onSessionEmpty-> mFeatureSwitchRequest:"
                                + mFeatureSwitchRequest + " mIsVowifiCall:"
                                + mIsVowifiCall + " mIsVolteCall:"
                                + mIsVolteCall + " mInCallHandoverFeature"
                                + mInCallHandoverFeature
                                +" mIsPendingRegisterVowifi: "+mIsPendingRegisterVowifi
                                +" mIsAPImsPdnActived: "+mIsAPImsPdnActived
                                +" mIsCPImsPdnActived: "+mIsCPImsPdnActived
                                +" impl.isImsRegistered(): "+(impl.isImsRegistered()));
                        if(mFeatureSwitchRequest == null && mIsVolteCall && !mIsPendingRegisterVowifi && !impl.isImsRegistered()
                                && !mIsAPImsPdnActived && mIsCPImsPdnActived){
                           impl.enableImsWhenPDNReady();
                        }
                        if (mFeatureSwitchRequest != null
                                && mFeatureSwitchRequest.mEventCode == ACTION_START_HANDOVER
                                && serviceId == mFeatureSwitchRequest.mServiceId) {
                            if (mInCallHandoverFeature != mFeatureSwitchRequest.mTargetType) {
                                if (mFeatureSwitchRequest.mTargetType == ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_WIFI) {
                                    mPendingAttachVowifiSuccess = true;
                                } else if (mFeatureSwitchRequest.mTargetType == ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_LTE) {
                                    mPendingActivePdnSuccess = true;
                                }
                            }
                            if ((mFeatureSwitchRequest.mTargetType == ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_WIFI)
                                    || ((mFeatureSwitchRequest.mTargetType == ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_LTE) // UNISOC: modify for bug983182
                                    && (mInCallHandoverFeature == ImsConfig.FeatureConstants.FEATURE_TYPE_UNKNOWN))){
                                if (mIsVolteCall && mIsPendingRegisterVowifi) {
                                    mWifiService.register();
                                    mIsLoggingIn = true;
                                    mFeatureSwitchRequest = null;
                                }
                                mIsPendingRegisterVowifi = false;
                            }
                            Log.i(TAG,
                                    "onSessionEmpty-> mPendingAttachVowifiSuccess:"
                                            + mPendingAttachVowifiSuccess
                                            + " mPendingActivePdnSuccess:"
                                            + mPendingActivePdnSuccess
                                            + " mIsLoggingIn:" + mIsLoggingIn);
                            if (mIsVolteCall
                                    && mFeatureSwitchRequest != null //SPRD modify for bug 751516
                                    && mFeatureSwitchRequest.mTargetType == ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_LTE
                                    && !mPendingAttachVowifiSuccess
                                    && !mPendingActivePdnSuccess) {
                                mFeatureSwitchRequest = null;
                                Log.i(TAG,
                                        "onSessionEmpty-> This is volte call,so mFeatureSwitchRequest has been emptyed.");
                            }
                        }
                        int currentImsFeature = getImsFeature(serviceId); // UNISOC: Add for bug950573
                        if ((currentImsFeature != ImsConfig.FeatureConstants.FEATURE_TYPE_UNKNOWN) || mIsVolteCall) { //UNISOC: modify for bug1126295
                            Log.i(TAG, "onSessionEmpty->currentImsFeature:"
                                    + currentImsFeature + " mIsVolteCall:" + mIsVolteCall);
                            updateInCallState(false);
                        }
                        mInCallHandoverFeature = ImsConfig.FeatureConstants.FEATURE_TYPE_UNKNOWN;
                        if (!mPendingAttachVowifiSuccess
                                && !mPendingActivePdnSuccess) {
                            mWifiService
                                    .updateCallRatState(CallRatState.CALL_NONE);
                        }
                        /* @} */
                        if (mIsVowifiCall) {
                            mIsVowifiCall = false;
                        } else if (mIsVolteCall) {
                            mIsVolteCall = false;
                        }
                    }
                } else {
                    Log.i(TAG, "onSessionEmpty->ImsServiceImpl is null");
                }
                /* @} */
                Log.i(TAG, "onSessionEmpty->serviceId: " + serviceId
                        + "mIsVolteCall: " + mIsVolteCall + " mIsVowifiCall:"
                        + mIsVowifiCall + "mInCallHandoverFeature: "
                        + mInCallHandoverFeature);
            } else {
                /* SPRD: Add for bug807691 {@ */
                ImsServiceImpl implPrimay = mImsServiceImplMap.get(Integer.valueOf(ImsRegister.getPrimaryCard(mPhoneCount)+1));
                Log.i(TAG,"onSessionEmpty-> serviceId: "+serviceId + " mIsVolteCall:" + mIsVolteCall +"  impl: "+ impl +"  implPrimay: "+implPrimay);
                if((impl != null && (impl.isVolteSessionListEmpty())) && (implPrimay != null && (implPrimay.isVolteSessionListEmpty()))){
                     if (mIsVolteCall) {
                         mIsVolteCall = false;
                     }
                }
                /* @} */
            }
            //SPRD: add for bug825528
            if(mMakeCallPrimaryCardServiceId != -1)
                mMakeCallPrimaryCardServiceId = -1;
        }
    }

    public boolean isImsEnabled() {
        int currentImsFeature = getPrimaryCardImsFeature(); // UNISOC: Add for bug950573
        return (currentImsFeature == ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_LTE || currentImsFeature == ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_WIFI);
    }

    public boolean isVoWifiEnabled() {
        int currentImsFeature = getPrimaryCardImsFeature(); // UNISOC: Add for bug950573
        return (currentImsFeature == ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_WIFI);
    }

    public boolean isVoLTEEnabled() {
        int currentImsFeature = getPrimaryCardImsFeature(); // UNISOC: Add for bug950573
        return (currentImsFeature == ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_LTE);
    }

    public void updateImsFeature() {
        int serviceId = Integer
                .valueOf(ImsRegister.getPrimaryCard(mPhoneCount) + 1);
        updateImsFeature(serviceId);
    }

    public void updateImsFeatureForAllService() {
        synchronized (mImsRegisterListeners) {
            for (Entry<Integer, ImsServiceImpl> entry : mImsServiceImplMap.entrySet()) { // UNISOC: modify for bug1195534
                ImsServiceImpl service = entry.getValue();
                if (service != null && (service.getVolteRegisterState() != IMS_REG_STATE_REGISTERING  // UNISOC: Add for bug1038497
                        || (!ImsManager.isWfcEnabledByUser(getApplicationContext()) || mWifiState == 0) && isVoWifiEnabled())) { // UNISOC: Add for bug1285106
                    updateImsFeature(service.getServiceId());
                }
            }
        }
    }

    /* UNISOC: Add for bug880865{@ */
    public void updateImsFeatureForDataChange() {
        synchronized (mImsRegisterListeners) {

            int primaryPhoneId = ImsRegister.getPrimaryCard(mPhoneCount);
            ImsServiceImpl imsService = mImsServiceImplMap.get(Integer.valueOf(primaryPhoneId +1));
            if(imsService != null) {
                //update Ims Feature for primary card
                updateImsFeature(imsService.getServiceId());
            }

            for (Entry<Integer, ImsServiceImpl> entry : mImsServiceImplMap.entrySet()) { // UNISOC: modify for bug1195534
                Integer id = entry.getKey();
                if(id != (primaryPhoneId +1))
                {
                   //update Ims Feature for secondary card
                   imsService = entry.getValue();
                   if(imsService != null) {
                       updateImsFeature(imsService.getServiceId());
                   }
                }
            }
        }
    }
    /* @} */

    /* UNISOC: Add for bug1008539{@ */
    public void updateVoWifiLocalAddr() {
        ImsServiceImpl imsService = mImsServiceImplMap.get(Integer.valueOf(ImsRegister.getPrimaryCard(mPhoneCount) +1));
        if(imsService != null) {
            //update VoWifi Local Addr with primary card address
            setVoWifiLocalAddr(imsService.getIMSRegAddress());
        }
    }

    public void setVoWifiLocalAddr(String addr) {
        mWifiService.setUsedLocalAddr(addr);
        mVoWifiLocalAddr = addr;
    }
    /* @} */

    public void updateImsFeature(int serviceId) {
        ImsServiceImpl imsService = mImsServiceImplMap.get(Integer
                .valueOf(serviceId));
        boolean isPrimaryCard = ImsRegister.getPrimaryCard(mPhoneCount) == (serviceId-1);
        boolean volteRegistered = (imsService != null) ? imsService.isImsRegisterState() : false;
        int     currentImsFeature;   // UNISOC: Add for bug950573
        boolean volteAvailable = (volteRegistered) ? (imsService.isRadioAvailableForImsService()) : false; // UNISOC: Modify for bug1065583
        Log.i(TAG,"updateImsFeature --> isPrimaryCard = " + ImsRegister.getPrimaryCard(mPhoneCount) + " | serviceId-1 = " + (serviceId-1)
                +" volteRegistered:"+volteRegistered + " volteAvailable:" + volteAvailable
                +" getLTECapabilityForPhone:"+getLTECapabilityForPhone(serviceId - 1));

        //SPRD: add for bug947058,modify for bug950573
        int vowifiServiceId = getVoWifiServiceId();
        if ((vowifiServiceId != IMS_INVALID_SERVICE_ID) && (vowifiServiceId != (ImsRegister.getPrimaryCard(mPhoneCount) + 1))) {
            Log.i(TAG, "updateImsFeature primaryCard is not same as Vowifi service Card, not update. vowifiServiceId = " + vowifiServiceId);
            return;
        }

        if (!isPrimaryCard && imsService != null) {

            /* UNISOC: Add for bug950573 @{*/
            if (volteAvailable) { // UNISOC: Modify for bug1065583
                currentImsFeature = ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_LTE;
            } else {
                currentImsFeature = ImsConfig.FeatureConstants.FEATURE_TYPE_UNKNOWN;
            }
            setCurrentImsFeature(serviceId, currentImsFeature); // UNISOC: Add for bug950573
            /*@}*/

            if(getLTECapabilityForPhone(serviceId - 1)) {
                imsService.updateImsFeatures(volteAvailable, false); // UNISOC: Modify for bug1065583
                imsService.notifyImsRegister(volteAvailable, volteAvailable, false); // UNISOC: Modify for bug880865,bug1065583
                notifyImsRegisterState();
            } else {
                if(imsService.isImsEnabled()) {
                    imsService.updateImsFeatures(false, false);
                }
            }
            return;
        }
        updateImsRegisterState();
        int oldImsFeature = getImsFeature(serviceId);// SPRD:add for bug673215,bug950573
        boolean isImsRegistered = false;
        if (mInCallHandoverFeature != ImsConfig.FeatureConstants.FEATURE_TYPE_UNKNOWN) {
            if (mInCallHandoverFeature == ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_WIFI) {
                currentImsFeature = ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_WIFI;
                isImsRegistered = true;
            } else if (imsService != null
                    && imsService.getSrvccState() == VoLteServiceState.HANDOVER_COMPLETED) {
                currentImsFeature = ImsConfig.FeatureConstants.FEATURE_TYPE_UNKNOWN;
                isImsRegistered = false;
            } else {
                currentImsFeature = ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_LTE;
                isImsRegistered = true;
            }
        } else if (volteAvailable) { // UNISOC: Modify for bug1065583
            currentImsFeature = ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_LTE;
            isImsRegistered = true;
        } else if (mWifiRegistered) {
            currentImsFeature = ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_WIFI;
            if(ImsRegister.getPrimaryCard(mPhoneCount) == (serviceId-1)) {
                isImsRegistered = true;
            }
        } else {
            currentImsFeature = ImsConfig.FeatureConstants.FEATURE_TYPE_UNKNOWN;
            isImsRegistered = false;
        }
        if (imsService != null) {
            imsService.setCurrentImsFeature(currentImsFeature); // UNISOC: Add for bug950573
            imsService
                    .updateImsFeatures(
                            currentImsFeature == ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_LTE,
                            currentImsFeature == ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_WIFI);

            // SPRD: add for bug671964
            if (currentImsFeature == ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_WIFI) {
                if (mWifiService != null) {
                    String addr = mWifiService.getCurPcscfAddress();
                    imsService.setImsPcscfAddress(addr);
                }
            }
            imsService.notifyImsRegister(isImsRegistered, currentImsFeature == ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_LTE, mWifiRegistered); // UNISOC: Modify for bug880865
            notifyImsRegisterState();
        }

        Log.i(TAG, "updateImsFeature->mWifiRegistered:" + mWifiRegistered
                + " volteRegistered:" + volteRegistered
                + " oldImsFeature:"+oldImsFeature
                + " currentImsFeature:" + currentImsFeature
                + " mInCallHandoverFeature:" + mInCallHandoverFeature
                + " serviceId:" + serviceId);
    }

    /* UNISOC: modify for bug1008539{@ */
    private boolean isVoLTERegisted(int serviceId) {
        boolean volteRegistered = false;

        ImsServiceImpl imsService = mImsServiceImplMap.get(serviceId);
        if(imsService != null) {
            volteRegistered = imsService.isImsRegisterState();
        }

        return volteRegistered;
    }
    /* @} */

    public void onReceiveHandoverEvent(boolean isCalling, int requestId,
            int targetType) {
        int currentImsFeature = getPrimaryCardImsFeature(); // UNISOC: Add for bug950573
        Log.i(TAG, "onReceiveHandoverEvent->isCalling:" + isCalling
                + " requestId:" + requestId + " targetType:" + targetType
                + " currentImsFeature:" + currentImsFeature
                + " mPendingVolteHandoverVolteSuccess:" + mPendingVolteHandoverVolteSuccess
                + " mPendingVowifiHandoverVowifiSuccess:" + mPendingVowifiHandoverVowifiSuccess);
        if (!mIsCalling && (mCallEndType != CallEndEvent.INVALID_CALL_END)) { // UNISOC: modify for bug978846
            Log.i(TAG, "onReceiveHandoverEvent-> mCallEndType:"
                    + mCallEndType);
            notifyCpCallEnd();

            mPendingVolteHandoverVolteSuccess   = false;
            mPendingVowifiHandoverVowifiSuccess = false;
        }

        mFeatureSwitchRequest = new ImsServiceRequest(
                requestId,
                isCalling ? ACTION_START_HANDOVER : ACTION_SWITCH_IMS_FEATURE /* eventCode */,
                ImsRegister.getPrimaryCard(mPhoneCount) + 1/* serviceId */,
                targetType);
        ImsServiceImpl service = mImsServiceImplMap.get(Integer
                .valueOf(mFeatureSwitchRequest.mServiceId));
        if (service != null) {
            if (mFeatureSwitchRequest.mTargetType == ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_LTE) {
                service.requestImsHandover(isCalling ? ImsHandoverType.INCALL_HANDOVER_TO_VOLTE
                        : ImsHandoverType.IDEL_HANDOVER_TO_VOLTE);
            } else if (mFeatureSwitchRequest.mTargetType == ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_WIFI) {
                service.requestImsHandover(isCalling ? ImsHandoverType.INCALL_HANDOVER_TO_VOWIFI
                        : ImsHandoverType.IDEL_HANDOVER_TO_VOWIFI);
            }
        }
    }

    public void onImsHandoverStateChange(boolean allow, int state) {
        Log.i(TAG, "onImsHandoverStateChange->allow:" + allow + " state:"
                + state + " mFeatureSwitchRequest:" + mFeatureSwitchRequest);
        if (mFeatureSwitchRequest == null) {
            Log.w(TAG,
                    "onImsHandoverStateChange->there is no handover request active!");
            return;
        }
        try {
            if (!allow) {
                if (mImsServiceListenerEx != null) {
                    if (mFeatureSwitchRequest.mTargetType == ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_LTE) {
                        mImsServiceListenerEx
                                .operationFailed(
                                        mFeatureSwitchRequest.mRequestId,
                                        "Do not allow.",
                                        (mFeatureSwitchRequest.mEventCode == ACTION_SWITCH_IMS_FEATURE) ? ImsOperationType.IMS_OPERATION_SWITCH_TO_VOLTE
                                                : ImsOperationType.IMS_OPERATION_HANDOVER_TO_VOLTE);
                    } else {
                        mImsServiceListenerEx
                                .operationFailed(
                                        mFeatureSwitchRequest.mRequestId,
                                        "Do not allow.",
                                        (mFeatureSwitchRequest.mEventCode == ACTION_SWITCH_IMS_FEATURE) ? ImsOperationType.IMS_OPERATION_CP_REJECT_SWITCH_TO_VOWIFI
                                                : ImsOperationType.IMS_OPERATION_CP_REJECT_HANDOVER_TO_VOWIFI);
                    }
                    mFeatureSwitchRequest = null;
                } else {
                    Log.w(TAG,
                            "onImsHandoverStateChange->mImsServiceListenerEx is null!");
                }
            } else if (mFeatureSwitchRequest.mEventCode == ACTION_SWITCH_IMS_FEATURE) {
                if (mFeatureSwitchRequest.mTargetType == ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_LTE) {
                    if (state == ImsHandoverResult.IMS_HANDOVER_PDN_BUILD_FAIL
                            || state == ImsHandoverResult.IMS_HANDOVER_REGISTER_FAIL) {
                        if (mImsServiceListenerEx != null) {
                            mImsServiceListenerEx
                                    .operationFailed(
                                            mFeatureSwitchRequest.mRequestId,
                                            "VOLTE pdn failed.",
                                            ImsOperationType.IMS_OPERATION_SWITCH_TO_VOLTE);
                        }
                        mFeatureSwitchRequest = null;
                    }
                } else if (mFeatureSwitchRequest.mTargetType == ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_WIFI) {
                    if (state == IMS_HANDOVER_ACTION_CONFIRMED) {
                        if (SystemProperties.getBoolean(PROP_S2B_ENABLED, true)) {
                            mWifiService.attach((mFeatureSwitchRequest.mEventCode == ACTION_SWITCH_IMS_FEATURE) ? false : true);
                        } else {
                            mWifiService.register();
                        }
                    }
                }
            } else if (mFeatureSwitchRequest.mEventCode == ACTION_START_HANDOVER) {
                if (mFeatureSwitchRequest.mTargetType == ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_LTE) {
                    if (state == ImsHandoverResult.IMS_HANDOVER_PDN_BUILD_FAIL
                            || state == ImsHandoverResult.IMS_HANDOVER_REGISTER_FAIL) {

                        /* UNISOC: delete for bug978846 ,handle by func: onImsPdnStatusChange()@{ */
                        /*if (mImsServiceListenerEx != null) { //UNISOC: modify by bug968960
                            mImsServiceListenerEx
                                    .operationFailed(
                                            mFeatureSwitchRequest.mRequestId,
                                            "VOLTE pdn failed.",
                                            ImsOperationType.IMS_OPERATION_HANDOVER_TO_VOLTE);
                        }
                        mFeatureSwitchRequest = null;
                        if (!mIsCalling && mPendingActivePdnSuccess) {
                            mPendingActivePdnSuccess = false;
                            mWifiService
                                    .updateCallRatState(CallRatState.CALL_NONE);
                            Log.i(TAG,
                                    "onImsHandoverStateChange->ACTION_START_HANDOVER fail,mPendingActivePdnSuccess is true!");
                        }*/
                        /*@}*/
                        Log.i(TAG,
                                "onImsHandoverStateChange->ACTION_START_HANDOVER fail!"); //UNISOC: Add for bug978846
                    } else if (state == ImsHandoverResult.IMS_HANDOVER_SRVCC_FAILED) {
                        if (mImsServiceListenerEx != null) { //UNISOC: modify by bug968960
                            mImsServiceListenerEx.onSrvccFaild();
                        }
                        Log.i(TAG,
                                "onImsHandoverStateChange->IMS_HANDOVER_SRVCC_FAILED.");
                    }
                } else if (mFeatureSwitchRequest.mTargetType == ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_WIFI) {
                    if (state == IMS_HANDOVER_ACTION_CONFIRMED
                            && SystemProperties.getBoolean(PROP_S2B_ENABLED,
                                    true)) {
                        /* UNISOC: Add for bug1008539 @{*/
                        if((mVoWifiLocalAddr == null) || (mVoWifiLocalAddr.length() == 0)) {
                            Log.i(TAG,
                                    "onImsHandoverStateChange->Handover to VoWifi, null VoWifi local addr, mIsVolteCall: " + mIsVolteCall);
                            if (mIsVolteCall) {
                                updateVoWifiLocalAddr();
                            }
                        }
                        /*@}*/
                        mWifiService.attach((mFeatureSwitchRequest.mEventCode == ACTION_SWITCH_IMS_FEATURE) ? false : true);
                    }
                }
            }
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    public void onImsPdnStatusChange(int serviceId, int state) {
        // SPRD: add for bug822996
        if(ImsRegister.getPrimaryCard(mPhoneCount) != (serviceId-1)){
            Log.i(TAG, "onImsPdnStatusChange->this is secondary card, serviceId:" + serviceId + " state:" + state);
            if (state == ImsPDNStatus.IMS_PDN_READY) {
                ImsServiceImpl service = mImsServiceImplMap.get(serviceId);
                if (service != null) {
                    service.enableImsWhenPDNReady();
                }
            }
            return;
        }

        if (state == ImsPDNStatus.IMS_PDN_READY) {
            mIsAPImsPdnActived = false;
            mIsCPImsPdnActived = true;
            mPendingCPSelfManagement = false;
            if (mWifiService != null) {
                mWifiService.onVoLTEPDNActivated(); // UNISOC: Add for bug1281165
            }
        } else {
            mIsCPImsPdnActived = false;
            // SPRD: add for bug642021
            if (state == ImsPDNStatus.IMS_PDN_ACTIVE_FAILED || isAirplaneModeOn()) {
                if (mPendingCPSelfManagement || mFeatureSwitchRequest == null
                        && !mWifiRegistered) {
                    ImsServiceImpl service = mImsServiceImplMap
                            .get(Integer.valueOf(ImsRegister
                                    .getPrimaryCard(mPhoneCount) + 1));
                    if (service != null && !mIsCalling) {// SPRD: add for
                                                         // bug717045
                        service.setIMSRegAddress(null);
                    }
                }
                mPendingCPSelfManagement = false;
            }
        }
        Log.i(TAG, "onImsPdnStatusChange->serviceId:" + serviceId + " state:"
                + state + " mFeatureSwitchRequitchRequest:"
                + mFeatureSwitchRequest + " mIsCalling:" + mIsCalling
                + " mIsCPImsPdnActived:" + mIsCPImsPdnActived
                + " mIsAPImsPdnActived:" + mIsAPImsPdnActived
                + " mWifiRegistered:" + mWifiRegistered + " volteRegistered:"
                + isVoLTERegisted(serviceId) + " mPendingCPSelfManagement:"
                + mPendingCPSelfManagement + " mPendingActivePdnSuccess:"
                + mPendingActivePdnSuccess + " isAirplaneModeOn:"
                + isAirplaneModeOn() + " mInCallHandoverFeature:"
                + mInCallHandoverFeature);
        try {
            if (mImsServiceListenerEx != null
                    && serviceId == ImsRegister.getPrimaryCard(mPhoneCount) + 1) {
                mImsServiceListenerEx.imsPdnStateChange(state);
            }
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        // If the switch request is null, we will start the volte call as default.
        if (mFeatureSwitchRequest == null && state == ImsPDNStatus.IMS_PDN_READY) {
            // add for Dual LTE
            ImsServiceImpl service = null;
            if (getLTECapabilityForPhone(serviceId - 1)) {
                service = mImsServiceImplMap.get(Integer.valueOf(serviceId));
            } else {
                service = mImsServiceImplMap.get(Integer
                        .valueOf(ImsRegister.getPrimaryCard(mPhoneCount) + 1));
            }
            if (service != null) {
                /*SPRD: Modify for bug599233{@*/
                Log.i(TAG, "onImsPdnStatusChange->mIsPendingRegisterVolte:" + mIsPendingRegisterVolte + " service.isImsRegistered():" + service.isImsRegistered());
                // If pdn is ready when handover from vowifi to volte but volte is not registered , never to turn on ims.
                // If Volte is registered , never to turn on ims.
                Log.i(TAG, "onImsPdnStatusChange->mIsVolteCall:" + mIsVolteCall
                        + " mIsVowifiCall:" + mIsVowifiCall
                        + " mIsAPImsPdnActived:" + mIsAPImsPdnActived);

                if (!mIsAPImsPdnActived && !mIsVolteCall && !mIsVowifiCall) {
                    Log.d(TAG,
                            "Switch request is null, but the pdn start, will enable the ims.");
                    service.enableImsWhenPDNReady();
                } else if (mInCallPhoneId != -1 && mInCallPhoneId != (serviceId - 1)) {//SPRD: add for bug817433
                    Log.d(TAG, "other sim  pdn start, will enable the ims.");
                    service.enableImsWhenPDNReady();
                }
            }
            /* @} */
        } else if (mFeatureSwitchRequest != null
                && mFeatureSwitchRequest.mTargetType == ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_LTE) {
            if (state == ImsPDNStatus.IMS_PDN_ACTIVE_FAILED) {
                if (mImsServiceListenerEx != null) {
                    try {
                        Log.i(TAG, "onImsPdnStatusChange -> operationFailed");
                        mImsServiceListenerEx
                                .operationFailed(
                                        mFeatureSwitchRequest.mRequestId,
                                        "VOLTE pdn failed.",
                                        (mFeatureSwitchRequest.mEventCode == ACTION_START_HANDOVER) ? ImsOperationType.IMS_OPERATION_HANDOVER_TO_VOLTE
                                                : ImsOperationType.IMS_OPERATION_SWITCH_TO_VOLTE);
                    } catch (RemoteException e) {
                        e.printStackTrace();
                    }
                } else {
                    Log.w(TAG,
                            "onImsPdnStatusChange->mImsServiceListenerEx is null!");
                }
                /* UNISOC: modify for bug983182 @{ */
                if(mFeatureSwitchRequest.mEventCode == ACTION_START_HANDOVER) {
                    Log.i(TAG, "onImsPdnStatusChange -> mIsCalling: " + mIsCalling + "mIsPendingRegisterVolte:" + mIsPendingRegisterVolte + "mIsVolteCall: " + mIsVolteCall);
                    if (!mIsCalling) {
                        mPendingActivePdnSuccess = false;
                        mWifiService
                                .updateCallRatState(CallRatState.CALL_NONE);

                        if (mAttachVowifiSuccess && !mIsPendingRegisterVolte & !mWifiRegistered) {
                            mWifiService.register();
                            mIsLoggingIn = true;
                        }
                        mFeatureSwitchRequest = null;
                    } else if (mIsVolteCall && mAttachVowifiSuccess && !mIsPendingRegisterVolte) {
                        mIsPendingRegisterVowifi = true;
                    } else {
                        mFeatureSwitchRequest = null;
                    }
                } else {
                    mFeatureSwitchRequest = null;
                }
                /*@}*/
                mIsPendingRegisterVolte = false;
            } else if (state == ImsPDNStatus.IMS_PDN_READY) {
                ImsServiceImpl service = mImsServiceImplMap.get(Integer
                        .valueOf(ImsRegister.getPrimaryCard(mPhoneCount) + 1));
                if (service != null && mFeatureSwitchRequest.mEventCode == ACTION_SWITCH_IMS_FEATURE) {
                    service.turnOnIms();
                    if (mImsServiceListenerEx != null) {
                        try {
                            Log.i(TAG,
                                    "onImsPdnStatusChange -> operationSuccessed-> IMS_OPERATION_SWITCH_TO_VOLTE");
                            mImsServiceListenerEx
                                    .operationSuccessed(
                                            mFeatureSwitchRequest.mRequestId,
                                            ImsOperationType.IMS_OPERATION_SWITCH_TO_VOLTE);
                        } catch (RemoteException e) {
                            e.printStackTrace();
                        }
                    }
                } else if (mFeatureSwitchRequest.mEventCode == ACTION_START_HANDOVER) {
                    int oldImsFeatrue = getImsFeature(serviceId); // UNISOC: Add for bug950573
                    /* SPRD: Modify for bug595321{@ */
                    if (mIsCalling) {
                        mInCallHandoverFeature = ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_LTE;
                        mIsPendingRegisterVolte = true;
                        updateImsFeature(serviceId);
                        if (mImsServiceListenerEx != null) {
                            try {
                                Log.i(TAG,
                                        "onImsPdnStatusChange -> operationSuccessed-> IMS_OPERATION_HANDOVER_TO_VOLTE");
                                mImsServiceListenerEx
                                        .operationSuccessed(
                                                mFeatureSwitchRequest.mRequestId,
                                                ImsOperationType.IMS_OPERATION_HANDOVER_TO_VOLTE);
                                if(oldImsFeatrue == ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_WIFI) {//UNISOC: add for bug915555
                                    showHanoverSuccessToast(serviceId);
                                }
                            } catch (RemoteException e) {
                                e.printStackTrace();
                            }
                        }
                        mWifiService.deattach(true);
                        mPendingActivePdnSuccess = false;
                        mWifiService
                                .updateCallRatState(CallRatState.CALL_VOLTE);
                        if(mIsVolteCall){
                            ImsServiceImpl reportService = mImsServiceImplMap.get(
                                    Integer.valueOf(mFeatureSwitchRequest.mServiceId));
                            if (reportService != null) {
                                reportService.disableWiFiParamReport();
                            }
                        }
                    } else {
                        mIsPendingRegisterVolte = true;
                        mInCallHandoverFeature = ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_LTE;    // UNISOC: Add for bug950573
                        updateImsFeature(serviceId);

                        Log.i(TAG,
                                "onImsPdnStatusChange -> currentImsFeature:"
                                        + ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_LTE
                                        + " mIsCalling:" + mIsCalling);
                        if (mImsServiceListenerEx != null) {
                            try {
                                Log.i(TAG,
                                        "onImsPdnStatusChange -> operationSuccessed-> IMS_OPERATION_HANDOVER_TO_VOLTE");
                                mImsServiceListenerEx
                                        .operationSuccessed(
                                                mFeatureSwitchRequest.mRequestId,
                                                ImsOperationType.IMS_OPERATION_HANDOVER_TO_VOLTE);
                                if(oldImsFeatrue == ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_WIFI) {//UNISOC: add for bug915555
                                    showHanoverSuccessToast(serviceId);
                                }
                            } catch (RemoteException e) {
                                e.printStackTrace();
                            }
                        }
                        mWifiService.deattach(true);
                        mPendingActivePdnSuccess = false;
                        if(isVoLTERegisted(serviceId)) { // UNISOC: modify for bug1008539
                            mPendingVolteHandoverVolteSuccess = true; // UNISOC: add for bug978846
                        }
                        mWifiService
                                .updateCallRatState(CallRatState.CALL_NONE);
                        mInCallHandoverFeature = ImsConfig.FeatureConstants.FEATURE_TYPE_UNKNOWN;    // UNISOC: Add for bug950573
                    }
                    /* @} */
                }
            }
        }
        // SPR:add for bug720289
        if (mIsCalling
                && mInCallHandoverFeature == ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_LTE
                && state == ImsPDNStatus.IMS_PDN_ACTIVE_FAILED) {
            Log.i(TAG,
                    "onImsPdnStatusChange -> handvoer to Volte failed,set mInCallHandoverFeature unknow");
            mInCallHandoverFeature = ImsConfig.FeatureConstants.FEATURE_TYPE_UNKNOWN;
            updateImsFeature();
        }
    }

    public void onImsCallEnd(int serviceId) {
    }

    public void onImsNetworkInfoChange(int type, String info) {
        Log.i(TAG, "onImsNetworkInfoChange->type:" + type + " info:" + info);
        if (mFeatureSwitchRequest != null) {
            int currentImsFeature = getImsFeature(mFeatureSwitchRequest.mServiceId); // UNISOC: Add for bug950573
            Log.i(TAG, "onImsNetworkInfoChange->mFeatureSwitchRequest:"
                    + mFeatureSwitchRequest.toString() + " currentImsFeature:"
                    + currentImsFeature);
            Log.i(TAG, "onImsNetworkInfoChange->type: " + type + " info: "
                    + info);
            mNetworkType = type;
            mNetworkInfo = info;
        } else {
            Log.i(TAG, "onImsNetworkInfoChange->mFeatureSwitchRequest is null.");
        }
    }

    public void notifyWiFiError(int statusCode) {
        try {
            if (mImsServiceListenerEx != null) {
                mImsServiceListenerEx.onVoWiFiError(statusCode);
            }
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    /* SPRD: Add for bug586758{@ */
    public boolean isVowifiCall() {
        return mIsVowifiCall;
    }

    public boolean isVolteCall() {
        return mIsVolteCall;
    }

    /* @} */
    private void iLog(String log) {
        Log.i(TAG, log);
    }

    public IImsServiceListenerEx getImsServiceListenerEx() {
        return mImsServiceListenerEx;
    }

    /**
     * Used for terminate all calls. param wifiState: wifi_disabled = 0; wifi_enabled = 1;
     */
    public void terminateAllCalls(int wifiState) {
        Log.i(TAG, "terminateAllCalls->mIsVolteCall:" + mIsVolteCall
                + " mIsVowifiCall:" + mIsVowifiCall + " wifiState:" + wifiState);
        if (mIsVolteCall) {
            ImsServiceImpl service = mImsServiceImplMap.get(Integer
                    .valueOf(ImsRegister.getPrimaryCard(mPhoneCount) + 1));
            if (service != null) {
                service.terminateVolteCall();
            } else {
                Log.i(TAG, "terminateAllCalls->ImsServiceImpl is null.");
            }
        } else if (mIsVowifiCall) {
            VoWifiServiceImpl.WifiState state;
            if (wifiState == 1) {
                state = VoWifiServiceImpl.WifiState.CONNECTED;
            } else {
                state = VoWifiServiceImpl.WifiState.DISCONNECTED;
            }
            if (mWifiService != null) {
                mWifiService.terminateCalls(state);
            } else {
                Log.i(TAG, "terminateAllCalls->VowifiServiceImpl is null.");
            }
        }
    }

    public void notifyCPVowifiAttachSucceed() {
        Log.i(TAG,
                "EVENT_NOTIFY_CP_VOWIFI_ATTACH_SUCCESSED-> notifyImsHandoverStatus:"
                        + ImsHandoverResult.IMS_HANDOVER_ATTACH_SUCCESS);
        ImsServiceImpl impl = mImsServiceImplMap.get(Integer
                .valueOf(ImsRegister.getPrimaryCard(mPhoneCount) + 1));
        if (impl != null) {
            impl.notifyImsHandoverStatus(ImsHandoverResult.IMS_HANDOVER_ATTACH_SUCCESS);
        }
    }

    public void notifyCpCallEnd() {
        int primaryPhoneId = ImsRegister.getPrimaryCard(mPhoneCount);
        Log.i(TAG, "notifyCpCallEnd->mCallEndType:" + mCallEndType
                + " primaryPhoneId:" + primaryPhoneId + " mIsCalling:"
                + mIsCalling);
        if (mCallEndType != CallEndEvent.INVALID_CALL_END && !mIsCalling) {
            ImsServiceImpl impl = mImsServiceImplMap.get(Integer
                    .valueOf(primaryPhoneId + 1));
            if (impl != null) {
                impl.notifyImsCallEnd(mCallEndType);
                try {
                    if (mImsServiceListenerEx != null) {
                        mImsServiceListenerEx
                                .imsCallEnd((mCallEndType == CallEndEvent.VOLTE_CALL_END) ? ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_LTE
                                        : ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_WIFI);
                    }
                } catch (RemoteException e) {
                    e.printStackTrace();
                }
                mCallEndType = CallEndEvent.INVALID_CALL_END;
                if (impl.getVolteRegisterState() != IMS_REG_STATE_REGISTERING) { // UNISOC: Add for 1053426
                    int serviceId = impl.getServiceId();
                    if (!(impl.isRadioAvailableForImsService()
                            && ((!isVoLTERegisted(serviceId) && mWifiRegistered && (getImsFeature(serviceId) == ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_LTE))
                            || (isVoLTERegisted(serviceId) && !mWifiRegistered && (getImsFeature(serviceId) == ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_WIFI))))) { // UNISOC: Add for bug1068538
                        updateImsFeature(serviceId);
                    }
                }
            } else {
                Log.w(TAG,
                        "notifyCpCallEnd->notifyImsCallEnd-> ImsServiceImpl is null");
            }
        }
    }

    public boolean allowEnableIms(int phoneId){
        // add for Dual LTE
        ImsServiceImpl service = null;
        if (getLTECapabilityForPhone(phoneId)) {
            service = mImsServiceImplMap.get(Integer.valueOf(phoneId + 1));
        } else {
            service = mImsServiceImplMap.get(
                    Integer.valueOf(ImsRegister.getPrimaryCard(mPhoneCount)+1));
        }
        Log.i(TAG,"allowEnableIms->service:"+service +" mFeatureSwitchRequest:"+mFeatureSwitchRequest
                + " isVolteEnabledBySystemProperties:"+ ImsConfigImpl.isVolteEnabledBySystemProperties());

        /* UNISOC: bug1126104 @{ */
        int volteSettingByUser = SubscriptionManager.getIntegerSubscriptionProperty(
                    getSubIdByServiceId(phoneId + 1), SubscriptionManager.ENHANCED_4G_MODE_ENABLED,
                    SUB_PROPERTY_NOT_INITIALIZED, ImsService.this);

        if ((volteSettingByUser == SUB_PROPERTY_NOT_INITIALIZED)
            || (volteSettingByUser == ImsConfig.FeatureValueConstants.OFF)) {
            Log.i(TAG, "allowEnableIms-> ENHANCED_4G_MODE_ENABLED not initialized or false, phoneId:" + phoneId + " volteSettingByUser:" + volteSettingByUser);
            return false;
        }
        /* @}*/

        // SPRD: change for bug822996
        if(phoneId == ImsRegister.getPrimaryCard(mPhoneCount)) {
            if (mFeatureSwitchRequest != null || service == null || !ImsConfigImpl.isVolteEnabledBySystemProperties()) {
                return false;
            }
            if ((mIsVolteCall && (phoneId == mInCallPhoneId)) || mIsVowifiCall || mIsAPImsPdnActived) { // UNISOC: Modify for bug1139536
                Log.i(TAG, "allowEnableIms->mIsVolteCall:" + mIsVolteCall
                        + " mIsVowifiCall:" + mIsVowifiCall
                        + " mIsAPImsPdnActived:" + mIsAPImsPdnActived);
                return false;
            }
            Log.i(TAG, "allowEnableIms->mIsPendingRegisterVolte:"
                    + mIsPendingRegisterVolte + " service.isImsRegistered():"
                    + service.isImsRegistered() + " service.isRadioAvailableForImsService():"
                    + service.isRadioAvailableForImsService());
            if (!mIsPendingRegisterVolte && !service.isImsRegistered()
                    && service.isRadioAvailableForImsService()) { // UNISOC: Modify for bug1046061
                return true;
            }
        }else {
            if (service == null || !ImsConfigImpl.isVolteEnabledBySystemProperties()) {
                return false;
            }else if(!service.isImsRegistered() && service.isRadioAvailableForImsService()) { // UNISOC: Modify for bug1046061
                Log.i(TAG," service.isImsRegistered():"+service.isImsRegistered()
                        + " service.isRadioAvailableForImsService():" + service.isRadioAvailableForImsService());
                return true;
            }
        }
        return false;
    }

    public void updateInCallState(boolean isInCall) {
        Log.i(TAG,"updateInCallState->mIsVolteCall:"+mIsVolteCall +" mIsVowifiCall:"+mIsVowifiCall
                + " isInCall:"+isInCall+" mIsWifiCalling:"+mIsWifiCalling);
        int currentImsFeature = getImsFeature(Integer.valueOf(ImsRegister.getPrimaryCard(mPhoneCount) + 1)); // UNISOC: Add for bug950573
        if (mIsCalling != isInCall) {
            mIsCalling = isInCall;
            if (mIsCalling
                    && (currentImsFeature != ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_WIFI || mInCallPhoneId != ImsRegister
                            .getPrimaryCard(mPhoneCount) || mIsEmergencyCallonIms)) {
                mWifiService
                        .updateIncomingCallAction(IncomingCallAction.REJECT);
            } else {
                mWifiService
                        .updateIncomingCallAction(IncomingCallAction.NORMAL);
            }
        }
        if ((!mIsEmergencyCallonIms && currentImsFeature == ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_WIFI && isInCall != mIsWifiCalling && mIsVowifiCall)
                || (!isInCall && mIsWifiCalling)) {
            mIsWifiCalling = isInCall;
            for (Map.Entry<Integer, ImsServiceImpl> entry : mImsServiceImplMap
                    .entrySet()) {
                entry.getValue().notifyWifiCalling(mIsWifiCalling);
            }
        }

        ImsServiceImpl imsService = mImsServiceImplMap.get(Integer
                .valueOf(ImsRegister.getPrimaryCard(mPhoneCount) + 1));
        if (!mIsCalling
                && imsService != null
                && imsService.getSrvccState() == VoLteServiceState.HANDOVER_COMPLETED) {
            imsService.setSrvccState(-1);
        }
        /**
         * SPRD: 673414
         */
        if (!isInCall) {
            if (mIsVowifiCall) {
                mIsVowifiCall = false;
            } else if (mIsVolteCall) {
                mIsVolteCall = false;
            }
            ImsServiceImpl service = mImsServiceImplMap.get(
                    Integer.valueOf(ImsRegister.getPrimaryCard(mPhoneCount)+1));
            if (service != null) {
                service.disableWiFiParamReport();
            }
        }

        iLog("updateInCallState->isInCall:" + isInCall + " mIsWifiCalling:"
                + mIsWifiCalling + " inCallPhoneId:" + mInCallPhoneId
                + " mIsVolteCall: " + mIsVolteCall);
    }

    public void notifySrvccCapbility(int cap) {
        if (mWifiService != null) {
            mWifiService.setSRVCCSupport(cap != 0 ? true : false);
        }
    }

    public void notifySrvccState(int phoneId, int status) {
        int primaryPhoneId = ImsRegister.getPrimaryCard(mPhoneCount);
        iLog("notifySrvccState->phoneId:" + phoneId + " primaryPhoneId:"
                + primaryPhoneId + " status:" + status);
        if (phoneId == primaryPhoneId + 1) {
            mWifiService.onSRVCCStateChanged(status);
            if (status == VoLteServiceState.HANDOVER_COMPLETED){
                mInCallHandoverFeature = ImsConfig.FeatureConstants.FEATURE_TYPE_UNKNOWN;
                mFeatureSwitchRequest = null;
                mIsPendingRegisterVowifi = false;
                if(mWifiRegistered) {
                    mWifiRegistered = false; // SPRD:add for bug659097
                }
            }
            updateImsFeature();  /* UNISOC: Modify for bug880865 */
        }
    }

    public void onVideoStateChanged(int videoState) {
        Log.i(TAG, "onVideoStateChanged videoState:" + videoState);
        try {
            if (mImsServiceListenerEx != null) {
                mImsServiceListenerEx.onVideoStateChanged(videoState);
            }
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    /* SPRD: add for bug809098 */
    public void showHanoverSuccessToast(int serviceId){
         boolean mShowToast = getBooleanCarrierConfigByServiceId(serviceId,
                                      CarrierConfigManagerEx.KEY_SHOW_IMS_CAPABILITY_CHANGE_TOAST, getApplicationContext());
             Log.i(TAG,"ShowHandoverSuccessToast :" + mShowToast);
         if(mShowToast){
             Toast.makeText(ImsService.this, R.string.handover_to_volte_success,Toast.LENGTH_SHORT).show();
         }
    }

    //SPRD: add for bug825528
    private BroadcastReceiver mReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if ((TelephonyIntents.ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED).equals(action)) {
                Log.i(TAG,"ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED, update Ims Feature For Data Change." );
                updateImsFeatureForDataChange(); //UNISOC: add for bug866765,bug880865
                updateVoWifiLocalAddr(); // UNISOC: Modify for bug1008539
            } else if ((TelephonyIntents.ACTION_SIM_STATE_CHANGED).equals(action)) {
                //Unisoc: add for bug1016166
                String stateExtra = intent.getStringExtra(IccCardConstants.INTENT_KEY_ICC_STATE);
                if (IccCardConstants.INTENT_VALUE_ICC_ABSENT.equals(stateExtra)) {
                    int phoneId = intent.getIntExtra(PhoneConstants.PHONE_KEY,
                            SubscriptionManager.INVALID_PHONE_INDEX);
                    Log.d(TAG,"ACTION_SIM_STATE_CHANGED, sim card absent.phoneid = "+ phoneId );
                    ImsServiceImpl imsService = mImsServiceImplMap.get(Integer.valueOf(phoneId + 1));
                    if (imsService != null) {
                        imsService.setUtDisableByNetWork(false);
                    }
                }
            }
        }
    };

    public void onCallWaitingStatusUpdateForVoWifi(int status){
        Log.d(TAG, "onCallWaitingStatusUpdateForVoWifi, status: " + status);
        SystemProperties.set("gsm.ss.call_waiting", String.valueOf(status));
    }

    public boolean getLTECapabilityForPhone(int phoneId){
        Phone phone = PhoneFactory.getPhone(phoneId);
        if (phone != null) {
            int rafMax = phone.getRadioAccessFamily();
            return (rafMax & RadioAccessFamily.RAF_LTE) == RadioAccessFamily.RAF_LTE;
        }
        return false;
    }

    /* UNISOC: Add for bug950573 @{*/
    /**
     * Used for get IMS feature for main sim card.
     *
     * @return: ImsConfig.FeatureConstants.FEATURE_TYPE_UNKNOWN = -1;
     *          ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_LTE = 0;
     *          ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_WIFI = 2;
     */
    private int getPrimaryCardImsFeature() {
        ImsServiceImpl imsService = mImsServiceImplMap.get(Integer.valueOf(ImsRegister.getPrimaryCard(mPhoneCount) + 1));
        if(imsService != null)
            return imsService.getCurrentImsFeature();
        else
            return ImsConfig.FeatureConstants.FEATURE_TYPE_UNKNOWN;
    }

    /**
     * Used for get IMS feature for specific card.
     *
     * @param: serviceId:  serviceId to get currentImsFeature
     * @return: ImsConfig.FeatureConstants.FEATURE_TYPE_UNKNOWN = -1;
     *          ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_LTE = 0;
     *          ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_WIFI = 2;
     */

    private int getImsFeature(int serviceId) {
        ImsServiceImpl imsService = mImsServiceImplMap.get(Integer.valueOf(serviceId));
        if(imsService != null)
            return imsService.getCurrentImsFeature();
        else
            return ImsConfig.FeatureConstants.FEATURE_TYPE_UNKNOWN;
    }

    /**
     * Used for set IMS feature for specific ServiceId.
     * @param: serviceId:  serviceId of the sim card to update currentImsFeature
     *         imsFeature: ImsConfig.FeatureConstants.FEATURE_TYPE_UNKNOWN = -1;
     *                     ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_LTE = 0;
     *                     ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_WIFI = 2;
     */
    private void setCurrentImsFeature(int serviceId, int imsFeature) {
        ImsServiceImpl imsService = mImsServiceImplMap.get(new Integer(serviceId));
        if(imsService != null)
            imsService.setCurrentImsFeature(imsFeature);
    }

    /**
     * Used for get the serviceId that is responsible for handling switch or HO request to VoWifi.
     */
    public int getVoWifiServiceId() {
        int serviceId = IMS_INVALID_SERVICE_ID;

        if (mFeatureSwitchRequest != null) {
            serviceId = mFeatureSwitchRequest.mServiceId;
        } else if ((mVowifiAttachedServiceId != IMS_INVALID_SERVICE_ID)
                && (mImsServiceImplMap.get(new Integer(mVowifiAttachedServiceId)) != null)) {
            serviceId = mVowifiAttachedServiceId;
        }

        if (serviceId != IMS_INVALID_SERVICE_ID) {
            Log.i(TAG,"VoWifiServiceId is : " + serviceId);
        }

        return serviceId;
    }
    /*@}*/

    public static boolean getBooleanCarrierConfigByServiceId(int serviceId, String key, Context context){
        int subId = getSubIdByServiceId(serviceId);
        CarrierConfigManager configManager = (CarrierConfigManager) context.
            getSystemService(Context.CARRIER_CONFIG_SERVICE);
        if (configManager.getConfigForSubId(subId) != null) {
            return configManager.getConfigForSubId(subId).getBoolean(key);
        }
        return false;
    }

    public static int getSubIdByServiceId(int serviceId){
        int[] subIds = SubscriptionManager.getSubId(serviceId-1);
        int subId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;
        if (subIds != null && subIds.length >= 1) {
            subId = subIds[0];
        }
        return subId;
    }

    public void onImsCNIInfoChange(int type, String info, int age){
        Log.i(TAG, "onImsPaniInfoChange->type:" + type + " info:" + info + " age:" + age);
        mWifiService.notifyImsCNIInfo(type, info, age);
    }

    /* UNISOC: Add for bug1046061, bug1065583 @{*/
    //return ture,if radioType support Ims Function.
    public boolean isRadioSupportImsService(int radioType) {
        boolean radioSupportImsService = false;
        switch(radioType){
            case ServiceState.RIL_RADIO_TECHNOLOGY_LTE:
            case ServiceState.RIL_RADIO_TECHNOLOGY_LTE_CA:
            case ServiceState.RIL_RADIO_TECHNOLOGY_NR:

                radioSupportImsService = true;
                break;

            default:
                radioSupportImsService = false;
                break;
        }
        return radioSupportImsService;
    }
    /*@}*/

    /* UNISOC: add for bug1153427 @{ */
    private void cancelVowifiNotification() {
        if (mVowifiNotificationShown) {
            Log.i(TAG, "cancelVowifiNotification");
            mNotificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            mNotificationManager.cancel(mCurrentVowifiNotification);
            mVowifiNotificationShown = false;
        }
    }
    /* @} */
    public void setAliveVolteCallType(int callType) {
        Log.i(TAG, "setAliveVolteCallType->callType:" + callType);
        if(mWifiService != null && mInCallPhoneId == ImsRegister
                .getPrimaryCard(mPhoneCount)){
            mWifiService.updateCurCallType(callType);
        }
    }
    public boolean isAirplaneModeOn() {
        return Settings.Global.getInt(getApplicationContext().getContentResolver(),
                Settings.Global.AIRPLANE_MODE_ON, 0) > 0;
    }
}
