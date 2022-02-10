package com.spreadtrum.ims;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.PersistableBundle;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.AsyncResult;
import android.telephony.CarrierConfigManagerEx;
import android.telephony.data.ApnSetting;
import android.telephony.CarrierConfigManager;
import android.telephony.ServiceState;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.telephony.VoLteServiceState;
import android.telephony.ims.feature.ImsFeature;
import android.telephony.ims.feature.MmTelFeature;
import android.util.Log;
import android.widget.Toast;

import android.provider.Telephony;
import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.PhoneFactory;
import com.android.internal.telephony.GsmCdmaPhone;

import com.android.ims.ImsException;
import com.android.ims.ImsManager;
import com.android.ims.ImsServiceClass;
import com.android.ims.ImsConfig;
import android.telephony.ims.ImsReasonInfo;

import com.android.sprd.telephony.RadioInteractor;
import com.spreadtrum.ims.ut.ImsUtImpl;
import com.spreadtrum.ims.ut.ImsUtProxy;

import com.android.ims.internal.IImsCallSession;
import android.telephony.ims.aidl.IImsCallSessionListener;
import com.android.ims.internal.IImsRegistrationListener;
import com.android.ims.internal.IImsPdnStateListener;
import com.android.ims.internal.IImsEcbm;
import com.android.ims.internal.IImsService;
import com.android.ims.internal.IImsUt;
import android.telephony.ims.aidl.IImsConfig;
import com.android.ims.internal.ImsManagerEx;
import com.android.internal.telephony.uicc.UiccController;
import com.android.internal.telephony.uicc.IccRecords;
import com.android.internal.telephony.DctConstants;
import com.android.internal.util.ArrayUtils;
import com.spreadtrum.ims.ImsService;
import com.spreadtrum.ims.data.ApnUtils;
import android.text.TextUtils;
import com.spreadtrum.ims.vowifi.VoWifiConfiguration;
import com.spreadtrum.ims.vowifi.VoWifiServiceImpl;
import com.spreadtrum.ims.vowifi.VoWifiServiceImpl.CallRatState;

import android.telephony.ims.ImsCallSession;
import android.telephony.ims.aidl.IImsRegistration;
import android.telephony.ims.aidl.IImsRegistrationCallback;
import android.telephony.ims.stub.ImsSmsImplBase;
import android.telephony.ims.stub.ImsRegistrationImplBase;
import android.telephony.ims.ImsCallProfile;
import android.telephony.ims.feature.CapabilityChangeRequest;

import android.telephony.ims.aidl.IImsMmTelFeature;
import com.android.ims.internal.IImsFeatureStatusCallback;
import com.android.ims.internal.IImsMultiEndpoint;
import com.android.ims.internal.IImsServiceListenerEx;
import vendor.sprd.hardware.radio.V1_0.ImsNetworkInfo;
import vendor.sprd.hardware.radio.V1_0.ImsErrorCauseInfo;
import com.android.sprd.telephony.CommandException;
import android.content.ComponentName;
import android.content.ServiceConnection;
import android.telephony.data.DataProfile;

public class ImsServiceImpl extends MmTelFeature {
    private static final String TAG = ImsServiceImpl.class.getSimpleName();
    private static final boolean DBG = true;

    // send this broadcast for DM
    private static final String VOLTE_REGISTED_BROADCAST = "com.spreadtrum.ims.VOLTE_REGISTED";
    private static final String VOLTE_REGISTED_PAC_NAME = "com.sprd.dm.mbselfreg";
    private static final String VOLTE_REGISTED_CLASSNAME = "com.sprd.dm.mbselfreg.DmReceiver";

    private static final int IMS_CALLING_RTP_TIME_OUT        = 1;
    private static final int IMS_INVALID_VOLTE_SETTING       =-1;  // UNISOC: Add for bug968317

    public static final int IMS_REG_STATE_INACTIVE            = 0;
    public static final int IMS_REG_STATE_REGISTERED          = 1;
    public static final int IMS_REG_STATE_REGISTERING         = 2;
    public static final int IMS_REG_STATE_REG_FAIL            = 3;
    public static final int IMS_REG_STATE_UNKNOWN             = 4;
    public static final int IMS_REG_STATE_ROAMING             = 5;
    public static final int IMS_REG_STATE_DEREGISTERING       = 6;

    protected static final int EVENT_CHANGE_IMS_STATE                  = 101;
    protected static final int EVENT_IMS_STATE_CHANGED                 = 102;
    protected static final int EVENT_IMS_STATE_DONE                    = 103;
    protected static final int EVENT_IMS_CAPABILITY_CHANGED            = 104;
    protected static final int EVENT_SRVCC_STATE_CHANGED               = 105;
    protected static final int EVENT_SERVICE_STATE_CHANGED             = 106;
    protected static final int EVENT_RADIO_STATE_CHANGED               = 107;
    //add for bug612670
    protected static final int EVENT_SET_VOICE_CALL_AVAILABILITY_DONE  = 108;

    protected static final int EVENT_IMS_REGISTER_ADDRESS_CHANGED      = 109;
    protected static final int EVENT_IMS_HANDOVER_STATE_CHANGED        = 110;
    protected static final int EVENT_IMS_HANDOVER_ACTION_COMPLETE      = 111;
    protected static final int EVENT_IMS_PND_STATE_CHANGED             = 112;
    protected static final int EVENT_IMS_NETWORK_INFO_UPDATE           = 113;
    protected static final int EVENT_IMS_WIFI_PARAM                    = 114;
    protected static final int EVENT_IMS_GET_SRVCC_CAPBILITY           = 115;
    protected static final int EVENT_IMS_GET_PCSCF_ADDRESS             = 116;
    protected static final int EVENT_IMS_GET_IMS_REG_ADDRESS           = 117;
    protected static final int EVENT_RADIO_AVAILABLE                   = 118;
    protected static final int EVENT_RADIO_ON                          = 119;

    protected static final int EVENT_GET_VIDEO_RESOLUTION              = 120;
    protected static final int EVENT_GET_RAT_CAP_NV_CONFIG             = 121;
    protected static final int EVENT_IMS_GET_IMS_CNI_INFO              = 122;
    protected static final int EVENT_IMS_ERROR_CAUSE_INFO              = 123;
    protected static final int EVENT_GET_RAT_CAP_RESULT                = 124;
    protected static final int EVENT_GET_SMS_OVER_IP                   = 125;

    /* UNISOC: add for bug968317 @{ */
    static class VoLTECallAvailSyncStatus {
        public static final int VOLTE_CALL_AVAIL_SYNC_IDLE     = 0;
        public static final int VOLTE_CALL_AVAIL_SYNC_ONGOING  = 1;
        public static final int VOLTE_CALL_AVAIL_SYNC_FAIL     = 2;
    }
    /*@}*/

    public static final int NETWORK_RAT_PS_PREFER = 0;
    public static final int NETWORK_RAT_PS_ONLY = 1;
    public static final int NETWORK_RAT_CS_ONLY = 2;
    public static final int NETWORK_RAT_PS_BY_IMS_STATUS = 3;

    public static final int IMS_ERROR_CAUSE_TYPE_CALL_FAILED = 0;
    public static final int IMS_ERROR_CAUSE_TYPE_SMS_FAILED = 1;
    public static final int IMS_ERROR_CAUSE_TYPE_IMSREG_FAILED = 2;
    public static final int IMS_ERROR_CAUSE_TYPE_SS_FAILED = 4;
    public static final int IMS_ERROR_CAUSE_TYPE_USSD_FAILED = 5;
    public static final int IMS_ERROR_CAUSE_ERRCODE_REG_FORBIDDED = 403;

    private GsmCdmaPhone mPhone;
    private ImsServiceState mImsServiceState;
    private int mServiceClass = ImsServiceClass.MMTEL;
    private int mServiceId;
    private PendingIntent mIncomingCallIntent;
    private IImsRegistrationListener mListener;
    private IImsFeatureStatusCallback mImsFeatureStatusCallback;
    private ConcurrentHashMap<IBinder, IImsRegistrationListener> mImsRegisterListeners = new ConcurrentHashMap<IBinder, IImsRegistrationListener>();
    private ConcurrentHashMap<IBinder, IImsPdnStateListener> mImsPdnStateListeners = new ConcurrentHashMap<IBinder, IImsPdnStateListener>();
    private Context mContext;
    private ImsRadioInterface mImsRadioInterface;
    private ImsConfigImpl mImsConfigImpl;
    private com.spreadtrum.ims.ut.ImsUtImpl mImsUtImpl;
    private ImsEcbmImpl mImsEcbmImpl;
    private ImsServiceCallTracker mImsServiceCallTracker;
    private ImsHandler mImsHandler;
    private UiccController mUiccController;
    private AtomicReference<IccRecords> mIccRecords = new AtomicReference<IccRecords>();
    private ApnChangeObserver mApnChangeObserver = null;
    private ImsRegister mImsRegister = null;
    private ArrayList<Listener> mListeners = new ArrayList<Listener>();
    private ImsService mImsService;
    private String mNWNumeric = "";
    private VoWifiServiceImpl mWifiService;//Add for data router
    private ImsUtProxy mImsUtProxy = null;
    private String mImsRegAddress = "";
    private String mImsPscfAddress = "";
    private int mAliveCallLose = -1;
    private int mAliveCallJitter = -1;
    private int mAliveCallRtt = -1;
    private int mSrvccCapbility = -1;
    // SPRD: 730973
    private boolean mVolteRegisterStateOld = false;
    private int mNetworkRATPrefer = NETWORK_RAT_PS_PREFER;//ps prefer
    private int mServiceState = ServiceState.STATE_POWER_OFF;
    private boolean mIsUtDisableByNetWork = false;
    /**
     * AndroidP start@{:
     */
    private ConcurrentHashMap<IBinder, IImsRegistrationCallback> mIImsRegistrationCallbacks = new ConcurrentHashMap<IBinder, IImsRegistrationCallback>();
    private MmTelCapabilities mVolteCapabilities = new MmTelCapabilities();
    private MmTelCapabilities mVowifiCapabilities = new MmTelCapabilities();
    //add for unisoc 911545
    private MmTelCapabilities mDeviceVolteCapabilities = new MmTelCapabilities();
    private MmTelCapabilities mDeviceVowifiCapabilities = new MmTelCapabilities();
    private int mCurrentImsFeature = ImsConfig.FeatureConstants.FEATURE_TYPE_UNKNOWN;  // UNISOC: Add for bug950573
    /* UNISOC: add for bug968317 @{ */
    private int VoLTECallAvailSync = VoLTECallAvailSyncStatus.VOLTE_CALL_AVAIL_SYNC_IDLE;
    private int currentVoLTESetting= IMS_INVALID_VOLTE_SETTING;
    private int pendingVoLTESetting= IMS_INVALID_VOLTE_SETTING;
    /*@}*/
    /* UNISOC: add for bug988585 @{ */
    private boolean mDeviceCapabilitiesFirstChange = true;
    private boolean mVideoCapabilityEnabled        = false;
    /*@}*/

