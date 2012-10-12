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
import java.io.Serializable;
import java.util.Date;

import android.content.Context;
import android.os.Parcel;
import android.os.Parcelable;

import com.otaupdater.R;
import com.otaupdater.utils.DownloadTask.DownloadResult;

public class DlState implements Parcelable, Serializable {
    private static final long serialVersionUID = -8208547298965710160L;

    public static final int SCALE_KBYTES = 1024;
    public static final int KBYTE_THRESH = 920; //0.9kb

    public static final int SCALE_MBYTES = 1048576;
    public static final int MBYTE_THRESH = 943718; //0.9mb

    public static final int SCALE_GBYTES = 1073741824;
    public static final int GBYTE_THRESH = 966367641; //0.9gb

    public static final int STATUS_QUEUED = 0;
    public static final int STATUS_STARTING = 1;
    public static final int STATUS_RUNNING = 2;
    public static final int STATUS_PAUSED_FOR_DATA = 3;
    public static final int STATUS_PAUSED_FOR_WIFI = 4;
    public static final int STATUS_PAUSED_RETRY = 5;
    public static final int STATUS_PAUSED_USER = 6;
    public static final int STATUS_PAUSED_SYSTEM = 7;
    public static final int STATUS_CANCELLED_USER = 8;
    public static final int STATUS_COMPLETED = 9;
    public static final int STATUS_FAILED = 10;

    public static final int FILTER_ALL = 0;
    public static final int FILTER_PENDING = 1;
    public static final int FILTER_RUNNING = 1 << 1;
    public static final int FILTER_ACTIVE = 1 << 2;
    public static final int FILTER_INACTIVE = 1 << 3;
    public static final int FILTER_PAUSED = 1 << 4;
    public static final int FILTER_COMPLETED = 1 << 5;
    public static final int FILTER_CANCELLED = 1 << 6;
    public static final int FILTER_FAILED = 1 << 7;

    private final RomInfo romInfo;
    private final KernelInfo kernelInfo;

    private transient DownloadTask task;

    private int id;
    private long totalSize = 0;
    private long totalDone = 0;
    private int status;
    private int numRedirects = 0;
    private String redirectedURL = null;
    private int numFailed = 0;
    private int retryAfter;
    private String eTag;
    private boolean pausing = false;
    private boolean continuing = false;
    private DownloadResult result = null;
    private boolean oneTimeNotifShown = false;

    public DlState(RomInfo info) {
        romInfo = info;
        kernelInfo = null;
    }

    public DlState(KernelInfo info) {
        kernelInfo = info;
        romInfo = null;
    }

    public void resetState() {
        totalSize = 0;
        totalDone = 0;
        status = STATUS_QUEUED;
        numRedirects = 0;
        redirectedURL = null;
        numFailed = 0;
        retryAfter = -1;
        eTag = null;
        pausing = false;
        continuing = false;
        result = null;
        setOneTimeNotifShown(false);
    }

    public boolean isRomDownload() {
        return romInfo != null;
    }

    public RomInfo getRomInfo() {
        return romInfo;
    }

    public boolean isKernelDownload() {
        return kernelInfo != null;
    }

    public KernelInfo getKernelInfo() {
        return kernelInfo;
    }

    public String getName() {
        if (isRomDownload()) return romInfo.romName;
        if (isKernelDownload()) return kernelInfo.kernelName;
        return null;
    }

    public String getVersion() {
        if (isRomDownload()) return romInfo.version;
        if (isKernelDownload()) return kernelInfo.version;
        return null;
    }

    public String getChangelog() {
        if (isRomDownload()) return romInfo.changelog;
        if (isKernelDownload()) return kernelInfo.changelog;
        return null;
    }

    public String getMd5() {
        if (isRomDownload()) return romInfo.md5;
        if (isKernelDownload()) return kernelInfo.md5;
        return null;
    }

    public Date getDate() {
        if (isRomDownload()) return romInfo.date;
        if (isKernelDownload()) return kernelInfo.date;
        return null;
    }

