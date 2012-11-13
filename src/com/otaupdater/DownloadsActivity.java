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

package com.otaupdater;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.ArrayList;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;
import android.support.v4.app.NavUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.actionbarsherlock.app.ActionBar;
import com.actionbarsherlock.app.SherlockListActivity;
import com.actionbarsherlock.view.MenuItem;
import com.otaupdater.DownloadService.BindUtil;
import com.otaupdater.DownloadService.BindUtil.Token;
import com.otaupdater.utils.Config;
import com.otaupdater.utils.DlState;
import com.otaupdater.utils.DownloadDialogCallback;

public class DownloadsActivity extends SherlockListActivity implements
        ActionBar.OnNavigationListener, ServiceConnection, DownloadDialogCallback {
    public static final String EXTRA_GOTO_TYPE = "goto_type";
    public static final int GOTO_TYPE_PENDING = 0;
    public static final int GOTO_TYPE_RECENT = 1;
    public static final int GOTO_TYPE_ROM = 2;
    public static final int GOTO_TYPE_KERNEL = 3;

    private ArrayList<String> fileList = new ArrayList<String>();
    private ArrayAdapter<String> fileAdapter = null;

    private ArrayList<DlState> dlList = new ArrayList<DlState>();
    private DownloadAdapter dlAdapter = null;

    private final ArrayList<Dialog> dlgs = new ArrayList<Dialog>();

    private Integer downloadDlgDlID = null;
    private IDownloadService service = null;
    private Token token;

    private int state = 0;

    private static final int REFRESH_DELAY = 1000;
    private final Handler REFRESH_HANDLER = new RefreshHandler(this);
    private static class RefreshHandler extends Handler {
        private WeakReference<DownloadsActivity> downloadsAct;

        public RefreshHandler(DownloadsActivity dls) {
            downloadsAct = new WeakReference<DownloadsActivity>(dls);
        }

        @Override
        public void handleMessage(Message msg) {
            downloadsAct.get().updateFileList();
        }
    };

    @Override
    public void onServiceConnected(ComponentName name, IBinder stub) {
        service = IDownloadService.Stub.asInterface(stub);
        updateFileList();

        if (downloadDlgDlID != null) DownloadsActivity.showDownloadingDialog(this, service, token, downloadDlgDlID, this);
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        DownloadsActivity.this.service = null;
        updateFileList();
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String extState = Environment.getExternalStorageState();
        if (!extState.equals(Environment.MEDIA_MOUNTED) && !extState.equals(Environment.MEDIA_MOUNTED_READ_ONLY)) {
            Toast.makeText(this, extState.equals(Environment.MEDIA_SHARED) ? R.string.toast_nosd_shared : R.string.toast_nosd_error, Toast.LENGTH_LONG).show();
            finish();
        }

        setContentView(R.layout.downloads);

        token = BindUtil.bindToService(this, this);

        final ActionBar bar = getSupportActionBar();
        bar.setDisplayHomeAsUpEnabled(true);
        bar.setDisplayShowTitleEnabled(false);
        bar.setNavigationMode(ActionBar.NAVIGATION_MODE_LIST);
        bar.setListNavigationCallbacks(ArrayAdapter.createFromResource(this, R.array.download_types, android.R.layout.simple_spinner_dropdown_item), this);

        state = getIntent().getIntExtra(EXTRA_GOTO_TYPE, state);
        if (savedInstanceState != null) {
            if (!getIntent().hasExtra(EXTRA_GOTO_TYPE)) state = savedInstanceState.getInt("state", state);
            if (savedInstanceState.containsKey("dlID")) downloadDlgDlID = savedInstanceState.getInt("dlID");
            else downloadDlgDlID = null;
        }
        bar.setSelectedNavigationItem(state);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt("state", getSupportActionBar().getSelectedNavigationIndex());
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateFileList();
    }

    @Override
    protected void onPause() {
        REFRESH_HANDLER.removeCallbacksAndMessages(null);
        for (Dialog dlg : dlgs) {
            if (dlg.isShowing()) dlg.dismiss();
        }
        dlgs.clear();
        super.onPause();

    }

    @Override
    protected void onDestroy() {
        BindUtil.unbindFromService(token);
        if (dlAdapter != null) {
            dlList.clear();
            dlAdapter = null;
        }
        if (fileAdapter != null) {
            fileList.clear();
            fileAdapter = null;
        }
        super.onDestroy();
    }

    @Override
    public boolean onNavigationItemSelected(int itemPosition, long itemId) {
        state = itemPosition;
        ((TextView) getListView().getEmptyView()).setText(
                getResources().getStringArray(R.array.download_types_empty)[itemPosition]);
        updateFileList();
        return true;
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
    protected void onListItemClick(ListView l, View v, int position, long id) {
        String path;
        if (state < 2) {
            DlState dlState = dlList.get(position);

            int status = dlState.getStatus();
            if (status == DlState.STATUS_COMPLETED) {
              //TODO show install dialog if necessary
            } else if (status == DlState.STATUS_FAILED) {
                try {
                    service.retry(dlState.getId());
                    updateFileList();
                } catch (RemoteException e) { }
            } else if (status == DlState.STATUS_PAUSED_USER) {
                try {
                    service.resume(dlState.getId());
                    updateFileList();
                } catch (RemoteException e) { }
            } else {
                downloadDlgDlID = dlState.getId();
                DownloadsActivity.showDownloadingDialog(this, service, token, downloadDlgDlID, this);
            }
        } else {
            path = fileList.get(position);
            path = (state == 2 ? Config.ROM_DL_PATH : Config.KERNEL_DL_PATH) + path;
            //TODO show install dialog if necessary
        }
    }

    private void updateFileList() {
        if (state < 2) {
            dlList.clear();
            if (service != null) {
                try {
                    int filter = 0;
                    if (state == 0) {
                        filter = DlState.FILTER_ACTIVE | DlState.FILTER_PAUSED;
                    } else if (state == 1) {
                        filter = DlState.FILTER_COMPLETED | DlState.FILTER_CANCELLED | DlState.FILTER_FAILED;
                    }
                    service.getDownloadsFilt(dlList, filter);
                } catch (RemoteException e) { }
            }

            if (dlAdapter == null) {
                dlAdapter = new DownloadAdapter();
                getListView().setAdapter(dlAdapter);

                if (fileAdapter != null) {
                    fileList.clear();
                    fileAdapter = null;
                }
            } else {
                dlAdapter.notifyDataSetChanged();
            }

            REFRESH_HANDLER.sendMessageDelayed(REFRESH_HANDLER.obtainMessage(), REFRESH_DELAY);
        } else {
            File dir = state == 2 ? Config.ROM_DL_PATH_FILE : Config.KERNEL_DL_PATH_FILE;
            File[] files = dir.listFiles();
            fileList.clear();
            if (files != null)
            {
                for (File file : files) {
                    if (file.isDirectory()) continue;
                    fileList.add(file.getName());
                }
            }

            if (fileAdapter == null) {
                fileAdapter = new ArrayAdapter<String>(this, R.layout.download_file, R.id.filename, fileList);
                getListView().setAdapter(fileAdapter);

                if (dlAdapter != null) {
                    dlList.clear();
                    dlAdapter = null;
                }
            } else {
                fileAdapter.notifyDataSetChanged();
            }

            REFRESH_HANDLER.removeCallbacksAndMessages(null);
        }
    }

    @Override
    public void onDialogShown(Dialog dlg) {
        dlgs.add(dlg);
    }

    @Override
    public void onDialogClosed(Dialog dlg) {
        dlgs.remove(dlg);
    }

    @Override
    public void onDownloadDialogShown(int dlID, Dialog dlg, Token serviceToken) {
        downloadDlgDlID = dlID;
    }

    @Override
    public void onDownloadDialogClosed(int dlID, Dialog dlg, Token serviceToken) {
        downloadDlgDlID = null;
    }

    private class DownloadAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return dlList.size();
        }

        @Override
        public Object getItem(int position) {
            return dlList.get(position);
        }

        @Override
        public long getItemId(int position) {
            return dlList.get(position).getId();
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = getLayoutInflater().inflate(R.layout.download_item, parent, false);
            }

            ImageView icon = (ImageView) convertView.findViewById(R.id.download_icon);
            ProgressBar bar = (ProgressBar) convertView.findViewById(R.id.download_progress_bar);
            TextView titleView = (TextView) convertView.findViewById(R.id.download_title);
            TextView subtxtView = (TextView) convertView.findViewById(R.id.download_subtext);
            TextView bytesView = (TextView) convertView.findViewById(R.id.download_bytes_text);
            TextView pctView = (TextView) convertView.findViewById(R.id.download_pct_text);

            DlState state = dlList.get(position);

            if (state.isRomDownload()) {
                titleView.setText(state.getRomInfo().romName);
                icon.setImageResource(R.drawable.zip);
            } else if (state.isKernelDownload()) {
                titleView.setText(state.getKernelInfo().kernelName);
                icon.setImageResource(R.drawable.zip);
            } else {
                icon.setImageResource(R.drawable.zip);
            }

            int status = state.getStatus();
            if (status == DlState.STATUS_RUNNING) {
                bytesView.setText(state.getProgressStr(DownloadsActivity.this));

                if (state.getTotalSize() == 0) {
                    pctView.setVisibility(View.GONE);
                    bar.setIndeterminate(true);
                } else {
                    pctView.setText(getString(R.string.downloads_pct_progress, Math.round(100.0f * (float) state.getPctDone())));
                    bytesView.setVisibility(View.VISIBLE);
                    bar.setMax((int) state.getTotalSize());
                    bar.setProgress((int) state.getTotalDone());
                    bar.setIndeterminate(false);
                }
                bytesView.setVisibility(View.VISIBLE);
                bar.setVisibility(View.VISIBLE);
                subtxtView.setVisibility(View.GONE);
            } else {
                if (status == DlState.STATUS_QUEUED || status == DlState.STATUS_STARTING) {
                    bar.setIndeterminate(true);
                    bar.setVisibility(View.VISIBLE);
                } else if (status == DlState.STATUS_PAUSED_USER) {
                    bar.setIndeterminate(false);
                    bar.setMax((int) state.getTotalSize());
                    bar.setProgress((int) state.getTotalDone());
                    bar.setVisibility(View.VISIBLE);
                } else {
                    bar.setVisibility(View.GONE);
                }
                bytesView.setVisibility(View.GONE);
                pctView.setVisibility(View.GONE);

                int subtext = 0;
                switch (status) {
                case DlState.STATUS_QUEUED:
                case DlState.STATUS_STARTING:
                    subtext = R.string.downloads_queued;
                    break;
                case DlState.STATUS_FAILED:
                    subtext = R.string.downloads_failed_unknown;
                    //TODO failed explanation
                    break;
                case DlState.STATUS_PAUSED_FOR_DATA:
                    subtext = R.string.downloads_paused_network;
                    break;
                case DlState.STATUS_PAUSED_FOR_WIFI:
                    subtext = R.string.downloads_paused_wifi;
                    break;
                case DlState.STATUS_PAUSED_RETRY:
                case DlState.STATUS_PAUSED_SYSTEM:
                    subtext = R.string.downloads_paused_retry;
                    break;
                case DlState.STATUS_COMPLETED:
                    subtext = R.string.notif_completed;
                    break;
                case DlState.STATUS_PAUSED_USER:
                    subtext = R.string.downloads_paused;
                    break;
                case DlState.STATUS_CANCELLED_USER:
                    subtext = R.string.downloads_cancelled;
                    break;
                }
                subtxtView.setText(subtext);
                subtxtView.setVisibility(View.VISIBLE);
            }

            return convertView;
        }
    }

    public static Dialog showDownloadingDialog(final Context ctx, final IDownloadService service, final Token token,
            final int dlID, final DownloadDialogCallback callback) {
        /*final*/ DlState initState = null;
        try {
            initState = service.getDownload(dlID);
        } catch (RemoteException e) { }

        if (initState == null) return null;
        final int initStatus = initState.getStatus();

        LayoutInflater inflater = LayoutInflater.from(ctx);
        View view = inflater.inflate(R.layout.download_dialog, null);

        TextView titleView = (TextView) view.findViewById(R.id.download_dlg_title);
        TextView changelogView = (TextView) view.findViewById(R.id.download_dlg_changelog);

        if (initState.isRomDownload()) {
            titleView.setText(ctx.getString(R.string.alert_downloading_rom_title, initState.getName(), initState.getVersion()));
        } else if (initState.isKernelDownload()) {
            titleView.setText(ctx.getString(R.string.alert_downloading_kernel_title, initState.getName(), initState.getVersion()));
        } else {
            titleView.setText(ctx.getString(R.string.alert_downloading_title, initState.getName(), initState.getVersion()));
        }

        changelogView.setText(initState.getChangelog());

        final TextView bytesView = (TextView) view.findViewById(R.id.download_dlg_bytes_txt);
        final TextView pctView = (TextView) view.findViewById(R.id.download_dlg_pct_txt);
        final TextView subtextView = (TextView) view.findViewById(R.id.download_dlg_subtext);
        final ProgressBar progressView = (ProgressBar) view.findViewById(R.id.download_dlg_progress);

        AlertDialog.Builder builder = new AlertDialog.Builder(ctx);
        builder.setTitle(R.string.alert_downloading);
        builder.setView(view);
        builder.setCancelable(true);
        builder.setPositiveButton(R.string.alert_hide, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                dialog.dismiss();
                DlState state = null;
                try {
                    state = service.getDownload(dlID);
                } catch (RemoteException e) { }

                if (state == null) return;

                int status = state.getStatus();
                if (status == DlState.STATUS_RUNNING) {
                    Toast.makeText(ctx, R.string.toast_downloading, Toast.LENGTH_SHORT).show();
                }
            }
        });
        builder.setNeutralButton(R.string.notif_pause, new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface dialog, int which) { }
        });
        builder.setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface dialog, int which) { }
        });

        final AlertDialog dlg = builder.create();

        final Handler REFRESH_HANDLER = new Handler() {
            @Override
            public void handleMessage(Message msg) {
                DlState state = null;
                try {
                    state = service.getDownload(dlID);
                } catch (RemoteException e) { }

                if (state == null) return;

                int status = state.getStatus();

                boolean active = status != DlState.STATUS_CANCELLED_USER &&
                        status != DlState.STATUS_COMPLETED &&
                        status != DlState.STATUS_FAILED;

                if (status == DlState.STATUS_COMPLETED) {
                    dlg.getButton(DialogInterface.BUTTON_NEGATIVE).setText(R.string.notif_flash);
                    progressView.setVisibility(View.GONE);
                    bytesView.setVisibility(View.GONE);
                    pctView.setVisibility(View.GONE);
                } else if (status == DlState.STATUS_FAILED || status == DlState.STATUS_CANCELLED_USER) {
                    dlg.getButton(DialogInterface.BUTTON_NEGATIVE).setText(R.string.notif_retry);
                    progressView.setVisibility(View.GONE);
                    bytesView.setVisibility(View.GONE);
                    pctView.setVisibility(View.GONE);
                } else {
                    dlg.getButton(DialogInterface.BUTTON_NEGATIVE).setText(R.string.notif_cancel);
                    progressView.setVisibility(View.VISIBLE);
                }

                if (active) {
                    if (status == DlState.STATUS_QUEUED || status == DlState.STATUS_STARTING || state.getTotalSize() == 0) {
                        progressView.setIndeterminate(true);
                        pctView.setVisibility(View.GONE);
                    } else {
                        progressView.setIndeterminate(false);
                        progressView.setMax((int) state.getTotalSize());
                        progressView.setProgress((int) state.getTotalDone());
                        pctView.setText(ctx.getString(R.string.downloads_pct_progress, Math.round(100.0f * (float) state.getPctDone())));
                        pctView.setVisibility(View.VISIBLE);
                    }

                    if (status == DlState.STATUS_RUNNING) {
                        bytesView.setText(state.getProgressStr(ctx));
                        bytesView.setVisibility(View.VISIBLE);
                        dlg.getButton(DialogInterface.BUTTON_NEUTRAL).setText(R.string.notif_pause);
                        dlg.getButton(DialogInterface.BUTTON_NEUTRAL).setVisibility(View.VISIBLE);
                    } else if (status == DlState.STATUS_PAUSED_USER) {
                        bytesView.setText(state.getProgressStr(ctx));
                        bytesView.setVisibility(View.VISIBLE);
                        dlg.getButton(DialogInterface.BUTTON_NEUTRAL).setText(R.string.notif_resume);
                        dlg.getButton(DialogInterface.BUTTON_NEUTRAL).setVisibility(View.VISIBLE);
                    } else {
                        bytesView.setVisibility(View.GONE);
                        dlg.getButton(DialogInterface.BUTTON_NEUTRAL).setVisibility(View.GONE);
                    }
                } else {
                    dlg.getButton(DialogInterface.BUTTON_NEUTRAL).setVisibility(View.GONE);
                }

                if (status == DlState.STATUS_RUNNING) {
                    subtextView.setVisibility(View.GONE);
                } else {
                    int subtext = 0;
                    switch (status) {
                    case DlState.STATUS_QUEUED:
                    case DlState.STATUS_STARTING:
                        subtext = R.string.downloads_queued;
                        break;
                    case DlState.STATUS_FAILED:
                        subtext = R.string.downloads_failed_unknown;
                        break;
                    case DlState.STATUS_PAUSED_FOR_DATA:
                        subtext = R.string.downloads_paused_network;
                        break;
                    case DlState.STATUS_PAUSED_FOR_WIFI:
                        subtext = R.string.downloads_paused_wifi;
                        break;
                    case DlState.STATUS_PAUSED_RETRY:
                    case DlState.STATUS_PAUSED_SYSTEM:
                        subtext = R.string.downloads_paused_retry;
                        break;
                    case DlState.STATUS_COMPLETED:
                        subtext = R.string.notif_completed;
                        break;
                    case DlState.STATUS_PAUSED_USER:
                        subtext = R.string.downloads_paused;
                        break;
                    case DlState.STATUS_CANCELLED_USER:
                        subtext = R.string.downloads_cancelled;
                        break;
                    }
                    subtextView.setText(ctx.getString(subtext));
                    subtextView.setVisibility(View.VISIBLE);
                }

                this.sendMessageDelayed(this.obtainMessage(), REFRESH_DELAY);
            }
        };

        dlg.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface dialog) {
                dlg.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        DlState state = null;
                        try {
                            state = service.getDownload(dlID);
                        } catch (RemoteException e) { }

                        if (state == null) return;

                        int status = state.getStatus();
                        if (status == DlState.STATUS_RUNNING) {
                            try {
                                service.pause(dlID);
                            } catch (RemoteException e) { }
                        } else if (status == DlState.STATUS_PAUSED_USER) {
                            try {
                                service.resume(dlID);
                            } catch (RemoteException e) { }
                        }
                    }
                });
                dlg.getButton(DialogInterface.BUTTON_NEGATIVE).setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        DlState state = null;
                        try {
                            state = service.getDownload(dlID);
                        } catch (RemoteException e) { }

                        if (state == null) return;

                        int status = state.getStatus();
                        if (status == DlState.STATUS_COMPLETED) {
                            dlg.dismiss();
                            //TODO dialog to flash shit
                        } else if (status == DlState.STATUS_FAILED || status == DlState.STATUS_CANCELLED_USER) {
                            try {
                                service.retry(dlID);
                            } catch (RemoteException e) { }
                        } else {
                            dlg.dismiss();
                            try {
                                service.cancel(dlID);
                            } catch (RemoteException e) { }
                        }
                    }
                });

                dlg.getButton(DialogInterface.BUTTON_NEUTRAL).setVisibility(initStatus == DlState.STATUS_RUNNING || initStatus == DlState.STATUS_PAUSED_USER ? View.VISIBLE : View.GONE);

                REFRESH_HANDLER.sendMessage(REFRESH_HANDLER.obtainMessage());
                if (callback != null) {
                    callback.onDialogShown(dlg);
                    callback.onDownloadDialogShown(dlID, dlg, token);
                }
            }
        });
        dlg.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface dialog) {
                REFRESH_HANDLER.removeCallbacksAndMessages(null);
                if (callback != null) {
                    callback.onDialogClosed(dlg);
                    callback.onDownloadDialogClosed(dlID, dlg, token);
                }
            }
        });
        dlg.show();

        return dlg;
    }
}
