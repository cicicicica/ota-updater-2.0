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

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;

import android.annotation.TargetApi;
import android.app.DownloadManager;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.support.v4.app.NotificationCompat;
import android.app.Notification;
import android.util.Log;
import android.util.SparseArray;

import com.otaupdater.utils.Config;
import com.otaupdater.utils.DlState;
import com.otaupdater.utils.DownloadTask;
import com.otaupdater.utils.DownloadTask.DownloadListener;
import com.otaupdater.utils.DownloadTask.DownloadResult;
import com.otaupdater.utils.KernelInfo;
import com.otaupdater.utils.RomInfo;

public class DownloadService extends Service implements DownloadListener {
    public static final String SERVICE_ACTION = "com.otaupdater.downloadservice.command";

    public static final String EXTRA_CMD = "service_cmd";
    public static final int CMD_DOWNLOAD = 1;
    public static final int CMD_PAUSE = 2;
    public static final int CMD_RESUME = 3;
    public static final int CMD_CANCEL = 4;
    public static final int CMD_RETRY = 5;

    public static final String EXTRAL_DOWNLOAD_ID = "download_id";

    public static final String EXTRA_INFO_TYPE = "info_type";
    public static final int EXTRA_INFO_TYPE_ROM = 1;
    public static final int EXTRA_INFO_TYPE_KERNEL = 2;

    public static final int STOP_NO_DATA = 1;
    public static final int STOP_NO_WIFI = 2;

    public static final int NETWORK_OK = 0;
    public static final int NETWORK_NOT_CONNECTED = 1;
    public static final int NETWORK_NO_WIFI = 2;
    public static final int NETWORK_SIZE_EXCEEDED = 3;

    private final ArrayList<Integer> DOWNLOAD_QUEUE = new ArrayList<Integer>();
    private final SparseArray<DlState> DOWNLOADS = new SparseArray<DlState>();
    private final SparseArray<DownloadTask> DOWNLOAD_THREADS = new SparseArray<DownloadTask>();

    private boolean serviceInUse = false;
    private int startId = -1;

    private Config cfg;

    private NotificationManager nm;
    private WakeLock wakeLock;

    private boolean isNetStateDirty = true;

    private long minNextNotifUpdate = 0;
    private static final long NOTIF_REFRESH_DELAY = 500;

    private long minNextWriteUpdate = 0;
    private static final long WRITE_STATE_DELAY = 100;
    private static final String STATE_STORE_NAME = "service_state";

    private static final int IDLE_DELAY = 60000;
    private final Handler DELAY_STOP_HANDLER = new StopHandler(this);
    private static class StopHandler extends Handler {
        private WeakReference<DownloadService> service;

        public StopHandler(DownloadService service) {
            this.service = new WeakReference<DownloadService>(service);
        }

        @Override
        public void handleMessage(Message msg) {
            if (service.get().serviceInUse ||
                    service.get().DOWNLOAD_THREADS.size() != 0 ||
                    service.get().DOWNLOAD_QUEUE.size() != 0) return;
            service.get().stopSelf(service.get().startId);
        }
    }

    @Override
    public IBinder onBind(Intent arg0) {
        DELAY_STOP_HANDLER.removeCallbacksAndMessages(null);
        serviceInUse = true;
        return BINDER;
    }

