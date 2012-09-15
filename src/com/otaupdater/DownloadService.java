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
import android.app.Activity;
import android.app.DownloadManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.util.Log;
import android.util.SparseArray;
import android.view.View;
import android.widget.RemoteViews;

import com.otaupdater.utils.Config;
import com.otaupdater.utils.DlState;
import com.otaupdater.utils.DownloadTask;
import com.otaupdater.utils.DownloadTask.DownloadListener;
import com.otaupdater.utils.DownloadTask.DownloadResult;
import com.otaupdater.utils.KernelInfo;
import com.otaupdater.utils.RomInfo;

public class DownloadService extends Service implements DownloadListener {
    public static final String SERVICE_ACTION = "com.otaupdater.downloadservice.command";

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
    private Notification statusNotif;
    private WakeLock wakeLock;

    private boolean isNetStateDirty = true;

    private long minNextNotifUpdate = 0;
    private static final long NOTIF_REFRESH_DELAY = 500;

    private long minNextWriteUpdate = 0;
    private static final long WRITE_STATE_DELAY = 100;
    private static final String STATE_STORE_NAME = "service_state";

    private final BroadcastReceiver INTENT_RECEIVER = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ConnectivityManager.CONNECTIVITY_ACTION.equals(action)) {
                isNetStateDirty = true;
            } else if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
                if (DOWNLOAD_QUEUE.size() != 0) {
                    tryStartQueue();
                }
            } else if (SERVICE_ACTION.equals(action)) {
                int cmd = intent.getIntExtra("servicecmd", -1);
                if (cmd == -1) return;
                switch (cmd) {

                }
            }
        }
    };

    private static final int IDLE_DELAY = 60000;
    private final Handler DELAY_STOP_HANDLER = new StopHandler(this);
    private static class StopHandler extends Handler {
        private WeakReference<DownloadService> service;

        public StopHandler(DownloadService service) {
            this.service = new WeakReference<DownloadService>(service);
        }

        @Override
        public void handleMessage(Message msg) {
            if (service.get().serviceInUse) return;
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

        if (intent != null) {
            INTENT_RECEIVER.onReceive(getApplicationContext(), intent);
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

        IntentFilter commandFilter = new IntentFilter();
        commandFilter.addAction(SERVICE_ACTION);
        commandFilter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        registerReceiver(INTENT_RECEIVER, commandFilter);

        loadState();

        wakeLock = ((PowerManager) getSystemService(Context.POWER_SERVICE)).
                newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, this.getClass().getName());
        wakeLock.setReferenceCounted(false);

        DELAY_STOP_HANDLER.sendMessageDelayed(DELAY_STOP_HANDLER.obtainMessage(), IDLE_DELAY);
    }

    @Override
    public void onDestroy() {
        DELAY_STOP_HANDLER.removeCallbacksAndMessages(null);
        unregisterReceiver(INTENT_RECEIVER);
        wakeLock.release();
        super.onDestroy();
    }

    @Override
    public void onStart(DlState state) {
        wakeLock.acquire();
        state.setStatus(DlState.STATUS_RUNNING);
        updateStatusNotif();
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
        updateStatusNotif();
        saveState(true);
    }

    @Override
    public void onProgress(DlState state) {
        updateStatusNotif(false);
        saveState(false);
    }

    @Override
    public void onPause(DlState state) {
        updateStatusNotif();
        cleanupFinish(state);
        saveState(true);
    }

    @Override
    public void onFinish(DlState state, DownloadResult result) {
        updateStatusNotif();
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
                    os.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
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
                try {
                    is.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
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
                DownloadTask task = new DownloadTask(state, this);
                DOWNLOAD_THREADS.put(id, task);
                task.execute();
                it.remove();

                RemoteViews statusView = new RemoteViews(getPackageName(), R.layout.download_status);

                statusNotif = new Notification();
                statusNotif.contentView = statusView;
                statusNotif.flags |= Notification.FLAG_NO_CLEAR;
                statusNotif.icon = R.drawable.ic_download_default;
                statusNotif.contentIntent = PendingIntent.getActivity(getApplicationContext(), 0,
                        new Intent(getApplicationContext(), Downloads.class), 0);
                startForeground(Config.DL_STATUS_NOTIF_ID, statusNotif);

                updateStatusNotif();

                //TODO if (cfg.getNoParallelDl()) break;
                break;
            }
        }
    }

    private void cleanupFinish(DlState state) {
        DOWNLOAD_THREADS.delete(state.getId());
        if (DOWNLOAD_THREADS.size() == 0) {
            stopForeground(true);
            wakeLock.release();
            DELAY_STOP_HANDLER.sendMessageDelayed(DELAY_STOP_HANDLER.obtainMessage(), IDLE_DELAY);
        } else {
            updateStatusNotif();
        }
    }

    private void updateStatusNotif() {
        updateStatusNotif(true);
    }

    private void updateStatusNotif(boolean force) {
        if (System.currentTimeMillis() < minNextNotifUpdate && !force) return;
        minNextNotifUpdate = System.currentTimeMillis() + NOTIF_REFRESH_DELAY;

        if (DOWNLOAD_THREADS.size() == 1) {
            DlState state = DOWNLOAD_THREADS.valueAt(0).getState();

            if (state.isRomDownload()) {
                statusNotif.contentView.setTextViewText(R.id.download_subtext,
                        getString(R.string.notif_downloading_rom, state.getRomInfo().version));
            } else if (state.isKernelDownload()) {
                statusNotif.contentView.setTextViewText(R.id.download_subtext,
                        getString(R.string.notif_downloading_kernel, state.getKernelInfo().version));
            }

            int status = state.getStatus();
            if (status == DlState.STATUS_RUNNING) {
                statusNotif.contentView.setTextViewText(R.id.download_bytes_text, state.getProgressStr(this));

                if (state.getTotalSize() == 0) {
                    statusNotif.contentView.setViewVisibility(R.id.download_pct_text, View.GONE);
                    statusNotif.contentView.setProgressBar(R.id.download_progress_bar, 0, 0, true);
                } else {
                    statusNotif.contentView.setTextViewText(R.id.download_pct_text, getString(R.string.downloads_pct_progress,
                            Math.round(100.0f * (float) state.getPctDone())));
                    statusNotif.contentView.setViewVisibility(R.id.download_pct_text, View.VISIBLE);
                    statusNotif.contentView.setProgressBar(R.id.download_progress_bar, state.getTotalSize(), state.getTotalDone(), false);
                }
                statusNotif.contentView.setViewVisibility(R.id.download_bytes_text, View.VISIBLE);
                statusNotif.contentView.setViewVisibility(R.id.download_progress_bar, View.VISIBLE);
                statusNotif.contentView.setViewVisibility(R.id.download_subtext, View.GONE);
            } else {
                if (status == DlState.STATUS_QUEUED || status == DlState.STATUS_STARTING) {
                    statusNotif.contentView.setViewVisibility(R.id.download_progress_bar, View.VISIBLE);
                    statusNotif.contentView.setProgressBar(R.id.download_progress_bar, 0, 0, true);
                } else if (status == DlState.STATUS_PAUSED_USER) {
                    statusNotif.contentView.setViewVisibility(R.id.download_progress_bar, View.VISIBLE);
                    statusNotif.contentView.setProgressBar(R.id.download_progress_bar, state.getTotalSize(), state.getTotalDone(), false);
                } else {
                    statusNotif.contentView.setViewVisibility(R.id.download_progress_bar, View.GONE);
                }
                statusNotif.contentView.setViewVisibility(R.id.download_bytes_text, View.GONE);
                statusNotif.contentView.setViewVisibility(R.id.download_pct_text, View.GONE);

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
                statusNotif.contentView.setTextViewText(R.id.download_subtext, getString(subtext));
                statusNotif.contentView.setViewVisibility(R.id.download_subtext, View.VISIBLE);
            }
        } else {
            //TODO multiple download notif
        }
        nm.notify(Config.DL_STATUS_NOTIF_ID, statusNotif);
    }

    public void cancel(int id) {
        DlState state = DOWNLOADS.get(id);
        state.setStatus(DlState.STATUS_CANCELLED_USER);
        DownloadTask task = DOWNLOAD_THREADS.get(id);
        if (task == null) return;
        task.cancel();
    }

    public void pause(int id) {
        DlState state = DOWNLOADS.get(id);
        state.setStatus(DlState.STATUS_PAUSED_USER);
        DownloadTask task = DOWNLOAD_THREADS.get(id);
        if (task == null) return;
        task.pause();
    }

    public void resume(int id) {
        DlState state = DOWNLOADS.get(id);
        state.setStatus(DlState.STATUS_QUEUED);
        DOWNLOAD_QUEUE.add(id);
        tryStartQueue();
    }

    public int getStatus(int id) {
        return getState(id).getStatus();
    }

    public int getTotalSize(int id) {
        return getState(id).getTotalSize();
    }

    public int getDoneSize(int id) {
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
            if (ni.getType() != ConnectivityManager.TYPE_WIFI && state.getTotalSize() > 0 &&
                    state.getTotalSize() > DownloadManager.getMaxBytesOverMobile(this)) return NETWORK_SIZE_EXCEEDED;
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
        public int getTotalSize(int id) {
            return service.get().getTotalSize(id);
        }

        @Override
        public int getDoneSize(int id) {
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

        public static Token bindToService(Activity act) {
            return bindToService(act, null);
        }

        public static Token bindToService(Activity act, ServiceConnection callback) {
            Activity realAct = act.getParent();
            if (realAct == null) realAct = act;

            ContextWrapper wrapper = new ContextWrapper(realAct);
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
