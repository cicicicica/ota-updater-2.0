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
import android.content.res.Resources;
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

import android.os.Environment;
import android.os.PowerManager;
//import android.os.SystemProperties;

import java.io.File;
import java.io.FileWriter;
import java.io.InputStreamReader;

public class Recovery {

    private static File RECOVERY_DIR = new File("/cache/recovery");
    private static File COMMAND_FILE = new File(RECOVERY_DIR, "command");

    // Utilities
    public static String getExternalStoragePath()
    {
        String path;

        path = System.getenv("SECONDARY_STORAGE");
        if (path != null)
            return path;

        path = System.getenv("EXTERNAL_STORAGE2");
        if (path != null)
            return path;

        path = System.getenv("EXTERNAL_ALT_STORAGE");
        if (path != null)
            return path;

        path = System.getenv("EXTERNAL_STORAGE");
        if (path != null)
            return path;

        path = Environment.getExternalStorageDirectory().getAbsolutePath();

        return path;
    }

    public static void rebootRecovery(Context ctx, boolean wipe_cache, boolean wipe_data, String path)
    {
        RECOVERY_DIR.mkdirs();
        COMMAND_FILE.delete();

        try {
            FileWriter command = new FileWriter(COMMAND_FILE);
            try {
                if (wipe_cache)
                {
                    command.write("--wipe_cache");
                    command.write("\n");
                }

                if (wipe_data)
                {
                    command.write("--wipe_data");
                    command.write("\n");
                }

                //command.write("--show_text");
                //command.write("\n");

                if (path != null && path.length() > 6)
                {
                    command.write("--update_package="+path);
                    command.write("\n");
                }

            } finally {
                command.close();
            }
        } catch (IOException ex) {
            ex.printStackTrace();
        }

        //SystemProperties.set("sys.shutdown.requested", "1recovery");

        PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
        pm.reboot("recovery");
    }


    public static void showFlashDialog(final Context ctx, final String rom_path) {
        Resources r = ctx.getResources();
        String[] installOpts = r.getStringArray(R.array.install_options);
        final boolean[] selectedOpts = new boolean[installOpts.length];
        selectedOpts[selectedOpts.length - 1] = true;

        AlertDialog.Builder builder = new AlertDialog.Builder(ctx);
        builder.setTitle(R.string.alert_install_title);
        //builder.setMessage(R.string.alert_install_message);

        builder.setMultiChoiceItems(installOpts, selectedOpts, new DialogInterface.OnMultiChoiceClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which, boolean isChecked) {
                selectedOpts[which] = isChecked;
            }
        });

        builder.setPositiveButton(R.string.alert_install, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                dialog.dismiss();

                Recovery.rebootRecovery(ctx, selectedOpts[1], selectedOpts[0], rom_path);
            }
        });
        builder.setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                dialog.dismiss();
            }
        });

        final AlertDialog dlg = builder.create();
        dlg.show();
    }

/*
    // adb shell chmod 6755 /system/xbin/su
    // adb shell ls -al /system/xbin/su
    public static void runBashCommand(String command, Boolean root)
    {
        try {
            Process process = null;
            DataOutputStream os = null;

            if (root)
            {
                process = Runtime.getRuntime().exec("su");
                os = new DataOutputStream(process.getOutputStream());
                os.writeBytes(command + "\n");
                os.writeBytes("exit\n");
                os.flush();
                os.close();
            }
            else
            {
                process = Runtime.getRuntime().exec(command);
            }
            process.waitFor();

            DataInputStream in = null;
            BufferedReader reader = null;
            String line = "";

            System.out.println(">>> Command: " + command);
            System.out.println(">>> Output:");
            in = new DataInputStream(process.getInputStream());
            reader = new BufferedReader(new InputStreamReader(in));
            while ((line = reader.readLine()) != null) {
                System.out.println(line);
            }
            System.out.println("<<<");
            in.close();

            System.out.println(">>> Error:");
            in = new DataInputStream(process.getErrorStream());
            reader = new BufferedReader(new InputStreamReader(in));
            while ((line = reader.readLine()) != null) {
                System.out.println(line);
            }
            System.out.println("<<<");
            in.close();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
*/
}
