# ActivityController Binder transport (Shizuku-aligned)

The privileged `app_process` server no longer uses a sticky broadcast to hand an `IUserService`
Binder to the normal application process. The Android-version-sensitive transport is now kept in
lock-step with Shizuku's current implementation.

## Upstream code mirrored

- `RikkaApps/Shizuku/server/src/main/java/rikka/shizuku/server/ShizukuService.java`
  - `sendBinderToUserApp(...)`
- `RikkaApps/Shizuku/server/src/main/java/rikka/shizuku/server/BinderSender.java`
- `RikkaApps/Shizuku/server/src/main/java/rikka/shizuku/server/api/IContentProviderUtils.java`
- `RikkaW/HiddenApi/compat/src/main/java/rikka/hidden/compat/ActivityManagerApis.java`
- `RikkaW/HiddenApi/compat/src/main/java/rikka/hidden/compat/DeviceIdleControllerApis.java`
- `RikkaW/HiddenApi/compat/src/main/java/rikka/hidden/compat/adapter/ProcessObserverAdapter.java`
- `RikkaW/HiddenApi/compat/src/main/java/rikka/hidden/compat/adapter/UidObserverAdapter.java`
- `RikkaW/HiddenApi/compat/src/main/java/rikka/hidden/compat/util/SystemServiceBinder.java`
- `RikkaApps/Shizuku-API/provider/src/main/java/moe/shizuku/api/BinderContainer.java`
- `RikkaApps/Shizuku-API/provider/src/main/java/rikka/shizuku/ShizukuProvider.java`

Only code for Android versions below ActivityController's `minSdk 26` is intentionally omitted.
The unavoidable product-specific substitutions are the package name, provider authority,
`IUserService` Binder type, and the rule used to decide which UID belongs to ActivityController.

## Version-sensitive Binder path

1. `starter.sh` starts the privileged process through `/system/bin/app_process`, using the installed
   APK as `java.class.path` when available and `/system/bin` as app_process's parent directory.
2. `ForeWatcher` creates the `IUserService.Stub` Binder.
3. Before provider acquisition, Android 11+ receives the same 30-second temporary power exemption
   used by Shizuku.
4. `ActivityManagerApis.getContentProviderExternal()` uses:
   - Android 10+ (API 29+): `(name, userId, token, tag)`
   - Android 8-9 (API 26-28): `(name, userId, token)`
5. The external-provider token is deliberately `null`, matching Shizuku's BinderProxy/hash-key
   workaround.
6. A dead provider triggers the same one-time `forceStopPackage` + 1 second retry.
7. The service Binder is wrapped in `BinderContainer` and placed in a `Bundle`.
8. `IContentProviderUtils.callCompat()` uses the same four branches as Shizuku:
   - Android 12+ (API 31+): `AttributionSource`
   - Android 11 (API 30): calling package + feature id + authority
   - Android 10 (API 29): calling package + authority
   - Android 8-9 (API 26-28): legacy call signature
9. `ActivityControllerProvider.call("sendBinder", ...)` receives the Binder in the app process and
   stores it in `ActivityControllerClient`.
10. `removeContentProviderExternal()` runs in `finally`, matching Shizuku.

The previous reflection-based hidden-API wrappers were removed. Calls now compile against
compile-only framework stubs and resolve to Android framework classes at runtime, the same model
used by Shizuku/HiddenApi.

## Why Process/UID observers are retained

They are part of core Binder availability, not merely cleanup. If the normal application process is
killed while `ForeWatcher` remains alive, its process-local Binder reference disappears. Shizuku's
Process/UID observers detect the new app process/UID lifecycle and push the server Binder again.
Without this, reopening ActivityController after its process is reclaimed could leave the UI without
an `IUserService` Binder until the privileged server is manually restarted.

`UidObserverAdapter.onTransact()` also keeps Shizuku's compatibility guard for platform callback
shape changes.

## UI changes

- `MainActivity` and the former `SettingActivity` are merged into one Activity.
- No new UI dependency was added; the interface uses platform views, drawables, and animations.
- Activity switches now mean **ON = allowed** and **OFF = blocked**.
- The old "only show selected activities" menu is replaced by two horizontally swipeable pages:
  - **All**: every app in the current user/system/all category, with its matching Activity rows.
  - **Blocked**: only apps that currently contain blocked Activity entries, and only those blocked
    Activity rows. Groups on this page are automatically expanded.