    @Override
    public void onRebind(Intent intent) {
        DELAY_STOP_HANDLER.removeCallbacksAndMessages(null);
        serviceInUse = true;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        serviceInUse = false;

        if (DOWNLOAD_THREADS.size() == 0) stopSelf(startId);

        return true;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        this.startId = startId;

        String action = intent == null ? null : intent.getAction();
        if (action != null) {
            Log.v(Config.LOG_TAG + "Service", "got action: " + action);

            if (ConnectivityManager.CONNECTIVITY_ACTION.equals(action)) {
                isNetStateDirty = true;
                if (DOWNLOAD_THREADS.size() == 0 && DOWNLOAD_QUEUE.size() != 0) {
                    tryStartQueue();
                }
            } else if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
                if (DOWNLOAD_QUEUE.size() != 0) {
                    tryStartQueue();
                }
            } else if (SERVICE_ACTION.equals(action)) {
                int cmd = intent.getIntExtra(EXTRA_CMD, -1);
                Log.v(Config.LOG_TAG + "Service", "got service action, cmd= " + cmd);
                switch (cmd) {
                case CMD_DOWNLOAD:
                    int type = intent.getIntExtra(EXTRA_INFO_TYPE, -1);
                    switch (type) {
                    case EXTRA_INFO_TYPE_ROM:
                        queueDownload(RomInfo.fromIntent(intent));
                        break;
                    case EXTRA_INFO_TYPE_KERNEL:
                        queueDownload(KernelInfo.fromIntent(intent));
                        break;
                    }

                    break;
                case CMD_PAUSE:
                    if (intent.hasExtra(EXTRAL_DOWNLOAD_ID)) {
                        pause(intent.getIntExtra(EXTRAL_DOWNLOAD_ID, 0));
                    } else {
                        for (int id : DOWNLOAD_QUEUE) {
                            if (DOWNLOADS.get(id).getStatus() == DlState.STATUS_RUNNING) pause(id);
                        }
                    }
                    break;
                case CMD_RESUME:
                    if (intent.hasExtra(EXTRAL_DOWNLOAD_ID)) {
                        resume(intent.getIntExtra(EXTRAL_DOWNLOAD_ID, 0));
                    } else {
                        for (int id : DOWNLOAD_QUEUE) {
                            if (DOWNLOADS.get(id).getStatus() == DlState.STATUS_PAUSED_USER) resume(id);
                        }
                    }
                    break;
                case CMD_CANCEL:
                    if (intent.hasExtra(EXTRAL_DOWNLOAD_ID)) {
                        cancel(intent.getIntExtra(EXTRAL_DOWNLOAD_ID, 0));
                    } else {
                        for (int id : DOWNLOAD_QUEUE) {
                            int status = DOWNLOADS.get(id).getStatus();
                            if (status != DlState.STATUS_CANCELLED_USER &&
                                    status != DlState.STATUS_COMPLETED &&
                                    status != DlState.STATUS_FAILED) cancel(id);
                        }
                    }
                    break;
                case CMD_RETRY:
                    if (intent.hasExtra(EXTRAL_DOWNLOAD_ID)) {
                        retry(intent.getIntExtra(EXTRAL_DOWNLOAD_ID, 0));
                    }
                    break;
                }
            }
        }

        DELAY_STOP_HANDLER.removeCallbacksAndMessages(null);
        DELAY_STOP_HANDLER.sendMessageDelayed(DELAY_STOP_HANDLER.obtainMessage(), IDLE_DELAY);

        return START_STICKY;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        cfg = Config.getInstance(getApplicationContext());

        loadState();

