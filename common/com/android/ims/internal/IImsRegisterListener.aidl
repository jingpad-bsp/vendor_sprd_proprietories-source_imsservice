
package com.android.ims.internal;

/**
  * @hide
  */
interface IImsRegisterListener {
    /**
     * Notifies the status of IMS register.
     * param isRegister: IMS register status
     */
    void imsRegisterStateChange(boolean isRegistered);
}
