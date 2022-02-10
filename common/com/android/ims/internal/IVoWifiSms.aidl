package com.android.ims.internal;

import com.android.ims.internal.IVoWifiSmsCallback;
import android.os.Bundle;

/**
 * @hide
 */
interface IVoWifiSms {

    String registerCallback(in IVoWifiSmsCallback callback);
    void unregisterCallback(String hashcode);

    int sendSms(int token, int messageRef, int retry, String smsc, String pdu);
    int acknowledgeSms(int token, int messageRef, int cause);
    int acknowledgeSmsReport(int token, int messageRef, int cause);

}
