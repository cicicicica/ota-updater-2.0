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

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager.NameNotFoundException;

public class Config {
    public static final String LOG_TAG = "OTA::";

    public static final String HTTPC_UA = "OTA Updater App HTTP Client";

    public static final String GPLUS_URL = "https://plus.google.com/102074511541445644953/posts";
    public static final String PP_DONATE_URL = "https://www.paypal.com/cgi-bin/webscr?cmd=_s-xclick&hosted_button_id=5UAZTL2YQ57ZS";

    public static final String WEB_HOME_URL = "http://otaupdatecenter.pro/test/";
    public static final String WEB_FEEDBACK_URL = WEB_HOME_URL + "?page=feedback";
    public static final String LOGIN_URL = WEB_HOME_URL + "pages/auth_login_key.php";
    public static final String LOGOUT_URL = WEB_HOME_URL + "pages/auth_logout_key.php";
    public static final String CODE_REDEEM_URL = WEB_HOME_URL + "pages/code_redeem.php";
    public static final String GCM_REGISTER_URL = WEB_HOME_URL + "pages/regdevice.php";
    public static final String ROM_PULL_URL = WEB_HOME_URL + "pages/rom_info.php";
    public static final String KERNEL_PULL_URL = WEB_HOME_URL + "pages/kernel_info.php";

    public static final String GCM_SENDER_ID = "1068482628480";

    public static final String OTA_SD_PATH_OS_PROP = "otaupdater.sdcard.os";
    public static final String OTA_SD_PATH_RECOVERY_PROP = "otaupdater.sdcard.recovery";

    public static final String KEY_PACKAGE = "com.otaupdater.key";
    public static final String KEY_VERIFY_ACTION = "com.otaupdater.key.action.VERIFY";

    public static final int ROM_NOTIF_ID = 1;
    public static final int KERNEL_NOTIF_ID = 2;
    public static final int DL_STATUS_NOTIF_ID = 3;

    public static final int DL_MAX_RETRIES = 5;
    public static final int DL_MAX_REDIRECTS = 5;
    public static final int DL_RETRY_MAX = 86400; //60*60*24
    public static final int DL_RETRY_MIN = 30;

    public static final int WAKE_TIMEOUT = 30000;

    public static final String DL_PATH = "/" + Utils.getOSSdPath() + "/OTA-Updater/download/";
    public static final String ROM_DL_PATH = DL_PATH + "ROM/";
    public static final String KERNEL_DL_PATH = DL_PATH + "kernel/";

    public static final File DL_PATH_FILE = new File(DL_PATH);
    public static final File ROM_DL_PATH_FILE = new File(ROM_DL_PATH);
    public static final File KERNEL_DL_PATH_FILE = new File(KERNEL_DL_PATH);

    static {
        DL_PATH_FILE.mkdirs();
        ROM_DL_PATH_FILE.mkdirs();
        KERNEL_DL_PATH_FILE.mkdirs();
    }

    /*
     * keyState values:
     *  - 0: key app not installed, redeem code not verified
     *  - 1: key app installed, not verified
     *  - 2: key app installed, first verification in progress
     *  - 3: key app installed, first verification failed; nextKeyVerif=time of next retry
     *  - 5: key app installed, first verification succeeded; nextKeyVerif=time of second verification
     *  - 6: key app installed, second verification in progress
     *  - 7: key app installed, second verification failed; nextKeyVerif=time of next retry
     *  - 11: key app installed, second verification succeeded, no more retries
     *  - 13: key app not installed, redeem code verified; redeemCode=code that was redeemed
     *  - -1: key app installed, verified invalid
     */
    public static final int KEY_STATE_NONE = 0;
    public static final int KEY_STATE_NOVERIF = 1;
    public static final int KEY_STATE_VERIF1_IP = 2;
    public static final int KEY_STATE_VERIF1_FAIL = 3;
    public static final int KEY_STATE_VERIF1_GOOD = 5;
    public static final int KEY_STATE_VERIF2_IP = 6;
    public static final int KEY_STATE_VERIF2_FAIL = 7;
    public static final int KEY_STATE_VERIF2_GOOD = 11;
    public static final int KEY_STATE_REDEEM_CODE = 13;
    public static final int KEY_STATE_INVALID_VERIF = -1;

    private int keyState = 0;
    private String redeemCode = null;
    private long nextKeyVerif = 0;

    private boolean showNotif = true;
    private boolean wifiOnlyDl = true;
    private boolean ignoredUnsupportedWarn = false;
    private boolean ignoredDataWarn = false;

    private int lastVersion = -1;
    private String lastDevice = null;
    private String lastRomID = null;
    private String lastKernelID = null;

    private int curVersion = -1;
    private String curDevice = null;
    private String curRomID = null;
    private String curKernelID = null;

    private RomInfo storedRomUpdate = null;
    private KernelInfo storedKernelUpdate = null;

    private String username = null;
    private String hmacKey = null;

