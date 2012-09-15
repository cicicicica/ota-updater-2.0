package com.otaupdater;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.otaupdater.utils.KernelInfo;
import com.otaupdater.utils.RomInfo;

public class DownloadReceiver extends BroadcastReceiver {
    public static final String DL_ROM_ACTION = "com.otaupdater.action.DL_ROM_ACTION";
    public static final String DL_KERNEL_ACTION = "com.otaupdater.action.DL_KERNEL_ACTION";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (action == null) return;
        if (action.equals(DL_ROM_ACTION)) {
            RomInfo.clearUpdateNotif(context);
            RomInfo.fromIntent(intent).downloadFileSilent(context);
        } else if (action.equals(DL_KERNEL_ACTION)) {
            KernelInfo.clearUpdateNotif(context);
            KernelInfo.fromIntent(intent).downloadFileSilent(context);
        }
    }
}
