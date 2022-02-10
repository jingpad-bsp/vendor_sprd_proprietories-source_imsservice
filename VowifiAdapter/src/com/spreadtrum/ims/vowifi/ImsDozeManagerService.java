package com.spreadtrum.ims.vowifi;

import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

import com.android.ims.internal.IImsDozeManager;
import com.android.ims.internal.IImsDozeObserver;
import com.android.ims.internal.ImsManagerEx;

import java.util.ArrayList;

public class ImsDozeManagerService extends Service {
    private static final String TAG = Utilities.getTag(ImsDozeManagerService.class.getSimpleName());

    private IBinder mBinder = new ImsDozeManagerImpl(this);

    private boolean mImsDozeEnabled = true;
    private ArrayList<IImsDozeObserver> mObserverList = new ArrayList<IImsDozeObserver>();

    private static final int MSG_NOTIFY = 1;
    private static final int MSG_NOTIFY_ALL = 2;
    private Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            try {
                if (msg.what == MSG_NOTIFY) {
                    IImsDozeObserver observer = (IImsDozeObserver) msg.obj;
                    if (observer != null) {
                        observer.onDozeModeOnOff(mImsDozeEnabled);
                    }
                } else if (msg.what == MSG_NOTIFY_ALL && mObserverList.size() > 0) {
                    for (IImsDozeObserver observer : mObserverList) {
                        observer.onDozeModeOnOff(mImsDozeEnabled);
                    }
                }
            } catch (RemoteException ex) {
                Log.e(TAG, "Failed to notify the ims doze status[" + mImsDozeEnabled
                        + "] for the message[" + msg.what + "] as catch the ex: " + ex.toString());
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();

        ServiceManager.addService(ImsManagerEx.IMS_DOZE_MANAGER, mBinder);
        Log.d(TAG, "IMS_DOZE_MANAGER add to service manager.");
    }

    @Override
    public IBinder onBind(Intent arg0) {
        return mBinder;
    }

    private void setImsDozeEnabled(boolean enabled) {
        Log.d(TAG, "Update the doze status from " + mImsDozeEnabled + " to " + enabled);
        if (mImsDozeEnabled != enabled) {
            mImsDozeEnabled = enabled;
            mHandler.sendEmptyMessage(MSG_NOTIFY_ALL);
        }
    }

    private void registerImsDozeObserver(IImsDozeObserver observer) {
        if (observer != null) {
            mObserverList.add(observer);
            mHandler.sendMessage(mHandler.obtainMessage(MSG_NOTIFY, observer));
        }
    }

    private void unregisterImsDozeObserver(IImsDozeObserver observer) {
        if (observer != null) {
            mObserverList.remove(observer);
        }
    }

    private class ImsDozeManagerImpl extends IImsDozeManager.Stub {
        private ImsDozeManagerService mService = null;

        public ImsDozeManagerImpl(ImsDozeManagerService service) {
            mService = service;
        }

        @Override
        public void setImsDozeEnabled(boolean enabled) throws RemoteException {
            mService.setImsDozeEnabled(enabled);
        }

        @Override
        public void registerImsDozeObserver(IImsDozeObserver observer) throws RemoteException {
            mService.registerImsDozeObserver(observer);
        }

        @Override
        public void unregisterImsDozeObserver(IImsDozeObserver observer) throws RemoteException {
            mService.unregisterImsDozeObserver(observer);
        }
    }
}
