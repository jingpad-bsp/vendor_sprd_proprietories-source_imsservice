package com.spreadtrum.ims;


import android.telephony.Rlog;
import android.util.Log;

import com.android.internal.telephony.ATParseEx;
import com.android.internal.telephony.ATResponseParser;
import com.android.internal.telephony.DriverCall;
import com.android.internal.telephony.UUSInfo;
import com.android.internal.telephony.PhoneConstants;
import android.telephony.ims.ImsConferenceState;

import android.telephony.PhoneNumberUtils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class  ImsDriverCall extends DriverCall{
    static final String LOG_TAG =   ImsDriverCall.class.getSimpleName();
    static final int PRESENTATION_INVALID         = 0;
    static final int PRESENTATION_VALID         = 1;
    static final int PRESENTATION_REQUEST       = 2;
    static final int PRESENTATION_ACCEPT       = 3;
    static final int PRESENTATION_REJECT       = 4;

    public static final int STATE_ACTIVE         = 0;
    public static final int STATE_HOLDING        = 1;
    public static final int STATE_DIALING        = 2;
    public static final int STATE_ALERTING       = 3;
    public static final int STATE_INCOMING       = 4;
    public static final int STATE_WAITING        = 5;
    public static final int STATE_DISCONNECTED   = 6;
    public static final int VIDEO_CALL_MEDIA_DIRECTION_INVALID = 1000;
    public static final int VIDEO_CALL_MEDIA_DIRECTION_SENDRECV = VIDEO_CALL_MEDIA_DIRECTION_INVALID + 1;// "m=video\a=sendrecv" or "m=video"
    public static final int VIDEO_CALL_MEDIA_DIRECTION_SENDONLY = VIDEO_CALL_MEDIA_DIRECTION_INVALID + 2;// "m=video\a=sendonly"
    public static final int VIDEO_CALL_MEDIA_DIRECTION_RECVONLY = VIDEO_CALL_MEDIA_DIRECTION_INVALID + 3;// "m=video\a=recvonly"

    public ImsDriverCall(){

    }

    public boolean isRequestUpgrade(){
        return (negStatus == PRESENTATION_REQUEST && mediaDescription != null && mediaDescription.contains("video"));
    }

    public boolean isRequestDowngradeToVoice(){
        return (negStatus == PRESENTATION_REQUEST && mediaDescription != null && mediaDescription.contains("audio"));
    }

    public boolean isReuestAccept(){
        return (negStatus == PRESENTATION_ACCEPT);
    }

    public boolean isReuestReject(){
        return (negStatus == PRESENTATION_REJECT);
    }

    public boolean isVideoCall(){
        return !isVoice;
    }

    public int getVideoCallMediaDirection() {
        if(mediaDescription == null || !mediaDescription.contains("video")){
            Log.d(LOG_TAG,"mediaDescription = "+mediaDescription);
            return VIDEO_CALL_MEDIA_DIRECTION_INVALID;
        }

        String videoCallMediaDirection = null;
        Pattern p = Pattern.compile("a=.*?(only|sendrecv)");
        Matcher m = p.matcher(mediaDescription);
        while (m.find()) {
            videoCallMediaDirection = m.group();
            break;
        }

        Log.d(LOG_TAG,"videoCallMediaDirection = "+videoCallMediaDirection);
        if (videoCallMediaDirection == null || videoCallMediaDirection.contains("sendrecv")) {
            return VIDEO_CALL_MEDIA_DIRECTION_SENDRECV;
        } else if (videoCallMediaDirection.contains("sendonly")) {
            return VIDEO_CALL_MEDIA_DIRECTION_SENDONLY;
        } else if (videoCallMediaDirection.contains("recvonly")) {
            return VIDEO_CALL_MEDIA_DIRECTION_RECVONLY;
        } else {
            return VIDEO_CALL_MEDIA_DIRECTION_INVALID;
        }
    }

    public ImsDriverCall(ImsDriverCall dc) {
        index = dc.index;
        isMT =dc.isMT;
        state = dc.state;
        isMpty = dc.isMpty;
        mptyState = dc.mptyState;
        number = dc.number;
        TOA = dc.TOA;
        isVoice = dc.isVoice;
        isVoicePrivacy = dc.isVoicePrivacy;
        als = dc.als;
        numberPresentation = dc.numberPresentation;
        name = dc.name;
        namePresentation = dc.namePresentation;
        uusInfo = dc.uusInfo;
        negStatusPresent = dc.negStatusPresent;
        negStatus = dc.negStatus;
        mediaDescription = dc.mediaDescription;
        csMode = dc.csMode;
        numberType = dc.numberType;
        prioritypresent = dc.prioritypresent;
        priority = dc.priority;
        cliValidityPresent = dc.cliValidityPresent;
    }

    public enum State {
        ACTIVE,
        HOLDING,
        DIALING,    // MO call only
        ALERTING,   // MO call only
        INCOMING,   // MT call only
        WAITING,    // MT call only
        DISCONNECTED;
        // If you add a state, make sure to look for the switch()
        // statements that use this enum
    }

    public int index;
    public boolean isMT;
    public State state;     // May be null if unavail
    public boolean isMpty;
    public int mptyState;
    public String number;
    public int TOA;
    public boolean isVoice;
    public boolean isVoicePrivacy;
    public int als;
    public int numberPresentation;
    public String name;
    public int namePresentation;
    public UUSInfo uusInfo;
    /* parameter from +CLCCS:
     * [+CLCCS: <ccid1>,<dir>,<neg_status_present>,<neg_status>,<SDP_md>,
     * <cs_mode>,<ccstatus>,<mpty>,[,<numbertype>,<ton>,<number>
     * [,<priority_present>,<priority>[,<CLI_validity_present>,<CLI_validity>]]]
     */
    public int negStatusPresent;   //CLCCS parameter:<neg_status_present>
    public int negStatus;          //CLCCS parameter:<neg_status>
    public String mediaDescription;//CLCCS parameter:<SDP_md>
    public int csMode;             //<cs_mode>
    public int numberType;        //CLCCS parameter:<numbertype>
    public int prioritypresent;   //CLCCS parameter:<priority_present>
    public int priority;          //CLCCS parameter:<priority>
    public int cliValidityPresent;//CLCCS parameter:<CLI_validity_present>
    /* */

    public static State
    stateFromCLCCS(int state) throws ATParseEx {
        switch(state) {
            case 0: return State.ACTIVE;
            case 1: return State.HOLDING;
            case 2: return State.DIALING;
            case 3: return State.ALERTING;
            case 4: return State.INCOMING;
            case 5: return State.WAITING;
            case 6: return State.DISCONNECTED;
            default:
                throw new ATParseEx("illegal call state " + state);
        }
    }

    public static int
    stateToInt(State state) {
        if(state == State.ACTIVE){
            return STATE_ACTIVE;
        } else if(state == State.HOLDING){
            return STATE_HOLDING;
        } else if(state == State.DIALING){
            return STATE_DIALING;
        } else if(state == State.ALERTING){
            return STATE_ALERTING;
        } else if(state == State.INCOMING){
            return STATE_INCOMING;
        } else if(state == State.WAITING){
            return STATE_WAITING;
        } else if(state == State.DISCONNECTED){
            return STATE_DISCONNECTED;
        }
        return STATE_DISCONNECTED;
    }

    public static String
    stateToConferenceString(State state) {
        if(state == State.ACTIVE){
            return ImsConferenceState.STATUS_CONNECTED;
        } else if(state == State.HOLDING){
            return ImsConferenceState.STATUS_ON_HOLD;
        } else if(state == State.DIALING){
            return ImsConferenceState.STATUS_DIALING_OUT;
        }  else if(state == State.ALERTING){
            return ImsConferenceState.STATUS_ALERTING;
        } else if(state == State.INCOMING || state == State.WAITING){
            return ImsConferenceState.STATUS_DIALING_IN;
        }  else if(state == State.DISCONNECTED){
            return ImsConferenceState.STATUS_DISCONNECTED;
        }
        return ImsConferenceState.STATUS_CONNECTED;
    }

    public static State
    ConferenceStringToState(String state) {
        if(state == ImsConferenceState.STATUS_CONNECTED){
            return State.ACTIVE;
        } else if(state == ImsConferenceState.STATUS_ON_HOLD){
            return State.HOLDING;
        } else if(state == ImsConferenceState.STATUS_DIALING_OUT){
            return State.DIALING;
        }  else if(state == ImsConferenceState.STATUS_ALERTING){
            return State.ALERTING;
        } else if(state == ImsConferenceState.STATUS_DIALING_IN){
            return State.INCOMING;
        }  else if(state == ImsConferenceState.STATUS_DISCONNECTED){
            return State.DISCONNECTED;
        }
        return State.ACTIVE;
    }

    public static int
    presentationFromCLIP(int cli) throws ATParseEx
    {
        switch(cli) {
            case 0: return PhoneConstants.PRESENTATION_ALLOWED;
            case 1: return PhoneConstants.PRESENTATION_RESTRICTED;
            case 2: return PhoneConstants.PRESENTATION_UNKNOWN;
            case 3: return PhoneConstants.PRESENTATION_PAYPHONE;
            default:
                throw new ATParseEx("illegal presentation " + cli);
        }
    }

    public boolean update(ImsDriverCall dc){
        boolean hasUpdate = false;
        if(index != dc.index){
            index = dc.index;
            hasUpdate = true;
        }
        if(isMT != dc.isMT){
            isMT = dc.isMT;
            hasUpdate = true;
        }
        if(state != dc.state){
            state = dc.state;
            hasUpdate = true;
        }
        if(isMpty != dc.isMpty){
            isMpty = dc.isMpty;
            hasUpdate = true;
        }
        if(mptyState != dc.mptyState){
            mptyState = dc.mptyState;
            hasUpdate = true;
        }
        if(number != dc.number){
            number = dc.number;
            hasUpdate = true;
        }
        if(TOA != dc.TOA){
            TOA = dc.TOA;
            hasUpdate = true;
        }
        if(isVoice != dc.isVoice){
            isVoice = dc.isVoice;
            hasUpdate = true;
        }
        if(isVoicePrivacy != dc.isVoicePrivacy){
            isVoicePrivacy = dc.isVoicePrivacy;
            hasUpdate = true;
        }
        if(als != dc.als){
            als = dc.als;
            hasUpdate = true;
        }
        if(numberPresentation != dc.numberPresentation){
            numberPresentation = dc.numberPresentation;
            hasUpdate = true;
        }
        if(!equalsHandlesNulls(name,dc.name)){
            name = dc.name;
            hasUpdate = true;
        }
        if(namePresentation != dc.namePresentation){
            namePresentation = dc.namePresentation;
            hasUpdate = true;
        }
        if(uusInfo != dc.uusInfo){
            uusInfo = dc.uusInfo;
            hasUpdate = true;
        }
        if(negStatusPresent != dc.negStatusPresent){
            negStatusPresent = dc.negStatusPresent;
            hasUpdate = true;
        }
        if(negStatus != dc.negStatus){
            negStatus = dc.negStatus;
            hasUpdate = true;
        }
        if(!equalsHandlesNulls(mediaDescription,dc.mediaDescription)){
            mediaDescription = dc.mediaDescription;
            hasUpdate = true;
        }
        if(csMode != dc.csMode){
            csMode = dc.csMode;
            hasUpdate = true;
        }
        if(numberType != dc.numberType){
            numberType = dc.numberType;
            hasUpdate = true;
        }
        if(prioritypresent != dc.prioritypresent){
            prioritypresent = dc.prioritypresent;
            hasUpdate = true;
        }
        if(priority != dc.priority){
            priority = dc.priority;
            hasUpdate = true;
        }
        if(cliValidityPresent != dc.cliValidityPresent){
            cliValidityPresent = dc.cliValidityPresent;
            hasUpdate = true;
        }

        return hasUpdate;
    }

    public boolean equalsHandlesNulls(Object a, Object b) {
        return (a == null) ? (b == null) : a.equals (b);
    }

    @Override
    public String
    toString() {
        return "id=" + index + ","
                + "isMT="+isMT + ","
                + "negStatusPresent="+negStatusPresent + ","
                + "negStatus="+negStatus + ","
                + "mediaDescription="+mediaDescription + ","
                + "csMode:" + csMode + ","
                + (isVoice ? "voc" : "nonvoc") + ","
                + state + ","
                + (isMpty ? "conf" : "norm") + ","
                + "numberType="+mptyState+" ,"
                + "numberType="+numberType + ","
                + "toa=" + TOA + ","
                //SPRD：encryption　for number, Bug#823616
                + "number=" + Rlog.pii(LOG_TAG, number)
                + "prioritypresent="+prioritypresent + ","
                + "priority="+priority + ","
                + "cliValidityPresent="+cliValidityPresent + ","
                + ",cli=" + numberPresentation + ","
                + (isVoice ? "voice" : "video") + ","
                + als + ","
                + (isVoicePrivacy ? "evp" : "noevp") + ","
                + "name="+ name  + "," + namePresentation;
    }
}