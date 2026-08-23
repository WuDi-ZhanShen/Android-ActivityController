package com.tile.activitycontroller;

import android.app.IActivityManager;
import android.os.Build;
import android.os.IDeviceIdleController;

/** Relevant subset of rikka.hidden.compat.Services for minSdk 26. */
final class Services {
    static final SystemServiceBinder<IActivityManager> activityManager;
    static final SystemServiceBinder<IDeviceIdleController> deviceIdleController;

    static {
        activityManager = new SystemServiceBinder<>(
                "activity", IActivityManager.Stub::asInterface);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            deviceIdleController = new SystemServiceBinder<>(
                    "deviceidle", IDeviceIdleController.Stub::asInterface);
        } else {
            deviceIdleController = null;
        }
    }

    private Services() {
    }
}
