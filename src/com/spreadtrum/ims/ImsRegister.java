package com.spreadtrum.ims;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.os.Message;
import android.os.Handler;
import android.os.SystemProperties;

import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneFactory;
import com.android.internal.telephony.GsmCdmaPhone;
import android.telephony.RadioAccessFamily;
import android.telephony.TelephonyManager;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.telephony.uicc.UiccCardApplication;
import com.android.internal.telephony.uicc.UiccController;
import com.android.internal.telephony.uicc.IccRecords;
import com.android.internal.telephony.uicc.IccRefreshResponse;
import com.android.internal.telephony.uicc.IsimUiccRecords;
import android.os.AsyncResult;
import android.os.Looper;
import android.util.Log;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.IccCardConstants;
import com.android.internal.telephony.CommandException;
import android.telephony.SubscriptionManager;
import android.provider.Settings;
import android.content.res.Resources;
import android.text.TextUtils;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import android.os.Environment;
import android.util.Xml;
import com.android.internal.util.XmlUtils;
import android.telephony.ServiceState;
import com.android.ims.internal.ImsManagerEx;
import com.android.ims.ImsManager;

public class ImsRegister {
    private static final String TAG = "ImsRegister";
    private static final boolean DBG = true;

    private Context mContext;
    private ImsRadioInterface mCi;
    private GsmCdmaPhone mPhone;
    private int mPhoneCount;
    private ImsService mImsService;

    private boolean mInitISIMDone;
    public boolean mSimChanged;
    private boolean mIMSBearerEstablished;
    private boolean mSIMLoaded;
    private TelephonyManager mTelephonyManager;

    private int mPhoneId;
    private BaseHandler mHandler;
    private boolean mCurrentImsRegistered;
    private UiccController mUiccController;
    private IccRecords mIccRecords = null;
    private UiccCardApplication mUiccApplcation = null;
    private String mNumeric;
    private String mLastNumeric="";
    private int mRetryCount = 0;
    private static final int DEFAULT_PHONE_ID   = 0;
    private static final int SLOTTWO_PHONE_ID   = 1;

    static final String PARTNER_SPN_OVERRIDE_PATH ="etc/spn-conf.xml";
    static final String OEM_SPN_OVERRIDE_PATH = "telephony/spn-conf.xml";
    public static final String PROP_VOLTE_ALLOWED_PLMN = "gsm.sys.sim.volte.allowedplmn"; // UNISOC: Add for bug1168347


    private static final int EVENT_ICC_CHANGED                       = 201;
    private static final int EVENT_RECORDS_LOADED                    = 202;
    private static final int EVENT_RADIO_STATE_CHANGED               = 203;
    private static final int EVENT_INIT_ISIM_DONE                    = 204;
    private static final int EVENT_IMS_BEARER_ESTABLISTED            = 205;
    private static final int EVENT_ENABLE_IMS                        = 206;
    private static final int EVENT_RADIO_CAPABILITY_CHANGED          = 207;
    //SPRD: Add for Bug 634502
    private static final int EVENT_SIM_REFRESH                       = 208;
    // UNISOC: Add for bug1168347
    private static final int EVENT_GET_VOLTE_ALLOWED_PLMN            = 209;
    public ImsRegister(GsmCdmaPhone phone , Context context, ImsRadioInterface ci) {
        mPhone = phone;
        mContext = context;
        mImsService = (ImsService)context;
        mCi = ci;
        mTelephonyManager = TelephonyManager.from(mContext);
        mPhoneId = mPhone.getPhoneId();
        mPhoneCount = mTelephonyManager.getPhoneCount();
        mHandler = new BaseHandler(mContext.getMainLooper());
        mUiccController = UiccController.getInstance();
        mUiccController.registerForIccChanged(mHandler, EVENT_ICC_CHANGED, null);
        mCi.registerForRadioStateChanged(mHandler, EVENT_RADIO_STATE_CHANGED, null);
        mCi.registerForImsBearerStateChanged(mHandler, EVENT_IMS_BEARER_ESTABLISTED, null);
        mCi.getImsBearerState(mHandler.obtainMessage(EVENT_IMS_BEARER_ESTABLISTED));
        mCi.registerForIccRefresh(mHandler, EVENT_SIM_REFRESH, null);
        mPhone.registerForRadioCapabilityChanged(mHandler, EVENT_RADIO_CAPABILITY_CHANGED, null);
    }

