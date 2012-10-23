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

import java.util.ArrayList;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.IBinder;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentPagerAdapter;
import android.support.v4.app.FragmentTransaction;
import android.support.v4.view.ViewPager;
import android.util.Log;

import com.actionbarsherlock.app.ActionBar;
import com.actionbarsherlock.app.ActionBar.Tab;
import com.actionbarsherlock.app.SherlockFragmentActivity;
import com.actionbarsherlock.view.Menu;
import com.actionbarsherlock.view.MenuInflater;
import com.actionbarsherlock.view.MenuItem;
import com.google.android.gcm.GCMRegistrar;
import com.otaupdater.DownloadService.BindUtil;
import com.otaupdater.DownloadService.BindUtil.Token;
import com.otaupdater.utils.Config;
import com.otaupdater.utils.DownloadDialogCallback;
import com.otaupdater.utils.KernelInfo;
import com.otaupdater.utils.RomInfo;
import com.otaupdater.utils.Utils;

public class OTAUpdaterActivity extends SherlockFragmentActivity implements DownloadDialogCallback {
    public static final String ROM_NOTIF_ACTION = "com.otaupdater.action.ROM_NOTIF_ACTION";
    public static final String KERNEL_NOTIF_ACTION = "com.otaupdater.action.KERNEL_NOTIF_ACTION";

    private final ArrayList<Dialog> dlgs = new ArrayList<Dialog>();

    private ViewPager mViewPager;
    private TabsAdapter mTabsAdapter;
    private Config cfg;

    private Integer downloadDlgDlID = null;
    private Token serviceToken = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final Context context = getApplicationContext();
        cfg = Config.getInstance(context);

        if (Utils.needProKeyVerify(context)) {
            Utils.verifyProKey(context);
        }