    // sim don't support ims, can't config ss by ut.
    public static final String DO_NOT_SUPPORT_IMS_DESCRIPTION = "HTTP/1.1 403";

    public IImsRegistration getRegistration(){
        return mImsRegistration;
    }

    private final IImsRegistration.Stub mImsRegistration = new IImsRegistration.Stub(){
        @Override
        public int getRegistrationTechnology(){
            if(isVoLteEnabled()){
                return ImsRegistrationImplBase.REGISTRATION_TECH_LTE;
            } else if(isVoWifiEnabled()){
                return ImsRegistrationImplBase.REGISTRATION_TECH_IWLAN;
            } else {
                return ImsRegistrationImplBase.REGISTRATION_TECH_NONE;
            }
        }

        @Override
        public void addRegistrationCallback(IImsRegistrationCallback c){
            if (c != null) {
                synchronized (mIImsRegistrationCallbacks) {
                    if (!mIImsRegistrationCallbacks.keySet().contains(c.asBinder())) {
                        mIImsRegistrationCallbacks.put(c.asBinder(), c);
                        /* UNISOC: add for bug968317 @{ */
                        try {
                            if (isVoLteEnabled()) {
                                log("addRegistrationCallback : notify onRegistered VOLTE : " + c);
                                c.onRegistered(ImsRegistrationImplBase.REGISTRATION_TECH_LTE);
                            } else if (isVoWifiEnabled()) {
                                log("addRegistrationCallback : notify onRegistered VOWIFI : " + c);
                                c.onRegistered(ImsRegistrationImplBase.REGISTRATION_TECH_IWLAN);
                            } else {
                                log("addRegistrationCallback : notify onDeregistered: " + c);
                                c.onDeregistered(new ImsReasonInfo());
                            }
                        } catch (RemoteException e) {
                            e.printStackTrace();
                        }
                        /*@}*/
                    } else {
                        log("addRegistrationCallback : Listener already add :" + c);
                    }
                }
            } else {
                log("addRegistrationCallback : IImsRegistrationCallback is null");
            }
        }

        @Override
        public void removeRegistrationCallback(IImsRegistrationCallback c){
            synchronized (mIImsRegistrationCallbacks) {
                if (mIImsRegistrationCallbacks.keySet().contains(c.asBinder())) {
                    mIImsRegistrationCallbacks.remove(c.asBinder());
                } else {
                    log("addRegistrationCallback Listener already remove :" + c);
                }
            }
        }
    };

    @Override
    public IImsCallSession createCallSessionInterface(ImsCallProfile profile) {
        log("createCallSessionInterface->profile:" + profile);
        return mImsService.createCallSessionInternal(mServiceId,profile,null);
    }

    public IImsCallSession createCallSessionInterface(int serviceId, ImsCallProfile profile, IImsCallSessionListener listener) {
        log("createCallSessionInterface->profile:" + profile);
        return createCallSessionInternal(profile);
    }

    public IImsCallSession createCallSessionInternal(ImsCallProfile profile) {
        if(mImsServiceCallTracker != null){
            return mImsServiceCallTracker.createCallSession(profile);
        } else {
            return null;
        }
    }

    @Override
    public int shouldProcessCall(String[] uris){
        //TODO: return MmTelFeature.PROCESS_CALL_CSFB to make CS call
        return MmTelFeature.PROCESS_CALL_IMS;
    }


    @Override
    public void changeEnabledCapabilities(CapabilityChangeRequest request,
                                          ImsFeature.CapabilityCallbackProxy c){
        List<CapabilityChangeRequest.CapabilityPair> enableCap = request.getCapabilitiesToEnable();
        List<CapabilityChangeRequest.CapabilityPair> disableCap = request.getCapabilitiesToDisable();
        log("changeEnabledCapabilities->enableCap:" + enableCap + "/n disableCap:"+disableCap);
        boolean isVideoCapabilityChanged   = false; // UNISOC: Add for bug988585
        synchronized (mDeviceVolteCapabilities) {   // UNISOC: Add for bug978339,bug988585
            for (CapabilityChangeRequest.CapabilityPair pair : enableCap) {
                if (pair.getRadioTech() == ImsRegistrationImplBase.REGISTRATION_TECH_LTE) {
                    //add for unisoc 911545
                    mDeviceVolteCapabilities.addCapabilities(pair.getCapability());
                } else if (pair.getRadioTech() == ImsRegistrationImplBase.REGISTRATION_TECH_IWLAN) {
                    mDeviceVowifiCapabilities.addCapabilities(pair.getCapability());
                }
            }

            for (CapabilityChangeRequest.CapabilityPair pair : disableCap) {
                if (pair.getRadioTech() == ImsRegistrationImplBase.REGISTRATION_TECH_LTE) {
                    //add for unisoc 911545
                    mDeviceVolteCapabilities.removeCapabilities(pair.getCapability());
                } else if (pair.getRadioTech() == ImsRegistrationImplBase.REGISTRATION_TECH_IWLAN) {
                    mDeviceVowifiCapabilities.removeCapabilities(pair.getCapability());
                }
            }
            /* UNISOC: modify for bug968317 @{ */
            //add for unisoc 900059,911545
            int newVoLTESetting;
            if (mDeviceVolteCapabilities.isCapable(MmTelCapabilities.CAPABILITY_TYPE_VOICE)) {
                log("changeEnabledCapabilities-> setImsVoiceCallAvailability on");
                newVoLTESetting = ImsConfig.FeatureValueConstants.ON;
            } else {
                log("changeEnabledCapabilities-> setImsVoiceCallAvailability off");
                newVoLTESetting = ImsConfig.FeatureValueConstants.OFF;
            }

            if (VoLTECallAvailSync != VoLTECallAvailSyncStatus.VOLTE_CALL_AVAIL_SYNC_ONGOING) {
                currentVoLTESetting = newVoLTESetting;
                setVoLTECallAvailablity();
            } else {
                if (newVoLTESetting != currentVoLTESetting) {
                    pendingVoLTESetting = newVoLTESetting;
                } else {
                    pendingVoLTESetting = IMS_INVALID_VOLTE_SETTING;
                }
            }
            /*@}*/
            /*@}*/

            /* UNISOC: add for bug988585 @{ */
            boolean newVideoCapabilityEnabled = mDeviceVolteCapabilities.isCapable(MmTelCapabilities.CAPABILITY_TYPE_VIDEO);
            if(mVideoCapabilityEnabled != newVideoCapabilityEnabled)
            {
                isVideoCapabilityChanged = true;
                mVideoCapabilityEnabled = newVideoCapabilityEnabled;
                log("changeEnabledCapabilities->mVideoCapabilityEnabled:" + mVideoCapabilityEnabled);
            }
            /*@}*/
        }

        /* UNISOC: modify for bug988585 @{ */
        if(mDeviceCapabilitiesFirstChange || isVideoCapabilityChanged)
        {
            mImsService.updateImsFeature(mServiceId);
            mDeviceCapabilitiesFirstChange = false;
        }
        /*@}*/
    }

    // SMS APIs
    @Override
    public ImsSmsImplBase getSmsImplementation() {
        //TODO: should implement ImsSmsImplBase
        if(mWifiService != null && mPhone != null){
            return mWifiService.getSmsImplementation(mPhone.getPhoneId());
        }else{
            log("getSmsImplementation mWifiService is null");
            return new ImsSmsImplBase();
        }
    }
    /* AndroidP end@} */

    /**
     * Handles changes to the APN db.
     */
    private class ApnChangeObserver extends ContentObserver {
        public ApnChangeObserver () {
            super(mImsHandler);
        }

        @Override
        public void onChange(boolean selfChange) {
            mImsHandler.sendMessage(mImsHandler.obtainMessage(DctConstants.EVENT_APN_CHANGED));
        }
    }

    //"VoLTE", "ViLTE", "VoWiFi", "ViWiFi","VOLTE-UT", "VOWIFI-UT"
    private int[] mEnabledFeatures = {
            ImsConfig.FeatureConstants.FEATURE_TYPE_UNKNOWN,
            ImsConfig.FeatureConstants.FEATURE_TYPE_UNKNOWN,
            ImsConfig.FeatureConstants.FEATURE_TYPE_UNKNOWN,
            ImsConfig.FeatureConstants.FEATURE_TYPE_UNKNOWN,
            ImsConfig.FeatureConstants.FEATURE_TYPE_UNKNOWN,
            ImsConfig.FeatureConstants.FEATURE_TYPE_UNKNOWN,
            ImsConfig.FeatureConstants.FEATURE_TYPE_UNKNOWN,
            ImsConfig.FeatureConstants.FEATURE_TYPE_UNKNOWN
    };
    private int[] mDisabledFeatures = {
            ImsConfig.FeatureConstants.FEATURE_TYPE_UNKNOWN,
            ImsConfig.FeatureConstants.FEATURE_TYPE_UNKNOWN,
            ImsConfig.FeatureConstants.FEATURE_TYPE_UNKNOWN,
            ImsConfig.FeatureConstants.FEATURE_TYPE_UNKNOWN,
            ImsConfig.FeatureConstants.FEATURE_TYPE_UNKNOWN,
            ImsConfig.FeatureConstants.FEATURE_TYPE_UNKNOWN,
            ImsConfig.FeatureConstants.FEATURE_TYPE_UNKNOWN,
            ImsConfig.FeatureConstants.FEATURE_TYPE_UNKNOWN
    };


