package com.spreadtrum.ims.ut;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;

import android.content.Context;
import android.os.AsyncResult;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.PersistableBundle;
import android.os.RemoteException;
import android.telephony.CarrierConfigManager;
import android.telephony.CarrierConfigManagerEx;
import android.util.Log;
import com.android.ims.internal.IImsServiceEx;
import com.android.ims.internal.ImsManagerEx;
import com.android.internal.telephony.CallForwardInfo;
import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.CommandException;
import com.android.internal.telephony.CommandException.Error;
import com.android.internal.telephony.imsphone.ImsPhoneMmiCode;

import com.android.ims.ImsUtInterface;
import com.android.ims.internal.IImsUt;
import com.android.ims.internal.IImsUtListener;
import android.telephony.ims.ImsReasonInfo;
import android.telephony.ims.ImsSsInfo;
import android.telephony.ims.ImsCallForwardInfo;

import com.android.ims.internal.ImsCallForwardInfoEx;
import com.android.ims.internal.IImsUtListenerEx;
import com.spreadtrum.ims.ImsServiceImpl;
import com.spreadtrum.ims.ImsRadioInterface;
import com.android.internal.telephony.Phone;
import android.telephony.RadioAccessFamily;
import vendor.sprd.hardware.radio.V1_0.CallForwardInfoUri;
import com.android.internal.telephony.dataconnection.DcNetworkManager;
import android.os.Bundle;
import android.os.SystemProperties;
import android.telephony.SubscriptionManager;
import static com.android.internal.telephony.CommandsInterface.CF_ACTION_DISABLE;
import static com.android.internal.telephony.CommandsInterface.CF_ACTION_ENABLE;
import static com.android.internal.telephony.CommandsInterface.CF_ACTION_ERASURE;
import static com.android.internal.telephony.CommandsInterface.CF_ACTION_REGISTRATION;
import static com.android.internal.telephony.CommandsInterface.CF_REASON_ALL;
import static com.android.internal.telephony.CommandsInterface.CF_REASON_ALL_CONDITIONAL;
import static com.android.internal.telephony.CommandsInterface.CF_REASON_NO_REPLY;
import static com.android.internal.telephony.CommandsInterface.CF_REASON_NOT_REACHABLE;
import static com.android.internal.telephony.CommandsInterface.CF_REASON_BUSY;
import static com.android.internal.telephony.CommandsInterface.CF_REASON_UNCONDITIONAL;
import static com.android.internal.telephony.CommandsInterface.SERVICE_CLASS_VOICE;
import static com.android.internal.telephony.CommandsInterface.SERVICE_CLASS_NONE;

public class ImsUtImpl extends IImsUt.Stub {
    private static final String TAG = ImsUtImpl.class.getSimpleName();

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
    private static final int ACTION_GET_CLIR_STATUS  = 21;
    private static final int ACTION_GET_CW_STATUS_FOR_VOWIFI  = 22;
    private static final int ACTION_QUERY_ROOT_NODE = 23;

    private static final int EVENT_REQUEST_NETWORK_DONE = 100;

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
    private static final String EXTRA_RESULT = "result";
    private static final String EXTRA_ENABLE = "enable";
    private static final String EXTRA_UT_ENABLE = "utEnable";
    private static final String CB_PASSWORD_DEFAULT_VALUTE = "0000";
    private static final String SS_SERVICE_CLASS_PROP = "gsm.ss.sc";

    private Phone mPhone;
    private ImsRadioInterface mCi;
    private ImsHandler mHandler;
    private Context mContext;
    private ImsServiceImpl mImsServiceImpl;
    private IImsUtListener mImsUtListener;
    private IImsUtListenerEx mImsUtListenerEx;
    private int mRequestId = -1;
    private Object mLock = new Object();
    private DcNetworkManager mDcNetworkManager = null;
    private List<Integer> mRequestedNetwork = new ArrayList<Integer>();
    public ImsUtImpl(ImsRadioInterface ci,Context context, Phone phone, ImsServiceImpl service){
        mCi = ci;
        mContext = context;
        mHandler = new ImsHandler(mContext.getMainLooper(), (IImsUt)this);
        mPhone = phone;
        mDcNetworkManager = new DcNetworkManager(mContext);
        mImsServiceImpl = service;
    }

