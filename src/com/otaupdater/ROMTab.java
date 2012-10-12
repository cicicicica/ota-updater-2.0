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
import com.otaupdater.utils.DownloadDialogCallback;
import com.otaupdater.utils.RomInfo;
import com.otaupdater.utils.RomInfo.RomInfoListener;
import com.otaupdater.utils.Utils;

public class ROMTab extends SherlockListFragment {

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
        item.put("title", getString(R.string.main_rom));
        item.put("summary", android.os.Build.DISPLAY);
        item.put("icon", R.drawable.hammer);
        DATA.add(item);

        if (Utils.isRomOtaEnabled()) {
            String romVersion = Utils.getRomOtaVersion();
            if (romVersion == null) romVersion = Utils.getRomVersion();
            Date romDate = Utils.getRomOtaDate();
            if (romDate != null) {
                romVersion += " (" + DateFormat.getDateTimeInstance().format(romDate) + ")";
            }

            item = new HashMap<String, Object>();
            item.put("title", getString(R.string.rom_version));
            item.put("summary", romVersion);
            item.put("icon", R.drawable.version);
            DATA.add(item);

            item = new HashMap<String, Object>();
            item.put("title", getString(R.string.main_otaid));
            item.put("summary", Utils.getRomOtaID());
            item.put("icon", R.drawable.key);
            DATA.add(item);

            item = new HashMap<String, Object>();
            item.put("title", getString(R.string.updates_avail_title));
            if (cfg.hasStoredRomUpdate()) {
                RomInfo info = cfg.getStoredRomUpdate();
                if (Utils.isRomUpdate(info)) {
                    item.put("summary", getString(R.string.updates_new, info.romName, info.version));
                } else {
                    item.put("summary", getString(R.string.updates_none));
                    cfg.clearStoredRomUpdate();
                }
            } else {
                item.put("summary", getString(R.string.updates_none));
            }
            item.put("icon", R.drawable.cloud);
            AVAIL_UPDATES_IDX = DATA.size();
            DATA.add(item);
        } else {
            if (cfg.hasStoredRomUpdate()) cfg.clearStoredRomUpdate();

            item = new HashMap<String, Object>();
            item.put("title", getString(R.string.rom_version));
            item.put("summary", Utils.getRomVersion());
            item.put("icon", R.drawable.version);
            DATA.add(item);

            item = new HashMap<String, Object>();
            item.put("title", getString(R.string.rom_unsupported_title));
            item.put("summary", getString(R.string.rom_unsupported_summary));
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
            if (cfg.hasStoredRomUpdate()) {
                RomInfo info = cfg.getStoredRomUpdate();
                if (Utils.isRomUpdate(info)) {
                    Activity act = getActivity();
                    info.showUpdateDialog(act, act instanceof DownloadDialogCallback ? (DownloadDialogCallback) act : null);
                } else {
                    cfg.clearStoredRomUpdate();
                    DATA.get(AVAIL_UPDATES_IDX).put("summary", getString(R.string.updates_none));
                    adapter.notifyDataSetChanged();

                    if (!fetching) {
                        checkForRomUpdates();
                    }
                }
            } else if (!fetching) {
                checkForRomUpdates();
            }
        }
    }

    private void checkForRomUpdates() {
        if (fetching) return;
        if (!Utils.isRomOtaEnabled()) return;

        RomInfo.fetchInfo(getActivity(), new RomInfoListener() {
            @Override
            public void onStartLoading() {
                fetching = true;
                DATA.get(AVAIL_UPDATES_IDX).put("summary", getString(R.string.updates_checking));
                adapter.notifyDataSetChanged();
            }
            @Override
            public void onLoaded(RomInfo info) {
                fetching = false;
                if (info == null) {
                    DATA.get(AVAIL_UPDATES_IDX).put("summary", getString(R.string.updates_error, "Unknown error"));
                    Toast.makeText(getActivity(), R.string.toast_fetch_error, Toast.LENGTH_SHORT).show();
                } else if (Utils.isRomUpdate(info)) {
                    cfg.storeRomUpdate(info);
                    DATA.get(AVAIL_UPDATES_IDX).put("summary", getString(R.string.updates_new, info.romName, info.version));
                    if (cfg.getShowNotif()) {
                        info.showUpdateNotif(getActivity());
                    } else {
                        Log.v(Config.LOG_TAG + "RomTab", "found rom update, notif not shown");
                    }
                } else {
                    cfg.clearStoredRomUpdate();
                    RomInfo.clearUpdateNotif(getActivity());
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