    private class BaseHandler extends Handler {
        BaseHandler(Looper looper) {
            super(looper);
        }
        @Override
        public void handleMessage(Message msg) {
           log("handleMessage msg=" + msg);
            AsyncResult ar = (AsyncResult) msg.obj;
            switch (msg.what) {
            case EVENT_ICC_CHANGED:
                onUpdateIccAvailability();
                break;
            case EVENT_RECORDS_LOADED:
                log("EVENT_RECORDS_LOADED");
                mSIMLoaded = true;
                initISIM();
                break;
            case EVENT_RADIO_STATE_CHANGED:
                if (!mPhone.isRadioOn()) {
                    mInitISIMDone = false;
                    TelephonyManager.setTelephonyProperty(mPhoneId, PROP_VOLTE_ALLOWED_PLMN, "0"); // UNISOC: Add for bug1168347
                    //add for L+G dual volte, if secondary card no need to reset mIMSBearerEstablished
                    // add for Dual LTE
                    if (getLTECapabilityForPhone()) {
                        mIMSBearerEstablished = false;
                    }

                    mLastNumeric="";
                    mCurrentImsRegistered = false;
                } else {
                    log("EVENT_RADIO_STATE_CHANGED -> radio is on");
                    initISIM();
                    SetUserAgent();//SPRD:add for user agent future 670075
                }
                break;
            case EVENT_INIT_ISIM_DONE:
                log("EVENT_INIT_ISIM_DONE");
                ar = (AsyncResult) msg.obj;
                if(ar == null) { // UNISOC: Add for bug1188903
                    Log.e(TAG, "EVENT_INIT_ISIM_DONE: ar == null");
                    break;
                }
                if(ar.exception != null) {
                    log("EVENT_INIT_ISIM_DONE ar.exception");
                    break;
                }
                mInitISIMDone = true;
                // UNISOC: Add for bug1168347
                mCi.getVoLTEAllowedPLMN(mHandler.obtainMessage(EVENT_GET_VOLTE_ALLOWED_PLMN));
                // add for Dual LTE
                if (mImsService.allowEnableIms(mPhoneId)) {
                    enableIms();
                }
                break;
            case EVENT_IMS_BEARER_ESTABLISTED:
                ar = (AsyncResult) msg.obj;
                if(ar == null) { // UNISOC: Add for bug1188903
                    Log.e(TAG, "EVENT_IMS_BEARER_ESTABLISTED: ar == null");
                    break;
                }
                if(ar.exception != null || ar.result == null) {
                    log("EVENT_IMS_BEARER_ESTABLISTED : ar.exception = "+ar.exception);
                    CommandException.Error err=null;
                    if (ar.exception != null && (ar.exception instanceof CommandException)) {
                        err = ((CommandException)(ar.exception)).getCommandError();
                    }
                    if (err == CommandException.Error.RADIO_NOT_AVAILABLE) {
                        if (mRetryCount < 8) {
                            mCi.getImsBearerState(mHandler.obtainMessage(EVENT_IMS_BEARER_ESTABLISTED));
                            mRetryCount++;
                        }
                    }
                    break;
                }
                /**
                 * 772714 should adapter int[] status.
                 * when use mCi.getImsBearerState, it will return int[] but not Integer
                 */
                try {
                    Integer conn = new Integer(-1);
                    if (ar.result instanceof Integer) {
                        conn = (Integer) ar.result;
                    } else if (ar.result instanceof int[] && ((int[]) ar.result).length > 0) { // UNISOC: Modify for bug1188903
                        int[] connArray = (int[]) ar.result;
                        conn = connArray[0];
                    }
                    log("EVENT_IMS_BEARER_ESTABLISTED : conn = " + conn);
                    if (conn.intValue() == 1) {
                        mIMSBearerEstablished = true;
                        mLastNumeric = "";
                    } else // add for L+G dual volte, if secondary card no need
                           // to reset mIMSBearerEstablished
                    if (conn.intValue() == 0) {
                        mIMSBearerEstablished = false;
                        log("EVENT_IMS_BEARER_ESTABLISTED : conn.intValue() == 0: clear mIMSBearerEstablished");
                        mLastNumeric = "";
                    }
                } catch (Exception e) {
                    log("EVENT_IMS_BEARER_ESTABLISTED : exception: "
                            + e.getMessage());
                    e.printStackTrace();
                }
               break;
            case EVENT_ENABLE_IMS:
                log("EVENT_ENABLE_IMS");
                mNumeric = mTelephonyManager.getNetworkOperatorForPhone(mPhoneId);
                // add for 796527
                log("current mNumeric = "+mNumeric);
                // add for Dual LTE
                if (getLTECapabilityForPhone()) {
                    log("PrimaryCard : mLastNumeric = "+mLastNumeric);
                    if(!(mLastNumeric.equals(mNumeric))) {
                        if(mImsService.allowEnableIms(mPhoneId)){
                              mSimChanged = false;
                              mCi.enableIms(true, null);
                              mLastNumeric = mNumeric;
                        }
                    }
                }
                break;
            case EVENT_RADIO_CAPABILITY_CHANGED:
                // add for Dual LTE
                if (!getLTECapabilityForPhone()) {
                    mInitISIMDone = false;
                    TelephonyManager.setTelephonyProperty(mPhoneId, PROP_VOLTE_ALLOWED_PLMN, "0"); // UNISOC: Add for bug1168347
                    mIMSBearerEstablished = false;
                    mLastNumeric = "";
                    mCurrentImsRegistered = false;
                } else {
                    log("EVENT_RADIO_CAPABILITY_CHANGED -> initisim");
                    initISIM();
                }
                break;
                /* SPRD: Add for Bug 634502 Need to init ISIM after uicc has been initialized @{ */
            case EVENT_SIM_REFRESH:
                log("EVENT_SIM_REFRESH");
                ar = (AsyncResult)msg.obj;
                if(ar == null) { // UNISOC: Add for bug1188903
                    Log.e(TAG, "EVENT_SIM_REFRESH: ar == null");
                    break;
                }
                if (ar.exception == null) {
                    IccRefreshResponse resp = (IccRefreshResponse)ar.result;
                    if(resp!= null && resp.refreshResult == IccRefreshResponse.REFRESH_RESULT_INIT){//uicc init
                        log("Uicc initialized, need to init ISIM again.");
                        mInitISIMDone = false;
                        TelephonyManager.setTelephonyProperty(mPhoneId, PROP_VOLTE_ALLOWED_PLMN, "0"); // UNISOC: Add for bug1168347
                        mLastNumeric="";
                    }
                } else {
                    log("Sim REFRESH with exception: " + ar.exception);
                }
                break;
                /* @} */
                /* UNISOC: Add for bug1168347 @{*/
            case EVENT_GET_VOLTE_ALLOWED_PLMN:
                log("EVENT_GET_VOLTE_ALLOWED_PLMN");
                ar = (AsyncResult) msg.obj;
                if (ar == null) { // UNISOC: Add for bug1188903
                    Log.e(TAG, "EVENT_GET_VOLTE_ALLOWED_PLMN: ar == null");
                    break;
                }
                if(ar.exception != null || ar.result == null) {
                    log("EVENT_GET_VOLTE_ALLOWED_PLMN : ar.exception = " + ar.exception);
                    break;
                }
                /* UNISOC: Add for bug1188903 @{*/
                int volteEnable = 0;
                if (ar.result instanceof int[] && ((int[]) ar.result).length > 0) {
                    volteEnable = (((int[]) ar.result))[0];
                }
                /* @} */
                TelephonyManager.setTelephonyProperty(mPhoneId, PROP_VOLTE_ALLOWED_PLMN, volteEnable == 1? "1":"0");
                /* @} */
            default:
                break;
            }
        }
    };

