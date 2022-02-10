package com.android.ims.internal;

interface IN3AMVideoStateNoitfy {
    void notifyVideoQos(int lose, int jitter, int rtt);
    void notifyRtpReceived(boolean isReceived);
}