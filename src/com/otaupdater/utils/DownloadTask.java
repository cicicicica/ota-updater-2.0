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

package com.otaupdater.utils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;

import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;

import android.net.http.AndroidHttpClient;
import android.os.AsyncTask;
import android.os.StatFs;

import com.otaupdater.DownloadService;
import com.otaupdater.utils.DownloadTask.DownloadResult;

public class DownloadTask extends AsyncTask<Void, Boolean, DownloadResult> {
    private DownloadListener callback = null;

    private DlState state;

    private boolean active = false;
    private boolean pausing = false;

    public DownloadTask(DlState state) {
        this(state, null);
    }

    public DownloadTask(DlState state, DownloadListener callback) {
        this.state = state;
        this.callback = callback;

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
        AndroidHttpClient httpc = AndroidHttpClient.newInstance("OTA Update Center Downloader");

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
                } else if (dest.length() == state.getTotalDone() && state.getTotalDone() == state.getTotalSize()) {
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

            HttpResponse resp = null;
            while (true) {
                HttpGet req = null;
                try {
                    req = new HttpGet(state.getSourceURL());
                    if (state.isContinuing()) {
                        req.addHeader("If-Match", state.getETag());
                        req.addHeader("Range", "bytes=" + state.getTotalDone());
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
                            return state.setResult(DownloadResult.FAILED_HTTP_PROTOCAL_ERROR);
                        }
                        String newUri;
                        try {
                            newUri = new URI(state.getSourceURL()).resolve(new URI(header.getValue())).toString();
                        } catch (URISyntaxException e) {
                            state.setStatus(DlState.STATUS_FAILED);
                            return state.setResult(DownloadResult.FAILED_HTTP_PROTOCAL_ERROR);
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
                            return state.setResult(DownloadResult.FAILED_ERROR_HTTP_CODE);
                        } else {
                            return state.setResult(DownloadResult.FAILED_UNHANDLED_HTTP_CODE);
                        }
                    }

                    break;
                } catch (IllegalArgumentException e) {
                    state.setStatus(DlState.STATUS_FAILED);
                    return state.setResult(DownloadResult.FAILED_HTTP_PROTOCAL_ERROR);
                } catch (IOException e) {
                    state.setStatus(DlState.STATUS_FAILED);
                    return state.setResult(DownloadResult.FAILED_NETWORK_ERROR);
                } finally {
                    if (req != null) {
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
                        publishProgress(true);
                    }
                }

                if (state.getTotalSize() != 0) {
                    StatFs stat = new StatFs(dest.getAbsolutePath());
                    long availSpace = ((long) stat.getAvailableBlocks()) * ((long) stat.getBlockSize());
                    if (state.getTotalSize() >= availSpace) {
                        state.setStatus(DlState.STATUS_FAILED);
                        return state.setResult(DownloadResult.FAILED_NOT_ENOUGH_SPACE);
                    }
                }

                out = new FileOutputStream(dest);
            } else {
                publishProgress(true);
            }

            byte[] buf = new byte[4096];
            int nRead = -1;
            in = resp.getEntity().getContent();
            while ((nRead = in.read(buf)) != -1) {
                out.write(buf, 0, nRead);
                state.incTotalDone(nRead);
                publishProgress();

                if (this.isCancelled()) {
                    if (pausing) {
                        state.setStatus(DlState.STATUS_PAUSED_USER);
                        return state.setResult(DownloadResult.PAUSED);
                    } else {
                        state.setStatus(DlState.STATUS_CANCELLED_USER);
                        return state.setResult(DownloadResult.CANCELLED);
                    }
                } else if (callback != null) {
                    int check = callback.onCheckContinue(state);
                    if (check != 0) {
                        if (check == DownloadService.STOP_NO_WIFI) {
                            state.setStatus(DlState.STATUS_PAUSED_FOR_WIFI);
                            return state.setResult(DownloadResult.PAUSED);
                        }
                        if (check == DownloadService.STOP_NO_DATA) {
                            state.setStatus(DlState.STATUS_PAUSED_FOR_DATA);
                            return state.setResult(DownloadResult.PAUSED);
                        }
                    }
                }
            }

            if (state.getTotalSize() != state.getTotalDone() && state.getTotalSize() != 0) {
                //TODO size mismatch - fail?
            }

            state.setStatus(DlState.STATUS_COMPLETED);
            return state.setResult(DownloadResult.FINISHED);
        } catch (IOException e) {
            state.setStatus(DlState.STATUS_FAILED);
            return state.setResult(DownloadResult.FAILED_NETWORK_ERROR);
        } catch (Exception e) {
            state.setStatus(DlState.STATUS_FAILED);
            return state.setResult(DownloadResult.FAILED_UNKNOWN);
        } finally {
            httpc.close();
            httpc = null;

            if (in != null) {
                try { in.close(); }
                catch (IOException e) { }
            }
            if (out != null) {
                try { out.flush(); out.close(); }
                catch (IOException e) { }
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
        FINISHED, CANCELLED, PAUSED, RETRY_LATER, FAILED_UNKNOWN,
        FAILED_MOUNT_NOT_AVAILABLE, FAILED_NOT_ENOUGH_SPACE, FAILED_HTTP_PROTOCAL_ERROR, FAILED_NETWORK_ERROR,
        FAILED_TOO_MANY_REDIRECTS, FAILED_TOO_MANY_RETRIES, FAILED_CANNOT_RESUME, FAILED_UNHANDLED_REDIRECT,
        FAILED_UNHANDLED_HTTP_CODE, FAILED_ERROR_HTTP_CODE
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