    public ImsServiceImpl(Phone phone , Context context, VoWifiServiceImpl wifiService){
        mPhone = (GsmCdmaPhone)phone;
        mWifiService = wifiService;//Add for data router
        mContext = context;
        mImsService = (ImsService)context;
        mImsRadioInterface = new ImsRadioInterface(phone.getContext(), phone.getPhoneId(), mPhone.mCi);
        mServiceId = phone.getPhoneId() + 1;
        mImsRegister = new ImsRegister(mPhone, mContext, mImsRadioInterface);
        mImsServiceState = new ImsServiceState(false, IMS_REG_STATE_INACTIVE);
        mImsConfigImpl = new ImsConfigImpl(mImsRadioInterface, context, this, mServiceId); // SPRD: bug805154
        mImsUtImpl = new ImsUtImpl(mImsRadioInterface, phone.getContext(), phone, this);
        com.spreadtrum.ims.vowifi.ImsUtImpl voWifiUtImpl =  mWifiService.getUtInterface(phone.getPhoneId());
        mImsUtProxy = new ImsUtProxy(context, mImsUtImpl, voWifiUtImpl, phone, this);//UNISOC:modif for bug1196935
        mImsEcbmImpl = new ImsEcbmImpl();
        mImsHandler = new ImsHandler(mContext.getMainLooper());
        if(mImsServiceCallTracker == null) {
            mImsServiceCallTracker = new ImsServiceCallTracker(mContext, mImsRadioInterface, null, mServiceId, this, mWifiService);
            SessionListListener listListener = new SessionListListener();
            mImsServiceCallTracker.addListener(listListener);
        }

        /* UNISOC: add for bug916375 @{ */
        Intent intent = new Intent(ImsManager.ACTION_IMS_SERVICE_UP);
        intent.putExtra(ImsManager.EXTRA_PHONE_ID, phone.getPhoneId());
        mContext.sendStickyBroadcast(intent);
        mContext.sendBroadcast(intent);
        /*@}*/

        mImsRadioInterface.registerForImsBearerStateChanged(mImsHandler, EVENT_IMS_PND_STATE_CHANGED, null);
        mImsRadioInterface.registerForImsNetworkStateChanged(mImsHandler, EVENT_IMS_STATE_CHANGED, null);
        mImsRadioInterface.registerForSrvccStateChanged(mImsHandler, EVENT_SRVCC_STATE_CHANGED, null);
        mImsRadioInterface.registerImsHandoverStatus(mImsHandler, EVENT_IMS_HANDOVER_STATE_CHANGED, null);
        mImsRadioInterface.registerImsNetworkInfo(mImsHandler, EVENT_IMS_NETWORK_INFO_UPDATE, null);
        mImsRadioInterface.registerImsRegAddress(mImsHandler, EVENT_IMS_REGISTER_ADDRESS_CHANGED, null);
        mImsRadioInterface.registerImsWiFiParam(mImsHandler, EVENT_IMS_WIFI_PARAM, null);
        mImsRadioInterface.registerForAvailable(mImsHandler, EVENT_RADIO_AVAILABLE, null); // UNISOC: Add for bug968317
        mImsRadioInterface.registerForOn(mImsHandler, EVENT_RADIO_ON, null);               // UNISOC: Add for bug968317
        log("ImsServiceImpl onCreate->phoneId:" + phone.getPhoneId());
        mUiccController = UiccController.getInstance();
        mUiccController.registerForIccChanged(mImsHandler, DctConstants.EVENT_ICC_CHANGED, null);
        mApnChangeObserver = new ApnChangeObserver();
        mPhone.registerForServiceStateChanged(mImsHandler, EVENT_SERVICE_STATE_CHANGED, null);
        mPhone.getContext().getContentResolver().registerContentObserver(
                Telephony.Carriers.CONTENT_URI, true, mApnChangeObserver);
        mImsRadioInterface.registerForRadioStateChanged(mImsHandler, EVENT_RADIO_STATE_CHANGED, null);//SPRD:add for bug594553
        mImsRadioInterface.getImsRegAddress(mImsHandler.obtainMessage(EVENT_IMS_GET_IMS_REG_ADDRESS));//SPRD: add for bug739660
        mImsRadioInterface.registerForImsErrorCause(mImsHandler, EVENT_IMS_ERROR_CAUSE_INFO, null);//UNISOC: add for bug1016116
    }

