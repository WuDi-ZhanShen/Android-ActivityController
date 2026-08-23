package com.tile.activitycontroller;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.Button;
import android.widget.ExpandableListView;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SearchView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import rikka.shizuku.Shizuku;

/** Single-activity UI for service control and per-Activity blocking. */
public class MainActivity extends Activity implements SearchView.OnQueryTextListener {

    public static final int PACKAGEMODE_USER = 0;
    public static final int PACKAGEMODE_SYSTEM = 1;
    public static final int PACKAGEMODE_ALL = 2;
    public static final int ACTIVITYMODE_EXPORTED = 0;
    public static final int ACTIVITYMODE_UNEXPORTED = 1;

    private static final int PAGE_ALL = 0;
    private static final int PAGE_SELECTED = 1;
    private static final String STATE_CURRENT_PAGE = "current_page";

    public static final Map<String, Boolean> targetPkgNamesMap = new HashMap<>();

    public static class AppInfo {
        public String packageName;
        public String packageLabel;
        public Drawable packageIcon;
        public Set<String> launcherActivityNames = new HashSet<>();
    }

    public static class ActivityItem {
        public ActivityInfo activityInfo;
        public Drawable activityIcon;
        public String activityLabel;
    }

    private SharedPreferences sharedPreferences;
    private volatile IUserService iUserService;
    private boolean shizukuPermissionListenerAdded;

    private View serviceCard;
    private View serviceDot;
    private TextView serviceTitle;
    private Button serviceAction;
    private SearchView searchView;
    private RadioGroup packageGroup;
    private Switch showUnexported;
    private TextView tabAll;
    private TextView tabSelected;
    private View pageContainer;
    private ExpandableListView listAll;
    private ExpandableListView listSelected;
    private ProgressBar loading;
    private TextView emptyText;

    private final List<AppInfo> allAppList = new ArrayList<>();
    private final HashMap<AppInfo, List<ActivityItem>> allActivityMap = new HashMap<>();
    private final List<AppInfo> selectedAppList = new ArrayList<>();
    private final HashMap<AppInfo, List<ActivityItem>> selectedActivityMap = new HashMap<>();

    private int currentPackageMode = PACKAGEMODE_USER;
    private int currentActivityMode = ACTIVITYMODE_EXPORTED;
    private int currentPage = PAGE_ALL;
    private String currentQuery = "";
    private boolean selectedPageDirty;
    private boolean allPageDirty;

    private AppLoadTask currentTask;
    private MyExpandableListAdapter allAdapter;
    private MyExpandableListAdapter selectedAdapter;
    private final Handler searchHandler = new Handler(Looper.getMainLooper());
    private Runnable searchRunnable;
    private GestureDetector pageGestureDetector;

    private final ActivityControllerClient.OnBinderReceivedListener binderReceivedListener =
            service -> runOnUiThread(() -> onServiceReady(service));
    private final ActivityControllerClient.OnBinderDeadListener binderDeadListener =
            () -> runOnUiThread(this::onServiceDead);

