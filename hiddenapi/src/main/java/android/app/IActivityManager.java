package android.app;

import android.content.IContentProvider;
import android.content.Intent;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IInterface;
import android.os.RemoteException;

/** Compile-only hidden framework stub. The platform implementation is used at runtime. */
public interface IActivityManager extends IInterface {
    void setActivityController(IActivityController watcher, boolean imAMonkey) throws RemoteException;

    int startActivity(IApplicationThread caller, String callingPackage, Intent intent,
                      String resolvedType, IBinder resultTo, String resultWho, int requestCode,
                      int flags, ProfilerInfo profilerInfo, Bundle options) throws RemoteException;

    void registerProcessObserver(IProcessObserver observer) throws RemoteException;
    void unregisterProcessObserver(IProcessObserver observer) throws RemoteException;

    void registerUidObserver(IUidObserver observer, int which, int cutpoint, String callingPackage)
            throws RemoteException;
    void unregisterUidObserver(IUidObserver observer) throws RemoteException;

    ContentProviderHolder getContentProviderExternal(String name, int userId, IBinder token)
            throws RemoteException;
    ContentProviderHolder getContentProviderExternal(String name, int userId, IBinder token, String tag)
            throws RemoteException;
    void removeContentProviderExternal(String name, IBinder token) throws RemoteException;

    void forceStopPackage(String packageName, int userId) throws RemoteException;

    abstract class Stub extends Binder implements IActivityManager {
        public static IActivityManager asInterface(IBinder binder) {
            throw new RuntimeException("STUB");
        }
    }
}