    /**
     * Used to listen to events.
     */
    private class ImsHandler extends Handler {
        ImsHandler(Looper looper) {
            super(looper);
        }
        @Override
        public void handleMessage(Message msg) {
            if (DBG) log("handleMessage msg=" + msg);
            AsyncResult ar = (AsyncResult) msg.obj;
            switch (msg.what) {
                case EVENT_IMS_STATE_CHANGED:
                    mImsRadioInterface.getImsRegistrationState(this.obtainMessage(EVENT_IMS_STATE_DONE));
                    // SPRD 681641 701983
                    if (ar.exception == null && ar.result != null && ar.result instanceof Integer) {
                        Integer responseArray = (Integer)ar.result;
                        mImsServiceState.mRegState = responseArray.intValue();
                        mImsServiceState.mImsRegistered = (mImsServiceState.mRegState == IMS_REG_STATE_REGISTERED
                                ? true : false);
                        // SPRD 869523
                        mImsServiceState.mSrvccState = -1;
                        log("EVENT_IMS_STATE_CHANGED->mRegState = "
                            + mImsServiceState.mRegState + " | mSrvccState = "
                            + mImsServiceState.mSrvccState);
                        switch(mImsServiceState.mRegState){
                            case IMS_REG_STATE_INACTIVE:
                                break;
                            case IMS_REG_STATE_REGISTERED:
                                break;
                            case IMS_REG_STATE_REG_FAIL:
                                break;
                            case IMS_REG_STATE_UNKNOWN:
                                break;
                            case IMS_REG_STATE_ROAMING:
                                break;
                            case IMS_REG_STATE_DEREGISTERING:
                                try{
                                    if(mListener == null){
                                        log("handleMessage msg=" + msg.what+" mListener is null!");
                                        break;
                                    }
                                    mListener.registrationProgressing();
                                    synchronized (mImsRegisterListeners) {
                                        for (IImsRegistrationListener l : mImsRegisterListeners.values()) {
                                            l.registrationProgressing();
                                        }
                                    }
                                } catch (RemoteException e){
                                    e.printStackTrace();
                                }
                                break;
                            default:
                                break;
                        }

                        /* UNISOC: add for bug968317 @{ */
                        if (((mImsServiceState.mRegState == IMS_REG_STATE_INACTIVE) || (mImsServiceState.mRegState == IMS_REG_STATE_REGISTERED))
                            && (VoLTECallAvailSync == VoLTECallAvailSyncStatus.VOLTE_CALL_AVAIL_SYNC_FAIL)) {
                            log("EVENT_IMS_STATE_CHANGED -> setVoLTECallAvailablity");
                            setVoLTECallAvailablity();
                        }
                        break;
                        /*@}*/
                    } else {
                        log("EVENT_IMS_STATE_CHANGED : ar.exception = "+ar.exception +" ar.result:"+ar.result);
                    }
                    break;
                case EVENT_IMS_STATE_DONE:
                    if (ar.exception == null && ar.result != null) {
                        // to be done
                        // SPRD 723085 mImsRegistered shouldn't be set twice time.
//                        int[] responseArray = (int[])ar.result;
//                        if(responseArray != null && responseArray.length >1){
//                            mImsServiceState.mImsRegistered = (responseArray[0] != 0 && responseArray[1]== 1);
//                            mImsServiceState.mSrvccState = -1;
//                        }
                    } else {
                        log("EVENT_IMS_STATE_DONE->ar.exception mServiceId:"+mServiceId);
                        mImsServiceState.mImsRegistered = false;
                    }
                    //add for SPRD:Bug 678430
                    log( "setTelephonyProperty mServiceId:"+mServiceId+"mImsRegistered:"+mImsServiceState.mImsRegistered
                          + ", VoPSRadioAvailable:" + isRadioAvailableForImsService()); // UNISOC: Modify for bug1156172
                    // add for send IMS registed broadcast
                    if (mImsServiceState.mImsRegistered) {
                        sendImsRegistedBroadcast(mServiceId - 1);
                        setUtDisableByNetWork(false);//UNISOC: add by bug1016166
                    }
                    TelephonyManager.setTelephonyProperty(mServiceId-1, "gsm.sys.volte.state",
                            (mImsServiceState.mImsRegistered && isRadioAvailableForImsService())? "1" :"0"); // UNISOC: Modify for bug1065583
                    notifyRegisterStateChange();
                    log("EVENT_IMS_STATE_DONE->mServiceState:" + mImsServiceState.mImsRegistered);
                    break;
                case DctConstants.EVENT_ICC_CHANGED:
                    onUpdateIcc();
                    break;
                case DctConstants.EVENT_RECORDS_LOADED:
                    onRecordsLoaded();
                    break;
                case DctConstants.EVENT_APN_CHANGED:
                    onApnChanged();
                    break;
                case EVENT_IMS_REGISTER_ADDRESS_CHANGED:
                    if(ar.exception == null && ar.result != null){
                        String[] address = (String[]) ar.result;
                        setIMSRegAddress(address[0]);
                        if (address.length > 1) {//SPRD: add for bug731711
                            log( "EVENT_IMS_REGISTER_ADDRESS_CHANGED psfcsAddr:" + address[1]);
                            mImsPscfAddress = address[1];
                        }
                    }else{
                        log("EVENT_IMS_REGISTER_ADDRESS_CHANGED has exception!");
                    }
                    break;
                case EVENT_SRVCC_STATE_CHANGED:
                    if (ar.exception == null) {
                        int[] ret = (int[]) ar.result;
                        if (ret != null && ret.length != 0) {
                            mImsServiceState.mSrvccState = ret[0];
                            // SPRD 689713
                            if (mImsServiceState.mSrvccState == VoLteServiceState.HANDOVER_COMPLETED) {
                                log( "Srvcc HANDOVER_COMPLETED : setTelephonyProperty mServiceId = " + mServiceId);
                                TelephonyManager.setTelephonyProperty(mServiceId-1, "gsm.sys.volte.state", "0");
                            }
                            mImsService.notifyImsRegisterState();
                            mImsService.notifySrvccState(mServiceId,mImsServiceState.mSrvccState);
                            log( "Srvcc state: " + ret[0]);
                        } else {
                            log("Srvcc error ret: " + Arrays.toString(ret));
                        }
                    } else {
                        log( "Srvcc exception: " + ar.exception);
                    }
                    break;
                case EVENT_SERVICE_STATE_CHANGED:
                    log("EVENT_SERVICE_STATE_CHANGED->ServiceStateChange");
                    ServiceState state = (ServiceState) ((AsyncResult) msg.obj).result;
                    if (state != null) {
                        log("DataRadioType:" + state.getRilDataRadioTechnology() + ", DataRegState:" + state.getDataRegState());
                        log("mImsRegistered:" + mImsServiceState.mImsRegistered + ", isVoLteEnabled:" + isVoLteEnabled()
                            + ", isVoLTERegistered:" + ImsManagerEx.isVoLTERegisteredForPhone(mServiceId - 1));
                        if (state.getDataRegState() == ServiceState.STATE_IN_SERVICE
                                && mImsService.isRadioSupportImsService(state.getRilDataRadioTechnology()) // UNISOC: Modify for bug1046061,bug1065583
                                && !mImsService.isAirplaneModeOn()) { // UNISOC: Modify for bug1295860
                            if (mImsServiceState.mImsRegistered && (!isVoLteEnabled() || !ImsManagerEx.isVoLTERegisteredForPhone(mServiceId - 1))) { // UNISOC: Modify for bug1156172
                                log("RAT switched to VoPS radioType:" + state.getRilDataRadioTechnology() + ", mImsRegistered:" + mImsServiceState.mImsRegistered
                                        + ", isVoLteEnabled:" + isVoLteEnabled());
                                mImsRadioInterface.getImsRegistrationState(this.obtainMessage(EVENT_IMS_STATE_CHANGED)); // UNISOC: Add for bug1065583
                            }
                            mImsRegister.enableIms();
                            // White list refactor: get default video resolution
                            mImsRadioInterface.getVideoResolution(this.obtainMessage(EVENT_GET_VIDEO_RESOLUTION));
                            // UNISOC:add for bug 1181272
                            mImsRadioInterface.getSmsBearer(this.obtainMessage(EVENT_GET_SMS_OVER_IP));
                        } else {
                            if (isRadioAvailableForImsService()) { // UNISOC: Add for bug1087243
                                log("RAT switched to temp non-VoPS radioType:" + state.getRilDataRadioTechnology() + ", mSrvccState:" + mImsServiceState.mSrvccState);
                            } else if (isVoLteEnabled() || ImsManagerEx.isVoLTERegisteredForPhone(mServiceId - 1)  // UNISOC: Modify for bug1156172
                                       || (mImsServiceState.mSrvccState == VoLteServiceState.HANDOVER_COMPLETED)) {
                                log("RAT switched to non-VoPS radioType:" + state.getRilDataRadioTechnology() + ", mSrvccState:" + mImsServiceState.mSrvccState);
                                if(!mImsService.isRadioSupportImsService(state.getRilDataRadioTechnology())) {
                                    mImsServiceState.mSrvccState = -1;
                                }
                                TelephonyManager.setTelephonyProperty(mServiceId - 1, "gsm.sys.volte.state", "0");
                                mImsService.updateImsFeature(mServiceId);
                            }
                        }
                        //UNISOC: modify by bug1188908
                        getSpecialRatcap(state);
                        setInitialAttachSosApn(state);
                    }

                    break;
                // White list refactor: get default video resolution
                case EVENT_GET_VIDEO_RESOLUTION:  //Unisoc change for bug 1035159
                    if (ar.exception == null && ar.result != null ) {
                        int[] videoResolution = (int[]) ar.result;
                        log("EVENT_GET_VIDEO_RESOLUTION from NV: " + videoResolution[0]);
                        setVideoResolution(videoResolution[0]);
                    }
                    break;
                case EVENT_GET_SMS_OVER_IP:
                    if (ar.exception == null && ar.result != null ) {
                        int[] smsType = (int[]) ar.result;
                        log("EVENT_GET_SMS_OVER_IP from NV: " + smsType[0]);
                        setSmsOverIp(smsType[0]);// UNISOC:add for bug 1181272
                    }
                    break;
                case EVENT_GET_RAT_CAP_NV_CONFIG:
                    if (ar.exception == null && ar.result != null) {
                        int[] responseArray = (int[]) ar.result;
                        log("GET_RAT_CAP_NV_CONFIG " + responseArray[0]);
                        mNetworkRATPrefer = responseArray[0];
                        mImsRadioInterface.getSpecialRatcap(mImsHandler.obtainMessage(EVENT_GET_RAT_CAP_RESULT), ImsRadioInterface.GET_RAT_CAP_RESULT);
                    } else {
                        log("GET_RAT_CAP_NV_CONFIG ar.exception: " + ar.exception);
                    }
                    break;
                /* SPRD: add for bug594553 @{ */
                case EVENT_RADIO_STATE_CHANGED:
                    log("EVENT_RADIO_STATE_CHANGED->mImsRegistered:" + mImsServiceState.mImsRegistered +"  isRaidoOn=" + mPhone.isRadioOn());
                    if (!mPhone.isRadioOn()) {
                        mImsServiceState.mImsRegistered = false;
                        // add for unisoc 947149
                        TelephonyManager.setTelephonyProperty(mServiceId-1, "gsm.sys.volte.state",
                            mImsServiceState.mImsRegistered ? "1" :"0");
                        notifyRegisterStateChange();
                    }
                   break;
                /* @} */
               case EVENT_IMS_HANDOVER_STATE_CHANGED:
                    if (ar != null) {
                        if (ar.exception == null && ar.result != null
                                && ar.result instanceof Integer) {
                            Integer response = (Integer) ar.result;
                            mImsService.onImsHandoverStateChange(true, response.intValue());
                        } else {
                            log("EVENT_IMS_HANDOVER_STATE_CHANGED : ar.exception = " + ar.exception
                                    + " ar.result:" + ar.result);
                        }
                    }
                    break;
                case EVENT_IMS_HANDOVER_ACTION_COMPLETE:
                    if (ar == null || ar.exception != null) {
                        mImsService.onImsHandoverStateChange(false, ImsService.IMS_HANDOVER_ACTION_CONFIRMED);
                    } else {
                        mImsService.onImsHandoverStateChange(true, ImsService.IMS_HANDOVER_ACTION_CONFIRMED);
                    }
                    break;
                case EVENT_IMS_PND_STATE_CHANGED:
                    if (ar != null && ar.exception == null && ar.result != null && ar.result instanceof Integer) {
                        Integer responseArray = (Integer)ar.result;
                        mImsService.onImsPdnStatusChange(mServiceId,responseArray.intValue());
                        notifyImsPdnStateChange(responseArray.intValue());
                        if(responseArray.intValue() == ImsService.ImsPDNStatus.IMS_PDN_READY){
                                mImsRadioInterface.getImsPcscfAddress(mImsHandler.obtainMessage(EVENT_IMS_GET_PCSCF_ADDRESS));
                        }
                        /* UNISOC: add for bug968317 @{ */
                        if(((responseArray.intValue() == ImsService.ImsPDNStatus.IMS_PDN_READY) || (responseArray.intValue() == ImsService.ImsPDNStatus.IMS_PDN_ACTIVE_FAILED))
                            && (VoLTECallAvailSync == VoLTECallAvailSyncStatus.VOLTE_CALL_AVAIL_SYNC_FAIL)) {
                                log("EVENT_IMS_PND_STATE_CHANGED -> setVoLTECallAvailablity, ImsPDNStatus = " + responseArray.intValue());
                                setVoLTECallAvailablity();
                        }
                        /*@}*/
                    } else {
                        if (ar != null) {
                            log("EVENT_IMS_PND_STATE_CHANGED: ar.exception" + ar.exception
                                    + " ar.result: " + ar.result);
                        } else {
                            log("EVENT_IMS_PND_STATE_CHANGED: ar is null");
                        }
                    }
                    break;
                case EVENT_IMS_NETWORK_INFO_UPDATE:
                    if (ar != null && ar.exception == null && ar.result != null) {
                        ImsNetworkInfo info = (ImsNetworkInfo)ar.result;
                        log("EVENT_IMS_NETWORK_INFO_UPDATE->info.mType: " + info.type + "info.mInfo: " + info.info);
                        mImsService.onImsNetworkInfoChange(info.type, info.info);
                    }
                    break;
                /*SPRD: add for bug612670 @{ */
                case EVENT_SET_VOICE_CALL_AVAILABILITY_DONE:
                    /* UNISOC: modify for bug968317 @{ */
                    if(ar.exception != null){
                        log("EVENT_SET_VOICE_CALL_AVAILABILITY_DONE: exception "+ar.exception);
                        log("Set VoLTE Call Availability failure, currentVoLTESetting = " + currentVoLTESetting);
                    }

                    if(pendingVoLTESetting != IMS_INVALID_VOLTE_SETTING) {
                        log("set new VoLTESetting=" + pendingVoLTESetting);
                        currentVoLTESetting = pendingVoLTESetting;
                        setVoLTECallAvailablity();
                        pendingVoLTESetting = IMS_INVALID_VOLTE_SETTING;
                        break;
                    }

                    if(ar.exception != null){
                        VoLTECallAvailSync = VoLTECallAvailSyncStatus.VOLTE_CALL_AVAIL_SYNC_FAIL;
                    } else {
                        log("EVENT_SET_VOICE_CALL_AVAILABILITY_DONE, currentVoLTESetting: " + currentVoLTESetting +
                                " mImsRegister.mSimChanged: "+mImsRegister.mSimChanged);
                        if(currentVoLTESetting == ImsConfig.FeatureValueConstants.ON
                                && mImsRegister.mSimChanged){
                            mImsRegister.enableIms();
                        }
                        VoLTECallAvailSync  = VoLTECallAvailSyncStatus.VOLTE_CALL_AVAIL_SYNC_IDLE;
                        currentVoLTESetting = IMS_INVALID_VOLTE_SETTING;
                    }
                    /*@}*/
                    break;
                /*@}*/
                case EVENT_IMS_WIFI_PARAM:
                    if (ar != null && ar.exception == null && ar.result != null) {
                        onWifiParamEvent(ar);
                    }
                    break;
                case EVENT_IMS_GET_SRVCC_CAPBILITY:
                    if (ar != null && ar.exception == null && ar.result != null) {
                        int[] conn = (int[]) ar.result;
                        mSrvccCapbility = conn[0];
                        mImsService.notifySrvccCapbility(mSrvccCapbility);
                        log("EVENT_SET_VOICE_CALL_AVAILABILITY_DONE:"+mSrvccCapbility);
                    }
                    break;
                case EVENT_IMS_GET_PCSCF_ADDRESS:
                    if (ar != null && ar.exception == null && ar.result != null){
                        mImsPscfAddress = (String)ar.result;
                        log("EVENT_IMS_GET_PCSCF_ADDRESS,mImsPscfAddress:"+mImsPscfAddress);
                    }
                    break;
                case EVENT_IMS_GET_IMS_REG_ADDRESS:
                    if (ar != null && ar.exception == null && ar.result != null) {
                        String[] address = (String[]) ar.result;
                        if (address.length >= 2) {
                            setIMSRegAddress(address[0]);
                            mImsPscfAddress = address[1];
                            log( "EVENT_IMS_GET_IMS_REG_ADDRESS,mImsPscfAddress:" + mImsPscfAddress);
                        }
                    }
                    break;
                /* UNISOC: add for bug968317 @{ */
                case EVENT_RADIO_AVAILABLE:
                    log("EVENT_RADIO_AVAILABLE");
                    if (VoLTECallAvailSync == VoLTECallAvailSyncStatus.VOLTE_CALL_AVAIL_SYNC_FAIL) {
                        log("EVENT_RADIO_AVAILABLE -> setVoLTECallAvailablity");
                        setVoLTECallAvailablity();
                    }
                    break;
                case EVENT_RADIO_ON:
                    log("EVENT_RADIO_ON");
                    if (VoLTECallAvailSync == VoLTECallAvailSyncStatus.VOLTE_CALL_AVAIL_SYNC_FAIL) {
                        log("EVENT_RADIO_ON -> setVoLTECallAvailablity");
                        setVoLTECallAvailablity();
                    }
                    break;
                /*@}*/
                case EVENT_IMS_GET_IMS_CNI_INFO:
                    if (ar != null && ar.exception == null && ar.result != null) {
                        ImsNetworkInfo info = (ImsNetworkInfo)ar.result;
                        Log.i(TAG,"EVENT_IMS_GET_IMS_CNI_INFO->info.type: " + info.type + " info.info:" + info.info +" info.age:" + info.age );
                        mImsService.onImsCNIInfoChange(info.type, info.info, info.age);
                    }
                    break;
                case EVENT_IMS_ERROR_CAUSE_INFO:
                    if (ar != null && ar.exception == null && ar.result != null) {

                        ImsErrorCauseInfo ImsErrorCauseInfo = (ImsErrorCauseInfo) ar.result;
                        onImsErrCauseInfoChange(ImsErrorCauseInfo);
                    } else {
                        if (ar != null) {
                            log("EVENT_IMS_REGISTER_SPIMS_REASON: ar.exception" + ar.exception + " ar.result: " + ar.result);
                        } else {
                            log("EVENT_IMS_REGISTER_SPIMS_REASON: ar == null");
                        }
                    }
                    break;
                case EVENT_GET_RAT_CAP_RESULT:
                    //UNISOC: add for bug1024577
                    if (ar.exception == null && ar.result != null) {
                        int[] responseArray = (int[]) ar.result;
                        log("EVENT_GET_RAT_CAP_RESULT " + responseArray[0]);
                        setUtDisableByNetWork(responseArray[0] == 1);//1-csfb
                        mImsService.updateImsFeature(mServiceId);
                    } else {
                        log("EVENT_GET_RAT_CAP_RESULT ar.exception: " + ar.exception);
                    }
                    break;
                default:
                    break;
            }
        }
    };

