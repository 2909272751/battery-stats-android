package com.codex.batterystats;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.content.res.Configuration;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.Display;
import android.view.ViewGroup;
import android.view.Window;
import android.view.animation.OvershootInterpolator;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private StatsDatabase database;
    private Handler handler;
    private final ExecutorService overviewBatteryExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "overview-battery"));
    private final ExecutorService rootStateExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "root-state"));
    private LinearLayout root;
    private LinearLayout topBar;
    private LinearLayout tabsBar;
    private final TextView[] tabViews = new TextView[4];
    private ViewPager2 pager;
    private BatteryPagerAdapter pagerAdapter;
    private final View[] pageViews = new View[4];
    private final ScrollView[] pageScrolls = new ScrollView[4];
    private ScrollView currentScroll;
    private int page = 0;
    private int processSortMode = 0;
    private boolean processSortAscending;
    private boolean processAppOnly = false;
    private boolean processAutoRefresh = true;
    private boolean processSearchFocused;
    private String processKeyword = "";
    private boolean processLoading;
    private long processRequestId;
    private long lastProcessLoadAt;
    private List<ProcessInfo> processCache = new ArrayList<>();
    private LinearLayout processListParent;
    private LinearLayout processListCard;
    private boolean processSkeletonVisible;
    private int processListStartIndex;
    private int processVisibleLimit = 20;
    private final Map<String, String> labelCache = new HashMap<>();
    private final Map<String, Boolean> systemPackageCache = new HashMap<>();
    private final Map<String, Drawable> iconCache = new HashMap<>();
    private int dischargeSortMode = 0;
    private boolean dischargeShowSystemApps;
    private boolean dischargeSortAscending;
    private long lastCpuIdle;
    private long lastCpuTotal;
    private GpuInfo cachedGpuInfo;
    private long cachedGpuAt;
    private final Map<Integer, long[]> coreLast = new HashMap<>();
    private long lastLocalBatterySampleAt;
    private boolean moduleOkCached;
    private boolean pageScrollActive;
    private boolean pendingPageRefresh;
    private boolean dischargeSnapshotLoading;
    private long dischargeSnapshotRequestId;
    private DischargeSnapshot pendingDischargeSnapshot;
    private boolean overviewSnapshotLoading;
    private long overviewSnapshotRequestId;
    private OverviewPageViews overviewViews;
    private boolean chargeSnapshotLoading;
    private long chargeSnapshotRequestId;
    private ChargeSnapshot pendingChargeSnapshot;
    private ChargePageViews chargeViews;
    private DischargePageViews dischargeViews;
    private boolean overviewBatteryLoading;
    private BatterySample overviewLiveBatterySample;
    private long lastOverviewTopProcessAt;
    private List<ProcessInfo> overviewTopProcessCache = new ArrayList<>();
    private Boolean lastRealtimePageRequested;
    private boolean appInForeground;
    private final Runnable scrollSettledRefresh = new Runnable() {
        @Override
        public void run() {
            pageScrollActive = false;
            if (pendingDischargeSnapshot != null && page == 1) {
                DischargeSnapshot snapshot = pendingDischargeSnapshot;
                pendingDischargeSnapshot = null;
                applyDischargeSnapshot(snapshot, false);
                pendingPageRefresh = false;
                return;
            }
            if (pendingChargeSnapshot != null && page == 2) {
                ChargeSnapshot snapshot = pendingChargeSnapshot;
                pendingChargeSnapshot = null;
                applyChargeSnapshot(snapshot);
                pendingPageRefresh = false;
                return;
            }
            if (pendingPageRefresh && page >= 0 && page <= 2) {
                pendingPageRefresh = false;
                refreshCurrentPage();
            }
        }
    };

    private final Runnable refresher = new Runnable() {
        @Override
        public void run() {
            boolean processAtTop = currentScroll == null || currentScroll.getScrollY() < dp(12);
            if (page == 0 || page == 1 || page == 2) {
                refreshCurrentPage();
            } else if (page == 3 && processAutoRefresh && !processSearchFocused) {
                requestProcessLoad(false);
            }
            handler.postDelayed(this, page == 3 && processAutoRefresh && !processSearchFocused && processAtTop ? 1000 : 2000);
        }
    };

    private final Runnable overviewBatteryRefresher = new Runnable() {
        @Override
        public void run() {
            if (page == 0 && overviewViews != null) {
                requestOverviewBatterySnapshot();
            }
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        database = new StatsDatabase(this);
        handler = new Handler();
        preferHighRefreshRate();
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.removeCallbacks(refresher);
        handler.removeCallbacks(overviewBatteryRefresher);
        handler.postDelayed(refresher, 1000);
        handler.post(overviewBatteryRefresher);
        appInForeground = true;
        updateRealtimePageRequest(true);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refresher);
        handler.removeCallbacks(overviewBatteryRefresher);
        appInForeground = false;
        updateRealtimePageRequest(true);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        overviewBatteryExecutor.shutdownNow();
        appInForeground = false;
        updateRealtimePageRequest(true);
        rootStateExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (page != 0) {
            switchPage(0);
            return;
        }
        super.onBackPressed();
    }

    private void insertLocalBatterySampleIfNeeded() {
        long now = System.currentTimeMillis();
        if (now - lastLocalBatterySampleAt < 2000) {
            return;
        }
        lastLocalBatterySampleAt = now;
        try {
            database.insert(new BatteryReader(this).read());
        } catch (Exception ignored) {
        }
    }

    private void preferHighRefreshRate() {
        try {
            Window window = getWindow();
            Display display = Build.VERSION.SDK_INT >= 30 ? getDisplay() : getWindowManager().getDefaultDisplay();
            if (display == null) return;
            Display.Mode[] modes = display.getSupportedModes();
            float best = display.getRefreshRate();
            int bestModeId = 0;
            for (Display.Mode mode : modes) {
                if (mode.getRefreshRate() > best) {
                    best = mode.getRefreshRate();
                    bestModeId = mode.getModeId();
                }
            }
            if (bestModeId != 0 && Build.VERSION.SDK_INT >= 23) {
                window.getAttributes().preferredDisplayModeId = bestModeId;
                window.setAttributes(window.getAttributes());
            }
        } catch (Exception ignored) {
        }
    }

    private void render() {
        configureSystemBars();
        if (root == null) {
            root = new LinearLayout(this);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setBackgroundColor(pageBgColor());
            setContentView(root);
            addTopBar();
            addTabs();
            pager = new ViewPager2(this);
            pager.setOrientation(ViewPager2.ORIENTATION_HORIZONTAL);
            pager.setOffscreenPageLimit(1);
            pagerAdapter = new BatteryPagerAdapter();
            pager.setAdapter(pagerAdapter);
            pager.setCurrentItem(page, false);
            pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
                @Override
                public void onPageSelected(int position) {
                    if (page == position) return;
                    page = position;
                    updateTabs();
                    configureSystemBars();
                    updateCurrentScrollFromPager();
                    updateRealtimePageRequest(false);
                    if (page == 3) {
                        requestProcessLoad(false);
                    } else if (page == 1) {
                        requestDischargeSnapshot(true);
                    } else if (page == 2) {
                        requestChargeSnapshot(true);
                    } else {
                        requestOverviewSnapshot(true);
                    }
                }
            });
            root.addView(pager, new LinearLayout.LayoutParams(-1, 0, 1));
            handler.postDelayed(() -> requestOverviewSnapshot(true), 250);
        } else {
            root.setBackgroundColor(pageBgColor());
            updateTopBar();
            updateTabs();
            if (pager != null && pager.getCurrentItem() != page) {
                pager.setCurrentItem(page, false);
            }
        }
        updateCurrentScrollFromPager();
    }

    private void refreshCurrentPage() {
        if (page == 0) {
            requestOverviewSnapshot(false);
            return;
        }
        if (page == 1) {
            requestDischargeSnapshot(false);
            return;
        }
        if (page == 2) {
            requestChargeSnapshot(false);
            return;
        }
        if (pageScrollActive) {
            pendingPageRefresh = true;
            return;
        }
    }

    private void updateCurrentScrollFromPager() {
        currentScroll = page >= 0 && page < pageScrolls.length ? pageScrolls[page] : null;
    }

    private View pageView(int targetPage) {
        if (pageViews[targetPage] == null) {
            pageViews[targetPage] = buildPageView(targetPage, moduleOkCached);
        }
        return pageViews[targetPage];
    }

    private View buildPageView(int targetPage, boolean moduleOk) {
        int oldPage = page;
        page = targetPage;
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(10), dp(6), dp(10), dp(26));
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(targetPage == 0);
        scroll.setClipToPadding(false);
        pageScrolls[targetPage] = scroll;
        if (targetPage == 1 || targetPage == 2) {
            scroll.setOnScrollChangeListener((v, scrollX, scrollY, oldScrollX, oldScrollY) -> {
                if (scrollY == oldScrollY) return;
                pageScrollActive = true;
                handler.removeCallbacks(scrollSettledRefresh);
                handler.postDelayed(scrollSettledRefresh, 420);
            });
        }
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        View pageView = scroll;

        if (targetPage == 0) {
            addOverviewPage(content, moduleOk);
        } else if (targetPage == 1) {
            addDischargePage(content, oldPage == 1);
        } else if (targetPage == 2) {
            addChargePage(content, oldPage == 2);
        } else {
            addProcessPage(content);
        }
        page = oldPage;
        return pageView;
    }

    private final class BatteryPagerAdapter extends RecyclerView.Adapter<PageHolder> {
        @NonNull
        @Override
        public PageHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            FrameLayout container = new FrameLayout(MainActivity.this);
            container.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            return new PageHolder(container);
        }

        @Override
        public void onBindViewHolder(@NonNull PageHolder holder, int position) {
            holder.container.removeAllViews();
            View view = pageView(position);
            ViewGroup parent = (ViewGroup) view.getParent();
            if (parent != null) {
                parent.removeView(view);
            }
            holder.container.addView(view, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            if (position == page) {
                updateCurrentScrollFromPager();
            }
        }

        @Override
        public int getItemCount() {
            return 4;
        }

        @Override
        public int getItemViewType(int position) {
            return position;
        }
    }

    private static final class PageHolder extends RecyclerView.ViewHolder {
        final FrameLayout container;

        PageHolder(@NonNull FrameLayout itemView) {
            super(itemView);
            container = itemView;
        }
    }

    private void addTopBar() {
        topBar = new LinearLayout(this);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(0, 0, 0, 0);
        root.addView(topBar);
        updateTopBar();
    }

    private void updateTopBar() {
        if (topBar == null) return;
        topBar.removeAllViews();
        topBar.addView(new View(this), new LinearLayout.LayoutParams(0, statusBarSpace(), 1));
    }

    private int statusBarSpace() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        int status = id > 0 ? getResources().getDimensionPixelSize(id) : dp(24);
        return status + dp(6);
    }

    private void configureSystemBars() {
        if (Build.VERSION.SDK_INT >= 21) {
            getWindow().setStatusBarColor(pageBgColor());
        }
        if (Build.VERSION.SDK_INT >= 23) {
            int flags = getWindow().getDecorView().getSystemUiVisibility();
            if (isDarkMode()) {
                flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            } else {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            }
            getWindow().getDecorView().setSystemUiVisibility(flags);
        }
    }

    private String title() {
        if (page == 1) return "耗电统计";
        if (page == 2) return "充电统计";
        if (page == 3) return "进程信息";
        return "电池统计";
    }

    private void addTabs() {
        LinearLayout tabs = new LinearLayout(this);
        tabs.setPadding(dp(14), dp(4), dp(14), dp(6));
        tabs.addView(tab("总览", 0), new LinearLayout.LayoutParams(0, dp(42), 1));
        tabs.addView(tab("耗电", 1), new LinearLayout.LayoutParams(0, dp(42), 1));
        tabs.addView(tab("充电", 2), new LinearLayout.LayoutParams(0, dp(42), 1));
        tabs.addView(tab("进程", 3), new LinearLayout.LayoutParams(0, dp(42), 1));
        tabsBar = tabs;
        root.addView(tabsBar);
        updateTabs();
    }

    private TextView tab(String label, int target) {
        TextView view = text(tabLabel(target), 15, true, target == page ? Color.WHITE : primaryTextColor());
        view.setGravity(Gravity.CENTER);
        view.setOnClickListener(v -> {
            press(v);
            switchPage(target);
        });
        tabViews[target] = view;
        return view;
    }

    private String tabLabel(int target) {
        if (target == 1) return "\u8017\u7535";
        if (target == 2) return "\u5145\u7535";
        if (target == 3) return "\u8fdb\u7a0b";
        return "\u603b\u89c8";
    }

    private void updateTabs() {
        for (int i = 0; i < tabViews.length; i++) {
            TextView tab = tabViews[i];
            if (tab == null) continue;
            boolean selected = i == page;
            tab.animate().cancel();
            tab.setText(tabLabel(i));
            tab.setTextColor(selected ? Color.WHITE : primaryTextColor());
            tab.setBackground(rounded(selected ? accentColor() : chipColor(), dp(12)));
            tab.setAlpha(selected ? 1f : 0.92f);
        }
    }

    private void switchPage(int target) {
        if (page == target) {
            return;
        }
        if (pager != null) {
            pager.setCurrentItem(target, true);
            return;
        }
        page = target;
        updateRealtimePageRequest(false);
        render();
    }

    private void updateRealtimePageRequest(boolean force) {
        boolean needed = appInForeground && (page == 0 || page == 1 || page == 2);
        if (!force && lastRealtimePageRequested != null && lastRealtimePageRequested == needed) {
            return;
        }
        lastRealtimePageRequested = needed;
        rootStateExecutor.execute(() -> RootShell.setRealtimePageRequested(needed));
    }

    private void addOverviewPage(LinearLayout content, boolean moduleOk) {
        overviewViews = new OverviewPageViews();
        addOverviewResourceCard(content);
        addOverviewGpuCard(content);
        addOverviewCpuCard(content);
        addOverviewBottomCards(content);
        addOverviewDaemonCard(content);
        applyOverviewSnapshot(buildOverviewSnapshot(moduleOk));
    }

    private void addOverviewResourceCard(LinearLayout content) {
        LinearLayout card = card();
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setOnClickListener(v -> switchPage(3));
        overviewViews.memRing = new RingView(this);
        card.addView(overviewViews.memRing, new LinearLayout.LayoutParams(dp(84), dp(84)));

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(dp(10), 0, 0, 0);
        overviewViews.memPhysical = text("--", 12, true, primaryTextColor());
        overviewViews.memSwap = text("--", 12, true, primaryTextColor());
        overviewViews.memCached = text("--", 10, false, secondaryTextColor());
        info.addView(overviewViews.memPhysical, new LinearLayout.LayoutParams(-1, dp(24)));
        info.addView(overviewViews.memSwap, new LinearLayout.LayoutParams(-1, dp(24)));
        info.addView(overviewViews.memCached);
        card.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
        content.addView(card);
    }

    private void addOverviewGpuCard(LinearLayout content) {
        LinearLayout card = card();
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        TextView gpu = text("GPU", 18, false, secondaryTextColor());
        gpu.setGravity(Gravity.CENTER);
        gpu.setBackground(rounded(isDarkMode() ? Color.rgb(38, 43, 49) : Color.rgb(242, 243, 245), dp(48)));
        card.addView(gpu, new LinearLayout.LayoutParams(dp(64), dp(64)));
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(dp(16), 0, 0, 0);
        overviewViews.gpuFreq = text("--", 20, true, primaryTextColor());
        overviewViews.gpuLoad = text("--", 12, false, secondaryTextColor());
        overviewViews.gpuDevice = text(Build.HARDWARE + "  Android " + Build.VERSION.RELEASE, 12, false, secondaryTextColor());
        info.addView(overviewViews.gpuFreq);
        info.addView(overviewViews.gpuLoad);
        info.addView(overviewViews.gpuDevice);
        card.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
        content.addView(card);
    }

    private void addOverviewCpuCard(LinearLayout content) {
        LinearLayout card = card();
        card.setOrientation(LinearLayout.VERTICAL);
        card.setOnClickListener(v -> switchPage(3));
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        overviewViews.cpuTopList = new LinearLayout(this);
        overviewViews.cpuTopList.setOrientation(LinearLayout.VERTICAL);
        top.addView(overviewViews.cpuTopList, new LinearLayout.LayoutParams(0, dp(112), 1));

        View split = new View(this);
        split.setBackgroundColor(isDarkMode() ? Color.rgb(43, 49, 56) : Color.rgb(232, 234, 236));
        LinearLayout.LayoutParams splitLp = new LinearLayout.LayoutParams(1, dp(100));
        splitLp.setMargins(dp(8), 0, dp(8), 0);
        top.addView(split, splitLp);

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.CENTER);
        overviewViews.cpuBars = new MiniBarsView(this);
        right.addView(overviewViews.cpuBars, new LinearLayout.LayoutParams(dp(112), dp(38)));
        right.addView(text("CPU", 18, false, primaryTextColor()));
        overviewViews.cpuModel = text("--", 13, true, primaryTextColor());
        overviewViews.cpuTemp = text("--", 11, false, secondaryTextColor());
        right.addView(overviewViews.cpuModel);
        overviewViews.cpuLoad = text("--", 12, false, secondaryTextColor());
        right.addView(overviewViews.cpuLoad);
        right.addView(overviewViews.cpuTemp);
        top.addView(right, new LinearLayout.LayoutParams(0, dp(112), 1));
        card.addView(top);
        View line = new View(this);
        line.setBackgroundColor(isDarkMode() ? Color.rgb(43, 49, 56) : Color.rgb(232, 234, 236));
        card.addView(line, new LinearLayout.LayoutParams(-1, 1));
        overviewViews.coreGrid = new LinearLayout(this);
        overviewViews.coreGrid.setOrientation(LinearLayout.VERTICAL);
        overviewViews.coreGrid.setPadding(0, dp(6), 0, 0);
        card.addView(overviewViews.coreGrid);
        content.addView(card);
    }

    private void addOverviewBottomCards(LinearLayout content) {
        LinearLayout row = new LinearLayout(this);
        LinearLayout battery = card();
        battery.setOrientation(LinearLayout.VERTICAL);
        battery.setOnClickListener(v -> switchPage(1));
        overviewViews.batteryPower = text("--W", 16, false, secondaryTextColor());
        overviewViews.batteryLevel = text("--%", 16, false, secondaryTextColor());
        overviewViews.batteryTemp = text("--C", 16, false, secondaryTextColor());
        battery.addView(overviewViews.batteryPower);
        battery.addView(overviewViews.batteryLevel);
        battery.addView(overviewViews.batteryTemp);
        LinearLayout sys = card();
        sys.setOrientation(LinearLayout.VERTICAL);
        sys.setOnClickListener(v -> switchPage(3));
        sys.addView(text("Android " + Build.VERSION.RELEASE, 16, false, secondaryTextColor()));
        overviewViews.uptime = text("--", 16, false, secondaryTextColor());
        overviewViews.module = text("--", 16, false, accentColor());
        sys.addView(overviewViews.uptime);
        sys.addView(overviewViews.module);
        row.addView(battery, homeCardLp(true));
        row.addView(sys, homeCardLp(false));
        content.addView(row);
    }

    private void addOverviewDaemonCard(LinearLayout content) {
        LinearLayout card = card();
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setOnClickListener(v -> switchPage(3));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        overviewViews.daemonTitle = text("--", 17, true, primaryTextColor());
        overviewViews.daemonDetail = text("--", 13, false, secondaryTextColor());
        texts.addView(overviewViews.daemonTitle);
        texts.addView(overviewViews.daemonDetail);
        card.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
        overviewViews.daemonButton = smallAction("--", v -> {
            boolean running = RootShell.isStatsDaemonRunning();
            boolean ok = RootShell.setStatsDaemonEnabled(!running);
            Toast.makeText(this, ok ? (!running ? "已启动统计守护" : "已暂停统计守护") : "操作失败，请检查 Root/模块路径", Toast.LENGTH_SHORT).show();
            requestOverviewSnapshot(true);
        });
        card.addView(overviewViews.daemonButton, new LinearLayout.LayoutParams(dp(74), dp(42)));
        content.addView(card);
    }

    private void addHomeBottomCards(LinearLayout content, BatterySample latest, boolean moduleOk) {
        LinearLayout row = new LinearLayout(this);
        LinearLayout battery = card();
        battery.setOrientation(LinearLayout.VERTICAL);
        battery.setOnClickListener(v -> switchPage(1));
        battery.addView(text(latest == null ? "--W" : String.format(Locale.CHINA, "%.2fW", latest.isCharging() ? latest.powerW : -latest.powerW), 16, false, Color.rgb(95, 99, 104)));
        battery.addView(text(latest == null ? "--%" : latest.level + "%  " + String.format(Locale.CHINA, "%.2fV", latest.voltageV), 16, false, Color.rgb(95, 99, 104)));
        battery.addView(text(latest == null ? "--C" : String.format(Locale.CHINA, "%.1fC", latest.tempC), 16, false, Color.rgb(95, 99, 104)));
        LinearLayout sys = card();
        sys.setOrientation(LinearLayout.VERTICAL);
        sys.setOnClickListener(v -> switchPage(3));
        sys.addView(text("Android " + Build.VERSION.RELEASE, 16, false, Color.rgb(95, 99, 104)));
        sys.addView(text("已开机 " + BatteryReader.formatDuration(android.os.SystemClock.elapsedRealtime()), 16, false, Color.rgb(95, 99, 104)));
        sys.addView(text(moduleOk ? "模块运行中" : "模块未读到", 16, false, Color.rgb(22, 137, 216)));
        row.addView(battery, homeCardLp(true));
        row.addView(sys, homeCardLp(false));
        content.addView(row);
    }

    private LinearLayout.LayoutParams homeCardLp(boolean left) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
        lp.setMargins(left ? 0 : dp(5), 0, left ? dp(5) : 0, 0);
        return lp;
    }

    private float[] cpuBars(double load) {
        float[] bars = new float[8];
        float base = (float) Math.max(0.08, Math.min(1, load / 100.0));
        for (int i = 0; i < bars.length; i++) {
            bars[i] = Math.max(0.08f, Math.min(1f, base * (1f - i * 0.075f) + 0.08f));
        }
        return bars;
    }

    private double readCpuLoadPercent() {
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/stat"))) {
            String[] p = reader.readLine().trim().split("\\s+");
            long user = Long.parseLong(p[1]);
            long nice = Long.parseLong(p[2]);
            long system = Long.parseLong(p[3]);
            long idle = Long.parseLong(p[4]);
            long iowait = p.length > 5 ? Long.parseLong(p[5]) : 0;
            long irq = p.length > 6 ? Long.parseLong(p[6]) : 0;
            long softirq = p.length > 7 ? Long.parseLong(p[7]) : 0;
            long total = user + nice + system + idle + iowait + irq + softirq;
            long idleAll = idle + iowait;
            long totalDelta = total - lastCpuTotal;
            long idleDelta = idleAll - lastCpuIdle;
            lastCpuTotal = total;
            lastCpuIdle = idleAll;
            if (totalDelta <= 0) return 0;
            return Math.max(0, Math.min(100, (totalDelta - idleDelta) * 100.0 / totalDelta));
        } catch (Exception ignored) {
            return 0;
        }
    }

    private String readFirstLine(String path, String fallback) {
        try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
            String line = reader.readLine();
            if (line != null && line.trim().length() > 0) {
                return line.trim();
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }

    private GpuInfo readGpuInfo() {
        long now = System.currentTimeMillis();
        if (cachedGpuInfo != null && now - cachedGpuAt < 30000) {
            return cachedGpuInfo;
        }
        GpuInfo info = new GpuInfo();
        String freq = readFirstExistingLine(
                "/sys/class/kgsl/kgsl-3d0/gpuclk",
                "/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq",
                "/sys/devices/platform/kgsl-3d0.0/kgsl/kgsl-3d0/gpuclk",
                "/sys/class/devfreq/gpufreq/cur_freq",
                "/sys/class/devfreq/gpu/cur_freq",
                "/sys/class/devfreq/mali0/cur_freq",
                "/sys/class/misc/mali0/device/devfreq/mali0/cur_freq",
                "/sys/kernel/gpu/gpu_clock",
                "/sys/kernel/gpu/gpu_freq");
        if (freq.length() == 0) {
            freq = RootShell.run("for p in /sys/class/kgsl/kgsl-3d0/gpuclk /sys/class/kgsl/kgsl-3d0/devfreq/cur_freq /sys/class/devfreq/*gpu*/cur_freq /sys/class/devfreq/*mali*/cur_freq /sys/kernel/gpu/gpu_clock /sys/kernel/gpu/gpu_freq; do [ -r \"$p\" ] && cat \"$p\" && exit; done", 900).trim();
        }
        info.freqText = formatGpuFreq(freq);

        String load = readFirstExistingLine(
                "/sys/class/kgsl/kgsl-3d0/gpubusy",
                "/sys/class/kgsl/kgsl-3d0/devfreq/gpu_load",
                "/sys/class/devfreq/gpufreq/load",
                "/sys/class/devfreq/gpu/load",
                "/sys/class/devfreq/mali0/load",
                "/sys/kernel/gpu/gpu_busy");
        if (load.length() == 0) {
            load = RootShell.run("for p in /sys/class/kgsl/kgsl-3d0/gpubusy /sys/class/kgsl/kgsl-3d0/devfreq/gpu_load /sys/class/devfreq/*gpu*/load /sys/class/devfreq/*mali*/load /sys/kernel/gpu/gpu_busy; do [ -r \"$p\" ] && cat \"$p\" && exit; done", 900).trim();
        }
        info.loadText = formatGpuLoad(load);
        cachedGpuInfo = info;
        cachedGpuAt = now;
        return info;
    }

    private String readFirstExistingLine(String... paths) {
        for (String path : paths) {
            String value = readFirstLine(path, "");
            if (value.length() > 0) {
                return value;
            }
        }
        return "";
    }

    private String formatGpuFreq(String raw) {
        if (raw == null || raw.trim().length() == 0) {
            return "GPU 频率不可读";
        }
        String first = raw.trim().split("\\s+")[0];
        try {
            double hz = Double.parseDouble(first);
            if (hz > 100000000) return String.format(Locale.CHINA, "%.0fMHz", hz / 1000000.0);
            if (hz > 100000) return String.format(Locale.CHINA, "%.0fMHz", hz / 1000.0);
            return String.format(Locale.CHINA, "%.0fMHz", hz);
        } catch (Exception ignored) {
            return first;
        }
    }

    private String formatGpuLoad(String raw) {
        if (raw == null || raw.trim().length() == 0) {
            return "不可读";
        }
        String[] parts = raw.trim().split("[\\s,/]+");
        try {
            if (parts.length >= 2) {
                double busy = Double.parseDouble(parts[0]);
                double total = Double.parseDouble(parts[1]);
                if (total > 0) {
                    return String.format(Locale.CHINA, "%.0f%%", Math.max(0, Math.min(100, busy * 100.0 / total)));
                }
            }
            double v = Double.parseDouble(parts[0]);
            if (v > 100) v = v / 10.0;
            return String.format(Locale.CHINA, "%.0f%%", Math.max(0, Math.min(100, v)));
        } catch (Exception ignored) {
            return raw.trim();
        }
    }

    private static final class GpuInfo {
        String freqText = "GPU 频率不可读";
        String loadText = "不可读";
    }

    private MemInfo readMemInfo() {
        MemInfo info = new MemInfo();
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/meminfo"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] p = line.split("\\s+");
                if (p.length < 2) continue;
                long kb = Long.parseLong(p[1]);
                if (line.startsWith("MemTotal:")) info.totalMemKb = kb;
                else if (line.startsWith("MemAvailable:")) info.availableMemKb = kb;
                else if (line.startsWith("Cached:")) info.cachedKb = kb;
                else if (line.startsWith("SwapTotal:")) info.totalSwapKb = kb;
                else if (line.startsWith("SwapFree:")) info.freeSwapKb = kb;
            }
        } catch (Exception ignored) {
        }
        info.finish();
        return info;
    }

    private static final class MemInfo {
        long totalMemKb;
        long availableMemKb;
        long cachedKb;
        long totalSwapKb;
        long freeSwapKb;
        int memPercent;
        int swapPercent;
        String totalMemText = "--";
        String usedMemText = "--";
        String totalSwapText = "--";
        String usedSwapText = "--";
        String cachedText = "--";

        void finish() {
            long usedMem = Math.max(0, totalMemKb - availableMemKb);
            long usedSwap = Math.max(0, totalSwapKb - freeSwapKb);
            memPercent = totalMemKb > 0 ? (int) Math.min(100, usedMem * 100 / totalMemKb) : 0;
            swapPercent = totalSwapKb > 0 ? (int) Math.min(100, usedSwap * 100 / totalSwapKb) : 0;
            totalMemText = gb(totalMemKb);
            usedMemText = gb(usedMem);
            totalSwapText = gb(totalSwapKb);
            usedSwapText = gb(usedSwap);
            cachedText = gb(cachedKb);
        }

        private String gb(long kb) {
            if (kb <= 0) return "--";
            return String.format(Locale.CHINA, "%.1fGB", kb / 1024.0 / 1024.0);
        }
    }

    private void addChargePage(LinearLayout content, boolean loadNow) {
        chargeViews = new ChargePageViews();
        ChargeSnapshot snapshot = loadNow ? buildChargeSnapshot() : new ChargeSnapshot();
        addChargeStatusCard(content);
        addChargeSummaryCard(content);
        addChargeChartCard(content, "功率 / 时间", ChartView.TYPE_POWER_TIME, dp(260));
        addChargeChartCard(content, "电量 / 时间", ChartView.TYPE_LEVEL_TIME, dp(260));
        addChargeChartCard(content, "温度 / 时间", ChartView.TYPE_TEMP_TIME, dp(220));
        addChargeChartCard(content, "电流 / 电量", ChartView.TYPE_CURRENT_LEVEL, dp(240));
        applyChargeSnapshot(snapshot);
    }

    private void addChargeStatusCard(LinearLayout content) {
        LinearLayout card = card();
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        RingView ring = new RingView(this);
        chargeViews.ring = ring;
        card.addView(ring, new LinearLayout.LayoutParams(dp(160), dp(160)));
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(dp(16), 0, 0, 0);
        chargeViews.state = text("--", 20, true, primaryTextColor());
        chargeViews.power = text("--W", 16, false, secondaryTextColor());
        chargeViews.tempVoltage = text("--C    --V", 16, false, secondaryTextColor());
        chargeViews.current = text("--mA", 16, false, secondaryTextColor());
        info.addView(chargeViews.state);
        info.addView(chargeViews.power);
        info.addView(chargeViews.tempVoltage);
        info.addView(chargeViews.current);
        card.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
        content.addView(card);
    }

    private void addChargeSummaryCard(LinearLayout content) {
        LinearLayout card = card();
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        TextView icon = logoBadge();
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(42), dp(42));
        iconLp.setMargins(0, 0, dp(10), 0);
        card.addView(icon, iconLp);
        chargeViews.summaryTime = text("--", 15, false, secondaryTextColor());
        chargeViews.summaryDuration = text("--", 15, false, secondaryTextColor());
        chargeViews.summaryDelta = text("--", 15, false, secondaryTextColor());
        chargeViews.summaryPower = text("--", 15, false, secondaryTextColor());
        card.addView(chargeViews.summaryTime, new LinearLayout.LayoutParams(0, dp(48), 2));
        card.addView(chargeViews.summaryDuration, new LinearLayout.LayoutParams(0, dp(48), 1));
        card.addView(chargeViews.summaryDelta, new LinearLayout.LayoutParams(0, dp(48), 1));
        card.addView(chargeViews.summaryPower, new LinearLayout.LayoutParams(0, dp(48), 1));
        content.addView(card);
    }

    private void addChargeChartCard(LinearLayout content, String title, int type, int height) {
        LinearLayout card = card();
        card.setOrientation(LinearLayout.VERTICAL);
        card.addView(text(chartTitle(type, title), 19, true, primaryTextColor()), new LinearLayout.LayoutParams(-1, dp(42)));
        ChartView chart = new ChartView(this);
        chart.setData(new ArrayList<>(), type);
        card.addView(chart, new LinearLayout.LayoutParams(-1, height));
        content.addView(card);
        if (type == ChartView.TYPE_POWER_TIME) chargeViews.powerChart = chart;
        else if (type == ChartView.TYPE_LEVEL_TIME) chargeViews.levelChart = chart;
        else if (type == ChartView.TYPE_TEMP_TIME) chargeViews.tempChart = chart;
        else if (type == ChartView.TYPE_CURRENT_LEVEL) chargeViews.currentChart = chart;
    }

    private void addDischargePage(LinearLayout content, boolean loadNow) {
        DischargeSnapshot snapshot = loadNow ? buildDischargeSnapshot() : new DischargeSnapshot();
        dischargeViews = new DischargePageViews();
        addDischargeUsageCard(content, snapshot.samples);
        addDischargeMetricsCard(content, snapshot);
        addDischargeAppUsageCard(content, snapshot.usages);
        applyDischargeSnapshot(snapshot, true);
    }

    private void addProcessPage(LinearLayout content) {
        requestProcessLoad(false);
        LinearLayout tools = card();
        tools.setOrientation(LinearLayout.VERTICAL);
        EditText search = new EditText(this);
        search.setSingleLine(true);
        search.setHint("搜索进程名");
        search.setText(processKeyword);
        search.setTextSize(15);
        search.setHint("\u641c\u7d22\u8fdb\u7a0b\u540d");
        search.setTextColor(primaryTextColor());
        search.setHintTextColor(secondaryTextColor());
        search.setBackground(rounded(isDarkMode() ? Color.rgb(38, 43, 49) : Color.rgb(247, 248, 249), dp(10)));
        search.setPadding(dp(12), 0, dp(12), 0);
        search.setOnFocusChangeListener((v, hasFocus) -> processSearchFocused = hasFocus);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                processKeyword = s.toString();
                processVisibleLimit = 20;
                refreshProcessListOnly();
            }
            @Override public void afterTextChanged(Editable s) { }
        });
        tools.addView(search, new LinearLayout.LayoutParams(-1, dp(46)));

        LinearLayout filters = new LinearLayout(this);
        filters.setPadding(0, dp(10), 0, 0);
        TextView appFilter = smallAction(processAppOnly ? "\u5b89\u5353\u5e94\u7528" : "\u5168\u90e8\u8fdb\u7a0b", v -> {
            processKeyword = search.getText().toString();
            processAppOnly = !processAppOnly;
            processVisibleLimit = 20;
            ((TextView) v).setText(processAppOnly ? "\u5b89\u5353\u5e94\u7528" : "\u5168\u90e8\u8fdb\u7a0b");
            refreshProcessListOnly();
        });
        filters.addView(appFilter, new LinearLayout.LayoutParams(0, dp(40), 1));
        TextView autoFilter = smallAction(processAutoRefresh ? "\u81ea\u52a8\u5237\u65b0" : "\u6682\u505c\u5237\u65b0", v -> {
            processKeyword = search.getText().toString();
            processAutoRefresh = !processAutoRefresh;
            ((TextView) v).setText(processAutoRefresh ? "\u81ea\u52a8\u5237\u65b0" : "\u6682\u505c\u5237\u65b0");
        });
        filters.addView(autoFilter, new LinearLayout.LayoutParams(0, dp(40), 1));
        filters.addView(smallAction("\u5237\u65b0", v -> {
            processKeyword = search.getText().toString();
            requestProcessLoad(true);
            refreshProcessListOnly();
        }), new LinearLayout.LayoutParams(0, dp(40), 1));
        tools.addView(filters);
        content.addView(tools);

        processListParent = content;
        processListStartIndex = content.getChildCount();
        processListCard = null;
        processSkeletonVisible = false;
        appendProcessListViews(content);
    }

    private void appendProcessListViews(LinearLayout content) {
        if (processLoading && processCache.isEmpty()) {
            addLoadingCard(content, "\u6b63\u5728\u8bfb\u53d6\u8fdb\u7a0b\u4fe1\u606f...");
            addProcessSkeletonCard(content);
            processSkeletonVisible = true;
            return;
        }
        processSkeletonVisible = false;
        processListCard = createProcessListCard(filteredProcessList());
        content.addView(processListCard);
    }

    private void addProcessSkeletonCard(LinearLayout content) {
        LinearLayout card = card();
        card.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < 12; i++) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(7), 0, dp(7));
            TextView icon = text("", 1, false, secondaryTextColor());
            icon.setBackground(rounded(isDarkMode() ? Color.rgb(43, 48, 54) : Color.rgb(238, 240, 243), dp(12)));
            row.addView(icon, new LinearLayout.LayoutParams(dp(46), dp(46)));

            LinearLayout lines = new LinearLayout(this);
            lines.setOrientation(LinearLayout.VERTICAL);
            lines.setPadding(dp(10), 0, 0, 0);
            View line1 = new View(this);
            line1.setBackground(rounded(isDarkMode() ? Color.rgb(48, 54, 62) : Color.rgb(232, 235, 238), dp(4)));
            View line2 = new View(this);
            line2.setBackground(rounded(isDarkMode() ? Color.rgb(42, 47, 54) : Color.rgb(240, 242, 244), dp(4)));
            lines.addView(line1, new LinearLayout.LayoutParams(-1, dp(12)));
            LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(dp(120), dp(10));
            lp2.setMargins(0, dp(8), 0, 0);
            lines.addView(line2, lp2);
            row.addView(lines, new LinearLayout.LayoutParams(0, dp(54), 1));
            card.addView(row);
        }
        content.addView(card);
    }

    private void refreshProcessListOnly() {
        if (processListParent == null || page != 3) {
            return;
        }
        final int y = currentScroll == null ? 0 : currentScroll.getScrollY();
        if (processSkeletonVisible && processLoading && processCache.isEmpty()) {
            return;
        }
        if (processListCard != null) {
            populateProcessListCard(processListCard, filteredProcessList());
            if (currentScroll != null) {
                currentScroll.post(() -> currentScroll.scrollTo(0, y));
            }
            return;
        }
        while (processListParent.getChildCount() > processListStartIndex) {
            processListParent.removeViewAt(processListStartIndex);
        }
        appendProcessListViews(processListParent);
        if (currentScroll != null) {
            currentScroll.post(() -> currentScroll.scrollTo(0, y));
        }
    }

    private void requestProcessLoad(boolean force) {
        if (page != 3 || processLoading && !force) {
            return;
        }
        long now = System.currentTimeMillis();
        if (!force && now - lastProcessLoadAt < 900) {
            return;
        }
        processLoading = true;
        long requestId = ++processRequestId;
        new Thread(() -> {
            List<ProcessInfo> list = ProcessReader.read(this, "", 0, false);
            runOnUiThread(() -> {
                if (requestId != processRequestId) {
                    return;
                }
                processCache = list;
                processLoading = false;
                lastProcessLoadAt = System.currentTimeMillis();
                if (page == 3) {
                    refreshProcessListOnly();
                }
            });
        }, "process-reader").start();
    }

    private void addLoadingCard(LinearLayout content, String message) {
        if (processLoading && processCache.isEmpty() && processListStartIndex > 0 && content.getChildCount() > processListStartIndex) {
            return;
        }
        LinearLayout card = card();
        card.setGravity(Gravity.CENTER_VERTICAL);
        TextView spinner = text("...", 24, true, Color.rgb(22, 137, 216));
        spinner.setGravity(Gravity.CENTER);
        spinner.animate().rotationBy(360f).setDuration(800).start();
        card.addView(spinner, new LinearLayout.LayoutParams(dp(42), dp(42)));
        card.addView(text(message, 15, false, Color.rgb(120, 123, 128)), new LinearLayout.LayoutParams(0, dp(42), 1));
        content.addView(card);
    }

    private LinearLayout createProcessListCard(List<ProcessInfo> list) {
        LinearLayout card = card();
        populateProcessListCard(card, list);
        return card;
    }

    private void populateProcessListCard(LinearLayout card, List<ProcessInfo> list) {
        card.removeAllViews();
        card.setOrientation(LinearLayout.VERTICAL);
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(text("\u8fdb\u7a0b", 13, true, Color.rgb(130, 134, 138)), new LinearLayout.LayoutParams(0, dp(28), 2));
        header.addView(text("PID", 12, true, Color.rgb(130, 134, 138)), new LinearLayout.LayoutParams(0, dp(28), 1));
        header.addView(sortHeader("RES", 1), new LinearLayout.LayoutParams(0, dp(28), 1));
        header.addView(sortHeader("CPU", 0), new LinearLayout.LayoutParams(0, dp(28), 1));
        card.addView(header);

        int count = Math.min(processVisibleLimit, list.size());
        if (count == 0) {
            String reason = ProcessReader.lastError();
            if (reason.length() == 0) {
                reason = "没有读取到进程。可切换全部进程或点击刷新重试。";
            }
            card.addView(text(reason, 14, false, Color.rgb(145, 148, 153)));
        }
        for (int i = 0; i < count; i++) {
            ProcessInfo info = list.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(7), 0, dp(7));
            row.setOnClickListener(v -> showProcessDialog(info));
            row.addView(appIconView(info.packageName, info.packageName, i), new LinearLayout.LayoutParams(dp(46), dp(46)));

            LinearLayout names = new LinearLayout(this);
            names.setOrientation(LinearLayout.VERTICAL);
            names.setPadding(dp(10), 0, 0, 0);
            names.addView(text(cachedLabel(info.packageName), 14, true, Color.rgb(55, 58, 62)));
            names.addView(text(info.processName, 11, false, Color.rgb(145, 148, 153)));
            row.addView(names, new LinearLayout.LayoutParams(0, dp(54), 2));
            row.addView(text(String.valueOf(info.pid), 12, false, Color.rgb(110, 114, 118)), new LinearLayout.LayoutParams(0, dp(54), 1));
            row.addView(text(info.resText(), 12, false, Color.rgb(110, 114, 118)), new LinearLayout.LayoutParams(0, dp(54), 1));
            row.addView(text(String.format(Locale.CHINA, "%.1f%%", info.cpuPercent), 12, false, Color.rgb(95, 99, 104)), new LinearLayout.LayoutParams(0, dp(54), 1));
            card.addView(row);
        }
        if (count < list.size()) {
            TextView more = smallAction(String.format(Locale.CHINA, "\u663e\u793a\u66f4\u591a  %d/%d", count, list.size()), v -> {
                processVisibleLimit += 20;
                refreshProcessListOnly();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(42));
            lp.setMargins(0, dp(6), 0, 0);
            card.addView(more, lp);
        }
    }

    private TextView sortHeader(String label, int mode) {
        String arrow = processSortMode == mode ? (processSortAscending ? " ^" : " v") : " -";
        TextView view = text(label + arrow, 12, true, Color.rgb(130, 134, 138));
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setOnClickListener(v -> {
            press(v);
            if (processSortMode == mode) {
                processSortAscending = !processSortAscending;
            } else {
                processSortMode = mode;
                processSortAscending = false;
            }
            processVisibleLimit = 20;
            refreshProcessListOnly();
        });
        return view;
    }

    private void showProcessDialog(ProcessInfo info) {
        if (Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this)) {
            Intent intent = new Intent(this, FloatingProcessService.class);
            intent.putExtra("pid", info.pid);
            intent.putExtra("packageName", info.packageName);
            intent.putExtra("name", info.processName == null || info.processName.length() == 0 ? info.packageName : info.processName);
            startService(intent);
            return;
        }
        Toast.makeText(this, "请允许显示在其他应用上层", Toast.LENGTH_SHORT).show();
        Intent settings = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()));
        startActivity(settings);
        return;
    }

    private float[] readCoreLoads() {
        String stat = readProcStatText();
        if (stat.length() == 0) return new float[]{0.05f};
        ArrayList<Float> out = new ArrayList<>();
        try {
            String[] lines = stat.split("\\n");
            for (String line : lines) {
                if (!line.startsWith("cpu") || line.startsWith("cpu ")) continue;
                String[] p = line.trim().split("\\s+");
                int core = Integer.parseInt(p[0].substring(3));
                long user = Long.parseLong(p[1]);
                long nice = Long.parseLong(p[2]);
                long system = Long.parseLong(p[3]);
                long idle = Long.parseLong(p[4]);
                long iowait = p.length > 5 ? Long.parseLong(p[5]) : 0;
                long irq = p.length > 6 ? Long.parseLong(p[6]) : 0;
                long softirq = p.length > 7 ? Long.parseLong(p[7]) : 0;
                long total = user + nice + system + idle + iowait + irq + softirq;
                long idleAll = idle + iowait;
                long[] last = coreLast.get(core);
                float load = 0.05f;
                if (last != null) {
                    long totalDelta = Math.max(1, total - last[0]);
                    long idleDelta = idleAll - last[1];
                    load = (float) Math.max(0.03, Math.min(1, (totalDelta - idleDelta) * 1.0 / totalDelta));
                }
                coreLast.put(core, new long[]{total, idleAll});
                out.add(load);
            }
        } catch (Exception ignored) { }
        if (out.isEmpty()) return new float[]{0.05f};
        float[] values = new float[out.size()];
        for (int i = 0; i < out.size(); i++) values[i] = out.get(i);
        return values;
    }

    private String readProcStatText() {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/stat"))) {
            String line;
            while ((line = reader.readLine()) != null) sb.append(line).append('\n');
        } catch (Exception ignored) {
        }
        if (sb.length() > 0) return sb.toString();
        return RootShell.run("cat /proc/stat 2>/dev/null", 800);
    }

    private List<ProcessInfo> filteredProcessList() {
        ArrayList<ProcessInfo> out = new ArrayList<>();
        String q = processKeyword == null ? "" : processKeyword.trim().toLowerCase(Locale.US);
        for (ProcessInfo info : processCache) {
            if (processAppOnly) {
                if (!info.installedApp || info.systemApp || isSystemPackageCached(info.packageName)) {
                    continue;
                }
            }
            if (q.length() > 0
                    && !info.packageName.toLowerCase(Locale.US).contains(q)
                    && !info.processName.toLowerCase(Locale.US).contains(q)
                    && !cachedLabel(info.packageName).toLowerCase(Locale.US).contains(q)) {
                continue;
            }
            out.add(info);
        }
        if (processSortMode == 1) {
            out.sort((a, b) -> Long.compare(a.rssKb, b.rssKb));
        } else {
            out.sort((a, b) -> Double.compare(a.cpuPercent, b.cpuPercent));
        }
        if (!processSortAscending) {
            java.util.Collections.reverse(out);
        }
        return out;
    }

    private void addDischargeUsageCard(LinearLayout content, List<BatterySample> samples) {
        LinearLayout card = card();
        card.setOrientation(LinearLayout.VERTICAL);
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(text("使用过程", 18, true, Color.rgb(70, 72, 76)), new LinearLayout.LayoutParams(0, dp(36), 1));
        TextView levelText = text("--", 18, false, Color.rgb(120, 123, 128));
        head.addView(levelText);
        card.addView(head);

        ChartView chart = new ChartView(this);
        chart.setData(samples, ChartView.TYPE_USAGE);
        card.addView(chart, new LinearLayout.LayoutParams(-1, dp(250)));

        LinearLayout foot = new LinearLayout(this);
        foot.setPadding(0, dp(8), 0, 0);
        TextView powerText = text("--W", 14, false, Color.rgb(130, 134, 138));
        TextView tempText = text("--C", 14, false, Color.rgb(130, 134, 138));
        TextView voltageText = text("--V", 14, false, Color.rgb(130, 134, 138));
        TextView stateText = text("--", 14, false, Color.rgb(130, 134, 138));
        foot.addView(powerText, new LinearLayout.LayoutParams(0, dp(36), 1));
        foot.addView(tempText, new LinearLayout.LayoutParams(0, dp(36), 1));
        foot.addView(voltageText, new LinearLayout.LayoutParams(0, dp(36), 1));
        foot.addView(stateText, new LinearLayout.LayoutParams(0, dp(36), 1));
        card.addView(foot);
        content.addView(card);

        dischargeViews.levelText = levelText;
        dischargeViews.usageChart = chart;
        dischargeViews.powerText = powerText;
        dischargeViews.tempText = tempText;
        dischargeViews.voltageText = voltageText;
        dischargeViews.stateText = stateText;
    }

    private void addDischargeMetricsCard(LinearLayout content, DischargeSnapshot snapshot) {
        LinearLayout card = card();
        card.setOrientation(LinearLayout.VERTICAL);

        LinearLayout top = metricRow();
        dischargeViews.avgValue = metricValue("--");
        dischargeViews.usedValue = metricValue("--");
        dischargeViews.screenValue = metricValue("--");
        top.addView(metric(dischargeViews.avgValue, "平均功耗"), new LinearLayout.LayoutParams(0, dp(64), 1));
        top.addView(metric(dischargeViews.usedValue, "已使用"), new LinearLayout.LayoutParams(0, dp(64), 1));
        top.addView(metric(dischargeViews.screenValue, "亮屏"), new LinearLayout.LayoutParams(0, dp(64), 1));
        card.addView(top);

        View divider = new View(this);
        divider.setBackgroundColor(isDarkMode() ? Color.rgb(43, 49, 56) : Color.rgb(236, 238, 241));
        LinearLayout.LayoutParams dividerLp = new LinearLayout.LayoutParams(-1, 1);
        dividerLp.setMargins(0, dp(4), 0, dp(4));
        card.addView(divider, dividerLp);

        LinearLayout bottom = metricRow();
        dischargeViews.lifeValue = metricValue("--");
        dischargeViews.screenLifeValue = metricValue("--");
        bottom.addView(metric(dischargeViews.lifeValue, "\u8fd8\u80fd\u4f7f\u7528"), new LinearLayout.LayoutParams(0, dp(62), 1));
        bottom.addView(metric(dischargeViews.screenLifeValue, "\u4eae\u5c4f\u53ef\u7528"), new LinearLayout.LayoutParams(0, dp(62), 1));
        card.addView(bottom);
        content.addView(card);
    }

    private void addDischargeAppUsageCard(LinearLayout content, List<StatsDatabase.AppUsage> usages) {
        LinearLayout card = card();
        card.setOrientation(LinearLayout.VERTICAL);
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.addView(logoBadge(), new LinearLayout.LayoutParams(dp(38), dp(38)));
        TextView cardTitle = text("使用场景", 19, true, Color.rgb(70, 72, 76));
        cardTitle.setPadding(dp(10), 0, 0, 0);
        titleRow.addView(cardTitle, new LinearLayout.LayoutParams(0, dp(44), 1));
        card.addView(titleRow);
        titleRow.setVisibility(View.GONE);

        LinearLayout filterRow = new LinearLayout(this);
        filterRow.setGravity(Gravity.CENTER_VERTICAL);
        filterRow.setPadding(0, 0, 0, dp(8));
        filterRow.addView(smallAction(dischargeShowSystemApps ? "\u542b\u7cfb\u7edf" : "\u53ea\u770b\u5e94\u7528", v -> {
            dischargeShowSystemApps = !dischargeShowSystemApps;
            requestDischargeSnapshot(true);
        }), new LinearLayout.LayoutParams(dp(88), dp(38)));
        TextView sortButton = smallAction(sortName(), null);
        sortButton.setOnClickListener(v -> showDischargeSortMenu(sortButton));
        LinearLayout.LayoutParams sortLp = new LinearLayout.LayoutParams(0, dp(38), 1);
        sortLp.setMargins(dp(8), 0, dp(8), 0);
        filterRow.addView(sortButton, sortLp);
        filterRow.addView(smallAction(dischargeSortAscending ? "\u4f4e\u5230\u9ad8" : "\u9ad8\u5230\u4f4e", v -> {
            dischargeSortAscending = !dischargeSortAscending;
            requestDischargeSnapshot(true);
        }), new LinearLayout.LayoutParams(dp(78), dp(38)));
        card.addView(filterRow);

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        card.addView(list);
        content.addView(card);
        dischargeViews.appList = list;
        updateAppUsageList(usages);
    }

    private String sortName() {
        if (dischargeSortMode == 1) return "\u6309\u65f6\u957f";
        return "\u6309\u8017\u7535";
    }

    private void showDischargeSortMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 0, 0, "\u6309\u8017\u7535\u6392\u5e8f");
        menu.getMenu().add(0, 1, 1, "\u6309\u65f6\u957f\u6392\u5e8f");
        menu.setOnMenuItemClickListener(item -> {
            dischargeSortMode = item.getItemId();
            requestDischargeSnapshot(true);
            return true;
        });
        menu.show();
    }

    private List<StatsDatabase.AppUsage> filteredAppUsage() {
        ArrayList<StatsDatabase.AppUsage> out = new ArrayList<>();
        for (StatsDatabase.AppUsage usage : database.appUsageForLatestDischarge(this)) {
            if (!dischargeShowSystemApps && isSystemPackageCached(usage.pkg)) {
                continue;
            }
            out.add(usage);
        }
        if (dischargeSortMode == 1) {
            out.sort((a, b) -> Long.compare(a.durationMs, b.durationMs));
        } else {
            out.sort((a, b) -> Double.compare(a.energyWh, b.energyWh));
        }
        if (!dischargeSortAscending) {
            java.util.Collections.reverse(out);
        }
        return out;
    }

    private void requestOverviewSnapshot(boolean force) {
        if (page != 0 || overviewViews == null) {
            return;
        }
        if (overviewSnapshotLoading && !force) {
            return;
        }
        overviewSnapshotLoading = true;
        long requestId = ++overviewSnapshotRequestId;
        new Thread(() -> {
            boolean moduleOk = ModuleDataImporter.importRecent(database);
            insertLocalBatterySampleIfNeeded();
            OverviewSnapshot snapshot = buildOverviewSnapshot(moduleOk);
            runOnUiThread(() -> {
                if (requestId != overviewSnapshotRequestId) {
                    return;
                }
                overviewSnapshotLoading = false;
                if (page == 0 && overviewViews != null) {
                    moduleOkCached = snapshot.moduleOk;
                    applyOverviewSnapshot(snapshot);
                }
            });
        }, "overview-snapshot").start();
    }

    private OverviewSnapshot buildOverviewSnapshot(boolean moduleOk) {
        OverviewSnapshot snapshot = new OverviewSnapshot();
        snapshot.latest = database.latest();
        snapshot.mem = readMemInfo();
        snapshot.gpu = readGpuInfo();
        snapshot.cpuLoad = readCpuLoadPercent();
        snapshot.coreLoads = readCoreLoads();
        snapshot.coreInfos = readCoreInfos(snapshot.coreLoads);
        snapshot.cpuTemp = readCpuTempText();
        snapshot.topProcesses = readOverviewTopProcesses();
        snapshot.daemonRunning = RootShell.isStatsDaemonRunning();
        snapshot.moduleOk = moduleOk || snapshot.daemonRunning || ModuleDataImporter.hasReadableData();
        return snapshot;
    }

    private void applyOverviewSnapshot(OverviewSnapshot snapshot) {
        if (overviewViews == null || snapshot == null) {
            return;
        }
        MemInfo mem = snapshot.mem == null ? new MemInfo() : snapshot.mem;
        if (overviewViews.memRing != null) overviewViews.memRing.setLevel(mem.memPercent);
        setTextIfChanged(overviewViews.memPhysical, "物理内存  " + mem.memPercent + "%  " + mem.usedMemText + " / " + mem.totalMemText);
        setTextIfChanged(overviewViews.memSwap, "交换分区  " + mem.swapPercent + "%  " + mem.usedSwapText + " / " + mem.totalSwapText);
        setTextIfChanged(overviewViews.memCached, "Used " + mem.memPercent + "%   Cached " + mem.cachedText);
        GpuInfo gpu = snapshot.gpu == null ? new GpuInfo() : snapshot.gpu;
        setTextIfChanged(overviewViews.gpuFreq, gpu.freqText);
        setTextIfChanged(overviewViews.gpuLoad, "负载: " + gpu.loadText);
        if (overviewViews.cpuBars != null) overviewViews.cpuBars.setValues(cpuBars(snapshot.cpuLoad));
        setTextIfChanged(overviewViews.cpuModel, Build.HARDWARE + " (" + snapshot.coreLoads.length + " Cores)");
        setTextIfChanged(overviewViews.cpuLoad, String.format(Locale.CHINA, "负载: %.0f%%", snapshot.cpuLoad));
        setTextIfChanged(overviewViews.cpuTemp, snapshot.cpuTemp);
        if (overviewViews.coreGrid != null) renderOverviewCoreGrid(overviewViews.coreGrid, snapshot.coreInfos);
        if (overviewViews.cpuTopList != null) renderOverviewTopProcesses(overviewViews.cpuTopList, snapshot.topProcesses);
        BatterySample latest = overviewLiveBatterySample == null ? snapshot.latest : overviewLiveBatterySample;
        applyOverviewBatterySample(latest);
        setTextIfChanged(overviewViews.uptime, "已开机 " + BatteryReader.formatDuration(android.os.SystemClock.elapsedRealtime()));
        setTextIfChanged(overviewViews.module, snapshot.moduleOk ? "模块运行中" : "模块未读到");
        setTextIfChanged(overviewViews.daemonTitle, snapshot.daemonRunning ? "统计守护运行中" : "统计守护已暂停");
        setTextIfChanged(overviewViews.daemonDetail, snapshot.daemonRunning ? "需要兼容敏感应用时可临时关闭，无需重启。" : "关闭期间不会记录新统计，点击按钮可立即恢复。");
        setTextIfChanged(overviewViews.daemonButton, snapshot.daemonRunning ? "暂停" : "启动");
    }

    private void requestOverviewBatterySnapshot() {
        if (overviewBatteryLoading || overviewViews == null || page != 0) {
            return;
        }
        overviewBatteryLoading = true;
        overviewBatteryExecutor.execute(() -> {
            BatterySample sample = null;
            try {
                sample = new BatteryReader(this).readBatteryOnly();
            } catch (Exception ignored) {
            }
            BatterySample result = isUsableBatterySample(sample) ? sample
                    : (overviewLiveBatterySample == null ? database.latest() : overviewLiveBatterySample);
            runOnUiThread(() -> {
                overviewBatteryLoading = false;
                if (page == 0 && overviewViews != null) {
                    if (result != null) {
                        overviewLiveBatterySample = result;
                    }
                    applyOverviewBatterySample(result);
                }
            });
        });
    }

    private boolean isUsableBatterySample(BatterySample sample) {
        return sample != null && (sample.voltageV > 0 || sample.level > 0 || sample.tempC > 0);
    }

    private void applyOverviewBatterySample(BatterySample latest) {
        setTextIfChanged(overviewViews.batteryPower, latest == null ? "--W" : String.format(Locale.CHINA, "%.2fW", latest.isCharging() ? latest.powerW : -latest.powerW));
        setTextIfChanged(overviewViews.batteryLevel, latest == null ? "--%" : latest.level + "%  " + String.format(Locale.CHINA, "%.2fV", latest.voltageV));
        setTextIfChanged(overviewViews.batteryTemp, latest == null ? "--C" : String.format(Locale.CHINA, "%.1fC", latest.tempC));
    }

    private CoreInfo[] readCoreInfos(float[] loads) {
        int count = loads == null ? 0 : loads.length;
        CoreInfo[] infos = new CoreInfo[count];
        for (int i = 0; i < count; i++) {
            CoreInfo info = new CoreInfo();
            info.index = i;
            info.load = loads[i];
            String base = "/sys/devices/system/cpu/cpu" + i + "/cpufreq/";
            info.freq = formatCpuFreq(readFirstLine(base + "scaling_cur_freq", ""));
            String min = formatCpuFreq(readFirstLine(base + "cpuinfo_min_freq", ""));
            String max = formatCpuFreq(readFirstLine(base + "cpuinfo_max_freq", ""));
            info.range = "--".equals(min) || "--".equals(max) ? "--" : min.replace("MHz", "") + "-" + max;
            infos[i] = info;
        }
        return infos;
    }

    private String formatCpuFreq(String raw) {
        if (raw == null || raw.trim().length() == 0) {
            return "--";
        }
        try {
            long khz = Long.parseLong(raw.trim().split("\\s+")[0]);
            if (khz <= 0) return "--";
            return String.format(Locale.CHINA, "%dMHz", Math.round(khz / 1000.0));
        } catch (Exception ignored) {
            return "--";
        }
    }

    private String readCpuTempText() {
        try {
            File root = new File("/sys/class/thermal");
            File[] zones = root.listFiles();
            if (zones == null) return "";
            double best = -1;
            for (File zone : zones) {
                String name = zone.getName();
                if (!name.startsWith("thermal_zone")) continue;
                String type = readFirstLine(new File(zone, "type").getAbsolutePath(), "").toLowerCase(Locale.US);
                if (!(type.contains("cpu") || type.contains("soc") || type.contains("ap") || type.contains("cluster"))) {
                    continue;
                }
                String raw = readFirstLine(new File(zone, "temp").getAbsolutePath(), "");
                if (raw.length() == 0) continue;
                double temp = Double.parseDouble(raw.trim());
                if (temp > 1000) temp /= 1000.0;
                if (temp > 0 && temp < 130) best = Math.max(best, temp);
            }
            return best > 0 ? String.format(Locale.CHINA, "%.1f°C", best) : "";
        } catch (Exception ignored) {
            return "";
        }
    }

    private List<ProcessInfo> readOverviewTopProcesses() {
        long now = System.currentTimeMillis();
        if (!overviewTopProcessCache.isEmpty() && now - lastOverviewTopProcessAt < 4000) {
            return new ArrayList<>(overviewTopProcessCache);
        }
        List<ProcessInfo> list = ProcessReader.read(this, "", 0, false);
        ArrayList<ProcessInfo> out = new ArrayList<>();
        for (ProcessInfo info : list) {
            if (info.cpuPercent <= 0) continue;
            out.add(info);
        }
        out.sort((a, b) -> Double.compare(b.cpuPercent, a.cpuPercent));
        while (out.size() > 5) {
            out.remove(out.size() - 1);
        }
        overviewTopProcessCache = new ArrayList<>(out);
        lastOverviewTopProcessAt = now;
        return out;
    }

    private void renderOverviewTopProcesses(LinearLayout list, List<ProcessInfo> processes) {
        list.removeAllViews();
        if (processes == null || processes.isEmpty()) {
            list.addView(text("暂无 CPU 进程", 12, false, secondaryTextColor()), new LinearLayout.LayoutParams(-1, dp(24)));
            return;
        }
        for (ProcessInfo info : processes) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView name = text(shortProcessName(info), 11, true, primaryTextColor());
            TextView cpu = text(String.format(Locale.CHINA, "%.1f%%", info.cpuPercent), 11, false, secondaryTextColor());
            cpu.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
            row.addView(name, new LinearLayout.LayoutParams(0, dp(20), 1));
            row.addView(cpu, new LinearLayout.LayoutParams(dp(48), dp(20)));
            list.addView(row);
        }
    }

    private String shortProcessName(ProcessInfo info) {
        String value = info.processName == null || info.processName.length() == 0 ? info.packageName : info.processName;
        int colon = value.lastIndexOf(':');
        if (colon >= 0 && colon < value.length() - 1) value = value.substring(colon + 1);
        int dot = value.lastIndexOf('.');
        if (dot >= 0 && dot < value.length() - 1) value = value.substring(dot + 1);
        return value.length() > 18 ? value.substring(0, 18) : value;
    }

    private void renderOverviewCoreGrid(LinearLayout grid, CoreInfo[] values) {
        grid.removeAllViews();
        if (values == null || values.length == 0) {
            return;
        }
        int columns = values.length > 4 ? 4 : Math.max(1, values.length);
        for (int rowIndex = 0; rowIndex * columns < values.length; rowIndex++) {
            LinearLayout row = new LinearLayout(this);
            for (int col = 0; col < columns; col++) {
                int idx = rowIndex * columns + col;
                if (idx < values.length) {
                    row.addView(coreInfoBox(values[idx]), new LinearLayout.LayoutParams(0, dp(72), 1));
                } else {
                    row.addView(new View(this), new LinearLayout.LayoutParams(0, dp(72), 1));
                }
            }
            grid.addView(row);
        }
    }

    private LinearLayout coreInfoBox(CoreInfo info) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        MiniBarsView bars = new MiniBarsView(this);
        bars.setValues(new float[]{Math.max(0.03f, info.load)});
        box.addView(bars, new LinearLayout.LayoutParams(dp(42), dp(10)));
        box.addView(text(Math.round(info.load * 100) + "%", 10, true, primaryTextColor()));
        box.addView(text(info.freq, 11, true, primaryTextColor()));
        TextView range = text(info.range, 8, false, secondaryTextColor());
        range.setSingleLine(true);
        box.addView(range);
        return box;
    }

    private void requestChargeSnapshot(boolean force) {
        if (page != 2 || chargeViews == null) {
            return;
        }
        if (chargeSnapshotLoading && !force) {
            return;
        }
        chargeSnapshotLoading = true;
        long requestId = ++chargeSnapshotRequestId;
        new Thread(() -> {
            ModuleDataImporter.importRecent(database);
            insertLocalBatterySampleIfNeeded();
            ChargeSnapshot snapshot = buildChargeSnapshot();
            runOnUiThread(() -> {
                if (requestId != chargeSnapshotRequestId) {
                    return;
                }
                chargeSnapshotLoading = false;
                if (page != 2 || chargeViews == null) {
                    return;
                }
                if (pageScrollActive) {
                    pendingChargeSnapshot = snapshot;
                } else {
                    applyChargeSnapshot(snapshot);
                }
            });
        }, "charge-snapshot").start();
    }

    private ChargeSnapshot buildChargeSnapshot() {
        ChargeSnapshot snapshot = new ChargeSnapshot();
        snapshot.samples = database.latestProcess(true);
        snapshot.latest = database.latest();
        snapshot.avgPower = avgPower(snapshot.samples);
        snapshot.durationMs = duration(snapshot.samples);
        if (snapshot.samples.size() >= 2) {
            BatterySample first = snapshot.samples.get(0);
            BatterySample last = snapshot.samples.get(snapshot.samples.size() - 1);
            snapshot.time = formatClock(first.timeMs) + " ~ " + formatClock(last.timeMs);
            snapshot.delta = "+" + (last.level - first.level) + "%";
        }
        return snapshot;
    }

    private void applyChargeSnapshot(ChargeSnapshot snapshot) {
        if (chargeViews == null || snapshot == null) {
            return;
        }
        BatterySample latest = snapshot.latest;
        if (chargeViews.ring != null) chargeViews.ring.setLevel(latest == null ? 0 : latest.level);
        setTextIfChanged(chargeViews.state, latest == null ? "等待采样" : (latest.isCharging() ? "充电中" : "未充电"));
        setTextIfChanged(chargeViews.power, latest == null ? "--W" : String.format(Locale.CHINA, "%.2fW", latest.isCharging() ? latest.powerW : -latest.powerW));
        setTextIfChanged(chargeViews.tempVoltage, latest == null ? "--C    --V" : String.format(Locale.CHINA, "%.1fC    %.3fV", latest.tempC, latest.voltageV));
        setTextIfChanged(chargeViews.current, latest == null ? "--mA" : String.format(Locale.CHINA, "%.0fmA", Math.abs(latest.currentA * 1000)));
        setTextIfChanged(chargeViews.summaryTime, snapshot.time);
        setTextIfChanged(chargeViews.summaryDuration, BatteryReader.formatDuration(snapshot.durationMs));
        setTextIfChanged(chargeViews.summaryDelta, snapshot.delta);
        setTextIfChanged(chargeViews.summaryPower, String.format(Locale.CHINA, "%.2fW", snapshot.avgPower));
        List<BatterySample> chartSamples = downsample(snapshot.samples, 220);
        if (chargeViews.powerChart != null) chargeViews.powerChart.setData(chartSamples, ChartView.TYPE_POWER_TIME);
        if (chargeViews.levelChart != null) chargeViews.levelChart.setData(chartSamples, ChartView.TYPE_LEVEL_TIME);
        if (chargeViews.tempChart != null) chargeViews.tempChart.setData(chartSamples, ChartView.TYPE_TEMP_TIME);
        if (chargeViews.currentChart != null) chargeViews.currentChart.setData(chartSamples, ChartView.TYPE_CURRENT_LEVEL);
    }

    private void requestDischargeSnapshot(boolean force) {
        if (page != 1 || dischargeViews == null) {
            return;
        }
        if (dischargeSnapshotLoading && !force) {
            return;
        }
        dischargeSnapshotLoading = true;
        long requestId = ++dischargeSnapshotRequestId;
        new Thread(() -> {
            ModuleDataImporter.importRecent(database);
            insertLocalBatterySampleIfNeeded();
            DischargeSnapshot snapshot = buildDischargeSnapshot();
            runOnUiThread(() -> {
                if (requestId != dischargeSnapshotRequestId) {
                    return;
                }
                dischargeSnapshotLoading = false;
                if (page != 1 || dischargeViews == null) {
                    return;
                }
                if (pageScrollActive) {
                    pendingDischargeSnapshot = snapshot;
                } else {
                    applyDischargeSnapshot(snapshot, false);
                }
            });
        }, "discharge-snapshot").start();
    }

    private DischargeSnapshot buildDischargeSnapshot() {
        DischargeSnapshot snapshot = new DischargeSnapshot();
        snapshot.samples = database.latestProcess(false);
        snapshot.latest = snapshot.samples.isEmpty() ? database.latest() : snapshot.samples.get(snapshot.samples.size() - 1);
        snapshot.avgPower = avgPower(snapshot.samples);
        snapshot.usedMs = duration(snapshot.samples);
        snapshot.screenOnMs = screenOnDuration(snapshot.samples);
        snapshot.life = "--";
        snapshot.screenLife = "--";
        if (snapshot.latest != null && snapshot.avgPower > 0.1) {
            double whLeft = snapshot.latest.voltageV * batteryCapacityAh() * snapshot.latest.level / 100.0;
            snapshot.life = BatteryReader.formatDuration((long) (whLeft / snapshot.avgPower * 3600000.0));
            double screenAvg = Math.max(screenOnAvgPower(snapshot.samples), snapshot.avgPower);
            snapshot.screenLife = BatteryReader.formatDuration((long) (whLeft / screenAvg * 3600000.0));
        }
        snapshot.usages = filteredAppUsage();
        return snapshot;
    }

    private void applyDischargeSnapshot(DischargeSnapshot snapshot, boolean initial) {
        if (dischargeViews == null || snapshot == null) {
            return;
        }
        BatterySample latest = snapshot.latest;
        setTextIfChanged(dischargeViews.levelText, latest == null ? "--" : latest.level + "%");
        setTextIfChanged(dischargeViews.powerText, latest == null ? "--W" : String.format(Locale.CHINA, "%.2fW", sanePower(latest)));
        setTextIfChanged(dischargeViews.tempText, latest == null ? "--C" : String.format(Locale.CHINA, "%.1fC", latest.tempC));
        setTextIfChanged(dischargeViews.voltageText, latest == null ? "--V" : String.format(Locale.CHINA, "%.3fV", latest.voltageV));
        setTextIfChanged(dischargeViews.stateText, latest != null && latest.isCharging() ? "充电" : "未充电");
        setTextIfChanged(dischargeViews.avgValue, String.format(Locale.CHINA, "%.2fW", snapshot.avgPower));
        setTextIfChanged(dischargeViews.usedValue, BatteryReader.formatDuration(snapshot.usedMs));
        setTextIfChanged(dischargeViews.screenValue, BatteryReader.formatDuration(snapshot.screenOnMs));
        setTextIfChanged(dischargeViews.lifeValue, snapshot.life);
        setTextIfChanged(dischargeViews.screenLifeValue, snapshot.screenLife);
        if (dischargeViews.usageChart != null) {
            dischargeViews.usageChart.setData(downsample(snapshot.samples, 220), ChartView.TYPE_USAGE);
        }
        updateAppUsageList(snapshot.usages);
    }

    private void updateAppUsageList(List<StatsDatabase.AppUsage> usages) {
        if (dischargeViews == null || dischargeViews.appList == null) {
            return;
        }
        LinearLayout list = dischargeViews.appList;
        list.removeAllViews();
        if (usages == null || usages.isEmpty()) {
            TextView empty = text("暂无应用耗电记录。模块运行一段时间后会显示前台和后台耗电。", 14, false, Color.rgb(145, 148, 153));
            list.addView(empty, new LinearLayout.LayoutParams(-1, dp(44)));
            return;
        }
        for (int i = 0; i < usages.size(); i++) {
            list.addView(appUsageRow(usages.get(i), i));
        }
    }

    private View appUsageRow(StatsDatabase.AppUsage usage, int index) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(8), 0, dp(8));
        row.addView(appIconView(usage.pkg, usage.label, index), new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(12), 0, 0, 0);
        texts.addView(text(usage.label, 16, true, Color.rgb(65, 68, 72)));
        texts.addView(text(usage.subtitle(), 13, false, Color.rgb(145, 148, 153)));
        texts.addView(text(usage.detail(), 12, false, Color.rgb(165, 168, 173)));
        row.addView(texts, new LinearLayout.LayoutParams(0, dp(74), 1));
        row.addView(text(BatteryReader.formatDuration(usage.durationMs), 14, false, Color.rgb(120, 123, 128)));
        return row;
    }

    private List<BatterySample> downsample(List<BatterySample> samples, int maxPoints) {
        if (samples == null || samples.size() <= maxPoints || maxPoints < 8) {
            return samples == null ? new ArrayList<>() : samples;
        }
        ArrayList<BatterySample> out = new ArrayList<>(maxPoints);
        int lastIndex = samples.size() - 1;
        out.add(samples.get(0));
        for (int i = 1; i < maxPoints - 1; i++) {
            int index = Math.round(i * lastIndex / (float) (maxPoints - 1));
            BatterySample sample = samples.get(index);
            if (out.get(out.size() - 1) != sample) {
                out.add(sample);
            }
        }
        if (out.get(out.size() - 1) != samples.get(lastIndex)) {
            out.add(samples.get(lastIndex));
        }
        return out;
    }

    private void setTextIfChanged(TextView view, String value) {
        if (view == null) {
            return;
        }
        if (!String.valueOf(view.getText()).equals(value)) {
            view.setText(value);
        }
    }

    private static final class DischargeSnapshot {
        List<BatterySample> samples = new ArrayList<>();
        List<StatsDatabase.AppUsage> usages = new ArrayList<>();
        BatterySample latest;
        double avgPower;
        long usedMs;
        long screenOnMs;
        String life = "--";
        String screenLife = "--";
    }

    private static final class OverviewSnapshot {
        BatterySample latest;
        boolean moduleOk;
        boolean daemonRunning;
        MemInfo mem;
        GpuInfo gpu;
        double cpuLoad;
        float[] coreLoads = new float[]{0.05f};
        CoreInfo[] coreInfos = new CoreInfo[0];
        String cpuTemp = "";
        List<ProcessInfo> topProcesses = new ArrayList<>();
    }

    private static final class CoreInfo {
        int index;
        float load;
        String freq = "--";
        String range = "--";
    }

    private static final class ChargeSnapshot {
        List<BatterySample> samples = new ArrayList<>();
        BatterySample latest;
        double avgPower;
        long durationMs;
        String time = "--";
        String delta = "--";
    }

    private static final class OverviewPageViews {
        RingView memRing;
        TextView memPhysical;
        TextView memSwap;
        TextView memCached;
        TextView gpuFreq;
        TextView gpuLoad;
        TextView gpuDevice;
        LinearLayout cpuTopList;
        MiniBarsView cpuBars;
        TextView cpuModel;
        TextView cpuLoad;
        TextView cpuTemp;
        LinearLayout coreGrid;
        TextView batteryPower;
        TextView batteryLevel;
        TextView batteryTemp;
        TextView uptime;
        TextView module;
        TextView daemonTitle;
        TextView daemonDetail;
        TextView daemonButton;
    }

    private static final class ChargePageViews {
        RingView ring;
        TextView state;
        TextView power;
        TextView tempVoltage;
        TextView current;
        TextView summaryTime;
        TextView summaryDuration;
        TextView summaryDelta;
        TextView summaryPower;
        ChartView powerChart;
        ChartView levelChart;
        ChartView tempChart;
        ChartView currentChart;
    }

    private static final class DischargePageViews {
        TextView levelText;
        ChartView usageChart;
        TextView powerText;
        TextView tempText;
        TextView voltageText;
        TextView stateText;
        TextView avgValue;
        TextView usedValue;
        TextView screenValue;
        TextView lifeValue;
        TextView screenLifeValue;
        LinearLayout appList;
    }

    private String cachedLabel(String pkg) {
        if (pkg == null || pkg.length() == 0) {
            return "";
        }
        String cached = labelCache.get(pkg);
        if (cached != null) {
            return cached;
        }
        String label = ProcessReader.label(this, pkg);
        labelCache.put(pkg, label);
        return label;
    }

    private boolean isSystemPackageCached(String pkg) {
        if (pkg == null || pkg.length() == 0) {
            return true;
        }
        Boolean cached = systemPackageCache.get(pkg);
        if (cached != null) {
            return cached;
        }
        boolean system = isSystemPackage(pkg);
        systemPackageCache.put(pkg, system);
        return system;
    }

    private boolean isSystemPackage(String pkg) {
        if (pkg == null || pkg.length() == 0) {
            return true;
        }
        if (pkg.equals("android")
                || pkg.startsWith("android.")
                || pkg.startsWith("com.android.")
                || pkg.startsWith("com.google.android.gms")
                || pkg.startsWith("com.google.android.gsf")
                || pkg.equals("com.google.android.packageinstaller")
                || pkg.equals("com.google.android.permissioncontroller")
                || pkg.equals("com.google.android.webview")
                || pkg.startsWith("com.qualcomm.")
                || pkg.startsWith("vendor.")
                || pkg.startsWith("com.qti.")
                || pkg.startsWith("com.mediatek.")
                || pkg.startsWith("com.coloros.")
                || pkg.startsWith("com.oplus.")
                || pkg.startsWith("com.heytap.")
                || pkg.startsWith("com.oneplus.")
                || pkg.startsWith("com.realme.")
                || pkg.startsWith("com.huawei.")
                || pkg.startsWith("com.hihonor.")
                || pkg.startsWith("com.xiaomi.")
                || pkg.startsWith("com.miui.")
                || pkg.startsWith("com.vivo.")
                || pkg.startsWith("com.iqoo.")
                || pkg.startsWith("com.bbk.")
                || pkg.startsWith("com.lenovo.")
                || pkg.startsWith("com.motorola.")
                || pkg.startsWith("com.samsung.")
                || pkg.startsWith("com.sec.")
                || pkg.startsWith("com.sonyericsson.")
                || pkg.startsWith("com.sonymobile.")
                || pkg.startsWith("com.nvidia.")
                || pkg.startsWith("com.lge.")
                || pkg.startsWith("com.htc.")
                || pkg.startsWith("org.codeaurora.")
                || pkg.contains(".ims")
                || pkg.contains(".telephony")
                || pkg.contains(".carrier")
                || pkg.contains(".provider")
                || pkg.contains(".service")) {
            return true;
        }
        try {
            ApplicationInfo info = getPackageManager().getApplicationInfo(pkg, 0);
            boolean system = (info.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
            boolean launchable = getPackageManager().getLaunchIntentForPackage(pkg) != null;
            return system || !launchable;
        } catch (Exception ignored) {
            return true;
        }
    }

    private String chartTitle(int type, String fallback) {
        if (type == ChartView.TYPE_POWER_TIME) return "\u529f\u7387 / \u65f6\u95f4";
        if (type == ChartView.TYPE_LEVEL_TIME) return "\u7535\u91cf / \u65f6\u95f4";
        if (type == ChartView.TYPE_TEMP_TIME) return "\u6e29\u5ea6 / \u65f6\u95f4";
        if (type == ChartView.TYPE_CURRENT_LEVEL) return "\u7535\u6d41 / \u7535\u91cf";
        if (type == ChartView.TYPE_USAGE) return "\u4f7f\u7528\u8fc7\u7a0b";
        return fallback;
    }

    private TextView smallAction(String label, View.OnClickListener listener) {
        TextView view = text(label, 13, true, accentColor());
        view.setGravity(Gravity.CENTER);
        view.setBackground(rounded(chipColor(), dp(10)));
        view.setOnClickListener(v -> {
            press(v);
            if (listener != null) {
                listener.onClick(v);
            }
        });
        return view;
    }

    private LinearLayout metric(TextView value, String label) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.addView(value);
        box.addView(text(label, 13, false, Color.rgb(145, 148, 153)));
        return box;
    }

    private TextView metricValue(String value) {
        return text(value, 20, false, Color.rgb(22, 137, 216));
    }

    private LinearLayout metricRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setPadding(dp(18), dp(14), dp(18), dp(14));
        card.setBackground(rounded(cardColor(), dp(14)));
        card.setElevation(dp(1));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, 0, 0, dp(14));
        card.setLayoutParams(lp);
        return card;
    }

    private void press(View view) {
        view.animate().cancel();
        view.animate()
                .scaleX(0.96f)
                .scaleY(0.96f)
                .setDuration(70)
                .withEndAction(() -> view.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(130)
                        .setInterpolator(new OvershootInterpolator(0.55f))
                        .start())
                .start();
    }

    private TextView text(String value, int sp, boolean bold, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(adaptTextColor(color));
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setIncludeFontPadding(true);
        if (bold) view.setTypeface(Typeface.DEFAULT_BOLD);
        return view;
    }

    private double batteryCapacityAh() {
        try {
            Object profile = Class.forName("com.android.internal.os.PowerProfile")
                    .getConstructor(Context.class)
                    .newInstance(this);
            double mah = (Double) Class.forName("com.android.internal.os.PowerProfile")
                    .getMethod("getBatteryCapacity")
                    .invoke(profile);
            if (mah > 0) return mah / 1000.0;
        } catch (Exception ignored) {
        }
        BatteryManager bm = (BatteryManager) getSystemService(BATTERY_SERVICE);
        if (bm != null) {
            long uah = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER);
            BatterySample latest = database.latest();
            if (uah > 0 && latest != null && latest.level > 0) {
                return (uah / 1000000.0) / (latest.level / 100.0);
            }
        }
        return 4.2;
    }

    private long duration(List<BatterySample> samples) {
        return samples.size() < 2 ? 0 : samples.get(samples.size() - 1).timeMs - samples.get(0).timeMs;
    }

    private double totalWh(List<BatterySample> samples) {
        double total = 0;
        for (int i = 1; i < samples.size(); i++) {
            BatterySample prev = samples.get(i - 1);
            BatterySample cur = samples.get(i);
            long dt = Math.max(0, cur.timeMs - prev.timeMs);
            if (dt > 10 * 60 * 1000L) {
                dt = 10 * 60 * 1000L;
            }
            total += sanePower(prev) * dt / 3600000.0;
        }
        return total;
    }

    private double avgPower(List<BatterySample> samples) {
        long d = duration(samples);
        if (d <= 0) {
            return 0;
        }
        double integrated = totalWh(samples) * 3600000.0 / d;
        double levelBased = levelBasedAvgPower(samples);
        if (levelBased > 0 && integrated > levelBased * 2.5) {
            return levelBased;
        }
        return Math.min(integrated, 25.0);
    }

    private double sanePower(BatterySample sample) {
        if (sample == null || Double.isNaN(sample.powerW) || Double.isInfinite(sample.powerW)) {
            return 0;
        }
        double power = Math.abs(sample.powerW);
        double cap = sample.isCharging() ? 100.0 : 35.0;
        return Math.max(0, Math.min(cap, power));
    }

    private double levelBasedAvgPower(List<BatterySample> samples) {
        if (samples.size() < 2) {
            return 0;
        }
        BatterySample first = samples.get(0);
        BatterySample last = samples.get(samples.size() - 1);
        int delta = first.level - last.level;
        long d = duration(samples);
        if (delta <= 0 || d < 20 * 60 * 1000L) {
            return 0;
        }
        double avgVoltage = 0;
        for (BatterySample sample : samples) {
            avgVoltage += sample.voltageV;
        }
        avgVoltage = avgVoltage / samples.size();
        double wh = batteryCapacityAh() * avgVoltage * delta / 100.0;
        return wh * 3600000.0 / d;
    }

    private long screenOnDuration(List<BatterySample> samples) {
        long total = 0;
        for (int i = 1; i < samples.size(); i++) {
            BatterySample prev = samples.get(i - 1);
            BatterySample cur = samples.get(i);
            if (prev.screenOn) {
                total += Math.max(0, cur.timeMs - prev.timeMs);
            }
        }
        return total;
    }

    private double screenOnAvgPower(List<BatterySample> samples) {
        long totalMs = 0;
        double wh = 0;
        for (int i = 1; i < samples.size(); i++) {
            BatterySample prev = samples.get(i - 1);
            BatterySample cur = samples.get(i);
            long dt = Math.max(0, cur.timeMs - prev.timeMs);
            if (dt > 10 * 60 * 1000L) {
                dt = 10 * 60 * 1000L;
            }
            if (prev.screenOn) {
                totalMs += dt;
                wh += sanePower(prev) * dt / 3600000.0;
            }
        }
        if (totalMs < 60 * 1000L) {
            return 0;
        }
        return Math.min(wh * 3600000.0 / totalMs, 35.0);
    }

    private String formatClock(long timeMs) {
        java.text.SimpleDateFormat format = new java.text.SimpleDateFormat("MM-dd HH:mm", Locale.CHINA);
        return format.format(new java.util.Date(timeMs));
    }

    private TextView logoBadge() {
        TextView logo = text("\u7535", 18, true, Color.WHITE);
        logo.setGravity(Gravity.CENTER);
        logo.setBackground(rounded(Color.rgb(21, 202, 203), dp(10)));
        return logo;
    }

    private View appIconView(String pkg, String label, int index) {
        try {
            Drawable icon = iconCache.get(pkg);
            if (icon == null) {
                icon = getPackageManager().getApplicationIcon(pkg);
                iconCache.put(pkg, icon);
            }
            ImageView image = new ImageView(this);
            image.setImageDrawable(icon);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setPadding(dp(3), dp(3), dp(3), dp(3));
            image.setBackground(rounded(Color.rgb(246, 247, 249), dp(12)));
            return image;
        } catch (Exception ignored) {
            ImageView image = new ImageView(this);
            image.setImageResource(R.drawable.ic_android_process);
            image.setPadding(dp(7), dp(7), dp(7), dp(7));
            image.setBackground(rounded(isDarkMode() ? Color.rgb(43, 48, 54) : Color.rgb(238, 246, 241), dp(12)));
            return image;
        }
    }

    private int colorFor(int i) {
        int[] colors = {
                Color.rgb(255, 171, 64), Color.rgb(17, 188, 116), Color.rgb(22, 137, 216),
                Color.rgb(132, 92, 245), Color.rgb(238, 91, 91), Color.rgb(60, 176, 210)
        };
        return colors[i % colors.length];
    }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private boolean isDarkMode() {
        return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    private int pageBgColor() {
        return isDarkMode() ? Color.rgb(20, 23, 27) : Color.rgb(241, 242, 244);
    }

    private int cardColor() {
        return isDarkMode() ? Color.rgb(30, 35, 41) : Color.WHITE;
    }

    private int primaryTextColor() {
        return isDarkMode() ? Color.rgb(226, 231, 236) : Color.rgb(52, 55, 58);
    }

    private int secondaryTextColor() {
        return isDarkMode() ? Color.rgb(155, 164, 174) : Color.rgb(120, 123, 128);
    }

    private int accentColor() {
        return isDarkMode() ? Color.rgb(105, 178, 245) : Color.rgb(22, 137, 216);
    }

    private int chipColor() {
        return isDarkMode() ? Color.rgb(38, 43, 49) : Color.WHITE;
    }

    private int adaptTextColor(int color) {
        if (!isDarkMode()) return color;
        if (color == Color.WHITE) return Color.rgb(232, 236, 241);
        int r = Color.red(color), g = Color.green(color), b = Color.blue(color);
        int avg = (r + g + b) / 3;
        if (avg < 90) return primaryTextColor();
        if (avg < 175) return secondaryTextColor();
        return Color.rgb(Math.max(170, r), Math.max(175, g), Math.max(180, b));
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
