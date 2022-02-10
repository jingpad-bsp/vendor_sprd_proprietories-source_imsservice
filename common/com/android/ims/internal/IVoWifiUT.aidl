package com.android.ims.internal;

import com.android.ims.internal.IVoWifiUTCallback;

/**
 * @hide
 */
interface IVoWifiUT {
    void registerCallback(IVoWifiUTCallback callback);

    void unregisterCallback(IVoWifiUTCallback callback);

    boolean updateIPAddr(String localIP, String dnsIP);

    /**
     * Retrieves the configuration of the call barring.
     */
    int queryCallBarring(int cbType);

    /**
     * Retrieves the configuration of the call forward.
     */
    int queryCallForward();

    /**
     * Retrieves the configuration of the call waiting.
     */
    int queryCallWaiting();

    /**
     * Updates the configuration of the call barring.
     */
    int updateCallBarring(int cbCondition, boolean enable, in String[] barrList, int serviceClass);

    /**
     * Updates the configuration of the call forward.
     */
    int updateCallForward(int action, int condition, String number, int serviceClass,
            int timeSeconds);

    /**
     * Updates the configuration of the call waiting.
     */
    int updateCallWaiting(boolean enabled);

    int queryCLIR();

    int updateCLIR(int clirMode);

    int queryCLIP();

    int updateCLIP(boolean enabled);

    int queryCOLR();

    int updateCOLR(int presentation);

    int queryCOLP();

    int updateCOLP(boolean enabled);
}