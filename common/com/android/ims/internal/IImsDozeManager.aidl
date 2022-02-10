package com.android.ims.internal;

import com.android.ims.internal.IImsDozeObserver;

/**
 * {@hide}
 */
interface IImsDozeManager {
    void setImsDozeEnabled(boolean enabled);

    void registerImsDozeObserver(in IImsDozeObserver observer);

    void unregisterImsDozeObserver(in IImsDozeObserver observer);
}
