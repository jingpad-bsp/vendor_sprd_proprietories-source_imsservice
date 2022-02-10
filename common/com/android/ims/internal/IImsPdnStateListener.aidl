
package com.android.ims.internal;

/**
  * @hide
  */
interface IImsPdnStateListener {
    /**
     * Notifies the status of IMS PDN.
     * param isRegister: IMS PDN status
     * IMS_PDN_ACTIVE_FAILED = 0;
     * IMS_PDN_READY = 1;
     * IMS_PDN_START = 2;
     */
    void imsPdnStateChange(int status);
}
