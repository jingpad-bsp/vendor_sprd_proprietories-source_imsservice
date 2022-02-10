package com.spreadtrum.ims.vowifi;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.util.Log;

import com.spreadtrum.ims.vowifi.Utilities.PendingAction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map.Entry;

public abstract class ServiceManager {
    private static final String TAG = Utilities.getTag(ServiceManager.class.getSimpleName());

    protected String mActionName;
    protected String mPackageName;
    protected String mClassName;

    protected Context mContext;
    protected Intent mIntent;
    protected IBinder mServiceBinder;
    protected PendingActionMap mPendingActions;

    private ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceDisconnected(ComponentName name) {
            if (Utilities.DEBUG) Log.i(TAG, "The service " + name + " disconnected.");
            mServiceBinder = null;
            onNativeReset();
            onServiceChanged();

            // Re-bind the service if the service disconnected.
            Log.d(TAG, "As service disconnected, will rebind the service after 10s.");
            mHandler.sendEmptyMessageDelayed(MSG_REBIND_SERVICE, 10 * 1000);
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            if (Utilities.DEBUG) Log.i(TAG, "The service " + name + " connected.");
            mServiceBinder = service;
            onServiceChanged();
        }
    };

    private static final int MSG_REBIND_SERVICE = -1;
    private static final int MSG_PROCESS_PENDING_ACTION = 0;
    protected Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            if (handleNormalMessage(msg)) {
                return;
            }

            if (msg.what == MSG_PROCESS_PENDING_ACTION) {
                processPendingAction();
            } else if (msg.what == MSG_REBIND_SERVICE) {
                rebindService();
            } else {
                PendingAction action = (PendingAction) msg.obj;

                if (action == null) {
                    Log.w(TAG, "Try to handle the pending action, but the action is null.");
                    // The action is null, remove it from the HashMap.
                    synchronized (mPendingActions) {
                        mPendingActions.remove(msg.what);
                    }

                    // If the action is null, do nothing.
                    return;
                }

                Message actionMsg = new Message();
                actionMsg.what = action._action;
                actionMsg.obj = action;
                if (handlePendingAction(actionMsg)) {
                    synchronized (mPendingActions) {
                        mPendingActions.remove(msg.what);
                    }
                }
            }
         }
    };

    protected ServiceManager(Context context, String pkg, String cls, String action) {
        if (context == null) throw new NullPointerException("The context is null.");

        mContext = context;
        mPendingActions = new PendingActionMap();

        mPackageName = pkg;
        mClassName = cls;
        mActionName = action;
    }

    abstract protected void onNativeReset();
    abstract protected void onServiceChanged();

    protected void bindService() {
        if (mServiceBinder != null) {
            Log.w(TAG, "The service already bind, needn't init again.");
            return;
        }

        mIntent = new Intent(mActionName);
        mIntent.setComponent(new ComponentName(mPackageName, mClassName));
        mContext.bindService(mIntent, mConnection, Context.BIND_AUTO_CREATE);
    }

    protected void rebindService() {
        if (mIntent != null) {
            mContext.bindService(mIntent, mConnection, Context.BIND_AUTO_CREATE);
        }
    }

    protected void unbindService() {
        mContext.unbindService(mConnection);
    }

    protected boolean handleNormalMessage(Message msg) {
        return false;
    }

    private void processPendingAction() {
        synchronized (mPendingActions) {
            if (mPendingActions.isEmpty()) return;

            Iterator<Entry<Integer, PendingAction>> iterator =
                    mPendingActions.entrySet().iterator();
            while (iterator.hasNext()) {
                Entry<Integer, PendingAction> entry = iterator.next();
                Message newMsg = new Message();
                newMsg.what = entry.getKey();
                newMsg.obj = entry.getValue();
                mHandler.sendMessage(newMsg);
            }
        }
    }

    protected void addToPendingList(PendingAction action) {
        if (action == null) {
            Log.e(TAG, "Can not add this action to pending list as it is null.");
            return;
        }

        synchronized (mPendingActions) {
            Integer key = (int) System.currentTimeMillis();
            mPendingActions.put(key, action);
        }
    }

    protected void clearPendingList() {
        clearPendingList(null);
    }

    protected void clearPendingList(ArrayList<Integer> exceptMsgs) {
        synchronized (mPendingActions) {
            if (exceptMsgs == null || exceptMsgs.size() < 1) {
                mPendingActions.clear();
                Log.d(TAG, "All the pending action will be clear.");
            } else {
                HashMap<Integer, PendingAction> actionMap =
                        (HashMap<Integer, PendingAction>) mPendingActions.clone();
                Iterator<Entry<Integer, PendingAction>> it = actionMap.entrySet().iterator();
                while (it.hasNext()) {
                    Entry<Integer, PendingAction> entry = it.next();
                    PendingAction action = entry.getValue();
                    if (!exceptMsgs.contains(action._action)) {
                        mPendingActions.remove(entry.getKey());
                        Log.d(TAG, "The pending action[msg.what=" + action._action
                                + "] will be removed.");
                    }
                }
            }
        }
    }

    protected boolean handlePendingAction(Message msg) {
        // Do nothing here. Please override this function.
        return true;
    }

    private class PendingActionMap extends HashMap<Integer, PendingAction> {
        @Override
        public PendingAction put(Integer key, PendingAction action) {
            Log.d(TAG, "Add a new pending action: " + action);

            PendingAction res = super.put(key, action);
            mHandler.removeMessages(MSG_PROCESS_PENDING_ACTION);
            mHandler.sendEmptyMessageDelayed(MSG_PROCESS_PENDING_ACTION, action._retryAfterMillis);
            return res;
        }
    }
}
