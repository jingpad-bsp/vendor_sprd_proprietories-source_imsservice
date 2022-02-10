

package com.android.ims.internal;

import android.content.Context;
import android.os.IBinder;
import android.os.Message;
import android.os.PersistableBundle;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.telephony.CarrierConfigManager;
import android.telephony.CarrierConfigManagerEx;
import android.telephony.SubscriptionManager;
import android.os.RemoteException;
import com.android.ims.ImsConfig;
import android.util.Log;


/**
 * @hide
 */
public class ImsManagerEx {

    public static final String EXTRA_IMS_CONFERENCE_REQUEST =
            "android.intent.extra.IMS_CONFERENCE_REQUEST";
    public static final String EXTRA_IMS_CONFERENCE_PARTICIPANTS =
            "android.intent.extra.IMS_CONFERENCE_PARTICIPANTS";
    private static final String TAG = "ImsManagerEx";

    private static final String MODEM_CONFIG_PROP = "persist.vendor.radio.modem.config";
    private static final String LTE_LTE_5M = "TL_LF_TD_W_G,TL_LF_TD_W_G";
    private static final String LTE_LTE_4M = "TL_LF_W_G,TL_LF_W_G";

    public static final String IMS_SERVICE_EX = "ims_ex";
    public static final String IMS_UT_EX = "ims_ut_ex";
    public static final String IMS_DOZE_MANAGER = "ims_doze_manager";

    public static final int IMS_UNREGISTERED = 0;
    public static final int IMS_REGISTERED   = 1;

    public static final int HANDOVER_STARTED   = 0;
    public static final int HANDOVER_COMPLETED = 1;
    public static final int HANDOVER_FAILED    = 2;
    public static final int HANDOVER_CANCELED  = 3;

    public static final int IMS_PDN_ACTIVE_FAILED = 0;
    public static final int IMS_PDN_READY = 1;
    public static final int IMS_PDN_START = 2;

    // -1 indicates a subscriptionProperty value that is never set.
    private static final int SUB_PROPERTY_NOT_INITIALIZED = -1;

    /**
     * Get Binder Object of ImsService
     */
    public static IImsServiceEx getIImsServiceEx(){
        IBinder b = ServiceManager.getService(IMS_SERVICE_EX);
        IImsServiceEx service = null;
        if(b != null){
            service = IImsServiceEx.Stub.asInterface(b);
        }
        return service;
    }

    /**
     * Get Binder Object of ImsUTEx
     */
    public static IImsUtEx getIImsUtEx(){
        IBinder b = ServiceManager.getService(IMS_UT_EX);
        IImsUtEx service = null;
        if(b != null){
            service = IImsUtEx.Stub.asInterface(b);
        }
        return service;
    }

    /**
     * Check Dual VOLTE register state
     */
    public static boolean isDualVoLTERegistered(){
        String mSimConfig = SystemProperties.get("gsm.sys.volte.state");
        if(mSimConfig != null){
            String[] states = mSimConfig.split(",");
            int count = 0;
            for(int i=0;i<states.length;i++){
                if(states[i].equals("1")){
                    count++;
                }
            }
            if(count>=2){
                return true;
            }
        }
        return false;
    }

    /**
     * SPRD: Add for VoLTE
     * Returns the Volte Registration Status
     */
    public static boolean isVoLTERegisteredForPhone(int phoneId) {
        // Add for bug 667040
        if(!SubscriptionManager.isValidPhoneId(phoneId)){
            return false;
        }
        String mSimConfig = SystemProperties.get("gsm.sys.volte.state");
        if(mSimConfig != null){
            return phoneId == 0 ? mSimConfig.startsWith("1")
                    : ( phoneId == 1 ? mSimConfig.endsWith(",1") : false);
        }
        return false;
    }