    private static final String PREFS_NAME = "prefs";
    private final SharedPreferences PREFS;

    private Config(Context ctx) {
        PREFS = ctx.getApplicationContext().getSharedPreferences(PREFS_NAME, 0);

        keyState = PREFS.getInt("keyState", keyState);
        redeemCode = PREFS.getString("redeemCode", redeemCode);
        nextKeyVerif = PREFS.getLong("nextKeyVerif", nextKeyVerif);

        username = PREFS.getString("username", username);
        hmacKey = PREFS.getString("hmacKey", hmacKey);

        showNotif = PREFS.getBoolean("showNotif", showNotif);
        wifiOnlyDl = PREFS.getBoolean("wifiOnlyDl", wifiOnlyDl);
        ignoredUnsupportedWarn = PREFS.getBoolean("ignoredUnsupportedWarn", ignoredUnsupportedWarn);
        ignoredDataWarn = PREFS.getBoolean("ignoredDataWarn", ignoredDataWarn);

        lastDevice = PREFS.getString("device", lastDevice);
        lastVersion = PREFS.getInt("version", lastVersion);
        lastRomID = PREFS.getString("rom_id", lastRomID);
        lastKernelID = PREFS.getString("kernel_id", lastKernelID);

        if (PREFS.contains("rom_info_name")) {
            storedRomUpdate = new RomInfo(PREFS.getString("rom_info_name", null),
                    PREFS.getString("rom_info_version", null),
                    PREFS.getString("rom_info_changelog", null),
                    PREFS.getString("rom_info_url", null),
                    PREFS.getString("rom_info_md5", null),
                    Utils.parseDate(PREFS.getString("rom_info_date", null)));
        }

        if (PREFS.contains("kernel_info_name")) {
            storedKernelUpdate = new KernelInfo(PREFS.getString("kernel_info_name", null),
                    PREFS.getString("kernel_info_version", null),
                    PREFS.getString("kernel_info_changelog", null),
                    PREFS.getString("kernel_info_url", null),
                    PREFS.getString("kernel_info_md5", null),
                    Utils.parseDate(PREFS.getString("kernel_info_date", null)));
        }

        try {
            curVersion = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionCode;
        } catch (NameNotFoundException e) {
        }
        curDevice = android.os.Build.DEVICE.toLowerCase();
        curRomID = Utils.isRomOtaEnabled() ? Utils.getRomOtaID() : null;
        curKernelID = Utils.isKernelOtaEnabled() ? Utils.getKernelOtaID() : null;
    }
    private static Config instance = null;
    public static synchronized Config getInstance(Context ctx) {
        if (instance == null) {
            instance = new Config(ctx);
            Utils.verifyKeyState(ctx);
        }
        return instance;
    }

    public boolean hasValidProKey() {
        return keyState > 0;
    }

    public boolean hasRedeemCode() {
        return keyState == KEY_STATE_REDEEM_CODE && redeemCode != null;
    }

    public boolean isProKeyTemporary() {
        return keyState < 10;
    }

    public boolean isVerifyingProKey() {
        return hasValidProKey() && keyState % 2 == 0;
    }

    public boolean isInvalidProKey() {
        return keyState == -1;
    }

    public int getKeyState() {
        return keyState;
    }

    public void setKeyState(int keyState) {
        this.keyState = keyState;
        synchronized (PREFS) {
            SharedPreferences.Editor editor = PREFS.edit();
            editor.putInt("keyState", keyState);
            editor.commit();
        }
    }

    public long getNextKeyVerif() {
        return nextKeyVerif;
    }

    public void setNextKeyVerif(long nextVerif) {
        this.nextKeyVerif = nextVerif;
        putLong("nextKeyVerif", nextKeyVerif);
    }

    public String getRedeemCode() {
        return redeemCode;
    }

    public void setRedeemCode(String redeemCode) {
        this.redeemCode = redeemCode;
        synchronized (PREFS) {
            SharedPreferences.Editor editor = PREFS.edit();
            editor.putString("redeemCode", redeemCode);
            editor.commit();
        }
    }

    public boolean getShowNotif() {
        return showNotif;
    }

    public void setShowNotif(boolean showNotif) {
        this.showNotif = showNotif;
        putBoolean("showNotif", showNotif);
    }

    public boolean getWifiOnlyDl() {
        return wifiOnlyDl;
    }

    public void setWifiOnlyDl(boolean wifiOnlyDl) {
        this.wifiOnlyDl = wifiOnlyDl;
        putBoolean("wifiOnlyDl", wifiOnlyDl);
    }

    public boolean getIgnoredUnsupportedWarn() {
        return ignoredUnsupportedWarn;
    }

    public void setIgnoredUnsupportedWarn(boolean ignored) {
        this.ignoredUnsupportedWarn = ignored;
        putBoolean("ignoredUnsupportedWarn", ignored);
    }

    public boolean getIgnoredDataWarn() {
        return ignoredDataWarn;
    }

