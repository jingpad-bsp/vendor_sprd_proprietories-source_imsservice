package com.android.ims.internal;

import com.android.ims.internal.IImsServiceListenerEx;
import com.android.ims.internal.IImsRegisterListener;
import com.android.ims.internal.ImsSrvccCallInfo;
import com.android.ims.internal.IImsPdnStateListener;

/**
  * @hide
  */
interface IImsServiceEx {

      /**
     * Used for switch IMS feature.
     * param type:
     * ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_LTE = 0;
     * ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_WIFI = 2;
     * return: request id
     */
    int switchImsFeature(int type);

    /**
     * Used for start IMS handover.
     * param targetType:
     * ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_LTE = 0;
     * ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_WIFI = 2;
     * return: request id
     */
    int startHandover(int targetType);

    /**
     * Used for notify network unavailable.
     */
    void notifyNetworkUnavailable();

    /**
     * Used for get IMS feature for main sim card.
     * return:
     * ImsConfig.FeatureConstants.FEATURE_TYPE_UNKNOWN = -1;
     * ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_LTE = 0;
     * ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_WIFI = 2;
     */
    int getCurrentImsFeature();

    /* UNISOC: Add for bug950573 @{*/
    /**
     * Used for get IMS feature for specific sim card.
     * param phoneId: identify specific sim card to get currentImsFeature
     * return:
     * ImsConfig.FeatureConstants.FEATURE_TYPE_UNKNOWN = -1;
     * ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_LTE = 0;
     * ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_WIFI = 2;
     */
    int getCurrentImsFeatureForPhone(int phoneId);
    /*@}*/

    /* UNISOC: Add for bug1119747 @{*/
    /**
     * Used for get vowifi attach status.
     */
    boolean isVoWifiAttached();
    /*@}*/

    /**
     * Used for set IMS service listener.
     */
    void setImsServiceListener(IImsServiceListenerEx listener);

    /**
     * Used for get IMS register address.
     */
    String getImsRegAddress();

    /**
     * Used for set release VoWifi Resource.
     */
    int releaseVoWifiResource();

    /**
     * Used for set VoWifi unavailable.
     * param wifiState:
     * wifi_disabled = 0;
     * wifi_enabled = 1;
     * return: request id
      */
    int setVoWifiUnavailable(int wifiState, boolean isOnlySendAT);

    /**
     * Used for cancel current switch or handover request.
     * return: request id
     */
    int cancelCurrentRequest();

     /**
     * Used for register IMS register listener.
     */
    void registerforImsRegisterStateChanged(IImsRegisterListener listener);

    /**
     * Used for unregister IMS register listener.
     */
    void unregisterforImsRegisterStateChanged(IImsRegisterListener listener);


    /**
     * Used for terminate VoWifi calls.
     * param wifiState:
     * wifi_disabled = 0;
     * wifi_enabled = 1;
      */
    void terminateCalls(int wifiState);

    /**
     * Used for get P-CSCF address.
     * return: P-CSCF address
     */
    String getCurPcscfAddress();

    /**
     * Used for set monitor period millis.
     */
    void setMonitorPeriodForNoData(int millis);

    /**
     * Used for vowifi unregist attention.
     */
    void showVowifiNotification();

    /**
     * Used for get local address.
     */
    String getCurLocalAddress();

    /**
     * Get current IMS video state.
     * return: video state
     * {VideoProfile#STATE_AUDIO_ONLY},
     * {VideoProfile#STATE_BIDIRECTIONAL},
     * {VideoProfile#STATE_TX_ENABLED},
     * {VideoProfile#STATE_RX_ENABLED},
     * {VideoProfile#STATE_PAUSED}.
     */
    int getCurrentImsVideoState();

    /**
     * Used for get alive call lose.
     */
    int getAliveCallLose();

    /**
     * Used for get alive call jitter.
     */
    int getAliveCallJitter();

    /**
     * Used for get alive call rtt.
     */
    int getAliveCallRtt();
    /**
     * Get Volte register state for main sim card.
     */
     int getVolteRegisterState();

    /* UNISOC: Add for bug972969  @{*/
    /**
     * Used for Volte register state for specific sim card.
     * param phoneId: identify specific sim card to get Volte register state
     */
     int getVolteRegisterStateForPhone(int phoneId);
    /*@}*/

    /**
     * Get call type
     * return:
     * NO_CALL = -1;
     * VOLTE_CALL = 0;
     * WIFI_CALL = 2;
     */
     int getCallType();

    /**
     * notify SRVCC Call Info
     */
     void notifySrvccCallInfos(in List<ImsSrvccCallInfo> list);

    /**
     * Used for get local address.
     */
     String getImsPcscfAddress();

     /**
     * used for set register or de-regesiter Vowifi
     * para action
     * 0 de-register before start call
     * 1 register after call end
     * **/
     void setVowifiRegister(int action);

    /**
     * Used for add IMS PDN State Listener.
     */
     void addImsPdnStateListener(int slotId, IImsPdnStateListener listener);

    /**
     * Used for remove IMS PDN State Listener.
     */
     void removeImsPdnStateListener(int slotId, IImsPdnStateListener listener);

     /**
     * used for get CLIR status for vowifi
     * para phone id
     * return ut request id
     * **/
     int getCLIRStatus(int phoneId);

      /**
      * used for set CLIR status for vowifi
      * para action
      * ImsUtInterface.OIR_PRESENTATION_NOT_RESTRICTED
      * ImsUtInterface.OIR_PRESENTATION_RESTRICTED
      * **/
      int updateCLIRStatus(int action);

      /**
       * Notify video capability change
       */
      void notifyVideoCapabilityChange();

      /**
       * Used for start Mobike
      */
      void startMobike();

      /**
       * Used for check Mobike whether support
       */
      boolean isSupportMobike();

      /**
       * used for get CW status for vowifi
       * para phone id
       *
       **/
      void getCallWaitingStatus(int phoneId);

      /**
      * Used for get CNI info.
      */
      void getImsCNIInfor();
}
