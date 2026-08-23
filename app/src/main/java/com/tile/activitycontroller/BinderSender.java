package com.tile.activitycontroller;

import android.app.IActivityManager;
import android.content.IContentProvider;
import android.content.pm.IPackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Binder delivery path kept intentionally in lock-step with Shizuku's BinderSender and
 * ShizukuService.sendBinderToUserApp. Only Shizuku-specific permission/package selection is
 * replaced with ActivityController's single-package check.
 */
final class BinderSender {

    private static final String PACKAGE_NAME = "com.tile.activitycontroller";
    private static final String AUTHORITY = PACKAGE_NAME + ActivityControllerProvider.AUTHORITY_SUFFIX;

    // AOSP / ActivityManagerHidden values used by Shizuku.
    private static final int UID_OBSERVER_GONE = 1 << 1;
    private static final int UID_OBSERVER_IDLE = 1 << 2;
    private static final int UID_OBSERVER_ACTIVE = 1 << 3;
    private static final int UID_OBSERVER_CACHED = 1 << 4;
    private static final int PROCESS_STATE_UNKNOWN = -1;

    private static IActivityManager activityManager;
    private static IPackageManager packageManager;
    private static IBinder serviceBinder;

    private static ProcessObserver processObserver;
    private static UidObserver uidObserver;

    private BinderSender() {
    }

    static synchronized void register(IActivityManager am, IBinder binder, int initialUserId) {
        activityManager = am;
        serviceBinder = binder;
        packageManager = IPackageManager.Stub.asInterface(ServiceManager.getService("package"));

        processObserver = new ProcessObserver();
        try {
            ActivityManagerApis.registerProcessObserver(processObserver);
        } catch (Throwable tr) {
            System.err.println("registerProcessObserver: " + tr);
        }

        // Shizuku registers this from API 26. Our minSdk is 26, so no lower-version branch exists.
        int flags = UID_OBSERVER_GONE | UID_OBSERVER_IDLE | UID_OBSERVER_ACTIVE;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            flags |= UID_OBSERVER_CACHED;
        }
        uidObserver = new UidObserver();
        try {
            ActivityManagerApis.registerUidObserver(uidObserver, flags, PROCESS_STATE_UNKNOWN, null);
        } catch (Throwable tr) {
            System.err.println("registerUidObserver: " + tr);
        }

