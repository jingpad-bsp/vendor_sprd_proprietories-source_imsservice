package com.spreadtrum.ims.vowifi;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.net.Uri.Builder;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;

import com.spreadtrum.ims.vowifi.Utilities.ProviderUtils;

public class VoWifiConfiguration {
    private static final String TAG = Utilities.getTag(VoWifiConfiguration.class.getSimpleName());

    public enum PeerFromType {
        FROM_PAI(true),
        FROM_SIP_FROM(false);

        private boolean value;

        private PeerFromType(boolean value) {
            this.value = value;
        }

        public boolean getValue() {
            return value;
        }
    }

    public enum IntegType {
        TYPE_NONE("NONE"),
        TYPE_HMAC_MD5_96("HMAC_MD5_96"),
        TYPE_HMAC_SHA1_96("HMAC_SHA1_96"),
        TYPE_DES_MAC("DES_MAC"),
        TYPE_KPDK_MD5("KPDK_MD5"),
        TYPE_AES_XCBC_96("AES_XCBC_96"),
        TYPE_HMAC_SHA2_256_128("HMAC_SHA2_256_128"),
        TYPE_HMAC_SHA2_384_192("HMAC_SHA2_384_192"),
        TYPE_HMAC_SHA2_512_256("HMAC_SHA2_512_256");

        private String value;

        private IntegType(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }
    }

    public enum EncryptType {
        TYPE_NONE("NONE"),
        TYPE_DES_IV64("DES_IV64"),
        TYPE_DES("DES"),
        TYPE_3DES("3DES"),
        TYPE_RC5("RC5"),
        TYPE_IDEA("IDEA"),
        TYPE_CAST("CAST"),
        TYPE_BLOWFISH("BLOWFISH"),
        TYPE_3IDEA("3IDEA"),
        TYPE_DES_IV32("DES_IV32"),
        TYPE_AES_CBC("AES_CBC"),
        TYPE_AES_CTR("AES_CTR");

        private String value;

        private EncryptType(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }
    }

    public enum TransportType {
        UDP("udp"),
        TCP("tcp"),
        AUTO("auto");

        private String value;

        private TransportType(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }
    }

    private static final Uri URI_IKE =
            Uri.parse(ProviderUtils.CONTENT_URI + "/" + ProviderUtils.FUN_SECURITY);
    private static final Uri URI_REG =
            Uri.parse(ProviderUtils.CONTENT_URI + "/" + ProviderUtils.FUN_REGISTER);
    private static final Uri URI_CALL =
            Uri.parse(ProviderUtils.CONTENT_URI + "/" + ProviderUtils.FUN_CALL);
    private static final Uri URI_MESSAGE =
            Uri.parse(ProviderUtils.CONTENT_URI + "/" + ProviderUtils.FUN_MESSAGE);
    private static final Uri URI_UT =
            Uri.parse(ProviderUtils.CONTENT_URI + "/" + ProviderUtils.FUN_UT);

    // The support query parameters.
    private static final String PARAM_SUBID = "subId";
    private static final String PARAM_MCC = "mcc";
    private static final String PARAM_MNC = "mnc";
    private static final String PARAM_GID = "gid";

    public static boolean isRegRequestPLCI(Context context) {
        Cursor cursor = context.getContentResolver().query(URI_REG, null, null, null, null);
        try {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndexOrThrow("withPLCI");
                return cursor.getInt(index) == 1 ? true : false;
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }

        return false;
    }

    public static boolean isRegRequestCNI(Context context) {
        Cursor cursor = context.getContentResolver().query(URI_REG, null, null, null, null);
        try {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndexOrThrow("withCNI");
                if (index > -1) {
                    return cursor.getInt(index) > 0;
                }
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }

        return false;
    }

    public static String getRegPANICountryCode(Context context) {
        return getRegPANICountryCode(context, Utilities.getPrimaryCardSubId(context));
    }

    public static String getRegPANICountryCode(Context context, int subId) {
        Cursor cursor = context.getContentResolver().query(URI_REG, null, null, null, null);
        try {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndexOrThrow("withCountryInPANI");
                if (index > -1) {
                    boolean requiredCountry = cursor.getInt(index) == 1 ? true : false;
                    if (requiredCountry) {
                        TelephonyManager tm = new TelephonyManager(context, subId);
                        String countryIso = tm.getNetworkCountryIso();
                        return TextUtils.isEmpty(countryIso) ? "" : countryIso.toUpperCase();
                    } else {
                        // As needn't country code, return empty string.
                        return "";
                    }
                }
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }

        return "";
    }

