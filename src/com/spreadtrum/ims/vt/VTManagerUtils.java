package com.spreadtrum.ims.vt;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnCancelListener;
import android.content.DialogInterface.OnKeyListener;
import android.content.Intent;
import android.telecom.VideoProfile;
import android.telecom.TelecomManager;
import android.net.Uri;
import android.view.View;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.view.LayoutInflater;
import android.widget.Toast;
import android.widget.TextView;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;

import com.spreadtrum.ims.ImsRadioInterface;
import com.spreadtrum.ims.R;

import com.android.internal.telephony.GsmCdmaPhone;
import com.android.internal.telephony.CallStateException;
import com.android.internal.telephony.CommandsInterface;
import android.os.Message;
import android.widget.CheckBox;
import android.preference.PreferenceManager;
import android.content.SharedPreferences;
import com.android.ims.ImsManager;

public class VTManagerUtils {
    private static final String TAG = VTManagerUtils.class.getSimpleName();

    public static final int VODEO_CALL_FDN_BLOCKED = 241;

    private static void log(String string){
        android.util.Log.i(TAG, string);
    }

    /*SPRD: add for bug673215 Vodafone new feature*/
    public static AlertDialog showVowifiRegisterToast(Context context) {

        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
        boolean nomore = sp.getBoolean("nomore", false);
        if (nomore) {
            log("showVoWifiNotification nomore ");
            return null;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(context);

        LayoutInflater inflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        View view = inflater.inflate(R.xml.vowifi_register_dialog, null);

        builder.setView(view);
        builder.setTitle(context.getString(R.string.vowifi_connected_title));
        builder.setMessage(context.getString(R.string.vowifi_connected_message));
        CheckBox cb = (CheckBox) view.findViewById(R.id.nomore);

        builder.setPositiveButton(context.getString(R.string.vowifi_connected_continue), new android.content.DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {
                if (cb.isChecked()) {
                    SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
                    SharedPreferences.Editor editor = sp.edit();
                    editor.putBoolean("nomore", true);
                    editor.apply();
                }
                log("Vowifi service Continue, cb.isChecked = " + cb.isChecked());
                if (dialog != null) {
                    dialog.dismiss();
                    dialog = null;
                }
            }
        });
        builder.setNegativeButton(context.getString(R.string.vowifi_connected_disable), new android.content.DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {
                if (cb.isChecked()) {
                    SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
                    SharedPreferences.Editor editor = sp.edit();
                    editor.putBoolean("nomore", true);
                    editor.apply();
                }
                log("Vowifi service disable, cb.isChecked = " + cb.isChecked());
                ImsManager.setWfcSetting(context, false);
                if (dialog != null) {
                    dialog.dismiss();
                    dialog = null;
                }
            }
        });
        builder.setCancelable(false);
        AlertDialog dialog = builder.create();
        dialog = builder.create();
        dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_DIALOG);
        dialog.show();
        return  dialog;
    }

    /* SPRD: Add feature of low battery for Reliance @{ */
    public static AlertDialog showLowBatteryMediaChangeAlert(final Context context, final int id, final ImsRadioInterface ril,
                                                             final int mediaRequest) {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(context.getString(R.string.low_battery_warning_title));
        builder.setMessage(context.getString(R.string.low_battery_warning_message));
        builder.setPositiveButton(
                context.getString(R.string.low_battery_continue_video_call),
                new android.content.DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        if (dialog != null) {
                            dialog.dismiss();
                        }
                    }
                });
        builder.setNegativeButton(
                context.getString(R.string.low_battery_convert_to_voice_call),
                new android.content.DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        if (ril != null) {
                            ril.requestVolteCallMediaChange(mediaRequest, id, null);
                            log("Battery is low,user choose to downgrade to voice call.");
                        }
                        if (dialog != null) {
                            dialog.dismiss();
                        }
                    }
                });
        builder.setCancelable(false);
        AlertDialog dialog = builder.create();
        dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_DIALOG);
        dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
        return dialog;
    }
    /* @} */

}