    /**
     * Used to listen to events.
     */
    private class ImsHandler extends Handler {
        public IImsUt mIImsUt;
        ImsHandler(Looper looper, IImsUt ut) {
            super(looper);
            mIImsUt = ut;
        }
        @Override
        public void handleMessage(Message msg) {
            Log.i(TAG,"handleMessage msg=" + msg);
            AsyncResult ar = (AsyncResult) msg.obj;
            switch (msg.what) {
                case ACTION_QUERY_CB:
                    try {
                        if(ar != null){

                            if (ar.exception != null) {
                                int info = ImsReasonInfo.CODE_UT_NETWORK_ERROR;
                                if (ar.exception instanceof CommandException) {
                                    info = getImsReasonInfoFromCommandException((CommandException) ar.exception);
                                }
                                mImsUtListener.utConfigurationQueryFailed((IImsUt)ar.userObj,
                                        msg.arg1,
                                        new ImsReasonInfo(info, 0));
                            } else {
                                int infoArray[] = (int[]) ar.result;
                                if(infoArray.length == 0 || ar.userObj instanceof Throwable){
                                    mImsUtListener.utConfigurationQueryFailed((IImsUt)ar.userObj,
                                            msg.arg1,
                                            new ImsReasonInfo(ImsReasonInfo.CODE_UT_OPERATION_NOT_ALLOWED, 0));
                                } else {
                                    ImsSsInfo ssInfos[] = new ImsSsInfo[1];
                                    ImsSsInfo ssInfo = new ImsSsInfo();
                                    ssInfo.mStatus = infoArray[0];
                                    ssInfos[0] = ssInfo;
                                    mImsUtListener.utConfigurationCallBarringQueried(
                                            (IImsUt)ar.userObj, msg.arg1, ssInfos);
                                    Log.i(TAG,"ACTION_QUERY_CB->ssInfo:" + Arrays.toString(ssInfos));
                                }
                            }
                        } else {
                            mImsUtListener.utConfigurationQueryFailed((IImsUt)this,
                                    msg.arg1,
                                    new ImsReasonInfo(ImsReasonInfo.CODE_UT_NETWORK_ERROR, 0));
                        }
                    } catch(RemoteException e){
                        e.printStackTrace();
                    }
                    releaseNetwork(msg.arg2);
                    break;
                case ACTION_QUERY_CF:
                    try {
                        if(ar != null){

                            if (ar.exception != null) {
                                int info = ImsReasonInfo.CODE_UT_NETWORK_ERROR;
                                if (ar.exception instanceof CommandException) {
                                    info = getImsReasonInfoFromCommandException((CommandException) ar.exception);
                                }
                                mImsUtListener.utConfigurationQueryFailed((IImsUt)ar.userObj,
                                        msg.arg1,
                                        new ImsReasonInfo(info, 0));
                            } else {
                                CallForwardInfo infos[] = (CallForwardInfo[]) ar.result;
                                if(infos.length == 0 || ar.userObj instanceof Throwable){
                                    mImsUtListener.utConfigurationQueryFailed((IImsUt)ar.userObj,
                                            msg.arg1,
                                            new ImsReasonInfo(ImsReasonInfo.CODE_UT_OPERATION_NOT_ALLOWED, 0));
                                } else {
                                    CallForwardInfo cfInfo;
                                    ImsCallForwardInfo callForwardInfo;
                                    ImsCallForwardInfo[] callForwardInfoList
                                    = new ImsCallForwardInfo[infos.length];
                                    for (int i = 0; i < infos.length; i++) {
                                        cfInfo = infos[i];
                                        callForwardInfo = new ImsCallForwardInfo();
                                        callForwardInfo.mStatus = cfInfo.status;
                                        callForwardInfo.mCondition = cfInfo.reason;
                                        callForwardInfo.mToA = cfInfo.toa;
                                        callForwardInfo.mNumber = cfInfo.number;
                                        callForwardInfo.mTimeSeconds = cfInfo.timeSeconds;
                                        callForwardInfo.mServiceClass = cfInfo.serviceClass;
                                        callForwardInfoList[i] = callForwardInfo;
                                        Log.i(TAG,"ACTION_QUERY_CF->callForwardInfo:" + callForwardInfo.toString());
                                    }

                                    mImsUtListener.utConfigurationCallForwardQueried(
                                            (IImsUt)ar.userObj, msg.arg1, callForwardInfoList);
                                    Log.i(TAG,"ACTION_QUERY_CF->callForwardInfoList:" + callForwardInfoList);
                                }
                            }
                        } else {
                            mImsUtListener.utConfigurationQueryFailed((IImsUt)this,
                                    msg.arg1,
                                    new ImsReasonInfo(ImsReasonInfo.CODE_UT_NETWORK_ERROR, 0));
                        }
                    } catch(RemoteException e){
                        e.printStackTrace();
                    }
                    releaseNetwork(msg.arg2);
                    break;
                case ACTION_QUERY_CW:
                    try {
                        if(ar != null){

                            if (ar.exception != null) {
                                int info = ImsReasonInfo.CODE_UT_NETWORK_ERROR;
                                if (ar.exception instanceof CommandException) {
                                    info = getImsReasonInfoFromCommandException((CommandException) ar.exception);
                                }
                                mImsUtListener.utConfigurationQueryFailed((IImsUt)ar.userObj,
                                        msg.arg1,
                                        new ImsReasonInfo(info, 0));
                            } else {
                                int infoArray[] = (int[]) ar.result;
                                if(infoArray.length == 0 || ar.userObj instanceof Throwable){
                                    mImsUtListener.utConfigurationQueryFailed((IImsUt)ar.userObj,
                                            msg.arg1,
                                            new ImsReasonInfo(ImsReasonInfo.CODE_UT_OPERATION_NOT_ALLOWED, 0));
                                } else {
                                    ImsSsInfo ssInfos[] = new ImsSsInfo[infoArray.length];
                                    for (int i = 0; i < infoArray.length; i++) {
                                        ssInfos[i] = new ImsSsInfo();
                                        ssInfos[i].mStatus = infoArray[i];
                                    }
                                    mImsUtListener.utConfigurationCallWaitingQueried(
                                            (IImsUt) ar.userObj, msg.arg1, ssInfos);
                                    Log.i(TAG, "ACTION_QUERY_CW->ssInfos:" + Arrays.toString(ssInfos));
                                }
                            }
                        } else {
                            mImsUtListener.utConfigurationQueryFailed((IImsUt)this,
                                    msg.arg1,
                                    new ImsReasonInfo(ImsReasonInfo.CODE_UT_NETWORK_ERROR, 0));
                        }
                    } catch(RemoteException e){
                        e.printStackTrace();
                    }
                    releaseNetwork(msg.arg2);
                    break;
                case ACTION_QUERY_CLIR:
                    try {
                        if(ar != null){
                            if (ar.exception != null) {
                                int info = ImsReasonInfo.CODE_UT_NETWORK_ERROR;
                                if (ar.exception instanceof CommandException) {
                                    info = getImsReasonInfoFromCommandException((CommandException) ar.exception);
                                }
                                mImsUtListener.utConfigurationQueryFailed((IImsUt)ar.userObj,
                                        msg.arg1,
                                        new ImsReasonInfo(info, 0));
                            } else {
                                int[] clirResp = (int[]) ar.result;
                                Bundle clirInfo = new Bundle();
                                clirInfo.putIntArray(ImsPhoneMmiCode.UT_BUNDLE_KEY_CLIR, clirResp);
                                Log.d(TAG, "Calling success callback for Query CLIR.");
                                mImsUtListener.utConfigurationQueried((IImsUt) ar.userObj, msg.arg1,
                                        clirInfo);
                                Log.i(TAG,"ACTION_QUERY_CLIR->clirInfo:" + clirInfo);
                            }
                        } else {
                            mImsUtListener.utConfigurationQueryFailed((IImsUt)this,
                                    msg.arg1,
                                    new ImsReasonInfo(ImsReasonInfo.CODE_UT_NETWORK_ERROR, 0));
                        }
                        updateCLIRStatus(ar);
                    } catch(RemoteException e){
                        e.printStackTrace();
                    }
                    releaseNetwork(msg.arg2);
                    break;
                case ACTION_QUERY_CLIP:
                    processQueryResult(msg);
                    releaseNetwork(msg.arg2);
                    break;
                case ACTION_QUERY_COLR:
                    processQueryResult(msg);
                    releaseNetwork(msg.arg2);
                    break;
                case ACTION_QUERY_COLP:
                    processQueryResult(msg);
                    releaseNetwork(msg.arg2);
                    break;
                case ACTION_TRANSACT:
                    break;
                case ACTION_UPDATE_CB:
                    try {
                        if(ar != null){
                            if (ar.exception != null) {
                                int info = ImsReasonInfo.CODE_UT_NETWORK_ERROR;
                                if (ar.exception instanceof CommandException) {
                                    info = getImsReasonInfoFromCommandException((CommandException) ar.exception);
                                }
                                mImsUtListener.utConfigurationUpdateFailed((IImsUt)ar.userObj,
                                        msg.arg1,
                                        new ImsReasonInfo(info, 0));
                            } else {
                                mImsUtListener.utConfigurationUpdated((IImsUt)ar.userObj, msg.arg1);
                                Log.i(TAG,"ACTION_UPDATE_CB->success!");
                            }
                        } else {
                            mImsUtListener.utConfigurationUpdateFailed((IImsUt)this, msg.arg1,
                                    new ImsReasonInfo(ImsReasonInfo.CODE_UT_NETWORK_ERROR, 0));
                        }
                    } catch(RemoteException e){
                        e.printStackTrace();
                    }
                    releaseNetwork(msg.arg2);
                    break;
                case ACTION_UPDATE_CF:
                    try {
                        if(ar != null){
                            if (ar.exception != null) {
                                int info = ImsReasonInfo.CODE_UT_NETWORK_ERROR;
                                if (ar.exception instanceof CommandException) {
                                    info = getImsReasonInfoFromCommandException((CommandException) ar.exception);
                                }
                                mImsUtListener.utConfigurationUpdateFailed((IImsUt)ar.userObj,
                                        msg.arg1,
                                        new ImsReasonInfo(info, 0));
                            } else {
                                mImsUtListener.utConfigurationUpdated((IImsUt)ar.userObj, msg.arg1);
                                Log.i(TAG,"ACTION_UPDATE_CF->success!");
                            }
                        } else {
                            mImsUtListener.utConfigurationUpdateFailed((IImsUt)this, msg.arg1,
                                    new ImsReasonInfo(ImsReasonInfo.CODE_UT_NETWORK_ERROR, 0));
                        }
                    } catch(RemoteException e){
                        e.printStackTrace();
                    }
                    releaseNetwork(msg.arg2);
                    break;
                case ACTION_UPDATE_CW:
                    try {
                        if(ar != null){
                            if (ar.exception != null) {
                                int info = ImsReasonInfo.CODE_UT_NETWORK_ERROR;
                                if (ar.exception instanceof CommandException) {
                                    info = getImsReasonInfoFromCommandException((CommandException) ar.exception);
                                }
                                mImsUtListener.utConfigurationUpdateFailed((IImsUt)ar.userObj,
                                        msg.arg1,
                                        new ImsReasonInfo(info, 0));
                            } else {
                                mImsUtListener.utConfigurationUpdated((IImsUt)ar.userObj, msg.arg1);
                                Log.i(TAG,"ACTION_UPDATE_CW->success!");
                            }
                        } else {
                            mImsUtListener.utConfigurationUpdateFailed((IImsUt)this, msg.arg1,
                                    new ImsReasonInfo(ImsReasonInfo.CODE_UT_NETWORK_ERROR, 0));
                        }
                    } catch(RemoteException e){
                        e.printStackTrace();
                    }
                    releaseNetwork(msg.arg2);
                    break;
                case ACTION_UPDATE_CLIR:
                    try {
                        if(ar != null){
                            if (ar.exception != null) {
                                int info = ImsReasonInfo.CODE_UT_NETWORK_ERROR;
                                if (ar.exception instanceof CommandException) {
                                    info = getImsReasonInfoFromCommandException((CommandException) ar.exception);
                                }
                                mImsUtListener.utConfigurationUpdateFailed((IImsUt)ar.userObj,
                                        msg.arg1,
                                        new ImsReasonInfo(info, 0));
                            } else {
                                mImsUtListener.utConfigurationUpdated((IImsUt)ar.userObj, msg.arg1);
                                Log.i(TAG,"ACTION_UPDATE->success!");
                            }
                        } else {
                            mImsUtListener.utConfigurationUpdateFailed((IImsUt)this, msg.arg1,
                                    new ImsReasonInfo(ImsReasonInfo.CODE_UT_NETWORK_ERROR, 0));
                        }
                    } catch(RemoteException e){
                        e.printStackTrace();
                    }
                    releaseNetwork(msg.arg2);
                    break;
                case ACTION_UPDATE_CLIP:
                    try {
                        if(ar != null){
                            if (ar.exception != null) {
                                int info = ImsReasonInfo.CODE_UT_NETWORK_ERROR;
                                if (ar.exception instanceof CommandException) {
                                    info = getImsReasonInfoFromCommandException((CommandException) ar.exception);
                                }
                                mImsUtListener.utConfigurationUpdateFailed((IImsUt)ar.userObj,
                                        msg.arg1,
                                        new ImsReasonInfo(info, 0));
                            } else {
                                mImsUtListener.utConfigurationUpdated((IImsUt)ar.userObj, msg.arg1);
                                Log.i(TAG,"ACTION_UPDATE->success!");
                            }
                        } else {
                            mImsUtListener.utConfigurationUpdateFailed((IImsUt)this, msg.arg1,
                                    new ImsReasonInfo(ImsReasonInfo.CODE_UT_NETWORK_ERROR, 0));
                        }
                    } catch(RemoteException e){
                        e.printStackTrace();
                    }
                    releaseNetwork(msg.arg2);
                    break;
                case ACTION_UPDATE_CLOR:
                case ACTION_UPDATE_COLP:
                    break;
                case ACTION_QUERY_CF_EX:
                    try {
                        if(ar != null){
                            if (ar.exception != null) {
                                int info = ImsReasonInfo.CODE_UT_NETWORK_ERROR;
                                //add for unisoc 1008518,1095032
                                if (ar.exception instanceof com.android.sprd.telephony.CommandException) {
                                    info = getImsReasonInfoFromSprdCommandException((com.android.sprd.telephony.CommandException) ar.exception);
                                }
                                mImsUtListenerEx.utConfigurationQueryFailed((IImsUt)ar.userObj,
                                        msg.arg1,
                                        new ImsReasonInfo(info, 0));
                            } else {
                                ImsCallForwardInfoEx infos[] = responseCallForwardInfo((ArrayList<CallForwardInfoUri>) ar.result) ;
                                if(ar.userObj instanceof Throwable){
                                    mImsUtListenerEx.utConfigurationQueryFailed((IImsUt)ar.userObj,
                                            msg.arg1,
                                            new ImsReasonInfo(ImsReasonInfo.CODE_UT_OPERATION_NOT_ALLOWED, 0));
                                } else {
                                    if(infos != null){
                                        for (int i = 0, s = infos.length; i < s; i++) {
                                            if ((infos[i].mCondition == ImsUtInterface.CDIV_CF_UNCONDITIONAL)
                                                    && ((infos[i].mServiceClass & CommandsInterface.SERVICE_CLASS_VOICE) != 0)) {
                                                mPhone.setVoiceCallForwardingFlag(1, (infos[i].mStatus == 1),
                                                        infos[i].mNumber);
                                                Log.i(TAG,"ACTION_QUERY_CF_EX->setVoiceCallForwardingFlag:"
                                                        +(infos[i].mStatus == 1));
                                            }
                                        }
                                    }
                                    mImsUtListenerEx.utConfigurationCallForwardQueried(
                                            (IImsUt)ar.userObj, msg.arg1, infos);
                                }
                                Log.i(TAG,"ACTION_QUERY_CF_EX->infos:"+infos+" id:"+msg.arg1);
                            }
                        } else {
                            mImsUtListenerEx.utConfigurationQueryFailed((IImsUt)this,
                                    msg.arg1,
                                    new ImsReasonInfo(ImsReasonInfo.CODE_UT_NETWORK_ERROR, 0));
                        }
                    } catch(RemoteException e){
                        e.printStackTrace();
                    }
                    if (mRequestedNetwork.remove(Integer.valueOf(ACTION_QUERY_CF_EX))) {
                        releaseNetwork(msg.arg2);
                    }
                    break;
                case ACTION_UPDATE_CF_EX:
                    try {
                        if(ar != null && !(ar.userObj instanceof Throwable)){
                            Cf cf = (Cf)ar.userObj;
                            if (ar.exception != null) {
                                int info = ImsReasonInfo.CODE_UT_NETWORK_ERROR;
                                if (ar.exception instanceof CommandException) {
                                    info = getImsReasonInfoFromCommandException((CommandException) ar.exception);
                                }
                                mImsUtListenerEx.utConfigurationUpdateFailed(mIImsUt,
                                        msg.arg1,
                                        new ImsReasonInfo(info, 0));
                            } else {
                                if(cf != null && cf.mIsCfu &&
                                        ((cf.mServiceClass & CommandsInterface.SERVICE_CLASS_VOICE) != 0)){
                                    mPhone.setVoiceCallForwardingFlag(1, cf.mIsCfEnable, cf.mSetCfNumber);
                                    Log.i(TAG,"ACTION_UPDATE_CF_EX->setVoiceCallForwardingFlag:"
                                            +cf.mIsCfEnable);
                                }
                                mImsUtListenerEx.utConfigurationUpdated(mIImsUt, msg.arg1);
                                Log.i(TAG,"ACTION_UPDATE_CF_EX->success,id:"+msg.arg1);
                            }
                        } else {
                            mImsUtListenerEx.utConfigurationUpdateFailed(mIImsUt, msg.arg1,
                                    new ImsReasonInfo(ImsReasonInfo.CODE_UT_NETWORK_ERROR, 0));
                        }
                    } catch(RemoteException e){
                        e.printStackTrace();
                        // SPRD bug 645393 add null avoid
                    } catch (NullPointerException e) {
                        e.printStackTrace();
                        Log.i(TAG,"nullpointerException" + e.getMessage());
                    }
                    if (mRequestedNetwork.remove(Integer.valueOf(ACTION_UPDATE_CF_EX))) {
                        releaseNetwork(msg.arg2);
                    }
                    break;
                case EVENT_REQUEST_NETWORK_DONE:
                    Bundle bundle = msg.getData();
                    onRequestNetworkDone(bundle);
                    break;
                case ACTION_UPDATE_CB_EX:
                    try {
                        if (ar != null) {
                            if (ar.exception != null) {
                                Error error = Error.GENERIC_FAILURE;
                                if (ar.exception instanceof CommandException) {
                                    error = ((CommandException) ar.exception).getCommandError();
                                }
                                Log.i(TAG, "error.ordinal() = " + error.ordinal());
                                mImsUtListenerEx.utConfigurationCallBarringFailed(msg.arg1, null, error.ordinal());
                            } else {
                                if (ar.result != null) {
                                    mImsUtListenerEx.utConfigurationCallBarringResult(msg.arg1,
                                            (int[])ar.result);
                                } else {
                                    mImsUtListenerEx.utConfigurationCallBarringResult(msg.arg1, null);
                                }
                            }
                        } else {
                            mImsUtListenerEx.utConfigurationCallBarringFailed(msg.arg1, null, Error.GENERIC_FAILURE.ordinal());
                        }
                    } catch (RemoteException e) {
                        e.printStackTrace();
                    }
                    releaseNetwork(msg.arg2);
                    break;
                case ACTION_QUERY_CB_EX:
                    try {
                        if (ar != null) {
                            if (ar.exception != null) {
                                Error error = Error.GENERIC_FAILURE;
                                if (ar.exception instanceof CommandException) {
                                    error = ((CommandException) ar.exception).getCommandError();
                                }
                                Log.i(TAG, "error.ordinal() = " + error.ordinal());
                                mImsUtListenerEx.utConfigurationCallBarringFailed(msg.arg1, null, error.ordinal());
                            } else {
                                if (ar.result != null) {
                                    mImsUtListenerEx.utConfigurationCallBarringResult(msg.arg1,
                                            (int[])ar.result);
                                } else {
                                    mImsUtListenerEx.utConfigurationCallBarringResult(msg.arg1, null);
                                }
                            }
                        } else {
                            mImsUtListenerEx.utConfigurationCallBarringFailed(msg.arg1, null, Error.GENERIC_FAILURE.ordinal());
                        }
                    } catch (RemoteException e) {
                        e.printStackTrace();
                    }
                    releaseNetwork(msg.arg2);
                    break;
                case ACTION_CHANGE_CB_PW:
                    try {
                        if (ar != null) {
                            if (ar.exception != null) {
                                Error error = Error.GENERIC_FAILURE;
                                if (ar.exception instanceof CommandException) {
                                    error = ((CommandException) ar.exception).getCommandError();
                                }
                                Log.i(TAG, "error.ordinal() = " + error.ordinal());
                                mImsUtListenerEx.utConfigurationCallBarringFailed(msg.arg1, null, error.ordinal());
                            } else {
                                if (ar.result != null) {
                                    mImsUtListenerEx.utConfigurationCallBarringResult(msg.arg1,
                                            (int[])ar.result);
                                } else {
                                    mImsUtListenerEx.utConfigurationCallBarringResult(msg.arg1, null);
                                }
                            }
                        } else {
                            mImsUtListenerEx.utConfigurationCallBarringFailed(msg.arg1, null, Error.GENERIC_FAILURE.ordinal());
                        }
                    } catch (RemoteException e) {
                        e.printStackTrace();
                    }
                    releaseNetwork(msg.arg2);
                    break;
                case ACTION_GET_CLIR_STATUS:
                    updateCLIRStatus(ar);
                    releaseNetwork(msg.arg2);
                    break;
                case ACTION_GET_CW_STATUS_FOR_VOWIFI:
                    if(ar != null){
                        if (ar.exception != null) {
                            int info = ImsReasonInfo.CODE_UT_NETWORK_ERROR;
                            if (ar.exception instanceof CommandException) {
                                info = getImsReasonInfoFromCommandException((CommandException) ar.exception);
                            }
                        } else {
                            int infoArray[] = (int[]) ar.result;
                            if(infoArray.length == 0 || ar.userObj instanceof Throwable){
                            } else {
                                mImsServiceImpl.onCallWaitingStatusUpdateForVoWifi(infoArray[0]);
                                Log.i(TAG,"ACTION_UPDATE_CW_STATUS_FOR_WIFI->mStatus:" + infoArray[0]);
                            }
                        }
                    }
                    releaseNetwork(msg.arg2);
                    break;
                case ACTION_QUERY_ROOT_NODE:
                    try {
                        if (ar != null) {
                            if (ar.exception != null) {
                                mImsUtListenerEx.utConfigurationQueryFailed((IImsUt)ar.userObj,
                                        msg.arg1,
                                        new ImsReasonInfo(ImsReasonInfo.CODE_UT_NETWORK_ERROR, 0));
                            } else {
                                mImsUtListenerEx.utConfigurationQueried((IImsUt) ar.userObj, msg.arg1,
                                        null);
                            }
                        } else {
                            mImsUtListenerEx.utConfigurationQueryFailed((IImsUt)this,
                                    msg.arg1,
                                    new ImsReasonInfo(ImsReasonInfo.CODE_UT_NETWORK_ERROR, 0));
                        }
                    } catch (RemoteException e) {
                        e.printStackTrace();
                    }
                    releaseNetwork(msg.arg2);
                    break;
                default:
                    break;
            }
        }
    }

