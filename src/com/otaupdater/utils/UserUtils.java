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

import java.util.ArrayList;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;
import org.json.JSONException;
import org.json.JSONObject;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.net.http.AndroidHttpClient;
import android.os.AsyncTask;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

import com.otaupdater.R;

public class UserUtils {
    public static void showLoginDialog(final Context ctx, String defUsername,
            final DialogCallback dlgCallback, final LoginCallback loginCallback) {
        View view = LayoutInflater.from(ctx).inflate(R.layout.login_prompt, null);
        final EditText inputUsername = (EditText) view.findViewById(R.id.auth_username);
        final EditText inputPassword = (EditText) view.findViewById(R.id.auth_password);

        if (defUsername != null) inputUsername.setText(defUsername);

        AlertDialog.Builder builder = new AlertDialog.Builder(ctx);
        builder.setTitle(R.string.alert_login_title);
        builder.setView(view);
        builder.setPositiveButton(R.string.alert_login, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {

            }
        });
        builder.setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                dialog.dismiss();
                if (loginCallback != null) loginCallback.onCancel();
            }
        });

        final AlertDialog dlg = builder.create();
        dlg.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface dialog) {
                if (dlgCallback != null) dlgCallback.onDialogShown(dlg);
                dlg.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        final String username = inputUsername.getText().toString();
                        final String password = inputPassword.getText().toString();

                        if (username.length() == 0 || password.length() == 0) {
                            Toast.makeText(ctx, R.string.toast_blank_userpass_error, Toast.LENGTH_LONG).show();
                            return;
                        }

                        dlg.dismiss();
                        new LoginTask(ctx, username, password, dlgCallback, loginCallback).execute();
                    }
                });
            }
        });
        dlg.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface dialog) {
                if (dlgCallback != null) dlgCallback.onDialogClosed(dlg);
            }
        });
        dlg.show();
    }

    public static void addNonce(JSONObject data) throws JSONException {
        data.put("__nonce__", Utils.md5(Double.toString(Math.random())));
        data.put("__ts__", System.currentTimeMillis());
    }

    public static String userHmac(Context ctx, String str) {
        Config cfg = Config.getInstance(ctx);
        if (!cfg.isUserLoggedIn()) return null;
        return Utils.hmac(str, cfg.getHmacKey());
    }

    public static interface LoginCallback {
        void onLoggedIn(String username);
        void onCancel();
        void onError(String error);
    }

    private static class LoginTask extends AsyncTask<Void, Void, Boolean> {
        private Context ctx;
        private ProgressDialog dlg;
        private String username, password;
        private DialogCallback dlgCallback;
        private LoginCallback loginCallback;

        public LoginTask(Context ctx, String username, String password, DialogCallback dlgCallback, LoginCallback loginCallback) {
            this.ctx = ctx;
            this.username = username;
            this.password = password;
            this.loginCallback = loginCallback;
            this.dlgCallback = dlgCallback;
        }

        @Override
        protected void onPreExecute() {
            dlg = ProgressDialog.show(ctx, "", ctx.getString(R.string.alert_logging_in), true, true, new DialogInterface.OnCancelListener() {
                @Override
                public void onCancel(DialogInterface dialog) {
                    if (dlgCallback != null) dlgCallback.onDialogClosed(dlg);
                    LoginTask.this.cancel(true);
                }
            });
            if (dlgCallback != null) dlgCallback.onDialogShown(dlg);
        }

        @Override
        protected Boolean doInBackground(Void... unused) {
            HttpClient httpc = AndroidHttpClient.newInstance(Config.HTTPC_UA, ctx);

            ArrayList<BasicNameValuePair> params = new ArrayList<BasicNameValuePair>();
            params.add(new BasicNameValuePair("username", username));
            params.add(new BasicNameValuePair("password", password));
            params.add(new BasicNameValuePair("device_id", Utils.getDeviceID(ctx)));
            params.add(new BasicNameValuePair("device_name", Utils.getDeviceName(ctx)));

            try {
                HttpPost req = new HttpPost(Config.LOGIN_URL);
                req.setEntity(new UrlEncodedFormEntity(params));
                HttpResponse resp = httpc.execute(req);
                HttpEntity entity = resp.getEntity();
                if (resp.getStatusLine().getStatusCode() == 200 && entity != null) {
                    JSONObject respData = new JSONObject(EntityUtils.toString(entity));
                    if (respData.getBoolean("success")) {
                        Config.getInstance(ctx).storeLogin(username, respData.getString("data"));
                        Log.d(Config.LOG_TAG + "Login", "logged in, key=" + respData.getString("data"));
                        if (loginCallback != null) loginCallback.onLoggedIn(username);
                    } else {
                        Log.w(Config.LOG_TAG + "Login", "ERROR: " + respData.getString("error"));
                        //TODO error
                    }
                } else {
                    Log.w(Config.LOG_TAG + "Login", "bad server response (" + resp.getStatusLine().getStatusCode() + ")");
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