    public int startSession(PendingIntent incomingCallIntent, IImsRegistrationListener listener) {
        mIncomingCallIntent = incomingCallIntent;
        mListener = listener;
        if(mImsServiceCallTracker == null) {
            mImsServiceCallTracker = new ImsServiceCallTracker(mContext, mImsRadioInterface, mIncomingCallIntent, mServiceId, this, mWifiService);
            SessionListListener listListener = new SessionListListener();
            mImsServiceCallTracker.addListener(listListener);
        }
        try{
            mEnabledFeatures[ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_LTE]
                    = ImsConfig.FeatureConstants.FEATURE_TYPE_UNKNOWN;
            mEnabledFeatures[ImsConfig.FeatureConstants.FEATURE_TYPE_VIDEO_OVER_LTE]
                    = ImsConfig.FeatureConstants.FEATURE_TYPE_UNKNOWN;
            mEnabledFeatures[ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_WIFI]
                    = ImsConfig.FeatureConstants.FEATURE_TYPE_UNKNOWN;
            mEnabledFeatures[ImsConfig.FeatureConstants.FEATURE_TYPE_VIDEO_OVER_WIFI]
                    = ImsConfig.FeatureConstants.FEATURE_TYPE_UNKNOWN;
            mListener.registrationFeatureCapabilityChanged(
                    ImsServiceClass.MMTEL,mEnabledFeatures, mDisabledFeatures);
            synchronized (mImsRegisterListeners) {
                for (IImsRegistrationListener l : mImsRegisterListeners.values()) {
                    try{
                        l.registrationFeatureCapabilityChanged(
                                ImsServiceClass.MMTEL,mEnabledFeatures, mDisabledFeatures);
                    } catch(RemoteException e){
                        e.printStackTrace();
                        continue;
                    }
                }
            }
            setFeatureState(ImsFeature.STATE_READY);
        } catch (RemoteException e){
            e.printStackTrace();
        }
        return mServiceId;
    }

    /**
     * send this broadcast for DM
     * @param phoneId
     */
    private void sendImsRegistedBroadcast(int phoneId) {

        Intent intent = new Intent(VOLTE_REGISTED_BROADCAST);
        intent.putExtra(ImsManager.EXTRA_PHONE_ID, phoneId);
        intent.setFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
        intent.setComponent(new ComponentName(VOLTE_REGISTED_PAC_NAME, VOLTE_REGISTED_CLASSNAME));

        mContext.sendBroadcast(intent);
    }


    @Override
    public int getFeatureState() {
        return ImsFeature.STATE_READY;
    }

    public void addRegistrationListener(IImsRegistrationListener listener) {
        if (listener == null) {
            log("addRegistrationListener->Listener is null!");
            Thread.dumpStack();
            return;
        }
        synchronized (mImsRegisterListeners) {
            if (!mImsRegisterListeners.keySet().contains(listener.asBinder())) {
                mImsRegisterListeners.put(listener.asBinder(), listener);
            } else {
                log("Listener already add :" + listener);
            }
        }
    }

    public void removeRegistrationListener(IImsRegistrationListener listener) {
        if (listener == null) {
            log("removeRegistrationListener->Listener is null!");
            Thread.dumpStack();
            return;
        }
        synchronized (mImsRegisterListeners) {
            if (mImsRegisterListeners.keySet().contains(listener.asBinder())) {
                mImsRegisterListeners.remove(listener.asBinder());
            } else {
                log("Listener already add :" + listener);
            }
        }
    }

    @Override
    public ImsCallProfile createCallProfile(int callSessionType, int callType) {
        return new ImsCallProfile(callSessionType,callType);
    }

    public IImsCallSession getPendingCallSession(int sessionId, String callId) {
        log("getPendingCallSession->callId:" + callId +
                " mImsServiceCallTracker:"+mImsServiceCallTracker);
        if(mImsServiceCallTracker != null){
            return mImsServiceCallTracker.getCallSession(callId);
        } else {
            return null;
        }
    }

    @Override
    public IImsUt getUtInterface() {
        return mImsUtProxy;
    }

    public IImsConfig getConfigInterface() {
        return (IImsConfig)mImsConfigImpl;
    }

    public void turnOnIms() {
        log("turnOnIms.");
        //add for bug 612670
    }

    public void turnOffIms() {
        log("turnOffIms.");
        //add for bug 612670
    }

    @Override
    public IImsEcbm getEcbmInterface() {
        return mImsEcbmImpl;
    }

    @Override
    public void setUiTtyMode(int uiTtyMode, Message onComplete) {

    }

    @Override
    public void onFeatureRemoved() {

    }

    public IImsMmTelFeature  onCreateImsFeature(IImsFeatureStatusCallback c){
        log("onCreateImsFeature.");
        mImsFeatureStatusCallback = c;
        try {
            if(mImsFeatureStatusCallback == null){
                log("ImsServiceImpl mImsFeatureStatusCallback is null!");
            } else {
                mImsFeatureStatusCallback.notifyImsFeatureStatus(ImsFeature.STATE_READY);
            }
        } catch (RemoteException e){
            e.printStackTrace();
        }
        return getBinder();
    }


    public int open(int serviceClass, PendingIntent incomingCallIntent,
            IImsRegistrationListener listener){
        mServiceClass = serviceClass;
        mIncomingCallIntent = incomingCallIntent;
        mListener = listener;
        if(mImsServiceCallTracker == null) {
            mImsServiceCallTracker = new ImsServiceCallTracker(mContext, mImsRadioInterface, mIncomingCallIntent, mServiceId, this, mWifiService);
            SessionListListener listListener = new SessionListListener();
            mImsServiceCallTracker.addListener(listListener);
        }
        try{
            mEnabledFeatures[ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_LTE]
                    = ImsConfig.FeatureConstants.FEATURE_TYPE_UNKNOWN;
            mEnabledFeatures[ImsConfig.FeatureConstants.FEATURE_TYPE_VIDEO_OVER_LTE]
                    = ImsConfig.FeatureConstants.FEATURE_TYPE_UNKNOWN;
            mEnabledFeatures[ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_WIFI]
                    = ImsConfig.FeatureConstants.FEATURE_TYPE_UNKNOWN;
            mEnabledFeatures[ImsConfig.FeatureConstants.FEATURE_TYPE_VIDEO_OVER_WIFI]
                    = ImsConfig.FeatureConstants.FEATURE_TYPE_UNKNOWN;
            mListener.registrationFeatureCapabilityChanged(
                    ImsServiceClass.MMTEL,mEnabledFeatures, mDisabledFeatures);
            synchronized (mImsRegisterListeners) {
                for (IImsRegistrationListener l : mImsRegisterListeners.values()) {
                    try{
                        l.registrationFeatureCapabilityChanged(
                                ImsServiceClass.MMTEL,mEnabledFeatures, mDisabledFeatures);
                    } catch(RemoteException e){
                        e.printStackTrace();
                        continue;
                    }
                }
            }
        } catch (RemoteException e){
            e.printStackTrace();
        }
        setFeatureState(ImsFeature.STATE_READY);
        return mServiceId;
    }

    public void close(){

    }


    public void setRegistrationListener(IImsRegistrationListener listener){
        mListener = listener;
    }

    public ImsUtImpl getUtImpl(){
        return mImsUtImpl;
    }

    public IImsUt getUTProxy(){
        return mImsUtProxy;
    }

    private void onUpdateIcc() {
        if (mUiccController == null ) {
            return;
        }
        IccRecords newIccRecords = getUiccRecords(UiccController.APP_FAM_3GPP);

        IccRecords r = mIccRecords.get();
        if (r != newIccRecords) {
            if (r != null) {
                if (DBG) log("Removing stale icc objects.");
                r.unregisterForRecordsLoaded(mImsHandler);
                mIccRecords.set(null);
            }
            if (newIccRecords != null) {
                if (DBG) log("New records found");
                mIccRecords.set(newIccRecords);
                newIccRecords.registerForRecordsLoaded(
                        mImsHandler, DctConstants.EVENT_RECORDS_LOADED, null);
            }
        }
    }
    private IccRecords getUiccRecords(int appFamily) {
        return mUiccController.getIccRecords(mPhone.getPhoneId(), appFamily);
    }
    private void onRecordsLoaded() {
        if (DBG) log("onRecordsLoaded: createAllApnList");
        IccRecords r = mIccRecords.get();
        String operator = (r != null) ? r.getOperatorNumeric() : "";
        ArrayList<ApnSetting> apnSettings = createAllApnList(operator);
        setInitialAttachIMSApn(apnSettings);
    }

    private ArrayList<ApnSetting> createAllApnList(String operator){
        ArrayList<ApnSetting> allApnSettings = new ArrayList<ApnSetting>();
        if (operator != null) {
            String selection = "numeric = '" + operator + "'";
            String orderBy = "_id";
            if (DBG) log("createAllApnList: selection=" + selection);

            Cursor cursor = mPhone.getContext().getContentResolver().query(
                    Telephony.Carriers.CONTENT_URI, null, selection, null, orderBy);

            if (cursor != null) {
                if (cursor.getCount() > 0) {
                    allApnSettings = ApnUtils.createApnList(cursor);
                }
                cursor.close();
            }
        }
        return allApnSettings;
    }

    private void setInitialAttachIMSApn(ArrayList<ApnSetting> apnSettings){
        ApnSetting apn = null;
        for (ApnSetting a : apnSettings) {
            if (a.canHandleType(ApnSetting.TYPE_IMS)) {
                apn = a;
                break;
            }
        }
        if (apn == null) {
            if (DBG) log("initialAttachIMSApnSetting: X There in no available ims apn");
        }else {
            if (DBG) log("initialAttachIMSApnSetting: X selected ims Apn=" + apn);
            mImsRadioInterface.setExtInitialAttachApn(ApnUtils.createDataProfile(apn,false), null);

        }
    }

    /**
     * Handles changes to the APN database.
     */
    private void onApnChanged() {
        if (DBG) log("onApnChanged: createAllApnList");
        IccRecords r = mIccRecords.get();
        String operator = (r != null) ? r.getOperatorNumeric() : "";
        ArrayList<ApnSetting> apnSettings = createAllApnList(operator);
        setInitialAttachIMSApn(apnSettings);
    }

    public int getServiceId(){
        return mServiceId;
    }