    private void initISIM() {
        log("nitISIM() : mSIMLoaded = " + mSIMLoaded
                + " | mPhone.isRadioOn() = " + mPhone.isRadioOn()
                + " | mTelephonyManager.getSimState(mPhoneId) = "
                + mTelephonyManager.getSimState(mPhoneId) + " | mPhoneId = "
                + mPhoneId
                + " | getLTECapabilityForPhone() = "
                + getLTECapabilityForPhone());
        if (mSIMLoaded && mPhone.isRadioOn() && !mInitISIMDone
                && mTelephonyManager.getSimState(mPhoneId) == TelephonyManager.SIM_STATE_READY
                     // add for Dual LTE
                && getLTECapabilityForPhone()) {
            mCi.initISIM(mHandler.obtainMessage(EVENT_INIT_ISIM_DONE));
        }
    }

    public void notifyImsStateChanged(boolean imsRegistered) {
        log("--notifyImsStateChanged : imsRegistered = " + imsRegistered + " | mCurrentImsRegistered = " + mCurrentImsRegistered);
        if( mCurrentImsRegistered != imsRegistered) {
            mCurrentImsRegistered = imsRegistered;
            /**
             * SPRD bug644157 should limit action to primary card
             * so remove if(){}
             */
//            if( mPhoneId == getPrimaryCard()) {
            if (mPhone.isRadioOn()
                    && getServiceState().getState() != ServiceState.STATE_IN_SERVICE) {
                log("voice regstate not in service, not call ImsNotifier to notifyServiceStateChanged");
                //mPhone.notifyServiceStateChanged(getServiceState()); // UNISOC: Add for bug1087243
            }
//            }
        }
    }