    /**
     * Closes the object. This object is not usable after being closed.
     */
    @Override
    public void close(){
        Log.i(TAG,"close");
        mCi = null;
        mContext = null;
        mHandler = null;
    }

    /**
     * Retrieves the configuration of the call barring.
     */
    @Override
    public int queryCallBarring(int cbType){
        int id = getReuestId();
        Bundle bundle = new Bundle();
        bundle.putInt(EXTRA_ACTION, ACTION_QUERY_CB);
        bundle.putInt(EXTRA_ID, id);
        bundle.putString(EXTRA_FACILITY, typeToString(cbType));
        bundle.putString(EXTRA_PASSWORD, "");
        bundle.putInt(EXTRA_SERVICE_CLASS, CommandsInterface.SERVICE_CLASS_VOICE);
        requestNetwork(bundle);
        return id;
    }


    /**
     * Retrieves the configuration of the call forward.
     */
    @Override
    public int queryCallForward(int condition, String number) {
        int serviceClass = getCFServiceClass() != -1 ?
                getCFServiceClass() : CommandsInterface.SERVICE_CLASS_VOICE;
        int id = getReuestId();
        Bundle bundle = new Bundle();
        bundle.putInt(EXTRA_ACTION, ACTION_QUERY_CF);
        bundle.putInt(EXTRA_ID, id);
        bundle.putInt(EXTRA_CFREASON, condition);
        bundle.putString(EXTRA_DIALING_NUM, number);
        bundle.putInt(EXTRA_SERVICE_CLASS, serviceClass);
        resetCFServiceClass();
        requestNetwork(bundle);
        return id;
    }