    //UNISOC: add for bug880865
    /**
     * UNISOC: Add for VoLTE&VoWifi
     * Returns the IMS Registration Status
     */
    public static boolean isImsRegisteredForPhone(int phoneId) {

        boolean isVolteRegistered = false;
        boolean isVoWifiRegistered  = false;

        if(!SubscriptionManager.isValidPhoneId(phoneId)){
            return false;
        }
        String mVolteState = SystemProperties.get("gsm.sys.volte.state");
        if(mVolteState != null){
            isVolteRegistered = ( phoneId == 0 ? mVolteState.startsWith("1")
                                   : ( phoneId == 1 ? mVolteState.endsWith(",1") : false));
        }

        String mVoWifiState = SystemProperties.get("gsm.sys.vowifi.state");
        if(mVoWifiState != null){
            isVoWifiRegistered = ( phoneId == 0 ? mVoWifiState.startsWith("1")
                                   : ( phoneId == 1 ? mVoWifiState.endsWith(",1") : false));
        }

        return (isVolteRegistered || isVoWifiRegistered);
    }

    //UNISOC: add for bug1108786
    /**
     * UNISOC: Add for VoWiFi
     * Returns the VoWiFi Registration Status
     */
    public static boolean isVoWiFiRegisteredForPhone(int phoneId) {
        if(!SubscriptionManager.isValidPhoneId(phoneId)){
            return false;
        }
        String voWifiState = SystemProperties.get("gsm.sys.vowifi.state");
        if(voWifiState != null){
            return phoneId == 0 ? voWifiState.startsWith("1")
                    : ( phoneId == 1 ? voWifiState.endsWith(",1") : false);
        }
        return false;
    }
    /**
     * SPRD: Add for L+L
     * @return true, if modem is L+L.
     */
    public static boolean isDualLteModem() {
        return SystemProperties.get(MODEM_CONFIG_PROP).equals(LTE_LTE_5M)
                || SystemProperties.get(MODEM_CONFIG_PROP).equals(LTE_LTE_4M);
    }

    /**
     * Notify video capability change
     */
    public static  void notifyVideoCapabilityChange(){
        try {
            if (getIImsServiceEx() != null) {
                getIImsServiceEx().notifyVideoCapabilityChange();
            }
        } catch(RemoteException e){
        }
    }

    /**
     * Returns a platform configuration for VoLTE which may override the user setting on a per Slot
     * basis.
     */
    public static boolean isVolteEnabledByPlatform(Context context) {
        if(context == null){
            return false;
        }

        return context.getResources().getBoolean(
                com.android.internal.R.bool.config_device_volte_available)
                && getBooleanCarrierConfig(CarrierConfigManager.KEY_CARRIER_VOLTE_AVAILABLE_BOOL,context)
                && SystemProperties.getBoolean("persist.vendor.sys.volte.enable",false);//modify by bug712261;
    }

    /**
     * Returns a platform configuration for WFC which may override the user
     * setting. Note: WFC presumes that VoLTE is enabled (these are
     * configuration settings which must be done correctly).
     *
     *  Doesn't work for MSIM devices. Use {@link #isWfcEnabledByPlatform()}
     * instead.
     */
    public static boolean isWfcEnabledByPlatform(Context context) {
        if(context == null){
            return false;
        }
        return context.getResources().getBoolean(
                com.android.internal.R.bool.config_device_wfc_ims_available) &&
                getBooleanCarrierConfig(
                        CarrierConfigManager.KEY_CARRIER_WFC_IMS_AVAILABLE_BOOL,context);
    }

    /**
     * Returns the user configuration of WFC setting
     *
     *  Does not support MSIM devices. Please use
     * {@link #isWfcEnabledByUser()} instead.
     */
    public static boolean isWfcEnabledByUser(Context context) {

        int setting = SubscriptionManager.getIntegerSubscriptionProperty(
                SubscriptionManager.getDefaultDataSubscriptionId(), SubscriptionManager.WFC_IMS_ENABLED,
                SUB_PROPERTY_NOT_INITIALIZED, context);

        // SUB_PROPERTY_NOT_INITIALIZED indicates it's never set in sub db.
        if (setting == SUB_PROPERTY_NOT_INITIALIZED) {
            return getBooleanCarrierConfig(
                    CarrierConfigManager.KEY_CARRIER_DEFAULT_WFC_IMS_ENABLED_BOOL,context);
        } else {
            return setting == ImsConfig.FeatureValueConstants.ON;
        }
    }

