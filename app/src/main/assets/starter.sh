#!/system/bin/sh

file_name="ForeWatcher.dex"
fallback_path="$(dirname "$0")/$file_name"
user_id="${1:-0}"

# Shizuku launches app_process with the manager APK itself as java.class.path.
apk_path="$(pm path com.tile.activitycontroller 2>/dev/null | head -n 1)"
apk_path="${apk_path#package:}"
if [ -n "$apk_path" ] && [ -r "$apk_path" ]; then
    class_path="$apk_path"
else
    # Vendor/permission fallback retained for devices where pm path is unavailable.
    class_path="$fallback_path"
fi

# Match Shizuku's single-server behavior: kill an old process with the same nice name first.
for old_pid in $(pidof activity_controller_server 2>/dev/null); do
    kill -9 "$old_pid" 2>/dev/null || exit 9
done

export CLASSPATH="$class_path"
nohup /system/bin/app_process \
    "-Djava.class.path=$class_path" \
    /system/bin \
    --nice-name=activity_controller_server \
    com.tile.activitycontroller.ForeWatcher "$user_id" \
    >/dev/null 2>&1 &