    /**
     * Retrieves the configuration of the call waiting.
     */
    @Override
    public int queryCallWaiting(){
        int id = getReuestId();
        Bundle bundle = new Bundle();
        bundle.putInt(EXTRA_ACTION, ACTION_QUERY_CW);
        bundle.putInt(EXTRA_ID, id);
        bundle.putInt(EXTRA_SERVICE_CLASS, CommandsInterface.SERVICE_CLASS_VOICE);
        requestNetwork(bundle);
        return id;
    }


    /**
     * Retrieves the default CLIR setting.
     */
    @Override
    public int queryCLIR(){
        int id = getReuestId();
        Bundle bundle = new Bundle();
        bundle.putInt(EXTRA_ACTION, ACTION_QUERY_CLIR);
        bundle.putInt(EXTRA_ID, id);
        requestNetwork(bundle);
        return id;
    }


    /**
     * Retrieves the CLIP call setting.
     */
    @Override
    public int queryCLIP(){
        int id = getReuestId();
        Bundle bundle = new Bundle();
        bundle.putInt(EXTRA_ACTION, ACTION_QUERY_CLIP);
        bundle.putInt(EXTRA_ID, id);
        requestNetwork(bundle);
        return id;
    }


    /**
     * Retrieves the COLR call setting.
     */
    @Override
    public int queryCOLR(){
        int id = getReuestId();
        Bundle bundle = new Bundle();
        bundle.putInt(EXTRA_ACTION, ACTION_QUERY_COLR);
        bundle.putInt(EXTRA_ID, id);
        requestNetwork(bundle);
        return id;
    }


    /**
     * Retrieves the COLP call setting.
     */
    @Override
    public int queryCOLP(){
        int id = getReuestId();
        Bundle bundle = new Bundle();
        bundle.putInt(EXTRA_ACTION, ACTION_QUERY_COLP);
        bundle.putInt(EXTRA_ID, id);
        requestNetwork(bundle);
        return id;
    }


    /**
     * Updates or retrieves the supplementary service configuration.
     */
    @Override
    public int transact(Bundle ssInfo){
        int id = getReuestId();
        return id;
    }


    /**
     * Updates the configuration of the call barring.
     */
    @Override
    public int updateCallBarring(int cbType, int action, String[] barrList){
        int id = getReuestId();
        boolean enable = false;
        if(action == CommandsInterface.CF_ACTION_ENABLE){
        	enable = true;
        }
        Bundle bundle = new Bundle();
        bundle.putInt(EXTRA_ACTION, ACTION_UPDATE_CB);
        bundle.putInt(EXTRA_ID, id);
        bundle.putString(EXTRA_FACILITY, typeToString(cbType));
        bundle.putBoolean(EXTRA_LOCK_STATE, enable);
        bundle.putString(EXTRA_PASSWORD, CB_PASSWORD_DEFAULT_VALUTE);
        bundle.putInt(EXTRA_SERVICE_CLASS, CommandsInterface.SERVICE_CLASS_VOICE);
        requestNetwork(bundle);
        return id;
    }


    /**
     * Updates the configuration of the call forward.
     */
    @Override
    public int updateCallForward(int action, int condition, String number,
            int serviceClass, int timeSeconds){
        int id = getReuestId();
        Bundle bundle = new Bundle();
        bundle.putInt(EXTRA_ACTION, ACTION_UPDATE_CF);
        bundle.putInt(EXTRA_ID, id);
        bundle.putInt(EXTRA_CFACTION, action);
        bundle.putInt(EXTRA_CFREASON, condition);
        bundle.putString(EXTRA_DIALING_NUM, number);
        bundle.putInt(EXTRA_SERVICE_CLASS, serviceClass);
        bundle.putInt(EXTRA_TIMER_SECONDS, timeSeconds);
        requestNetwork(bundle);

        return id;
    }


    /**
     * Updates the configuration of the call waiting.
     */
    @Override
    public int updateCallWaiting(boolean enable, int serviceClass){
        int id = getReuestId();
        Bundle bundle = new Bundle();
        bundle.putInt(EXTRA_ACTION, ACTION_UPDATE_CW);
        bundle.putInt(EXTRA_ID, id);
        bundle.putBoolean(EXTRA_LOCK_STATE, enable);
        //add for unisoc 1147258 add service class according to config
        bundle.putInt(EXTRA_SERVICE_CLASS, serviceClass);
        requestNetwork(bundle);
        return id;
    }


