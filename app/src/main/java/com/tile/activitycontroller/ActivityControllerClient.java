package com.tile.activitycontroller;

import android.os.IBinder;
import android.os.RemoteException;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * App-process side holder for the Binder delivered by the privileged app_process server.
 * This plays the same role as Shizuku's process-local Binder holder.
 */
public final class ActivityControllerClient {

    public interface OnBinderReceivedListener {
        void onBinderReceived(IUserService service);
    }

    public interface OnBinderDeadListener {
        void onBinderDead();
    }

    private static final CopyOnWriteArrayList<OnBinderReceivedListener> RECEIVED_LISTENERS = new CopyOnWriteArrayList<>();
    private static final CopyOnWriteArrayList<OnBinderDeadListener> DEAD_LISTENERS = new CopyOnWriteArrayList<>();

    private static IBinder binder;
    private static IUserService service;
    private static IBinder.DeathRecipient deathRecipient;

    private ActivityControllerClient() {
    }

    public static synchronized IBinder getBinder() {
        return binder;
    }

    public static synchronized IUserService getService() {
        if (binder == null || !binder.pingBinder()) {
            return null;
        }
        return service;
    }

    public static synchronized boolean pingBinder() {
        return binder != null && binder.pingBinder();
    }

    public static void addBinderReceivedListener(OnBinderReceivedListener listener, boolean callImmediatelyIfReady) {
        if (listener == null) return;
        RECEIVED_LISTENERS.addIfAbsent(listener);
        if (callImmediatelyIfReady) {
            IUserService current = getService();
            if (current != null) {
                listener.onBinderReceived(current);
            }
        }
    }

    public static void removeBinderReceivedListener(OnBinderReceivedListener listener) {
        RECEIVED_LISTENERS.remove(listener);
    }

    public static void addBinderDeadListener(OnBinderDeadListener listener) {
        if (listener != null) DEAD_LISTENERS.addIfAbsent(listener);
    }

    public static void removeBinderDeadListener(OnBinderDeadListener listener) {
        DEAD_LISTENERS.remove(listener);
    }

    static void onBinderReceived(IBinder newBinder) {
        if (newBinder == null || !newBinder.pingBinder()) return;

        IUserService newService;
        synchronized (ActivityControllerClient.class) {
            if (binder == newBinder && binder.pingBinder()) return;

            unlinkDeathLocked();
            binder = newBinder;
            service = IUserService.Stub.asInterface(newBinder);
            final IBinder watchedBinder = newBinder;
            deathRecipient = () -> onBinderDied(watchedBinder);
            try {
                newBinder.linkToDeath(deathRecipient, 0);
            } catch (RemoteException e) {
                binder = null;
                service = null;
                deathRecipient = null;
                return;
            }
            newService = service;
        }

        for (OnBinderReceivedListener listener : RECEIVED_LISTENERS) {
            listener.onBinderReceived(newService);
        }
    }

    private static void onBinderDied(IBinder deadBinder) {
        synchronized (ActivityControllerClient.class) {
            if (binder != deadBinder) return;
            unlinkDeathLocked();
            binder = null;
            service = null;
        }
        for (OnBinderDeadListener listener : DEAD_LISTENERS) {
            listener.onBinderDead();
        }
    }

    private static void unlinkDeathLocked() {
        if (binder != null && deathRecipient != null) {
            try {
                binder.unlinkToDeath(deathRecipient, 0);
            } catch (Throwable ignored) {
            }
        }
        deathRecipient = null;
    }
}
