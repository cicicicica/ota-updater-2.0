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
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;

import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPReply;
import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;

import android.content.Context;
import android.net.Uri;
import android.net.http.AndroidHttpClient;
import android.os.AsyncTask;
import android.os.StatFs;
import android.util.Log;

import com.otaupdater.DownloadService;
import com.otaupdater.utils.DownloadTask.DownloadResult;

public class DownloadTask extends AsyncTask<Void, Boolean, DownloadResult> {
    private Context context;

    private DownloadListener callback = null;

    private DlState state;

    private boolean active = false;
    private boolean pausing = false;

    public DownloadTask(DlState state, Context ctx) {
        this(state, ctx, null);
    }

    public DownloadTask(DlState state, Context ctx, DownloadListener callback) {
        this.state = state;
        this.callback = callback;
        this.context = ctx;

        state.setTask(this);
    }

    public boolean isActive() {
        return active;
    }

    public DlState getState() {
        return state;
    }

    @Override
    protected void onPreExecute() {
        active = true;
        if (callback != null) callback.onStart(state);
    }

    @Override
    protected DownloadResult doInBackground(Void... params) {
        AndroidHttpClient httpc = null;
        FTPClient ftpc = null;

        InputStream in = null;
        OutputStream out = null;
        try {
            File dest = state.getDestFile();
            File dir = dest.getParentFile();
            if (dir == null) {
                state.setStatus(DlState.STATUS_FAILED);
                return state.setResult(DownloadResult.FAILED_MOUNT_NOT_AVAILABLE);
            }
            dir.mkdirs();
            if (!dir.exists()) {
                state.setStatus(DlState.STATUS_FAILED);
                return state.setResult(DownloadResult.FAILED_MOUNT_NOT_AVAILABLE);
            }

            if (dest.exists()) {
                if (dest.length() == 0) {
                    dest.delete();
                } else if (state.getTotalDone() == state.getTotalSize() && dest.length() == state.getTotalDone()) {
                    state.setStatus(DlState.STATUS_COMPLETED);
                    return state.setResult(DownloadResult.FINISHED);
                } else {
                    if (dest.length() != state.getTotalDone()) {
                        dest.delete();
                    } else {
                        if (state.getETag() == null) {
                            dest.delete();
                        } else {
                            state.setContinuing(true);
                            out = new FileOutputStream(dest, true);
                        }
                    }
                }
            }

            if (callback != null) {
                int checkResult = callback.onCheckContinue(state);
                if (checkResult != 0) {
                    if (checkResult == DownloadService.STOP_NO_WIFI) {
                        state.setStatus(DlState.STATUS_PAUSED_FOR_WIFI);
                        return state.setResult(DownloadResult.PAUSED);
                    }
                    if (checkResult == DownloadService.STOP_NO_DATA) {
                        state.setStatus(DlState.STATUS_PAUSED_FOR_DATA);
                        return state.setResult(DownloadResult.PAUSED);
                    }
                }
            }

            Uri dlUri = Uri.parse(state.getSourceURL());
            if (dlUri.getScheme().equals("http")) {
                httpc = AndroidHttpClient.newInstance(Config.HTTPC_UA, context);
                HttpResponse resp = null;
                while (true) {
                    HttpGet req = null;
                    boolean success = false;
                    try {
                        req = new HttpGet(state.getSourceURL());
                        if (state.isContinuing()) {
                            req.addHeader("If-Match", state.getETag());
                            req.addHeader("Range", "bytes=" + state.getTotalDone() + "-");
                        }
                        resp = httpc.execute(req);

                        int statusCode = resp.getStatusLine().getStatusCode();
                        if (statusCode == 503) {
                            if (state.getNumFailed() >= Config.DL_MAX_RETRIES) {
                                state.setStatus(DlState.STATUS_FAILED);
                                return state.setResult(DownloadResult.FAILED_TOO_MANY_RETRIES);
                            }
                            Header header = resp.getFirstHeader("Retry-After");
                            if (header == null) {
                                state.setRetryAfter(Config.DL_RETRY_MIN * (1 << state.getNumFailed()));
                            } else {
                                int retry = Integer.parseInt(header.getValue());
                                if (retry < 0) {
                                    state.setRetryAfter(Config.DL_RETRY_MIN);
                                } else {
                                    if (retry < Config.DL_RETRY_MIN) retry = Config.DL_RETRY_MIN;
                                    if (retry > Config.DL_RETRY_MAX) retry = Config.DL_RETRY_MAX;
                                    retry += (int) (Config.DL_RETRY_MIN * Math.random());
                                    state.setRetryAfter(retry);
                                }
                            }
                            state.incNumFailed();
                            state.setStatus(DlState.STATUS_PAUSED_RETRY);
                            return state.setResult(DownloadResult.RETRY_LATER);
                        }
                        if (statusCode == 301 || statusCode == 302 || statusCode == 303 || statusCode == 307) {
                            if (state.getNumRedirects() >= Config.DL_MAX_REDIRECTS) {
                                state.setStatus(DlState.STATUS_FAILED);
                                return state.setResult(DownloadResult.FAILED_TOO_MANY_REDIRECTS);
                            }
                            Header header = resp.getFirstHeader("Location");
                            if (header == null) {
                                state.setStatus(DlState.STATUS_FAILED);
                                return state.setResult(DownloadResult.FAILED_PROTOCAL_ERROR);
                            }
                            String newUri;
                            try {
                                newUri = new URI(state.getSourceURL()).resolve(new URI(header.getValue())).toString();
                            } catch (URISyntaxException e) {
                                state.setStatus(DlState.STATUS_FAILED);
                                return state.setResult(DownloadResult.FAILED_PROTOCAL_ERROR);
                            }
                            state.incNumRedirects();
                            state.setRedirectURL(newUri);
                        }
                        if (statusCode != (state.isContinuing() ? 206 : 200)) {
                            state.setStatus(DlState.STATUS_FAILED);
                            if (statusCode == 416 || (state.isContinuing() && statusCode != 206)) {
                                return state.setResult(DownloadResult.FAILED_CANNOT_RESUME);
                            } else if (statusCode >= 300 && statusCode < 400) {
                                return state.setResult(DownloadResult.FAILED_UNHANDLED_REDIRECT);
                            } else if (statusCode >= 400 && statusCode < 600) {
                                return state.setResult(statusCode == 404 ? DownloadResult.FAILED_FILE_NOT_FOUND : DownloadResult.FAILED_HTTP_ERROR_CODE);
                            } else {
                                return state.setResult(DownloadResult.FAILED_UNHANDLED_HTTP_CODE);
                            }
                        }

                        success = true;
                        break;
                    } catch (IllegalArgumentException e) {
                        state.setStatus(DlState.STATUS_FAILED);
                        return state.setResult(DownloadResult.FAILED_PROTOCAL_ERROR);
                    } catch (IOException e) {
                        state.setStatus(DlState.STATUS_FAILED);
                        return state.setResult(DownloadResult.FAILED_NETWORK_ERROR);
                    } finally {
                        if (req != null && !success) {
                            req.abort();
                            req = null;
                        }
                    }
                }

                if (!state.isContinuing()) {
                    Header header = resp.getFirstHeader("ETag");
                    if (header != null) state.setETag(header.getValue());

                    String headerTransferEncoding = null;
                    header = resp.getFirstHeader("Transfer-Encoding");
                    if (header != null) {
                        headerTransferEncoding = header.getValue();
                    }
                    if (headerTransferEncoding == null) {
                        header = resp.getFirstHeader("Content-Length");
                        if (header != null) {
                            state.setTotalSize(Long.parseLong(header.getValue()));
                            publishProgress(true);
                        }
                    }

                    if (state.getTotalSize() != 0) {
                        StatFs stat = new StatFs(dir.getAbsolutePath());
                        long availSpace = ((long) stat.getAvailableBlocks()) * ((long) stat.getBlockSize());
                        if (state.getTotalSize() >= availSpace) {
                            state.setStatus(DlState.STATUS_FAILED);
                            return state.setResult(DownloadResult.FAILED_NOT_ENOUGH_SPACE);
                        }
                    }

                    out = new FileOutputStream(dest, false);
                } else {
                    publishProgress(true);
                }

                in = resp.getEntity().getContent();
            } else if (dlUri.getScheme().equals("ftp")) {
                ftpc = new FTPClient();

                if (dlUri.getPort() == -1) {
                    ftpc.connect(dlUri.getHost());
                } else {
                    ftpc.connect(dlUri.getHost(), dlUri.getPort());
                }

                if (!FTPReply.isPositiveCompletion(ftpc.getReplyCode())) {
                    state.setStatus(DlState.STATUS_FAILED);
                    return state.setResult(DownloadResult.FAILED_CONNECTION_REFUSED);
                }

                boolean loginRes;
                if (dlUri.getUserInfo() == null) {
                    loginRes = ftpc.login("anonymous", "anonymous");
                } else {
                    String[] parts = dlUri.getUserInfo().split(":", 2);
                    loginRes = ftpc.login(parts[0], parts[1]);
                }

                if (!loginRes) {
                    state.setStatus(DlState.STATUS_FAILED);
                    return state.setResult(DownloadResult.FAILED_FTP_LOGIN_ERROR);
                }

                ftpc.enterLocalPassiveMode();
                ftpc.setFileType(FTP.BINARY_FILE_TYPE);

                if (state.isContinuing()) {
                    ftpc.setRestartOffset(state.getTotalDone());
                    publishProgress(true);
                } else {
                    FTPFile[] files = ftpc.listFiles(dlUri.getPath());
                    if (files == null || files.length == 0) {
                        state.setStatus(DlState.STATUS_FAILED);
                        return state.setResult(DownloadResult.FAILED_FILE_NOT_FOUND);
                    } else {
                        state.setTotalSize(files[0].getSize());
                        publishProgress(true);

                        StatFs stat = new StatFs(dir.getAbsolutePath());
                        long availSpace = ((long) stat.getAvailableBlocks()) * ((long) stat.getBlockSize());
                        if (state.getTotalSize() >= availSpace) {
                            state.setStatus(DlState.STATUS_FAILED);
                            return state.setResult(DownloadResult.FAILED_NOT_ENOUGH_SPACE);
                        }

                        out = new FileOutputStream(dest, false);
                    }
                }

                in = ftpc.retrieveFileStream(dlUri.getPath());
            } else {
                Log.e(Config.LOG_TAG + "DLTask", "invalid scheme " + dlUri.getScheme());
            }

            byte[] buf = new byte[4096];
            while (true) {
                if (this.isCancelled()) {
                    if (pausing) {
                        Log.v(Config.LOG_TAG + "DLTask", "pausing - user request");
                        state.setStatus(DlState.STATUS_PAUSED_USER);
                        return state.setResult(DownloadResult.PAUSED);
                    } else {
                        Log.v(Config.LOG_TAG + "DLTask", "cancel - user request");
                        state.setStatus(DlState.STATUS_CANCELLED_USER);
                        return state.setResult(DownloadResult.CANCELLED);
                    }
                } else if (callback != null) {
                    int check = callback.onCheckContinue(state);
                    if (check != 0) {
                        if (check == DownloadService.STOP_NO_WIFI) {
                            Log.v(Config.LOG_TAG + "DLTask", "pausing - need wifi");
                            state.setStatus(DlState.STATUS_PAUSED_FOR_WIFI);
                            return state.setResult(DownloadResult.PAUSED);
                        }
                        if (check == DownloadService.STOP_NO_DATA) {
                            Log.v(Config.LOG_TAG + "DLTask", "pausing - need data");
                            state.setStatus(DlState.STATUS_PAUSED_FOR_DATA);
                            return state.setResult(DownloadResult.PAUSED);
                        }
                    }
                }

                int nRead = -1;
                try {
                    nRead = in.read(buf);
                } catch (IOException e) {
                    boolean data = Utils.dataAvailable(context);
                    Log.w(Config.LOG_TAG + "DLTask", "IOException reading - connected=" + data);

                    if (!data) {
                        Log.v(Config.LOG_TAG + "DLTask", "pausing - need data");
                        state.setStatus(DlState.STATUS_PAUSED_FOR_DATA);
                        return state.setResult(DownloadResult.PAUSED);
                    }
                    continue;
                }

                if (nRead == -1) break;

                out.write(buf, 0, nRead);
                state.incTotalDone(nRead);
                publishProgress();
            }

            if (state.getTotalSize() != state.getTotalDone() && state.getTotalSize() != 0) {
                Log.w(Config.LOG_TAG + "DLTask", "size mismatch after download");
                //TODO size mismatch - fail?
            }

            state.setStatus(DlState.STATUS_COMPLETED);
            return state.setResult(DownloadResult.FINISHED);
        } catch (IOException e) {
            //Log.w(Config.LOG_TAG + "DLTask", "IOException: " + e.getMessage());
            e.printStackTrace();
            state.setStatus(DlState.STATUS_FAILED);
            return state.setResult(DownloadResult.FAILED_NETWORK_ERROR);
        } catch (Exception e) {
            //Log.w(Config.LOG_TAG + "DLTask", "Exception (" + e.getClass().getName() + "): " + e.getMessage());
            e.printStackTrace();
            state.setStatus(DlState.STATUS_FAILED);
            return state.setResult(DownloadResult.FAILED_UNKNOWN);
        } finally {
            if (in != null) {
                try { in.close(); }
                catch (IOException e) { }
            }

            if (out != null) {
                try { out.flush(); out.close(); }
                catch (IOException e) { }
            }

            if (httpc != null) {
                httpc.close();
                httpc = null;
            }

            if (ftpc != null) {
                try {
                    if (ftpc.isConnected()) {
                        ftpc.abort();
                        ftpc.logout();
                        ftpc.disconnect();
                    }
                    ftpc = null;
                } catch (IOException e) { }
            }
        }
    }

