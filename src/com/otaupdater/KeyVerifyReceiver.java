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
import android.util.Log;
import android.widget.Toast;

import com.otaupdater.utils.Config;

public class KeyVerifyReceiver extends BroadcastReceiver {
    private static final long MARKET_REFUND_TIME = 1800000; //30 minutes in ms - give enough time to refund+whatever

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.v(Config.LOG_TAG + "KeyVerify", "got pro key response");

        if (intent.hasExtra("errorCode")) {
            int error = intent.getIntExtra("errorCode", 0);
            int errorResId;
            switch (error) {
            case 1:
                errorResId = R.string.prokey_verify_error_nodata;
                break;
            case 2:
                errorResId = R.string.prokey_verify_error_nomarket;
                break;
            default:
                errorResId = R.string.prokey_verify_error_other;
            }
            Log.w(Config.LOG_TAG + "KeyVerify", "key verification returned error " + error);
            Toast.makeText(context, errorResId, Toast.LENGTH_LONG).show();
            return;
        }

        if (!intent.hasExtra("licensed") || !intent.hasExtra("definitive")) {
            Log.e(Config.LOG_TAG + "KeyVerify", "invalid key verification response!");
            return;
        }

        boolean licensed = intent.getBooleanExtra("licensed", false);
        boolean definitive = intent.getBooleanExtra("definitive", true);

        final Config cfg = Config.getInstance(context.getApplicationContext());

        if (definitive) {
            if (licensed) {
                if (cfg.getKeyState() == Config.KEY_STATE_VERIF1_IP) {
                    cfg.setKeyState(Config.KEY_STATE_VERIF1_GOOD);
                    cfg.setNextKeyVerif(System.currentTimeMillis() + MARKET_REFUND_TIME);
                    Toast.makeText(context, R.string.prokey_verified, Toast.LENGTH_LONG).show();
                } else if (cfg.getKeyState() == Config.KEY_STATE_VERIF2_IP) {
                    cfg.setKeyState(Config.KEY_STATE_VERIF2_GOOD);
                }
            } else {
                cfg.setKeyState(Config.KEY_STATE_INVALID_VERIF);
                Toast.makeText(context, R.string.prokey_invalid, Toast.LENGTH_LONG).show();
            }
        } else {
            if (cfg.getKeyState() == Config.KEY_STATE_VERIF1_IP) {
                cfg.setKeyState(Config.KEY_STATE_VERIF1_FAIL);
                cfg.setNextKeyVerif(System.currentTimeMillis() + intent.getLongExtra("retry_after", 0));
            } else if (cfg.getKeyState() == Config.KEY_STATE_VERIF2_IP) {
                cfg.setKeyState(Config.KEY_STATE_VERIF2_FAIL);
                cfg.setNextKeyVerif(System.currentTimeMillis() + intent.getLongExtra("retry_after", 0));
            }
            Toast.makeText(context, R.string.prokey_noverify, Toast.LENGTH_LONG).show();
        }
    }
}
