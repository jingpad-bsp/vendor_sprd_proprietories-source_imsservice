package com.spreadtrum.ims;

import java.util.concurrent.ConcurrentHashMap;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.util.Log;
import com.android.ims.ImsConfigListener;
import android.telephony.ims.aidl.IImsConfig;
import android.telephony.ims.aidl.IImsConfigCallback;
import com.android.ims.ImsConfig;
import com.android.internal.telephony.CommandsInterface;
import android.telephony.TelephonyManager;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;

public class ImsConfigImpl extends IImsConfig.Stub {

    private static final String TAG = ImsConfigImpl.class.getSimpleName();
    public static final String VT_RESOLUTION_VALUE = "vt_resolution";

    private static final int ACTION_GET_VT_RESOLUTION = 101;
    private static final int ACTION_SET_VT_RESOLUTION = 102;
    private static final int ACTION_GET_IMS_CALL_AVAILABILITY = 103;
    private static final int ACTION_SET_IMS_CALL_AVAILABILITY = 104;
    private static final int EVENT_VOLTE_CALL_DEFINED_MEDIA_TYPE = 105;
    private static final int ACTION_SET_SMS_OVER_IP_AVAILABILITY = 106;
    private static final int EVENT_SET_SMS_OVER_IP_DONE = 107;

    public static final int VT_RESOLUTION_720P = 0;                //1280*720 Frame rate:30
    public static final int VT_RESOLUTION_VGA_REVERSED_15 = 1;     //480*640 Frame rate:15
    public static final int VT_RESOLUTION_VGA_REVERSED_30 = 2;     //480*640 Frame rate:30
    public static final int VT_RESOLUTION_QVGA_REVERSED_15 = 3;    //240*320 Frame rate:15
    public static final int VT_RESOLUTION_QVGA_REVERSED_30 = 4;    //240*320 Frame rate:30
    public static final int VT_RESOLUTION_CIF = 5;                 //352*288 Frame rate:30
    public static final int VT_RESOLUTION_QCIF = 6;                //176*144 Frame rate:30
    public static final int VT_RESOLUTION_VGA_15 = 7;              //640*480 Frame rate:15
    public static final int VT_RESOLUTION_VGA_30 = 8;              //640*480 Frame rate:30
    public static final int VT_RESOLUTION_QVGA_15 = 9;             //320*240 Frame rate:15
    public static final int VT_RESOLUTION_QVGA_30 = 10;            //320*240 Frame rate:30

    public static class VideoQualityConstants {
        public static final int FEATURE_VT_RESOLUTION = 50;
        public static final int NETWORK_VT_RESOLUTION = 51;
    }

    private ImsRadioInterface mCi;
    private ImsHandler mHandler;
    private Context mContext;
    private SharedPreferences mSharedPreferences;
    private static final String VIDEO_CALL_RESOLUTION = "vt_resolution";
    private int mCameraResolution = VT_RESOLUTION_VGA_REVERSED_30;
    public int mDefaultVtResolution = VT_RESOLUTION_VGA_REVERSED_30;
    private static final String SMS_OVER_IP = "sms_over_ip";
    private int mSmsOverIp = ImsConfig.FeatureValueConstants.ON;
    public int mDefaultSmsOverIp = ImsConfig.FeatureValueConstants.ON;
    private ImsServiceImpl mImsServiceImpl = null;
    private int mImsServiceId;  // SPRD: bug805154

    /**
     * AndroidP start@{:
     */
    private ConcurrentHashMap<IBinder, IImsConfigCallback> mIImsConfigCallbacks = new ConcurrentHashMap<IBinder, IImsConfigCallback>();