    public void pause() {
        pausing = true;
        cancel(true);
    }

    public void cancel() {
        pausing = false;
        cancel(true);
    }

    @Override
    protected void onCancelled(DownloadResult result) {
        active = false;
        if (callback != null) {
            if (pausing) callback.onPause(state);
            callback.onFinish(state, pausing ? DownloadResult.PAUSED : DownloadResult.CANCELLED);
        }
        pausing = false;
    }

    @Override
    protected void onPostExecute(DownloadResult result) {
        active = false;
        if (callback != null) {
            callback.onFinish(state, result);
        }
    }

    @Override
    protected void onProgressUpdate(Boolean... flags) {
        if (callback == null) return;
        if (flags.length != 0) {
            if (flags[0]) callback.onLengthReceived(state);
            callback.onProgress(state);
        } else {
            callback.onProgress(state);
        }
    }

    public static enum DownloadResult {
        FINISHED, CANCELLED, PAUSED, RETRY_LATER, FAILED_UNKNOWN, FAILED_FILE_NOT_FOUND,
        FAILED_MOUNT_NOT_AVAILABLE, FAILED_NOT_ENOUGH_SPACE, FAILED_PROTOCAL_ERROR, FAILED_NETWORK_ERROR,
        FAILED_TOO_MANY_REDIRECTS, FAILED_TOO_MANY_RETRIES, FAILED_CANNOT_RESUME, FAILED_UNHANDLED_REDIRECT,
        FAILED_UNHANDLED_HTTP_CODE, FAILED_HTTP_ERROR_CODE, FAILED_FTP_LOGIN_ERROR, FAILED_CONNECTION_REFUSED
    }

    public static interface DownloadListener {
        void onStart(DlState state);
        int onCheckContinue(DlState state);
        void onLengthReceived(DlState state);
        void onProgress(DlState state);
        void onPause(DlState state);
        void onFinish(DlState state, DownloadResult result);
    }
}
