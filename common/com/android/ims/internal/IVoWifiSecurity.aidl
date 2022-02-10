package com.android.ims.internal;

import com.android.ims.internal.IVoWifiSecurityCallback;

/**
 * @hide
 */
interface IVoWifiSecurity {

    void registerCallback(IVoWifiSecurityCallback callback);

    void unregisterCallback(IVoWifiSecurityCallback callback);

    int start(int type, int subId);

    int startWithAddr(boolean isHandover, int type, int subId, String localAddr);

    void stop(int sessionId, boolean forHandover);

    void startMobike(int sessionId);

    int getState(int sessionId);

    boolean switchLoginIpVersion(int sessionId, int ipVersion);

    boolean deleteTunelIpsec(int sessionId);

}
