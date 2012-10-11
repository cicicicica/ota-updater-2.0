package com.otaupdater.utils;

import android.app.Dialog;

public interface DialogCallback {
    void onDialogShown(Dialog dlg);
    void onDialogClosed(Dialog dlg);
}