    /**
     * Updates the configuration of the CLIR supplementary service.
     */
    @Override
    public int updateCLIR(int clirMode){
        int id = getReuestId();
        Bundle bundle = new Bundle();
        bundle.putInt(EXTRA_ACTION, ACTION_UPDATE_CLIR);
        bundle.putInt(EXTRA_ID, id);
        bundle.putInt(EXTRA_CLIR_MODE, clirMode);
        requestNetwork(bundle);
        return id;
    }


    /**
     * Updates the configuration of the CLIP supplementary service.
     */
    @Override
    public int updateCLIP(boolean enable){
        int id = getReuestId();
        Bundle bundle = new Bundle();
        bundle.putInt(EXTRA_ACTION, ACTION_UPDATE_CLIP);
        bundle.putInt(EXTRA_ID, id);
        bundle.putInt(EXTRA_ENABLE, enable ? 1 : 0);
        requestNetwork(bundle);
        return id;
    }


    /**
     * Updates the configuration of the COLR supplementary service.
     */
    @Override
    public int updateCOLR(int presentation){
        int id = getReuestId();
        return id;
    }


    /**
     * Updates the configuration of the COLP supplementary service.
     */
    @Override
    public int updateCOLP(boolean enable){
        int id = getReuestId();
        return id;
    }


    /**
     * Sets the listener.
     */
    @Override
    public void setListener(IImsUtListener listener){
        Log.i(TAG,"setListener->listener:"+listener);
        mImsUtListener = listener;
    }

    /**
    * Sets the listener.
    */
    public void setListenerEx(IImsUtListenerEx listener){
        Log.i(TAG,"setListenerEx->listener:"+listener);
        mImsUtListenerEx = listener;
    }

    private int getReuestId(){
        synchronized(mLock){
            mRequestId++;
            if(mRequestId > 100){
                mRequestId = 0;
            }
            return mRequestId;
        }
    }

    private String typeToString(int reason){
        switch (reason){
            case ImsUtInterface.CB_BAOC:
                return CommandsInterface.CB_FACILITY_BAOC;
            case ImsUtInterface.CB_BOIC:
                return CommandsInterface.CB_FACILITY_BAOIC;
            case ImsUtInterface.CB_BOIC_EXHC:
                return CommandsInterface.CB_FACILITY_BAOICxH;
            case ImsUtInterface.CB_BAIC:
                return CommandsInterface.CB_FACILITY_BAIC;
            case ImsUtInterface.CB_BIC_WR:
                return CommandsInterface.CB_FACILITY_BAICr;
            case ImsUtInterface.CB_BA_ALL:
                return CommandsInterface.CB_FACILITY_BA_ALL;
            case ImsUtInterface.CB_BA_MO:
                return CommandsInterface.CB_FACILITY_BA_MO;
            case ImsUtInterface.CB_BA_MT:
                return CommandsInterface.CB_FACILITY_BA_MT;
            default:
                return CommandsInterface.CB_FACILITY_BAOC;
        }
    }

    /**
     * Retrieves the configuration of the call forward.
     */
    public int setCallForwardingOption(int commandInterfaceCFAction,
            int commandInterfaceCFReason,int serviceClass, String dialingNumber,
            int timerSeconds, String ruleSet){
        int id = getReuestId();
        Bundle bundle = new Bundle();
        bundle.putInt(EXTRA_ACTION, ACTION_UPDATE_CF_EX);
        bundle.putInt(EXTRA_ID, id);
        bundle.putInt(EXTRA_CFREASON, commandInterfaceCFReason);
        bundle.putInt(EXTRA_CFACTION, commandInterfaceCFAction);
        bundle.putInt(EXTRA_SERVICE_CLASS, serviceClass);
        bundle.putString(EXTRA_DIALING_NUM, dialingNumber);
        bundle.putInt(EXTRA_TIMER_SECONDS, timerSeconds);
        bundle.putString(EXTRA_RULE_SET, ruleSet);
        if (isSimSlotSupportLTE()) {
            mRequestedNetwork.add(Integer.valueOf(ACTION_UPDATE_CF_EX));
            requestNetwork(bundle);
        } else {
            setCallForwardingOption(bundle);
        }
        return id;
    }

    /**
     * Updates the configuration of the call forward.
     */
    public int getCallForwardingOption(int commandInterfaceCFReason, int serviceClass,
            String ruleSet){
        int id = getReuestId();
        Bundle bundle = new Bundle();
        bundle.putInt(EXTRA_ACTION, ACTION_QUERY_CF_EX);
        bundle.putInt(EXTRA_ID, id);
        bundle.putInt(EXTRA_CFREASON, commandInterfaceCFReason);
        bundle.putInt(EXTRA_SERVICE_CLASS, serviceClass);
        bundle.putString(EXTRA_RULE_SET, ruleSet);
        bundle.putString(EXTRA_DIALING_NUM, "");
        if (isSimSlotSupportLTE()) {
            mRequestedNetwork.add(Integer.valueOf(ACTION_QUERY_CF_EX));
            requestNetwork(bundle);
        } else {
            getCallForwardingOption(bundle);
        }
        return id;
    }
    private boolean isSimSlotSupportLTE() {
        if (mPhone != null) {
            int rafMax = mPhone.getRadioAccessFamily();
            return (rafMax & RadioAccessFamily.RAF_LTE) == RadioAccessFamily.RAF_LTE ||
                    (rafMax & RadioAccessFamily.RAF_LTE_CA) == RadioAccessFamily.RAF_LTE_CA;
        }
        return false;
    }
    // Create Cf (Call forward) so that dialling number &
    // mIsCfu (true if reason is call forward unconditional)
    // mOnComplete (Message object passed by client) can be packed &
    // given as a single Cf object as user data to UtInterface.
    private static class Cf {
        String mSetCfNumber;
        boolean mIsCfu;
        boolean mIsCfEnable;
        int mServiceClass;
        Cf(){
        }
    }

    private int getImsReasonInfoFromCommandException(CommandException ce){
        int info = ImsReasonInfo.CODE_UT_NETWORK_ERROR;
        if(ce != null){
            switch(ce.getCommandError()){
                case FDN_CHECK_FAILURE:
                    info = ImsReasonInfo.CODE_FDN_BLOCKED;
                    break;
                case RADIO_NOT_AVAILABLE:
                    info = ImsReasonInfo.CODE_UT_SERVICE_UNAVAILABLE;
                    break;
            }
        }
        return info;
    }
    //add for unisoc 1008518,1095032
    private int getImsReasonInfoFromSprdCommandException(com.android.sprd.telephony.CommandException ce){
        int info = ImsReasonInfo.CODE_UT_NETWORK_ERROR;
        if(ce != null){
            switch(ce.getCommandError()){
                case FDN_CHECK_FAILURE:
                    info = ImsReasonInfo.CODE_FDN_BLOCKED;
                    break;
                case RADIO_NOT_AVAILABLE:
                    info = ImsReasonInfo.CODE_UT_SERVICE_UNAVAILABLE;
                    break;
            }
        }
        return info;
    }

    public int changeBarringPassword(String facility, String oldPwd, String newPwd){
        int id = getReuestId();
        Bundle bundle = new Bundle();
        bundle.putInt(EXTRA_ACTION, ACTION_CHANGE_CB_PW);
        bundle.putInt(EXTRA_ID, id);
        bundle.putString(EXTRA_FACILITY, facility);
        bundle.putString(EXTRA_OLD_PASSWORD, oldPwd);
        bundle.putString(EXTRA_PASSWORD, newPwd);
        requestNetwork(bundle);
        return id;
    }

    public int setFacilityLock(String facility, boolean lockState, String password,
            int serviceClass){
        int id = getReuestId();
        Bundle bundle = new Bundle();
        bundle.putInt(EXTRA_ACTION, ACTION_UPDATE_CB_EX);
        bundle.putInt(EXTRA_ID, id);
        bundle.putString(EXTRA_FACILITY, facility);
        bundle.putInt(EXTRA_SERVICE_CLASS, serviceClass);
        bundle.putString(EXTRA_PASSWORD, password);
        bundle.putBoolean(EXTRA_LOCK_STATE, lockState);
        requestNetwork(bundle);
        return id;
    }