    @Override
    public synchronized void addImsConfigCallback(IImsConfigCallback c){  //Unisoc: change for bug 1201922,CID:231233
        if (c == null) {
            Log.w(TAG,"addImsConfigCallback->Listener is null!");
            Thread.dumpStack();
            return;
        }

        if (!mIImsConfigCallbacks.keySet().contains(c.asBinder())) {
            mIImsConfigCallbacks.put(c.asBinder(), c);
        } else {
            Log.w(TAG,"addImsConfigCallback Listener already add :" + c);
        }
    }

    @Override
    public synchronized void removeImsConfigCallback(IImsConfigCallback c){  //Unisoc: change for bug 1201922,CID:230880
        if (c == null) {
            Log.w(TAG,"removeImsConfigCallback->Listener is null!");
            Thread.dumpStack();
            return;
        }
        if (mIImsConfigCallbacks.keySet().contains(c.asBinder())) {
            mIImsConfigCallbacks.remove(c.asBinder());
        } else {
            Log.w(TAG,"removeImsConfigCallback already remove :" + c);
        }
    }

    public synchronized void notifyIntConfigChanged(int item, int value){  //Unisoc: change for bug 1201922,CID:228624
        for (IImsConfigCallback l : mIImsConfigCallbacks.values()) {
            try{
                l.onIntConfigChanged(item,value);
            } catch(RemoteException e){
                e.printStackTrace();
                continue;
            }
        }
    }

    public synchronized void notifyStringConfigChanged(int item, String value){  //Unisoc: change for bug 1201922,CID:232823
        for (IImsConfigCallback l : mIImsConfigCallbacks.values()) {
            try{
                l.onStringConfigChanged(item,value);
            } catch(RemoteException e){
                e.printStackTrace();
                continue;
            }
        }
    }

    @Override
    public int getConfigInt(int item){
        if(item == ImsConfig.ConfigConstants.VIDEO_QUALITY) {
            int quality = getVideoQualityFromPreference();
            Log.d(TAG, "getVideoQuality qualiy = " + quality);
            return quality;
        } else if (item == ImsConfig.ConfigConstants.SMS_OVER_IP) {
            int type = getSmsOverIp();
            Log.d(TAG, "getSmsOverIp type = " + type);
            return type;
        }
        return ImsConfig.OperationStatusConstants.UNKNOWN;
    }

    @Override
    public String getConfigString(int item){
        return null;
    }

    // Return result code defined in ImsConfig#OperationStatusConstants
    @Override
    public int setConfigInt(int item, int value){
        if(item == ImsConfig.ConfigConstants.VIDEO_QUALITY) {
            Log.d(TAG, "setVideoQuality from screen qualiy = " + value);
            setVideoQualityPreference(value);

            return ImsConfig.OperationStatusConstants.SUCCESS;
        } else if (item == ImsConfig.ConfigConstants.SMS_OVER_IP){
            Log.d(TAG, "setSmsOverIp set value = " + value);
            setSmsOverIp(value);
            return ImsConfig.OperationStatusConstants.SUCCESS;
        }
        return ImsConfig.OperationStatusConstants.UNKNOWN;
    }

    // Return result code defined in ImsConfig#OperationStatusConstants
    @Override
    public int setConfigString(int item, String value){
        return ImsConfig.OperationStatusConstants.UNKNOWN;
    }
    /* AndroidP end@} */

