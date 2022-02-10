package com.spreadtrum.ims.ut;

import java.util.HashMap;
import android.telephony.ims.ImsCallForwardInfo;
import android.telephony.ims.ImsReasonInfo;
import android.telephony.ims.ImsSsInfo;
import android.telephony.ims.ImsSsData;
import com.android.internal.telephony.uicc.IsimRecords;
import com.android.ims.ImsManager;
import com.android.ims.internal.IImsUt;
import com.android.ims.internal.IImsUtListener;
import com.android.ims.internal.ImsCallForwardInfoEx;

import android.os.Bundle;
import android.content.Context;
import android.os.RemoteException;
import com.spreadtrum.ims.ImsService;
import com.spreadtrum.ims.ImsServiceImpl;
import com.spreadtrum.ims.vowifi.ImsUtImpl;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneConstants;
import android.util.Log;
import com.android.ims.internal.IImsUtListenerEx;
import com.android.internal.telephony.CommandsInterface;
import static com.android.internal.telephony.CommandsInterface.SERVICE_CLASS_VOICE;
import static com.android.internal.telephony.CommandsInterface.SERVICE_CLASS_NONE;
import com.spreadtrum.ims.vowifi.VoWifiConfiguration;
import android.telephony.SubscriptionManager;

public class ImsUtProxy extends IImsUt.Stub {
    private static final String TAG = ImsUtProxy.class.getSimpleName();

    private static final int PRIORITY_VOLTE_UT = 0;
    private static final int PRIORITY_VOWIFI_UT = 1;

    private static final int ACTION_QUERY_CB    = 1;
    private static final int ACTION_QUERY_CF    = 2;
    private static final int ACTION_QUERY_CW    = 3;
    private static final int ACTION_QUERY_CLIR  = 4;
    private static final int ACTION_QUERY_CLIP  = 5;
    private static final int ACTION_QUERY_COLR =  6;
    private static final int ACTION_QUERY_COLP  = 7;
    private static final int ACTION_TRANSACT    = 8;
    private static final int ACTION_UPDATE_CB   = 9;
    private static final int ACTION_UPDATE_CF   = 10;
    private static final int ACTION_UPDATE_CW   = 11;
    private static final int ACTION_UPDATE_CLIR = 12;
    private static final int ACTION_UPDATE_CLIP = 13;
    private static final int ACTION_UPDATE_CLOR = 14;
    private static final int ACTION_UPDATE_COLP = 15;
    private static final int ACTION_QUERY_CF_EX = 16;
    private static final int ACTION_UPDATE_CF_EX= 17;
    private static final int ACTION_UPDATE_CB_EX= 18;
    private static final int ACTION_QUERY_CB_EX= 19;
    private static final int ACTION_CHANGE_CB_PW= 20;
    private static final int INVALID_ID = -1;
    private static final int VOWIFI_QUERY_ID = 100;

    private static final String EXTRA_ID = "id";
    private static final String EXTRA_ACTION = "action";
    private static final String EXTRA_FACILITY = "facility";
    private static final String EXTRA_PASSWORD = "password";
    private static final String EXTRA_OLD_PASSWORD = "oldPassword";
    private static final String EXTRA_CFACTION = "CFAction";
    private static final String EXTRA_CFREASON = "CFReason";
    private static final String EXTRA_SERVICE_CLASS = "serviceClass";
    private static final String EXTRA_DIALING_NUM = "dialingNumber";
    private static final String EXTRA_TIMER_SECONDS = "timerSeconds";
    private static final String EXTRA_RULE_SET = "ruleSet";
    private static final String EXTRA_LOCK_STATE = "lockState";
    private static final String EXTRA_CLIR_MODE = "clirMode";
    private static final String EXTRA_BARR_LIST = "barrList";

    private com.spreadtrum.ims.ut.ImsUtImpl mVoLTEUtImpl;
    private com.spreadtrum.ims.vowifi.ImsUtImpl mVoWifiUtImpl;
    private IImsUtListener mListener;
    private IImsUtListenerEx mListenerEx;
    private int mPriority = PRIORITY_VOLTE_UT;
    private boolean mQueryOnVoLTE = false;
    private Context mContext;
    private ImsService mImsService;
    private Phone mPhone;
    private int mServiceId = -1;
    private ImsServiceImpl mImsServiceImpl;
    private HashMap<Integer, Bundle> mPendingMap = new HashMap<Integer, Bundle>();
    private HashMap<Integer, Integer> mQueryOnVoLTEId = new HashMap<Integer, Integer>();

    public ImsUtProxy(Context context, com.spreadtrum.ims.ut.ImsUtImpl VoLTEUtImpl,
            com.spreadtrum.ims.vowifi.ImsUtImpl VoWifiUtImpl, Phone phone, ImsServiceImpl imsServiceImpl) {
        mContext = context;
        mVoLTEUtImpl = VoLTEUtImpl;
        mVoWifiUtImpl = VoWifiUtImpl;
        mPhone = phone;
        mServiceId = phone.getPhoneId() + 1;
        mImsServiceImpl = imsServiceImpl;
    }

    /**
     * Closes the object. This object is not usable after being closed.
     */
    public void close() {
        if (isVowifiUtEnable()) {
            mVoWifiUtImpl.close();
        } else {
            mVoLTEUtImpl.close();
        }
    }

    /**
     * Retrieves the configuration of the call barring.
     */
    public int queryCallBarring(int cbType) {
        int id = INVALID_ID;
        if (isVowifiUtEnable()) {
            id = mVoWifiUtImpl.queryCallBarring(cbType);
            if (id > VOWIFI_QUERY_ID && mQueryOnVoLTE) {
                Bundle bundle = new Bundle();
                bundle.putInt(EXTRA_ACTION, ACTION_QUERY_CB);
                bundle.putInt(EXTRA_FACILITY, cbType);
                mPendingMap.put(id, bundle);
            }
            if (id < 0 && mQueryOnVoLTE) {
                id = mVoLTEUtImpl.queryCallBarring(cbType);
            }
        } else {
            id = mVoLTEUtImpl.queryCallBarring(cbType);
        }
        log("queryCB id = " + id);
        return id;
    }

