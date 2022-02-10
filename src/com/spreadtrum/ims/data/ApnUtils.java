package com.spreadtrum.ims.data;

import java.util.ArrayList;

import android.telephony.ServiceState;
import android.telephony.data.ApnSetting;
import android.telephony.data.DataProfile;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.uicc.IccRecords;
import android.database.Cursor;
import android.provider.Telephony;
import android.net.NetworkUtils;
public class ApnUtils {
    private static final boolean DBG = true;
    public static ArrayList<ApnSetting> createApnList(Cursor cursor) {
        ArrayList<ApnSetting> mnoApns = new ArrayList<ApnSetting>();

        if (cursor.moveToFirst()) {
            do {
                ApnSetting apn = ApnSetting.makeApnSetting(cursor);
                if (apn == null) {
                    continue;
                }
                mnoApns.add(apn);
            } while (cursor.moveToNext());
        }

        return mnoApns;
    }

    @VisibleForTesting
    public static DataProfile createDataProfile(ApnSetting apn,boolean isPreferred) {
        int profileType;

        int networkTypeBitmask = apn.getNetworkTypeBitmask();

        if (networkTypeBitmask == 0) {
            profileType = DataProfile.TYPE_COMMON;
        } else if (ServiceState.bearerBitmapHasCdma(networkTypeBitmask)) {
            profileType = DataProfile.TYPE_3GPP2;
        } else {
            profileType = DataProfile.TYPE_3GPP;
        }
        return new DataProfile.Builder()
                .setProfileId(apn.getProfileId())
                .setApn(apn.getApnName())
                .setProtocolType(apn.getProtocol())
                .setAuthType(apn.getAuthType())
                .setUserName(apn.getUser())
                .setPassword(apn.getPassword())
                .setType(profileType)
                .setMaxConnectionsTime(apn.getMaxConnsTime())
                .setMaxConnections(apn.getMaxConns())
                .setWaitTime(apn.getWaitTime())
                .enable(apn.isEnabled())
                .setSupportedApnTypesBitmask(apn.getApnTypeBitmask())
                .setRoamingProtocolType(apn.getRoamingProtocol())
                .setBearerBitmask(networkTypeBitmask)
                .setMtu(apn.getMtu())
                .setPersistent(apn.isPersistent())
                .setPreferred(isPreferred)
                .build();
    }
}