    private int getPrimaryCard() {
        log("-getPrimaryCard() mPhoneCount = " + mPhoneCount);
        if (mPhoneCount == 1) {
            return DEFAULT_PHONE_ID;
        }
        int primaryCard = SubscriptionManager
                .getSlotIndex(SubscriptionManager.getDefaultDataSubscriptionId());
        if (primaryCard == -1) {
            return DEFAULT_PHONE_ID;
        }
        return primaryCard;
    }

    public static int getPrimaryCard(int phoneCount) {
        // SPRD add for dual LTE
        Log.d(TAG, "-getPrimaryCard(int phoneCount) phoneCount = " + phoneCount);
        if (phoneCount == 1) {
            return DEFAULT_PHONE_ID;
        }
        int primaryCard = SubscriptionManager
                .getSlotIndex(SubscriptionManager.getDefaultDataSubscriptionId());
        if (primaryCard == -1) {
            return DEFAULT_PHONE_ID;
        }
        return primaryCard;
    }

    private static int getPrimaryCardFromProp(int[] workMode) {
        switch (workMode[DEFAULT_PHONE_ID]) {
        case 10:
            if(workMode[SLOTTWO_PHONE_ID] != 10 && workMode[SLOTTWO_PHONE_ID] != 254) {
                return SLOTTWO_PHONE_ID;
            }
            break;
        case 254:
            if(workMode[SLOTTWO_PHONE_ID] != 254) {
                return SLOTTWO_PHONE_ID;
            }
            break;
        // L+W mode SRPD: 675103 721982
        case 255:
            Log.d(TAG,"-getPrimaryCardFromProp() workMode[2] = " + workMode[SLOTTWO_PHONE_ID]);
            if (workMode[SLOTTWO_PHONE_ID] == 9
                || workMode[SLOTTWO_PHONE_ID] == 6
                || workMode[SLOTTWO_PHONE_ID] == 7
                || workMode[SLOTTWO_PHONE_ID] == 20) {

                return SLOTTWO_PHONE_ID;
            }
            break;
        }
        return DEFAULT_PHONE_ID;
    }

