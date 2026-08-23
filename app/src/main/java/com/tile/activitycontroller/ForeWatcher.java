package com.tile.activitycontroller;

import android.app.IActivityController;
import android.app.IActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.ServiceManager;

import java.util.HashMap;
import java.util.Map;

/** Privileged service process started with app_process. */
public class ForeWatcher {
    static public IActivityManager activityManager = null;
    static public final Map<String, Boolean> targetPkgNamesMap = new HashMap<>();

    private static volatile boolean cleanedUp;

    public static void main(String[] argv) {
        if (Looper.getMainLooper() == null) {
            Looper.prepareMainLooper();
        }

        int uid = android.os.Process.myUid();
        if (uid != 0 && uid != 2000) {
            System.err.printf("Insufficient permission! Need adb (uid 2000) or root (uid 0), current uid=%d%n", uid);
            System.exit(255);
            return;
        }

        int initialUserId = 0;
        if (argv != null && argv.length > 0) {
            try {
                initialUserId = Integer.parseInt(argv[0]);
            } catch (NumberFormatException ignored) {
            }
        }

        System.out.println("Start ForeWatcher Service. Binder transport: Shizuku-style ContentProvider.call().");

        // minSdk is 26, so the pre-26 ActivityManagerNative branch used by older code is omitted.
        activityManager = IActivityManager.Stub.asInterface(
                ServiceManager.getService(Context.ACTIVITY_SERVICE));
        if (activityManager == null) {
            System.err.println("Can't get IActivityManager!");
            System.exit(255);
            return;
        }

        final IBinder userService = createUserService(activityManager);
        installActivityController(activityManager);

        // This is the Shizuku-style Binder hand-off. It also registers process/UID observers
        // so the Binder is re-sent after the normal app process is recreated.
        BinderSender.register(activityManager, userService, initialUserId);

        Runtime.getRuntime().addShutdownHook(new Thread(ForeWatcher::cleanup, "ForeWatcher-shutdown"));
        Looper.loop();
        cleanup();
        System.out.println("Stop ForeWatcher Service.");
    }

    private static IBinder createUserService(IActivityManager am) {
        return new IUserService.Stub() {
            @Override
            public void updateTargetPkgNamesMap(String map) {
                MainActivity.initHashMapByDisallowedList(targetPkgNamesMap, map);
            }

            @Override
            public void launchUnexportedActivity(Intent intent) throws RemoteException {
                am.startActivity(
                        null,
                        android.system.Os.getuid() == 2000 ? "com.android.shell" : null,
                        intent,
                        null,
                        null,
                        null,
                        0,
                        intent.getFlags(),
                        null,
                        null
                );
            }

            @Override
            public void exit() {
                cleanup();
                System.exit(0);
            }
        };
    }

    private static void installActivityController(IActivityManager am) {
        try {
            am.setActivityController(new IActivityController.Stub() {
                @Override
                public boolean activityStarting(Intent intent, String pkg) {
                    ComponentName componentName = intent.getComponent();
                    if (componentName == null) return true;
                    return !targetPkgNamesMap.containsKey(componentName.flattenToShortString());
                }

                @Override
                public boolean activityResuming(String pkg) {
                    return true;
                }

                @Override
                public boolean appCrashed(String processName, int pid, String shortMsg, String longMsg,
                                          long timeMillis, String stackTrace) {
                    return true;
                }

                @Override
                public int appEarlyNotResponding(String processName, int pid, String annotation) {
                    return 0;
                }

                @Override
                public int appNotResponding(String processName, int pid, String processStats) {
                    return 0;
                }

                @Override
                public int systemNotResponding(String msg) {
                    return 0;
                }
            }, false);
        } catch (Throwable tr) {
            System.err.println("Failed to install ActivityController: " + tr);
            tr.printStackTrace();
            System.exit(255);
        }
    }

    private static synchronized void cleanup() {
        if (cleanedUp) return;
        cleanedUp = true;

        BinderSender.unregister();
        IActivityManager am = activityManager;
        activityManager = null;
        if (am != null) {
            try {
                am.setActivityController(null, false);
            } catch (Throwable tr) {
                System.err.println("Failed to remove ActivityController: " + tr);
            }
        }
    }
}
