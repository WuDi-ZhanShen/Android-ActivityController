package com.tile.activitycontroller;

/** UID helper matching Shizuku's process-level OsUtils behavior. */
final class OsUtils {
    private static final int UID = android.system.Os.getuid();

    private OsUtils() {
    }

    static int getUid() {
        return UID;
    }
}
