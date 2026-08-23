package com.tile.activitycontroller;

import android.os.Build;

/** Relevant minSdk-26+ subset of Shizuku's DeviceIdleControllerApis. */
final class DeviceIdleControllerApis {

    private DeviceIdleControllerApis() {
    }

    static void addPowerSaveTempWhitelistApp(String name, long duration, int userId,
                                             int reasonCode, String reason) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Services.deviceIdleController.get().addPowerSaveTempWhitelistApp(
                    name, duration, userId, reasonCode, reason);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Services.deviceIdleController.get().addPowerSaveTempWhitelistApp(
                    name, duration, userId, reason);
        }
    }
}