    public void setIgnoredDataWarn(boolean ignored) {
        this.ignoredDataWarn = ignored;
        putBoolean("ignoredDataWarn", ignored);
    }

    public int getLastVersion() {
        return lastVersion;
    }

    public String getLastDevice() {
        return lastDevice;
    }

    public String getLastRomID() {
        return lastRomID;
    }

    public void setValuesToCurrent() {
        synchronized (PREFS) {
            SharedPreferences.Editor editor = PREFS.edit();
            editor.putInt("version", curVersion);
            editor.putString("device", curDevice);
            editor.putString("rom_id", curRomID);
            editor.putString("kernel_id", curKernelID);
            editor.commit();
        }
    }

    public boolean upToDate() {
        if (lastDevice == null) return false;

        boolean romIdUpToDate = true;
        if (Utils.isRomOtaEnabled()) {
            if (lastRomID == null || curRomID == null) romIdUpToDate = false;
            else romIdUpToDate = curRomID.equals(lastRomID);
        } else if (lastRomID != null) romIdUpToDate = false;

        boolean kernelIdUpToDate = true;
        if (Utils.isKernelOtaEnabled()) {
            if (lastKernelID == null || curKernelID == null) kernelIdUpToDate = false;
            else kernelIdUpToDate = curKernelID.equals(lastKernelID);
        } else if (lastKernelID != null) kernelIdUpToDate = false;

        return curVersion == lastVersion && curDevice.equals(lastDevice) && romIdUpToDate && kernelIdUpToDate;
    }

    public boolean hasStoredRomUpdate() {
        return storedRomUpdate != null;
    }

    public RomInfo getStoredRomUpdate() {
        return storedRomUpdate;
    }

    public void storeRomUpdate(RomInfo info) {
        synchronized (PREFS) {
            SharedPreferences.Editor editor = PREFS.edit();
            editor.putString("rom_info_name", info.romName);
            editor.putString("rom_info_version", info.version);
            editor.putString("rom_info_changelog", info.changelog);
            editor.putString("rom_info_url", info.url);
            editor.putString("rom_info_md5", info.md5);
            editor.putString("rom_info_date", Utils.formatDate(info.date));
            editor.commit();
        }
    }

    public void clearStoredRomUpdate() {
        synchronized (PREFS) {
            SharedPreferences.Editor editor = PREFS.edit();
            editor.remove("rom_info_name");
            editor.remove("rom_info_version");
            editor.remove("rom_info_changelog");
            editor.remove("rom_info_url");
            editor.remove("rom_info_md5");
            editor.remove("rom_info_date");
            editor.commit();
        }
    }

    public boolean hasStoredKernelUpdate() {
        return storedKernelUpdate != null;
    }

    public KernelInfo getStoredKernelUpdate() {
        return storedKernelUpdate;
    }

    public void storeKernelUpdate(KernelInfo info) {
        synchronized (PREFS) {
            SharedPreferences.Editor editor = PREFS.edit();
            editor.putString("kernel_info_name", info.kernelName);
            editor.putString("kernel_info_version", info.version);
            editor.putString("kernel_info_changelog", info.changelog);
            editor.putString("kernel_info_url", info.url);
            editor.putString("kernel_info_md5", info.md5);
            editor.putString("kernel_info_date", Utils.formatDate(info.date));
            editor.commit();
        }
    }

    public void clearStoredKernelUpdate() {
        synchronized (PREFS) {
            SharedPreferences.Editor editor = PREFS.edit();
            editor.remove("kernel_info_name");
            editor.remove("kernel_info_version");
            editor.remove("kernel_info_changelog");
            editor.remove("kernel_info_url");
            editor.remove("kernel_info_md5");
            editor.remove("kernel_info_date");
            editor.commit();
        }
    }

    public boolean isUserLoggedIn() {
        return username != null && hmacKey != null;
    }

    public void storeLogin(String username, String hmacKey) {
        this.username = username;
        this.hmacKey = hmacKey;

        synchronized (PREFS) {
            SharedPreferences.Editor editor = PREFS.edit();
            editor.putString("username", username);
            editor.putString("hmacKey", hmacKey);
            editor.commit();
        }
    }

    public void clearLogin() {
        username = null;
        hmacKey = null;

        synchronized (PREFS) {
            SharedPreferences.Editor editor = PREFS.edit();
            editor.remove("username");
            editor.remove("hmacKey");
            editor.commit();
        }
    }

    public String getUsername() {
        return username;
    }

    public String getHmacKey() {
        return hmacKey;
    }

    private void putBoolean(String name, boolean value) {
        synchronized (PREFS) {
            SharedPreferences.Editor editor = PREFS.edit();
            editor.putBoolean(name, value);
            editor.commit();
        }
    }

    private void putLong(String name, long value) {
        synchronized (PREFS) {
            SharedPreferences.Editor editor = PREFS.edit();
            editor.putLong(name, value);
            editor.commit();
        }
    }
}