    public int queryFacilityLock(String facility, String password, int serviceClass){
        int id = getReuestId();
        Bundle bundle = new Bundle();
        bundle.putInt(EXTRA_ACTION, ACTION_QUERY_CB_EX);
        bundle.putInt(EXTRA_ID, id);
        bundle.putString(EXTRA_FACILITY, facility);
        bundle.putInt(EXTRA_SERVICE_CLASS, serviceClass);
        bundle.putString(EXTRA_PASSWORD, password);
        requestNetwork(bundle);
        return id;
    }

    public int getCLIRStatus() {
        int id = getReuestId();
        Bundle bundle = new Bundle();
        bundle.putInt(EXTRA_ACTION, ACTION_GET_CLIR_STATUS);
        bundle.putInt(EXTRA_ID, id);
        requestNetwork(bundle);
        return id;
    }

    public int getCallWaitingStatusForVoWifi() {
        int id = getReuestId();
        Bundle bundle = new Bundle();
        bundle.putInt(EXTRA_ACTION, ACTION_GET_CW_STATUS_FOR_VOWIFI);
        bundle.putInt(EXTRA_ID, id);
        requestNetwork(bundle);
        return id;
    }

    public int queryRootNode(){
        int id = getReuestId();
        Bundle bundle = new Bundle();
        bundle.putInt(EXTRA_ACTION, ACTION_QUERY_ROOT_NODE);
        bundle.putInt(EXTRA_ID, id);
        requestNetwork(bundle);
        return id;
    }

    //Unisoc add for Bug#1147915
    private boolean forceSSLocalOps(int action) {
        boolean forceSSLocalOps = false;
        CarrierConfigManager carrierConfig = (CarrierConfigManager) mContext.getSystemService(
                Context.CARRIER_CONFIG_SERVICE);

        if (carrierConfig != null) {
            PersistableBundle config = carrierConfig.getConfigForSubId(mPhone.getSubId());
            String key = null;

            if (config != null) {
                if (action == ACTION_QUERY_CW || action == ACTION_UPDATE_CW || action == ACTION_GET_CW_STATUS_FOR_VOWIFI) {
                    key = CarrierConfigManagerEx.KEY_CARRIER_REQUEST_XCAP_PDN_FOR_CW;
                } else if (action == ACTION_QUERY_CLIR || action == ACTION_UPDATE_CLIR || action == ACTION_GET_CLIR_STATUS) {
                    key = CarrierConfigManagerEx.KEY_CARRIER_REQUEST_XCAP_PDN_FOR_CLIR;
                }
                if (key != null) {
                    forceSSLocalOps = !config.getBoolean(key, true);
                }
            }
        }
        Log.d(TAG, "forceSSLocalOps(), action = " + action + ", forceSSLocalOps = " + forceSSLocalOps);
        return forceSSLocalOps;
    }

    private void requestNetwork(Bundle bundle) {
        Message request = mHandler.obtainMessage(EVENT_REQUEST_NETWORK_DONE);
        boolean isUtEnabled = isUtEnabled();
        bundle.putBoolean(EXTRA_UT_ENABLE, isUtEnabled);
        request.setData(bundle);

        //UNISOC: add for bug1015365
        if (!isUtEnabled || forceSSLocalOps(bundle.getInt(EXTRA_ACTION, -1))) {
            mHandler.sendMessage(request);
            return;
        }
        int subId = mPhone.getSubId();
        mDcNetworkManager.requestNetwork(subId, request);
    }

    private void releaseNetwork(int utEnabled) {
        if (utEnabled == 0) {
            return;
        }
        mDcNetworkManager.releaseNetworkRequest();
    }

    private void onRequestNetworkDone(Bundle bundle) {
        int action = -1;
        if (bundle != null) {
            action = bundle.getInt(EXTRA_ACTION, -1);
            Log.d(TAG, "onRequestNetworkDone action = " + action);
            switch (action) {
            case ACTION_QUERY_CB:
                queryCallBarring(bundle);
                break;
            case ACTION_QUERY_CF:
                queryCallForward(bundle);
                break;
            case ACTION_QUERY_CW:
                queryCallWaiting(bundle);
                break;
            case ACTION_QUERY_CLIR:
                queryCLIR(bundle);
                break;
            case ACTION_QUERY_CLIP:
                queryCLIP(bundle);
                break;
            case ACTION_QUERY_COLR:
                queryCOLR(bundle);
                break;
            case ACTION_QUERY_COLP:
                queryCOLP(bundle);
                break;
            case ACTION_TRANSACT:
                break;
            case ACTION_UPDATE_CB:
                updateCallBarring(bundle);
                break;
            case ACTION_UPDATE_CF:
                updateCallForward(bundle);
                break;
            case ACTION_UPDATE_CW:
                updateCallWaiting(bundle);
                break;
            case ACTION_UPDATE_CLIR:
                updateCLIR(bundle);
                break;
            case ACTION_UPDATE_CLIP:
                updateCLIP(bundle);
                break;
            case ACTION_UPDATE_CLOR:
                break;
            case ACTION_UPDATE_COLP:
                break;
            case ACTION_QUERY_CF_EX:
                getCallForwardingOption(bundle);
                break;
            case ACTION_UPDATE_CF_EX:
                setCallForwardingOption(bundle);
                break;
            case ACTION_UPDATE_CB_EX:
                setFacilityLock(bundle);
                break;
            case ACTION_QUERY_CB_EX:
                queryFacilityLock(bundle);
                break;
            case ACTION_CHANGE_CB_PW:
                changeBarringPassword(bundle);
                break;
            case ACTION_GET_CLIR_STATUS:
                getCLIRStatus(bundle);
                break;
            case ACTION_GET_CW_STATUS_FOR_VOWIFI:
                queryCallWaitingForVoWifi(bundle);
                break;
             case ACTION_QUERY_ROOT_NODE:
                 queryRootNode(bundle);
                break;
            default:
                break;
            }
        }
    }

    private void queryCallBarring(Bundle bundle) {
        Log.d(TAG, "onexcue queryCallBarring = " + bundle.toString());
        int id = bundle.getInt(EXTRA_ID, -1);
        String facility = bundle.getString(EXTRA_FACILITY, "");
        String password = bundle.getString(EXTRA_PASSWORD, "");
        int serviceClass = bundle.getInt(EXTRA_SERVICE_CLASS, CommandsInterface.SERVICE_CLASS_VOICE);
        boolean utEnabled = bundle.getBoolean(EXTRA_UT_ENABLE, true);
        mCi.queryFacilityLock(facility, password, serviceClass,
                mHandler.obtainMessage(ACTION_QUERY_CB, id, utEnabled ? 1 : 0, this));
    }

    private void queryCallForward(Bundle bundle) {
        Log.d(TAG, "onexcue queryCallForward = " + bundle.toString());
        int id = bundle.getInt(EXTRA_ID, -1);
        int condition = bundle.getInt(EXTRA_CFREASON, -1);
        String number = bundle.getString(EXTRA_DIALING_NUM, "");
        int serviceClass = bundle.getInt(EXTRA_SERVICE_CLASS, CommandsInterface.SERVICE_CLASS_VOICE);
        boolean utEnabled = bundle.getBoolean(EXTRA_UT_ENABLE, true);
        mCi.queryCallForwardStatus(condition, serviceClass, number,
                mHandler.obtainMessage(ACTION_QUERY_CF, id, utEnabled ? 1 : 0, this));
    }

    private void queryCallWaiting(Bundle bundle) {
        Log.d(TAG, "onexcue queryCallWaiting = " + bundle.toString());
        int id = bundle.getInt(EXTRA_ID, -1);
        int serviceClass = bundle.getInt(EXTRA_SERVICE_CLASS, CommandsInterface.SERVICE_CLASS_VOICE);
        boolean utEnabled = bundle.getBoolean(EXTRA_UT_ENABLE, true);
        mCi.queryCallWaiting(serviceClass,
                mHandler.obtainMessage(ACTION_QUERY_CW, id, utEnabled ? 1 : 0, this));
    }

    private void queryCLIR(Bundle bundle) {
        Log.d(TAG, "onexcue queryCLIR = " + bundle.toString());
        int id = bundle.getInt(EXTRA_ID, -1);
        boolean utEnabled = bundle.getBoolean(EXTRA_UT_ENABLE, true);
        mCi.getCLIR(mHandler.obtainMessage(ACTION_QUERY_CLIR, id, utEnabled ? 1 : 0, this));
    }

