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

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ListView;
import android.widget.SimpleAdapter;
import android.widget.Toast;

import com.actionbarsherlock.app.SherlockListFragment;
import com.otaupdater.utils.Config;
import com.otaupdater.utils.DialogCallback;
import com.otaupdater.utils.KernelInfo;
import com.otaupdater.utils.KernelInfo.KernelInfoListener;
import com.otaupdater.utils.Utils;

public class KernelTab extends SherlockListFragment {

    private final ArrayList<HashMap<String, Object>> DATA = new ArrayList<HashMap<String, Object>>();
    private /*final*/ int AVAIL_UPDATES_IDX = -1;
    private /*final*/ SimpleAdapter adapter;

    private Config cfg;
    private boolean fetching = false;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        cfg = Config.getInstance(getActivity().getApplicationContext());

        HashMap<String, Object> item;

        item = new HashMap<String, Object>();
        item.put("title", getString(R.string.main_device));
        item.put("summary", android.os.Build.DEVICE.toLowerCase());
        item.put("icon", R.drawable.device);
        DATA.add(item);

        item = new HashMap<String, Object>();
        item.put("title", getString(R.string.main_kernel));
        item.put("summary", Utils.getKernelVersion());
        item.put("icon", R.drawable.hammer);
        DATA.add(item);

        if (Utils.isKernelOtaEnabled()) {
            String kernelVersion = Utils.getKernelOtaVersion();
            if (kernelVersion == null) kernelVersion = getString(R.string.kernel_version_unknown);
            Date kernelDate = Utils.getKernelOtaDate();
            if (kernelDate != null) {
                kernelVersion += " (" + DateFormat.getDateTimeInstance().format(kernelDate) + ")";
            }

            item = new HashMap<String, Object>();
            item.put("title", getString(R.string.kernel_version));
            item.put("summary", kernelVersion);
            item.put("icon", R.drawable.version);
            DATA.add(item);

            item = new HashMap<String, Object>();
            item.put("title", getString(R.string.main_otaid));
            item.put("summary", Utils.getKernelOtaID());
            item.put("icon", R.drawable.key);
            DATA.add(item);

            item = new HashMap<String, Object>();
            item.put("title", getString(R.string.updates_avail_title));
            if (cfg.hasStoredKernelUpdate()) {
                KernelInfo info = cfg.getStoredKernelUpdate();
                if (Utils.isKernelUpdate(info)) {
                    item.put("summary", getString(R.string.updates_new, info.kernelName, info.version));
                } else {
                    item.put("summary", getString(R.string.updates_none));
                    cfg.clearStoredKernelUpdate();
                }
            } else {
                item.put("summary", getString(R.string.updates_none));
            }
            item.put("icon", R.drawable.cloud);
            AVAIL_UPDATES_IDX = DATA.size();
            DATA.add(item);
        } else {
            if (cfg.hasStoredKernelUpdate()) cfg.clearStoredKernelUpdate();

            item = new HashMap<String, Object>();
            item.put("title", getString(R.string.kernel_unsupported_title));
            item.put("summary", getString(R.string.kernel_unsupported_summary));
            item.put("icon", R.drawable.cloud);
            DATA.add(item);
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.list, container, false);
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        adapter = new SimpleAdapter(getActivity(),
                DATA,
                R.layout.two_line_icon_list_item,
                new String[] { "title", "summary", "icon" },
                new int[] { android.R.id.text1, android.R.id.text2, android.R.id.icon });
        setListAdapter(adapter);
    }

    @Override
    public void onListItemClick(ListView l, View v, int position, long id) {
        if (position == AVAIL_UPDATES_IDX) {
            if (cfg.hasStoredKernelUpdate()) {
                KernelInfo info = cfg.getStoredKernelUpdate();
                if (Utils.isKernelUpdate(info)) {
                    Activity act = getActivity();
                    info.showUpdateDialog(act, act instanceof DialogCallback ? (DialogCallback) act : null);
                } else {
                    cfg.clearStoredKernelUpdate();
                    DATA.get(AVAIL_UPDATES_IDX).put("summary", getString(R.string.updates_none));
                    adapter.notifyDataSetChanged();

                    if (!fetching) {
                        checkForKernelUpdates();
                    }
                }
            } else if (!fetching) {
                checkForKernelUpdates();
            }
        }
    }

    private void checkForKernelUpdates() {
        if (fetching) return;
        final Config cfg = Config.getInstance(getActivity().getApplicationContext());
        KernelInfo.fetchInfo(getActivity(), new KernelInfoListener() {
            @Override
            public void onStartLoading() {
                fetching = true;
                DATA.get(AVAIL_UPDATES_IDX).put("summary", getString(R.string.updates_checking));
                adapter.notifyDataSetChanged();
            }
            @Override
            public void onLoaded(KernelInfo info) {
                fetching = false;
                if (info == null) {
                    DATA.get(AVAIL_UPDATES_IDX).put("summary", getString(R.string.updates_error, "Unknown error"));
                    Toast.makeText(getActivity(), R.string.toast_fetch_error, Toast.LENGTH_SHORT).show();
                } else if (Utils.isKernelUpdate(info)) {
                    cfg.storeKernelUpdate(info);
                    DATA.get(AVAIL_UPDATES_IDX).put("summary", getString(R.string.updates_new, info.kernelName, info.version));
                    if (cfg.getShowNotif()) {
                        info.showUpdateNotif(getActivity());
                    } else {
                        Log.v(Config.LOG_TAG + "KernelTab", "found kernel update, notif not shown");
                    }
                } else {
                    cfg.clearStoredKernelUpdate();
                    KernelInfo.clearUpdateNotif(getActivity());
                    DATA.get(AVAIL_UPDATES_IDX).put("summary", getString(R.string.updates_none));
                    Toast.makeText(getActivity(), R.string.toast_no_updates, Toast.LENGTH_SHORT).show();
                }
                adapter.notifyDataSetChanged();
            }
            @Override
            public void onError(String error) {
                fetching = false;
                DATA.get(AVAIL_UPDATES_IDX).put("summary", getString(R.string.updates_error, error));
                adapter.notifyDataSetChanged();
                Toast.makeText(getActivity(), error, Toast.LENGTH_SHORT).show();
            }
        });
    }
}
