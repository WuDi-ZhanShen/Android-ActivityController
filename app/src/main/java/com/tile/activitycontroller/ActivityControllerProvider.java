package com.tile.activitycontroller;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;

/**
 * Binder delivery endpoint for the privileged app_process process.
 * The transport intentionally mirrors ShizukuProvider: exported provider + shell-only permission
 * + ContentProvider.call() carrying a Parcelable BinderContainer.
 */
public class ActivityControllerProvider extends ContentProvider {

    public static final String AUTHORITY_SUFFIX = ".activitycontroller";
    public static final String METHOD_SEND_BINDER = "sendBinder";
    public static final String METHOD_GET_BINDER = "getBinder";
    public static final String EXTRA_BINDER = "moe.shizuku.privileged.api.intent.extra.BINDER";

    @Override
    public void attachInfo(Context context, ProviderInfo info) {
        super.attachInfo(context, info);
        if (info.multiprocess) {
            throw new IllegalStateException("android:multiprocess must be false");
        }
        if (!info.exported) {
            throw new IllegalStateException("android:exported must be true");
        }
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (METHOD_SEND_BINDER.equals(method)) {
            if (extras == null) return null;
            extras.setClassLoader(BinderContainer.class.getClassLoader());
            BinderContainer container = extras.getParcelable(EXTRA_BINDER);
            if (container != null && container.binder != null) {
                // Same behavior as ShizukuProvider: do not replace an already-live Binder.
                if (!ActivityControllerClient.pingBinder()) {
                    ActivityControllerClient.onBinderReceived(container.binder);
                }
            }
            return new Bundle();
        }

        if (METHOD_GET_BINDER.equals(method)) {
            IBinder binder = ActivityControllerClient.getBinder();
            if (binder == null || !binder.pingBinder()) return null;
            Bundle reply = new Bundle();
            reply.putParcelable(EXTRA_BINDER, new BinderContainer(binder));
            return reply;
        }

        return null;
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
}