    public IImsCallSession getPendingCallSession(String callId){
        log("getPendingCallSession->callId:" + callId +
                " mImsServiceCallTracker:"+mImsServiceCallTracker);
        if(mImsServiceCallTracker != null){
            return mImsServiceCallTracker.getCallSession(callId);
        } else {
            return null;
        }
    }

    public void enableImsWhenPDNReady(){
        log("enableImsWhenPDNReady.");
        mImsRegister.onImsPDNReady();
        mImsRegister.enableIms();
    }


    public int getImsRegisterState(){
        //add for Bug 620214
        if (mImsServiceState.mImsRegistered) {
            if (mPhone.getState() == PhoneConstants.State.IDLE) {
                return ImsManagerEx.IMS_REGISTERED;
            } else {
                if(mImsServiceState.mSrvccState == VoLteServiceState.HANDOVER_STARTED
                        || mImsServiceState.mSrvccState == VoLteServiceState.HANDOVER_COMPLETED){
                    return ImsManagerEx.IMS_UNREGISTERED;
                }
                return ImsManagerEx.IMS_REGISTERED;
            }
        }
        return ImsManagerEx.IMS_UNREGISTERED;
    }

    public boolean isImsRegisterState() {
        // add for Bug 620214
        if (mImsServiceState.mImsRegistered) {
            if (mPhone.getState() == PhoneConstants.State.IDLE) {
                return true;
            } else {
                // SPRD 659914
                if (mImsServiceState.mSrvccState == VoLteServiceState.HANDOVER_COMPLETED) {
                    return false;
                } else if (mImsServiceState.mSrvccState == VoLteServiceState.HANDOVER_FAILED ||
                        mImsServiceState.mSrvccState == VoLteServiceState.HANDOVER_CANCELED) {
                    return true;
                }
                return true;
            }
        }
        return false;
    }

    public int getSrvccState(){
        return mImsServiceState.mSrvccState;
    }

    public void setVideoResolution(int videoResolution){
        log("ImsServiceImpl ==> setVideoResolution mDefaultVtResolution = " + videoResolution);
        // SPRD:909828
        mImsConfigImpl.sendVideoQualitytoIMS(videoResolution);
    }


    /* UNISOC: add for bug1181272 @{ */
    public void setSmsOverIp(int type){
        log("ImsServiceImpl ==> setSmsOverIp mDefaultSmsOverIp = " + type);
        mImsConfigImpl.configSmsBearer(type);
    }
    /*@}*/

    /* UNISOC: 630048 add sos apn for yes 4G @{*/
    //UNISOC: remove sos APN from volte-conf.xml to apns-conf_8_v2.xml
    public void setInitialAttachSosApn(ServiceState state){
        String carrier = state.getOperatorNumeric();
        if (!TextUtils.isEmpty(carrier) && !carrier.equals(mNWNumeric)) {
            ArrayList<ApnSetting> apnSettings = createAllApnList(carrier);
            ApnSetting apn = null;
            for (ApnSetting a : apnSettings) {
                if (a.canHandleType(ApnSetting.TYPE_EMERGENCY)) {
                    apn = a;
                    break;
                }
            }
            if (apn == null) {
                if (DBG) log("setInitialAttachSosApn: X There is no available emergency apn");
            } else {
                if (DBG) log("setInitialAttachSosApn: X selected emergency Apn=" + apn);
                mImsRadioInterface.setExtInitialAttachApn(ApnUtils.createDataProfile(apn,false), null);
            }
            mNWNumeric = carrier;
        }
    }
    /* @} */

    class SessionListListener implements ImsServiceCallTracker.SessionListListener {
        @Override
        public void onSessionConnected(ImsCallSessionImpl callSession){
        }

        @Override
        public void  onSessionDisonnected(ImsCallSessionImpl callSession){
            if(mImsServiceCallTracker.isSessionListEmpty()){
                notifySessionEmpty();
            }
        }
    }

    public interface Listener {
        void onRegisterStateChange(int serviceId);
        void onSessionEmpty(int serviceId);
    }

    public void addListener(Listener listener){
        if (listener == null) {
            log("addListener-> listener is null!");
            return;
        }
        if (!mListeners.contains(listener)) {
            mListeners.add(listener);
        } else {
            log("addListener-> listener already add!");
        }
    }

    public void removeListener(Listener listener){
        if (listener == null) {
            log("removeListener-> listener is null!");
            return;
        }
        if (mListeners.contains(listener)) {
            mListeners.remove(listener);
        } else {
            log("addListener-> listener already remove!");
        }
    }

    public void notifyRegisterStateChange() {
        // SPRD Add for DSDA bug684926:
        // If dual volte active, update RAT to 4G and voice reg state to in service.
        // And if dual volte not active, service state need to be set to correct state.
        int phoneCount = TelephonyManager.from(mContext).getPhoneCount();
        if(phoneCount > 1 && mPhone.getPhoneId() != mImsRegister.getPrimaryCard(phoneCount)) {
            log( "Ims Register State Changed, poll state again on vice SIM,"
                    + "phone Id = " + mPhone.getPhoneId());
            mPhone.getServiceStateTracker().pollState();
        }
        for (Listener listener : mListeners) {
            listener.onRegisterStateChange(mServiceId);
        }
    }

    public void notifySessionEmpty() {
        for (Listener listener : mListeners) {
            listener.onSessionEmpty(mServiceId);
        }
    }

    public boolean isImsRegistered(){
        return mImsServiceState.mImsRegistered;
    }

    public ImsRegister getImsRegister() {
        return mImsRegister;
    }

    public void notifyImsRegister(boolean isRegistered, boolean isVolte, boolean isWifiRegistered){   // UNISOC: Modify for bug880865
        try{
            // SPRD: 730973
            if(isRegistered){
                mVolteRegisterStateOld = true;
            } else {
                mVolteRegisterStateOld = false;
            }
            synchronized (mIImsRegistrationCallbacks) {
                for (IImsRegistrationCallback l : mIImsRegistrationCallbacks.values()) {
                    if(isRegistered) {
                        l.onRegistered(isVolte ? ImsRegistrationImplBase.REGISTRATION_TECH_LTE
                                : ImsRegistrationImplBase.REGISTRATION_TECH_IWLAN);
                    } else {
                        l.onDeregistered(new ImsReasonInfo());
                    }
                }
            }

            log("notifyImsRegister->isRegistered:" + isRegistered
                    + " isWifiRegistered:"+isWifiRegistered
                    + " isImsEnabled():"+mImsService.isImsEnabled()
                    + " mImsService.isVoLTEEnabled():"+mImsService.isVoLTEEnabled()
                    + " mImsService.isVoWifiEnabled():"+mImsService.isVoWifiEnabled());
            log("notifyImsRegister->mServiceState:" + isRegistered);

            // UNISOC: Add for bug880865
            TelephonyManager.setTelephonyProperty(mServiceId-1, "gsm.sys.vowifi.state",
                                                  isWifiRegistered ? "1" :"0");

            mImsRegister.notifyImsStateChanged(isRegistered);
            if(mListener == null){
                log("notifyImsRegister->mListener is null!");
                return;
            }
            if(isRegistered){
                mListener.registrationConnected();
            } else {
                mListener.registrationDisconnected(new ImsReasonInfo());
            }
            synchronized (mImsRegisterListeners) {
                for (IImsRegistrationListener l : mImsRegisterListeners.values()) {
                    if(isRegistered){
                        l.registrationConnected();
                    } else {
                        l.registrationDisconnected(new ImsReasonInfo());
                    }
                }
            }

        } catch (RemoteException e){
            e.printStackTrace();
        }
    }

    public void sendIncomingCallIntent(IImsCallSession c, String callId, boolean unknownSession, boolean isVolteCall) { // UNISOC: Modify for bug909030
        Bundle extras = new Bundle();
        extras.putBoolean(ImsManager.EXTRA_USSD, false);
        extras.putBoolean(ImsManager.EXTRA_IS_UNKNOWN_CALL, unknownSession); // UNISOC: Modify for bug909030
        extras.putString(ImsManager.EXTRA_CALL_ID, callId);
            /*SPRD: Modify for bug586758{@*/
        log("sendIncomingCallIntent-> " + (isVolteCall ? "startVolteCall" : "startVoWifiCall") // UNISOC: Modify for bug909030
                + " mIsVowifiCall: " + mImsService.isVowifiCall()
                + " mIsVolteCall: " + mImsService.isVolteCall()
                + " isVoWifiEnabled(): " + mImsService.isVoWifiEnabled()
                + " isVoLTEEnabled(): " + mImsService.isVoLTEEnabled());

        /*UNISOC: Add for bug909030{@*/
        mImsService.setInCallPhoneId(mServiceId - 1);
        mImsService.updateInCallState(true);

        int phoneCount = TelephonyManager.from(mContext).getPhoneCount();
        boolean isPrimaryCard = (ImsRegister.getPrimaryCard(phoneCount) == (mServiceId - 1));

        if(isPrimaryCard) {
            /*VoLTE Call*/
            if (mImsService.isVoLTEEnabled() && !mImsService.isVowifiCall() && !mImsService.isVolteCall()) {
                mImsService.setCallType(ImsService.CallType.VOLTE_CALL);
                mWifiService.updateCurCallSlot(mServiceId - 1); // UNISOC: Add for bug1138223
                mWifiService.updateCallRatState(CallRatState.CALL_VOLTE);
            }

            /*VoWifi Call*/
            if (mImsService.isVoWifiEnabled() && !mImsService.isVowifiCall() && !mImsService.isVolteCall()) {
                mImsService.setCallType(ImsService.CallType.WIFI_CALL); // UNISOC: Add for bug909030
                mWifiService.updateCurCallSlot(mServiceId - 1); // UNISOC: Add for bug1138223
                mWifiService.updateCallRatState(CallRatState.CALL_VOWIFI);
            }
            /*@}*/
        }
        /*@}*/
        notifyIncomingCallSession(c,extras);
    }

    public String getIMSRegAddress() {
        if(DBG){
            log("getIMSRegAddress mImsRegAddress = " + mImsRegAddress);
        }
        return mImsRegAddress;
    }

    public String getImsPcscfAddress(){
        if(DBG){
            log("getImsPcscfAddress mImsPscfAddress = " + mImsPscfAddress);
        }
        return mImsPscfAddress;
    }

    public void setIMSRegAddress(String addr) {
        if(DBG){
            log( "setIMSRegAddress addr = " + addr);
        }
        mImsRegAddress = addr;

        int phoneCount = TelephonyManager.from(mContext).getPhoneCount();
        if(mPhone.getPhoneId() == mImsRegister.getPrimaryCard(phoneCount)) { // SPRD: add for bug974910,modify for bug1008539
            //update vowifi address for primary sim.
            Log.d(TAG, "setIMSRegAddress update VoWifi addr, phone Id =" + mPhone.getPhoneId() + " vowifiServId =" + mImsService.getVoWifiServiceId());
            mImsService.setVoWifiLocalAddr(addr);
        }
    }
    public void requestImsHandover(int type){
        log("requestImsHandover->type:" + type);
        mImsRadioInterface.requestImsHandover(type,mImsHandler.obtainMessage(EVENT_IMS_HANDOVER_ACTION_COMPLETE));
    }

