package com.otaupdater.utils;

import java.io.File;
import java.util.Date;

import org.json.JSONException;
import org.json.JSONObject;

import android.os.Build;
import android.util.Log;

import com.otaupdater.utils.ShellCommand.CommandResult;

public class PropUtils {
    private static String cachedRomID = null;
    private static Date cachedRomDate = null;
    private static String cachedRomVer = null;

    private static String cachedKernelID = null;
    private static Date cachedKernelDate = null;
    private static String cachedKernelVer = null;
    private static String cachedKernelUname = null;

    private static String cachedOSSdPath = null;
    private static String cachedRcvrySdPath = null;

    public static boolean isRomOtaEnabled() {
        return new File("/system/rom.ota.prop").exists();
    }

    public static boolean isKernelOtaEnabled() {
        return new File("/system/kernel.ota.prop").exists();
    }

    public static String getRomOtaID() {
        if (!isRomOtaEnabled()) return null;
        if (cachedRomID == null) {
            readRomOtaProp();
        }
        return cachedRomID;
    }

    public static Date getRomOtaDate() {
        if (!isRomOtaEnabled()) return null;
        if (cachedRomDate == null) {
            readRomOtaProp();
        }
        return cachedRomDate;
    }

    public static String getRomOtaVersion() {
        if (!isRomOtaEnabled()) return null;
        if (cachedRomVer == null) {
            readRomOtaProp();
        }
        return cachedRomVer;
    }

    public static String getRomVersion() {
        ShellCommand cmd = new ShellCommand();
        CommandResult modVer = cmd.sh.runWaitFor("getprop ro.modversion");
        if (modVer.stdout.length() != 0) return modVer.stdout;

        CommandResult cmVer = cmd.sh.runWaitFor("getprop ro.cm.version");
        if (cmVer.stdout.length() != 0) return cmVer.stdout;

        CommandResult aokpVer = cmd.sh.runWaitFor("getprop ro.aokp.version");
        if (aokpVer.stdout.length() != 0) return aokpVer.stdout;

        return Build.DISPLAY;
    }

    public static String getKernelOtaID() {
        if (!isKernelOtaEnabled()) return null;
        if (cachedKernelID == null) {
            readKernelOtaProp();
        }
        return cachedKernelID;
    }

    public static Date getKernelOtaDate() {
        if (!isKernelOtaEnabled()) return null;
        if (cachedKernelDate == null) {
            readKernelOtaProp();
        }
        return cachedKernelDate;
    }

    public static String getKernelOtaVersion() {
        if (!isKernelOtaEnabled()) return null;
        if (cachedKernelVer == null) {
            readKernelOtaProp();
        }
        return cachedKernelVer;
    }

    public static String getKernelVersion() {
        if (cachedKernelUname == null) {
            ShellCommand cmd = new ShellCommand();
            CommandResult propResult = cmd.sh.runWaitFor("cat /proc/version");

            if (propResult == null || propResult.stdout.length() == 0)
                return null;

            cachedKernelUname = propResult.stdout;
        }
        return cachedKernelUname;
    }

    public static String getOSSdPath() {
        if (cachedOSSdPath == null) {
            ShellCommand cmd = new ShellCommand();
            CommandResult propResult = cmd.sh.runWaitFor("getprop " + Config.OTA_SD_PATH_OS_PROP);
            if (propResult.stdout.length() == 0) return "sdcard";
            cachedOSSdPath = propResult.stdout;
        }
        return cachedOSSdPath;
    }

    public static String getRcvrySdPath() {
        if (cachedRcvrySdPath == null) {
            ShellCommand cmd = new ShellCommand();
            CommandResult propResult = cmd.sh.runWaitFor("getprop " + Config.OTA_SD_PATH_RECOVERY_PROP);
            if (propResult.stdout.length() == 0) return "sdcard";
            cachedRcvrySdPath = propResult.stdout;
        }
        return cachedRcvrySdPath;
    }

    private static void readRomOtaProp() {
        if (!isRomOtaEnabled()) return;

        ShellCommand cmd = new ShellCommand();
        CommandResult catResult = cmd.sh.runWaitFor("cat /system/rom.ota.prop");
        if (catResult.stdout.length() == 0) return;

        try {
            JSONObject romOtaProp = new JSONObject(catResult.stdout);
            cachedRomID = romOtaProp.getString("otaid");
            cachedRomVer = romOtaProp.getString("otaver");
            cachedRomDate = Utils.parseDate(romOtaProp.getString("otatime"));
        } catch (JSONException e) {
            Log.e(Config.LOG_TAG + "ReadOTAProp", "Error in rom.ota.prop file!");
        }
    }

    private static void readKernelOtaProp() {
        if (!isKernelOtaEnabled()) return;

        ShellCommand cmd = new ShellCommand();
        CommandResult catResult = cmd.sh.runWaitFor("cat /system/kernel.ota.prop");
        if (catResult.stdout.length() == 0) return;

        try {
            JSONObject romOtaProp = new JSONObject(catResult.stdout);
            cachedKernelID = romOtaProp.getString("otaid");
            cachedKernelVer = romOtaProp.getString("otaver");
            cachedKernelDate = Utils.parseDate(romOtaProp.getString("otatime"));
        } catch (JSONException e) {
            Log.e(Config.LOG_TAG + "ReadOTAProp", "Error in kernel.ota.prop file!");
        }
    }
}