    /**
     * Creates the Ims Config interface object for a sub.
     * @param senderRxr
     */
    public ImsConfigImpl(ImsRadioInterface ci,Context context,ImsServiceImpl imsService, int imsserviceid) {
        if (SystemProperties.getInt("persist.sys.ims.vt_resolution", -1) == VT_RESOLUTION_QVGA_REVERSED_15) {
            mDefaultVtResolution = VT_RESOLUTION_QVGA_REVERSED_15;
        }
        mCi = ci;
        mImsServiceImpl = imsService;
        mHandler = new ImsHandler(context.getMainLooper());
        mContext = context;
        mSharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        mSharedPreferences.registerOnSharedPreferenceChangeListener(mSharedPreferenceListener);
        // SPRD 864003
        mCameraResolution = mSharedPreferences.getInt(VIDEO_CALL_RESOLUTION+imsserviceid, mDefaultVtResolution);
        mSmsOverIp = mSharedPreferences.getInt(SMS_OVER_IP + imsserviceid, mDefaultSmsOverIp);
        mImsServiceId = imsserviceid; // SPRD: bug805154
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
            Log.d(TAG, "handleMessage what:" + msg.what +" msg.obj:"+msg.obj);
            switch (msg.what) {
                case ACTION_GET_VT_RESOLUTION:
                    try {
                        ImsConfigListener imsConfigListener = (ImsConfigListener)msg.obj;
                        int status = ImsConfig.OperationStatusConstants.SUCCESS;
                        int result = msg.arg1;
                        Log.i(TAG, "ACTION_GET_VT_RESOLUTION->status:"+status+" result:"+result);
                        if(imsConfigListener != null){
                            imsConfigListener.onGetVideoQuality(status,result);
                        } else {
                            Log.w(TAG, "ACTION_GET_VT_RESOLUTION->imsConfigListener is null!");
                        }
                    } catch(RemoteException e){
                        e.printStackTrace();
                    }
                    break;
                case ACTION_SET_VT_RESOLUTION:
                    try {
                        ImsConfigListener imsConfigListener = (ImsConfigListener)msg.obj;
                        if(imsConfigListener != null){
                            imsConfigListener.onSetVideoQuality(ImsConfig.OperationStatusConstants.SUCCESS);
                        } else {
                            Log.w(TAG, "ACTION_GET_VT_RESOLUTION->imsConfigListener is null!");
                        }
                    } catch(RemoteException e){
                        e.printStackTrace();
                    }
                    break;
                case ACTION_GET_IMS_CALL_AVAILABILITY:
                    try {
                        AsyncResult ar = (AsyncResult) msg.obj;
                        if(ar != null){
                            ImsConfigListener imsConfigListener = (ImsConfigListener)ar.userObj;
                            int status = -1;
                            int result = -1;
                            if(ar.exception != null || ar.userObj instanceof Throwable){
                                status = ImsConfig.OperationStatusConstants.FAILED;
                            } else {
                                status = ImsConfig.OperationStatusConstants.SUCCESS;
                                int[] results = (int[])ar.result;
                                if(results != null && results.length > 0){ //Unisoc: change for Bug 1188899
                                    result = results[0];
                                }
                            }
                            Log.i(TAG, "ACTION_GET_IMS_CALL_AVAILABILITY->status:"+status+" result:"+result);
                            if(imsConfigListener != null){
                                imsConfigListener.onGetFeatureResponse(ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_LTE,
                                        TelephonyManager.NETWORK_TYPE_LTE, result, status);
                            } else {
                                Log.w(TAG, "ACTION_GET_IMS_CALL_AVAILABILITY->imsConfigListener is null!");
                            }
                        }
                    } catch(RemoteException e){
                        e.printStackTrace();
                    }
                    break;
                case ACTION_SET_IMS_CALL_AVAILABILITY:
                    try {
                        AsyncResult ar = (AsyncResult) msg.obj;
                        if(ar != null){
                            ImsConfigListener imsConfigListener = (ImsConfigListener)ar.userObj;
                            int status = -1;
                            int result = -1;
                            if(ar.exception != null){
                                status = ImsConfig.OperationStatusConstants.FAILED;
                            } else {
                                status = ImsConfig.OperationStatusConstants.SUCCESS;
                            }
                            Log.i(TAG, "ACTION_SET_IMS_CALL_AVAILABILITY->status:"+status+" result:"+result);
                            if(imsConfigListener != null){
                                imsConfigListener.onSetFeatureResponse(ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_LTE,
                                        TelephonyManager.NETWORK_TYPE_LTE, result, status);
                            } else {
                                Log.w(TAG, "ACTION_SET_IMS_CALL_AVAILABILITY->imsConfigListener is null!");
                            }
                        }
                    } catch(RemoteException e){
                        e.printStackTrace();
                    }
                    break;
                case EVENT_VOLTE_CALL_DEFINED_MEDIA_TYPE:
                    int resolution = mCameraResolution;
                    try {
                        int resolutionConfig = SystemProperties
                                .getInt("persist.sys.videotelcel", -1);
                        Log.i(TAG, "EVENT_VOLTE_CALL_DEDINE_MEDIA_TYPE ->resolutionConfig:"
                                        + resolutionConfig);
                        if (resolutionConfig != -1) {
                            mCameraResolution = resolutionConfig;
                            resolution = resolutionConfig;
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "EVENT_VOLTE_CALL_DEDINE_MEDIA_TYPE Exception: "
                                + e.getMessage());
                        e.printStackTrace();
                    }
                    Log.i(TAG, "EVENT_VOLTE_CALL_DEDINE_MEDIA_TYPE ->resolution = " + resolution);
                    mCi.setVideoResolution(resolution,null);
                    break;
                case ACTION_SET_SMS_OVER_IP_AVAILABILITY:
                    Log.i(TAG, "ACTION_SET_SMS_OVER_IP_AVAILABILITY ->mSmsOverIp = " + mSmsOverIp);
                    mCi.setSmsBearer(mSmsOverIp,mHandler.obtainMessage(EVENT_SET_SMS_OVER_IP_DONE));
                    break;
                case EVENT_SET_SMS_OVER_IP_DONE:
                    AsyncResult ar = (AsyncResult) msg.obj;
                    Log.i(TAG, "EVENT_SET_SMS_OVER_IP_DONE -> =ar " + ar);
                    if (ar.exception == null) {
                        mImsServiceImpl.updateImsFeatureForAllService();
                    }
                    break;
                default:
                    Log.e(TAG, "handleMessage: unhandled message");
            }

        }

    };


    /**
     * Gets the value for ims service/capabilities parameters from the provisioned
     * value storage. Synchronous blocking call.
     *
     * @param item, as defined in com.android.ims.ImsConfig#ConfigConstants.
     * @return value in Integer format.
     */
    public int getProvisionedValue(int item){
        return 0;
    }

    /**
     * Gets the value for ims service/capabilities parameters from the provisioned
     * value storage. Synchronous blocking call.
     *
     * @param item, as defined in com.android.ims.ImsConfig#ConfigConstants.
     * @return value in String format.
     */
    public String getProvisionedStringValue(int item){
        return null;
    }

    /**
     * Sets the value for IMS service/capabilities parameters by the operator device
     * management entity. It sets the config item value in the provisioned storage
     * from which the master value is derived. Synchronous blocking call.
     *
     * @param item, as defined in com.android.ims.ImsConfig#ConfigConstants.
     * @param value in Integer format.
     * @return as defined in com.android.ims.ImsConfig#OperationStatusConstants.
     */
    public int setProvisionedValue(int item, int value){
        return 0;
    }

    /**
     * Sets the value for IMS service/capabilities parameters by the operator device
     * management entity. It sets the config item value in the provisioned storage
     * from which the master value is derived.  Synchronous blocking call.
     *
     * @param item, as defined in com.android.ims.ImsConfig#ConfigConstants.
     * @param value in String format.
     * @return as defined in com.android.ims.ImsConfig#OperationStatusConstants.
     */
    public int setProvisionedStringValue(int item, String value){
        return 0;
    }

    /**
     * Gets the value of the specified IMS feature item for specified network type.
     * This operation gets the feature config value from the master storage (i.e. final
     * value). Asynchronous non-blocking call.
     *
     * @param feature. as defined in com.android.ims.ImsConfig#FeatureConstants.
     * @param network. as defined in android.telephony.TelephonyManager#NETWORK_TYPE_XXX.
     * @param listener. feature value returned asynchronously through listener.
     * @return void
     */
    public void getFeatureValue(int feature, int network, ImsConfigListener listener){
        if(feature == VideoQualityConstants.FEATURE_VT_RESOLUTION
                && network == VideoQualityConstants.NETWORK_VT_RESOLUTION){
            if(listener != null){
                getVideoQuality(listener);
            }
        }
    }

    /**
     * Sets the value for IMS feature item for specified network type.
     * This operation stores the user setting in setting db from which master db
     * is dervied.
     *
     * @param feature. as defined in com.android.ims.ImsConfig#FeatureConstants.
     * @param network. as defined in android.telephony.TelephonyManager#NETWORK_TYPE_XXX.
     * @param value. as defined in com.android.ims.ImsConfig#FeatureValueConstants.
     * @param listener, provided if caller needs to be notified for set result.
     * @return void
     */
    public void setFeatureValue(int feature, int network, int value, ImsConfigListener listener){
        Log.d(TAG, "setFeatureValue: feature = " + feature + ", network =" + network +
                ", value =" + value + ", listener =" + listener);
        if(feature == VideoQualityConstants.FEATURE_VT_RESOLUTION
                && network == VideoQualityConstants.NETWORK_VT_RESOLUTION){
            if(listener != null){
                setVideoQuality(value,listener);
            }
        }else if(feature == ImsConfig.FeatureConstants.FEATURE_TYPE_VIDEO_OVER_LTE){//SPRD: add for bug712024
            if(listener != null && mImsServiceImpl != null){
                mImsServiceImpl.updateImsFeatureForAllService();
            }
        }
    }

    /**
     * Gets the value for IMS volte provisioned.
     * This should be the same as the operator provisioned value if applies.
     *
     * @return void
     */
    public boolean getVolteProvisioned(){
        return isVolteEnabledBySystemProperties();
    }


    /**
     *
     * Gets the value for ims fature item video quality.
     *
     * @param listener. Video quality value returned asynchronously through listener.
     * @return void
     */
    public void getVideoQuality(ImsConfigListener imsConfigListener) {
        Log.d(TAG, "  getVideoQuality  String:"+VT_RESOLUTION_VALUE+mImsServiceId); // SPRD: bug805154
        Message m = mHandler.obtainMessage(ACTION_GET_VT_RESOLUTION, getVideoQualityFromPreference(),
                0, imsConfigListener);
        m.sendToTarget();
    }

    /**
     * Sets the value for IMS feature item video quality.
     *
     * @param quality, defines the value of video quality.
     * @param listener, provided if caller needs to be notified for set result.
     * @return void
     *
     * @throws ImsException if calling the IMS service results in an error.
     */
    public void setVideoQuality(int quality, ImsConfigListener imsConfigListener) {
        Log.d(TAG, "setVideoQuality from screen! qualiy = " + quality);
        setVideoQualityPreference(quality);
        Message m = mHandler.obtainMessage(ACTION_SET_VT_RESOLUTION, quality, 0, imsConfigListener);
        m.sendToTarget();
    }

    public void setVideoQualityPreference(int value){
        Editor editor = mSharedPreferences.edit();
        editor.putInt(VT_RESOLUTION_VALUE + mImsServiceId, value);  // SPRD: bug805154
        editor.apply();
    }

    /* UNISOC: add for bug1181272 @{ */
    public void setSmsOverIp(int value){
        Editor editor = mSharedPreferences.edit();
        editor.putInt(SMS_OVER_IP + mImsServiceId, value);
        editor.apply();
    }
    /*@}*/

    //Unisoc: change for bug 1223217
    // SPRD:909828
    public void sendVideoQualitytoIMS(int value) {
            Log.i(TAG, "sendVideoQualitytoIMS : mCameraResolution = " + mCameraResolution
                        + " | mDefaultVtResolution = " + mDefaultVtResolution
                        + " | value = " + value);

            mCameraResolution = value;
            mDefaultVtResolution = value;

            mHandler.removeMessages(EVENT_VOLTE_CALL_DEFINED_MEDIA_TYPE);
            mHandler.sendEmptyMessageDelayed(EVENT_VOLTE_CALL_DEFINED_MEDIA_TYPE, 1000);
    }

    /* UNISOC: add for bug1181272 @{ */
    public void configSmsBearer(int value) {
        int smsBearerByUser = mSharedPreferences.getInt(
                SMS_OVER_IP + mImsServiceId, -1);
        Log.i(TAG, "sendSmsOverIpToIMS : smsBearerByUser = " + smsBearerByUser +" | value = "+ value
                + " | mDefaultSmsOverIp = " + mDefaultSmsOverIp);
        if (smsBearerByUser == -1) {
            // Update configuration of sms bearer from NV if user can't set
            mSmsOverIp = value;
            mDefaultSmsOverIp = value;
        } else {
            // If user configure sms bearer already，synchronize user's preference to modem
            mSmsOverIp = smsBearerByUser;
            if (smsBearerByUser != value) {
                mHandler.removeMessages(ACTION_SET_SMS_OVER_IP_AVAILABILITY);
                mHandler.sendEmptyMessageDelayed(ACTION_SET_SMS_OVER_IP_AVAILABILITY, 1000);
            }
        }

    }
    /*@}*/

    public int getVideoQualityFromPreference(){
        return mSharedPreferences.getInt(VT_RESOLUTION_VALUE+mImsServiceId, mDefaultVtResolution);  // SPRD: bug805154
    }


    /* UNISOC: add for bug1181272 @{ */
    public int getSmsOverIp() {
        return mSharedPreferences.getInt(SMS_OVER_IP + mImsServiceId, mDefaultSmsOverIp);
    }
    /*@}*/

    public static boolean isVolteEnabledBySystemProperties(){
        return SystemProperties.getBoolean("persist.vendor.sys.volte.enable", false);
    }

    public static boolean isImsEnabledBySystemProperties(){
        return SystemProperties.getBoolean("persist.vendor.sys.volte.enable", false) || SystemProperties.getInt("persist.dbg.wfc_avail_ovr", 0) == 1;
    }

    public static boolean isVoWiFiEnabledByBoard(Context context){
        if(context == null) return false;
        return context.getResources().getBoolean(com.android.internal.R.bool.config_device_wfc_ims_available);
    }

    private OnSharedPreferenceChangeListener mSharedPreferenceListener = new OnSharedPreferenceChangeListener() {
        public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
            Log.d(TAG,"onSharedPreferenceChanged()->key:"+key + " | mImsServiceId = " + mImsServiceId);
            if((VIDEO_CALL_RESOLUTION+mImsServiceId).equals(key)){//SPRD:modify by bug814655
                mCameraResolution = sharedPreferences.getInt(VIDEO_CALL_RESOLUTION+mImsServiceId, mDefaultVtResolution);
                mHandler.removeMessages(EVENT_VOLTE_CALL_DEFINED_MEDIA_TYPE);
                mHandler.sendEmptyMessageDelayed(EVENT_VOLTE_CALL_DEFINED_MEDIA_TYPE, 1000);
                Log.d(TAG,"onSharedPreferenceChanged()->mCameraResolution:"+mCameraResolution);
            } else if ((SMS_OVER_IP + mImsServiceId).equals(key)) {
                mSmsOverIp = sharedPreferences.getInt(SMS_OVER_IP + mImsServiceId, mDefaultSmsOverIp);
                mHandler.removeMessages(ACTION_SET_SMS_OVER_IP_AVAILABILITY);
                mHandler.sendEmptyMessageDelayed(ACTION_SET_SMS_OVER_IP_AVAILABILITY, 1000);
            }
        }
    };
}
