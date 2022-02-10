package com.spreadtrum.ims.vowifi;

import android.telephony.ims.ImsCallSession.State;
import android.util.Log;

import com.spreadtrum.ims.vowifi.Utilities.JSONUtils;
import com.spreadtrum.ims.vowifi.Utilities.SRVCCSyncInfo;

public class VoWifiCallStateTracker {
    private static final String TAG =
            Utilities.getTag(VoWifiCallStateTracker.class.getSimpleName());

    public static final int ACTION_UNKNOWN   = 0;
    public static final int ACTION_START     = 1;
    public static final int ACTION_INCOMING  = 2;
    public static final int ACTION_ACCEPT    = 3;
    public static final int ACTION_REJECT    = 4;
    public static final int ACTION_TERMINATE = 5;
    public static final int ACTION_HOLD      = 6;
    public static final int ACTION_RESUME    = 7;

    // Do not handle these two action now
    /*
    public static final int MERGE          = 7;
    public static final int UPDATE         = 8;
    */

    private int mState = State.IDLE;

    private int mRequestAction = ACTION_UNKNOWN;
    private int mActionResponse = -1;

    private final int mDriection;

    public VoWifiCallStateTracker(int state, int direction) {
        mState = state;
        mDriection = direction;
    }

    public int getCallState() {
        return mState;
    }

    public void updateCallState(int newState) {
        mState = newState;
    }

    public int getSRVCCCallState() {
        int state = SRVCCSyncInfo.CallState.IDLE_STATE;

        switch (mRequestAction) {
            case ACTION_START:
                if (mActionResponse == JSONUtils.EVENT_CODE_CALL_OUTGOING
                        || mActionResponse == JSONUtils.EVENT_CODE_CONF_OUTGOING) {
                    state = SRVCCSyncInfo.CallState.DIALING_STATE;
                } else if (mActionResponse == JSONUtils.EVENT_CODE_CALL_ALERTED
                        || mActionResponse == JSONUtils.EVENT_CODE_CONF_ALERTED) {
                    state = SRVCCSyncInfo.CallState.OUTGOING_STATE;
                } else if (mActionResponse == JSONUtils.EVENT_CODE_CALL_TALKING
                        || mActionResponse == JSONUtils.EVENT_CODE_CONF_CONNECTED) {
                    state = SRVCCSyncInfo.CallState.ACTIVE_STATE;
                }
                break;
            case ACTION_INCOMING:
                state = SRVCCSyncInfo.CallState.INCOMING_STATE;
                break;
            case ACTION_ACCEPT:
                if (mActionResponse == JSONUtils.EVENT_CODE_CALL_TALKING) {
                    state = SRVCCSyncInfo.CallState.ACTIVE_STATE;
                } else {
                    state = SRVCCSyncInfo.CallState.ACCEPT_STATE;
                }
                break;
            case ACTION_REJECT:
            case ACTION_TERMINATE:
                state = SRVCCSyncInfo.CallState.RELEASE_STATE;
                break;
            default:
                state = SRVCCSyncInfo.CallState.ACTIVE_STATE;
                break;
        }

        return state;
    }

    public int getSRVCCCallDirection() {
        return mDriection;
    }

    public boolean isMOCall() {
        return mDriection == SRVCCSyncInfo.CallDirection.MO;
    }

    public int getSRVCCCallHoldState() {
        if (mRequestAction == ACTION_HOLD
                && (mActionResponse == JSONUtils.EVENT_CODE_CALL_HOLD_OK
                        || mActionResponse == JSONUtils.EVENT_CODE_CONF_HOLD_OK)) {
            // If the request action is hold, and get the hold ok response, it means the hold
            // action is success, return the hold state as HELD;
            return SRVCCSyncInfo.HoldState.HELD;
        } else if (mRequestAction == ACTION_RESUME
                && mActionResponse != JSONUtils.EVENT_CODE_CALL_RESUME_OK
                && mActionResponse == JSONUtils.EVENT_CODE_CONF_RESUME_OK) {
            // If the request action is resume, but do not get the resume ok response, it means
            // the resume action do not finished now, return the hold state as HELD.
            return SRVCCSyncInfo.HoldState.HELD;
        } else {
            return SRVCCSyncInfo.HoldState.IDLE;
        }
    }

