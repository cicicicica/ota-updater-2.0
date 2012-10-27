/*
 * Copyright (C) 2012 OTA Update Center
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.otaupdater.utils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;
import org.json.JSONObject;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import com.otaupdater.R;
import com.otaupdater.SettingsActivity;

public class Utils {
    public static String md5(String s) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            digest.update(s.getBytes());

            return byteArrToStr(digest.digest());
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "";
    }

    public static String md5(File f) {
        InputStream in = null;
        try {
            in = new FileInputStream(f);

            MessageDigest digest = MessageDigest.getInstance("MD5");

            byte[] buf = new byte[4096];
            int nRead = -1;
            while ((nRead = in.read(buf)) != -1) {
                digest.update(buf, 0, nRead);
            }

            return byteArrToStr(digest.digest());
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (in != null) {
                try { in.close(); }
                catch (IOException e) { }
            }
        }
        return "";
    }

    public static String hmac(String str, String key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key.getBytes(), mac.getAlgorithm()));
            return byteArrToStr(mac.doFinal(str.getBytes()));
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    public static void toastWrapper(final Activity activity, final CharSequence text, final int duration) {
        activity.runOnUiThread(new Runnable() {
            @Override public void run() {
                Toast.makeText(activity, text, duration).show();
            }
        });
    }

    public static void toastWrapper(final Activity activity, final int resId, final int duration) {
        activity.runOnUiThread(new Runnable() {
            @Override public void run() {
                Toast.makeText(activity, resId, duration).show();
            }
        });
    }

    public static void toastWrapper(final View view, final CharSequence text, final int duration) {
        view.post(new Runnable() {
            @Override public void run() {
                Toast.makeText(view.getContext(), text, duration).show();
            }
        });
    }

    public static void toastWrapper(final View view, final int resId, final int duration) {
        view.post(new Runnable() {
            @Override public void run() {
                Toast.makeText(view.getContext(), resId, duration).show();
            }
        });
    }

    public static boolean marketAvailable(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        try {
            pm.getPackageInfo("com.android.vending", 0);
        } catch (NameNotFoundException e) {
            return false;
        }
        return true;
    }

    public static boolean haveProKey(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        try {
            pm.getPackageInfo(Config.KEY_PACKAGE, 0);
        } catch (NameNotFoundException e) {
            return false;
        }
        return true;
    }

    public static void verifyKeyState(Context ctx) {
        Config cfg = Config.getInstance(ctx);
        if (!haveProKey(ctx)) {
            if (!cfg.hasRedeemCode()) cfg.setKeyState(Config.KEY_STATE_NONE);
        } else if (!cfg.hasRedeemCode() && cfg.hasValidProKey() && cfg.isProKeyTemporary()) {
            if (cfg.getNextKeyVerif() < System.currentTimeMillis() && !cfg.isVerifyingProKey()) {
                verifyProKey(ctx);
            }
        }
    }

    public static void verifyProKey(Context ctx) {
        Config cfg = Config.getInstance(ctx);
        if (!haveProKey(ctx)) return;
        if (cfg.isVerifyingProKey()) return;

        if (ctx.getPackageManager().checkSignatures(ctx.getPackageName(), Config.KEY_PACKAGE) != PackageManager.SIGNATURE_MATCH) {
            Log.w(Config.LOG_TAG + "Key", "signatures don't match!");
            return;
        }
        Log.v(Config.LOG_TAG + "Key", "sending verify intent");

        int keyState = cfg.getKeyState();
        if (keyState == Config.KEY_STATE_NOVERIF || keyState == Config.KEY_STATE_VERIF1_FAIL) {
            cfg.setKeyState(Config.KEY_STATE_VERIF1_IP);
        } else if (keyState == Config.KEY_STATE_VERIF1_GOOD || keyState == Config.KEY_STATE_VERIF2_FAIL) {
            cfg.setKeyState(Config.KEY_STATE_VERIF2_IP);
        } else {
            return;
        }

        Intent i = new Intent(Config.KEY_VERIFY_ACTION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB_MR1) {
            i.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
        }
        ctx.sendBroadcast(i);
    }

    public static boolean needProKeyVerify(Context ctx) {
        Config cfg = Config.getInstance(ctx);
        if (!haveProKey(ctx)) return false;
        if (cfg.isVerifyingProKey()) return true;

        int keyState = cfg.getKeyState();
        if (keyState == Config.KEY_STATE_NOVERIF) return true;
        if (keyState == Config.KEY_STATE_VERIF1_GOOD ||
                keyState == Config.KEY_STATE_VERIF1_FAIL ||
                keyState == Config.KEY_STATE_VERIF2_FAIL) {
            return cfg.getNextKeyVerif() < System.currentTimeMillis();
        }

        return false;
    }

    public static boolean dataAvailable(Context ctx) {
        ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo ni = cm.getActiveNetworkInfo();
        return ni != null && ni.isConnected();
    }

    public static Date parseDate(String date) {
        if (date == null) return null;
        try {
            return new SimpleDateFormat("yyyyMMdd-kkmm").parse(date);
        } catch (ParseException e) {
            e.printStackTrace();
        }
        return null;
    }

    public static String formatDate(Date date) {
        if (date == null) return null;
        return new SimpleDateFormat("yyyyMMdd-kkmm").format(date);
    }

    public static boolean isRomUpdate(RomInfo info) {
        if (info == null) return false;
        if (info.date != null) {
            if (PropUtils.getRomOtaDate() == null) return true;
            if (info.date.after(PropUtils.getRomOtaDate())) return true;
        } else if (info.version != null) {
            if (PropUtils.getRomOtaVersion() == null) return true;
            if (!info.version.equalsIgnoreCase(PropUtils.getRomOtaVersion())) return true;
        }
        return false;
    }

    public static boolean isKernelUpdate(KernelInfo info) {
        if (info == null) return false;
        if (info.date != null) {
            if (PropUtils.getKernelOtaDate() == null) return true;
            if (info.date.after(PropUtils.getKernelOtaDate())) return true;
        } else if (info.version != null) {
            if (PropUtils.getKernelOtaVersion() == null) return true;
            if (!info.version.equalsIgnoreCase(PropUtils.getKernelOtaVersion())) return true;
        }
        return false;
    }

    public static void updateGCMRegistration(Context ctx, String regID) {
        Log.v(Config.LOG_TAG + "updateGCM", "updating GCM reg infos");
        ArrayList<BasicNameValuePair> params = new ArrayList<BasicNameValuePair>();

        params.add(new BasicNameValuePair("do", "register"));
        params.add(new BasicNameValuePair("reg_id", regID));
        params.add(new BasicNameValuePair("device", android.os.Build.DEVICE.toLowerCase()));
        params.add(new BasicNameValuePair("device_id", getDeviceID(ctx)));

        if (PropUtils.isRomOtaEnabled()) params.add(new BasicNameValuePair("rom_id", PropUtils.getRomOtaID()));
        if (PropUtils.isKernelOtaEnabled()) params.add(new BasicNameValuePair("kernel_id", PropUtils.getKernelOtaID()));

        PackageInfo pInfo = null;
        try {
            pInfo = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
        } catch (NameNotFoundException e) {
            e.printStackTrace();
        }
        int version = pInfo == null ? 20 : pInfo.versionCode;
        params.add(new BasicNameValuePair("app_version", version + ""));

        try {
            HttpClient http = new DefaultHttpClient();
            HttpPost req = new HttpPost(Config.GCM_REGISTER_URL);
            req.setEntity(new UrlEncodedFormEntity(params));

            HttpResponse r = http.execute(req);
            int status = r.getStatusLine().getStatusCode();
            HttpEntity e = r.getEntity();
            if (status == 200) {
                String data = EntityUtils.toString(e);
                if (data.length() == 0) {
                    Log.w(Config.LOG_TAG + "updateGCM", "No response to registration");
                    return;
                }
                JSONObject json = new JSONObject(data);

                if (json.length() == 0) {
                    Log.w(Config.LOG_TAG + "updateGCM", "Empty response to registration");
                    return;
                }

                if (json.has("error")) {
                    Log.e(Config.LOG_TAG + "updateGCM", json.getString("error"));
                    return;
                }

                final Context context = ctx.getApplicationContext();
                final Config cfg = Config.getInstance(context);

                if (PropUtils.isRomOtaEnabled()) {
                    JSONObject jsonRom = json.getJSONObject("rom");

                    RomInfo info = new RomInfo(
                            jsonRom.getString("name"),
                            jsonRom.getString("version"),
                            jsonRom.getString("changelog"),
                            jsonRom.getString("url"),
                            jsonRom.getString("md5"),
                            Utils.parseDate(jsonRom.getString("date")));

                    if (Utils.isRomUpdate(info)) {
                        cfg.storeRomUpdate(info);
                        if (cfg.getShowNotif()) {
                            info.showUpdateNotif(context);
                        } else {
                            Log.v(Config.LOG_TAG + "updateGCM", "got rom update response, notif not shown");
                        }
                    } else {
                        cfg.clearStoredRomUpdate();
                        RomInfo.clearUpdateNotif(context);
                    }
                }

                if (PropUtils.isKernelOtaEnabled()) {
                    JSONObject jsonKernel = json.getJSONObject("rom");

                    KernelInfo info = new KernelInfo(
                            jsonKernel.getString("name"),
                            jsonKernel.getString("version"),
                            jsonKernel.getString("changelog"),
                            jsonKernel.getString("url"),
                            jsonKernel.getString("md5"),
                            Utils.parseDate(jsonKernel.getString("date")));

                    if (Utils.isKernelUpdate(info)) {
                        cfg.storeKernelUpdate(info);
                        if (cfg.getShowNotif()) {
                            info.showUpdateNotif(context);
                        } else {
                            Log.v(Config.LOG_TAG + "updateGCM", "got kernel update response, notif not shown");
                        }
                    } else {
                        cfg.clearStoredKernelUpdate();
                        KernelInfo.clearUpdateNotif(context);
                    }
                }
            } else {
                if (e != null) e.consumeContent();
                Log.w(Config.LOG_TAG + "updateGCM", "registration response " + status);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static String getDeviceID(Context ctx) {
        String deviceID = ((TelephonyManager) ctx.getSystemService(Context.TELEPHONY_SERVICE)).getDeviceId();
        if (deviceID == null) {
            WifiManager wm = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
            if (wm.isWifiEnabled()) {
                deviceID = wm.getConnectionInfo().getMacAddress();
            } else {
                //fallback to ANDROID_ID - gets reset on data wipe, but it's better than nothing
                deviceID = Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.ANDROID_ID);
            }
        }
        return md5(deviceID);
    }

    public static String getDeviceName(Context ctx) {
        String carrier = ((TelephonyManager) ctx.getSystemService(Context.TELEPHONY_SERVICE)).getNetworkOperatorName();
        if (carrier == null) carrier = "Wi-Fi";

        return carrier + " " + Build.MODEL;
    }

    public static String sanitizeName(String name) {
        if (name == null) return "";

        name = Normalizer.normalize(name, Normalizer.Form.NFD);
        name = name.replaceAll("[^\\p{ASCII}]","");
        name = name.replace(' ', '_');
        name = name.toLowerCase();

        return name;
    }

    public static void showProKeyOnlyFeatureDialog(final Context ctx, final DialogCallback callback) {
        AlertDialog.Builder builder = new AlertDialog.Builder(ctx);
        builder.setTitle(R.string.prokey_only_feature_title);
        builder.setMessage(R.string.prokey_only_feature_message);
        builder.setPositiveButton(R.string.prokey_only_get, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                dialog.dismiss();
                Intent i = new Intent(ctx, SettingsActivity.class);
                i.putExtra(SettingsActivity.EXTRA_SHOW_GET_PROKEY_DLG, true);
                ctx.startActivity(i);
            }
        });
        builder.setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                dialog.dismiss();
            }
        });

        final AlertDialog dlg = builder.create();
        dlg.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface dialog) {
                if (callback != null) callback.onDialogShown(dlg);
            }
        });
        dlg.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface dialog) {
                if (callback != null) callback.onDialogClosed(dlg);
            }
        });
        dlg.show();
    }

    private static final char[] HEX_DIGITS = new char[] { '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f' };
    public static String byteArrToStr(byte[] bytes) {
        StringBuffer str = new StringBuffer();
        for (int q = 0; q < bytes.length; q++) {
            str.append(HEX_DIGITS[(0xF0 & bytes[q]) >>> 4]);
            str.append(HEX_DIGITS[0xF & bytes[q]]);
        }
        return str.toString();
    }
}
