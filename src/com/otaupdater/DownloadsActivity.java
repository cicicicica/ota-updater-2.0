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
                DownloadsActivity.showDownloadingDialog(this, service, token, downloadDlgDlID, this);
                downloadDlgDlID = dlState.getId();
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
            for (File file : files) {
                if (file.isDirectory()) continue;
                fileList.add(file.getName());
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
    public void onDownloadDialogShown(int dlID, Dialog dlg) {
        downloadDlgDlID = dlID;
    }

    @Override
    public void onDownloadDialogClosed(int dlID, Dialog dlg) {
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
        DlState state = null;
        try {
            state = service.getDownload(dlID);
        } catch (RemoteException e) { }

        if (state == null) return null;

        AlertDialog.Builder builder = new AlertDialog.Builder(ctx);
        builder.setTitle(R.string.alert_downloading);
        builder.setMessage(ctx.getString(R.string.alert_downloading_changelog, state.getChangelog()));
        builder.setCancelable(true);
        builder.setPositiveButton(R.string.alert_hide, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                dialog.dismiss();
            }
        });
        builder.setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                dialog.dismiss();
                try {
                    service.cancel(dlID);
                } catch (RemoteException e) { }
            }
        });

        final AlertDialog dlg = builder.create();
        dlg.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface dialog) {
                if (callback != null) {
                    callback.onDialogShown(dlg);
                    callback.onDownloadDialogShown(dlID, dlg);
                }
            }
        });
        dlg.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface dialog) {
                if (callback != null) {
                    callback.onDialogClosed(dlg);
                    callback.onDownloadDialogClosed(dlID, dlg);
                }
            }
        });
        dlg.show();

        return dlg;
    }
}
