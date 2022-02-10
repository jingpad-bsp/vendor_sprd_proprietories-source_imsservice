package com.spreadtrum.ims.vowifi;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.CursorWrapper;
import android.net.Uri;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.provider.Settings;
import android.telecom.VideoProfile;
import android.telephony.PhoneNumberUtils;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.telephony.TelephonyManagerEx;
import android.telephony.emergency.EmergencyNumber;
import android.telephony.ims.ImsCallProfile;
import android.text.TextUtils;
import android.util.Log;
import android.view.WindowManager;
import android.widget.Toast;

import com.android.ims.internal.IImsServiceEx;
import com.android.ims.internal.IVoWifiCall;
import com.android.ims.internal.IVoWifiRegister;
import com.android.ims.internal.IVoWifiSecurity;
import com.android.ims.internal.IVoWifiSms;
import com.android.ims.internal.IVoWifiUT;
import com.android.ims.internal.ImsManagerEx;

import com.spreadtrum.ims.ImsConfigImpl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map.Entry;

public class Utilities {
    private static final String TAG = getTag(Utilities.class.getSimpleName());

    // This value will be false if release.
    public static final boolean DEBUG = true;

    public static final String SERVICE_PACKAGE = "com.spreadtrum.vowifi";
    public static final String SERVICE_PACKAGE_SEC = "com.spreadtrum.vowifi.sec";

    public static final String SERVICE_CLASS_SEC = SERVICE_PACKAGE + ".service.SecurityService";
    public static final String SERVICE_CLASS_REG = SERVICE_PACKAGE + ".service.RegisterService";
    public static final String SERVICE_CLASS_CALL = SERVICE_PACKAGE + ".service.CallService";
    public static final String SERVICE_CLASS_SMS = SERVICE_PACKAGE + ".service.SmsService";
    public static final String SERVICE_CLASS_UT = SERVICE_PACKAGE + ".service.UTService";

    public static final String SERVICE_ACTION_SEC = IVoWifiSecurity.class.getCanonicalName();
    public static final String SERVICE_ACTION_REG = IVoWifiRegister.class.getCanonicalName();
    public static final String SERVICE_ACTION_CALL = IVoWifiCall.class.getCanonicalName();
    public static final String SERVICE_ACTION_SMS = IVoWifiSms.class.getCanonicalName();
    public static final String SERVICE_ACTION_UT = IVoWifiUT.class.getCanonicalName();

    // Used to get the primary card id.
    private static final int DEFAULT_PHONE_ID = 0;

    // Used to enable or disable the secondary service for sos register.
    private static final String PROP_KEY_ENABLE_SEC_SERVICE = "persist.vowifi.sec.ser.enabled";
    // Used to get the call waiting status.
    private static final String PROP_KEY_SS_CW = "gsm.ss.call_waiting";

    public static HashMap<Integer, VideoQuality> sVideoQualitys =
            new HashMap<Integer, VideoQuality>();
    static {
        // Refer to ImsConfigImpl#VT_RESOLUTION_720P}
        sVideoQualitys.put(ImsConfigImpl.VT_RESOLUTION_720P, new VideoQuality(
                31, 1280, 720, 30, 3000 * 1000, 4000 * 1000, 200 * 1000, 30, 1));
        // Refer to ImsConfigImpl#VT_RESOLUTION_VGA_REVERSED_15
        sVideoQualitys.put(ImsConfigImpl.VT_RESOLUTION_VGA_REVERSED_15, new VideoQuality(
                22, 480, 640, 15, 400 * 1000, 660 * 1000, 150 * 1000, 15, 1));
        // Refer to ImsConfigImpl#VT_RESOLUTION_VGA_REVERSED_30
        sVideoQualitys.put(ImsConfigImpl.VT_RESOLUTION_VGA_REVERSED_30, new VideoQuality(
                30, 480, 640, 30, 600 * 1000, 980 * 1000, 150 * 1000, 30, 1));
        // Refer to ImsConfigImpl#VT_RESOLUTION_QVGA_REVERSED_15
        sVideoQualitys.put(ImsConfigImpl.VT_RESOLUTION_QVGA_REVERSED_15, new VideoQuality(
                12, 240, 320, 15, 256 * 1000, 320 * 1000, 100 * 1000, 15, 1));
        // Refer to ImsConfigImpl#VT_RESOLUTION_QVGA_REVERSED_30
        sVideoQualitys.put(ImsConfigImpl.VT_RESOLUTION_QVGA_REVERSED_30, new VideoQuality(
                13, 240, 320, 30, 384 * 1000, 512 * 1000, 100 * 1000, 30, 1));
        // Refer to ImsConfigImpl#VT_RESOLUTION_CIF
        sVideoQualitys.put(ImsConfigImpl.VT_RESOLUTION_CIF, new VideoQuality(
                14, 352, 288, 30, 300 * 1000, 400 * 1000, 100 * 1000, 30, 1));
        // Refer to ImsConfigImpl#VT_RESOLUTION_QCIF
        sVideoQualitys.put(ImsConfigImpl.VT_RESOLUTION_QCIF, new VideoQuality(
                11, 176, 144, 30, 100 * 1000, 300 * 1000, 60 * 1000, 30, 1));
    }

    public static String getTag(String tag) {
        return "[Adapter]" + tag;
    }

    public static boolean isSupportSOSSingleProcess(Context context) {
        boolean secSerEnabled = SystemProperties.getBoolean(PROP_KEY_ENABLE_SEC_SERVICE, false);
        if (secSerEnabled) {
            PackageManager pm = context.getPackageManager();
            return pm.isPackageAvailable(SERVICE_PACKAGE_SEC);
        } else {
            // Do not support sos single process.
            Log.d(TAG, "The secondary vowifi server is disabled.");
            return false;
        }
    }

    public static boolean isAirplaneModeOff(Context context) {
        return Settings.Global.getInt(context.getContentResolver(),
                Settings.Global.AIRPLANE_MODE_ON, 0) == 0;
    }

    public static boolean isCallWaitingEnabled() {
        return SystemProperties.getBoolean(PROP_KEY_SS_CW, true);
    }

    public static String getStringFromArray(Object[] items) {
        if (items == null || items.length < 1) {
            return "{null}";
        }

        StringBuilder builder = new StringBuilder("{[0]");
        builder.append(items[0].toString());
        for (int i = 1; i < items.length; i++) {
            builder.append(" | [" + i + "]");
            builder.append(items[i].toString());
        }
        builder.append("}");
        return builder.toString();
    }

    public static int getSubId(int phoneId) {
        int[] subId = SubscriptionManager.getSubId(phoneId);
        if (subId == null || subId.length == 0) {
            Log.e(TAG, "Can not get the sub id from the phone id: " + phoneId);
            return -1;
        }

        return subId[0];
    }