    /**
     * Change persistent WFC enabled setting.
     * Does not support MSIM devices. Please use
     * {@link #setWfcSetting} instead.
     */
    public static void setWfcSetting(Context context, boolean enabled) {
        if(context == null){
            return;
        }
        /*UNISOC:modify for IMS {*/
        android.provider.Settings.Global.putInt(context.getContentResolver(),
                android.provider.Settings.Global.WFC_IMS_ENABLED,
                enabled ? ImsConfig.FeatureValueConstants.ON : ImsConfig.FeatureValueConstants.OFF);
         /*@}*/
        SubscriptionManager.setSubscriptionProperty(SubscriptionManager.getDefaultDataSubscriptionId(),
                SubscriptionManager.WFC_IMS_ENABLED, booleanToPropertyString(enabled));
    }


    /**
     * Get the boolean config from carrier config manager.
     *
     * @param key config key defined in CarrierConfigManager
     * @return boolean value of corresponding key.
     */
    private static boolean getBooleanCarrierConfig(String key,Context context) {
        int subId = SubscriptionManager.getDefaultDataSubscriptionId();
        CarrierConfigManager configManager = (CarrierConfigManager) context.getSystemService(
                Context.CARRIER_CONFIG_SERVICE);

        PersistableBundle b = null;
        if (configManager != null) {
            // If an invalid subId is used, this bundle will contain default values.
            b = configManager.getConfigForSubId(subId);
        }
        if (b != null) {
            return b.getBoolean(key);
        } else {
            // Return static default defined in CarrierConfigManager.
            return CarrierConfigManager.getDefaultConfig().getBoolean(key);
        }
    }


    /**
     * Returns the user configuration of Enhanced 4G LTE Mode setting for slot. If the option is
     * not editable ({@link CarrierConfigManager#KEY_EDITABLE_ENHANCED_4G_LTE_BOOL} is false), or
     * the setting is not initialized, this method will return default value specified by
     * {@link CarrierConfigManager#KEY_ENHANCED_4G_LTE_ON_BY_DEFAULT_BOOL}.
     *
     * Note that even if the setting was set, it may no longer be editable. If this is the case we
     * return the default value.
     */
    public static boolean isEnhanced4gLteModeSettingEnabledByUser(Context context) {
        if(context == null){
            return false;
        }
        int setting = SubscriptionManager.getIntegerSubscriptionProperty(
                SubscriptionManager.getDefaultDataSubscriptionId(), SubscriptionManager.ENHANCED_4G_MODE_ENABLED,
                SUB_PROPERTY_NOT_INITIALIZED, context);
        boolean onByDefault = getBooleanCarrierConfig(
                CarrierConfigManager.KEY_ENHANCED_4G_LTE_ON_BY_DEFAULT_BOOL, context);

        // If Enhanced 4G LTE Mode is uneditable or not initialized, we use the default value
        if (!getBooleanCarrierConfig(CarrierConfigManager.KEY_EDITABLE_ENHANCED_4G_LTE_BOOL, context)
                || setting == SUB_PROPERTY_NOT_INITIALIZED) {
            return onByDefault;
        } else {
            return (setting == ImsConfig.FeatureValueConstants.ON);
        }
    }


    private static String booleanToPropertyString(boolean bool) {
        return bool ? "1" : "0";
    }

    /* SPRD: add for bug977043 @{ */
    public static boolean synSettingForWFCandVoLTE(Context context) {
        Log.d(TAG, "synSettingForWFCandVoLTE =  " + getBooleanCarrierConfig(
                CarrierConfigManagerEx.KEY_SYNCHRONOUS_SETTING_FOR_WFC_VOLTE, context));
        return getBooleanCarrierConfig(
                CarrierConfigManagerEx.KEY_SYNCHRONOUS_SETTING_FOR_WFC_VOLTE, context);
    }
    /* @} */
}
