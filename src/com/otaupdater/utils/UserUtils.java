package com.otaupdater.utils;

import android.content.Context;

public class UserUtils {
    public static String userHmac(Context ctx, String str) {
        Config cfg = Config.getInstance(ctx);
        if (!cfg.isUserLoggedIn()) return null;
        return Utils.hmac(str, cfg.getHmacKey());
    }
}