        // The manager app is already running when it starts app_process, so mirror Shizuku's
        // startup sendBinderToClient/sendBinderToManager behavior with one immediate delivery.
        sendBinderToUserApp(serviceBinder, initialUserId);
    }

    static synchronized void unregister() {
        if (activityManager != null) {
            if (processObserver != null) {
                try {
                    ActivityManagerApis.unregisterProcessObserver(processObserver);
                } catch (Throwable ignored) {
                }
            }
            if (uidObserver != null) {
                try {
                    ActivityManagerApis.unregisterUidObserver(uidObserver);
                } catch (Throwable ignored) {
                }
            }
        }
        processObserver = null;
        uidObserver = null;
        activityManager = null;
        packageManager = null;
        serviceBinder = null;
        ProcessObserver.clear();
        UidObserver.clear();
    }

    private static final class ProcessObserver extends ProcessObserverAdapter {

        private static final List<Integer> PID_LIST = new ArrayList<>();

        @Override
        public void onForegroundActivitiesChanged(int pid, int uid, boolean foregroundActivities)
                throws RemoteException {
            synchronized (PID_LIST) {
                if (PID_LIST.contains(pid) || !foregroundActivities) {
                    return;
                }
                PID_LIST.add(pid);
            }
            sendBinder(uid, pid);
        }

        @Override
        public void onProcessDied(int pid, int uid) {
            synchronized (PID_LIST) {
                int index = PID_LIST.indexOf(pid);
                if (index != -1) {
                    PID_LIST.remove(index);
                }
            }
        }

        @Override
        public void onProcessStateChanged(int pid, int uid, int procState) throws RemoteException {
            synchronized (PID_LIST) {
                if (PID_LIST.contains(pid)) {
                    return;
                }
                PID_LIST.add(pid);
            }
            sendBinder(uid, pid);
        }

        static void clear() {
            synchronized (PID_LIST) {
                PID_LIST.clear();
            }
        }
    }

    private static final class UidObserver extends UidObserverAdapter {

        private static final List<Integer> UID_LIST = new ArrayList<>();

        @Override
        public void onUidActive(int uid) throws RemoteException {
            uidStarts(uid);
        }

        @Override
        public void onUidCachedChanged(int uid, boolean cached) throws RemoteException {
            if (!cached) {
                uidStarts(uid);
            }
        }

        @Override
        public void onUidIdle(int uid, boolean disabled) throws RemoteException {
            uidStarts(uid);
        }

        @Override
        public void onUidGone(int uid, boolean disabled) {
            synchronized (UID_LIST) {
                int index = UID_LIST.indexOf(uid);
                if (index != -1) {
                    UID_LIST.remove(index);
                }
            }
        }

        private void uidStarts(int uid) throws RemoteException {
            synchronized (UID_LIST) {
                if (UID_LIST.contains(uid)) {
                    return;
                }
                UID_LIST.add(uid);
            }
            sendBinder(uid, -1);
        }

        static void clear() {
            synchronized (UID_LIST) {
                UID_LIST.clear();
            }
        }
    }

    private static void sendBinder(int uid, int pid) {
        IPackageManager pm = packageManager;
        if (pm == null) {
            return;
        }
        try {
            String[] packages = pm.getPackagesForUid(uid);
            if (packages == null || !Arrays.asList(packages).contains(PACKAGE_NAME)) {
                return;
            }
        } catch (Throwable tr) {
            System.err.println("getPackagesForUid: " + tr);
            return;
        }

        int userId = uid / 100000;
        sendBinderToUserApp(serviceBinder, userId);
    }

    private static void sendBinderToUserApp(IBinder binder, int userId) {
        sendBinderToUserApp(binder, userId, true);
    }

    /** Port of ShizukuService.sendBinderToUserApp(), adapted only for our authority/key. */
    private static void sendBinderToUserApp(IBinder binder, int userId, boolean retry) {
        if (binder == null || !binder.pingBinder()) {
            return;
        }

        try {
            DeviceIdleControllerApis.addPowerSaveTempWhitelistApp(
                    PACKAGE_NAME, 30 * 1000, userId,
                    316 /* PowerExemptionManager#REASON_SHELL */, "shell");
        } catch (Throwable tr) {
            System.err.printf("Failed to add %d:%s to power save temp whitelist: %s%n",
                    userId, PACKAGE_NAME, tr);
        }

        String name = AUTHORITY;
        IContentProvider provider = null;

        /*
         * Same null-token strategy as Shizuku. Crossing Binder creates a BinderProxy, whose
         * identity cannot be used reliably as the external-provider HashMap key for removal.
         */
        IBinder token = null;

        try {
            provider = ActivityManagerApis.getContentProviderExternal(name, userId, token, name);
            if (provider == null) {
                System.err.printf("provider is null %s %d%n", name, userId);
                return;
            }
            if (!provider.asBinder().pingBinder()) {
                System.err.printf("provider is dead %s %d%n", name, userId);
                if (retry) {
                    ActivityManagerApis.forceStopPackageNoThrow(PACKAGE_NAME, userId);
                    System.err.printf("kill %s in user %d and try again%n", PACKAGE_NAME, userId);
                    Thread.sleep(1000);
                    sendBinderToUserApp(binder, userId, false);
                }
                return;
            }

            if (!retry) {
                System.err.println("retry works");
            }

            Bundle extra = new Bundle();
            extra.putParcelable(ActivityControllerProvider.EXTRA_BINDER,
                    new BinderContainer(binder));

            Bundle reply = IContentProviderUtils.callCompat(
                    provider, null, name, ActivityControllerProvider.METHOD_SEND_BINDER,
                    null, extra);
            if (reply != null) {
                System.out.printf("send binder to user app %s in user %d%n",
                        PACKAGE_NAME, userId);
            } else {
                System.err.printf("failed to send binder to user app %s in user %d%n",
                        PACKAGE_NAME, userId);
            }
        } catch (Throwable tr) {
            System.err.printf("failed send binder to user app %s in user %d: %s%n",
                    PACKAGE_NAME, userId, tr);
            tr.printStackTrace();
        } finally {
            if (provider != null) {
                try {
                    ActivityManagerApis.removeContentProviderExternal(name, token);
                } catch (Throwable tr) {
                    System.err.println("removeContentProviderExternal: " + tr);
                }
            }
        }
    }
}
