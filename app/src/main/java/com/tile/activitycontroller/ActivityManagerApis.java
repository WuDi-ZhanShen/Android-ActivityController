package com.tile.activitycontroller;

import android.app.ContentProviderHolder;
import android.app.IActivityManager;
import android.app.IProcessObserver;
import android.app.IUidObserver;
import android.content.IContentProvider;
import android.os.Build;
import android.os.IBinder;
import android.os.RemoteException;

/**
 * Relevant minSdk-26+ subset of rikka.hidden.compat.ActivityManagerApis.
 * The pre-26 Refine branch is intentionally omitted because this app cannot run there.
 */
final class ActivityManagerApis {

    private ActivityManagerApis() {
    }

    static void registerProcessObserver(IProcessObserver processObserver) throws RemoteException {
        Services.activityManager.get().registerProcessObserver(processObserver);
    }

    static void unregisterProcessObserver(IProcessObserver observer) throws RemoteException {
        Services.activityManager.get().unregisterProcessObserver(observer);
    }

    static void registerUidObserver(IUidObserver observer, int which, int cutpoint,
                                    String callingPackage) throws RemoteException {
        Services.activityManager.get().registerUidObserver(observer, which, cutpoint, callingPackage);
    }

    static void unregisterUidObserver(IUidObserver observer) throws RemoteException {
        Services.activityManager.get().unregisterUidObserver(observer);
    }

    static IContentProvider getContentProviderExternal(String name, int userId, IBinder token,
                                                       String tag) throws RemoteException {
        IActivityManager am = Services.activityManager.get();
        ContentProviderHolder contentProviderHolder;
        IContentProvider provider;
        if (Build.VERSION.SDK_INT >= 29) {
            contentProviderHolder = am.getContentProviderExternal(name, userId, token, tag);
            provider = contentProviderHolder != null ? contentProviderHolder.provider : null;
        } else {
            contentProviderHolder = am.getContentProviderExternal(name, userId, token);
            provider = contentProviderHolder != null ? contentProviderHolder.provider : null;
        }
        return provider;
    }

    static void removeContentProviderExternal(String name, IBinder token) throws RemoteException {
        Services.activityManager.get().removeContentProviderExternal(name, token);
    }

    static void forceStopPackageNoThrow(String packageName, int userId) {
        try {
            Services.activityManager.get().forceStopPackage(packageName, userId);
        } catch (Exception ignored) {
        }
    }
}