    public void notifyImsHandoverStatus(int status){
        log("notifyImsHandoverStatus->status:" + status);
        mImsRadioInterface.notifyImsHandoverStatus(status,null);
    }

    public void notifyImsCallEnd(int type){
        log("notifyImsCallEnd.");
        mImsRadioInterface.notifyImsCallEnd(type,null);
    }

    public void notifyVoWifiEnable(boolean enable){
        log("notifyVoWifiEnable.");
        mImsRadioInterface.notifyVoWifiEnable(enable,null);
    }
    /*SPRD: Add for get network info{@*/
    public void notifyImsNetworkInfo(int type, String info){
        log("notifyImsNetworkInfo->type:"+type+" info:"+info);
        mImsRadioInterface.notifyImsNetworkInfo(type, info,null);
    }
    /*@}*/
    public void onImsCallEnd(){
        mImsService.onImsCallEnd(mServiceId);
    }

    public void notifyWifiCalling(boolean inCall){
        mImsRadioInterface.notifyVoWifiCallStateChanged(inCall,null);
    }
    /*SPRD: Add for notify data router{@*/
    public void notifyDataRouter(){
        log("notifyDataRouter");
        mImsRadioInterface.notifyDataRouter(null);
    }
    /*@}*/
    /*SPRD: Add for bug586758{@*/
    public boolean isVolteSessionListEmpty() {
        if (mImsServiceCallTracker != null) {
            log("isSessionListEmpty: " + mImsServiceCallTracker.isSessionListEmpty()
                + " hasConferenceSession: " + mImsServiceCallTracker.hasConferenceSession());
            return (mImsServiceCallTracker.isSessionListEmpty()
                    && !mImsServiceCallTracker.hasConferenceSession());  //UNISOC: modify for bug1148670
        }
        return false;
    }
    public boolean isVowifiSessionListEmpty() {
        if (mWifiService != null) {
            log("mWifiService.getCallCount(): " + mWifiService.getCallCount());
            return mWifiService.getCallCount()==0;
        }
        return false;
    }
    /*@}*/

    public void onWifiParamEvent(Object object){
        AsyncResult ar =(AsyncResult)object;
        int resultArray[] = (int[]) ar.result;
        log("onWifiParamEvent->rtp_time_Out:" + resultArray[3]);
        mAliveCallLose = resultArray[1];
        mAliveCallJitter = resultArray[2];
        mAliveCallRtt = resultArray[0];
        IImsServiceListenerEx imsServiceListenerEx = mImsService.getImsServiceListenerEx();
        try {
            if (imsServiceListenerEx != null) {
                log("onWifiParamEvent->onMediaQualityChanged->isvideo:false"
                        + " mAliveCallLose:" + mAliveCallLose + " mAliveCallJitter:" + mAliveCallJitter
                        + " mAliveCallRtt:" + mAliveCallRtt);
                imsServiceListenerEx.onMediaQualityChanged(false,mAliveCallLose,mAliveCallJitter,mAliveCallRtt);
                if (resultArray[3] == IMS_CALLING_RTP_TIME_OUT) {
                    log("onWifiParamEvent->onRtpReceived->isvideo:false");
                    imsServiceListenerEx.onNoRtpReceived(false);
                }
            } else {
                log("onWifiParamEvent->imsServiceListenerEx is null");
            }
        } catch(RemoteException e){
            e.printStackTrace();
        }
    }

    public int getAliveCallLose() {
        log("getAliveCallLose->mAliveCallLose:" + mAliveCallLose);
        return mAliveCallLose;
    }

    public int getAliveCallJitter() {
        log("getAliveCallJitter->mAliveCallJitter:" + mAliveCallJitter);
        return mAliveCallJitter;
    }

    public int getAliveCallRtt() {
        log("getAliveCallRtt->mAliveCallRtt:" + mAliveCallRtt);
        return mAliveCallRtt;
    }

