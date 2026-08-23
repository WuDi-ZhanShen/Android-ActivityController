package android.content;

import android.os.Bundle;
import android.os.IInterface;
import android.os.RemoteException;

/** Compile-only hidden framework stub. */
public interface IContentProvider extends IInterface {
    Bundle call(AttributionSource attributionSource, String authority, String method, String arg,
                Bundle extras) throws RemoteException;
    Bundle call(String callingPkg, String callingFeatureId, String authority, String method,
                String arg, Bundle extras) throws RemoteException;
    Bundle call(String callingPkg, String authority, String method, String arg, Bundle extras)
            throws RemoteException;
    Bundle call(String callingPkg, String method, String arg, Bundle extras) throws RemoteException;
}
