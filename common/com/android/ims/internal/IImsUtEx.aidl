
package com.android.ims.internal;

import com.android.ims.internal.IImsUtListenerEx;

/**
  * @hide
  */
interface IImsUtEx {

    /**
     * Retrieves the configuration of the call forward.
     */
    int setCallForwardingOption(int phoneId, int commandInterfaceCFAction,
            int commandInterfaceCFReason,int serviceClass, String dialingNumber,
            int timerSeconds, String ruleSet);

    /**
     * Updates the configuration of the call forward.
     */
    int getCallForwardingOption(int phoneId, int commandInterfaceCFReason, int serviceClass,
            String ruleSet);

    /**
     * Sets the listener.
     */
    void setListenerEx(int phoneId, IImsUtListenerEx listener);

    /**
     * Updates the configuration of the call barring password.
     */
    int changeBarringPassword(int phoneId, String facility, String oldPwd, String newPwd);

    /**
     * Updates the configuration of the call barring.
     */
    int setFacilityLock(int phoneId, String facility, boolean lockState, String password,
            int serviceClass);

    /**
     * query the configuration of the call barring.
     */
    int queryFacilityLock(int phoneId, String facility, String password, int serviceClass);

    /**
     * query RootNode.
     */
    int queryRootNode(int phoneId);
}
