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

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Date;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.utils.URLEncodedUtils;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;
import org.json.JSONObject;

import android.app.AlertDialog;
import android.app.Dialog;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.ProgressDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.AsyncTask;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;
import android.support.v4.app.NotificationCompat;
import android.app.Notification;
import android.util.Log;
import android.widget.Toast;

import com.otaupdater.DownloadReceiver;
import com.otaupdater.DownloadService;
import com.otaupdater.DownloadService.BindUtil;
import com.otaupdater.DownloadService.BindUtil.Token;
import com.otaupdater.DownloadsActivity;
import com.otaupdater.IDownloadService;
import com.otaupdater.OTAUpdaterActivity;
import com.otaupdater.R;

public class RomInfo implements Parcelable, Serializable {
    private static final long serialVersionUID = 7138464743643950748L;

    public String romName;
    public String version;
    public String changelog;
    public String url;
    public String md5;
    public Date date;

    private transient Token serviceToken = null;
    private transient Dialog downloadingDialog = null;

    public RomInfo(String romName, String version, String changelog, String downurl, String md5, Date date) {
        this.romName = romName;
        this.version = version;
        this.changelog = changelog;
        this.url = downurl;
        this.md5 = md5;
        this.date = date;
    }

    public static RomInfo fromIntent(Intent i) {
        return new RomInfo(
                i.getStringExtra("rom_info_name"),
                i.getStringExtra("rom_info_version"),
                i.getStringExtra("rom_info_changelog"),
                i.getStringExtra("rom_info_url"),
                i.getStringExtra("rom_info_md5"),
                Utils.parseDate(i.getStringExtra("rom_info_date")));
    }

    public void addToIntent(Intent i) {
        i.putExtra("rom_info_name", romName);
        i.putExtra("rom_info_version", version);
        i.putExtra("rom_info_changelog", changelog);
        i.putExtra("rom_info_url", url);
        i.putExtra("rom_info_md5", md5);
        i.putExtra("rom_info_date", Utils.formatDate(date));
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(romName);
        dest.writeString(version);
        dest.writeString(changelog);
        dest.writeString(url);
        dest.writeString(md5);
        dest.writeLong(date.getTime());
    }

    public static final Creator<RomInfo> CREATOR = new Creator<RomInfo>() {
        @Override
        public RomInfo[] newArray(int size) {
            return new RomInfo[size];
        }

        @Override
        public RomInfo createFromParcel(Parcel source) {
            return new RomInfo(
                    source.readString(),
                    source.readString(),
                    source.readString(),
                    source.readString(),
                    source.readString(),
                    new Date(source.readLong()));
        }
    };

    public void showUpdateNotif(Context ctx) {
        Intent mainIntent = new Intent(ctx, OTAUpdaterActivity.class);
        mainIntent.setAction(OTAUpdaterActivity.ROM_NOTIF_ACTION);
        this.addToIntent(mainIntent);
        PendingIntent mainPIntent = PendingIntent.getActivity(ctx, 0, mainIntent, PendingIntent.FLAG_CANCEL_CURRENT);

        Intent dlInent = new Intent(ctx, DownloadReceiver.class);
        dlInent.setAction(DownloadReceiver.DL_ROM_ACTION);
        this.addToIntent(dlInent);
        PendingIntent dlPIntent = PendingIntent.getBroadcast(ctx, 0, dlInent, PendingIntent.FLAG_CANCEL_CURRENT);

        Notification.Builder builder = new Notification.Builder(ctx);
        builder.setContentIntent(mainPIntent);
        builder.setContentTitle(ctx.getString(R.string.notif_source));
        builder.setContentText(ctx.getString(R.string.notif_text_rom));
        builder.setTicker(ctx.getString(R.string.notif_text_rom));
        builder.setWhen(System.currentTimeMillis());
        builder.setSmallIcon(R.drawable.updates);
        builder.setStyle(new Notification.BigTextStyle().bigText(ctx.getString(R.string.notif_text_rom_detailed, changelog)));
        builder.setPriority(Notification.PRIORITY_LOW);
        builder.addAction(R.drawable.ic_download_default, ctx.getString(R.string.notif_download), dlPIntent);

        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(Config.ROM_NOTIF_ID, builder.build());
    }