    private void onUpdateIccAvailability() {
        if (mUiccController == null ) {
            return;
        }

        UiccCardApplication newUiccApplication = getUiccCardApplication();

        if (mUiccApplcation != newUiccApplication) {
            if (mUiccApplcation != null) {
                log("Removing stale icc objects.");
                if (mIccRecords != null) {
                    mIccRecords.unregisterForRecordsLoaded(mHandler);
                }
                mIccRecords = null;
                mUiccApplcation = null;
                mInitISIMDone = false;
                mSIMLoaded    = false;
                TelephonyManager.setTelephonyProperty(mPhoneId, PROP_VOLTE_ALLOWED_PLMN, "0"); // UNISOC: Add for bug1168347
            }
            if (newUiccApplication != null) {
                log("New card found");
                mSimChanged = true;
                mUiccApplcation = newUiccApplication;
                mIccRecords = mUiccApplcation.getIccRecords();
                if (mIccRecords != null) {
                    mIccRecords.registerForRecordsLoaded(mHandler, EVENT_RECORDS_LOADED, null);
                }
            }
        }
    }

    private UiccCardApplication getUiccCardApplication() {
        return mUiccController.getUiccCardApplication(mPhoneId,
                UiccController.APP_FAM_3GPP);
    }

    private void log(String s) {
        if (DBG) {
            Log.d(TAG, "[ImsRegister" + mPhoneId + "] " + s);
        }
    }

    public void enableIms() {
        log("enableIms ->mIMSBearerEstablished:" + mIMSBearerEstablished + " mInitISIMDone:" + mInitISIMDone);
        if(!ImsConfigImpl.isVolteEnabledBySystemProperties()){
            log("enableIms ->ImsConfigImpl.isVolteEnabledBySystemProperties():" + ImsConfigImpl.isVolteEnabledBySystemProperties());
            return;
        }
        if(mIMSBearerEstablished && mInitISIMDone) {
            mHandler.sendMessage(mHandler.obtainMessage(EVENT_ENABLE_IMS));
        }
    }

    private ServiceState getServiceState() {
        if (mPhone.getServiceStateTracker() != null) {
            return mPhone.getServiceStateTracker().mSS;
        } else {
            return new ServiceState();
        }
    }

    public void onImsPDNReady(){
        mIMSBearerEstablished = true;
    }

    private boolean isRelianceCard(String operatorName){
        if(operatorName != null &&(operatorName.equalsIgnoreCase("Reliance")
                || operatorName.equalsIgnoreCase("Jio"))){
            return true;
        }
        return false;
    }

    private boolean isCmccCard(String operatorName){
        if(operatorName != null &&operatorName.equalsIgnoreCase("China Mobile")){
            return true;
        }
        return false;
    }

    /**
     * SPRD:add for user agent future
     * userAgent: deviceName_SW version
     ***/
    private void SetUserAgent() {
        String userAgent = SystemProperties.get("ro.config.useragent", "SPRD VOLTE");
        if ("SPRD VOLTE".equals(userAgent)) {
            return;
        }
        String[] cmd = new String[1];
        cmd[0] = "AT+SPENGMDVOLTE=22,1," + "\"" + userAgent + "\"";
        log("SetUserAgent :" + cmd[0]);
    }/* @} */

    public boolean getLTECapabilityForPhone(){
        int rafMax = mPhone.getRadioAccessFamily();
        return (rafMax & RadioAccessFamily.RAF_LTE) == RadioAccessFamily.RAF_LTE;
    }
}