    public String getSourceURL() {
        if (redirectedURL != null) return redirectedURL;
        if (isRomDownload()) return romInfo.url;
        if (isKernelDownload()) return kernelInfo.url;
        return null; //should never happen
    }

    public void setRedirectURL(String url) {
        this.redirectedURL = url;
    }

    public File getDestFile() {
        if (isRomDownload()) return new File(Config.ROM_DL_PATH_FILE, romInfo.getDownloadFileName());
        if (isKernelDownload()) return new File(Config.KERNEL_DL_PATH_FILE, kernelInfo.getDownloadFileName());
        return null; //should never happen
    }

    public DownloadTask getTask() {
        return task;
    }

    public void setTask(DownloadTask task) {
        this.task = task;
    }

    public long getTotalSize() {
        return totalSize;
    }

    public void setTotalSize(long totalSize) {
        this.totalSize = totalSize;
    }

    public long getTotalDone() {
        return totalDone;
    }

    public void setTotalDone(long totalDone) {
        this.totalDone = totalDone;
    }

    public void incTotalDone(int inc) {
        this.totalDone += inc;
    }

    public double getPctDone() {
        return ((double) totalDone) / ((double) totalSize);
    }

    public String getProgressStr(Context ctx) {
        long scaledDone = totalDone;
        long scaledTotal = totalSize;

        if (totalSize == 0) {
            int bytesTxtRes = R.string.downloads_size_progress_unknown_b;
            if (totalDone >= DlState.GBYTE_THRESH) {
                scaledDone /= DlState.SCALE_GBYTES;
                bytesTxtRes = R.string.downloads_size_progress_unknown_gb;
            } else if (totalDone >= DlState.MBYTE_THRESH) {
                scaledDone /= DlState.SCALE_MBYTES;
                bytesTxtRes = R.string.downloads_size_progress_unknown_mb;
            } else if (totalDone >= DlState.KBYTE_THRESH) {
                scaledDone /= DlState.SCALE_KBYTES;
                bytesTxtRes = R.string.downloads_size_progress_unknown_kb;
            }
            return ctx.getString(bytesTxtRes, scaledDone);
        } else {
            int bytesTxtRes = R.string.downloads_size_progress_b;
            if (totalSize >= DlState.GBYTE_THRESH) {
                scaledDone /= DlState.SCALE_GBYTES;
                scaledTotal /= DlState.SCALE_GBYTES;
                bytesTxtRes = R.string.downloads_size_progress_gb;
            } else if (totalSize >= DlState.MBYTE_THRESH) {
                scaledDone /= DlState.SCALE_MBYTES;
                scaledTotal /= DlState.SCALE_MBYTES;
                bytesTxtRes = R.string.downloads_size_progress_mb;
            } else if (totalSize >= DlState.KBYTE_THRESH) {
                scaledDone /= DlState.SCALE_KBYTES;
                scaledTotal /= DlState.SCALE_KBYTES;
                bytesTxtRes = R.string.downloads_size_progress_kb;
            }
            return ctx.getString(bytesTxtRes, scaledDone, scaledTotal);
        }
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public int getNumFailed() {
        return numFailed;
    }

    public void setNumFailed(int numFailed) {
        this.numFailed = numFailed;
    }

    public void incNumFailed() {
        this.numFailed++;
    }

    public int getRetryAfter() {
        return retryAfter;
    }

    public void setRetryAfter(int retryAfter) {
        this.retryAfter = retryAfter;
    }

    public int getStatus() {
        return status;
    }

    public void setStatus(int status) {
        this.status = status;
    }

    public DownloadResult getResult() {
        return result;
    }

    public DownloadResult setResult(DownloadResult result) {
        this.result = result;
        return result;
    }

    public String getETag() {
        return eTag;
    }

    public void setETag(String eTag) {
        this.eTag = eTag;
    }

    public boolean isPausing() {
        return pausing;
    }

    public void setPausing(boolean pausing) {
        this.pausing = pausing;
    }

    public boolean isContinuing() {
        return continuing;
    }

    public void setContinuing(boolean continuing) {
        this.continuing = continuing;
    }

    public int getNumRedirects() {
        return numRedirects;
    }

    public void setNumRedirects(int numRedirects) {
        this.numRedirects = numRedirects;
    }

    public void incNumRedirects() {
        this.numRedirects++;
    }

    public boolean wasOneTimeNotifShown() {
        return oneTimeNotifShown;
    }

    public void setOneTimeNotifShown(boolean oneTimeNotifShown) {
        this.oneTimeNotifShown = oneTimeNotifShown;
    }

    public boolean matchesFilter(int filter) {
        if (filter == FILTER_ALL) return true;

        if ((filter & FILTER_ACTIVE) != 0) {
            if (status == STATUS_QUEUED ||
                    status == STATUS_RUNNING ||
                    status == STATUS_STARTING ||
                    status == STATUS_PAUSED_FOR_DATA ||
                    status == STATUS_PAUSED_FOR_WIFI ||
                    status == STATUS_PAUSED_RETRY) return true;
        }

        if ((filter & FILTER_PENDING) != 0) {
            if (status == STATUS_QUEUED ||
                    status == STATUS_STARTING ||
                    status == STATUS_PAUSED_FOR_DATA ||
                    status == STATUS_PAUSED_FOR_WIFI ||
                    status == STATUS_PAUSED_RETRY) return true;
        }

        if ((filter & FILTER_PAUSED) != 0) {
            if (status == STATUS_PAUSED_USER ||
                    status == STATUS_PAUSED_FOR_DATA ||
                    status == STATUS_PAUSED_FOR_WIFI ||
                    status == STATUS_PAUSED_RETRY) return true;
        }

        if ((filter & FILTER_INACTIVE) != 0) {
            if (status == STATUS_CANCELLED_USER ||
                    status == STATUS_PAUSED_USER ||
                    status == STATUS_COMPLETED) return true;
        }

        if ((filter & FILTER_CANCELLED) != 0) {
            if (status == STATUS_CANCELLED_USER) return true;
        }

        if ((filter & FILTER_COMPLETED) != 0) {
            if (status == STATUS_COMPLETED) return true;
        }

        if ((filter & FILTER_FAILED) != 0) {
            if (status == STATUS_FAILED) return true;
        }

        if ((filter & FILTER_RUNNING) != 0) {
            if (status == STATUS_RUNNING ||
                    status == STATUS_STARTING) return true;
        }

        return false;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(isRomDownload() ? 1 : (isKernelDownload() ? 2 : -1));
        if (isRomDownload()) romInfo.writeToParcel(dest, flags);
        if (isKernelDownload()) kernelInfo.writeToParcel(dest, flags);

        dest.writeInt(id);
        dest.writeLong(totalSize);
        dest.writeLong(totalDone);
        dest.writeInt(status);
        dest.writeInt(numRedirects);
        dest.writeString(redirectedURL);
        dest.writeInt(numFailed);
        dest.writeInt(retryAfter);
        dest.writeString(eTag);
        dest.writeByte((byte) (pausing ? 1 : 0));
        dest.writeByte((byte) (continuing ? 1 : 0));
    }

    public static final Creator<DlState> CREATOR = new Creator<DlState>() {
        @Override
        public DlState[] newArray(int size) {
            return new DlState[size];
        }

        @Override
        public DlState createFromParcel(Parcel source) {
            int type = source.readInt();
            if (type == -1) return null;

            DlState state = null;
            switch (type) {
            case 1:
                state = new DlState(RomInfo.CREATOR.createFromParcel(source));
                break;
            case 2:
                state = new DlState(KernelInfo.CREATOR.createFromParcel(source));
                break;
            }
            if (state == null) return null;

            state.setId(source.readInt());
            state.setTotalSize(source.readLong());
            state.setTotalDone(source.readLong());
            state.setStatus(source.readInt());
            state.setNumRedirects(source.readInt());
            state.setRedirectURL(source.readString());
            state.setNumFailed(source.readInt());
            state.setRetryAfter(source.readInt());
            state.setETag(source.readString());
            state.setPausing(source.readByte() == 1);
            state.setContinuing(source.readByte() == 1);

            return state;
        }
    };
}