    private final Shizuku.OnRequestPermissionResultListener requestPermissionResultListener =
            (requestCode, grantResult) -> {
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    startViaShizuku();
                } else {
                    Toast.makeText(this, "未授予 Shizuku 权限", Toast.LENGTH_SHORT).show();
                }
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            getWindow().setNavigationBarContrastEnforced(false);
        }
        boolean night = (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        if (!night) {
            int flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
            getWindow().getDecorView().setSystemUiVisibility(flags);
        }
        setContentView(R.layout.main);

        sharedPreferences = getSharedPreferences("data", 0);
        initHashMapByDisallowedList(targetPkgNamesMap,
                sharedPreferences.getString("disallowedList", ""));

        bindViews();
        setupControls();
        setupPageGestures();
        if (savedInstanceState != null
                && savedInstanceState.getInt(STATE_CURRENT_PAGE, PAGE_ALL) == PAGE_SELECTED) {
            switchPage(PAGE_SELECTED, false);
        }
        playEntranceAnimation();

        ActivityControllerClient.addBinderReceivedListener(binderReceivedListener, true);
        ActivityControllerClient.addBinderDeadListener(binderDeadListener);

        reloadAppList();

        if (sharedPreferences.getBoolean("first", true)) {
            showPrivacy();
        } else {
            maybeShowTip();
        }
    }

    private void bindViews() {
        serviceCard = findViewById(R.id.service_card);
        serviceDot = findViewById(R.id.service_dot);
        serviceTitle = findViewById(R.id.service_title);
        serviceAction = findViewById(R.id.service_action);
        searchView = findViewById(R.id.search_view);
        packageGroup = findViewById(R.id.package_group);
        showUnexported = findViewById(R.id.show_unexported);
        tabAll = findViewById(R.id.tab_all);
        tabSelected = findViewById(R.id.tab_selected);
        pageContainer = findViewById(R.id.page_container);
        listAll = findViewById(R.id.list_all);
        listSelected = findViewById(R.id.list_selected);
        loading = findViewById(R.id.loading);
        emptyText = findViewById(R.id.empty_text);
    }

    private void setupControls() {
        serviceAction.setOnClickListener(v -> {
            if (iUserService == null) {
                showActivate();
            } else {
                showStopConfirmation();
            }
        });
        updateServiceUi(false);

        searchView.setOnQueryTextListener(this);
        searchView.setQueryHint("搜索应用、包名或 Activity");

        packageGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.filter_system) {
                currentPackageMode = PACKAGEMODE_SYSTEM;
            } else if (checkedId == R.id.filter_all) {
                currentPackageMode = PACKAGEMODE_ALL;
            } else {
                currentPackageMode = PACKAGEMODE_USER;
            }
            updateFilterChipStyles();
            reloadAppList();
        });
        updateFilterChipStyles();

        // Restore the last state before installing the listener, so startup does not
        // trigger an unnecessary reload.
        boolean showUnexportedActivities =
                sharedPreferences.getBoolean("showUnexportedActivities", false);
        showUnexported.setChecked(showUnexportedActivities);
        currentActivityMode = showUnexportedActivities
                ? ACTIVITYMODE_UNEXPORTED
                : ACTIVITYMODE_EXPORTED;

        showUnexported.setOnCheckedChangeListener((buttonView, isChecked) -> {
            currentActivityMode = isChecked
                    ? ACTIVITYMODE_UNEXPORTED
                    : ACTIVITYMODE_EXPORTED;
            sharedPreferences.edit()
                    .putBoolean("showUnexportedActivities", isChecked)
                    .apply();
            reloadAppList();
        });

        tabAll.setOnClickListener(v -> switchPage(PAGE_ALL, true));
        tabSelected.setOnClickListener(v -> switchPage(PAGE_SELECTED, true));
        updateTabStyles();

        ExpandableListView.OnGroupClickListener groupClickListener =
                (parent, v, groupPosition, id) -> {
                    if (parent.isGroupExpanded(groupPosition)) {
                        parent.collapseGroup(groupPosition);
                    } else {
                        parent.expandGroup(groupPosition);
                    }
                    return true;
                };
        listAll.setOnGroupClickListener(groupClickListener);
        listSelected.setOnGroupClickListener(groupClickListener);
    }

    private void setupPageGestures() {
        pageGestureDetector = new GestureDetector(this,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onDown(MotionEvent e) {
                        return true;
                    }

                    @Override
                    public boolean onFling(MotionEvent e1, MotionEvent e2,
                                           float velocityX, float velocityY) {
                        if (e1 == null || e2 == null) return false;
                        float dx = e2.getX() - e1.getX();
                        float dy = e2.getY() - e1.getY();
                        if (Math.abs(dx) < 140 || Math.abs(velocityX) < 350
                                || Math.abs(dx) < Math.abs(dy) * 1.25f) {
                            return false;
                        }
                        if (dx < 0 && currentPage == PAGE_ALL) {
                            switchPage(PAGE_SELECTED, true);
                            return true;
                        }
                        if (dx > 0 && currentPage == PAGE_SELECTED) {
                            switchPage(PAGE_ALL, true);
                            return true;
                        }
                        return false;
                    }
                });
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (pageGestureDetector != null) {
            pageGestureDetector.onTouchEvent(ev);
        }
        return super.dispatchTouchEvent(ev);
    }

    private void playEntranceAnimation() {
        View header = findViewById(R.id.header);
        View filterCard = findViewById(R.id.filter_card);
        View tabs = findViewById(R.id.page_tabs);
        animateIn(header, 0);
        animateIn(filterCard, 80);
        animateIn(tabs, 160);
    }

    private void animateIn(View view, long delay) {
        view.setAlpha(0f);
        view.setTranslationY(dp(14));
        view.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(delay)
                .setDuration(300)
                .setInterpolator(new AccelerateDecelerateInterpolator())
                .start();
    }

    private void updateFilterChipStyles() {
        styleFilterButton(findViewById(R.id.filter_user),
                currentPackageMode == PACKAGEMODE_USER);
        styleFilterButton(findViewById(R.id.filter_system),
                currentPackageMode == PACKAGEMODE_SYSTEM);
        styleFilterButton(findViewById(R.id.filter_all),
                currentPackageMode == PACKAGEMODE_ALL);
    }

    private void styleFilterButton(RadioButton button, boolean selected) {
        button.setBackground(roundedBackground(
                selected ? getColor(R.color.accent_soft) : android.graphics.Color.TRANSPARENT,
                14));
        button.setTextColor(getColor(selected ? R.color.accent : R.color.text_secondary));
        button.setTypeface(null, selected ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
    }

    private void updateTabStyles() {
        styleTab(tabAll, currentPage == PAGE_ALL);
        styleTab(tabSelected, currentPage == PAGE_SELECTED);
    }

    private void styleTab(TextView tab, boolean selected) {
        tab.setBackground(roundedBackground(
                selected ? getColor(R.color.surface) : android.graphics.Color.TRANSPARENT,
                13));
        tab.setTextColor(getColor(selected ? R.color.accent : R.color.text_secondary));
        tab.setTypeface(null, selected ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        tab.setElevation(selected ? dp(1) : 0f);
    }

    private GradientDrawable roundedBackground(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private GradientDrawable dotDrawable(int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);
        drawable.setColor(color);
        return drawable;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void onServiceReady(IUserService service) {
        if (isFinishing() || isDestroyed()) return;
        iUserService = service;
        try {
            service.updateTargetPkgNamesMap(sharedPreferences.getString("disallowedList", ""));
        } catch (RemoteException e) {
            iUserService = null;
            updateServiceUi(false);
            return;
        }
        updateServiceUi(true);
    }

    private void onServiceDead() {
        if (isFinishing() || isDestroyed()) return;
        iUserService = null;
        updateServiceUi(false);
    }

    private void updateServiceUi(boolean running) {
        serviceCard.animate().cancel();
        serviceCard.animate().scaleX(0.985f).scaleY(0.985f).setDuration(70)
                .withEndAction(() -> serviceCard.animate().scaleX(1f).scaleY(1f).setDuration(160).start())
                .start();

        serviceDot.setBackground(dotDrawable(getColor(running ? R.color.success : R.color.danger)));
        serviceTitle.setText(running ? "服务状态：已运行" : "服务状态：未运行");
        serviceAction.setText(running ? "停止" : "启动");
        int tint = getColor(running ? R.color.danger : R.color.accent);
        serviceAction.setBackgroundTintList(ColorStateList.valueOf(tint));
        serviceAction.setTextColor(android.graphics.Color.WHITE);
    }

    private void showStopConfirmation() {
        new AlertDialog.Builder(this)
                .setTitle("停止服务")
                .setMessage("停止后将不再阻止任何 Activity 启动。")
                .setPositiveButton("停止", (dialog, which) -> {
                    IUserService service = iUserService;
                    if (service != null) {
                        try {
                            service.exit();
                        } catch (RemoteException ignored) {
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    public IUserService getUserService() {
        return iUserService;
    }

    /** Called by the adapter. allowed=true means switch ON; allowed=false means blocked/off. */
    void setActivityAllowed(String component, boolean allowed) {
        if (allowed) {
            targetPkgNamesMap.remove(component);
        } else {
            targetPkgNamesMap.put(component, true);
        }

        persistDisallowedList();
        pushDisallowedListToService();

        // The blocked-page structure may have changed, but keep the current
        // SELECT page as a snapshot until it is entered again.
        selectedPageDirty = true;

        // When the change is made from SELECT, the hidden ALL page still has
        // old switch widgets. Its data structure is unchanged, so only request
        // a lightweight rebind when ALL is entered again.
        if (currentPage == PAGE_SELECTED) {
            allPageDirty = true;
        }
    }

    private void persistDisallowedList() {
        StringBuilder builder = new StringBuilder();
        for (String key : targetPkgNamesMap.keySet()) {
            if (key == null || key.isEmpty()) continue;
            if (builder.length() > 0) builder.append(',');
            builder.append(key);
        }
        sharedPreferences.edit().putString("disallowedList", builder.toString()).apply();
    }

    private void pushDisallowedListToService() {
        IUserService service = iUserService;
        if (service == null) return;
        try {
            service.updateTargetPkgNamesMap(sharedPreferences.getString("disallowedList", ""));
        } catch (RemoteException ignored) {
        }
    }

    private void reloadAppList() {
        if (currentTask != null) {
            currentTask.cancel(true);
        }
        currentTask = new AppLoadTask();
        currentTask.execute(currentPackageMode, currentActivityMode);
    }

    private Set<String> queryLauncherActivities(String packageName) {
        Set<String> result = new HashSet<>();
        PackageManager pm = getPackageManager();
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        intent.setPackage(packageName);
        List<ResolveInfo> resolveInfos = pm.queryIntentActivities(intent, 0);
        for (ResolveInfo resolveInfo : resolveInfos) {
            if (resolveInfo.activityInfo != null && resolveInfo.activityInfo.name != null) {
                result.add(resolveInfo.activityInfo.name);
            }
        }
        return result;
    }

    private boolean isActivityBlocked(ActivityInfo activityInfo) {
        if (activityInfo == null || activityInfo.name == null) return false;
        String key = new android.content.ComponentName(
                activityInfo.packageName, activityInfo.name).flattenToShortString();
        return targetPkgNamesMap.containsKey(key);
    }

    private final class AppLoadTask extends AsyncTask<Integer, Void, Void> {
        private final List<AppInfo> loadedApps = new ArrayList<>();
        private final HashMap<AppInfo, List<ActivityItem>> loadedActivities = new HashMap<>();

        @Override
        protected void onPreExecute() {
            loading.setVisibility(View.VISIBLE);
            emptyText.setVisibility(View.GONE);
        }

        @Override
        protected Void doInBackground(Integer... params) {
            int packageMode = params[0];
            int activityMode = params[1];
            PackageManager pm = getPackageManager();
            List<PackageInfo> packages = pm.getInstalledPackages(0);

            String query = currentQuery == null
                    ? "" : currentQuery.trim().toLowerCase(Locale.ROOT);
            boolean hasQuery = !query.isEmpty();

            for (PackageInfo pkg0 : packages) {
                if (isCancelled()) return null;
                ApplicationInfo ai = pkg0.applicationInfo;
                if (ai == null) continue;

                boolean isSystem = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                if ((packageMode == PACKAGEMODE_USER && isSystem)
                        || (packageMode == PACKAGEMODE_SYSTEM && !isSystem)) {
                    continue;
                }

                String pkgName = ai.packageName;
                String label;
                try {
                    label = pm.getApplicationLabel(ai).toString();
                } catch (Throwable tr) {
                    label = pkgName;
                }
                boolean appMatches = !hasQuery
                        || label.toLowerCase(Locale.ROOT).contains(query)
                        || pkgName.toLowerCase(Locale.ROOT).contains(query);

                PackageInfo pkg;
                try {
                    pkg = pm.getPackageInfo(pkgName, PackageManager.GET_ACTIVITIES);
                } catch (PackageManager.NameNotFoundException e) {
                    continue;
                }

                List<ActivityItem> activities = new ArrayList<>();
                ActivityInfo[] activityArray = pkg.activities;
                if (activityArray != null) {
                    for (ActivityInfo info : activityArray) {
                        if (isCancelled()) return null;
                        if (info == null || info.name == null) continue;
                        if (activityMode == ACTIVITYMODE_EXPORTED && !info.exported) continue;

                        String activityLabel;
                        try {
                            activityLabel = info.loadLabel(pm).toString();
                        } catch (Throwable tr) {
                            activityLabel = info.name;
                        }
                        boolean activityMatches = !hasQuery
                                || activityLabel.toLowerCase(Locale.ROOT).contains(query)
                                || info.name.toLowerCase(Locale.ROOT).contains(query);
                        if (!appMatches && !activityMatches) continue;

                        ActivityItem item = new ActivityItem();
                        item.activityInfo = info;
                        item.activityLabel = activityLabel;
                        try {
                            item.activityIcon = info.loadIcon(pm);
                        } catch (Throwable ignored) {
                            item.activityIcon = ai.loadIcon(pm);
                        }
                        activities.add(item);
                    }
                }

                // "全部" page means all apps in the current package category. With a search query,
                // keep an app only when the app itself or one of its visible Activity rows matches.
                if (hasQuery && !appMatches && activities.isEmpty()) {
                    continue;
                }

                AppInfo app = new AppInfo();
                app.packageName = pkgName;
                app.packageLabel = label;
                try {
                    app.packageIcon = pm.getApplicationIcon(ai);
                } catch (Throwable ignored) {
                    app.packageIcon = ai.loadIcon(pm);
                }
                app.launcherActivityNames = queryLauncherActivities(pkgName);
                loadedApps.add(app);
                loadedActivities.put(app, activities);
            }

            loadedApps.sort(Comparator.comparing(
                    app -> app.packageLabel.toLowerCase(Locale.ROOT)));
            return null;
        }

        @Override
        protected void onPostExecute(Void unused) {
            if (isCancelled()) return;
            allAppList.clear();
            allAppList.addAll(loadedApps);
            allActivityMap.clear();
            allActivityMap.putAll(loadedActivities);
            // The source data changed. Rebuild the selected page lazily unless it is visible now.
            selectedPageDirty = true;
            loading.setVisibility(View.GONE);
            refreshLoadedAdapters();
        }

        @Override
        protected void onCancelled() {
            loading.setVisibility(View.GONE);
        }
    }

    private void rebuildSelectedFromLoadedData() {
        selectedAppList.clear();
        selectedActivityMap.clear();
        for (AppInfo app : allAppList) {
            List<ActivityItem> source = allActivityMap.get(app);
            if (source == null || source.isEmpty()) continue;
            List<ActivityItem> blocked = new ArrayList<>();
            for (ActivityItem item : source) {
                if (isActivityBlocked(item.activityInfo)) {
                    blocked.add(item);
                }
            }
            if (!blocked.isEmpty()) {
                selectedAppList.add(app);
                selectedActivityMap.put(app, blocked);
            }
        }
    }

    private void refreshLoadedAdapters() {
        // Search/category/exported-mode changes replace the ALL-page source data,
        // so create a fresh adapter once. This also makes any previous ALL-page
        // switch dirty state obsolete.
        allAdapter = new MyExpandableListAdapter(
                allAppList, allActivityMap, targetPkgNamesMap, this);
        listAll.setAdapter(allAdapter);
        allPageDirty = false;

        // If SELECT is visible, source-data changes must be reflected immediately.
        // Otherwise leave it dirty and rebuild only when the user enters SELECT.
        if (currentPage == PAGE_SELECTED) {
            refreshSelectedPageIfDirty();
        }

        activeList().setAlpha(0f);
        activeList().animate().alpha(1f).setDuration(220).start();
        updateEmptyState();
    }

    private void refreshAllPageIfDirty() {
        if (!allPageDirty) return;

        allPageDirty = false;

        if (allAdapter != null) {
            // Run on the next UI frame. switchPage() has just changed listAll from
            // GONE to VISIBLE, and rebinding it synchronously at that exact moment
            // may leave the old expanded child views attached. Waiting one frame
            // guarantees the destination list participates in layout first.
            listAll.post(() -> {
                allAdapter.notifyDataSetChanged();
                listAll.invalidateViews();
                listAll.requestLayout();
            });
        }
    }

    private void refreshSelectedPageIfDirty() {
        if (!selectedPageDirty) return;

        rebuildSelectedFromLoadedData();

        if (selectedAdapter == null) {
            selectedAdapter = new MyExpandableListAdapter(
                    selectedAppList, selectedActivityMap, targetPkgNamesMap, this);
            listSelected.setAdapter(selectedAdapter);
        } else {
            selectedAdapter.notifyDataSetChanged();
        }

        for (int i = 0; i < selectedAppList.size(); i++) {
            listSelected.expandGroup(i);
        }

        selectedPageDirty = false;
    }

    private ExpandableListView activeList() {
        return currentPage == PAGE_ALL ? listAll : listSelected;
    }

    private void switchPage(int page, boolean animate) {
        if (page == currentPage) return;

        View oldPage = currentPage == PAGE_ALL ? listAll : listSelected;
        View newPage = page == PAGE_ALL ? listAll : listSelected;
        int direction = page > currentPage ? 1 : -1;
        int width = pageContainer.getWidth();
        if (width <= 0) width = getResources().getDisplayMetrics().widthPixels;

        // Make the destination list visible before rebinding it. In particular, the ALL
        // page may have been GONE while an Activity was re-enabled from SELECT; rebinding
        // a GONE ExpandableListView is not guaranteed to recreate its cached child views.
        newPage.setVisibility(View.VISIBLE);

        if (page == PAGE_SELECTED) {
            refreshSelectedPageIfDirty();
        } else {
            refreshAllPageIfDirty();
        }

        currentPage = page;
        updateTabStyles();

        if (!animate) {
            oldPage.setVisibility(View.GONE);
            oldPage.setTranslationX(0f);
            newPage.setTranslationX(0f);
            updateEmptyState();
            return;
        }

        newPage.setTranslationX(direction * width);
        newPage.setAlpha(0.75f);
        final View old = oldPage;
        old.animate()
                .translationX(-direction * width * 0.32f)
                .alpha(0f)
                .setDuration(220)
                .withEndAction(() -> {
                    old.setVisibility(View.GONE);
                    old.setTranslationX(0f);
                    old.setAlpha(1f);
                })
                .start();
        newPage.animate()
                .translationX(0f)
                .alpha(1f)
                .setDuration(240)
                .setInterpolator(new AccelerateDecelerateInterpolator())
                .start();
        updateEmptyState();
    }

    private void updateEmptyState() {
        int count = currentPage == PAGE_ALL ? allAppList.size() : selectedAppList.size();
        emptyText.setVisibility(count == 0 && loading.getVisibility() != View.VISIBLE
                ? View.VISIBLE : View.GONE);
        if (count == 0) {
            if (currentPage == PAGE_SELECTED) {
                emptyText.setText("当前分类没有已禁止的 Activity");
            } else if (currentQuery != null && !currentQuery.trim().isEmpty()) {
                emptyText.setText("没有匹配的应用或 Activity");
            } else {
                emptyText.setText("当前分类没有应用");
            }
        }
    }

    @Override
    public boolean onQueryTextSubmit(String query) {
        return false;
    }

    @Override
    public boolean onQueryTextChange(String newText) {
        currentQuery = newText == null ? "" : newText;
        if (searchRunnable != null) {
            searchHandler.removeCallbacks(searchRunnable);
        }
        searchRunnable = this::reloadAppList;
        searchHandler.postDelayed(searchRunnable, 300);
        return true;
    }

    private void showPrivacy() {
        new AlertDialog.Builder(this)
                .setTitle("隐私政策")
                .setMessage("本应用不会记录或上传您的任何信息。继续使用即表示您同意上述说明。")
                .setCancelable(false)
                .setPositiveButton("同意", (dialog, which) -> {
                    sharedPreferences.edit().putBoolean("first", false).apply();
                    maybeShowTip();
                })
                .setNegativeButton("退出", (dialog, which) -> finish())
                .show();
    }

    private void maybeShowTip() {
        if (!sharedPreferences.getBoolean("firstSet", true)) return;
        new AlertDialog.Builder(this)
                .setTitle("使用提示")
                .setMessage("第一页显示当前分类下的全部应用；向左滑进入“已禁止”页，只显示已经禁止的 Activity。\n\n"
                        + "Activity 开关：开启 = 允许启动，关闭 = 禁止启动。\n\n"
                        + "长按 Activity 可尝试启动，用来验证拦截是否生效。")
                .setPositiveButton("明白", null)
                .setNeutralButton("不再显示", (dialog, which) ->
                        sharedPreferences.edit().putBoolean("firstSet", false).apply())
                .show();
    }

    private void showActivate() {
        unzipFiles();
        String cmd = "sh " + getExternalFilesDir(null).getPath() + "/starter.sh "
                + (android.os.Process.myUid() / 100000);
        new AlertDialog.Builder(this)
                .setTitle("启动服务")
                .setMessage("任选一种方式启动：ADB、Shizuku 或 root。设备重启后需要重新启动服务。")
                .setPositiveButton("root", (dialog, which) -> {
                    try {
                        Process process = Runtime.getRuntime().exec("su");
                        OutputStream outputStream = process.getOutputStream();
                        outputStream.write((cmd + "\nexit\n").getBytes());
                        outputStream.flush();
                        outputStream.close();
                    } catch (IOException e) {
                        Toast.makeText(this, "无法启动 su", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNeutralButton("复制 ADB 命令", (dialog, which) -> {
                    String adb = "adb shell " + cmd;
                    ((ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE))
                            .setPrimaryClip(ClipData.newPlainText("ActivityController", adb));
                    Toast.makeText(this, "ADB 命令已复制", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Shizuku", (dialog, which) -> checkShizukuAndStart())
                .show();
    }

    private void checkShizukuAndStart() {
        if (!shizukuPermissionListenerAdded) {
            Shizuku.addRequestPermissionResultListener(requestPermissionResultListener);
            shizukuPermissionListenerAdded = true;
        }

        try {
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(0);
                return;
            }
        } catch (Exception e) {
            if (e instanceof IllegalStateException) {
                Toast.makeText(this, "Shizuku 未运行", Toast.LENGTH_SHORT).show();
                return;
            }
            if (checkSelfPermission("moe.shizuku.manager.permission.API_V23")
                    != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "无法获取 Shizuku 权限", Toast.LENGTH_SHORT).show();
                return;
            }
        }
        startViaShizuku();
    }

    private void startViaShizuku() {
        try {
            Process process = Shizuku.newProcess(new String[]{"sh"}, null, null);
            OutputStream output = process.getOutputStream();
            output.write(("sh " + getExternalFilesDir(null).getPath() + "/starter.sh "
                    + (android.os.Process.myUid() / 100000) + "\nexit\n").getBytes());
            output.flush();
            output.close();
            process.waitFor();
            process.destroyForcibly();
        } catch (IOException | InterruptedException e) {
            Toast.makeText(this, "Shizuku 启动命令执行失败", Toast.LENGTH_SHORT).show();
        }
    }

    private void unzipFiles() {
        String starter = getExternalFilesDir(null).getPath() + "/starter.sh";
        try (InputStream input = getAssets().open("starter.sh");
             FileOutputStream output = new FileOutputStream(starter)) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
        } catch (IOException ignored) {
        }

        String dex = getExternalFilesDir(null).getPath() + "/ForeWatcher.dex";
        try (ZipFile zipFile = new ZipFile(getPackageResourcePath())) {
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!"classes.dex".equals(entry.getName())) continue;
                try (InputStream input = zipFile.getInputStream(entry);
                     FileOutputStream output = new FileOutputStream(dex)) {
                    byte[] buffer = new byte[4096];
                    int read;
                    while ((read = input.read(buffer)) > 0) {
                        output.write(buffer, 0, read);
                    }
                }
                break;
            }
        } catch (IOException ignored) {
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putInt(STATE_CURRENT_PAGE, currentPage);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onDestroy() {
        if (shizukuPermissionListenerAdded) {
            Shizuku.removeRequestPermissionResultListener(requestPermissionResultListener);
        }
        ActivityControllerClient.removeBinderReceivedListener(binderReceivedListener);
        ActivityControllerClient.removeBinderDeadListener(binderDeadListener);
        if (currentTask != null) currentTask.cancel(true);
        if (searchRunnable != null) searchHandler.removeCallbacks(searchRunnable);
        super.onDestroy();
    }

    public static void initHashMapByDisallowedList(Map<String, Boolean> map, String disallowedList) {
        map.clear();
        if (disallowedList == null || disallowedList.trim().isEmpty()) return;
        String[] entries = disallowedList.split(",");
        for (String entry : entries) {
            if (entry != null && !entry.trim().isEmpty()) {
                map.put(entry.trim(), true);
            }
        }
    }
}
