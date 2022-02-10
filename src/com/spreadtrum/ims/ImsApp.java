package com.spreadtrum.ims;

import android.app.Application;
import android.util.Log;
import android.content.ComponentName;
import android.content.Intent;
import android.os.UserHandle;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;


public class ImsApp extends Application {
    private static final String TAG = ImsApp.class.getSimpleName();

    @Override
    public void onCreate() {
        super.onCreate();
        PackageInfo packageInfo = null;
        try {
            packageInfo = this.getPackageManager().getPackageInfo(this.getPackageName(), 0);
        } catch (NameNotFoundException e) {
            e.printStackTrace();
        }
        String versionName = "";
        if(packageInfo != null){
            versionName = " version:" + packageInfo.versionName;
        }
        Log.i(TAG, "ImsApp Boot Successfully." + versionName);
        if(!ImsConfigImpl.isImsEnabledBySystemProperties() && !ImsConfigImpl.isVoWiFiEnabledByBoard(this)){
            Log.w(TAG, "Could Not Start Ims Service because volte disabled by system properties!");
            return;
        }
        if(UserHandle.myUserId() != UserHandle.USER_OWNER){
            return;
        }
        ComponentName comp = new ComponentName(this.getPackageName(),
                ImsService.class.getName());
        ComponentName service = this.startService(new Intent().setComponent(comp));
        if (service == null) {
            Log.w(TAG, "Could Not Start Service " + comp.toString());
        } else {
            Log.i(TAG, "ImsService Boot Successfully!");
        }
    }

}
