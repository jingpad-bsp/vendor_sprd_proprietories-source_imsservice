package com.android.ims.internal;

import com.android.ims.internal.IVoWifiRegisterCallback;

/**
 * @hide
 */
interface IVoWifiRegister {

    void registerCallback(IVoWifiRegisterCallback callback);

    void unregisterCallback(IVoWifiRegisterCallback callback);

    int cliOpen(int subId);

    int cliStart();

    int cliUpdateSettings(boolean isSRVCCSupport);

    /**
     * Start the SIP register process. Before login, you need open, start and update
     * account settings first.
     *
     * @return {@link Utils#RESULT_FAIL} as fail. If login failed, please handle it.
     *         {@link Utils#RESULT_SUCCESS} as success.
     */
    int cliLogin(boolean forSos, boolean isIPv4, String localIP, String pcscfIP,
            String dnsSerIP, int networkType, String info, int age, boolean isRelogin);

    /**
     * Start the SIP re-register process.
     *
     * @return {@link Utils#RESULT_FAIL} as fail. If re-register failed, please handle it.
     *         {@link Utils#RESULT_SUCCESS} as success.
     */
    int cliRefresh(int type, String info);

    /**
     * Start the logout process.
     *
     * @return {@link Utils#RESULT_FAIL} as fail. If logout failed, please handle it.
     *         {@link Utils#RESULT_SUCCESS} as success.
     */
    int cliLogout();

    int cliReset();

}