    public void updateImsFeatures(boolean volteEnable, boolean wifiEnable){
        log("updateImsFeatures->volteEnable:" + volteEnable + " wifiEnable:" + wifiEnable+" id:"+mServiceId);
        ImsManager imsManager = ImsManager.getInstance(mContext,mPhone.getPhoneId());
        synchronized (mVolteCapabilities) {   // UNISOC: Add for bug978339, bug988585
            try {

                if (volteEnable) {
                    mEnabledFeatures[ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_LTE]
                            = ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_LTE;
                    mVolteCapabilities.addCapabilities(MmTelCapabilities.CAPABILITY_TYPE_VOICE);

                    if (imsManager.isVtEnabledByUser() && imsManager.isVtEnabledByPlatform()) {//SPRD:modify for bug805161
                        mEnabledFeatures[ImsConfig.FeatureConstants.FEATURE_TYPE_VIDEO_OVER_LTE]
                                = ImsConfig.FeatureConstants.FEATURE_TYPE_VIDEO_OVER_LTE;
                        mVolteCapabilities.addCapabilities(MmTelCapabilities.CAPABILITY_TYPE_VIDEO);
                    } else {
                        mEnabledFeatures[ImsConfig.FeatureConstants.FEATURE_TYPE_VIDEO_OVER_LTE]
                                = ImsConfig.FeatureConstants.FEATURE_TYPE_UNKNOWN;
                        mVolteCapabilities.removeCapabilities(MmTelCapabilities.CAPABILITY_TYPE_VIDEO);
                    }
                } else {
                    mEnabledFeatures[ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_LTE]
                            = ImsConfig.FeatureConstants.FEATURE_TYPE_UNKNOWN;
                    mEnabledFeatures[ImsConfig.FeatureConstants.FEATURE_TYPE_VIDEO_OVER_LTE]
                            = ImsConfig.FeatureConstants.FEATURE_TYPE_UNKNOWN;

                    mVolteCapabilities.removeCapabilities(MmTelCapabilities.CAPABILITY_TYPE_VOICE);
                    mVolteCapabilities.removeCapabilities(MmTelCapabilities.CAPABILITY_TYPE_SMS);
                    mVolteCapabilities.removeCapabilities(MmTelCapabilities.CAPABILITY_TYPE_VIDEO);
                }
                if (wifiEnable) {
                    mEnabledFeatures[ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_WIFI]
                            = ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_WIFI;
                    mVowifiCapabilities.addCapabilities(MmTelCapabilities.CAPABILITY_TYPE_VOICE);
                    if (VoWifiConfiguration.isSupportSMS(mContext) && (mImsConfigImpl.getConfigInt(
                            ImsConfig.ConfigConstants.SMS_OVER_IP) == ImsConfig.FeatureValueConstants.ON)){
                        mVowifiCapabilities.addCapabilities(MmTelCapabilities.CAPABILITY_TYPE_SMS);
                    } else {
                        mVowifiCapabilities.removeCapabilities(MmTelCapabilities.CAPABILITY_TYPE_SMS);
                    }
                    if (imsManager.isVtEnabledByUser() && imsManager.isVtEnabledByPlatform()) {//SPRD:modify for bug810321
                        mEnabledFeatures[ImsConfig.FeatureConstants.FEATURE_TYPE_VIDEO_OVER_WIFI]
                                = ImsConfig.FeatureConstants.FEATURE_TYPE_VIDEO_OVER_WIFI;
                        mVowifiCapabilities.addCapabilities(MmTelCapabilities.CAPABILITY_TYPE_VIDEO);
                    } else {
                        mEnabledFeatures[ImsConfig.FeatureConstants.FEATURE_TYPE_VIDEO_OVER_WIFI]
                                = ImsConfig.FeatureConstants.FEATURE_TYPE_UNKNOWN;
                        mVowifiCapabilities.removeCapabilities(MmTelCapabilities.CAPABILITY_TYPE_VIDEO);
                    }
                } else {
                    mEnabledFeatures[ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_WIFI]
                            = ImsConfig.FeatureConstants.FEATURE_TYPE_UNKNOWN;
                    mEnabledFeatures[ImsConfig.FeatureConstants.FEATURE_TYPE_VIDEO_OVER_WIFI]
                            = ImsConfig.FeatureConstants.FEATURE_TYPE_UNKNOWN;
                    mEnabledFeatures[ImsConfig.FeatureConstants.FEATURE_TYPE_UT_OVER_WIFI]
                            = ImsConfig.FeatureConstants.FEATURE_TYPE_UNKNOWN;
                }

                if(isUtEnable()){
                    mVolteCapabilities.addCapabilities(MmTelCapabilities.CAPABILITY_TYPE_UT);
                    mVowifiCapabilities.addCapabilities(MmTelCapabilities.CAPABILITY_TYPE_UT);
                    mEnabledFeatures[ImsConfig.FeatureConstants.FEATURE_TYPE_UT_OVER_LTE]
                            = ImsConfig.FeatureConstants.FEATURE_TYPE_UT_OVER_LTE;
                    mEnabledFeatures[ImsConfig.FeatureConstants.FEATURE_TYPE_UT_OVER_WIFI]
                            = ImsConfig.FeatureConstants.FEATURE_TYPE_UT_OVER_WIFI;
                }else {
                    mVolteCapabilities.removeCapabilities(MmTelCapabilities.CAPABILITY_TYPE_UT);
                    mVowifiCapabilities.removeCapabilities(MmTelCapabilities.CAPABILITY_TYPE_UT);
                    mEnabledFeatures[ImsConfig.FeatureConstants.FEATURE_TYPE_UT_OVER_WIFI]
                            = ImsConfig.FeatureConstants.FEATURE_TYPE_UNKNOWN;
                    mEnabledFeatures[ImsConfig.FeatureConstants.FEATURE_TYPE_UT_OVER_LTE]
                            = ImsConfig.FeatureConstants.FEATURE_TYPE_UNKNOWN;
                }

                if(volteEnable){
                    notifyCapabilitiesStatusChanged(mVolteCapabilities);
                }else if(wifiEnable){
                    mImsUtProxy.updateUtConfig(mWifiService.getUtInterface(mPhone.getPhoneId()));//UNISOC: add for bug1155369
                    notifyCapabilitiesStatusChanged(mVowifiCapabilities);
                }

                synchronized (mImsRegisterListeners) {
                    for (IImsRegistrationListener l : mImsRegisterListeners.values()) {
                        l.registrationFeatureCapabilityChanged(
                                ImsServiceClass.MMTEL, mEnabledFeatures, mDisabledFeatures);
                    }
                }
                if (!volteEnable && !wifiEnable) {
                    notifyCapabilitiesStatusChanged(mVolteCapabilities);
                }

                //wifi capability caused by a handover for bug837323 { */
                setCallRatType(wifiEnable);

                if (mListener == null) {
                    log("updateImsFeatures mListener is null!");
                    return;
                }
                mListener.registrationFeatureCapabilityChanged(
                        ImsServiceClass.MMTEL, mEnabledFeatures, mDisabledFeatures);

            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
    }

    /* SPRD: wifi capability caused by a handover for bug837323 { */
    public void setCallRatType(boolean wifiEnable) {
        if (mImsServiceCallTracker != null) {
            mImsServiceCallTracker.setCallRatType(wifiEnable);
            log(" setCallRatType->ok");
        } else {
            log(" setCallRatType->mImsServiceCallTracker is null");
        }
    }
    /*@}*/

    public int getVolteRegisterState() {
        log("getVolteRegisterState->VolteRegisterState:" + mImsServiceState.mRegState);
        return mImsServiceState.mRegState;
    }
    public void terminateVolteCall(){
        if(mImsServiceCallTracker != null){
            mImsServiceCallTracker.terminateVolteCall();
            log(" terminateVolteCall->ok");
        }else{
            log(" terminateVolteCall->mImsServiceCallTracker is null");
        }
    }

    public void notifyHandoverCallInfo(String callInfo) {
        mImsRadioInterface.notifyHandoverCallInfo(callInfo,null);
    }

    public void getSrvccCapbility() {
        mImsRadioInterface.getSrvccCapbility(mImsHandler.obtainMessage(EVENT_IMS_GET_SRVCC_CAPBILITY));
    }

    //SPRD: add for bug671964
    public void setImsPcscfAddress(String addr) {
        log( "setImsPcscfAddress addr = " + addr);
        String pcscfAdd = "";
        if (addr != null && addr.length() != 0) {

            if (addr.matches("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}")) {
                pcscfAdd = "1,\"" + addr + "\"";
            } else if (addr.contains(":")) {
                //pcscf address is ipv6, address format is "[addr]"
                pcscfAdd = "2,\"[" + addr + "]\""; //UNISOC: modify for bug1173690
            }

            log( "setImsPcscfAddress pcscfAdd = " + pcscfAdd);
            if (pcscfAdd.length() != 0) {
                mImsRadioInterface.setImsPcscfAddress(pcscfAdd, null);
            }
        }
    }
    public void setSrvccState(int srvccState){
        mImsServiceState.mSrvccState = srvccState;
    }

    /**
     * Used for add IMS PDN State Listener.
     */
    public void addImsPdnStateListener(IImsPdnStateListener listener){
        if (listener == null) {
            log("addImsPdnStateListener->Listener is null!");
            Thread.dumpStack();
            return;
        }
        synchronized (mImsPdnStateListeners) {
            if (!mImsPdnStateListeners.keySet().contains(listener.asBinder())) {
                mImsPdnStateListeners.put(listener.asBinder(), listener);
            } else {
                log("addImsPdnStateListener Listener already add :" + listener);
            }
        }
    }

    /**
     * Used for remove IMS PDN State Listener.
     */
    public void removeImsPdnStateListener(IImsPdnStateListener listener){
        if (listener == null) {
            log("removeImsPdnStateListener->Listener is null!");
            Thread.dumpStack();
            return;
        }
        synchronized (mImsPdnStateListeners) {
            if (mImsPdnStateListeners.keySet().contains(listener.asBinder())) {
                mImsPdnStateListeners.remove(listener.asBinder());
            } else {
                log("removeImsPdnStateListener Listener already add :" + listener);
            }
        }
    }

    public boolean isImsEnabled(){
        return ((mEnabledFeatures[ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_LTE]
                == ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_LTE) ||
                (mEnabledFeatures[ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_WIFI]
                == ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_WIFI));
    }

    public boolean isVoLteEnabled(){
        return (mEnabledFeatures[ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_LTE]
                == ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_LTE);
    }

    public boolean isVoWifiEnabled(){
        return ((mEnabledFeatures[ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_WIFI]
                        == ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_WIFI));
    }

    public void updateImsFeatureForAllService(){
        mImsService.updateImsFeatureForAllService();
    }

    public void getSpecialRatcap(ServiceState state) {

        if (state != null && mImsRadioInterface != null) {
            if (mServiceState != state.getState()) {
                log("getSpecialRatcap: servaiceState: " + state.getState());
                mImsRadioInterface.getSpecialRatcap(mImsHandler.obtainMessage(EVENT_GET_RAT_CAP_NV_CONFIG), ImsRadioInterface.GET_RAT_CAP_NV_CONFIG);
                if (state.getState() == ServiceState.STATE_POWER_OFF) {
                    setUtDisableByNetWork(false);
                    log("getSpecialRatcap: mIsUtDisableByNetWork: " + mIsUtDisableByNetWork);
                }
            }
            mServiceState = state.getState();
        }
    }

    /*UNISOC: add for bug1016166 @{*/
    public void setUtDisableByNetWork(boolean value){
        mIsUtDisableByNetWork = value;
    }

    public boolean getCarrierCofValueByKey(int sudId,String key) {

        Log.d(TAG, "getCarrierCofValueByKey sudId = " + sudId + " key = " + key);
        boolean carrierCofValue = false;
        CarrierConfigManager carrierConfig = (CarrierConfigManager) mContext.getSystemService(
                Context.CARRIER_CONFIG_SERVICE);

        if (carrierConfig != null) {
            PersistableBundle config = carrierConfig.getConfigForSubId(sudId);
            if (config != null) {
                carrierCofValue = config.getBoolean(key, false);
            }
        }
        return carrierCofValue;
    }


      public void onImsErrCauseInfoChange(ImsErrorCauseInfo imsErrorCauseInfo) {

        if (getCarrierCofValueByKey(mPhone.getSubId(),
                    CarrierConfigManagerEx.KEY_CARRIER_SUPPORT_DISABLE_UT_BY_NETWORK)) {
            if (imsErrorCauseInfo.type == IMS_ERROR_CAUSE_TYPE_IMSREG_FAILED &&
                    imsErrorCauseInfo.errCode == IMS_ERROR_CAUSE_ERRCODE_REG_FORBIDDED) {
                setUtDisableByNetWork(true);
                Log.d(TAG, "onImsCsfbReasonInfoChange = " + mIsUtDisableByNetWork);
                if (mImsService != null) {
                    mImsService.updateImsFeature(mServiceId);
                }
            }
        }

        // add for Bug 1071722
        if (DO_NOT_SUPPORT_IMS_DESCRIPTION.equals(imsErrorCauseInfo.errDescription)) {
            log("errDescription is HTTP/1.1 403, setUtDisableByNetWork true.");
            setUtDisableByNetWork(true);
            if (mImsService != null) {
                mImsService.updateImsFeature(mServiceId);
            }
        }

        if (imsErrorCauseInfo.type == IMS_ERROR_CAUSE_TYPE_SS_FAILED) {
            mImsRadioInterface.getSpecialRatcap(mImsHandler.obtainMessage(EVENT_GET_RAT_CAP_RESULT), ImsRadioInterface.GET_RAT_CAP_RESULT);
        }
        log("onImsCsfbReasonInfoChange: info:" + imsErrorCauseInfo.type + " errCode:"
                + imsErrorCauseInfo.errCode + " errDescription: " + imsErrorCauseInfo.errDescription);
    }

    public int getNetworkRATPrefer() {
        return mNetworkRATPrefer;
    }

    public boolean isUtEnable() {
        boolean isUtEnable = true;
        //UNISOC: add for bug1016166
        if (mIsUtDisableByNetWork) {
            log("isUtEnable mIsUtDisableByNetWork = " + mIsUtDisableByNetWork);
            return false;
        }
        if (mNetworkRATPrefer == NETWORK_RAT_PS_PREFER || mNetworkRATPrefer == NETWORK_RAT_PS_ONLY) {
            isUtEnable = true;
        } else if (mNetworkRATPrefer == NETWORK_RAT_PS_BY_IMS_STATUS) {
            isUtEnable = isImsEnabled();
        } else {
            isUtEnable = false;
        }
        return isUtEnable;
    }

    public void notifyImsPdnStateChange(int state){
        synchronized (mImsPdnStateListeners) {
            for (IImsPdnStateListener l : mImsPdnStateListeners.values()) {
                try{
                    l.imsPdnStateChange(state);
                } catch(RemoteException e){
                    e.printStackTrace();
                    continue;
                }
            }
        }
    }

    public void enableWiFiParamReport(){
        mImsRadioInterface.enableWiFiParamReport(true,null);
    }

    public void disableWiFiParamReport(){
        mImsRadioInterface.enableWiFiParamReport(false, null);
    }

    // SPRD: 730973
    public boolean getVolteRegisterStateOld(){
        return mVolteRegisterStateOld;
    }

    public void setVolteRegisterStateOld(boolean state){
        mVolteRegisterStateOld = state;
    }

    public void onCallWaitingStatusUpdateForVoWifi(int status){
        mImsService.onCallWaitingStatusUpdateForVoWifi(status);
    }

    public void log(String info){
        Log.i(TAG,"["+mServiceId+"]:" + info);
    }

    // SPRD add for dual LTE
    private int getSubId() {
        int[] subIds = SubscriptionManager.getSubId(mPhone.getPhoneId());
        int subId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;
        if (subIds != null && subIds.length >= 1) {
            subId = subIds[0];
        }
        return subId;
    }

    /* UNISOC: Add for bug950573 @{*/
    /**
     * Used for get IMS feature.
     *
     * @return: ImsConfig.FeatureConstants.FEATURE_TYPE_UNKNOWN = -1;
     *          ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_LTE = 0;
     *          ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_WIFI = 2;
     */
    public int getCurrentImsFeature() {
        return mCurrentImsFeature;
    }
    /**
     * Used for set IMS feature.
     *
     * @param: imsFeature: ImsConfig.FeatureConstants.FEATURE_TYPE_UNKNOWN = -1;
     *                     ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_LTE = 0;
     *                     ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_WIFI = 2;
     */
    public void setCurrentImsFeature(int imsFeature) {
        mCurrentImsFeature = imsFeature;
    }
    /*@}*/

    /* UNISOC: add for bug968317 @{ */
    /**
     * Used for set VoLTE Voice Call Availablity.
     *
     */
    private void setVoLTECallAvailablity() {
        if(currentVoLTESetting != IMS_INVALID_VOLTE_SETTING) {
            VoLTECallAvailSync = VoLTECallAvailSyncStatus.VOLTE_CALL_AVAIL_SYNC_ONGOING;

            log("setVoLTECallAvailablity, currentVoLTESetting = " + currentVoLTESetting);
            mImsRadioInterface.setImsVoiceCallAvailability(currentVoLTESetting , mImsHandler.obtainMessage(EVENT_SET_VOICE_CALL_AVAILABILITY_DONE, currentVoLTESetting));
        }
    }
    /*@}*/

    public void getImsCNIInfo(){
        mImsRadioInterface.getImsCNIInfo(mImsHandler.obtainMessage(EVENT_IMS_GET_IMS_CNI_INFO));
    }

    /* UNISOC: Add for bug1046061 @{*/
    public boolean isRadioAvailableForImsService() {
        boolean radioAvailableForImservice = false;
        if (mPhone != null) {
            ServiceState state = mPhone.getServiceState();
            if (state != null && state.getDataRegState() == ServiceState.STATE_IN_SERVICE
                    && mImsService.isRadioSupportImsService(state.getRilDataRadioTechnology())){
                radioAvailableForImservice = true;
            }
        }
        return radioAvailableForImservice;
    }
    /*@}*/
}