    public static int getPrimaryCard(Context context) {
        TelephonyManager tm =
                (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
        int phoneCount = tm.getPhoneCount();
        if (phoneCount == 1) {
            return DEFAULT_PHONE_ID;
        }

        int primaryCard =
                SubscriptionManager.getPhoneId(SubscriptionManager.getDefaultDataSubscriptionId());
        if (primaryCard < 0) {
            return DEFAULT_PHONE_ID;
        }
        return primaryCard;
    }

    public static int getPrimaryCardSubId(Context context) {
        return getSubId(getPrimaryCard(context));
    }

    public static boolean isAudioCall(int callType) {
        return callType == ImsCallProfile.CALL_TYPE_VOICE
                || callType == ImsCallProfile.CALL_TYPE_VOICE_N_VIDEO;
    }

    public static boolean isVideoCall(int callType) {
        return callType != ImsCallProfile.CALL_TYPE_VOICE
                && callType != ImsCallProfile.CALL_TYPE_VOICE_N_VIDEO;
    }

    public static boolean isVideoTX(int callType) {
        return callType == ImsCallProfile.CALL_TYPE_VT_TX;
    }

    public static boolean isVideoRX(int callType) {
        return callType == ImsCallProfile.CALL_TYPE_VT_RX;
    }

    public static VideoQuality getDefaultVideoQuality(SharedPreferences preference) {
        int resolution = ImsConfigImpl.VT_RESOLUTION_VGA_REVERSED_30;
        if (preference != null) {
            resolution = preference.getInt(ImsConfigImpl.VT_RESOLUTION_VALUE,
                    ImsConfigImpl.VT_RESOLUTION_VGA_REVERSED_30);
            // As do not accept none reversed resolution, need adjust to reversed resolution.
            switch (resolution) {
                case ImsConfigImpl.VT_RESOLUTION_VGA_15:
                    resolution = ImsConfigImpl.VT_RESOLUTION_VGA_REVERSED_15;
                    break;
                case ImsConfigImpl.VT_RESOLUTION_VGA_30:
                    resolution = ImsConfigImpl.VT_RESOLUTION_VGA_REVERSED_30;
                    break;
                case ImsConfigImpl.VT_RESOLUTION_QVGA_15:
                    resolution = ImsConfigImpl.VT_RESOLUTION_QVGA_REVERSED_15;
                    break;
                case ImsConfigImpl.VT_RESOLUTION_QVGA_30:
                    resolution = ImsConfigImpl.VT_RESOLUTION_QVGA_REVERSED_30;
                    break;
            }
        }

        return sVideoQualitys.get((Integer) resolution);
    }

    public static VideoQuality findVideoQuality(float videoLevel) {
        Iterator<Entry<Integer, VideoQuality>> it = sVideoQualitys.entrySet().iterator();
        while (it.hasNext()) {
            VideoQuality quality = it.next().getValue();
            if (quality._level == videoLevel) {
                return quality;
            }
        }

        return null;
    }

    public static boolean isSameCallee(String calleeNumber, String phoneNumber) {
        if (TextUtils.isEmpty(calleeNumber)
                || TextUtils.isEmpty(phoneNumber)) {
            return false;
        }

        if (PhoneNumberUtils.compare(calleeNumber, phoneNumber)) {
            return true;
        } else {
            if (phoneNumber.indexOf(calleeNumber) >= 0
                    || calleeNumber.indexOf(phoneNumber) >= 0) {
                return true;
            } else if (calleeNumber.startsWith("0") && calleeNumber.length() > 1) {
                // Sometimes, the phone number will be start will 0, we'd like to sub the string.
                String tempCallee = calleeNumber.substring(1);
                if (phoneNumber.indexOf(tempCallee) >= 0
                        || tempCallee.indexOf(phoneNumber) >= 0) {
                    return true;
                }
            }
        }

        return false;
    }

    // This defined is match the error used by CM. Please do not change.
    public static class UnsolicitedCode {
        public static final int SECURITY_DPD_DISCONNECTED  = 1;
        public static final int SIP_TIMEOUT                = 2;
        public static final int SIP_LOGOUT                 = 3;
        public static final int SECURITY_REKEY_FAILED      = 4;
        public static final int SECURITY_STOP              = 5;
        public static final int DEREGISTER_AND_RETRY_AFTER = 6;

        // TODO: Sync this defined with ImsCM? Now use the same unsolicited code value as
        //       SECURITY_REKEY_FAILED to start the normal attach without VOWIFI_UNAVAILABLE.
        public static final int SOS_FAILED = SECURITY_REKEY_FAILED;
    }

    public static class CallType {
        public static final int CALL_TYPE_UNKNOWN = 0;
        public static final int CALL_TYPE_VOICE   = 1;
        public static final int CALL_TYPE_VIDEO   = 2;
    }

    public static class VolteNetworkType {
        public static final int IEEE_802_11 = -1;
        public static final int NONE        = 0;
        public static final int GERAN       = 1;
        public static final int UTRAN_FDD   = 2;
        public static final int UTRAN_TDD   = 3;
        public static final int E_UTRAN_FDD = 4; // 3gpp
        public static final int E_UTRAN_TDD = 5; // 3gpp
    }

    public static class VowifiNetworkType {
        public static final int IEEE_802_11 = 1;
        public static final int GERAN       = 6;
        public static final int UTRAN_FDD   = 7;
        public static final int UTRAN_TDD   = 8;
        public static final int E_UTRAN_FDD = 9;  // 3gpp
        public static final int E_UTRAN_TDD = 10; // 3gpp
    }

    public static class NativeErrorCode {
        public static final int IKE_INTERRUPT_STOP   = 0xD200 + 198;
        public static final int IKE_HANDOVER_STOP    = 0xD200 + 199;
        public static final int DPD_DISCONNECT       = 0xD200 + 15;
        public static final int IPSEC_REKEY_FAIL     = 0xD200 + 10;
        public static final int IKE_REKEY_FAIL       = 0xD200 + 11;
        public static final int REG_TIMEOUT          = 0xE100 + 5;
        public static final int SERVER_TIMEOUT       = 0xE100 + 6;
        public static final int REG_SERVER_FORBIDDEN = 0xE100 + 8;
        public static final int REG_EXPIRED_TIMEOUT  = 0xE100 + 17;
        public static final int REG_EXPIRED_OTHER    = 0xE100 + 18;
    }

    public static class RegisterState {
        public static final int STATE_IDLE        = 0;
        public static final int STATE_PROGRESSING = 1;
        public static final int STATE_CONNECTED   = 2;
    }

    public static class Result {
        public static final int INVALID_ID = -1;

        public static final int FAIL       = 0;
        public static final int SUCCESS    = 1;
    }

    public static class S2bType {
        public static final int NORMAL = 1;
        public static final int SOS    = 2;
        public static final int UT     = 4;
        public static final int MMS    = 8;
    }

    public static class RegisterType {
        public static final int NORMAL = 1;
        public static final int SOS    = 2;
    }

    public static class CallStateForDataRouter {
        public static final int VOLTE  = 0;
        public static final int VOWIFI = 1;
        public static final int NONE   = 2;
        public static final int VOWIFI_VIDEO = 3;

        public static String getDRStateString(int state) {
            switch (state) {
                case VOLTE:
                    return "volte";
                case VOWIFI:
                    return "vowifi[audio]";
                case VOWIFI_VIDEO:
                    return "vowifi[video]";
                case NONE:
                    return "none";
            }

            return null;
        }
    }

    public static class IPVersion {
        public static final int NONE = -1;
        public static final int IP_V4 = 0;
        public static final int IP_V6 = 1;
    }

    // Note: Do not change this defined value, as it matched the call state used in CP.
    public static class SRVCCSyncInfo {

        public static class CallState {
            public static final int IDLE_STATE           = 0;
            public static final int DIALING_STATE        = 1;
            public static final int OUTGOING_STATE       = 2;
            public static final int ACTIVE_STATE         = 3;
            public static final int INCOMING_STATE       = 4;
            public static final int ACCEPT_STATE         = 5;
            public static final int MODIFY_PENDING_STATE = 6;
            public static final int RELEASE_STATE        = 7;
            public static final int CCBS_RECALL_STATE    = 8;
            public static final int MT_CSFB_STATE        = 9;
            public static final int MAX_STATE            = 10;
        }

        public static class HoldState {
            public static final int IDLE = 0;
            public static final int HELD = 2;
        }

        public static class MultipartyState {
            public static final int NO  = 0;
            public static final int YES = 2;
        }

        public static class CallDirection {
            public static final int MO = 0;
            public static final int MT = 1;
        }

        public static class CallType {
            public static final int NORMAL    = 0;
            public static final int EMERGENCY = 1;
            public static final int VIDEO     = 2;
        }

        public static class PhoneNumberType {
            public static final int INTERNATIONAL = 1;
            public static final int NATIONAL = 2;
            public static final int NETWORK = 3;
        }
    }

    public static class SRVCCResult {
        public static final int SUCCESS = 1;
        public static final int CANCEL  = 2;
        public static final int FAILURE  = 3;
    }

    public static class SecurityConfig {
        public String _pcscf4;
        public String _pcscf6;
        public String _dns4;
        public String _dns6;
        public String _ip4;
        public String _ip6;
        public boolean _prefIPv4;
        public boolean _isSupportMobike;

        public int _useIPVersion = IPVersion.NONE;

        public SecurityConfig(String pcscf4, String pcscf6, String dns4, String dns6, String ip4,
                String ip6, boolean prefIPv4, boolean isSupportMobike) {
            _pcscf4 = pcscf4;
            _pcscf6 = pcscf6;
            _dns4 = dns4;
            _dns6 = dns6;
            _ip4 = ip4;
            _ip6 = ip6;
            _prefIPv4 = prefIPv4;
            _isSupportMobike = isSupportMobike;
        }

        @Override
        public String toString() {
            return "[pcscf4=" + _pcscf4 + ", pcscf6=" + _pcscf6 + ", dns4=" + _dns4 + ", dns6="
                    + _dns6 + ", ip4=" + _ip4 + ", ip6=" + _ip6 + ", prefIPv4=" + _prefIPv4
                    + ", supportMobike=" + _isSupportMobike + "]";
        }
    }

    public static class RegisterConfig {
        private static final String JSON_PCSCF_SEP = ";";

        private String mLocalIPv4;
        private String mLocalIPv6;
        private String[] mPcscfIPv4;
        private String[] mPcscfIPv6;
        private String mDnsSerIPv4;
        private String mDnsSerIPv6;
        private int mIPv4Index = 0;
        private int mIPv6Index = 0;

        private String mUsedLocalIP;
        private String mUsedPcscfIP;
        private String mUsedDnsSerlIP;

        public static RegisterConfig getInstance(String localIPv4, String localIPv6,
                String pcscfIPv4, String pcscfIPv6, String usedPcscfAddr, String dns4,
                String dns6) {
            if (Utilities.DEBUG) {
                Log.i(TAG, "Get the s2b ip address from localIPv4: " + localIPv4 + ", localIPv6: "
                        + localIPv6 + ", pcscfIPv4: " + pcscfIPv4 + ", pcscfIPv6: "
                        + pcscfIPv6 + ", usedPcscfAddr: " + usedPcscfAddr + ", dns4: " + dns4
                        + ", dns6: " + dns6);
            }

            String[] pcscfIPv4s =
                    TextUtils.isEmpty(pcscfIPv4) ? null : pcscfIPv4.split(JSON_PCSCF_SEP);
            String[] pcscfIPv6s =
                    TextUtils.isEmpty(pcscfIPv6) ? null : pcscfIPv6.split(JSON_PCSCF_SEP);
            if (TextUtils.isEmpty(usedPcscfAddr)) {
                return new RegisterConfig(
                        localIPv4, localIPv6, pcscfIPv4s, pcscfIPv6s, dns4, dns6);
            } else {
                String[] newPcscfIPv4s = rebuildAddr(pcscfIPv4s, usedPcscfAddr, true);
                String[] newPcscfIPv6s = rebuildAddr(pcscfIPv6s, usedPcscfAddr, false);
                return new RegisterConfig(
                        localIPv4, localIPv6, newPcscfIPv4s, newPcscfIPv6s, dns4, dns6);
            }
        }

        private RegisterConfig(String localIPv4, String localIPv6, String[] pcscfIPv4,
                String[] pcscfIPv6, String dns4, String dns6) {
            mLocalIPv4 = localIPv4;
            mLocalIPv6 = localIPv6;
            mPcscfIPv4 = pcscfIPv4;
            mPcscfIPv6 = pcscfIPv6;
            mDnsSerIPv4 = dns4;
            mDnsSerIPv6 = dns6;
        }

        public boolean isCurUsedIPv4() {
            return isIPv4(mUsedLocalIP);
        }

        public String getCurUsedLocalIP() {
            return mUsedLocalIP;
        }

        public String getCurUsedPcscfIP() {
            return mUsedPcscfIP;
        }

        public String getCurUsedDnsSerIP() {
            return mUsedDnsSerlIP;
        }

        public String getLocalIP(boolean isIPv4) {
            mUsedLocalIP = isIPv4 ? getLocalIPv4() : getLocalIPv6();
            return mUsedLocalIP;
        }

        public String getDnsSerIP(boolean isIPv4) {
            mUsedDnsSerlIP = isIPv4 ? getDnsSerIPv4() : getDnsSerIPv6();
            return mUsedDnsSerlIP;
        }

        public String getPcscfIP(boolean isIPv4) {
            mUsedPcscfIP = isIPv4 ? getPcscfIPv4() : getPcscfIPv6();
            return mUsedPcscfIP;
        }

        public int getValidIPVersion(boolean prefIPv4) {
            if (prefIPv4) {
                if (isIPv4Valid()) return IPVersion.IP_V4;
                if (isIPv6Valid()) return IPVersion.IP_V6;
            } else {
                if (isIPv6Valid()) return IPVersion.IP_V6;
                if (isIPv4Valid()) return IPVersion.IP_V4;
            }

            return IPVersion.NONE;
        }

        private String getLocalIPv4() {
            return mLocalIPv4;
        }

        private String getDnsSerIPv4() {
            return mDnsSerIPv4;
        }

        private String getLocalIPv6() {
            return mLocalIPv6;
        }

        private String getDnsSerIPv6() {
            return mDnsSerIPv6;
        }

        private String getPcscfIPv4() {
            String pcscfIPv4 = null;
            if (mIPv4Index < mPcscfIPv4.length) {
                pcscfIPv4 = mPcscfIPv4[mIPv4Index];
                mIPv4Index = mIPv4Index + 1;
            }
            return pcscfIPv4;
        }

        private String getPcscfIPv6() {
            String pcscfIPv6 = null;
            if (mIPv6Index < mPcscfIPv6.length) {
                pcscfIPv6 = mPcscfIPv6[mIPv6Index];
                mIPv6Index = mIPv6Index + 1;
            }
            return pcscfIPv6;
        }

        private boolean isIPv4Valid() {
            return !TextUtils.isEmpty(mLocalIPv4)
                    && mPcscfIPv4 != null
                    && mPcscfIPv4.length > 0
                    && mIPv4Index < mPcscfIPv4.length;
        }

        private boolean isIPv6Valid() {
            return !TextUtils.isEmpty(mLocalIPv6)
                    && mPcscfIPv6 != null
                    && mPcscfIPv6.length > 0
                    && mIPv6Index < mPcscfIPv6.length;
        }

        private static boolean isIPv4(String ipAddr) {
            return ipAddr.contains(".");
        }

        private static String[] rebuildAddr(String[] oldAddrs, String firstAddr, boolean asIPv4) {
            if (TextUtils.isEmpty(firstAddr)
                    || isIPv4(firstAddr) != asIPv4) {
                return oldAddrs;
            }

            if (oldAddrs == null || oldAddrs.length == 0) {
                return new String[] { firstAddr };
            } else {
                String[] newAddrs = new String[oldAddrs.length + 1];
                for (int i = 0; i < oldAddrs.length; i++) {
                    if (firstAddr.equals(oldAddrs[i])) {
                        String oldFirstAddr = oldAddrs[0];
                        oldAddrs[0] = firstAddr;
                        oldAddrs[i] = oldFirstAddr;
                        return oldAddrs;
                    }
                    newAddrs[i] = oldAddrs[i];
                }

                // It means do not find matched address, append it to the last.
                newAddrs[oldAddrs.length] = firstAddr;
                return newAddrs;
            }
        }

        @Override
        public String toString() {
            StringBuilder builder = new StringBuilder();
            builder.append("localIPv4[" + mLocalIPv4 + "]");
            builder.append(", localIPv6[" + mLocalIPv6 + "]");
            if (mPcscfIPv4 != null) {
                for (int i = 0; i < mPcscfIPv4.length; i++) {
                    builder.append(", pcscfIPv4[" + i + "] = " + mPcscfIPv4[i]);
                }
            }
            if (mPcscfIPv6 != null) {
                for (int i = 0; i < mPcscfIPv6.length; i++) {
                    builder.append(", pcscfIPv6[" + i + "] = " + mPcscfIPv6[i]);
                }
            }
            builder.append(", pcscfDnsSerIPv4[" + mDnsSerIPv4 + "]");
            builder.append(", pcscfDnsSerIPv6[" + mDnsSerIPv6 + "]");
            return builder.toString();
        }
    }

    public static class CellularNetInfo {
        public int _type;
        public String _info;
        public int _age;

        public static void requestCellularNetInfo() {
            // New a thread to request the access net info.
            new Thread(new Runnable() {
                @Override
                public void run() {
                    IImsServiceEx imsServiceEx = ImsManagerEx.getIImsServiceEx();
                    if (imsServiceEx != null) {
                        try {
                            Log.d(TAG, "Get the access net info.");
                            imsServiceEx.getImsCNIInfor();
                        } catch (RemoteException e) {
                            Log.e(TAG, "Failed to get the access net info as exception: " + e);
                        }
                    } else {
                        Log.e(TAG, "Can not get the ims ex service.");
                    }
                }
            }).start();
        }

        public CellularNetInfo(int eNodeType, String info) {
            this(eNodeType, info, -1);
        }

        public CellularNetInfo(int eNodeType, String info, int age) {
            _type = getVowifiNetworkType(eNodeType);
            _info = info;
            _age = age;
        }

        private int getVowifiNetworkType(int volteType) {
            switch (volteType) {
                case VolteNetworkType.IEEE_802_11:
                case VolteNetworkType.NONE:
                    return VowifiNetworkType.IEEE_802_11;
                case VolteNetworkType.GERAN:
                    return VowifiNetworkType.GERAN;
                case VolteNetworkType.UTRAN_FDD:
                    return VowifiNetworkType.UTRAN_FDD;
                case VolteNetworkType.UTRAN_TDD:
                    return VowifiNetworkType.UTRAN_TDD;
                case VolteNetworkType.E_UTRAN_FDD:
                    return VowifiNetworkType.E_UTRAN_FDD;
                case VolteNetworkType.E_UTRAN_TDD:
                    return VowifiNetworkType.E_UTRAN_TDD;
                default:
                    Log.e(TAG, "Do not support this volte network type now, type: " + volteType);
                    return VowifiNetworkType.IEEE_802_11;
            }
        }

        @Override
        public String toString() {
            return "CellularNetInfo [type=" + _type + ", info=" + _info + "]";
        }
    }

    public static class Camera {
        private static final String CAMERA_FRONT = "1";
        private static final String CAMERA_BACK = "0";

        private static final String CAMERA_NAME_NULL = "null";
        private static final String CAMERA_NAME_FRONT = "front";
        private static final String CAMERA_NAME_BACK = "back";

        public static boolean isFront(String cameraId) {
            return CAMERA_FRONT.equals(cameraId);
        }

        public static boolean isBack(String cameraId) {
            return CAMERA_BACK.equals(cameraId);
        }

        public static String toString(String cameraId) {
            String cameraName = CAMERA_NAME_NULL;
            if (!TextUtils.isEmpty(cameraId)) {
                cameraName = isFront(cameraId) ? CAMERA_NAME_FRONT : CAMERA_NAME_BACK;
            }
            return cameraName;
        }
    }

    /**
     * For emergency service please refer to 3GPP TS 22.101 clause 10.
     */
    public static class EMUtils {
        // The default based emergency service urn format
        public static final String DEFAULT_EMERGENCY_SERVICE_URN = "urn:service:sos";
        public static final String DEFAULT_EMERGENCY_SERVICE_URN_PREFIX = "urn:service:sos.";
        // Police
        public static final String EMERGENCY_CATEGORY_POLICE = "police";
        // Ambulance
        public static final String EMERGENCY_CATEGORY_AMBULANCE = "ambulance";
        // Fire Brigade
        public static final String EMERGENCY_CATEGORY_FIRE = "fire";
        // Marine Guard
        public static final String EMERGENCY_CATEGORY_MARINE = "marine";
        // Mountain Rescue
        public static final String EMERGENCY_CATEGORY_MOUNTAIN = "mountain";

        public static final int CATEGORY_VALUE_NONE = 0;
        public static final int CATEGORY_VALUE_POLICE = 1;
        public static final int CATEGORY_VALUE_AMBULANCE = 2;
        public static final int CATEGORY_VALUE_FIRE = 4;
        public static final int CATEGORY_VALUE_MARINE = 8;
        public static final int CATEGORY_VALUE_MOUNTAIN = 16;

        public static boolean isValidCategory(int category) {
            return category == EmergencyNumber.EMERGENCY_SERVICE_CATEGORY_UNSPECIFIED
                    || category == EmergencyNumber.EMERGENCY_SERVICE_CATEGORY_POLICE
                    || category == EmergencyNumber.EMERGENCY_SERVICE_CATEGORY_AMBULANCE
                    || category == EmergencyNumber.EMERGENCY_SERVICE_CATEGORY_FIRE_BRIGADE
                    || category == EmergencyNumber.EMERGENCY_SERVICE_CATEGORY_MARINE_GUARD
                    || category == EmergencyNumber.EMERGENCY_SERVICE_CATEGORY_MOUNTAIN_RESCUE
                    || category == EmergencyNumber.EMERGENCY_SERVICE_CATEGORY_MIEC
                    || category == EmergencyNumber.EMERGENCY_SERVICE_CATEGORY_AIEC;
        }

        public static boolean isRealEmergencyNumber(Context context, String phoneNumber) {
            if (context == null || TextUtils.isEmpty(phoneNumber)) {
                return false;
            }

            String realEccList = Settings.Global.getString(context.getContentResolver(),
                    "ecc_list_real" + getPrimaryCard(context));
            if (TextUtils.isEmpty(realEccList)) {
                return false;
            }

            HashMap<String, String> eccList = parserEccList(realEccList);
            return eccList.containsKey(phoneNumber);
        }

        /**
         * byte to inverted bit
         */
        public static String byteToInvertedBit(byte b) {
            return "" + (byte) ((b >> 0) & 0x1) + (byte) ((b >> 1) & 0x1) + (byte) ((b >> 2) & 0x1)
                    + (byte) ((b >> 3) & 0x1) + (byte) ((b >> 4) & 0x1) + (byte) ((b >> 5) & 0x1)
                    + (byte) ((b >> 6) & 0x1) + (byte) ((b >> 7) & 0x1);
        }

        public static String getUrnWithPhoneNumber(Context context, String phoneNumber) {
            if (context == null || TextUtils.isEmpty(phoneNumber)) {
                return EMUtils.DEFAULT_EMERGENCY_SERVICE_URN;
            }

            String realEccList = Settings.Global.getString(context.getContentResolver(),
                    "ecc_list_real" + getPrimaryCard(context));
            if (TextUtils.isEmpty(realEccList)) {
                return EMUtils.DEFAULT_EMERGENCY_SERVICE_URN;
            }

            HashMap<String, String> eccList = parserEccList(realEccList);
            String category = eccList.get(phoneNumber);
            if (TextUtils.isEmpty(category)) {
                return EMUtils.DEFAULT_EMERGENCY_SERVICE_URN;
            } else {
                return category;
            }
        }

        private static HashMap<String, String> parserEccList(String eccList) {
            HashMap<String, String> eccListMap = new HashMap<String, String>();

            String[] eccNumbers = eccList.split(",");
            for (String eccNumber : eccNumbers) {
                String[] eccInfo = eccNumber.split("@");
                if (eccInfo.length != 2) {
                    Log.w(TAG, "The current ecc number is: " + eccNumber + ". INVALID category!");
                    eccListMap.put(eccNumber, EMUtils.DEFAULT_EMERGENCY_SERVICE_URN);
                } else {
                    String urn = EMUtils.getEmergencyCallUrn(eccInfo[1]);
                    eccListMap.put(eccInfo[0], urn);
                    Log.d(TAG, "Handle ecc number " + eccInfo[0] + " as urn: " + urn);
                }
            }
            return eccListMap;
        }

        public static String getEmergencyCallUrn(String urnStr) {
            if (DEBUG) Log.i(TAG, "Get the emergency call's urn, category: " + urnStr);

            if (TextUtils.isEmpty(urnStr)) {
                Log.w(TAG, "The category is empty, return the default urn.");
                return DEFAULT_EMERGENCY_SERVICE_URN;
            }

            if (urnStr.startsWith(DEFAULT_EMERGENCY_SERVICE_URN)) {
                Log.d(TAG, "It is already urn str, needn't parse as category.");
                return urnStr;
            }

            String urnUri = DEFAULT_EMERGENCY_SERVICE_URN;
            try {
                int categoryValue = Integer.parseInt(urnStr);
                if ((categoryValue > 0) && (categoryValue < 128)) {
                    byte categoryByte = (byte) categoryValue;
                    String categoryBitString = byteToInvertedBit(categoryByte);
                    if (categoryBitString.charAt(0) == '1') {
                        urnUri = urnUri.concat(".").concat(EMERGENCY_CATEGORY_POLICE);
                    }
                    if (categoryBitString.charAt(1) == '1') {
                        urnUri = urnUri.concat(".").concat(EMERGENCY_CATEGORY_AMBULANCE);
                    }
                    if (categoryBitString.charAt(2) == '1') {
                        urnUri = urnUri.concat(".").concat(EMERGENCY_CATEGORY_FIRE);
                    }
                    if (categoryBitString.charAt(3) == '1') {
                        urnUri = urnUri.concat(".").concat(EMERGENCY_CATEGORY_MARINE);
                    }
                    if (categoryBitString.charAt(4) == '1') {
                        urnUri = urnUri.concat(".").concat(EMERGENCY_CATEGORY_MOUNTAIN);
                    }
                }
            } catch (NumberFormatException e) {
                Log.e(TAG, "Failed to get emergency urn as catch the ex: " + e.toString());
            }

            return urnUri;
        }

        public static int getEmergencyCallCategory(String urnUri) {
            if (DEBUG) Log.i(TAG, "Get the emergency call's category from urn: " + urnUri);

            if (TextUtils.isEmpty(urnUri)) {
                Log.w(TAG, "The urn uri is empty, return -1.");
                return -1;
            }

            int category = -1;
            String urnLowerCase = urnUri.toLowerCase();
            if (urnLowerCase.equals(DEFAULT_EMERGENCY_SERVICE_URN)) {
                return CATEGORY_VALUE_NONE;
            } else if (urnLowerCase.startsWith(DEFAULT_EMERGENCY_SERVICE_URN_PREFIX)) {
                String categoryString =
                        urnLowerCase.substring(DEFAULT_EMERGENCY_SERVICE_URN_PREFIX.length());
                if (!TextUtils.isEmpty(categoryString)) {
                    switch (categoryString) {
                        case EMERGENCY_CATEGORY_POLICE:
                            category = CATEGORY_VALUE_POLICE;
                            break;
                        case EMERGENCY_CATEGORY_AMBULANCE:
                            category = CATEGORY_VALUE_AMBULANCE;
                            break;
                        case EMERGENCY_CATEGORY_FIRE:
                            category = CATEGORY_VALUE_FIRE;
                            break;
                        case EMERGENCY_CATEGORY_MARINE:
                            category = CATEGORY_VALUE_MARINE;
                            break;
                        case EMERGENCY_CATEGORY_MOUNTAIN:
                            category = CATEGORY_VALUE_MOUNTAIN;
                            break;
                    }
                }
            }

            Log.d(TAG, "Get the emergency call's category as: " + category);
            return category;
        }
    }

    public static class ECBMRequest {
        public static final int ECBM_STEP_INVALID = 0;

        // For ECBM normal step.
        public static final int ECBM_STEP_DEREGISTER_NORMAL = 1;
        public static final int ECBM_STEP_DEATTACH_NORMAL = 2;
        public static final int ECBM_STEP_ATTACH_SOS = 3;
        public static final int ECBM_STEP_REGISTER_SOS = 4;
        public static final int ECBM_STEP_START_EMERGENCY_CALL = 5;
        // Needn't de-register for SOS.
        public static final int ECBM_STEP_DEATTACH_SOS = 6;
        public static final int ECBM_STEP_ATTACH_NORMAL = 7;
        public static final int ECBM_STEP_REGISTER_NORMAL = 8;

        // For ECBM error step.
        public static final int ECBM_STEP_FORCE_RESET = 10;

        private ArrayList<Integer> mRequestSteps;
        private int mIndex;
        private ImsCallSessionImpl mCallSession;

        private ECBMRequest(ImsCallSessionImpl callSession, int... steps) {
            mCallSession = callSession;

            mIndex = 0;
            mRequestSteps = new ArrayList<Integer>();
            for (int step : steps) {
                mRequestSteps.add(step);
            }
        }

        public static ECBMRequest get(ImsCallSessionImpl callSession, boolean asNormalCall,
                boolean needRemoveOldS2b) {
            if (asNormalCall) {
                return new ECBMRequest(callSession, ECBM_STEP_INVALID);
            } else if (needRemoveOldS2b) {
                return new ECBMRequest(
                        callSession,
                        ECBM_STEP_DEREGISTER_NORMAL,
                        ECBM_STEP_DEATTACH_NORMAL,
                        ECBM_STEP_ATTACH_SOS,
                        ECBM_STEP_REGISTER_SOS,
                        ECBM_STEP_START_EMERGENCY_CALL,
                        ECBM_STEP_DEATTACH_SOS,
                        ECBM_STEP_ATTACH_NORMAL,
                        ECBM_STEP_REGISTER_NORMAL);
            } else {
                return new ECBMRequest(
                        callSession,
                        ECBM_STEP_ATTACH_SOS,
                        ECBM_STEP_REGISTER_SOS,
                        ECBM_STEP_START_EMERGENCY_CALL,
                        ECBM_STEP_DEATTACH_SOS);
            }
        }

        public ImsCallSessionImpl getCallSession() {
            return mCallSession;
        }

        public int getCurStep() {
            return mRequestSteps.get(mIndex);
        }

        public int getNextStep() {
            mIndex = mIndex + 1;
            if (mIndex >= mRequestSteps.size()) {
                return ECBM_STEP_INVALID;
            } else {
                return mRequestSteps.get(mIndex);
            }
        }

        public int getExitECBMStep() {
            if (mRequestSteps.size() == 1) {
                mIndex = 0;
            } else {
                mIndex = (mRequestSteps.size() / 2) + 1;
            }
            return mRequestSteps.get(mIndex);
        }

        @Override
        public String toString() {
            StringBuilder builder = new StringBuilder();
            builder.append("call:" + mCallSession);
            builder.append(", current step:" + getCurStep());
            builder.append(", step size:" + mRequestSteps.size());
            return builder.toString();
        }
    }

    public static class PendingAction {
        private static final int RETRY_AFTER_MILLIS = 15 * 1000;

        public int _retryAfterMillis;
        public String _name;
        public int _action;
        public ArrayList<Object> _params;

        public PendingAction(String name, int action, Object... params) {
            this(RETRY_AFTER_MILLIS, name, action, params);
        }

        public PendingAction(int retryAfterMillis, String name, int action, Object... params) {
            _retryAfterMillis = retryAfterMillis;
            _name = name;
            _action = action;
            _params = new ArrayList<Object>();
            if (params != null) {
                for (Object param : params) {
                    _params.add(param);
                }
            } else {
                Log.d(TAG, "The action '" + _name + "' do not contains the params.");
            }
        }

        @Override
        public String toString() {
            return "PendingAction [_retryAfterMillis=" + _retryAfterMillis + ", _name=" + _name
                    + ", _action=" + _action + "]";
        }
    }

    public static class CallBarringInfo {
        // Refer to ImsUtInterface#CDIV_CF_XXX
        public int mCondition;
        // 0: disabled, 1: enabled
        public int mStatus;

        public CallBarringInfo() {
        }

        public void setCondition(int conditon) {
            mCondition = conditon;
        }

        public void setStatus(int status) {
            mStatus = status;
        }
    }

    public static class VideoQuality {
        public int _level;
        public int _width;
        public int _height;
        public int _frameRate;
        public int _bitRate;
        public int _brHi;
        public int _brLo;
        public int _frHi;
        public int _frLo;

        public VideoQuality(int level, int width, int height, int frameRate, int bitRate, int brHi,
                int brLo, int frHi, int frLo) {
            _level = level;
            _width = width;
            _height = height;
            _frameRate = frameRate;
            _bitRate = bitRate;
            _brHi = brHi;
            _brLo = brLo;
            _frHi = frHi;
            _frLo = frLo;
        }

        @Override
        public String toString() {
            return "[level=" + _level + ", width=" + _width + ", height=" + _height
                    + ", frameRate=" + _frameRate + ", bitRate=" + _bitRate + ", BrHi=" + _brHi
                    + ", BrLo=" + _brLo + ", FrHi=" + _frHi + ", FrLo=" + _frLo + "]";
        }
    }

    public static class ProviderUtils {
        public static final String CONTENT_URI = "content://com.spreadtrum.vowifi.accountsettings";

        // Defined support get configuration items.
        public static final String FUN_SECURITY = "security";
        public static final String FUN_REGISTER = "register";
        public static final String FUN_CALL     = "call";
        public static final String FUN_MESSAGE  = "message";
        public static final String FUN_UT       = "ut";
    }

    public static class CallCursor extends CursorWrapper {
        private Cursor mCursor;

        private static final String COL_SUPPORT_VIDEO_CALL = "supportVideoCall";
        private static final String COL_WITH_LOCATION      = "withLocation";
        private static final String COL_SOS_AS_NORMAL_CALL = "sosAsNormal";
        private static final String COL_SOS_REMOVE_OLD_S2B = "sosRemoveOldS2b";

        private static int sIndexSupportVideoCall = -1;
        private static int sIndexWithLocation = -1;
        private static int sIndexSosAsNormalCall = -1;
        private static int sIndexSosRemoveOldS2b = -1;

        public CallCursor(Cursor cursor) {
            super(cursor);
            mCursor = cursor;

            if (sIndexWithLocation < 0) {
                sIndexSupportVideoCall = mCursor.getColumnIndexOrThrow(COL_SUPPORT_VIDEO_CALL);
                sIndexWithLocation = mCursor.getColumnIndexOrThrow(COL_WITH_LOCATION);
                sIndexSosAsNormalCall = mCursor.getColumnIndexOrThrow(COL_SOS_AS_NORMAL_CALL);
                sIndexSosRemoveOldS2b = mCursor.getColumnIndexOrThrow(COL_SOS_REMOVE_OLD_S2B);
            }
        }

        public boolean supportVideoCall() {
            // Will be only one item.
            mCursor.moveToFirst();
            return mCursor.getInt(sIndexSupportVideoCall) > 0;
        }

        public boolean needWithLocation() {
            // Will be only one item.
            mCursor.moveToFirst();
            return mCursor.getInt(sIndexWithLocation) > 0;
        }

        public boolean sosAsNormalCall() {
            // Will be only one item.
            mCursor.moveToFirst();
            return mCursor.getInt(sIndexSosAsNormalCall) > 0;
        }

        public boolean sosNeedRemoveOldS2b() {
            // Will be only one item.
            mCursor.moveToFirst();
            return mCursor.getInt(sIndexSosRemoveOldS2b) > 0;
        }
    }

    public static class VideoType {
        public static final int NATIVE_VIDEO_TYPE_NONE = 0;
        public static final int NATIVE_VIDEO_TYPE_BROADCAST_ONLY = 1;
        public static final int NATIVE_VIDEO_TYPE_RECEIVED_ONLY = 2;
        public static final int NATIVE_VIDEO_TYPE_BIDIRECT = 3;

        public static int getNativeVideoType(int callType) {
            switch (callType) {
                case ImsCallProfile.CALL_TYPE_VOICE:
                    return NATIVE_VIDEO_TYPE_NONE;
                case ImsCallProfile.CALL_TYPE_VT_TX:
                    return NATIVE_VIDEO_TYPE_BROADCAST_ONLY;
                case ImsCallProfile.CALL_TYPE_VT_RX:
                    return NATIVE_VIDEO_TYPE_RECEIVED_ONLY;
                case ImsCallProfile.CALL_TYPE_VT:
                    return NATIVE_VIDEO_TYPE_BIDIRECT;
            }

            return NATIVE_VIDEO_TYPE_NONE;
        }

        public static int getNativeVideoType(VideoProfile profile) {
            if (profile == null) {
                Log.e(TAG, "Failed to get the native video type as video profile is null.");
                return NATIVE_VIDEO_TYPE_NONE;
            }

            boolean isVideo = VideoProfile.isVideo(profile.getVideoState());
            if (isVideo) {
                boolean isBidirectional = VideoProfile.isBidirectional(profile.getVideoState());
                if (isBidirectional) {
                    return NATIVE_VIDEO_TYPE_BIDIRECT;
                } else {
                    boolean isTrans = VideoProfile.isTransmissionEnabled(profile.getVideoState());
                    if (isTrans) {
                        return NATIVE_VIDEO_TYPE_BROADCAST_ONLY;
                    } else {
                        return NATIVE_VIDEO_TYPE_RECEIVED_ONLY;
                    }
                }
            } else {
                // Is audio only.
                return NATIVE_VIDEO_TYPE_NONE;
            }
        }

        public static int getCallType(int nativeVideoType) {
            switch (nativeVideoType) {
                case NATIVE_VIDEO_TYPE_NONE:
                    return ImsCallProfile.CALL_TYPE_VOICE;
                case NATIVE_VIDEO_TYPE_BROADCAST_ONLY:
                    return ImsCallProfile.CALL_TYPE_VT_TX;
                case NATIVE_VIDEO_TYPE_RECEIVED_ONLY:
                    return ImsCallProfile.CALL_TYPE_VT_RX;
                case NATIVE_VIDEO_TYPE_BIDIRECT:
                    return ImsCallProfile.CALL_TYPE_VT;
            }

            return ImsCallProfile.CALL_TYPE_VOICE;
        }

        public static VideoProfile getVideoProfile(int nativeVideoType) {
            int videoState = VideoProfile.STATE_AUDIO_ONLY;
            switch (nativeVideoType) {
                case NATIVE_VIDEO_TYPE_NONE:
                    videoState = VideoProfile.STATE_AUDIO_ONLY;
                    break;
                case NATIVE_VIDEO_TYPE_BROADCAST_ONLY:
                    videoState = VideoProfile.STATE_TX_ENABLED;
                    break;
                case NATIVE_VIDEO_TYPE_RECEIVED_ONLY:
                    videoState = VideoProfile.STATE_RX_ENABLED;
                    break;
                case NATIVE_VIDEO_TYPE_BIDIRECT:
                    videoState = VideoProfile.STATE_BIDIRECTIONAL;
                    break;
            }

            return new VideoProfile(videoState);
        }

        public static boolean updateAccept(int oldVideoType, int newVideoType) {
            switch (oldVideoType) {
                case NATIVE_VIDEO_TYPE_NONE:
                    return isInAcceptList(newVideoType,
                            NATIVE_VIDEO_TYPE_BROADCAST_ONLY,
                            NATIVE_VIDEO_TYPE_RECEIVED_ONLY,
                            NATIVE_VIDEO_TYPE_BIDIRECT);
                case NATIVE_VIDEO_TYPE_BROADCAST_ONLY:
                case NATIVE_VIDEO_TYPE_RECEIVED_ONLY:
                    return isInAcceptList(newVideoType,
                            NATIVE_VIDEO_TYPE_NONE,
                            NATIVE_VIDEO_TYPE_BIDIRECT);
                case NATIVE_VIDEO_TYPE_BIDIRECT:
                    return isInAcceptList(newVideoType,
                            NATIVE_VIDEO_TYPE_NONE,
                            NATIVE_VIDEO_TYPE_BROADCAST_ONLY,
                            NATIVE_VIDEO_TYPE_RECEIVED_ONLY);
            }

            return false;
        }

        private static boolean isInAcceptList(int orgVideoType, int... list) {
            for (int videoType : list) {
                if (orgVideoType == videoType) return true;
            }

            return false;
        }
    }

    public static class MyToast {
        public static Toast makeText(Context context, String text, int duration) {
            Toast toast = Toast.makeText(context, text, duration);
            toast.getWindowParams().flags = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                    | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED;
            return toast;
        }

        public static Toast makeText(Context context, int resId, int duration) {
            Toast toast = Toast.makeText(context, resId, duration);
            toast.getWindowParams().flags = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                    | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED;
            return toast;
        }
    }

    public static class FDNHelper {
        private static final String FDN_CONTENT_URI = "content://icc/fdn/subId/";
        private static final String[] FDN_PROJECTION = new String[] {
                "name", "number"
        };
        private static final int FDN_COL_NUMBER = 1;

        private Context mContext;
        private int mSubId;

        public FDNHelper(Context context, int subId) {
            mContext = context;
            mSubId = subId;
        }

        public boolean isEnabled() {
            TelephonyManagerEx tmEx = TelephonyManagerEx.from(mContext);
            return tmEx.getIccFdnEnabled(mSubId);
        }

        public boolean isAccept(String callee) {
            if (TextUtils.isEmpty(callee)) {
                Log.e(TAG, "The callee is empty, can not check if FDN accept.");
                return false;
            }

            ArrayList<String> fdnList = getFDNList(mContext, mSubId);
            if (fdnList == null || fdnList.size() < 1) {
                // As the FDN list is empty, return false as do not accept.
                Log.d(TAG, "FDN list is null or empty, do not accept the callee: " + callee);
                return false;
            }

            for (String fdn : fdnList) {
                if (PhoneNumberUtils.compare(callee, fdn)) {
                    Log.d(TAG, "The callee[" + callee + "] matched the fdn number[" + fdn + "]");
                    return true;
                }
            }

            // It means do not find the matched FDN, return as do not accept.
            Log.d(TAG, "Do not find any matched FDN number, do not accept the callee: " + callee);
            return false;
        }

        private ArrayList<String> getFDNList(Context context, int subId) {
            Cursor cursor = context.getContentResolver().query(
                    Uri.parse(FDN_CONTENT_URI + subId), FDN_PROJECTION, null, null, null);
            try {
                if (cursor != null && cursor.getCount() > 0) {
                    ArrayList<String> fdnList = new ArrayList<String>();
                    while (cursor.moveToNext()) {
                        String number = cursor.getString(FDN_COL_NUMBER);
                        if (!TextUtils.isEmpty(number)) {
                            fdnList.add(number);
                            Log.d(TAG, "Add the fdn number[" + number + "] to list.");
                        }
                    }
                    return fdnList;
                } else {
                    Log.w(TAG, "Query FDN list finished, but cursor's count is: "
                            + (cursor == null ? "null" : cursor.getCount()));
                }
            } finally {
                if (cursor != null) {
                    cursor.close();
                    cursor = null;
                }
            }

            // Meet some error, return null.
            return null;
        }
    }

    public static class JSONUtils {
        // Callback constants
        public static final String KEY_EVENT_CODE = "event_code";
        public static final String KEY_EVENT_NAME = "event_name";
        public static final String KEY_CALLBACK_HASHCODE = "callback_hashcode";

        // Security
        public static final String KEY_SESSION_ID = "session_id";

        public static final int STATE_CODE_SECURITY_INVALID_ID       = -1;
        public static final int STATE_CODE_SECURITY_AUTH_FAILED      = -2;
        public static final int STATE_CODE_SECURITY_LOCAL_IP_IS_NULL = -3;
        public static final int STATE_CODE_SECURITY_NO_REQUEST       = -4;
        public static final int STATE_CODE_SECURITY_STOP_TIMEOUT     = -5;

        public final static int SECURITY_EVENT_CODE_BASE = 0;
        public final static int EVENT_CODE_ATTACH_SUCCESSED = SECURITY_EVENT_CODE_BASE + 1;
        public final static int EVENT_CODE_ATTACH_FAILED = SECURITY_EVENT_CODE_BASE + 2;
        public final static int EVENT_CODE_ATTACH_PROGRESSING = SECURITY_EVENT_CODE_BASE + 3;
        public final static int EVENT_CODE_ATTACH_STOPPED = SECURITY_EVENT_CODE_BASE + 4;

        public final static String EVENT_ATTACH_SUCCESSED = "attach_successed";
        public final static String EVENT_ATTACH_FAILED = "attach_failed";
        public final static String EVENT_ATTACH_PROGRESSING = "attach_progressing";
        public final static String EVENT_ATTACH_STOPPED = "attach_stopped";

        // Keys for security callback
        public final static String KEY_PROGRESS_STATE = "progress_state";
        public final static String KEY_LOCAL_IP4 = "local_ip4";
        public final static String KEY_LOCAL_IP6 = "local_ip6";
        public final static String KEY_PCSCF_IP4 = "pcscf_ip4";
        public final static String KEY_PCSCF_IP6 = "pcscf_ip6";
        public final static String KEY_DNS_IP4 = "dns_ip4";
        public final static String KEY_DNS_IP6 = "dns_ip6";
        public final static String KEY_PREF_IP4 = "pref_ip4";
        public final static String KEY_TYPE = "type";
        public final static String KEY_SUPPORT_MOBIKE = "support_mobike";

        public final static int USE_IP4 = 0;
        public final static int USE_IP6 = 1;

        // Register
        public static final String KEY_STATE_CODE = "state_code";
        public static final String KEY_RETRY_AFTER = "retry_after";
        public static final String KEY_P_ASSOCIATED_URI = "P-Associated-URI";

        public static final int STATE_CODE_REG_PING_FAILED = 1;
        public static final int STATE_CODE_REG_NATIVE_FAILED = 2;
        public static final int STATE_CODE_REG_AUTH_FAILED = 3;

        public static final int REGISTER_EVENT_CODE_BASE = 50;
        public static final int EVENT_CODE_LOGIN_OK = REGISTER_EVENT_CODE_BASE + 1;
        public static final int EVENT_CODE_LOGIN_FAILED = REGISTER_EVENT_CODE_BASE + 2;
        public static final int EVENT_CODE_LOGOUTED = REGISTER_EVENT_CODE_BASE + 3;
        public static final int EVENT_CODE_REREGISTER_OK = REGISTER_EVENT_CODE_BASE + 4;
        public static final int EVENT_CODE_REREGISTER_FAILED = REGISTER_EVENT_CODE_BASE + 5;
        public static final int EVENT_CODE_REGISTER_STATE_UPDATE = REGISTER_EVENT_CODE_BASE + 6;

        public static final String EVENT_LOGIN_OK = "login_ok";
        public static final String EVENT_LOGIN_FAILED = "login_failed";
        public static final String EVENT_LOGOUTED = "logouted";
        public static final String EVENT_REREGISTER_OK = "refresh_ok";
        public static final String EVENT_REREGISTER_FAILED = "refresh_failed";
        public static final String EVENT_REGISTER_STATE_UPDATE = "state_update";

        // Call & Conference
        public static final String KEY_ID = "id";
        public static final String KEY_ALERT_TYPE = "alert_type";
        public static final String KEY_IS_VIDEO = "is_video";
        public static final String KEY_PEER_SUPPORT_VIDEO = "peer_support_video";
        public static final String KEY_PHONE_NUM = "phone_num";
        public static final String KEY_SIP_URI = "sip_uri";
        public static final String KEY_VIDEO_HEIGHT = "video_height";
        public static final String KEY_VIDEO_WIDTH = "video_width";
        public static final String KEY_VIDEO_LEVEL = "video_level";
        public static final String KEY_VIDEO_TYPE = "video_type";
        public static final String KEY_RTP_RECEIVED = "rtp_received";
        public static final String KEY_RTCP_LOSE = "rtcp_lose";
        public static final String KEY_RTCP_JITTER = "rtcp_jitter";
        public static final String KEY_RTCP_RTT = "rtcp_rtt";
        public static final String KEY_CONF_PART_NEW_STATUS = "conf_part_new_status";
        public static final String KEY_ECALL_IND_URN_URI = "emergency_call_ind_urn_uri";
        public static final String KEY_ECALL_IND_REASON = "emergency_call_ind_reason";
        public static final String KEY_ECALL_IND_ACTION_TYPE = "emergency_call_ind_action_type";
        public static final String KEY_USSD_INFO_RECEIVED = "ussd_info_received";
        public static final String KEY_USSD_MODE = "ussd_mode";
        public static final String KEY_VOICE_CODEC = "voice_codec";
        public static final String KEY_CONF_CHILD_ID = "conf_child_id";

        // Call
        public static final int CALL_EVENT_CODE_BASE = 100;
        public static final int EVENT_CODE_CALL_INCOMING = CALL_EVENT_CODE_BASE + 1;
        public static final int EVENT_CODE_CALL_OUTGOING = CALL_EVENT_CODE_BASE + 2;
        public static final int EVENT_CODE_CALL_ALERTED = CALL_EVENT_CODE_BASE + 3;
        public static final int EVENT_CODE_CALL_TALKING = CALL_EVENT_CODE_BASE + 4;
        public static final int EVENT_CODE_CALL_TERMINATE = CALL_EVENT_CODE_BASE + 5;
        public static final int EVENT_CODE_CALL_HOLD_OK = CALL_EVENT_CODE_BASE + 6;
        public static final int EVENT_CODE_CALL_HOLD_FAILED = CALL_EVENT_CODE_BASE + 7;
        public static final int EVENT_CODE_CALL_RESUME_OK = CALL_EVENT_CODE_BASE + 8;
        public static final int EVENT_CODE_CALL_RESUME_FAILED = CALL_EVENT_CODE_BASE + 9;
        public static final int EVENT_CODE_CALL_HOLD_RECEIVED = CALL_EVENT_CODE_BASE + 10;
        public static final int EVENT_CODE_CALL_RESUME_RECEIVED = CALL_EVENT_CODE_BASE + 11;
        public static final int EVENT_CODE_CALL_UPDATE_VIDEO_OK = CALL_EVENT_CODE_BASE + 12;
        public static final int EVENT_CODE_CALL_UPDATE_VIDEO_FAILED = CALL_EVENT_CODE_BASE + 13;
        public static final int EVENT_CODE_CALL_ADD_VIDEO_REQUEST = CALL_EVENT_CODE_BASE + 14;
        public static final int EVENT_CODE_CALL_ADD_VIDEO_CANCEL = CALL_EVENT_CODE_BASE + 15;
        public static final int EVENT_CODE_CALL_RTP_RECEIVED = CALL_EVENT_CODE_BASE + 16;
        public static final int EVENT_CODE_CALL_RTCP_CHANGED = CALL_EVENT_CODE_BASE + 17;
        public static final int EVENT_CODE_CALL_IS_FOCUS = CALL_EVENT_CODE_BASE + 18;
        public static final int EVENT_CODE_CALL_IS_EMERGENCY = CALL_EVENT_CODE_BASE + 19;
        public static final int EVENT_CODE_USSD_INFO_RECEIVED = CALL_EVENT_CODE_BASE + 20;
        public static final int EVENT_CODE_CALL_IS_FORWARDED = CALL_EVENT_CODE_BASE + 21;
        public static final int EVENT_CODE_CALL_REQUIRE_DEREGISTER = CALL_EVENT_CODE_BASE + 22;
        public static final int EVENT_CODE_CALL_REQUIRE_ALERT_INFO = CALL_EVENT_CODE_BASE + 23;

        public static final String EVENT_CALL_INCOMING = "call_incoming";
        public static final String EVENT_CALL_OUTGOING = "call_outgoing";
        public static final String EVENT_CALL_ALERTED = "call_alerted";
        public static final String EVENT_CALL_TALKING = "call_talking";
        public static final String EVENT_CALL_TERMINATE = "call_terminate";
        public static final String EVENT_CALL_HOLD_OK = "call_hold_ok";
        public static final String EVENT_CALL_HOLD_FAILED = "call_hold_failed";
        public static final String EVENT_CALL_RESUME_OK = "call_resume_ok";
        public static final String EVENT_CALL_RESUME_FAILED = "call_resume_failed";
        public static final String EVENT_CALL_HOLD_RECEIVED = "call_hold_received";
        public static final String EVENT_CALL_RESUME_RECEIVED = "call_resume_received";
        public static final String EVENT_CALL_UPDATE_VIDEO_OK = "call_update_video_ok";
        public static final String EVENT_CALL_UPDATE_VIDEO_FAILED = "call_update_video_failed";
        public static final String EVENT_CALL_ADD_VIDEO_REQUEST = "call_add_video_request";
        public static final String EVENT_CALL_ADD_VIDEO_CANCEL = "call_add_video_cancel";
        public static final String EVENT_CALL_RTP_RECEIVED = "call_rtp_received";
        public static final String EVENT_CALL_RTCP_CHANGED = "call_rtcp_changed";
        public static final String EVENT_CALL_IS_FOCUS = "call_is_focus";
        public static final String EVENT_CALL_IS_EMERGENCY = "call_is_emergency";
        public static final String EVENT_USSD_INFO_RECEIVED = "ussd_info_received";
        public static final String EVENT_CALL_IS_FORWARDED = "call_is_forwarded";
        public static final String EVENT_CALL_REQUIRE_DEREGISTER = "call_require_deregister";
        public static final String EVENT_CALL_REQUIRE_ALERT_INFO = "call_require_alert_info";

        // call's cookie string
        public static final String COOKIE_ITEM_CLIR = "clir_mode";
        public static final String COOKIE_ITEM_CNI_TYPE = "cni_type";
        public static final String COOKIE_ITEM_CNI_INFO = "cni_info";
        public static final String COOKIE_ITEM_CNI_AGE = "cni_age";

        // Conference
        public static final int CONF_EVENT_CODE_BASE = 200;
        public static final int EVENT_CODE_CONF_OUTGOING = CONF_EVENT_CODE_BASE + 1;
        public static final int EVENT_CODE_CONF_ALERTED = CONF_EVENT_CODE_BASE + 2;
        public static final int EVENT_CODE_CONF_CONNECTED = CONF_EVENT_CODE_BASE + 3;
        public static final int EVENT_CODE_CONF_DISCONNECTED = CONF_EVENT_CODE_BASE + 4;
        public static final int EVENT_CODE_CONF_INVITE_ACCEPT = CONF_EVENT_CODE_BASE + 5;
        public static final int EVENT_CODE_CONF_INVITE_FAILED = CONF_EVENT_CODE_BASE + 6;
        public static final int EVENT_CODE_CONF_KICK_ACCEPT = CONF_EVENT_CODE_BASE + 7;
        public static final int EVENT_CODE_CONF_KICK_FAILED = CONF_EVENT_CODE_BASE + 8;
        public static final int EVENT_CODE_CONF_PART_UPDATE = CONF_EVENT_CODE_BASE + 9;
        public static final int EVENT_CODE_CONF_HOLD_OK = CONF_EVENT_CODE_BASE + 10;
        public static final int EVENT_CODE_CONF_HOLD_FAILED = CONF_EVENT_CODE_BASE + 11;
        public static final int EVENT_CODE_CONF_RESUME_OK = CONF_EVENT_CODE_BASE + 12;
        public static final int EVENT_CODE_CONF_RESUME_FAILED = CONF_EVENT_CODE_BASE + 13;
        public static final int EVENT_CODE_CONF_HOLD_RECEIVED = CONF_EVENT_CODE_BASE + 14;
        public static final int EVENT_CODE_CONF_RESUME_RECEIVED = CONF_EVENT_CODE_BASE + 15;
        public static final int EVENT_CODE_CONF_RTP_RECEIVED = CONF_EVENT_CODE_BASE + 16;
        public static final int EVENT_CODE_CONF_RTCP_CHANGED = CONF_EVENT_CODE_BASE + 17;
        public static final int EVENT_CODE_CONF_REFER_NOTIFIED = CONF_EVENT_CODE_BASE + 18;

        public static final String EVENT_CONF_OUTGOING = "conf_outgoing";
        public static final String EVENT_CONF_ALERTED = "conf_alerted";
        public static final String EVENT_CONF_CONNECTED = "conf_connected";
        public static final String EVENT_CONF_DISCONNECTED = "conf_disconnected";
        public static final String EVENT_CONF_INVITE_ACCEPT = "conf_invite_accept";
        public static final String EVENT_CONF_INVITE_FAILED = "conf_invite_failed";
        public static final String EVENT_CONF_KICK_ACCEPT = "conf_kick_accept";
        public static final String EVENT_CONF_KICK_FAILED = "conf_kick_failed";
        public static final String EVENT_CONF_PART_UPDATE = "conf_part_update";
        public static final String EVENT_CONF_HOLD_OK = "conf_hold_ok";
        public static final String EVENT_CONF_HOLD_FAILED = "conf_hold_failed";
        public static final String EVENT_CONF_RESUME_OK = "conf_resume_ok";
        public static final String EVENT_CONF_RESUME_FAILED = "conf_resume_failed";
        public static final String EVENT_CONF_HOLD_RECEIVED = "conf_hold_received";
        public static final String EVENT_CONF_RESUME_RECEIVED = "conf_resume_received";
        public static final String EVENT_CONF_RTP_RECEIVED = "conf_rtp_received";
        public static final String EVENT_CONF_RTCP_CHANGED = "conf_rtcp_changed";
        public static final String EVENT_CONF_REFER_NOTIFIED = "conf_refer_notified";

        // Call require alert info type
        public static final String ALERT_INFO_CALL_FAILURE = "info_call_failure";

        // Bug 1093173: add supplementary notification for vowifi.
        public static final String ALERT_TYPE_CALL_WAITING = "call_waiting";
        public static final String ALERT_TYPE_CALL_FORWARD = "call_forward";

        // Voice
        public static final int VOICE_EVENT_CODE_BASE = 280;
        public static final int EVENT_CODE_VOICE_CODEC = VOICE_EVENT_CODE_BASE + 1;

        public static final String EVENT_VOICE_CODEC = "voice_negociated_codec";

        // Video
        public static final int VIDEO_EVENT_CODE_BASE = 300;
        public static final int EVENT_CODE_LOCAL_VIDEO_RESIZE = VIDEO_EVENT_CODE_BASE + 1;
        public static final int EVENT_CODE_REMOTE_VIDEO_RESIZE = VIDEO_EVENT_CODE_BASE + 2;
        public static final int EVENT_CODE_LOCAL_VIDEO_LEVEL_UPDATE = VIDEO_EVENT_CODE_BASE + 3;

        public static final String EVENT_LOCAL_VIDEO_RESIZE = "local_video_resize";
        public static final String EVENT_REMOTE_VIDEO_RESIZE = "remote_video_resize";
        public static final String EVENT_LOCAL_VIDEO_LEVEL_UPDATE = "local_video_level_update";

        // UT
        public static final String KEY_UT_CF_TIME_SECONDS = "ut_cf_time_seconds";
        public static final String KEY_UT_CF_RULES = "ut_cf_rules";
        public static final String KEY_UT_CF_RULE_ENABLED = "ut_cf_rule_enabled";
        public static final String KEY_UT_CF_RULE_MEDIA = "ut_cf_rule_media";
        public static final String KEY_UT_CF_CONDS = "ut_cf_conditions";
        public static final String KEY_UT_CF_ACTION_TARGET = "ut_cf_action_target";
        public static final String KEY_UT_CB_RULES = "ut_cb_rules";
        public static final String KEY_UT_CB_RULE_ENABLED = "ut_cb_rule_enabled";
        public static final String KEY_UT_CB_CONDS = "ut_cb_conditions";
        public static final String KEY_UT_ENABLED = "ut_enabled";
        public static final String KEY_UT_CLIR_M_PARAM = "ut_clir_m_param";

        public static final int UT_EVENT_CODE_BASE = 350;
        public static final int EVENT_CODE_UT_QUERY_CB_OK = UT_EVENT_CODE_BASE + 1;
        public static final int EVENT_CODE_UT_QUERY_CB_FAILED = UT_EVENT_CODE_BASE + 2;
        public static final int EVENT_CODE_UT_QUERY_CF_OK = UT_EVENT_CODE_BASE + 3;
        public static final int EVENT_CODE_UT_QUERY_CF_FAILED = UT_EVENT_CODE_BASE + 4;
        public static final int EVENT_CODE_UT_QUERY_CW_OK = UT_EVENT_CODE_BASE + 5;
        public static final int EVENT_CODE_UT_QUERY_CW_FAILED = UT_EVENT_CODE_BASE + 6;
        public static final int EVENT_CODE_UT_UPDATE_CB_OK = UT_EVENT_CODE_BASE + 7;
        public static final int EVENT_CODE_UT_UPDATE_CB_FAILED = UT_EVENT_CODE_BASE + 8;
        public static final int EVENT_CODE_UT_UPDATE_CF_OK = UT_EVENT_CODE_BASE + 9;
        public static final int EVENT_CODE_UT_UPDATE_CF_FAILED = UT_EVENT_CODE_BASE + 10;
        public static final int EVENT_CODE_UT_UPDATE_CW_OK = UT_EVENT_CODE_BASE + 11;
        public static final int EVENT_CODE_UT_UPDATE_CW_FAILED = UT_EVENT_CODE_BASE + 12;
        public static final int EVENT_CODE_UT_QUERY_CLIR_OK = UT_EVENT_CODE_BASE + 13;
        public static final int EVENT_CODE_UT_QUERY_CLIR_FAILED = UT_EVENT_CODE_BASE + 14;
        public static final int EVENT_CODE_UT_UPDATE_CLIR_OK = UT_EVENT_CODE_BASE + 15;
        public static final int EVENT_CODE_UT_UPDATE_CLIR_FAILED = UT_EVENT_CODE_BASE + 16;

        public static final String EVENT_UT_QUERY_CB_OK = "ut_query_call_barring_ok";
        public static final String EVENT_UT_QUERY_CB_FAILED = "ut_query_call_barring_failed";
        public static final String EVENT_UT_QUERY_CF_OK = "ut_query_call_forward_ok";
        public static final String EVENT_UT_QUERY_CF_FAILED = "ut_query_call_forward_failed";
        public static final String EVENT_UT_QUERY_CW_OK = "ut_query_call_waiting_ok";
        public static final String EVENT_UT_QUERY_CW_FAILED = "ut_query_call_waiting_failed";
        public static final String EVENT_UT_UPDATE_CB_OK = "ut_update_call_barring_ok";
        public static final String EVENT_UT_UPDATE_CB_FAILED = "ut_update_call_barring_failed";
        public static final String EVENT_UT_UPDATE_CF_OK = "ut_update_call_forward_ok";
        public static final String EVENT_UT_UPDATE_CF_FAILED = "ut_update_call_forward_failed";
        public static final String EVENT_UT_UPDATE_CW_OK = "ut_update_call_waiting_ok";
        public static final String EVENT_UT_UPDATE_CW_FAILED = "ut_update_call_waiting_failed";
        public static final String EVENT_UT_QUERY_CLIR_OK = "ut_query_clir_ok";
        public static final String EVENT_UT_QUERY_CLIR_FAILED = "ut_query_clir_failed";
        public static final String EVENT_UT_UPDATE_CLIR_OK = "ut_update_clir_ok";
        public static final String EVENT_UT_UPDATE_CLIR_FAILED = "ut_update_clir_failed";

        // Query call forward result of media
        public static final String RULE_MEDIA_AUDIO = "audio";
        public static final String RULE_MEDIA_VIDEO = "video";

        // Sms
        public static final String KEY_SMS_TOKEN = "token";
        public static final String KEY_SMS_MESSAGE_REF = "messageRef";
        public static final String KEY_SMS_RESULT = "result";
        public static final String KEY_SMS_REASON = "reason";
        public static final String KEY_SMS_PDU = "pdu";

        public static final int SMS_EVENT_CODE_BASE = 400;
        public static final int EVENT_CODE_SMS_SEND_FINISHED = SMS_EVENT_CODE_BASE + 1;
        public static final int EVENT_CODE_SMS_STATUS_REPORT_RECEIVED = SMS_EVENT_CODE_BASE + 2;
        public static final int EVENT_CODE_SMS_RECEIVED = SMS_EVENT_CODE_BASE + 3;

        public static final String EVENT_SMS_SEND_FINISHED = "sms_send_finished";
        public static final String EVENT_SMS_STATUS_REPORT_RECEIVED = "sms_status_report_received";
        public static final String EVENT_SMS_RECEIVED = "sms_received";
    }

    public static class Version {
        /**
         * Update the version number please follow as this:
         *     AIDL - please update the first version number.
         *     Native - please update the second version number.
         *     Others - please update the third version number.
         */
        public static final String NUMBER = "3.0.1";

        /**
         * Old detail as this:
         * 1.0.0[Init]
         * 1.0.1[Get the urn from DB if there isn't category info.]
         * 1.0.2[Support require CNI when vowifi register.]
         * 1.0.3[Get sms Tp-Mr from SIM, and sync SS after register.]
         * 1.0.4[Delay 500ms to handle the hold failed.]
         * 1.0.5[Handle the EM call with whole step. (PANI to PCNI)]
         * 2.0.0[Support query CLIR.]
         * 3.0.0[Support low power for voice call.]
         */
        public static final String DETAIL = "Toast call waiting and call forward for ims alert type.";

        public static String getVersionInfo() {
            return NUMBER + "[" + DETAIL + "]";
        }
    }
}