        wakeLock = ((PowerManager) getSystemService(Context.POWER_SERVICE)).
                newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, this.getClass().getName());
        wakeLock.setReferenceCounted(false);

        DELAY_STOP_HANDLER.sendMessageDelayed(DELAY_STOP_HANDLER.obtainMessage(), IDLE_DELAY);
    }

    @Override
    public void onDestroy() {
        if (serviceInUse || DOWNLOAD_THREADS.size() != 0) {
            Log.w(Config.LOG_TAG + "Service", "onDestroy while service active!!");
        }

        DELAY_STOP_HANDLER.removeCallbacksAndMessages(null);
        wakeLock.release();

        for (int q = 0; q < DOWNLOAD_THREADS.size(); q++) {
            DownloadTask task = DOWNLOAD_THREADS.valueAt(q);
            DlState state = task.getState();
            state.setStatus(DlState.STATUS_PAUSED_SYSTEM);
            task.pause();
        }

        super.onDestroy();
    }

    @Override
    public void onStart(DlState state) {
        wakeLock.acquire();
        state.setStatus(DlState.STATUS_RUNNING);
        updateStatusNotif(true);
    }

    @Override
    public int onCheckContinue(DlState state) {
        if (isNetStateDirty) {
            int netCheck = checkNetwork(state);
            if (netCheck == NETWORK_NOT_CONNECTED) {
                return STOP_NO_DATA;
            }
            if (netCheck == NETWORK_NO_WIFI || netCheck == NETWORK_SIZE_EXCEEDED) {
                return STOP_NO_WIFI;
            }
        }

        return 0;
    }

    @Override
    public void onLengthReceived(DlState state) {
        updateStatusNotif(true);
        saveState(true);
    }

    @Override
    public void onProgress(DlState state) {
        updateStatusNotif(false);
        saveState(false);
    }

    @Override
    public void onPause(DlState state) {
        updateStatusNotif(true);
        cleanupFinish(state);
        saveState(true);
    }

    @Override
    public void onFinish(DlState state, DownloadResult result) {
        if (result != DownloadResult.CANCELLED && result != DownloadResult.FINISHED && state.getStatus() != DlState.STATUS_FAILED) {
            DOWNLOAD_QUEUE.add(state.getId());
        }
        updateStatusNotif(true);
        cleanupFinish(state);
        saveState(true);
    }

    private void saveState(boolean force) {
        if (System.currentTimeMillis() < minNextWriteUpdate && !force) return;
        minNextWriteUpdate = System.currentTimeMillis() + WRITE_STATE_DELAY;

        ObjectOutputStream os = null;
        try {
            os = new ObjectOutputStream(openFileOutput(STATE_STORE_NAME, 0));
            os.writeInt(DOWNLOADS.size());
            for (int q = 0; q < DOWNLOADS.size(); q++) {
                os.writeInt(DOWNLOADS.keyAt(q));
                os.writeObject(DOWNLOADS.valueAt(q));
            }
            os.writeObject(DOWNLOAD_QUEUE);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (os != null) {
                try {
                    os.flush();
                    os.close();
                } catch (IOException e) { }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void loadState() {
        DOWNLOADS.clear();
        DOWNLOAD_QUEUE.clear();
        DOWNLOAD_THREADS.clear();

        ObjectInputStream is = null;
        try {
            is = new ObjectInputStream(openFileInput(STATE_STORE_NAME));
            int nStates = is.readInt();
            for (int q = 0; q < nStates; q++) {
                DOWNLOADS.put(is.readInt(), (DlState) is.readObject());
            }
            DOWNLOAD_QUEUE.addAll((ArrayList<Integer>) is.readObject());
        } catch(FileNotFoundException e) {
            return;
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (is != null) {
                try { is.close(); }
                catch (IOException e) { }
            }
        }
    }

    public int queueDownload(RomInfo info) {
        return queueDownload(new DlState(info));
    }

    public int queueDownload(KernelInfo info) {
        return queueDownload(new DlState(info));
    }

    private int queueDownload(DlState state) {
        int id = state.hashCode();
        Log.v(Config.LOG_TAG + "Service", "queuing download id=" + id);
        state.setId(id);
        state.setStatus(DlState.STATUS_QUEUED);
        DOWNLOADS.put(id, state);
        DOWNLOAD_QUEUE.add(id);
        saveState(true);
        tryStartQueue();
        return id;
    }

    private void tryStartQueue() {
        //TODO if (cfg.getNoParallelDl() && DOWNLOAD_THREADS.size() != 0) return;
        if (DOWNLOAD_THREADS.size() != 0) return;

        for (Iterator<Integer> it = DOWNLOAD_QUEUE.iterator(); it.hasNext(); ) {
            int id = it.next();
            DlState state = getState(id);
            int status = state.getStatus();
            if (status == DlState.STATUS_PAUSED_USER || status == DlState.STATUS_CANCELLED_USER) continue;

            int netCheck = checkNetwork(state);
            if (netCheck == NETWORK_OK) {
                state.setStatus(DlState.STATUS_STARTING);
                DownloadTask task = new DownloadTask(state, this, this);
                DOWNLOAD_THREADS.put(id, task);

                updateStatusNotif(true);

                task.execute();
                it.remove();

                //TODO if (cfg.getNoParallelDl()) break;
                break;
            } else {
                switch (netCheck) {
                case NETWORK_NO_WIFI:
                case NETWORK_SIZE_EXCEEDED:
                    state.setStatus(DlState.STATUS_PAUSED_FOR_WIFI);
                    break;
                case NETWORK_NOT_CONNECTED:
                    state.setStatus(DlState.STATUS_PAUSED_FOR_DATA);
                    break;
                default:
                    state.setStatus(DlState.STATUS_PAUSED_SYSTEM);
                }

                updateStatusNotif(true);
            }
        }
    }

    private void cleanupFinish(DlState state) {
        DOWNLOAD_THREADS.delete(state.getId());
        if (DOWNLOAD_THREADS.size() == 0) {
            //stopForeground(true);
            wakeLock.release();
            DELAY_STOP_HANDLER.sendMessageDelayed(DELAY_STOP_HANDLER.obtainMessage(), IDLE_DELAY);
        } else {
            updateStatusNotif(true);
        }
    }

    private void updateStatusNotif(boolean force) {
        if (System.currentTimeMillis() < minNextNotifUpdate && !force) return;
        minNextNotifUpdate = System.currentTimeMillis() + NOTIF_REFRESH_DELAY;

        Notification.Builder builder = new Notification.Builder(this);
        if (DOWNLOAD_THREADS.size() <= 1) {
            DlState state = null;
            if (DOWNLOAD_THREADS.size() == 0) {
                if (DOWNLOAD_QUEUE.size() == 0) {
                    state = DOWNLOADS.valueAt(DOWNLOADS.size() - 1);
                } else {
                    state = getState(DOWNLOAD_QUEUE.get(0));
                }
            } else {
                state = DOWNLOAD_THREADS.valueAt(0).getState();
            }
            if (state == null) return;
            if (state.wasOneTimeNotifShown()) return;

            if (state.isRomDownload()) {
                builder.setContentTitle(getString(R.string.notif_downloading_rom, state.getRomInfo().version));
            } else if (state.isKernelDownload()) {
                builder.setContentTitle(getString(R.string.notif_downloading_kernel, state.getKernelInfo().version));
            } else {
                builder.setContentTitle(getString(R.string.notif_downloading));
            }
            builder.setTicker(getString(R.string.notif_downloading));

            int status = state.getStatus();

            boolean active = status != DlState.STATUS_CANCELLED_USER &&
                    status != DlState.STATUS_COMPLETED &&
                    status != DlState.STATUS_FAILED;
            builder.setOngoing(active);
            builder.setSmallIcon(status == DlState.STATUS_RUNNING ? android.R.drawable.stat_sys_download : android.R.drawable.stat_sys_download_done);

            if (status == DlState.STATUS_COMPLETED) {
                Intent i = new Intent(this, DownloadsActivity.class);
                //TODO intent to flash shit
                builder.setContentIntent(PendingIntent.getActivity(this, 1, i, 0));
            } else if (active) {
                Intent i = new Intent(this, DownloadsActivity.class);
                i.putExtra(DownloadsActivity.EXTRA_GOTO_TYPE, DownloadsActivity.GOTO_TYPE_PENDING);
                builder.setContentIntent(PendingIntent.getActivity(this, 2, i, 0));
            } else {
                Intent i = new Intent(this, DownloadsActivity.class);
                i.putExtra(DownloadsActivity.EXTRA_GOTO_TYPE, DownloadsActivity.GOTO_TYPE_RECENT);
                builder.setContentIntent(PendingIntent.getActivity(this, 3, i, 0));
            }

            if (active) {
                if (status == DlState.STATUS_QUEUED || status == DlState.STATUS_STARTING || state.getTotalSize() == 0) {
                    builder.setProgress(0, 0, true);
                } else {
                    builder.setProgress((int) state.getTotalSize(), (int) state.getTotalDone(), false);
                    builder.setContentInfo(getString(R.string.downloads_pct_progress, Math.round(100.0f * (float) state.getPctDone())));
                }

                if (status == DlState.STATUS_RUNNING) {
                    Intent i = new Intent(this, DownloadReceiver.class);
                    i.setAction(SERVICE_ACTION);
                    i.putExtra(EXTRA_CMD, CMD_PAUSE);
                    i.putExtra(EXTRAL_DOWNLOAD_ID, state.getId());
                    builder.addAction(0, getString(R.string.notif_pause), PendingIntent.getBroadcast(this, 4, i, PendingIntent.FLAG_UPDATE_CURRENT));
                } else if (status == DlState.STATUS_PAUSED_USER) {
                    Intent i = new Intent(this, DownloadReceiver.class);
                    i.setAction(SERVICE_ACTION);
                    i.putExtra(EXTRA_CMD, CMD_RESUME);
                    i.putExtra(EXTRAL_DOWNLOAD_ID, state.getId());
                    builder.addAction(0, getString(R.string.notif_resume), PendingIntent.getBroadcast(this, 5, i, PendingIntent.FLAG_UPDATE_CURRENT));
                }

                Intent i = new Intent(this, DownloadReceiver.class);
                i.setAction(SERVICE_ACTION);
                i.putExtra(EXTRA_CMD, CMD_CANCEL);
                i.putExtra(EXTRAL_DOWNLOAD_ID, state.getId());
                builder.addAction(0, getString(R.string.notif_cancel), PendingIntent.getBroadcast(this, 6, i, PendingIntent.FLAG_UPDATE_CURRENT));
            } else {
                if (status == DlState.STATUS_FAILED || status == DlState.STATUS_CANCELLED_USER) {
                    Intent i = new Intent(this, DownloadReceiver.class);
                    i.setAction(SERVICE_ACTION);
                    i.putExtra(EXTRA_CMD, CMD_RETRY);
                    i.putExtra(EXTRAL_DOWNLOAD_ID, state.getId());
                    builder.addAction(0, getString(R.string.notif_retry), PendingIntent.getBroadcast(this, 7, i, PendingIntent.FLAG_UPDATE_CURRENT));
                } else if (status == DlState.STATUS_COMPLETED) {
                    Intent i = new Intent(this, DownloadsActivity.class);
                    //TODO intent to flash shit
                    builder.addAction(0, getString(R.string.notif_flash), PendingIntent.getActivity(this, 8, i, 0));
                }
                builder.setAutoCancel(true);
                state.setOneTimeNotifShown(true);
            }

            if (status == DlState.STATUS_RUNNING) {
                builder.setContentText(state.getProgressStr(this));
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
                builder.setContentText(getString(subtext));
            }
        } else {
            //TODO multiple download notif
        }

        nm.notify(Config.DL_STATUS_NOTIF_ID, builder.build());
    }

    public void cancel(int id) {
        DlState state = DOWNLOADS.get(id);
        if (state == null) return;
        if (state.getStatus() == DlState.STATUS_CANCELLED_USER ||
                state.getStatus() == DlState.STATUS_COMPLETED ||
                state.getStatus() == DlState.STATUS_FAILED) return;
        state.setStatus(DlState.STATUS_CANCELLED_USER);

        updateStatusNotif(true);
        saveState(true);

        DownloadTask task = DOWNLOAD_THREADS.get(id);
        if (task == null) return;
        task.cancel();
    }

    public void pause(int id) {
        DlState state = DOWNLOADS.get(id);
        if (state == null) return;
        if (state.getStatus() != DlState.STATUS_RUNNING) return;
        state.setStatus(DlState.STATUS_PAUSED_USER);

        updateStatusNotif(true);
        saveState(true);

        DownloadTask task = DOWNLOAD_THREADS.get(id);
        if (task == null) return;
        task.pause();
    }

    public void resume(int id) {
        DlState state = DOWNLOADS.get(id);
        if (state == null) return;
        if (state.getStatus() != DlState.STATUS_PAUSED_USER) return;
        state.setStatus(DlState.STATUS_QUEUED);
        DOWNLOAD_QUEUE.add(id);

        updateStatusNotif(true);
        saveState(true);

        tryStartQueue();
    }

    public void retry(int id) {
        DlState state = DOWNLOADS.get(id);
        if (state == null) return;

        int status = state.getStatus();
        if (status == DlState.STATUS_CANCELLED_USER ||
                status == DlState.STATUS_COMPLETED ||
                status == DlState.STATUS_FAILED) {
            state.resetState();
            DOWNLOAD_QUEUE.add(id);

            updateStatusNotif(true);
            saveState(true);

            tryStartQueue();
        }
    }

    public int getStatus(int id) {
        return getState(id).getStatus();
    }

    public long getTotalSize(int id) {
        return getState(id).getTotalSize();
    }

    public long getDoneSize(int id) {
        return getState(id).getTotalDone();
    }

    private DlState getState(int id) {
        DlState state = DOWNLOADS.get(id);
        if (state == null) throw new InvalidDownloadException();
        return state;
    }

    public DlState getDownload(int id) {
        return DOWNLOADS.get(id);
    }

    public void getDownloads(List<DlState> list) {
        getDownloads(list, 0);
    }

    public void getDownloads(List<DlState> list, int filter) {
        list.clear();
        for (int q = 0; q < DOWNLOADS.size(); q++) {
            DlState state = DOWNLOADS.valueAt(q);
            if (state.matchesFilter(filter)) list.add(state);
        }
    }

    @TargetApi(11)
    private int checkNetwork(DlState state) {
        isNetStateDirty = false;

        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        NetworkInfo ni = cm.getActiveNetworkInfo();

        if (ni == null || !ni.isConnectedOrConnecting()) return NETWORK_NOT_CONNECTED;
        if (cfg.getWifiOnlyDl() && ni.getType() != ConnectivityManager.TYPE_WIFI) return NETWORK_NO_WIFI;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.GINGERBREAD) {
            if (ni.getType() != ConnectivityManager.TYPE_WIFI) {
                Long maxMobileSize = DownloadManager.getMaxBytesOverMobile(this);
                if (state.getTotalSize() > 0 &&
                        maxMobileSize != null &&
                        state.getTotalSize() > maxMobileSize)
                    return NETWORK_SIZE_EXCEEDED;
            }
        }

        return NETWORK_OK;
    }

    private final IBinder BINDER = new ServiceStub(this);
    protected static class ServiceStub extends IDownloadService.Stub {
        private WeakReference<DownloadService> service;

        protected ServiceStub(DownloadService service) {
            this.service = new WeakReference<DownloadService>(service);
        }

        @Override
        public int queueRomDownload(RomInfo info) {
            return service.get().queueDownload(info);
        }

        @Override
        public int queueKernelDownload(KernelInfo info) {
            return service.get().queueDownload(info);
        }

        @Override
        public int getStatus(int id) {
            return service.get().getStatus(id);
        }

        @Override
        public long getTotalSize(int id) {
            return service.get().getTotalSize(id);
        }

        @Override
        public long getDoneSize(int id) {
            return service.get().getDoneSize(id);
        }

        @Override
        public void cancel(int id) {
            service.get().cancel(id);
        }

        @Override
        public void pause(int id) {
            service.get().pause(id);
        }

        @Override
        public void resume(int id) {
            service.get().resume(id);
        }

        @Override
        public void retry(int id) {
            service.get().retry(id);
        }

        @Override
        public void getDownloads(List<DlState> list) {
            service.get().getDownloads(list);
        }

        @Override
        public void getDownloadsFilt(List<DlState> list, int filter) {
            service.get().getDownloads(list, filter);
        }

        @Override
        public DlState getDownload(int id) {
            return service.get().getDownload(id);
        }
    }

    public static class BindUtil {
        protected static IDownloadService service = null;
        private static HashMap<Context, ServiceBinder> connections = new HashMap<Context, ServiceBinder>();

        public static Token bindToService(Context ctx) {
            return bindToService(ctx, null);
        }

        public static Token bindToService(Context ctx, ServiceConnection callback) {
            ContextWrapper wrapper = new ContextWrapper(ctx.getApplicationContext());
            wrapper.startService(new Intent(wrapper, DownloadService.class));
            ServiceBinder sb = new ServiceBinder(callback);
            if (wrapper.bindService((new Intent()).setClass(wrapper, DownloadService.class), sb, 0)) {
                connections.put(wrapper, sb);
                return new Token(wrapper);
            }
            Log.e(Config.LOG_TAG + "Bind", "Failed to bind to service!");
            return null;
        }

        public static void unbindFromService(Token token) {
            if (token == null) {
                Log.e(Config.LOG_TAG + "Unbind", "null token!");
                return;
            }

            ContextWrapper wrapper = token.ctxWrapper;
            ServiceBinder sb = connections.get(wrapper);
            if (sb == null) {
                Log.e(Config.LOG_TAG + "Unbind", "unknown context!");
                return;
            }

            wrapper.unbindService(sb);
            if (connections.isEmpty()) service = null;
        }

        public static class Token {
            protected ContextWrapper ctxWrapper;
            public Token(ContextWrapper ctxWrapper) {
                this.ctxWrapper = ctxWrapper;
            }
        }

        private static class ServiceBinder implements ServiceConnection {
            private ServiceConnection callback = null;
            public ServiceBinder(ServiceConnection callback) {
                this.callback = callback;
            }

            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                BindUtil.service = IDownloadService.Stub.asInterface(service);
                if (callback != null) callback.onServiceConnected(name, service);
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                if (callback != null) callback.onServiceDisconnected(name);
                BindUtil.service = null;
            }
        }
    }
}

class InvalidDownloadException extends RuntimeException {
    private static final long serialVersionUID = 4325699990746007467L;
}