    private void queryCLIP(Bundle bundle) {
        Log.d(TAG, "onexcue queryCLIP = " + bundle.toString());
        int id = bundle.getInt(EXTRA_ID, -1);
        boolean utEnabled = bundle.getBoolean(EXTRA_UT_ENABLE, true);
        mCi.queryCLIP(mHandler.obtainMessage(ACTION_QUERY_CLIP, id, utEnabled ? 1 : 0, this));
    }

    private void queryCOLP(Bundle bundle) {
        Log.d(TAG, "onexcue queryCOLP = " + bundle.toString());
        int id = bundle.getInt(EXTRA_ID, -1);
        boolean utEnabled = bundle.getBoolean(EXTRA_UT_ENABLE, true);
        mCi.queryCOLP(mHandler.obtainMessage(ACTION_QUERY_COLP, id, utEnabled ? 1 : 0, this));
    }

    private void queryCOLR(Bundle bundle) {
        Log.d(TAG, "onexcue queryCOLR = " + bundle.toString());
        int id = bundle.getInt(EXTRA_ID, -1);
        boolean utEnabled = bundle.getBoolean(EXTRA_UT_ENABLE, true);
        mCi.queryCOLR(mHandler.obtainMessage(ACTION_QUERY_COLR, id, utEnabled ? 1 : 0, this));
    }

    private void updateCallBarring(Bundle bundle) {
        Log.d(TAG, "onexcue updateCallBarring = " + bundle.toString());
        int id = bundle.getInt(EXTRA_ID, -1);
        String facility = bundle.getString(EXTRA_FACILITY, "");
        boolean lockState = bundle.getBoolean(EXTRA_LOCK_STATE, false);
        String password = bundle.getString(EXTRA_PASSWORD, "");
        int serviceClass = bundle.getInt(EXTRA_SERVICE_CLASS, CommandsInterface.SERVICE_CLASS_VOICE);
        boolean utEnabled = bundle.getBoolean(EXTRA_UT_ENABLE, true);
        mCi.setFacilityLock (facility, lockState, password, serviceClass,
                mHandler.obtainMessage(ACTION_UPDATE_CB, id, utEnabled ? 1 : 0, this));
    }

    private void updateCallForward(Bundle bundle) {
        Log.d(TAG, "onexcue updateCallForward = " + bundle.toString());
        int id = bundle.getInt(EXTRA_ID, -1);
        int action = bundle.getInt(EXTRA_CFACTION, -1);
        int condition = bundle.getInt(EXTRA_CFREASON, -1);
        int serviceClass = bundle.getInt(EXTRA_SERVICE_CLASS, -1);
        String number = bundle.getString(EXTRA_DIALING_NUM, "");
        int timeSeconds = bundle.getInt(EXTRA_TIMER_SECONDS, -1);
        boolean utEnabled = bundle.getBoolean(EXTRA_UT_ENABLE, true);
        mCi.setCallForward(action,
                condition,
                serviceClass,
                number,
                timeSeconds,
                mHandler.obtainMessage(ACTION_UPDATE_CF, id, utEnabled ? 1 : 0, this));
    }

    private void updateCallWaiting(Bundle bundle) {
        Log.d(TAG, "onexcue updateCallWaiting = " + bundle.toString());
        int id = bundle.getInt(EXTRA_ID, -1);
        boolean enable = bundle.getBoolean(EXTRA_LOCK_STATE, false);
        int serviceClass = bundle.getInt(EXTRA_SERVICE_CLASS, CommandsInterface.SERVICE_CLASS_VOICE);
        boolean utEnabled = bundle.getBoolean(EXTRA_UT_ENABLE, true);
        mCi.setCallWaiting(enable, serviceClass,
                mHandler.obtainMessage(ACTION_UPDATE_CW, id, utEnabled ? 1 : 0, this));
    }

    private void updateCLIR(Bundle bundle){
        Log.d(TAG, "onexcue updateCLIR = " + bundle.toString());
        int id = bundle.getInt(EXTRA_ID, -1);
        int clirMode = bundle.getInt(EXTRA_CLIR_MODE, -1);
        boolean utEnabled = bundle.getBoolean(EXTRA_UT_ENABLE, true);
        mCi.setCLIR(clirMode,
                mHandler.obtainMessage(ACTION_UPDATE_CLIR, id, utEnabled ? 1 : 0, this));
    }

    private void setCallForwardingOption(Bundle bundle) {
        Log.d(TAG, "onexcue setCallForwardingOption = " + bundle.toString());
        Cf cf = new Cf();
        int id = bundle.getInt(EXTRA_ID, -1);
        int commandInterfaceCFReason = bundle.getInt(EXTRA_CFREASON, -1);
        int commandInterfaceCFAction = bundle.getInt(EXTRA_CFACTION, -1);
        int serviceClass = bundle.getInt(EXTRA_SERVICE_CLASS, CommandsInterface.SERVICE_CLASS_NONE);
        int timerSeconds = bundle.getInt(EXTRA_TIMER_SECONDS, -1);
        String dialingNumber = bundle.getString(EXTRA_DIALING_NUM, "");
        String ruleSet = bundle.getString(EXTRA_RULE_SET, "");
        boolean utEnabled = bundle.getBoolean(EXTRA_UT_ENABLE, true);
        if(commandInterfaceCFReason == CF_REASON_UNCONDITIONAL){
            if((commandInterfaceCFAction == CF_ACTION_ENABLE)
                    || commandInterfaceCFAction == CF_ACTION_REGISTRATION){
                cf.mIsCfEnable = true;
            } else {
                cf.mIsCfEnable = false;
            }
            cf.mIsCfu = true;
        } else {
            cf.mIsCfu = false;
        }
        cf.mSetCfNumber = dialingNumber;
        cf.mServiceClass = serviceClass;
        mCi.setCallForward(commandInterfaceCFAction, commandInterfaceCFReason, serviceClass,
                dialingNumber, timerSeconds, ruleSet,
                mHandler.obtainMessage(ACTION_UPDATE_CF_EX, id, utEnabled ? 1 : 0,cf));
    }

    private void getCallForwardingOption(Bundle bundle) {
        Log.d(TAG, "onexcue getCallForwardingOption = " + bundle.toString());
        int id = bundle.getInt(EXTRA_ID, -1);
        int commandInterfaceCFReason = bundle.getInt(EXTRA_CFREASON, -1);
        int serviceClass = bundle.getInt(EXTRA_SERVICE_CLASS, CommandsInterface.SERVICE_CLASS_VOICE);
        String ruleSet = bundle.getString(EXTRA_RULE_SET, "");
        String number = bundle.getString(EXTRA_DIALING_NUM, "");
        boolean utEnabled = bundle.getBoolean(EXTRA_UT_ENABLE, true);
        mCi.queryCallForwardStatus(commandInterfaceCFReason, serviceClass,
                number, ruleSet, mHandler.obtainMessage(ACTION_QUERY_CF_EX, id, utEnabled ? 1 : 0, this));
    }

    private void changeBarringPassword(Bundle bundle) {
        Log.d(TAG, "onexcue changeBarringPassword = " + bundle.toString());
        int id = bundle.getInt(EXTRA_ID, -1);
        String facility = bundle.getString(EXTRA_FACILITY, "");
        String oldPwd = bundle.getString(EXTRA_OLD_PASSWORD, "");
        String newPwd = bundle.getString(EXTRA_PASSWORD, "");
        boolean utEnabled = bundle.getBoolean(EXTRA_UT_ENABLE, true);
        mCi.changeBarringPassword(facility, oldPwd, newPwd,
                mHandler.obtainMessage(ACTION_CHANGE_CB_PW, id, utEnabled ? 1 : 0, this));
    }

    private void setFacilityLock(Bundle bundle) {
        Log.d(TAG, "onexcue setFacilityLock = " + bundle.toString());
        int id = bundle.getInt(EXTRA_ID, -1);
        int serviceClass = bundle.getInt(EXTRA_SERVICE_CLASS, CommandsInterface.SERVICE_CLASS_VOICE);
        String facility = bundle.getString(EXTRA_FACILITY, "");
        String password = bundle.getString(EXTRA_PASSWORD, "");
        boolean lockState = bundle.getBoolean(EXTRA_LOCK_STATE, false);
        boolean utEnabled = bundle.getBoolean(EXTRA_UT_ENABLE, true);
        mCi.setFacilityLock(facility, lockState, password,
                serviceClass, mHandler.obtainMessage(ACTION_UPDATE_CB_EX, id, utEnabled ? 1 : 0, this));
    }