        if (!Utils.isRomOtaEnabled() && !Utils.isKernelOtaEnabled()) {
            if (!cfg.getIgnoredUnsupportedWarn()) {
                AlertDialog.Builder alert = new AlertDialog.Builder(this);
                alert.setTitle(R.string.alert_unsupported_title);
                alert.setMessage(R.string.alert_unsupported_message);
                alert.setCancelable(false);
                alert.setNegativeButton(R.string.alert_exit, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                        finish();
                    }
                });
                alert.setPositiveButton(R.string.alert_ignore, new DialogInterface.OnClickListener() {
    				@Override
    				public void onClick(DialogInterface dialog, int which) {
    				    cfg.setIgnoredUnsupportedWarn(true);
    					dialog.dismiss();
    				}
    			});
                alert.create().show();
            }

            if (Utils.marketAvailable(this)) {
                GCMRegistrar.checkDevice(context);
                GCMRegistrar.checkManifest(context);
                final String regId = GCMRegistrar.getRegistrationId(context);
                if (regId.length() != 0) {
                    GCMRegistrar.unregister(context);
                }
            }

        } else {
            if (Utils.marketAvailable(this)) {
                GCMRegistrar.checkDevice(context);
                GCMRegistrar.checkManifest(context);
                final String regId = GCMRegistrar.getRegistrationId(context);
                if (regId.length() != 0) {
                    if (cfg.upToDate()) {
                        Log.v(Config.LOG_TAG + "GCMRegister", "Already registered");
                    } else {
                        Log.v(Config.LOG_TAG + "GCMRegister", "Already registered, out-of-date");
                        cfg.setValuesToCurrent();
                        new AsyncTask<Void, Void, Void>() {
                            @Override
                            protected Void doInBackground(Void... params) {
                                Utils.updateGCMRegistration(context, regId);
                                return null;
                            }
                        }.execute();
                    }
                } else {
                    GCMRegistrar.register(context, Config.GCM_SENDER_ID);
                    Log.v(Config.LOG_TAG + "GCMRegister", "GCM registered");
                }
            } else {
                UpdateCheckReceiver.setAlarm(context);
            }
        }

        setContentView(R.layout.main);

        mViewPager = (ViewPager) findViewById(R.id.pager);

        final ActionBar bar = getSupportActionBar();
        bar.setNavigationMode(ActionBar.NAVIGATION_MODE_TABS);
        bar.setDisplayOptions(ActionBar.DISPLAY_SHOW_TITLE, ActionBar.DISPLAY_SHOW_TITLE);
        bar.setTitle(R.string.app_name);

        mTabsAdapter = new TabsAdapter(this, mViewPager);
        mTabsAdapter.addTab(bar.newTab().setText(R.string.main_about), AboutTab.class, null);
        mTabsAdapter.addTab(bar.newTab().setText(R.string.main_rom), ROMTab.class, null);
        mTabsAdapter.addTab(bar.newTab().setText(R.string.main_kernel), KernelTab.class, null);
        mTabsAdapter.addTab(bar.newTab().setText(R.string.main_walls), WallsTab.class, null);

        String action = getIntent().getAction();
        if (ROM_NOTIF_ACTION.equals(action)) {
            RomInfo.clearUpdateNotif(context);
            bar.setSelectedNavigationItem(1);
            RomInfo.fromIntent(getIntent()).showUpdateDialog(this, this);
        } else if (KERNEL_NOTIF_ACTION.equals(action)) {
            KernelInfo.clearUpdateNotif(context);
            bar.setSelectedNavigationItem(2);
            KernelInfo.fromIntent(getIntent()).showUpdateDialog(this, this);
        } else {
            if (savedInstanceState != null) {
                bar.setSelectedNavigationItem(savedInstanceState.getInt("tab", 0));
                if (savedInstanceState.containsKey("dlID")) {
                    final int dlID = savedInstanceState.getInt("dlID", 0);
                    serviceToken = BindUtil.bindToService(this, new ServiceConnection() {
                        @Override
                        public void onServiceConnected(ComponentName name, IBinder stub) {
                            final IDownloadService service = IDownloadService.Stub.asInterface(stub);
                            DownloadsActivity.showDownloadingDialog(OTAUpdaterActivity.this, service, serviceToken, dlID, OTAUpdaterActivity.this);
                        }
                        @Override
                        public void onServiceDisconnected(ComponentName name) {
                            serviceToken = null;
                        }
                    });
                }
            } else {
                if (cfg.hasStoredRomUpdate()) cfg.getStoredRomUpdate().showUpdateNotif(this);
                if (cfg.hasStoredKernelUpdate()) cfg.getStoredKernelUpdate().showUpdateNotif(this);
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        for (Dialog dlg : dlgs) {
            if (dlg.isShowing()) dlg.dismiss();
        }
        dlgs.clear();
    }

    @Override
    protected void onDestroy() {
        if (serviceToken != null) BindUtil.unbindFromService(serviceToken);
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt("tab", getSupportActionBar().getSelectedNavigationIndex());
        if (downloadDlgDlID != null) outState.putInt("dlID", downloadDlgDlID);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getSupportMenuInflater();
        inflater.inflate(R.menu.actionbar_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        Intent i;
        switch (item.getItemId()) {
        case R.id.settings:
            i = new Intent(this, SettingsActivity.class);
            startActivity(i);
            return true;
        case R.id.downloads:
            i = new Intent(this, DownloadsActivity.class);
            startActivity(i);
            return true;
        case R.id.accounts:
            i = new Intent(this, AccountsScreen.class);
            startActivity(i);
            return true;
        default:
            return super.onOptionsItemSelected(item);
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
    public void onDownloadDialogShown(int dlID, Dialog dlg, Token token) {
        serviceToken = token;
        downloadDlgDlID = dlID;
    }

    @Override
    public void onDownloadDialogClosed(int dlID, Dialog dlg, Token token) {
        downloadDlgDlID = null;
        BindUtil.unbindFromService(token);
        serviceToken = null;
    }

    public static class TabsAdapter extends FragmentPagerAdapter
            implements ActionBar.TabListener, ViewPager.OnPageChangeListener {

        private Context ctx;
        private final ActionBar mActionBar;
        private final ViewPager mViewPager;
        private final ArrayList<TabInfo> mTabs = new ArrayList<TabInfo>();

        static final class TabInfo {
            private final Class<?> clss;
            private final Bundle args;

            TabInfo(Class<?> _class, Bundle _args) {
                clss = _class;
                args = _args;
            }
        }

        public TabsAdapter(SherlockFragmentActivity activity, ViewPager pager) {
            super(activity.getSupportFragmentManager());
            ctx = activity;
            mActionBar = activity.getSupportActionBar();
            mViewPager = pager;
            mViewPager.setAdapter(this);
            mViewPager.setOnPageChangeListener(this);
        }

        public void addTab(ActionBar.Tab tab, Class<?> clss, Bundle args) {
            TabInfo info = new TabInfo(clss, args);
            tab.setTag(info);
            tab.setTabListener(this);
            mTabs.add(info);
            mActionBar.addTab(tab);
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return mTabs.size();
        }

        @Override
        public Fragment getItem(int position) {
            TabInfo info = mTabs.get(position);
            return Fragment.instantiate(ctx, info.clss.getName(), info.args);
        }

        @Override
        public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) {
        }

        @Override
        public void onPageSelected(int position) {
            mActionBar.setSelectedNavigationItem(position);
        }

        @Override
        public void onPageScrollStateChanged(int state) {
        }

        @Override
        public void onTabSelected(Tab tab, FragmentTransaction ft) {
            Object tag = tab.getTag();
            for (int i=0; i<mTabs.size(); i++) {
                if (mTabs.get(i) == tag) {
                    mViewPager.setCurrentItem(i);
                }
            }
        }

        @Override
        public void onTabUnselected(Tab tab, FragmentTransaction ft) {
        }

        @Override
        public void onTabReselected(Tab tab, FragmentTransaction ft) {
        }
    }
}