    /**
     * Retrieves the configuration of the call forward.
     */
    public int queryCallForward(int condition, String number) {
        int id = INVALID_ID;
        if (isVowifiUtEnable()) {
            id = mVoWifiUtImpl.queryCallForward(condition, number);
            if (id > VOWIFI_QUERY_ID && mQueryOnVoLTE) {
                Bundle bundle = new Bundle();
                bundle.putInt(EXTRA_ACTION, ACTION_QUERY_CF);
                bundle.putInt(EXTRA_CFREASON, condition);
                bundle.putString(EXTRA_DIALING_NUM, number);
                mPendingMap.put(id, bundle);
            }
            if (id < 0 && mQueryOnVoLTE) {
                id = mVoLTEUtImpl.queryCallForward(condition, number);
            }
        } else {
            id = mVoLTEUtImpl.queryCallForward(condition, number);
        }
        log("queryCF id = " + id);
        return id;
    }

    /**
     * Retrieves the configuration of the call waiting.
     */
    public int queryCallWaiting() {
        int id = INVALID_ID;
        try {
            if (isVowifiUtEnable()) {
                id = mVoWifiUtImpl.queryCallWaiting();
                if (id > VOWIFI_QUERY_ID && mQueryOnVoLTE) {
                    Bundle bundle = new Bundle();
                    bundle.putInt(EXTRA_ACTION, ACTION_QUERY_CW);
                    mPendingMap.put(id, bundle);
                }
                if (id < 0 && mQueryOnVoLTE) {
                    id = mVoLTEUtImpl.queryCallWaiting();
                }
            } else {
                id = mVoLTEUtImpl.queryCallWaiting();
            }
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        log("queryCW id = " + id);
        return id;
    }

    /**
     * Retrieves the default CLIR setting.
     */
    public int queryCLIR() {
        int id = INVALID_ID;
        try {
            if (isVowifiUtEnable()) {
                id = mVoWifiUtImpl.queryCLIR();
                if (id > VOWIFI_QUERY_ID && mQueryOnVoLTE) {
                    Bundle bundle = new Bundle();
                    bundle.putInt(EXTRA_ACTION, ACTION_QUERY_CLIR);
                    mPendingMap.put(id, bundle);
                }
                if (id < 0 && mQueryOnVoLTE) {
                    id = mVoLTEUtImpl.queryCLIR();
                }
            } else {
                id = mVoLTEUtImpl.queryCLIR();
            }
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        log("queryCLIR id = " + id);
        return id;
    }

    /**
     * Retrieves the CLIP call setting.
     */
    public int queryCLIP() {
        int id = INVALID_ID;
        try {
            if (isVowifiUtEnable()) {
                id = mVoWifiUtImpl.queryCLIP();
                if (id > VOWIFI_QUERY_ID && mQueryOnVoLTE) {
                    Bundle bundle = new Bundle();
                    bundle.putInt(EXTRA_ACTION, ACTION_QUERY_CLIP);
                    mPendingMap.put(id, bundle);
                }
                if (id < 0 && mQueryOnVoLTE) {
                    id = mVoLTEUtImpl.queryCLIP();
                }
            } else {
                id = mVoLTEUtImpl.queryCLIP();
            }
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        log("queryCLIP id = " + id);
        return id;
    }

    /**
     * Retrieves the COLR call setting.
     */
    public int queryCOLR() {
        int id = INVALID_ID;
        try {
            if (isVowifiUtEnable()) {
                id = mVoWifiUtImpl.queryCOLR();
                if (id > VOWIFI_QUERY_ID && mQueryOnVoLTE) {
                    Bundle bundle = new Bundle();
                    bundle.putInt(EXTRA_ACTION, ACTION_QUERY_COLR);
                    mPendingMap.put(id, bundle);
                }
                if (id < 0 && mQueryOnVoLTE) {
                    id = mVoLTEUtImpl.queryCOLR();
                }
            } else {
                id = mVoLTEUtImpl.queryCOLR();
            }
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        log("queryCOLR id = " + id);
        return id;
    }

    /**
     * Retrieves the COLP call setting.
     */
    public int queryCOLP() {
        int id = INVALID_ID;
        try {
            if (isVowifiUtEnable()) {
                id = mVoWifiUtImpl.queryCOLP();
                if (id > VOWIFI_QUERY_ID && mQueryOnVoLTE) {
                    Bundle bundle = new Bundle();
                    bundle.putInt(EXTRA_ACTION, ACTION_QUERY_COLP);
                    mPendingMap.put(id, bundle);
                }
                if (id < 0 && mQueryOnVoLTE) {
                    id = mVoLTEUtImpl.queryCOLP();
                }
            } else {
                id = mVoLTEUtImpl.queryCOLP();
            }
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        log("queryCOLP id = " + id);
        return id;
    }

    /**
     * Updates or retrieves the supplementary service configuration.
     */
    public int transact(Bundle ssInfo) {
        int id = INVALID_ID;
        if (isVowifiUtEnable()) {
            id = mVoWifiUtImpl.transact(ssInfo);
            if (id < 0 && mQueryOnVoLTE) {
                id = mVoLTEUtImpl.transact(ssInfo);
            }
        } else {
            id = mVoLTEUtImpl.transact(ssInfo);
        }
        return id;
    }

    /**
     * Updates the configuration of the call barring.
     */
    public int updateCallBarring(int cbType, int action, String[] barrList) {
        int id = INVALID_ID;
        if (isVowifiUtEnable()) {
            id = mVoWifiUtImpl.updateCallBarring(cbType, action, barrList);
            if (id > VOWIFI_QUERY_ID && mQueryOnVoLTE) {
                Bundle bundle = new Bundle();
                StringBuilder sb = new StringBuilder();
                if (barrList != null) {
                    for (int i = 0; i < barrList.length; i++) {
                        sb.append(barrList[i]);
                        sb.append(",");
                    }
                }

                bundle.putInt(EXTRA_ACTION, ACTION_UPDATE_CB);
                bundle.putInt(EXTRA_FACILITY, cbType);
                bundle.putInt(EXTRA_LOCK_STATE, action);
                bundle.putString(EXTRA_BARR_LIST, sb.toString());
                mPendingMap.put(id, bundle);
            }
            if (id < 0 && mQueryOnVoLTE) {
                id = mVoLTEUtImpl.updateCallBarring(cbType, action, barrList);
            }
        } else {
            id = mVoLTEUtImpl.updateCallBarring(cbType, action, barrList);
        }
        log("updateCB id = " + id);
        return id;
    }

    /**
     * Updates the configuration of the call forward.
     */
    public int updateCallForward(int action, int condition, String number,
            int serviceClass, int timeSeconds) {
        int id = INVALID_ID;
        if (isVowifiUtEnable()) {
            id = mVoWifiUtImpl.updateCallForward(action, condition,number, serviceClass, timeSeconds);
            if (id > VOWIFI_QUERY_ID && mQueryOnVoLTE) {
                Bundle bundle = new Bundle();
                bundle.putInt(EXTRA_ACTION, ACTION_UPDATE_CF);
                bundle.putInt(EXTRA_CFACTION, action);
                bundle.putInt(EXTRA_CFREASON, condition);
                bundle.putString(EXTRA_DIALING_NUM, number);
                bundle.putInt(EXTRA_SERVICE_CLASS, serviceClass);
                bundle.putInt(EXTRA_TIMER_SECONDS, timeSeconds);
                mPendingMap.put(id, bundle);
            }
            if (id < 0 && mQueryOnVoLTE) {
                id = mVoLTEUtImpl.updateCallForward(action, condition,number, serviceClass, timeSeconds);
            }
        } else {
            id = mVoLTEUtImpl.updateCallForward(action, condition,number, serviceClass, timeSeconds);
        }
        log("updateCF id = " + id);
        return id;
    }

    /**
     * Updates the configuration of the call waiting.
     */
    public int updateCallWaiting(boolean enable, int serviceClass) {
        int id = INVALID_ID;
        try {
            if (isVowifiUtEnable()) {
                id = mVoWifiUtImpl.updateCallWaiting(enable, serviceClass);
                if (id > VOWIFI_QUERY_ID && mQueryOnVoLTE) {
                    Bundle bundle = new Bundle();
                    bundle.putInt(EXTRA_ACTION, ACTION_UPDATE_CW);
                    bundle.putBoolean(EXTRA_LOCK_STATE, enable);
                    bundle.putInt(EXTRA_SERVICE_CLASS, serviceClass);
                    mPendingMap.put(id, bundle);
                }
                if (id < 0 && mQueryOnVoLTE) {
                    id = mVoLTEUtImpl.updateCallWaiting(enable, serviceClass);
                }
            } else {
                id = mVoLTEUtImpl.updateCallWaiting(enable, serviceClass);
            }
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        log("updateCW id = " + id);
        return id;
    }

    /**
     * Updates the configuration of the CLIR supplementary service.
     */
    public int updateCLIR(int clirMode) {
        int id = INVALID_ID;
        try {
            if (isVowifiUtEnable()) {
                id = mVoWifiUtImpl.updateCLIR(clirMode);
                if (id > VOWIFI_QUERY_ID && mQueryOnVoLTE) {
                    Bundle bundle = new Bundle();
                    bundle.putInt(EXTRA_ACTION, ACTION_UPDATE_CLIR);
                    bundle.putInt(EXTRA_CLIR_MODE, clirMode);
                    mPendingMap.put(id, bundle);
                }
                if (id < 0 && mQueryOnVoLTE) {
                    id = mVoLTEUtImpl.updateCLIR(clirMode);
                }
            } else {
                id = mVoLTEUtImpl.updateCLIR(clirMode);
            }
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        log("updateCLIR id = " + id);
        return id;
    }

    /**
     * Updates the configuration of the CLIP supplementary service.
     */
    public int updateCLIP(boolean enable) {
        int id = INVALID_ID;
        try {
            if (isVowifiUtEnable()) {
                id = mVoWifiUtImpl.updateCLIP(enable);
                if (id < 0 && mQueryOnVoLTE) {
                    id = mVoLTEUtImpl.updateCLIP(enable);
                }
            } else {
                id = mVoLTEUtImpl.updateCLIP(enable);
            }
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        log("updateCLIP id = " + id);
        return id;
    }

    /**
     * Updates the configuration of the COLR supplementary service.
     */
    public int updateCOLR(int presentation) {
        int id = INVALID_ID;
        try {
            if (isVowifiUtEnable()) {
                id = mVoWifiUtImpl.updateCOLR(presentation);
                if (id < 0 && mQueryOnVoLTE) {
                    id = mVoLTEUtImpl.updateCOLR(presentation);
                }
            } else {
                id = mVoLTEUtImpl.updateCOLR(presentation);
            }
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        log("updateCOLR id = " + id);
        return id;
    }

    /**
     * Updates the configuration of the COLP supplementary service.
     */
    public int updateCOLP(boolean enable) {
        int id = INVALID_ID;
        try {
            if (isVowifiUtEnable()) {
                id = mVoWifiUtImpl.updateCOLP(enable);
                if (id < 0 && mQueryOnVoLTE) {
                    id = mVoLTEUtImpl.updateCOLP(enable);
                }
            } else {
                id = mVoLTEUtImpl.updateCOLP(enable);
            }
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        log("updateCOLP id = " + id);
        return id;
    }

    /**
     * Sets the listener.
     */
    public void setListener(IImsUtListener listener) {
        mListener = listener;
        getUTConfig();

        try {
            if (mVoWifiUtImpl != null) {
                mVoWifiUtImpl.setListener(mImsUtListener);
            }
            mVoLTEUtImpl.setListener(mImsUtListener);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    /**
     * Retrieves the configuration of the call forward.
     */
    public int setCallForwardingOption(int commandInterfaceCFAction,
            int commandInterfaceCFReason,int serviceClass, String dialingNumber,
            int timerSeconds, String ruleSet){
        int id = INVALID_ID;
        if (isVowifiUtEnable()) {
            id = mVoWifiUtImpl.setCallForwardingOption(commandInterfaceCFAction, commandInterfaceCFReason,
                    serviceClass, dialingNumber, timerSeconds, ruleSet);
            if (id > VOWIFI_QUERY_ID && mQueryOnVoLTE) {
                Bundle bundle = new Bundle();
                bundle.putInt(EXTRA_ACTION, ACTION_UPDATE_CF_EX);
                bundle.putInt(EXTRA_CFREASON, commandInterfaceCFReason);
                bundle.putInt(EXTRA_CFACTION, commandInterfaceCFAction);
                bundle.putInt(EXTRA_SERVICE_CLASS, serviceClass);
                bundle.putString(EXTRA_DIALING_NUM, dialingNumber);
                bundle.putInt(EXTRA_TIMER_SECONDS, timerSeconds);
                bundle.putString(EXTRA_RULE_SET, ruleSet);
                mPendingMap.put(id, bundle);
            }
            if (id < 0 && mQueryOnVoLTE) {
                id = mVoLTEUtImpl.setCallForwardingOption(commandInterfaceCFAction, commandInterfaceCFReason,
                        serviceClass, dialingNumber, timerSeconds, ruleSet);
            }
        } else {
            id = mVoLTEUtImpl.setCallForwardingOption(commandInterfaceCFAction, commandInterfaceCFReason,
                    serviceClass, dialingNumber, timerSeconds, ruleSet);
        }
        log("setCFP id = " + id);
        return id;
    }

    /**
     * Updates the configuration of the call forward.
     */
    public int getCallForwardingOption(int commandInterfaceCFReason, int serviceClass,
            String ruleSet){
        int id = INVALID_ID;
        if (isVowifiUtEnable()) {
            id = mVoWifiUtImpl.getCallForwardingOption(commandInterfaceCFReason,
                    serviceClass, ruleSet);
            if (id > VOWIFI_QUERY_ID && mQueryOnVoLTE) {
                Bundle bundle = new Bundle();
                bundle.putInt(EXTRA_ACTION, ACTION_QUERY_CF_EX);
                bundle.putInt(EXTRA_CFREASON, commandInterfaceCFReason);
                bundle.putInt(EXTRA_SERVICE_CLASS, serviceClass);
                bundle.putString(EXTRA_RULE_SET, ruleSet);
                mPendingMap.put(id, bundle);
            }
            if (id < 0 && mQueryOnVoLTE) {
                id = mVoLTEUtImpl.getCallForwardingOption( commandInterfaceCFReason,
                        serviceClass, ruleSet);
            }
        } else {
            id = mVoLTEUtImpl.getCallForwardingOption( commandInterfaceCFReason,
                    serviceClass, ruleSet);
        }
        log("getCFP id = " + id);
        return id;
    }

    public int changeBarringPassword(String facility, String oldPwd, String newPwd){
        int id = INVALID_ID;
        if (isVowifiUtEnable()) {
            id = mVoWifiUtImpl.changeBarringPassword(facility,
                  oldPwd, newPwd);
            if (id > VOWIFI_QUERY_ID && mQueryOnVoLTE) {
                Bundle bundle = new Bundle();
                bundle.putInt(EXTRA_ACTION, ACTION_CHANGE_CB_PW);
                bundle.putString(EXTRA_FACILITY, facility);
                bundle.putString(EXTRA_OLD_PASSWORD, oldPwd);
                bundle.putString(EXTRA_PASSWORD, newPwd);
                mPendingMap.put(id, bundle);
            }
            if (id < 0 && mQueryOnVoLTE) {
                id = mVoLTEUtImpl.changeBarringPassword(facility,
                        oldPwd, newPwd);
            }
        } else {
            id = mVoLTEUtImpl.changeBarringPassword(facility,
                  oldPwd, newPwd);
        }
        log("CBP id = " + id);
        return id;
    }

    public int setFacilityLock(String facility, boolean lockState, String password,
            int serviceClass){
        int id = INVALID_ID;
        if (isVowifiUtEnable()) {
            id = mVoWifiUtImpl.setFacilityLock(facility,
                  lockState, password, serviceClass);
          if (id > VOWIFI_QUERY_ID && mQueryOnVoLTE) {
              Bundle bundle = new Bundle();
              bundle.putInt(EXTRA_ACTION, ACTION_UPDATE_CB_EX);
              bundle.putString(EXTRA_FACILITY, facility);
              bundle.putInt(EXTRA_SERVICE_CLASS, serviceClass);
              bundle.putString(EXTRA_PASSWORD, password);
              bundle.putBoolean(EXTRA_LOCK_STATE, lockState);
              mPendingMap.put(id, bundle);
            }
            if (id < 0 && mQueryOnVoLTE) {
                id = mVoLTEUtImpl.setFacilityLock(facility,
                        lockState, password, serviceClass);
            }
        } else {
            id = mVoLTEUtImpl.setFacilityLock(facility,
                   lockState, password, serviceClass);
        }
        log("setFL id = " + id);
        return id;
    }

    public int queryFacilityLock(String facility, String password, int serviceClass) {
        int id = INVALID_ID;
        if (isVowifiUtEnable()) {
            id = mVoWifiUtImpl.queryFacilityLock(facility,
                    password, serviceClass);
            if (id > VOWIFI_QUERY_ID && mQueryOnVoLTE) {
                Bundle bundle = new Bundle();
                bundle.putInt(EXTRA_ACTION, ACTION_QUERY_CB_EX);
                bundle.putString(EXTRA_FACILITY, facility);
                bundle.putInt(EXTRA_SERVICE_CLASS, serviceClass);
                bundle.putString(EXTRA_PASSWORD, password);
                mPendingMap.put(id, bundle);
            }
            if (id < 0 && mQueryOnVoLTE) {
                id = mVoLTEUtImpl.queryFacilityLock(facility,
                        password, serviceClass);
            }
        } else {
            id = mVoLTEUtImpl.queryFacilityLock(facility,
                    password, serviceClass);
        }
        log("queryFL id = " + id);
        return id;
    }

    public int queryRootNode() {
        int id = INVALID_ID;
        if (isVowifiUtEnable()) {
            /*id = mVoWifiUtImpl.queryFacilityLock(facility,
                    password, serviceClass);
            if (id > VOWIFI_QUERY_ID && mQueryOnVoLTE) {
                Bundle bundle = new Bundle();
                bundle.putInt(EXTRA_ACTION, ACTION_QUERY_CB_EX);
                bundle.putString(EXTRA_FACILITY, facility);
                bundle.putInt(EXTRA_SERVICE_CLASS, serviceClass);
                bundle.putString(EXTRA_PASSWORD, password);
                mPendingMap.put(id, bundle);
            }
            if (id < 0 && mQueryOnVoLTE) {
                id = mVoLTEUtImpl.queryFacilityLock(facility,
                        password, serviceClass);
            }*/
            log("queryRootNode: isVowifiUtEnable!!!");
        } else {
            id = mVoLTEUtImpl.queryRootNode();
        }
        log("queryRootNode id = " + id);
        return id;
    }

    private boolean isVowifiUtEnable() {
        if (mVoWifiUtImpl != null && mPriority == PRIORITY_VOWIFI_UT && mImsServiceImpl.isVoWifiEnabled()) {
            return true;
        }
        return  false;
    }

    /**
     * AndroidP start@{:
     */
    @Override
    public int updateCallBarringForServiceClass(int cbType, int action,
                                                String[] barrList, int serviceClass) throws RemoteException {
        int id = INVALID_ID;
        if (isVowifiUtEnable()) {
            //TODO:vowifi add service class
            id = mVoWifiUtImpl.updateCallBarring(cbType, action, barrList);
            if (id > VOWIFI_QUERY_ID && mQueryOnVoLTE) {
                Bundle bundle = new Bundle();
                StringBuilder sb = new StringBuilder();
                if (barrList != null) {
                    for (int i = 0; i < barrList.length; i++) {
                        sb.append(barrList[i]);
                        sb.append(",");
                    }
                }

                bundle.putInt(EXTRA_ACTION, ACTION_UPDATE_CB);
                bundle.putInt(EXTRA_FACILITY, cbType);
                bundle.putInt(EXTRA_LOCK_STATE, action);
                bundle.putString(EXTRA_BARR_LIST, sb.toString());
                bundle.putInt(EXTRA_SERVICE_CLASS, serviceClass);
                mPendingMap.put(id, bundle);
            }
            if (id < 0 && mQueryOnVoLTE) {
                id = mVoLTEUtImpl.updateCallBarringForServiceClass(cbType, action, barrList,serviceClass);
            }
        } else {
            id = mVoLTEUtImpl.updateCallBarringForServiceClass(cbType, action, barrList,serviceClass);
        }
        log("updateCB id = " + id);
        return id;
    }

    @Override
    public int queryCallBarringForServiceClass(int cbType, int serviceClass){
        int id = INVALID_ID;
        if (isVowifiUtEnable()) {
            //TODO:vowifi add service class
            id = mVoWifiUtImpl.queryCallBarring(cbType);
            if (id > VOWIFI_QUERY_ID && mQueryOnVoLTE) {
                Bundle bundle = new Bundle();
                bundle.putInt(EXTRA_ACTION, ACTION_QUERY_CB);
                bundle.putInt(EXTRA_FACILITY, cbType);
                bundle.putInt(EXTRA_SERVICE_CLASS, serviceClass);
                mPendingMap.put(id, bundle);
            }
            if (id < 0 && mQueryOnVoLTE) {
                id = mVoLTEUtImpl.queryCallBarringForServiceClass(cbType,serviceClass);
            }
        } else {
            id = mVoLTEUtImpl.queryCallBarringForServiceClass(cbType,serviceClass);
        }
        log("queryCB id = " + id);
        return id;
    }
    /* AndroidP end@} */

    public void setListenerEx(IImsUtListenerEx listenerEx) {
        mListenerEx = listenerEx;
        getUTConfig();

        log("setListenerEx");
        if (mVoWifiUtImpl != null) {
            mVoWifiUtImpl.setListenerEx(mImsUtListenerExBinder);
        }
        mVoLTEUtImpl.setListenerEx(mImsUtListenerExBinder);
    }

    private void getUTConfig() {
        boolean isVowifiSupportUT = false;
        if (mContext != null && SubscriptionManager.isValidSubscriptionId(mPhone.getSubId())) {

            isVowifiSupportUT = VoWifiConfiguration.isSupportUT(mContext, mPhone.getSubId());
            mPriority = isVowifiSupportUT ? PRIORITY_VOWIFI_UT : PRIORITY_VOLTE_UT;
            mQueryOnVoLTE = isVowifiSupportUT;
        }
        log("getUTConfig isVowifiSupportUT: " + isVowifiSupportUT + " mPriority: " + mPriority);
    }

    //UNISOC: add for bug1155369
    public void updateUtConfig(com.spreadtrum.ims.vowifi.ImsUtImpl voWifiUtImpl) {
        getUTConfig();
        mVoWifiUtImpl = voWifiUtImpl;

        try {
            if (mVoWifiUtImpl != null) {
                mVoWifiUtImpl.setListener(mImsUtListener);
                mVoWifiUtImpl.setListenerEx(mImsUtListenerExBinder);
            }

        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    private int onConfigurationFailed(Bundle bundle) {
        int action = -1;
        int queryOnVoLTEId = INVALID_ID;
        if (bundle != null) {
            action = bundle.getInt(EXTRA_ACTION, -1);
            log("onConfigurationFailed action = " + action);
            switch (action) {
            case ACTION_QUERY_CB:
                queryOnVoLTEId = queryCallBarring(bundle);
                break;
            case ACTION_QUERY_CF:
                queryOnVoLTEId = queryCallForward(bundle);
                break;
            case ACTION_QUERY_CW:
                queryOnVoLTEId = queryCallWaiting(bundle);
                break;
            case ACTION_QUERY_CLIR:
                queryOnVoLTEId = queryCLIR(bundle);
                break;
            case ACTION_QUERY_CLIP:
                queryOnVoLTEId = queryCLIP(bundle);
                break;
            case ACTION_QUERY_COLR:
                queryOnVoLTEId = queryCOLR(bundle);
                break;
            case ACTION_QUERY_COLP:
                queryOnVoLTEId = queryCOLP(bundle);
                break;
            case ACTION_UPDATE_CB:
                queryOnVoLTEId = updateCallBarring(bundle);
                break;
            case ACTION_UPDATE_CF:
                queryOnVoLTEId = updateCallForward(bundle);
                break;
            case ACTION_UPDATE_CW:
                queryOnVoLTEId = updateCallWaiting(bundle);
                break;
            case ACTION_UPDATE_CLIR:
                queryOnVoLTEId = updateCLIR(bundle);
                break;
            case ACTION_QUERY_CF_EX:
                queryOnVoLTEId = getCallForwardingOption(bundle);
                break;
            case ACTION_UPDATE_CF_EX:
                queryOnVoLTEId = setCallForwardingOption(bundle);
                break;
            case ACTION_UPDATE_CB_EX:
                queryOnVoLTEId = setFacilityLock(bundle);
                break;
            case ACTION_QUERY_CB_EX:
                queryOnVoLTEId = queryFacilityLock(bundle);
                break;
            case ACTION_CHANGE_CB_PW:
                queryOnVoLTEId = changeBarringPassword(bundle);
                break;
            default:
                break;
            }
        }
        return queryOnVoLTEId;
    }

    private int queryCallBarring(Bundle bundle) {
        int id = INVALID_ID;
        int facility = bundle.getInt(EXTRA_FACILITY, 0);
        id = mVoLTEUtImpl.queryCallBarring(facility);
        return id;
    }
    private int queryCallForward(Bundle bundle) {
        int id = INVALID_ID;
        int condition = bundle.getInt(EXTRA_CFREASON, 0);
        String number = bundle.getString(EXTRA_DIALING_NUM, "");
        id = mVoLTEUtImpl.queryCallForward(condition, number);
        return id;
    }
    private int queryCallWaiting(Bundle bundle) {
        int id = INVALID_ID;
        id = mVoLTEUtImpl.queryCallWaiting();
        return id;
    }
    private int queryCLIR(Bundle bundle) {
        int id = INVALID_ID;
        id = mVoLTEUtImpl.queryCLIR();
        return id;
    }
    private int queryCLIP(Bundle bundle) {
        int id = INVALID_ID;
        id = mVoLTEUtImpl.queryCLIP();
        return id;
    }
    private int queryCOLR(Bundle bundle) {
        int id = INVALID_ID;
        id = mVoLTEUtImpl.queryCOLR();
        return id;
    }
    private int queryCOLP(Bundle bundle) {
        int id = INVALID_ID;
        id = mVoLTEUtImpl.queryCOLP();
        return id;
    }
    private int updateCallBarring(Bundle bundle) {
        int id = INVALID_ID;
        int cbType =  bundle.getInt(EXTRA_FACILITY, 0);
        int action = bundle.getInt(EXTRA_LOCK_STATE, 0);
        String barrString = bundle.getString(EXTRA_BARR_LIST, "");
        String[] barrList = null;
        if (!barrString.isEmpty()){
            barrList = barrString.split(",");
        }
        id = mVoLTEUtImpl.updateCallBarring(cbType, action, barrList);
        return id;
    }
    private int updateCallForward(Bundle bundle) {
        int id = INVALID_ID;
        int action = bundle.getInt(EXTRA_CFACTION, -1);
        int condition = bundle.getInt(EXTRA_CFREASON, -1);
        int serviceClass = bundle.getInt(EXTRA_SERVICE_CLASS, -1);
        String number = bundle.getString(EXTRA_DIALING_NUM, "");
        int timeSeconds = bundle.getInt(EXTRA_TIMER_SECONDS, -1);
        id = mVoLTEUtImpl.updateCallForward(action, condition, number,
                serviceClass, timeSeconds);
        return id;
    }
    private int updateCallWaiting(Bundle bundle) {
        int id = INVALID_ID;
        boolean enable = bundle.getBoolean(EXTRA_LOCK_STATE, false);
        int serviceClass = bundle.getInt(EXTRA_SERVICE_CLASS, CommandsInterface.SERVICE_CLASS_VOICE);
        id = mVoLTEUtImpl.updateCallWaiting(enable, serviceClass);
        return id;
    }
    private int updateCLIR(Bundle bundle) {
        int id = INVALID_ID;
        int clirMode = bundle.getInt(EXTRA_CLIR_MODE, -1);
        id = mVoLTEUtImpl.updateCLIR(clirMode);
        return id;
    }
    private int getCallForwardingOption(Bundle bundle) {
        int id = INVALID_ID;
        int commandInterfaceCFReason = bundle.getInt(EXTRA_CFREASON, -1);
        int serviceClass = bundle.getInt(EXTRA_SERVICE_CLASS, CommandsInterface.SERVICE_CLASS_VOICE);
        String ruleSet = bundle.getString(EXTRA_RULE_SET, "");
        id = mVoLTEUtImpl.getCallForwardingOption( commandInterfaceCFReason,
                serviceClass, ruleSet);
        return id;
    }
    private int setCallForwardingOption(Bundle bundle) {
        int id = INVALID_ID;
        int commandInterfaceCFReason = bundle.getInt(EXTRA_CFREASON, -1);
        int commandInterfaceCFAction = bundle.getInt(EXTRA_CFACTION, -1);
        int serviceClass = bundle.getInt(EXTRA_SERVICE_CLASS, CommandsInterface.SERVICE_CLASS_NONE);
        int timerSeconds = bundle.getInt(EXTRA_TIMER_SECONDS, -1);
        String dialingNumber = bundle.getString(EXTRA_DIALING_NUM, "");
        String ruleSet = bundle.getString(EXTRA_RULE_SET, "");
        id = mVoLTEUtImpl.setCallForwardingOption(commandInterfaceCFAction, commandInterfaceCFReason,
                serviceClass, dialingNumber, timerSeconds, ruleSet);
        return id;
    }
    private int setFacilityLock(Bundle bundle) {
        int id = INVALID_ID;
        int serviceClass = bundle.getInt(EXTRA_SERVICE_CLASS, CommandsInterface.SERVICE_CLASS_VOICE);
        String facility = bundle.getString(EXTRA_FACILITY, "");
        String password = bundle.getString(EXTRA_PASSWORD, "");
        boolean lockState = bundle.getBoolean(EXTRA_LOCK_STATE, false);
        id = mVoLTEUtImpl.setFacilityLock(facility,
                lockState, password, serviceClass);
        return id;
    }
    private int queryFacilityLock(Bundle bundle) {
        int id = INVALID_ID;
        int serviceClass = bundle.getInt(EXTRA_SERVICE_CLASS, CommandsInterface.SERVICE_CLASS_VOICE);
        String facility = bundle.getString(EXTRA_FACILITY, "");
        String password = bundle.getString(EXTRA_PASSWORD, "");
        id = mVoLTEUtImpl.queryFacilityLock(facility,
                password, serviceClass);
        return id;
    }
    private int changeBarringPassword(Bundle bundle) {
        int id = INVALID_ID;
        String facility = bundle.getString(EXTRA_FACILITY, "");
        String oldPwd = bundle.getString(EXTRA_OLD_PASSWORD, "");
        String newPwd = bundle.getString(EXTRA_PASSWORD, "");
        id = mVoLTEUtImpl.changeBarringPassword( facility,
                oldPwd, newPwd);
        return id;
    }

    /**
     * A listener type for the result of the supplementary service configuration.
     */
    private final IImsUtListener.Stub mImsUtListener = new IImsUtListener.Stub() {
        /**
         * Notifies the result of the supplementary service configuration udpate.
         */
        @Override
        public void utConfigurationUpdated(IImsUt ut, int id) {
            Integer resultId = mQueryOnVoLTEId.remove(id);
            if (resultId != null) {
                id = resultId;
            }
            try {
                mListener.utConfigurationUpdated(ut, id);
                mPendingMap.remove(id);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void utConfigurationUpdateFailed(IImsUt ut, int id, ImsReasonInfo error) {
            if (id > VOWIFI_QUERY_ID && mQueryOnVoLTE) {
                Bundle bundle = mPendingMap.get(id);
                int queryOnVoLTEId =  onConfigurationFailed(bundle);
                mQueryOnVoLTEId.put(queryOnVoLTEId, id);
            } else {
                Integer resultId = mQueryOnVoLTEId.remove(id);
                if (resultId != null) {
                    id = resultId;
                }
                try {
                    mListener.utConfigurationQueryFailed(ut, id, error);
                    mPendingMap.remove(id);
                } catch (RemoteException e) {
                    e.printStackTrace();
                }
            }
        }

        /**
         * Notifies the result of the supplementary service configuration query.
         */
        @Override
        public void utConfigurationQueried(IImsUt ut, int id, Bundle ssInfo) {
            Integer resultId = mQueryOnVoLTEId.remove(id);
            if (resultId != null) {
                id = resultId;
            }
            try {
                mListener.utConfigurationQueried(ut, id, ssInfo);
                mPendingMap.remove(id);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void utConfigurationQueryFailed(IImsUt ut, int id, ImsReasonInfo error) {
            if (id > VOWIFI_QUERY_ID && mQueryOnVoLTE) {
                Bundle bundle = mPendingMap.get(id);
                int queryOnVoLTEId =  onConfigurationFailed(bundle);
                mQueryOnVoLTEId.put(queryOnVoLTEId, id);
            } else {
                Integer resultId = mQueryOnVoLTEId.remove(id);
                if (resultId != null) {
                    id = resultId;
                }
                try {
                    mListener.utConfigurationQueryFailed(ut, id, error);
                    mPendingMap.remove(id);
                } catch (RemoteException e) {
                    e.printStackTrace();
                }
            }
        }

        /**
         * Notifies the status of the call barring supplementary service.
         */
        @Override
        public void utConfigurationCallBarringQueried(IImsUt ut,
                int id, ImsSsInfo[] cbInfo) {
            Integer resultId = mQueryOnVoLTEId.remove(id);
            if (resultId != null) {
                id = resultId;
            }
            try {
                mListener.utConfigurationCallBarringQueried(ut, id, cbInfo);
                mPendingMap.remove(id);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }

        /**
         * Notifies the status of the call forwarding supplementary service.
         */
        @Override
        public void utConfigurationCallForwardQueried(IImsUt ut,
                int id, ImsCallForwardInfo[] cfInfo) {
            Integer resultId = mQueryOnVoLTEId.remove(id);
            if (resultId != null) {
                id = resultId;
            }
            try {
                mListener.utConfigurationCallForwardQueried(ut, id, cfInfo);
                mPendingMap.remove(id);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }

        /**
         * Notifies the status of the call waiting supplementary service.
         */
        @Override
        public void utConfigurationCallWaitingQueried(IImsUt ut,
                int id, ImsSsInfo[] cwInfo) {
            Integer resultId = mQueryOnVoLTEId.remove(id);
            if (resultId != null) {
                id = resultId;
            }
            try {
                mListener.utConfigurationCallWaitingQueried(ut, id, cwInfo);
                mPendingMap.remove(id);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }

        /**
         * AndroidP start@{:
         * Notifies client when Supplementary Service indication is received
         */
        @Override
        public void onSupplementaryServiceIndication(ImsSsData ssData) {
            //TODO:
        }
    };

    private final IImsUtListenerEx.Stub mImsUtListenerExBinder = new IImsUtListenerEx.Stub(){
        /**
         * Notifies the result of the supplementary service configuration udpate.
         */
        @Override
        public void utConfigurationUpdated(IImsUt ut, int id) {
            Integer resultId = mQueryOnVoLTEId.remove(id);
            if (resultId != null) {
                id = resultId;
            }
            try {
                mListenerEx.utConfigurationUpdated(ut, id);
                mPendingMap.remove(id);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void utConfigurationUpdateFailed(IImsUt ut, int id, ImsReasonInfo error) {
            if (id > VOWIFI_QUERY_ID && mQueryOnVoLTE) {
                Bundle bundle = mPendingMap.get(id);
                int queryOnVoLTEId =  onConfigurationFailed(bundle);
                mQueryOnVoLTEId.put(queryOnVoLTEId, id);
            } else {
                Integer resultId = mQueryOnVoLTEId.remove(id);
                if (resultId != null) {
                    id = resultId;
                }
                try {
                    mListenerEx.utConfigurationUpdateFailed(ut, id, error);
                    mPendingMap.remove(id);
                } catch (RemoteException e) {
                    e.printStackTrace();
                }
            }
        }

        /**
         * Notifies the result of the supplementary service configuration query.
         */
        @Override
        public void utConfigurationQueried(IImsUt ut, int id, Bundle ssInfo) {
            Integer resultId = mQueryOnVoLTEId.remove(id);
            if (resultId != null) {
                id = resultId;
            }
            try {
                mListenerEx.utConfigurationQueried(ut, id, ssInfo);
                mPendingMap.remove(id);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void utConfigurationQueryFailed(IImsUt ut, int id, ImsReasonInfo error) {
            if (id > VOWIFI_QUERY_ID && mQueryOnVoLTE) {
                Bundle bundle = mPendingMap.get(id);
                int queryOnVoLTEId =  onConfigurationFailed(bundle);
                mQueryOnVoLTEId.put(queryOnVoLTEId, id);
            } else {
                Integer resultId = mQueryOnVoLTEId.remove(id);
                if (resultId != null) {
                    id = resultId;
                }
                try {
                    mListenerEx.utConfigurationQueryFailed(ut, id, error);
                    mPendingMap.remove(id);
                } catch (RemoteException e) {
                    e.printStackTrace();
                }
            }
        }

        /**
         * Notifies the status of the call barring supplementary service.
         */
        @Override
        public void utConfigurationCallBarringQueried(IImsUt ut,
                int id, ImsSsInfo[] cbInfo) {
            Integer resultId = mQueryOnVoLTEId.remove(id);
            if (resultId != null) {
                id = resultId;
            }
            try {
                mListenerEx.utConfigurationCallBarringQueried(ut, id, cbInfo);
                mPendingMap.remove(id);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }

        /**
         * Notifies the status of the call forwarding supplementary service.
         */
        @Override
        public void utConfigurationCallForwardQueried(IImsUt ut,
                int id, ImsCallForwardInfoEx[] cfInfo) {
            Integer resultId = mQueryOnVoLTEId.remove(id);
            if (resultId != null) {
                id = resultId;
            }
            try {
                mListenerEx.utConfigurationCallForwardQueried(ut, id, cfInfo);
                mPendingMap.remove(id);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }

        /**
         * Notifies the status of the call waiting supplementary service.
         */
        @Override
        public void utConfigurationCallWaitingQueried(IImsUt ut,
                int id, ImsSsInfo[] cwInfo) {

            Integer resultId = mQueryOnVoLTEId.remove(id);
            if (resultId != null) {
                id = resultId;
            }
            try {
                mListenerEx.utConfigurationCallWaitingQueried(ut, id, cwInfo);
                mPendingMap.remove(id);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }

        /**
         * Notifies the status of the call barring supplementary service.
         */
        @Override
        public void utConfigurationCallBarringFailed(int id, int[] result, int errorCode) {
            if (id > VOWIFI_QUERY_ID && mQueryOnVoLTE) {
                Bundle bundle = mPendingMap.get(id);
                int queryOnVoLTEId =  onConfigurationFailed(bundle);
                mQueryOnVoLTEId.put(queryOnVoLTEId, id);
            } else {
                Integer resultId = mQueryOnVoLTEId.remove(id);
                if (resultId != null) {
                    id = resultId;
                }
                try {
                    mListenerEx.utConfigurationCallBarringFailed(id, result, errorCode);
                    mPendingMap.remove(id);
                } catch (RemoteException e) {
                    e.printStackTrace();
                }
            }
        }

        /**
         * Notifies the status of the call barring supplementary service.
         */
        @Override
        public void utConfigurationCallBarringResult(int id, int[] result) {
            Integer resultId = mQueryOnVoLTEId.remove(id);
            if (resultId != null) {
                id = resultId;
            }
            try {
                mListenerEx.utConfigurationCallBarringResult(id, result);
                mPendingMap.remove(id);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
    };

    private void log(String log) {
        Log.d(TAG, "["+mServiceId+"]:"+log);
    }
}
