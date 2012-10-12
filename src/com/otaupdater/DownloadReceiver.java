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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;

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
        } else if (action.equals(DownloadService.SERVICE_ACTION)) {
            Intent i = new Intent(context, DownloadService.class);
            i.setAction(DownloadService.SERVICE_ACTION);
            i.putExtras(intent);
            context.startService(i);
        } else if (action.equals(Intent.ACTION_BOOT_COMPLETED)) {
            Intent i = new Intent(context, DownloadService.class);
            i.setAction(Intent.ACTION_BOOT_COMPLETED);
            i.putExtras(intent);
            context.startService(i);
        } else if (action.equals(ConnectivityManager.CONNECTIVITY_ACTION)) {
            Intent i = new Intent(context, DownloadService.class);
            i.setAction(ConnectivityManager.CONNECTIVITY_ACTION);
            i.putExtras(intent);
            context.startService(i);
        }
    }
}
