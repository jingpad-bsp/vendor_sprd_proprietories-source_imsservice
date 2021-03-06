package com.spreadtrum.ims;

public class ImsRadioConstants {

    final static int RIL_SPRD_REQUEST_BASE = 2000;

    final static int RIL_REQUEST_GET_IMS_CURRENT_CALLS = RIL_SPRD_REQUEST_BASE + 1;
    final static int RIL_REQUEST_SET_IMS_VOICE_CALL_AVAILABILITY = RIL_SPRD_REQUEST_BASE + 2;
    final static int RIL_REQUEST_GET_IMS_VOICE_CALL_AVAILABILITY = RIL_SPRD_REQUEST_BASE + 3;
    final static int RIL_REQUEST_INIT_ISIM = RIL_SPRD_REQUEST_BASE + 4;
    final static int RIL_REQUEST_IMS_CALL_REQUEST_MEDIA_CHANGE  = RIL_SPRD_REQUEST_BASE + 5;
    final static int RIL_REQUEST_IMS_CALL_RESPONSE_MEDIA_CHANGE = RIL_SPRD_REQUEST_BASE + 6;
    final static int RIL_REQUEST_SET_IMS_SMSC = RIL_SPRD_REQUEST_BASE + 7;
    final static int RIL_REQUEST_IMS_CALL_FALL_BACK_TO_VOICE = RIL_SPRD_REQUEST_BASE + 8;
    final static int RIL_REQUEST_SET_EXT_INITIAL_ATTACH_APN = RIL_SPRD_REQUEST_BASE + 9;
    final static int RIL_REQUEST_QUERY_CALL_FORWARD_STATUS_URI = RIL_SPRD_REQUEST_BASE + 10;
    final static int RIL_REQUEST_SET_CALL_FORWARD_URI = RIL_SPRD_REQUEST_BASE + 11;
    final static int RIL_REQUEST_IMS_INITIAL_GROUP_CALL = RIL_SPRD_REQUEST_BASE + 12;
    final static int RIL_REQUEST_IMS_ADD_TO_GROUP_CALL = RIL_SPRD_REQUEST_BASE + 13;
    final static int RIL_REQUEST_ENABLE_IMS = RIL_SPRD_REQUEST_BASE + 14;
    final static int RIL_REQUEST_GET_IMS_BEARER_STATE = RIL_SPRD_REQUEST_BASE + 15;
    final static int RIL_REQUEST_IMS_HANDOVER = RIL_SPRD_REQUEST_BASE +16;
    final static int RIL_REQUEST_IMS_HANDOVER_STATUS_UPDATE = RIL_SPRD_REQUEST_BASE +17;
    final static int RIL_REQUEST_IMS_NETWORK_INFO_CHANGE = RIL_SPRD_REQUEST_BASE +18;
    final static int RIL_REQUEST_IMS_HANDOVER_CALL_END = RIL_SPRD_REQUEST_BASE +19;
    final static int RIL_REQUEST_GET_TPMR_STATE = RIL_SPRD_REQUEST_BASE +20;
    final static int RIL_REQUEST_SET_TPMR_STATE = RIL_SPRD_REQUEST_BASE +21;
    final static int RIL_REQUEST_IMS_WIFI_ENABLE = RIL_SPRD_REQUEST_BASE +22;
    final static int RIL_REQUEST_IMS_WIFI_CALL_STATE_CHANGE = RIL_SPRD_REQUEST_BASE +23;
    final static int RIL_REQUEST_IMS_UPDATE_DATA_ROUTER = RIL_SPRD_REQUEST_BASE +24;
    final static int RIL_REQUEST_IMS_HOLD_SINGLE_CALL = RIL_SPRD_REQUEST_BASE + 25;
    final static int RIL_REQUEST_IMS_MUTE_SINGLE_CALL = RIL_SPRD_REQUEST_BASE + 26;
    final static int RIL_REQUEST_IMS_SILENCE_SINGLE_CALL = RIL_SPRD_REQUEST_BASE + 27;
    final static int RIL_REQUEST_IMS_ENABLE_LOCAL_CONFERENCE = RIL_SPRD_REQUEST_BASE + 28;
    final static int RIL_REQUEST_IMS_NOTIFY_HANDOVER_CALL_INFO = RIL_SPRD_REQUEST_BASE + 29;
    final static int RIL_REQUEST_GET_IMS_SRVCC_CAPBILITY = RIL_SPRD_REQUEST_BASE + 30;
    final static int RIL_REQUEST_GET_IMS_PCSCF_ADDR  = RIL_SPRD_REQUEST_BASE + 31;
    final static int RIL_REQUEST_SET_IMS_PCSCF_ADDR  = RIL_SPRD_REQUEST_BASE + 32;
    final static int RIL_REQUEST_QUERY_FACILITY_LOCK_EXT = RIL_SPRD_REQUEST_BASE + 33;
    final static int RIL_REQUEST_GET_IMS_REGADDR = RIL_SPRD_REQUEST_BASE + 34;
    final static int RIL_REQUEST_QUERY_ROOT_NODE = RIL_SPRD_REQUEST_BASE + 35;

    final static int RIL_SPRD_UNSOL_RESPONSE_BASE = 3000;

    final static int RIL_UNSOL_RESPONSE_IMS_CALL_STATE_CHANGED = RIL_SPRD_UNSOL_RESPONSE_BASE + 0;
    final static int RIL_UNSOL_RESPONSE_VIDEO_QUALITY = RIL_SPRD_UNSOL_RESPONSE_BASE + 1;
    final static int RIL_UNSOL_RESPONSE_IMS_BEARER_ESTABLISTED = RIL_SPRD_UNSOL_RESPONSE_BASE + 2;
    final static int RIL_UNSOL_IMS_HANDOVER_REQUEST = RIL_SPRD_UNSOL_RESPONSE_BASE + 3;
    final static int RIL_UNSOL_IMS_HANDOVER_STATUS_CHANGE = RIL_SPRD_UNSOL_RESPONSE_BASE + 4;
    final static int RIL_UNSOL_IMS_NETWORK_INFO_CHANGE  = RIL_SPRD_UNSOL_RESPONSE_BASE + 5;
    final static int RIL_UNSOL_IMS_REGISTER_ADDRESS_CHANGE  = RIL_SPRD_UNSOL_RESPONSE_BASE + 6;
    final static int RIL_UNSOL_IMS_WIFI_PARAM   = RIL_SPRD_UNSOL_RESPONSE_BASE + 7;
    final static int RIL_UNSOL_IMS_NETWORK_STATE_CHANGED   = RIL_SPRD_UNSOL_RESPONSE_BASE + 8;
}
