package com.codex.batterystats;

import android.Manifest;
import android.app.Activity;
import android.app.AppOpsManager;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
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
import android.view.MotionEvent;
import android.view.View;
import android.view.Display;
import android.view.Window;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity {
    private StatsDatabase database;
    private Handler handler;
    private LinearLayout root;
    private FrameLayout contentHost;
    private View currentPageView;
    private View previewPageView;
    private ScrollView currentScroll;
    private int page = 0;
    private int renderedPage = -1;
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
    private int processListStartIndex;
    private int dischargeSortMode = 0;
    private boolean dischargeShowSystemApps;
    private boolean dischargeSortAscending;
    private long lastCpuIdle;
    private long lastCpuTotal;
    private GpuInfo cachedGpuInfo;
    private long cachedGpuAt;
    private Dialog processDialog;
    private Handler processDialogHandler;
    private Runnable processDialogUpdater;
    private final Map<Integer, long[]> coreLast = new HashMap<>();
    private float swipeDownX;
    private float swipeDownY;
    private boolean swipeHorizontal;
    private boolean pageSwitching;
    private int previewTargetPage = -1;
    private int lastPageForAnimation = -1;
    private long lastLocalBatterySampleAt;

    private final Runnable refresher = new Runnable() {
        @Override
        public void run() {
            boolean processAtTop = currentScroll == null || currentScroll.getScrollY() < dp(12);
            if (page == 0) {
                render();
            } else if (page == 3 && processAutoRefresh && !processSearchFocused) {
                requestProcessLoad(false);
            }
            handler.postDelayed(this, page == 3 && processAutoRefresh && !processSearchFocused && processAtTop ? 1000 : 5000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        database = new StatsDatabase(this);
        handler = new Handler();
        preferHighRefreshRate();
        ensurePermissions();
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.removeCallbacks(refresher);
        handler.postDelayed(refresher, 5000);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refresher);
        stopProcessDialogUpdater();
        super.onPause();
    }

    @Override
    public void onBackPressed() {
        if (page != 0) {
            page = 0;
            render();
            return;
        }
        super.onBackPressed();
    }

    private void startMonitor() {
        Intent service = new Intent(this, BatteryMonitorService.class);
        startService(service);
    }

    private void ensurePermissions() {
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
        final boolean samePage = renderedPage == page;
        final int restoreY = samePage && currentScroll != null ? currentScroll.getScrollY() : 0;
        boolean moduleOk = ModuleDataImporter.importRecent(database);
        insertLocalBatterySampleIfNeeded();
        configureSystemBars();
        View oldPageView = currentPageView;
        if (root == null) {
            root = new LinearLayout(this);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setBackgroundColor(pageBgColor());
            setContentView(root);
            addTopBar();
            addTabs();
            contentHost = new SwipeFrameLayout(this);
            root.addView(contentHost, new LinearLayout.LayoutParams(-1, 0, 1));
        } else {
            root.setBackgroundColor(pageBgColor());
            refreshTopBars();
            contentHost.animate().cancel();
            contentHost.setTranslationX(0);
            contentHost.setAlpha(1f);
            if (!samePage) {
                contentHost.removeAllViews();
                oldPageView = null;
            }
        }

        previewPageView = null;
        previewTargetPage = -1;
        View pageView = buildPageView(page, true, moduleOk, restoreY);
        currentPageView = pageView;
        contentHost.addView(pageView, new FrameLayout.LayoutParams(-1, -1));
        renderedPage = page;
        if (samePage && oldPageView != null && oldPageView != pageView) {
            final View pageToRemove = oldPageView;
            pageView.setAlpha(0f);
            pageView.setTranslationY(dp(4));
            pageToRemove.animate().cancel();
            pageToRemove.animate()
                    .alpha(0f)
                    .setDuration(110)
                    .setInterpolator(new DecelerateInterpolator(1.2f))
                    .withEndAction(() -> contentHost.removeView(pageToRemove))
                    .start();
            pageView.animate()
                    .alpha(1f)
                    .translationY(0)
                    .setDuration(150)
                    .setInterpolator(new DecelerateInterpolator(1.6f))
                    .start();
        } else if (!samePage && currentPageView != null) {
            float dir = lastPageForAnimation >= 0 && page > lastPageForAnimation ? 1f : -1f;
            currentPageView.setTranslationX(dir * dp(42));
            currentPageView.setAlpha(0.18f);
            currentPageView.animate()
                    .translationX(0)
                    .alpha(1f)
                    .setDuration(240)
                    .setInterpolator(new DecelerateInterpolator(1.8f))
                    .start();
            lastPageForAnimation = page;
        }
    }

    private View buildPageView(int targetPage, boolean assignCurrentScroll, boolean moduleOk, int restoreY) {
        int oldPage = page;
        page = targetPage;
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(10), dp(6), dp(10), targetPage == 0 ? dp(8) : dp(26));
        ScrollView scroll = null;
        View pageView;
        if (targetPage == 0) {
            if (assignCurrentScroll) currentScroll = null;
            pageView = content;
        } else {
            scroll = new ScrollView(this);
            if (assignCurrentScroll) currentScroll = scroll;
            scroll.addView(content);
            pageView = scroll;
        }

        if (targetPage == 0) {
            addOverview(content, moduleOk);
        } else if (targetPage == 1) {
            addDischargePage(content);
        } else if (targetPage == 2) {
            addChargePage(content);
        } else {
            addProcessPage(content);
        }
        page = oldPage;
        if (assignCurrentScroll && scroll != null && restoreY > 0) {
            final ScrollView restoreScroll = scroll;
            restoreScroll.post(() -> restoreScroll.scrollTo(0, restoreY));
        }
        if (assignCurrentScroll && restoreY == 0) {
            animateCards(content);
        }
        return pageView;
    }

    private void refreshTopBars() {
        if (root == null || root.getChildCount() < 2) return;
        root.removeViews(0, Math.min(2, root.getChildCount()));
        addTopBarAt(0);
        addTabsAt(1);
    }

    private void addTopBar() {
        addTopBarAt(-1);
    }

    private void addTopBarAt(int index) {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(0, 0, 0, 0);
        TextView back = text(page == 0 ? "" : "<", 38, false, Color.rgb(32, 34, 36));
        back.setGravity(Gravity.CENTER);
        back.setOnClickListener(v -> {
            page = 0;
            render();
        });
        bar.addView(back, new LinearLayout.LayoutParams(0, statusBarSpace()));
        if (page == 0) {
            ImageView logo = new ImageView(this);
            logo.setImageResource(getApplicationInfo().icon);
            logo.setPadding(dp(5), dp(5), dp(5), dp(5));
            logo.setBackgroundResource(R.drawable.app_logo_bg);
            LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams(0, statusBarSpace());
            logoLp.setMargins(0, 0, dp(10), 0);
            bar.addView(logo, logoLp);
        }
        bar.addView(new View(this), new LinearLayout.LayoutParams(0, statusBarSpace(), 1));
        if (index >= 0) root.addView(bar, index);
        else root.addView(bar);
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
        root.addView(tabs);
    }

    private void addTabsAt(int index) {
        LinearLayout tabs = new LinearLayout(this);
        tabs.setPadding(dp(14), dp(4), dp(14), dp(6));
        tabs.addView(tab("总览", 0), new LinearLayout.LayoutParams(0, dp(42), 1));
        tabs.addView(tab("耗电", 1), new LinearLayout.LayoutParams(0, dp(42), 1));
        tabs.addView(tab("充电", 2), new LinearLayout.LayoutParams(0, dp(42), 1));
        tabs.addView(tab("进程", 3), new LinearLayout.LayoutParams(0, dp(42), 1));
        root.addView(tabs, index);
    }

    private TextView tab(String label, int target) {
        TextView view = text(label, 15, true, target == page ? Color.WHITE : Color.rgb(52, 55, 58));
        view.setGravity(Gravity.CENTER);
        view.setBackground(rounded(target == page ? accentColor() : (isDarkMode() ? Color.rgb(34, 39, 46) : Color.WHITE), dp(12)));
        view.setOnClickListener(v -> {
            press(v);
            switchPage(target);
        });
        return view;
    }

    private void switchPage(int target) {
        if (page == target) {
            return;
        }
        if (contentHost != null && currentPageView != null) {
            float syntheticDx = target > page ? -Math.max(1, contentHost.getWidth()) * 0.45f : Math.max(1, contentHost.getWidth()) * 0.45f;
            switchPageWithSwipe(target, syntheticDx);
            return;
        }
        page = target;
        render();
    }

    private void switchPageWithSwipe(int target, float dx) {
        if (pageSwitching || page == target || contentHost == null || currentPageView == null) {
            return;
        }
        pageSwitching = true;
        ensureSwipePreview(target, dx);
        float width = Math.max(1, contentHost.getWidth());
        float currentOut = dx < 0 ? -width : width;
        currentPageView.animate().cancel();
        currentPageView.animate()
                .translationX(currentOut)
                .alpha(0.35f)
                .setDuration(230)
                .setInterpolator(new AccelerateInterpolator(0.9f))
                .start();
        if (previewPageView != null) {
            previewPageView.animate().cancel();
            previewPageView.animate()
                    .translationX(0)
                    .alpha(1f)
                    .setDuration(260)
                    .setInterpolator(new DecelerateInterpolator(2.1f))
                    .withEndAction(() -> {
                        finishSwipeTo(target);
                    })
                    .start();
        } else {
            page = target;
            pageSwitching = false;
            render();
        }
    }

    private void finishSwipeTo(int target) {
        View old = currentPageView;
        page = target;
        renderedPage = target;
        currentPageView = previewPageView;
        previewPageView = null;
        previewTargetPage = -1;
        if (old != null && old != currentPageView) {
            contentHost.removeView(old);
        }
        if (currentPageView != null) {
            currentPageView.animate().cancel();
            currentPageView.setTranslationX(0);
            currentPageView.setAlpha(1f);
            if (currentPageView instanceof ScrollView) {
                currentScroll = (ScrollView) currentPageView;
            } else {
                currentScroll = null;
            }
        }
        root.setBackgroundColor(pageBgColor());
        refreshTopBars();
        pageSwitching = false;
        lastPageForAnimation = page;
    }

    private void settleSwipe() {
        if (contentHost == null || currentPageView == null) {
            return;
        }
        currentPageView.animate().cancel();
        currentPageView.animate()
                .translationX(0)
                .alpha(1f)
                .setDuration(190)
                .setInterpolator(new DecelerateInterpolator(1.7f))
                .start();
        if (previewPageView != null) {
            float width = Math.max(1, contentHost.getWidth());
            float targetX = previewTargetPage > page ? width : -width;
            previewPageView.animate().cancel();
            previewPageView.animate()
                    .translationX(targetX)
                    .alpha(0.2f)
                    .setDuration(170)
                    .setInterpolator(new DecelerateInterpolator(1.5f))
                    .withEndAction(() -> removeSwipePreview())
                    .start();
        }
    }

    private int swipeTargetForDelta(float dx) {
        if (dx < 0 && page < 3) return page + 1;
        if (dx > 0 && page > 0) return page - 1;
        return -1;
    }

    private void ensureSwipePreview(int target, float dx) {
        if (target < 0 || contentHost == null) {
            removeSwipePreview();
            return;
        }
        if (previewPageView == null || previewTargetPage != target) {
            removeSwipePreview();
            previewTargetPage = target;
            previewPageView = buildPageView(target, false, ModuleDataImporter.importRecent(database), 0);
            previewPageView.setAlpha(0.72f);
            contentHost.addView(previewPageView, new FrameLayout.LayoutParams(-1, -1));
        }
        float width = Math.max(1, contentHost.getWidth());
        previewPageView.setTranslationX(target > page ? width + dx : -width + dx);
    }

    private void removeSwipePreview() {
        if (contentHost != null && previewPageView != null) {
            contentHost.removeView(previewPageView);
        }
        previewPageView = null;
        previewTargetPage = -1;
    }

    private void installSwipeNavigation(View view) {
        view.setOnTouchListener((v, event) -> handleSwipeEvent(event));
    }

    private boolean handleSwipeEvent(MotionEvent event) {
        if (processDialog != null && processDialog.isShowing()) return false;
        if (page == 3 && processSearchFocused) return false;
        if (pageSwitching) return true;
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            swipeDownX = event.getX();
            swipeDownY = event.getY();
            swipeHorizontal = false;
            if (currentPageView != null) {
                currentPageView.animate().cancel();
            }
            return true;
        }
        if (event.getAction() == MotionEvent.ACTION_MOVE) {
            float dx = event.getX() - swipeDownX;
            float dy = event.getY() - swipeDownY;
            if (!swipeHorizontal && Math.abs(dx) > dp(10) && Math.abs(dx) > Math.abs(dy) * 1.25f) {
                swipeHorizontal = true;
            }
            if (swipeHorizontal && contentHost != null && currentPageView != null) {
                int target = swipeTargetForDelta(dx);
                if (target < 0) {
                    settleSwipe();
                    return true;
                }
                ensureSwipePreview(target, dx);
                float width = Math.max(1, contentHost.getWidth());
                float clamped = Math.max(-width, Math.min(width, dx));
                float progress = Math.min(1f, Math.abs(clamped) / width);
                currentPageView.setTranslationX(clamped);
                currentPageView.setAlpha(1f - Math.min(0.24f, progress * 0.32f));
                if (previewPageView != null) {
                    previewPageView.setAlpha(0.72f + 0.28f * progress);
                }
                return true;
            }
            return true;
        }
        if (event.getAction() == MotionEvent.ACTION_UP) {
            float dx = event.getX() - swipeDownX;
            float dy = event.getY() - swipeDownY;
            if (Math.abs(dx) > dp(70) && Math.abs(dx) > Math.abs(dy) * 1.6f) {
                int target = swipeTargetForDelta(dx);
                if (target >= 0) {
                    switchPageWithSwipe(target, dx);
                } else {
                    settleSwipe();
                }
                swipeHorizontal = false;
                return true;
            }
            settleSwipe();
            swipeHorizontal = false;
            return true;
        }
        if (event.getAction() == MotionEvent.ACTION_CANCEL) {
            settleSwipe();
            swipeHorizontal = false;
            return true;
        }
        return false;
    }

    private class SwipeFrameLayout extends FrameLayout {
        SwipeFrameLayout(Context context) {
            super(context);
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent event) {
            if (processDialog != null && processDialog.isShowing()) return false;
            if (page == 3 && processSearchFocused) return false;
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                swipeDownX = event.getX();
                swipeDownY = event.getY();
                swipeHorizontal = false;
                return false;
            }
            if (event.getAction() == MotionEvent.ACTION_MOVE) {
                float dx = event.getX() - swipeDownX;
                float dy = event.getY() - swipeDownY;
                if (Math.abs(dx) > dp(10) && Math.abs(dx) > Math.abs(dy) * 1.25f) {
                    swipeHorizontal = true;
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            return handleSwipeEvent(event);
        }
    }

    private void addOverview(LinearLayout content, boolean moduleOk) {
        BatterySample latest = database.latest();
        addHomeResourceCard(content);
        addHomeGpuCard(content);
        addHomeCpuCard(content);
        addHomeBottomCards(content, latest, moduleOk);
        addDaemonToggleCard(content);
    }

    private void addProcessPreview(LinearLayout content) {
        LinearLayout card = card();
        card.setOrientation(LinearLayout.VERTICAL);
        card.addView(text("进程信息", 19, true, primaryTextColor()), new LinearLayout.LayoutParams(-1, dp(42)));
        card.addView(text("松手后进入进程页面", 14, false, secondaryTextColor()), new LinearLayout.LayoutParams(-1, dp(38)));
        content.addView(card);
    }

    private void addDaemonToggleCard(LinearLayout content) {
        boolean running = RootShell.isStatsDaemonRunning();
        LinearLayout card = card();
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setOnClickListener(v -> switchPage(3));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(text(running ? "统计守护运行中" : "统计守护已暂停", 17, true, Color.rgb(64, 68, 72)));
        texts.addView(text(running ? "需要兼容敏感应用时可临时关闭，无需重启。" : "关闭期间不会记录新统计，点击按钮可立即恢复。", 13, false, Color.rgb(145, 148, 153)));
        card.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
        TextView toggle = smallAction(running ? "暂停" : "启动", v -> {
            boolean ok = RootShell.setStatsDaemonEnabled(!running);
            Toast.makeText(this, ok ? (!running ? "已启动统计守护" : "已暂停统计守护") : "操作失败，请检查 Root/模块路径", Toast.LENGTH_SHORT).show();
            render();
        });
        card.addView(toggle, new LinearLayout.LayoutParams(dp(74), dp(42)));
        content.addView(card);
    }

    private void addHomeResourceCard(LinearLayout content) {
        LinearLayout card = card();
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setOnClickListener(v -> switchPage(3));
        RingView ring = new RingView(this);
        MemInfo mem = readMemInfo();
        ring.setLevel(mem.memPercent);
        card.addView(ring, new LinearLayout.LayoutParams(dp(84), dp(84)));

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(dp(10), 0, 0, 0);
        addProgressLine(info, "物理内存", mem.memPercent, mem.usedMemText + " / " + mem.totalMemText);
        addProgressLine(info, "交换分区", mem.swapPercent, mem.usedSwapText + " / " + mem.totalSwapText);
        info.addView(text("Used " + mem.memPercent + "%   Cached " + mem.cachedText, 10, false, Color.rgb(145, 148, 153)));
        card.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
        content.addView(card);
    }

    private void addProgressLine(LinearLayout parent, String label, int percent, String right) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(text(label, 12, true, Color.rgb(70, 72, 76)), new LinearLayout.LayoutParams(0, dp(24), 7));
        TextView value = text(percent + "%  " + right, 10, false, Color.rgb(120, 123, 128));
        value.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
        row.addView(value, new LinearLayout.LayoutParams(0, dp(24), 13));
        parent.addView(row);
        View bg = new View(this);
        bg.setBackground(rounded(Color.rgb(236, 238, 240), dp(4)));
        LinearLayout wrap = new LinearLayout(this);
        wrap.setBackground(rounded(Color.rgb(236, 238, 240), dp(4)));
        View fill = new View(this);
        fill.setBackground(rounded(Color.rgb(156, 203, 250), dp(4)));
        wrap.addView(fill, new LinearLayout.LayoutParams(0, dp(7), Math.max(1, percent)));
        wrap.addView(new View(this), new LinearLayout.LayoutParams(0, dp(7), Math.max(1, 100 - percent)));
        parent.addView(wrap, new LinearLayout.LayoutParams(-1, dp(7)));
    }

    private void addHomeGpuCard(LinearLayout content) {
        LinearLayout card = card();
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        TextView gpu = text("GPU", 18, false, Color.rgb(145, 148, 153));
        gpu.setGravity(Gravity.CENTER);
        gpu.setBackground(rounded(Color.rgb(242, 243, 245), dp(48)));
        card.addView(gpu, new LinearLayout.LayoutParams(dp(64), dp(64)));
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(dp(16), 0, 0, 0);
        GpuInfo gpuInfo = readGpuInfo();
        info.addView(text(gpuInfo.freqText, 20, true, Color.rgb(52, 55, 58)));
        info.addView(text("负载: " + gpuInfo.loadText, 12, false, Color.rgb(120, 123, 128)));
        info.addView(text(Build.HARDWARE + "  Android " + Build.VERSION.RELEASE, 12, false, Color.rgb(160, 163, 168)));
        card.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
        content.addView(card);
    }

    private void addHomeCpuCard(LinearLayout content) {
        LinearLayout card = card();
        card.setOrientation(LinearLayout.VERTICAL);
        card.setOnClickListener(v -> switchPage(3));
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.CENTER);
        MiniBarsView bars = new MiniBarsView(this);
        double cpuLoad = readCpuLoadPercent();
        bars.setValues(cpuBars(cpuLoad));
        right.addView(bars, new LinearLayout.LayoutParams(dp(112), dp(38)));
        TextView cpu = text("CPU", 18, false, Color.rgb(78, 82, 86));
        cpu.setGravity(Gravity.CENTER);
        right.addView(cpu);
        right.addView(text(Build.HARDWARE, 13, true, Color.rgb(52, 55, 58)));
        right.addView(text(String.format(Locale.CHINA, "负载: %.0f%%", cpuLoad), 12, false, Color.rgb(145, 148, 153)));
        top.addView(right, new LinearLayout.LayoutParams(-1, dp(84)));
        card.addView(top);

        View line = new View(this);
        line.setBackgroundColor(Color.rgb(232, 234, 236));
        card.addView(line, new LinearLayout.LayoutParams(-1, 1));
        LinearLayout coresWrap = new LinearLayout(this);
        coresWrap.setOrientation(LinearLayout.VERTICAL);
        coresWrap.setPadding(0, dp(6), 0, 0);
        float[] coreLoads = readCoreLoads();
        int coreCount = Math.min(12, coreLoads.length);
        int rows = (int) Math.ceil(coreCount / 4.0);
        for (int rowIndex = 0; rowIndex < rows; rowIndex++) {
            LinearLayout row = new LinearLayout(this);
            for (int col = 0; col < 4; col++) {
                int core = rowIndex * 4 + col;
                if (core < coreCount) {
                    row.addView(coreBox(core, coreLoads[core]), new LinearLayout.LayoutParams(0, dp(48), 1));
                } else {
                    row.addView(new View(this), new LinearLayout.LayoutParams(0, dp(48), 1));
                }
            }
            coresWrap.addView(row);
        }
        card.addView(coresWrap);
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

    private LinearLayout coreBox(int index, float load) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        MiniBarsView bars = new MiniBarsView(this);
        bars.setValues(new float[]{load});
        box.addView(bars, new LinearLayout.LayoutParams(dp(42), dp(16)));
        box.addView(text("CPU" + index + " " + Math.round(load * 100) + "%", 10, true, Color.rgb(92, 96, 100)));
        box.addView(text(readFirstLine("/sys/devices/system/cpu/cpu" + index + "/cpufreq/scaling_cur_freq", "--") + "KHz", 9, false, Color.rgb(145, 148, 153)));
        return box;
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

    private Button commandButton(String label, int target) {
        Button button = button(label);
        button.setOnClickListener(v -> switchPage(target));
        return button;
    }

    private LinearLayout.LayoutParams buttonLp(boolean left) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(48), 1);
        lp.setMargins(left ? 0 : dp(8), 0, left ? dp(8) : 0, 0);
        return lp;
    }

    private void addChargePage(LinearLayout content) {
        List<BatterySample> samples = database.latestProcess(true);
        addStatusCard(content, database.latest());
        addProcessSummary(content, samples, true);
        addChartCard(content, "功率 / 时间", samples, ChartView.TYPE_POWER_TIME, dp(260));
        addChartCard(content, "电量 / 时间", samples, ChartView.TYPE_LEVEL_TIME, dp(260));
        addChartCard(content, "温度 / 时间", samples, ChartView.TYPE_TEMP_TIME, dp(220));
        addChartCard(content, "电流 / 电量", samples, ChartView.TYPE_CURRENT_LEVEL, dp(240));
    }

    private void addDischargePage(LinearLayout content) {
        List<BatterySample> samples = database.latestProcess(false);
        addUsageProcessCard(content, samples);
        addDischargeMetrics(content, samples);
        addAppUsageCard(content);
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
        search.setBackground(rounded(Color.rgb(247, 248, 249), dp(10)));
        search.setPadding(dp(12), 0, dp(12), 0);
        search.setOnFocusChangeListener((v, hasFocus) -> processSearchFocused = hasFocus);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                processKeyword = s.toString();
                refreshProcessListOnly();
            }
            @Override public void afterTextChanged(Editable s) { }
        });
        tools.addView(search, new LinearLayout.LayoutParams(-1, dp(46)));

        LinearLayout filters = new LinearLayout(this);
        filters.setPadding(0, dp(10), 0, 0);
        filters.addView(smallAction(processAppOnly ? "安卓应用" : "全部进程", v -> {
            processKeyword = search.getText().toString();
            processAppOnly = !processAppOnly;
            render();
        }), new LinearLayout.LayoutParams(0, dp(40), 1));
        filters.addView(smallAction(processAutoRefresh ? "自动刷新" : "暂停刷新", v -> {
            processKeyword = search.getText().toString();
            processAutoRefresh = !processAutoRefresh;
            render();
        }), new LinearLayout.LayoutParams(0, dp(40), 1));
        filters.addView(smallAction("刷新", v -> {
            processKeyword = search.getText().toString();
            requestProcessLoad(true);
            render();
        }), new LinearLayout.LayoutParams(0, dp(40), 1));
        tools.addView(filters);
        content.addView(tools);

        processListParent = content;
        processListStartIndex = content.getChildCount();
        processListCard = null;
        appendProcessListViews(content);
    }

    private void appendProcessListViews(LinearLayout content) {
        if (processLoading && processCache.isEmpty()) {
            addLoadingCard(content, "正在读取进程信息...");
        }
        processListCard = createProcessListCard(filteredProcessList());
        content.addView(processListCard);
    }

    private void refreshProcessListOnly() {
        if (processListParent == null || page != 3) {
            return;
        }
        final int y = currentScroll == null ? 0 : currentScroll.getScrollY();
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
        header.addView(text("进程", 13, true, Color.rgb(130, 134, 138)), new LinearLayout.LayoutParams(0, dp(28), 2));
        header.addView(text("PID", 12, true, Color.rgb(130, 134, 138)), new LinearLayout.LayoutParams(0, dp(28), 1));
        header.addView(sortHeader("RES", 1), new LinearLayout.LayoutParams(0, dp(28), 1));
        header.addView(sortHeader("CPU", 0), new LinearLayout.LayoutParams(0, dp(28), 1));
        card.addView(header);

        int count = list.size();
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
            names.addView(text(ProcessReader.label(this, info.packageName), 14, true, Color.rgb(55, 58, 62)));
            names.addView(text(info.processName, 11, false, Color.rgb(145, 148, 153)));
            row.addView(names, new LinearLayout.LayoutParams(0, dp(54), 2));
            row.addView(text(String.valueOf(info.pid), 12, false, Color.rgb(110, 114, 118)), new LinearLayout.LayoutParams(0, dp(54), 1));
            row.addView(text(info.resText(), 12, false, Color.rgb(110, 114, 118)), new LinearLayout.LayoutParams(0, dp(54), 1));
            row.addView(text(String.format(Locale.CHINA, "%.1f%%", info.cpuPercent), 12, false, Color.rgb(95, 99, 104)), new LinearLayout.LayoutParams(0, dp(54), 1));
            card.addView(row);
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

    private void showProcessDialogInApp(ProcessInfo info) {
        stopProcessDialogUpdater();
        processDialog = new Dialog(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(14), dp(16), dp(14));
        box.setBackground(rounded(cardColor(), dp(18)));

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(appIconView(info.packageName, info.packageName, 0), new LinearLayout.LayoutParams(dp(42), dp(42)));
        LinearLayout titleBox = new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        titleBox.setPadding(dp(10), 0, 0, 0);
        titleBox.addView(text(ProcessReader.label(this, info.packageName), 16, true, primaryTextColor()));
        titleBox.addView(text(info.processName + "  PID " + info.pid, 11, false, secondaryTextColor()));
        head.addView(titleBox, new LinearLayout.LayoutParams(0, dp(48), 1));
        TextView close = text("X", 16, true, secondaryTextColor());
        close.setGravity(Gravity.CENTER);
        close.setBackground(rounded(isDarkMode() ? Color.rgb(49, 53, 60) : Color.rgb(238, 240, 243), dp(16)));
        close.setOnClickListener(v -> {
            stopProcessDialogUpdater();
            if (processDialog != null) processDialog.dismiss();
        });
        head.addView(close, new LinearLayout.LayoutParams(dp(36), dp(36)));
        box.addView(head);

        LinearLayout metrics = new LinearLayout(this);
        metrics.setPadding(0, dp(12), 0, dp(4));
        TextView cpuValue = metricText("--", "CPU");
        TextView resValue = metricText("--", "RES");
        TextView coresValue = metricText("--", "核心");
        metrics.addView(cpuValue, new LinearLayout.LayoutParams(0, dp(64), 1));
        metrics.addView(resValue, new LinearLayout.LayoutParams(0, dp(64), 1));
        metrics.addView(coresValue, new LinearLayout.LayoutParams(0, dp(64), 1));
        box.addView(metrics);

        MiniBarsView bars = new MiniBarsView(this);
        box.addView(bars, new LinearLayout.LayoutParams(-1, dp(76)));
        LinearLayout coreGrid = new LinearLayout(this);
        coreGrid.setOrientation(LinearLayout.VERTICAL);
        coreGrid.setPadding(0, dp(8), 0, 0);
        box.addView(coreGrid);
        TextView detail = text("正在读取...", 12, false, secondaryTextColor());
        box.addView(detail, new LinearLayout.LayoutParams(-1, dp(30)));

        processDialog.setContentView(box);
        if (processDialog.getWindow() != null) {
            processDialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }
        processDialog.setOnDismissListener(d -> stopProcessDialogUpdater());
        processDialog.show();

        coreLast.clear();
        readCoreLoads();
        processDialogHandler = new Handler();
        processDialogUpdater = new Runnable() {
            @Override public void run() {
                ProcessInfo latest = findProcess(info.pid);
                if (latest == null) {
                    latest = info;
                }
                cpuValue.setText(String.format(Locale.CHINA, "%.1f%%\nCPU", latest.cpuPercent));
                resValue.setText(latest.resText() + "\nRES");
                float[] coreLoads = readCoreLoads();
                coresValue.setText(coreLoads.length + "\n核心");
                bars.setValues(coreLoads);
                renderCoreGrid(coreGrid, coreLoads);
                detail.setText("核心负载实时刷新中");
                processDialogHandler.postDelayed(this, 1000);
            }
        };
        processDialogHandler.post(processDialogUpdater);
    }

    private TextView metricText(String value, String label) {
        TextView view = text(value + "\n" + label, 15, true, accentColor());
        view.setGravity(Gravity.CENTER);
        view.setBackground(rounded(isDarkMode() ? Color.rgb(38, 43, 49) : Color.rgb(244, 248, 252), dp(12)));
        return view;
    }

    private void stopProcessDialogUpdater() {
        if (processDialogHandler != null && processDialogUpdater != null) {
            processDialogHandler.removeCallbacks(processDialogUpdater);
        }
        processDialogUpdater = null;
    }

    private ProcessInfo findProcess(int pid) {
        for (ProcessInfo info : processCache) {
            if (info.pid == pid) return info;
        }
        List<ProcessInfo> latest = ProcessReader.read(this, "", 0, false);
        for (ProcessInfo info : latest) {
            if (info.pid == pid) return info;
        }
        return null;
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

    private void renderCoreGrid(LinearLayout grid, float[] values) {
        grid.removeAllViews();
        int count = values.length;
        int columns = count > 4 ? 4 : Math.max(1, count);
        for (int rowIndex = 0; rowIndex * columns < count; rowIndex++) {
            LinearLayout row = new LinearLayout(this);
            for (int col = 0; col < columns; col++) {
                int idx = rowIndex * columns + col;
                if (idx < count) {
                    TextView cell = text("CPU" + idx + "  " + Math.round(values[idx] * 100) + "%", 11, true, secondaryTextColor());
                    cell.setGravity(Gravity.CENTER);
                    cell.setBackground(rounded(isDarkMode() ? Color.rgb(38, 43, 49) : Color.rgb(244, 248, 252), dp(8)));
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(30), 1);
                    lp.setMargins(dp(3), dp(3), dp(3), dp(3));
                    row.addView(cell, lp);
                } else {
                    row.addView(new View(this), new LinearLayout.LayoutParams(0, dp(30), 1));
                }
            }
            grid.addView(row);
        }
    }

    private List<ProcessInfo> filteredProcessList() {
        ArrayList<ProcessInfo> out = new ArrayList<>();
        String q = processKeyword == null ? "" : processKeyword.trim().toLowerCase(Locale.US);
        for (ProcessInfo info : processCache) {
            if (processAppOnly && !info.installedApp) {
                continue;
            }
            String label = ProcessReader.label(this, info.packageName).toLowerCase(Locale.US);
            if (q.length() > 0
                    && !info.packageName.toLowerCase(Locale.US).contains(q)
                    && !info.processName.toLowerCase(Locale.US).contains(q)
                    && !label.contains(q)) {
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

    private void addStatusCard(LinearLayout content, BatterySample latest) {
        LinearLayout card = card();
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        RingView ring = new RingView(this);
        ring.setLevel(latest == null ? 0 : latest.level);
        card.addView(ring, new LinearLayout.LayoutParams(dp(160), dp(160)));
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(dp(12), 0, 0, 0);
        if (latest == null) {
            info.addView(text("等待采样", 20, true, Color.rgb(70, 72, 76)));
            info.addView(text("模块启动后会自动记录", 14, false, Color.rgb(145, 148, 153)));
        } else {
            info.addView(text(latest.isCharging() ? "\u5145\u7535\u4e2d" : "\u672a\u5145\u7535", 20, true, Color.rgb(70, 72, 76)));
            info.addView(text(String.format(Locale.CHINA, "%.2fW", latest.isCharging() ? latest.powerW : -latest.powerW), 16, false, Color.rgb(145, 148, 153)));
            info.addView(text(String.format(Locale.CHINA, "%.1fC    %.3fV", latest.tempC, latest.voltageV), 16, false, Color.rgb(145, 148, 153)));
            info.addView(text(String.format(Locale.CHINA, "%.0fmA", Math.abs(latest.currentA * 1000)), 16, false, Color.rgb(145, 148, 153)));
        }
        card.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
        content.addView(card);
    }

    private void addProcessSummary(LinearLayout content, List<BatterySample> samples, boolean charging) {
        LinearLayout card = card();
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        TextView icon = logoBadge();
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(42), dp(42));
        iconLp.setMargins(0, 0, dp(10), 0);
        card.addView(icon, iconLp);
        String time = "--";
        String delta = "--";
        String energy = "--";
        if (samples.size() >= 2) {
            BatterySample first = samples.get(0);
            BatterySample last = samples.get(samples.size() - 1);
            time = formatClock(first.timeMs) + " ~ " + formatClock(last.timeMs);
            delta = (charging ? "+" : "") + (last.level - first.level) + "%";
            energy = String.format(Locale.CHINA, "%+.2fWh", totalWh(samples));
        }
        card.addView(text(time, 15, false, Color.rgb(120, 123, 128)), new LinearLayout.LayoutParams(0, dp(48), 2));
        card.addView(text(BatteryReader.formatDuration(duration(samples)), 15, false, Color.rgb(120, 123, 128)), new LinearLayout.LayoutParams(0, dp(48), 1));
        card.addView(text(delta, 15, false, Color.rgb(120, 123, 128)), new LinearLayout.LayoutParams(0, dp(48), 1));
        card.addView(text(energy, 15, false, Color.rgb(120, 123, 128)), new LinearLayout.LayoutParams(0, dp(48), 1));
        content.addView(card);
    }

    private void addUsageProcessCard(LinearLayout content, List<BatterySample> samples) {
        LinearLayout card = card();
        card.setOrientation(LinearLayout.VERTICAL);
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(text("使用过程", 18, true, Color.rgb(70, 72, 76)), new LinearLayout.LayoutParams(0, dp(36), 1));
        head.addView(text(samples.isEmpty() ? "--" : samples.get(samples.size() - 1).level + "%", 18, false, Color.rgb(120, 123, 128)));
        card.addView(head);
        ChartView chart = new ChartView(this);
        chart.setData(samples, ChartView.TYPE_USAGE);
        card.addView(chart, new LinearLayout.LayoutParams(-1, dp(260)));
        LinearLayout foot = new LinearLayout(this);
        foot.setPadding(0, dp(8), 0, 0);
        BatterySample latest = samples.isEmpty() ? database.latest() : samples.get(samples.size() - 1);
        foot.addView(text(latest == null ? "--Wh" : String.format(Locale.CHINA, "%.1fWh", latest.voltageV * batteryCapacityAh()), 14, false, Color.rgb(130, 134, 138)), new LinearLayout.LayoutParams(0, dp(36), 1));
        foot.addView(text(latest == null ? "--C" : String.format(Locale.CHINA, "%.1fC", latest.tempC), 14, false, Color.rgb(130, 134, 138)), new LinearLayout.LayoutParams(0, dp(36), 1));
        foot.addView(text(latest == null ? "--V" : String.format(Locale.CHINA, "%.3fV", latest.voltageV), 14, false, Color.rgb(130, 134, 138)), new LinearLayout.LayoutParams(0, dp(36), 1));
        foot.addView(text(latest != null && latest.isCharging() ? "充电" : "未充电", 14, false, Color.rgb(130, 134, 138)), new LinearLayout.LayoutParams(0, dp(36), 1));
        card.addView(foot);
        content.addView(card);
    }

    private void addDischargeMetrics(LinearLayout content, List<BatterySample> samples) {
        LinearLayout card = card();
        card.setOrientation(LinearLayout.HORIZONTAL);
        double avg = avgPower(samples);
        long used = duration(samples);
        long screenUsed = screenOnDuration(samples);
        String life = "--";
        if (!samples.isEmpty() && avg > 0.1) {
            double whLeft = samples.get(samples.size() - 1).voltageV * batteryCapacityAh() * samples.get(samples.size() - 1).level / 100.0;
            life = BatteryReader.formatDuration((long) (whLeft / avg * 3600000.0));
        }
        card.addView(metric(String.format(Locale.CHINA, "%.2fW", avg), "\u5e73\u5747\u529f\u8017"), new LinearLayout.LayoutParams(0, dp(76), 1));
        card.addView(metric(BatteryReader.formatDuration(used), "\u5df2\u4f7f\u7528"), new LinearLayout.LayoutParams(0, dp(76), 1));
        card.addView(metric(BatteryReader.formatDuration(screenUsed), "\u4eae\u5c4f"), new LinearLayout.LayoutParams(0, dp(76), 1));
        card.addView(metric(life, "\u7406\u8bba\u7eed\u822a"), new LinearLayout.LayoutParams(0, dp(76), 1));
        content.addView(card);
    }

    private void addAppUsageCard(LinearLayout content) {
        LinearLayout card = card();
        card.setOrientation(LinearLayout.VERTICAL);
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.addView(logoBadge(), new LinearLayout.LayoutParams(dp(38), dp(38)));
        TextView cardTitle = text("\u4f7f\u7528\u573a\u666f", 19, true, Color.rgb(70, 72, 76));
        cardTitle.setPadding(dp(10), 0, 0, 0);
        titleRow.addView(cardTitle, new LinearLayout.LayoutParams(0, dp(44), 1));
        titleRow.addView(smallAction(dischargeShowSystemApps ? "\u7cfb\u7edf\u5f00" : "\u7cfb\u7edf\u5173", v -> {
            dischargeShowSystemApps = !dischargeShowSystemApps;
            render();
        }), new LinearLayout.LayoutParams(dp(70), dp(38)));
        TextView sortButton = smallAction(sortName(), null);
        sortButton.setOnClickListener(v -> showDischargeSortMenu(sortButton));
        titleRow.addView(sortButton, new LinearLayout.LayoutParams(dp(78), dp(38)));
        titleRow.addView(smallAction(dischargeSortAscending ? "\u6b63\u5e8f" : "\u5012\u5e8f", v -> {
            dischargeSortAscending = !dischargeSortAscending;
            render();
        }), new LinearLayout.LayoutParams(dp(64), dp(38)));
        card.addView(titleRow);
        List<StatsDatabase.AppUsage> usages = filteredAppUsage();
        int count = usages.size();
        if (count == 0) {
            card.addView(text("\u6682\u65e0\u5e94\u7528\u8017\u7535\u8bb0\u5f55\u3002\u6a21\u5757\u8fd0\u884c\u4e00\u6bb5\u65f6\u95f4\u540e\u4f1a\u663e\u793a\u524d\u53f0\u548c\u540e\u53f0\u8017\u7535\u3002", 14, false, Color.rgb(145, 148, 153)));
        }
        for (int i = 0; i < count; i++) {
            StatsDatabase.AppUsage usage = usages.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.addView(appIconView(usage.pkg, usage.label, i), new LinearLayout.LayoutParams(dp(48), dp(48)));
            LinearLayout texts = new LinearLayout(this);
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.setPadding(dp(12), 0, 0, 0);
            texts.addView(text(usage.label, 16, true, Color.rgb(65, 68, 72)));
            texts.addView(text(usage.subtitle(), 13, false, Color.rgb(145, 148, 153)));
            texts.addView(text(usage.detail(), 12, false, Color.rgb(165, 168, 173)));
            row.addView(texts, new LinearLayout.LayoutParams(0, dp(74), 1));
            row.addView(text(BatteryReader.formatDuration(usage.durationMs), 14, false, Color.rgb(120, 123, 128)));
            card.addView(row);
        }
        content.addView(card);
    }

    private String sortName() {
        if (dischargeSortMode == 1) return "\u65f6\u957f\u6392\u5e8f";
        return "\u8017\u7535\u6392\u5e8f";
    }

    private void showDischargeSortMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 0, 0, "\u8017\u7535\u6392\u5e8f");
        menu.getMenu().add(0, 1, 1, "\u65f6\u957f\u6392\u5e8f");
        menu.setOnMenuItemClickListener(item -> {
            dischargeSortMode = item.getItemId();
            render();
            return true;
        });
        menu.show();
    }

    private List<StatsDatabase.AppUsage> filteredAppUsage() {
        ArrayList<StatsDatabase.AppUsage> out = new ArrayList<>();
        for (StatsDatabase.AppUsage usage : database.appUsageForLatestDischarge(this)) {
            if (!dischargeShowSystemApps && isSystemPackage(usage.pkg)) {
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
                || pkg.startsWith("com.samsung.")
                || pkg.startsWith("com.sec.")) {
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

    private void addChartCard(LinearLayout content, String title, List<BatterySample> samples, int type, int height) {
        LinearLayout card = card();
        card.setOrientation(LinearLayout.VERTICAL);
        card.addView(text(title, 19, true, Color.rgb(70, 72, 76)), new LinearLayout.LayoutParams(-1, dp(42)));
        ChartView chart = new ChartView(this);
        chart.setData(samples, type);
        card.addView(chart, new LinearLayout.LayoutParams(-1, height));
        content.addView(card);
    }

    private void addHintCard(LinearLayout content, String msg, String action, View.OnClickListener listener) {
        LinearLayout card = card();
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(rounded(Color.rgb(228, 230, 233), dp(14)));
        card.addView(text(msg, 16, false, Color.rgb(120, 123, 128)), new LinearLayout.LayoutParams(-1, dp(42)));
        if (action != null) {
            TextView link = text(action, 17, true, Color.rgb(22, 137, 216));
            link.setOnClickListener(listener);
            card.addView(link, new LinearLayout.LayoutParams(-1, dp(40)));
        }
        content.addView(card);
    }

    private TextView smallAction(String label, View.OnClickListener listener) {
        TextView view = text(label, 13, true, accentColor());
        view.setGravity(Gravity.CENTER);
        view.setBackground(rounded(isDarkMode() ? Color.rgb(38, 43, 49) : Color.WHITE, dp(10)));
        view.setOnClickListener(v -> {
            press(v);
            if (listener != null) {
                listener.onClick(v);
            }
        });
        return view;
    }

    private LinearLayout metric(String value, String label) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.addView(text(value, 22, false, Color.rgb(22, 137, 216)));
        box.addView(text(label, 13, false, Color.rgb(145, 148, 153)));
        return box;
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

    private void animateCards(LinearLayout content) {
        int animated = 0;
        for (int i = 0; i < content.getChildCount(); i++) {
            View child = content.getChildAt(i);
            if (!(child instanceof LinearLayout)) {
                continue;
            }
            child.setAlpha(0f);
            child.setTranslationY(dp(10));
            child.animate()
                    .alpha(1f)
                    .translationY(0)
                    .setStartDelay(Math.min(120, animated * 22L))
                    .setDuration(220)
                    .setInterpolator(new DecelerateInterpolator(1.8f))
                    .start();
            animated++;
            if (animated >= 8) {
                break;
            }
        }
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

    private Button button(String value) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextSize(16);
        button.setTextColor(Color.rgb(22, 137, 216));
        button.setAllCaps(false);
        button.setBackground(rounded(Color.WHITE, dp(12)));
        return button;
    }

    private boolean hasUsageAccess() {
        try {
            AppOpsManager appOps = (AppOpsManager) getSystemService(APP_OPS_SERVICE);
            int mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            return false;
        }
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
        double cap = sample.isCharging() ? 80.0 : 25.0;
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

    private String formatClock(long timeMs) {
        java.text.SimpleDateFormat format = new java.text.SimpleDateFormat("MM-dd HH:mm", Locale.CHINA);
        return format.format(new java.util.Date(timeMs));
    }

    private TextView logoBadge() {
        TextView logo = text("?", 18, true, Color.WHITE);
        logo.setGravity(Gravity.CENTER);
        logo.setBackground(rounded(Color.rgb(21, 202, 203), dp(10)));
        return logo;
    }

    private View appIconView(String pkg, String label, int index) {
        try {
            Drawable icon = getPackageManager().getApplicationIcon(pkg);
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