    public static void clearUpdateNotif(Context ctx) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.cancel(Config.ROM_NOTIF_ID);
    }

    public void downloadFileSilent(Context ctx) {
        Intent i = new Intent(ctx, DownloadService.class);
        i.setAction(DownloadService.SERVICE_ACTION);
        i.putExtra(DownloadService.EXTRA_CMD, DownloadService.CMD_DOWNLOAD);
        i.putExtra(DownloadService.EXTRA_INFO_TYPE, DownloadService.EXTRA_INFO_TYPE_ROM);
        this.addToIntent(i);
        ctx.startService(i);
    }

    public void downloadFileDialog(final Context ctx, final DownloadDialogCallback callback) {
        final Dialog tempDlg = ProgressDialog.show(ctx, "", ctx.getString(R.string.downloads_starting), true, false, new DialogInterface.OnCancelListener() {
            @Override
            public void onCancel(DialogInterface dialog) {
            }
        });
        if (callback != null) callback.onDialogShown(tempDlg);

        serviceToken = BindUtil.bindToService(ctx, new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder stub) {
                final IDownloadService service = IDownloadService.Stub.asInterface(stub);
                try {
                    final int dlID = service.queueRomDownload(RomInfo.this);
                    tempDlg.dismiss();
                    if (callback != null) callback.onDialogClosed(tempDlg);
                    RomInfo.clearUpdateNotif(ctx);
                    downloadingDialog = DownloadsActivity.showDownloadingDialog(ctx, service, serviceToken, dlID, callback);
                } catch (RemoteException e) {
                    tempDlg.dismiss();
                    if (callback != null) callback.onDialogClosed(tempDlg);
                    Toast.makeText(ctx, R.string.downloads_error_starting, Toast.LENGTH_LONG).show();
                }
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                if (downloadingDialog != null && downloadingDialog.isShowing()) downloadingDialog.dismiss();
                downloadingDialog = null;
                serviceToken = null;
            }
        });
    }

    public String getDownloadFileName() {
        return Utils.sanitizeName(romName + "__" + version + ".zip");
    }

    public void showUpdateDialog(final Context ctx, final DownloadDialogCallback callback) {
        AlertDialog.Builder builder = new AlertDialog.Builder(ctx);
        builder.setTitle(R.string.alert_update_title);
        builder.setMessage(ctx.getString(R.string.alert_update_rom_to, romName, version));

        builder.setPositiveButton(R.string.alert_download, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int whichButton) {
                dialog.dismiss();
                downloadFileDialog(ctx, callback);
            }
        });

        if (changelog.length() != 0) {
            builder.setNeutralButton(R.string.alert_changelog, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    dialog.dismiss();
                    showChangelogDialog(ctx, callback);
                }
            });
        }

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

    public void showChangelogDialog(final Context ctx, final DownloadDialogCallback callback) {
        AlertDialog.Builder builder = new AlertDialog.Builder(ctx);
        builder.setTitle(ctx.getString(R.string.alert_changelog_title, version));
        builder.setMessage(changelog);

        builder.setPositiveButton(R.string.alert_download, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int whichButton) {
                dialog.dismiss();
                downloadFileDialog(ctx, callback);
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

    public static void fetchInfo(Context ctx) {
        fetchInfo(ctx, null);
    }

    public static void fetchInfo(Context ctx, RomInfoListener callback) {
        new FetchInfoTask(ctx, callback).execute();
    }

    protected static class FetchInfoTask extends AsyncTask<Void, Void, RomInfo> {
        private RomInfoListener callback = null;
        private Context context = null;
        private String error = null;

        public FetchInfoTask(Context ctx, RomInfoListener callback) {
            this.context = ctx;
            this.callback = callback;
        }

        @Override
        public void onPreExecute() {
            if (callback != null) callback.onStartLoading();
        }

        @Override
        protected RomInfo doInBackground(Void... notused) {
            if (!PropUtils.isRomOtaEnabled()) {
                error = context.getString(R.string.rom_unsupported_title);
                return null;
            }
            if (!Utils.dataAvailable(context)) {
                error = context.getString(R.string.alert_nodata_title);
                return null;
            }

            try {
                ArrayList<BasicNameValuePair> params = new ArrayList<BasicNameValuePair>();
                params.add(new BasicNameValuePair("device", android.os.Build.DEVICE.toLowerCase()));
                params.add(new BasicNameValuePair("rom", PropUtils.getRomOtaID()));

                HttpClient client = new DefaultHttpClient();
                HttpGet get = new HttpGet(Config.ROM_PULL_URL + "?" + URLEncodedUtils.format(params, "UTF-8"));
                HttpResponse r = client.execute(get);
                int status = r.getStatusLine().getStatusCode();
                HttpEntity e = r.getEntity();
                if (status == 200) {
                    String data = EntityUtils.toString(e);
                    JSONObject json = new JSONObject(data);

                    if (json.has("error")) {
                        Log.e(Config.LOG_TAG + "Fetch", json.getString("error"));
                        error = json.getString("error");
                        return null;
                    }

                    return new RomInfo(
                            json.getString("name"),
                            json.getString("version"),
                            json.getString("changelog"),
                            json.getString("url"),
                            json.getString("md5"),
                            Utils.parseDate(json.getString("date")));
                } else {
                    if (e != null) e.consumeContent();
                    error = "Server responded with error " + status;
                    return null;
                }
            } catch (Exception e) {
                e.printStackTrace();
                error = e.getMessage();
            }

            return null;
        }

        @Override
        public void onPostExecute(RomInfo result) {
            if (callback != null) {
                if (result != null) callback.onLoaded(result);
                else callback.onError(error);
            }
        }
    }

    public static interface RomInfoListener {
        void onStartLoading();
        void onLoaded(RomInfo info);
        void onError(String err);
    }
}