    public static boolean isSupportSMS(Context context) {
        Cursor cursor = context.getContentResolver().query(URI_MESSAGE, null, null, null, null);
        try {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndexOrThrow("smsSupport");
                if (index > -1) {
                    return cursor.getInt(index) > 0;
                }
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }

        return false;
    }

    public static boolean isSupportUT(Context context) {
        return isSupportUT(getUtConfiguration(context));
    }

    public static boolean isSupportUT(Context context, int subId) {
        return isSupportUT(getUtConfiguration(context, subId));
    }

    private static boolean isSupportUT(Cursor cursor) {
        if (cursor != null && cursor.moveToFirst()) {
            try {
                int index = cursor.getColumnIndexOrThrow("utSupport");
                if (index > -1) {
                    return cursor.getInt(index) > 0;
                }
            } finally {
                cursor.close();
            }
        }

        Log.w(TAG, "Failed to get ut configuration, handle ut support as false.");
        return false;
    }

    public static Cursor getUtConfiguration(Context context) {
        return context.getContentResolver().query(URI_UT, null, null, null, null);
    }

    public static Cursor getUtConfiguration(Context context, int subId) {
        Builder builder = URI_UT.buildUpon();
        Uri queryUri = builder.appendQueryParameter(PARAM_SUBID, String.valueOf(subId)).build();
        return context.getContentResolver().query(queryUri, null, null, null, null);
    }

    public static boolean updateIkeFQDN(Context context, String fqdn) {
        ContentValues values = new ContentValues();
        values.put("ike_profile_static_ip", fqdn);
        return update(context, URI_IKE, values);
    }

    public static boolean updateIkeDpdTimer(Context context, int timer) {
        ContentValues values = new ContentValues();
        values.put("ike_profile_dpd_timer", timer);
        return update(context, URI_IKE, values);
    }

    public static boolean updateIkeReTransTimes(Context context, int times) {
        ContentValues values = new ContentValues();
        values.put("ike_profile_re_trans_times", times);
        return update(context, URI_IKE, values);
    }

    public static boolean updateIkeReKeyTimer(Context context, int timer) {
        ContentValues values = new ContentValues();
        values.put("ike_profile_ipsec_rekey_timer", timer);
        values.put("ike_profile_ike_rekey_timer", timer);
        return update(context, URI_IKE, values);
    }

    public static boolean updateIkeIpsecIntegType(Context context, IntegType type) {
        ContentValues values = new ContentValues();
        values.put("ike_profile_ipsec_integ_type", type.getValue());
        return update(context, URI_IKE, values);
    }

    public static boolean updateIkeIpsecEncryptType(Context context, EncryptType type) {
        ContentValues values = new ContentValues();
        values.put("ike_profile_ipsec_encrypt_type", type.getValue());
        return update(context, URI_IKE, values);
    }

    public static boolean updateCallPeerFrom(Context context, PeerFromType fromType) {
        ContentValues values = new ContentValues();
        values.put("peer_from_pai", fromType.getValue());
        return update(context, URI_CALL, values);
    }

    public static boolean updateRegUseIpsec(Context context, boolean useIpsec) {
        ContentValues values = new ContentValues();
        values.put("use_ipsec", useIpsec);
        return update(context, URI_REG, values);
    }

    public static boolean updateRegUserAgent(Context context, String userAgent) {
        ContentValues values = new ContentValues();
        values.put("userAgent", userAgent);
        return update(context, URI_REG, values);
    }

    public static boolean updateRegTransType(Context context, TransportType type) {
        ContentValues values = new ContentValues();
        values.put("transport_type", type.getValue());
        return update(context, URI_REG, values);
    }

    public static boolean updateRegRingbackTimer(Context context, int timer) {
        ContentValues values = new ContentValues();
        values.put("ringback_timer", timer);
        return update(context, URI_REG, values);
    }

    public static boolean updateRegRingbackTimerB(Context context, int timer) {
        ContentValues values = new ContentValues();
        values.put("ringback_timerB", timer);
        return update(context, URI_REG, values);
    }

    public static boolean updateRegSipTimer1(Context context, int timer) {
        ContentValues values = new ContentValues();
        values.put("sip_timer1", timer);
        return update(context, URI_REG, values);
    }

    public static boolean updateRegSipTimer2(Context context, int timer) {
        ContentValues values = new ContentValues();
        values.put("sip_timer2", timer);
        return update(context, URI_REG, values);
    }

    public static boolean updateRegSipTimer4(Context context, int timer) {
        ContentValues values = new ContentValues();
        values.put("sip_timer4", timer);
        return update(context, URI_REG, values);
    }

    private static boolean update(Context context, Uri uri, ContentValues values) {
        int count = context.getContentResolver().update(uri, values, null, null);
        return (count == values.size());
    }
}