    private void queryFacilityLock(Bundle bundle) {
        Log.d(TAG, "onexcue queryFacilityLock = " + bundle.toString());
        int id = bundle.getInt(EXTRA_ID, -1);
        int serviceClass = bundle.getInt(EXTRA_SERVICE_CLASS, CommandsInterface.SERVICE_CLASS_VOICE);
        String facility = bundle.getString(EXTRA_FACILITY, "");
        String password = bundle.getString(EXTRA_PASSWORD, "");
        boolean utEnabled = bundle.getBoolean(EXTRA_UT_ENABLE, true);
        mCi.queryFacilityLockForAppExt(facility, password, serviceClass,
                mHandler.obtainMessage(ACTION_QUERY_CB_EX, id, utEnabled ? 1 : 0, this));
    }
    private void getCLIRStatus(Bundle bundle) {
        Log.d(TAG, "onexcue getCLIRStatus = " + bundle.toString());
        int id = bundle.getInt(EXTRA_ID, -1);
        boolean utEnabled = bundle.getBoolean(EXTRA_UT_ENABLE, true);
        mCi.getCLIR(mHandler.obtainMessage(ACTION_GET_CLIR_STATUS, id, utEnabled ? 1 : 0, this));
    }

    private void updateCLIRStatus(AsyncResult ar) {
        IImsServiceEx imsServiceEx = ImsManagerEx.getIImsServiceEx();
        int action = ImsUtInterface.OIR_PRESENTATION_NOT_RESTRICTED;
        Log.d(TAG, "updateCLIRStatus");
        try {
            if(ar != null){
                if (ar.exception == null) {
                    int[] clirResp = (int[]) ar.result;
                    Log.i(TAG,"ACTION_QUERY_CLIR->clirResp:" + clirResp.length);
                    if (clirResp.length >= 2) {
                        Log.i(TAG,"ACTION_QUERY_CLIR->clirResp[0]:" + clirResp[0] + ", clirResp[1]" + clirResp[1]);
                        if (clirResp[0] == 1 && clirResp[1] == 4) {
                            action = ImsUtInterface.OIR_PRESENTATION_RESTRICTED;
                        }
                    }
                }
            }
            if (imsServiceEx != null) {
                imsServiceEx.updateCLIRStatus(action);
            }
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }
    private void updateCLIP(Bundle bundle) {
        Log.d(TAG, "onexcue updateCLIP = " + bundle.toString());
        int id = bundle.getInt(EXTRA_ID, -1);
        int enable = bundle.getInt(EXTRA_ENABLE, 0);
        boolean utEnabled = bundle.getBoolean(EXTRA_UT_ENABLE, true);
        mCi.updateCLIP(enable, mHandler.obtainMessage(ACTION_UPDATE_CLIP, id, utEnabled ? 1 : 0, this));
    }
    private void processQueryResult(Message msg) {
        AsyncResult ar = (AsyncResult) msg.obj;
        try {
            if(ar != null){
                if (ar.exception != null || ar.result == null) {
                    int info = ImsReasonInfo.CODE_UT_NETWORK_ERROR;
                    if (ar.exception instanceof CommandException) {
                        info = getImsReasonInfoFromCommandException((CommandException) ar.exception);
                    }
                    mImsUtListener.utConfigurationQueryFailed((IImsUt)ar.userObj,
                            msg.arg1,
                            new ImsReasonInfo(info, 0));
                } else {
                    int[] result = (int[]) ar.result;
                    Bundle bundle = new Bundle();
                    ImsSsInfo ssinfo = new ImsSsInfo();
                    ssinfo.mStatus = result[0];
                    bundle.putParcelable(ImsPhoneMmiCode.UT_BUNDLE_KEY_SSINFO, ssinfo);
                    mImsUtListener.utConfigurationQueried((IImsUt) ar.userObj, msg.arg1,
                            bundle);
                    Log.i(TAG,"ACTION_QUERY bundle = " + bundle);
                }
            } else {
                mImsUtListener.utConfigurationQueryFailed((IImsUt)this,
                        msg.arg1,
                        new ImsReasonInfo(ImsReasonInfo.CODE_UT_NETWORK_ERROR, 0));
            }
        } catch(RemoteException e){
            e.printStackTrace();
        }
    }

    private void queryCallWaitingForVoWifi(Bundle bundle) {
        Log.d(TAG, "onexcue queryCallWaiting = " + bundle.toString());
        int id = bundle.getInt(EXTRA_ID, -1);
        int serviceClass = bundle.getInt(EXTRA_SERVICE_CLASS, CommandsInterface.SERVICE_CLASS_VOICE);
        boolean utEnabled = bundle.getBoolean(EXTRA_UT_ENABLE, true);
        mCi.queryCallWaiting(serviceClass,
                mHandler.obtainMessage(ACTION_GET_CW_STATUS_FOR_VOWIFI, id, utEnabled ? 1 : 0, this));
    }

    private void queryRootNode(Bundle bundle) {
        Log.d(TAG, "onexcue queryRootNode = " + bundle.toString());
        int id = bundle.getInt(EXTRA_ID, -1);
        boolean utEnabled = bundle.getBoolean(EXTRA_UT_ENABLE, true);
        mCi.queryRootNode(mHandler.obtainMessage(ACTION_QUERY_ROOT_NODE, id, utEnabled ? 1 : 0, this));
    }

    public boolean isUtEnabled() {
        boolean isUtEnable = true;
        if (!(mPhone.isRadioOn())) {
            return false;
        }

        if(mImsServiceImpl != null){
            isUtEnable = mImsServiceImpl.isUtEnable();
        }
        Log.d(TAG, "isUtEnable isUtEnable = " + isUtEnable);
        return isUtEnable;
    }

    /**
     * AndroidP start@{:
     */
    @Override
    public int updateCallBarringForServiceClass(int cbType, int action,
                                                String[] barrList, int serviceClass) throws RemoteException {
        int id = getReuestId();
        boolean enable = false;
        if(action == CommandsInterface.CF_ACTION_ENABLE){
            enable = true;
        }
        Bundle bundle = new Bundle();
        bundle.putInt(EXTRA_ACTION, ACTION_UPDATE_CB);
        bundle.putInt(EXTRA_ID, id);
        bundle.putString(EXTRA_FACILITY, typeToString(cbType));
        bundle.putBoolean(EXTRA_LOCK_STATE, enable);
        bundle.putString(EXTRA_PASSWORD, CB_PASSWORD_DEFAULT_VALUTE);
        bundle.putInt(EXTRA_SERVICE_CLASS, serviceClass);
        requestNetwork(bundle);
        return id;
    }

    @Override
    public int queryCallBarringForServiceClass(int cbType, int serviceClass){
        int id = getReuestId();
        Bundle bundle = new Bundle();
        bundle.putInt(EXTRA_ACTION, ACTION_QUERY_CB);
        bundle.putInt(EXTRA_ID, id);
        bundle.putString(EXTRA_FACILITY, typeToString(cbType));
        bundle.putString(EXTRA_PASSWORD, "");
        bundle.putInt(EXTRA_SERVICE_CLASS, serviceClass);
        requestNetwork(bundle);
        return id;
    }

    private ImsCallForwardInfoEx[] responseCallForwardInfo(ArrayList<CallForwardInfoUri> callForwardInfos) {
        ImsCallForwardInfoEx[] ret = new ImsCallForwardInfoEx[callForwardInfos.size()];
        for (int i = 0; i < callForwardInfos.size(); i++) {
            ret[i] = new ImsCallForwardInfoEx();
            ret[i].mStatus = callForwardInfos.get(i).status;
            ret[i].mCondition = callForwardInfos.get(i).reason;
            ret[i].mNumberType = callForwardInfos.get(i).numberType;
            ret[i].mToA = callForwardInfos.get(i).ton;
            ret[i].mNumber = callForwardInfos.get(i).number;
            ret[i].mServiceClass = callForwardInfos.get(i).serviceClass;
            ret[i].mRuleset = callForwardInfos.get(i).ruleset;
            ret[i].mTimeSeconds = callForwardInfos.get(i).timeSeconds;
        }
        return ret;
    }

    /* AndroidP end@} */
    //add for unisoc 1528747
    private static int getCFServiceClass() {
        return Integer.parseInt(SystemProperties.get(SS_SERVICE_CLASS_PROP, "-1"));
    }

    //add for unisoc 1528747
    private static void resetCFServiceClass() {
        SystemProperties.set(SS_SERVICE_CLASS_PROP, "-1");
    }
}