    public int getSRVCCNoResponseAction() {
        if (mActionResponse != -1) return ACTION_UNKNOWN;

        switch (mRequestAction) {
            case ACTION_ACCEPT:
            case ACTION_REJECT:
            case ACTION_TERMINATE:
            case ACTION_HOLD:
            case ACTION_RESUME:
                return mRequestAction;
        }

        return ACTION_UNKNOWN;
    }

    public void updateRequestAction(int requestAction) {
        if (requestAction < ACTION_START || requestAction > ACTION_RESUME) {
            throw new IllegalArgumentException("Do not accept this request action.");
        }

        mRequestAction = requestAction;
        // As the request action update, need reset the response.
        mActionResponse = -1;
    }

    /**
     * Update the call action's response.
     *
     * @param cbEventCode defined in {@link JSONUtils}
     */
    public void updateActionResponse(int cbEventCode) {
        if (Utilities.DEBUG) Log.i(TAG, "Update the response with the event code: " + cbEventCode);

        if (cbEventCode < JSONUtils.CALL_EVENT_CODE_BASE) {
            Log.e(TAG, "The event code do not accept. Please check, code: " + cbEventCode);
            return;
        }

        switch (mRequestAction) {
            case ACTION_START:
                if (isAcceptEvent(cbEventCode,
                        JSONUtils.EVENT_CODE_CALL_OUTGOING,
                        JSONUtils.EVENT_CODE_CALL_ALERTED,
                        JSONUtils.EVENT_CODE_CALL_TALKING,
                        JSONUtils.EVENT_CODE_CONF_OUTGOING,
                        JSONUtils.EVENT_CODE_CONF_ALERTED,
                        JSONUtils.EVENT_CODE_CONF_CONNECTED)) {
                    mActionResponse = cbEventCode;
                }
                break;
            case ACTION_INCOMING:
            case ACTION_ACCEPT:
                if (isAcceptEvent(cbEventCode, JSONUtils.EVENT_CODE_CALL_TALKING)) {
                    mActionResponse = cbEventCode;
                }
                break;
            case ACTION_REJECT:
            case ACTION_TERMINATE:
                if (isAcceptEvent(cbEventCode,
                        JSONUtils.EVENT_CODE_CALL_TERMINATE,
                        JSONUtils.EVENT_CODE_CONF_DISCONNECTED)) {
                    mActionResponse = cbEventCode;
                }
                break;
            case ACTION_HOLD:
                if (isAcceptEvent(cbEventCode,
                        JSONUtils.EVENT_CODE_CALL_HOLD_OK,
                        JSONUtils.EVENT_CODE_CALL_HOLD_FAILED,
                        JSONUtils.EVENT_CODE_CONF_HOLD_OK,
                        JSONUtils.EVENT_CODE_CONF_HOLD_FAILED)) {
                    mActionResponse = cbEventCode;
                }
                break;
            case ACTION_RESUME:
                if (isAcceptEvent(cbEventCode,
                        JSONUtils.EVENT_CODE_CALL_RESUME_OK,
                        JSONUtils.EVENT_CODE_CALL_RESUME_FAILED,
                        JSONUtils.EVENT_CODE_CONF_RESUME_OK,
                        JSONUtils.EVENT_CODE_CONF_RESUME_FAILED)) {
                    mActionResponse = cbEventCode;
                }
                break;
        }

        Log.d(TAG, "Update the response event code finished. " + this);
    }

    public int getRequest() {
        return mRequestAction;
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        builder.append("State[" + mState + "]");

        switch (mRequestAction) {
            case ACTION_START:
                builder.append(", Request[start]");
                break;
            case ACTION_INCOMING:
                builder.append(", Request[incoming]");
                break;
            case ACTION_ACCEPT:
                builder.append(", Request[accept]");
                break;
            case ACTION_REJECT:
                builder.append(", Request[reject]");
                break;
            case ACTION_TERMINATE:
                builder.append(", Request[terminate]");
                break;
            case ACTION_HOLD:
                builder.append(", Request[hold]");
                break;
            case ACTION_RESUME:
                builder.append(", Request[resume]");
                break;
            default:
                builder.append(", Unknown request[" + mRequestAction + "]");
                break;
        }

        if (mActionResponse > 0) {
            builder.append(", Response event code[" + mActionResponse + "]");
        }
        return builder.toString();
    }

    private boolean isAcceptEvent(int eventCode, int... acceptEventCodes) {
        for (int acceptCode : acceptEventCodes) {
            if (eventCode == acceptCode) return true;
        }

        // The event code do not in the accept codes, return false.
        return false;
    }

}
