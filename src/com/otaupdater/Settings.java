/*
 * Copyright (C) 2012 OTA Update Center
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may only use this file in compliance with the license and provided you are not associated with or are in co-operation anyone by the name 'X Vanderpoel'.
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

package com.otaupdater;

import java.util.ArrayList;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;
import org.json.JSONObject;

import android.app.AlertDialog;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.net.http.AndroidHttpClient;
import android.os.AsyncTask;
import android.os.Bundle;
import android.preference.CheckBoxPreference;
import android.preference.Preference;
import android.preference.PreferenceScreen;
import android.support.v4.app.NavUtils;
import android.util.Log;

import com.actionbarsherlock.app.ActionBar;
import com.actionbarsherlock.app.SherlockPreferenceActivity;
import com.actionbarsherlock.view.MenuItem;
import com.otaupdater.utils.Config;
import com.otaupdater.utils.DialogCallback;
import com.otaupdater.utils.UserUtils;
import com.otaupdater.utils.UserUtils.LoginCallback;
import com.otaupdater.utils.Utils;

public class Settings extends SherlockPreferenceActivity implements DialogCallback {

    private final ArrayList<Dialog> dlgs = new ArrayList<Dialog>();

    private Config cfg;

    private CheckBoxPreference notifPref;
    private CheckBoxPreference wifidlPref;
    private Preference resetWarnPref;
    private Preference prokeyPref;
    private Preference donatePref;

    @Override
    @SuppressWarnings("deprecation")
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final ActionBar bar = getSupportActionBar();
        bar.setDisplayHomeAsUpEnabled(true);

        cfg = Config.getInstance(getApplicationContext());

        Utils.verifyKeyState(getApplicationContext());
        if (Utils.needProKeyVerify(getApplicationContext())) {
            Utils.verifyProKey(getApplicationContext());
        }

        addPreferencesFromResource(R.xml.settings);

        notifPref = (CheckBoxPreference) findPreference("notif_pref");
        notifPref.setChecked(cfg.getShowNotif());

        wifidlPref = (CheckBoxPreference) findPreference("wifidl_pref");
        wifidlPref.setChecked(cfg.getWifiOnlyDl());

        prokeyPref = findPreference("prokey_pref");
        if (cfg.hasValidProKey()) {
            prokeyPref.setTitle(R.string.settings_prokey_title_pro);
        }

        if (cfg.hasValidProKey()) {
            if (cfg.hasRedeemCode()) {
                prokeyPref.setSummary(getString(R.string.settings_prokey_summary_redeemed, cfg.getRedeemCode()));
            } else if (cfg.isVerifyingProKey()) {
                prokeyPref.setSummary(R.string.settings_prokey_summary_verifying);
            } else if (cfg.isProKeyTemporary()) {
                prokeyPref.setSummary(R.string.settings_prokey_summary_verify);
            } else {
                prokeyPref.setSummary(R.string.settings_prokey_summary_pro);
            }
        } else if (!Utils.marketAvailable(getApplicationContext())) {
            prokeyPref.setSummary(R.string.settings_prokey_summary_nomarket);
        }

        resetWarnPref = findPreference("resetwarn_pref");
        donatePref = findPreference("donate_pref");
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
        case android.R.id.home:
            NavUtils.navigateUpFromSameTask(this);
            return true;
        }
        return false;
    }

    @Override
    @SuppressWarnings("deprecation")
    public boolean onPreferenceTreeClick(PreferenceScreen preferenceScreen, Preference preference) {
        if (preference == notifPref) {
            cfg.setShowNotif(notifPref.isChecked());
        } else if (preference == wifidlPref) {
            cfg.setWifiOnlyDl(wifidlPref.isChecked());
        } else if (preference == resetWarnPref) {
            cfg.setIgnoredDataWarn(false);
            cfg.setIgnoredUnsupportedWarn(false);
        } else if (preference == prokeyPref) {
            AlertDialog.Builder builder = new AlertDialog.Builder(this);

            if (cfg.hasValidProKey()) {
                if (cfg.hasRedeemCode()) {
                    builder.setMessage(R.string.prokey_redeemed_thanks);
                    builder.setNeutralButton(R.string.alert_close, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            dialog.dismiss();
                        }
                    });
                } else if (cfg.isVerifyingProKey()) {
                    // do nothing
                } else if (cfg.isProKeyTemporary()) {
                    Utils.verifyProKey(getApplicationContext());
                } else {
                    builder.setMessage(R.string.prokey_thanks);
                    builder.setNeutralButton(R.string.alert_close, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            dialog.dismiss();
                        }
                    });
                }
            } else {
                builder.setTitle(R.string.settings_prokey_title);
                final boolean market = Utils.marketAvailable(this);
                builder.setItems(market ? R.array.prokey_ops : R.array.prokey_ops_nomarket, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        which -= market ? 1 : 0;
                        switch (which) {
                        case -1:
                            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + Config.KEY_PACKAGE)));
                            break;
                        case 0:
                            redeemProKey();
                            break;
                        case 1:
                            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(Config.PP_DONATE_URL)));
                            break;
                        }
                    }
                });
            }

            final AlertDialog dlg = builder.create();
            dlg.setOnShowListener(new DialogInterface.OnShowListener() {
                @Override
                public void onShow(DialogInterface dialog) {
                    onDialogShown(dlg);
                }
            });
            dlg.setOnDismissListener(new DialogInterface.OnDismissListener() {
                @Override
                public void onDismiss(DialogInterface dialog) {
                    onDialogClosed(dlg);
                }
            });
            dlg.show();
        } else if (preference == donatePref) {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(Config.PP_DONATE_URL)));
        } else {
            return super.onPreferenceTreeClick(preferenceScreen, preference);
        }

        return true;
    }

    private void redeemProKey() {
        if (cfg.isUserLoggedIn()) {
            new RedeemTask(this, this).execute();
        } else {
            UserUtils.showLoginDialog(Settings.this, null, Settings.this, new LoginCallback() {
                @Override
                public void onLoggedIn(String username) {
                    new RedeemTask(Settings.this, Settings.this).execute();
                }

                @Override
                public void onError(String error) {
                    // TODO Auto-generated method stub
                }

                @Override
                public void onCancel() { }
            });
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        for (Dialog dlg : dlgs) {
            if (dlg.isShowing()) dlg.dismiss();
        }
        dlgs.clear();
    }

    @Override
    public void onDialogShown(Dialog dlg) {
        dlgs.add(dlg);
    }

    @Override
    public void onDialogClosed(Dialog dlg) {
        dlgs.remove(dlg);
    }

    private class RedeemTask extends AsyncTask<Void, Void, Boolean> {
        private Context ctx;
        private ProgressDialog dlg;
        private DialogCallback dlgCallback;

        public RedeemTask(Context ctx, DialogCallback dlgCallback) {
            this.ctx = ctx;
            this.dlgCallback = dlgCallback;
        }

        @Override
        protected void onPreExecute() {
            dlg = ProgressDialog.show(ctx, "", ctx.getString(R.string.settings_prokey_redeeming), true, true, new DialogInterface.OnCancelListener() {
                @Override
                public void onCancel(DialogInterface dialog) {
                    if (dlgCallback != null) dlgCallback.onDialogClosed(dlg);
                    RedeemTask.this.cancel(true);
                }
            });
            if (dlgCallback != null) dlgCallback.onDialogShown(dlg);
        }

        @Override
        protected Boolean doInBackground(Void... unused) {
            HttpClient httpc = AndroidHttpClient.newInstance(Config.HTTPC_UA, ctx);

            try {
                JSONObject data = new JSONObject();
                data.put("username", cfg.getUsername());
                data.put("device_id", Utils.getDeviceID(Settings.this));
                data.put("device_name", Utils.getDeviceName(Settings.this));
                UserUtils.addNonce(data);

                String dataStr = data.toString();

                ArrayList<BasicNameValuePair> params = new ArrayList<BasicNameValuePair>();
                params.add(new BasicNameValuePair("data_str", dataStr));
                params.add(new BasicNameValuePair("mac", UserUtils.userHmac(Settings.this, dataStr)));

                HttpPost req = new HttpPost(Config.CODE_REDEEM_URL);
                req.setEntity(new UrlEncodedFormEntity(params));
                HttpResponse resp = httpc.execute(req);
                HttpEntity entity = resp.getEntity();
                if (resp.getStatusLine().getStatusCode() == 200 && entity != null) {
                    JSONObject respData = new JSONObject(EntityUtils.toString(entity));
                    if (respData.getBoolean("success")) {
                        Config.getInstance(ctx).setRedeemCode(respData.getString("data"));
                        Log.d(Config.LOG_TAG + "Redeem", "redeemed, code=" + respData.getString("data"));
                    } else {
                        Log.w(Config.LOG_TAG + "Redeem", "ERROR: " + respData.getString("error"));
                        //TODO error
                    }
                } else {
                    Log.w(Config.LOG_TAG + "Redeem", "bad server response (" + resp.getStatusLine().getStatusCode() + ")");
                }
            } catch (Exception e) {
                e.printStackTrace();
            }

            return null;
        }

        @Override
        protected void onPostExecute(Boolean result) {
            dlg.dismiss();
            if (dlgCallback != null) dlgCallback.onDialogClosed(dlg);
        }

    }
}
