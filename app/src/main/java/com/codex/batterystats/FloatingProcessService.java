package com.codex.batterystats;

import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Choreographer;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class FloatingProcessService extends Service {
    private static final int SAMPLE_INTERVAL_MS = 1000;

    private WindowManager windowManager;
    private WindowManager.LayoutParams params;
    private LinearLayout root;
    private LinearLayout content;
    private Handler uiHandler;
    private HandlerThread samplerThread;
    private Handler samplerHandler;
    private Runnable updater;
    private int pid;
    private String packageName;
    private String name;
    private boolean large = true;
    private boolean sampling;
    private boolean framePosted;
    private float downX;
    private float downY;
    private int startX;
    private int startY;
    private int pendingX;
    private int pendingY;
    private TextView statusValue;
    private TextView sizeButton;
    private TextView summaryValue;
    private final ArrayList<ThreadRow> threadRows = new ArrayList<>();
    private long lastTotalTicks;
    private long lastProcessTicks;
    private int lastCoreCount;
    private final Map<Integer, Long> lastThreadTicks = new HashMap<>();
    private final Map<Integer, Long> threadCpuMasks = new HashMap<>();

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        pid = intent == null ? 0 : intent.getIntExtra("pid", 0);
        packageName = intent == null ? "" : intent.getStringExtra("packageName");
        name = intent == null ? "" : intent.getStringExtra("name");
        if (pid <= 0) {
            stopSelf();
            return START_NOT_STICKY;
        }
        showWindow();
        return START_STICKY;
    }

    private void showWindow() {
        removeWindow();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        uiHandler = new Handler(Looper.getMainLooper());
        samplerThread = new HandlerThread("process-float-sampler");
        samplerThread.start();
        samplerHandler = new Handler(samplerThread.getLooper());

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(7), dp(6), dp(7), dp(6));
        root.setBackgroundColor(Color.argb(218, 28, 34, 42));
        root.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                downX = event.getRawX();
                downY = event.getRawY();
                startX = params.x;
                startY = params.y;
                return true;
            }
            if (event.getAction() == MotionEvent.ACTION_MOVE) {
                pendingX = startX + (int) (event.getRawX() - downX);
                pendingY = startY + (int) (event.getRawY() - downY);
                scheduleMoveFrame();
                return true;
            }
            return true;
        });

        buildLayout();
        params = new WindowManager.LayoutParams(
                dp(large ? 230 : 176),
                WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.LEFT;
        params.x = dp(24);
        params.y = dp(120);
        windowManager.addView(root, params);
        writeWatchRequest();
        startUpdater();
    }

    private void buildLayout() {
        root.removeAllViews();

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text(name == null || name.length() == 0 ? "PID " + pid : name, 8, true, Color.rgb(232, 236, 241));
        title.setSingleLine(true);
        head.addView(title, new LinearLayout.LayoutParams(0, dp(20), 1));
        sizeButton = text(large ? "[]" : "+", 9, true, Color.rgb(120, 190, 250));
        sizeButton.setGravity(Gravity.CENTER);
        sizeButton.setOnClickListener(v -> toggleSize());
        head.addView(sizeButton, new LinearLayout.LayoutParams(dp(22), dp(20)));
        TextView close = text("X", 9, true, Color.rgb(190, 200, 210));
        close.setGravity(Gravity.CENTER);
        close.setOnClickListener(v -> stopSelf());
        head.addView(close, new LinearLayout.LayoutParams(dp(22), dp(20)));
        root.addView(head);

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        root.addView(content);
        buildContentViews();
    }

    private void buildContentViews() {
        content.removeAllViews();
        threadRows.clear();

        summaryValue = monoText("CPU% Top" + (large ? "15" : "8"), large ? 8 : 7, true, Color.rgb(246, 248, 250));
        content.addView(summaryValue, new LinearLayout.LayoutParams(-1, dp(14)));

        ThreadRow header = tableRow("CPU%", "CPUS", "TID", "COMM", true);
        content.addView(header.layout, new LinearLayout.LayoutParams(-1, dp(12)));

        int rows = large ? 15 : 8;
        for (int i = 0; i < rows; i++) {
            ThreadRow row = tableRow("", "", "", "", false);
            threadRows.add(row);
            content.addView(row.layout, new LinearLayout.LayoutParams(-1, dp(12)));
        }

        statusValue = text("", 7, false, Color.rgb(170, 180, 190));
        statusValue.setGravity(Gravity.CENTER);
        content.addView(statusValue, new LinearLayout.LayoutParams(-1, dp(14)));
    }

    private void scheduleMoveFrame() {
        if (framePosted) return;
        framePosted = true;
        Choreographer.getInstance().postFrameCallback(frameTimeNanos -> {
            framePosted = false;
            if (windowManager == null || root == null || params == null) return;
            params.x = pendingX;
            params.y = pendingY;
            try {
                windowManager.updateViewLayout(root, params);
            } catch (Exception ignored) {
            }
        });
    }

    private void startUpdater() {
        lastTotalTicks = 0;
        lastProcessTicks = 0;
        lastCoreCount = Math.max(1, Runtime.getRuntime().availableProcessors());
        lastThreadTicks.clear();
        threadCpuMasks.clear();
        updater = new Runnable() {
            @Override public void run() {
                if (sampling) {
                    samplerHandler.postDelayed(this, SAMPLE_INTERVAL_MS);
                    return;
                }
                sampling = true;
                ProcessSnapshot info = sampleProcess();
                sampling = false;
                uiHandler.post(() -> applySample(info));
                samplerHandler.postDelayed(this, SAMPLE_INTERVAL_MS);
            }
        };
        samplerHandler.post(updater);
    }

    private void applySample(ProcessSnapshot info) {
        if (root == null) return;
        if (info == null) {
            statusValue.setText("Process ended");
            summaryValue.setText("CPU% Top" + (large ? "15" : "8"));
            for (ThreadRow row : threadRows) row.set("", "", "", "");
            return;
        }
        statusValue.setText("");
        summaryValue.setText(String.format(Locale.CHINA, "CPU%% Top%d   %.1f%%   RES %s", large ? 15 : 8, info.cpuPercent, resText(info.rssKb)));
        int count = Math.min(threadRows.size(), info.threads.size());
        for (int i = 0; i < threadRows.size(); i++) {
            ThreadRow row = threadRows.get(i);
            if (i < count) {
                ThreadSample th = info.threads.get(i);
                row.set(String.format(Locale.US, "%.1f", th.percent), th.cpus, String.valueOf(th.tid), trim(th.comm, large ? 12 : 8));
            } else {
                row.set("", "", "", "");
            }
        }
    }

    private ThreadRow tableRow(String cpu, String cpus, String tid, String comm, boolean header) {
        int color = header ? Color.rgb(246, 248, 250) : Color.rgb(238, 242, 246);
        int sp = large ? 7 : 6;
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView cpuV = monoText(cpu, sp, true, color);
        TextView cpusV = monoText(cpus, sp, true, color);
        TextView tidV = monoText(tid, sp, true, color);
        TextView commV = monoText(comm, sp, true, color);
        cpuV.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        cpusV.setGravity(Gravity.CENTER);
        tidV.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        commV.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        row.addView(cpuV, new LinearLayout.LayoutParams(dp(31), -1));
        row.addView(cpusV, new LinearLayout.LayoutParams(dp(36), -1));
        row.addView(tidV, new LinearLayout.LayoutParams(dp(44), -1));
        row.addView(commV, new LinearLayout.LayoutParams(0, -1, 1));
        return new ThreadRow(row, cpuV, cpusV, tidV, commV);
    }

    private void toggleSize() {
        large = !large;
        if (sizeButton != null) sizeButton.setText(large ? "[]" : "+");
        params.width = dp(large ? 230 : 176);
        buildContentViews();
        try {
            windowManager.updateViewLayout(root, params);
        } catch (Exception ignored) {
        }
    }

    private ProcessSnapshot sampleProcess() {
        ProcessSnapshot cached = sampleProcessFromDaemon();
        if (cached != null) return cached;
        String out = readProcessSample();
        if (out.length() == 0 || !out.contains("|")) return null;
        try {
            ProcessSnapshot info = new ProcessSnapshot();
            info.pid = pid;
            long totalTicks = 0;
            long processTicks = 0;
            double psCpuPercent = -1;
            int coreCount = 0;
            Map<Integer, Long> threadNow = new HashMap<>();
            Map<Integer, Double> psThreadCpu = new HashMap<>();
            Map<Integer, String> psThreadComm = new HashMap<>();
            Map<Integer, Integer> psThreadCore = new HashMap<>();
            Map<Integer, String> allowedCpus = new HashMap<>();
            ArrayList<ThreadSample> currentThreads = new ArrayList<>();
            String[] lines = out.trim().split("\\n");
            for (String line : lines) {
                String[] p = line.split("\\|", 5);
                if (p.length == 0) continue;
                if ("RSS".equals(p[0]) && p.length >= 2) {
                    info.rssKb = Long.parseLong(p[1].trim());
                } else if ("PID".equals(p[0]) && p.length >= 2) {
                    int newPid = parseIntSafe(p[1].trim(), pid);
                    if (newPid > 0) {
                        pid = newPid;
                        info.pid = newPid;
                    }
                } else if ("TOTAL".equals(p[0]) && p.length >= 2) {
                    totalTicks = Long.parseLong(p[1].trim());
                } else if ("PROC".equals(p[0]) && p.length >= 2) {
                    processTicks = Long.parseLong(p[1].trim());
                } else if ("PSCPU".equals(p[0]) && p.length >= 2) {
                    try {
                        psCpuPercent = Double.parseDouble(p[1].trim().replace("%", ""));
                    } catch (Exception ignored) {
                        psCpuPercent = -1;
                    }
                } else if ("PSTH".equals(p[0]) && p.length >= 5) {
                    try {
                        int tid = Integer.parseInt(p[1].trim());
                        psThreadCpu.put(tid, Double.parseDouble(p[2].trim().replace("%", "")));
                        int psr = parseIntSafe(p[3].trim(), -1);
                        if (psr >= 0) psThreadCore.put(tid, psr);
                        psThreadComm.put(tid, p[4].trim());
                    } catch (Exception ignored) {
                    }
                } else if ("ALLOW".equals(p[0]) && p.length >= 3) {
                    try {
                        allowedCpus.put(Integer.parseInt(p[1].trim()), p[2].trim());
                    } catch (Exception ignored) {
                    }
                } else if ("CORE".equals(p[0])) {
                    coreCount++;
                } else if ("TH".equals(p[0]) && p.length >= 4) {
                    int tid = Integer.parseInt(p[1].trim());
                    int core = parseIntSafe(p[2].trim(), -1);
                    long ticks = Long.parseLong(p[3].trim());
                    threadNow.put(tid, ticks);
                    ThreadSample th = new ThreadSample();
                    th.tid = tid;
                    th.core = core;
                    th.ticks = ticks;
                    th.comm = p.length >= 5 && p[4].trim().length() > 0 ? p[4].trim() : "Thread-" + tid;
                    currentThreads.add(th);
                }
            }
            if (totalTicks <= 0) return null;
            if (coreCount <= 0) coreCount = lastCoreCount > 0 ? lastCoreCount : Math.max(1, Runtime.getRuntime().availableProcessors());
            long totalDelta = lastTotalTicks > 0 ? Math.max(1, totalTicks - lastTotalTicks) : 0;
            double processTotalPercent = 0;
            double psThreadTotalPercent = 0;
            for (ThreadSample th : currentThreads) {
                Long oldTicks = lastThreadTicks.get(th.tid);
                double percent = 0;
                if (oldTicks != null && totalDelta > 0 && th.ticks >= oldTicks) {
                    percent = (th.ticks - oldTicks) * coreCount * 100.0 / totalDelta;
                }
                th.percent = Math.max(0, Math.min(100, percent));
                if (psThreadCpu.containsKey(th.tid)) {
                    th.percent = Math.max(0, Math.min(100, psThreadCpu.get(th.tid)));
                    psThreadTotalPercent += th.percent;
                }
                if (psThreadCore.containsKey(th.tid)) {
                    th.core = psThreadCore.get(th.tid);
                }
                if (psThreadComm.containsKey(th.tid) && psThreadComm.get(th.tid).length() > 0) {
                    th.comm = psThreadComm.get(th.tid);
                }
                th.cpus = th.core >= 0 ? updateCpuMask(th.tid, th.core) : allowedCpus.containsKey(th.tid) ? normalizeCpuList(allowedCpus.get(th.tid)) : "-";
                processTotalPercent += th.percent;
            }
            currentThreads.sort((a, b) -> Double.compare(b.percent, a.percent));
            if (processTicks > 0 && lastProcessTicks > 0 && processTicks >= lastProcessTicks && totalDelta > 0) {
                processTotalPercent = (processTicks - lastProcessTicks) * coreCount * 100.0 / totalDelta;
            }
            if (psThreadTotalPercent > 0.01) {
                processTotalPercent = psThreadTotalPercent;
            }
            if (processTotalPercent <= 0.01 && psCpuPercent > 0) {
                processTotalPercent = psCpuPercent;
                if (!currentThreads.isEmpty() && currentThreads.get(0).percent <= 0.01) {
                    currentThreads.get(0).percent = psCpuPercent;
                    currentThreads.sort((a, b) -> Double.compare(b.percent, a.percent));
                }
            }
            lastTotalTicks = totalTicks;
            if (processTicks > 0) lastProcessTicks = processTicks;
            lastCoreCount = coreCount;
            lastThreadTicks.clear();
            lastThreadTicks.putAll(threadNow);
            info.cpuPercent = processTotalPercent;
            info.threads = currentThreads;
            return info;
        } catch (Exception ignored) {
            return null;
        }
    }

    private ProcessSnapshot sampleProcessFromDaemon() {
        String out = RootShell.run("cat /data/adb/battery_stats/process_top.csv 2>/dev/null", 350);
        if (out.length() == 0 || !out.contains("|")) return null;
        try {
            ProcessSnapshot info = new ProcessSnapshot();
            Map<Integer, ThreadSample> byTid = new HashMap<>();
            String[] lines = out.trim().split("\\n");
            for (String line : lines) {
                String[] p = line.split("\\|", 7);
                if (p.length == 0) continue;
                if ("END".equals(p[0])) {
                    return null;
                }
                if ("META".equals(p[0]) && p.length >= 6) {
                    info.pid = parseIntSafe(p[1], pid);
                    pid = info.pid > 0 ? info.pid : pid;
                    info.rssKb = parseLongSafe(p[3], 0);
                    info.cpuPercent = parseDoubleSafe(p[4], 0);
                    lastCoreCount = parseIntSafe(p[5], lastCoreCount);
                } else if ("PSTH".equals(p[0]) && p.length >= 5) {
                    int tid = parseIntSafe(p[1], -1);
                    if (tid < 0) continue;
                    ThreadSample th = byTid.containsKey(tid) ? byTid.get(tid) : new ThreadSample();
                    th.tid = tid;
                    th.percent = Math.max(th.percent, parseDoubleSafe(p[2], 0));
                    int core = parseIntSafe(p[3], -1);
                    if (core >= 0) th.core = core;
                    if (p[4].trim().length() > 0) th.comm = p[4].trim();
                    byTid.put(tid, th);
                } else if ("TH".equals(p[0]) && p.length >= 6) {
                    int tid = parseIntSafe(p[1], -1);
                    if (tid < 0) continue;
                    ThreadSample th = byTid.containsKey(tid) ? byTid.get(tid) : new ThreadSample();
                    th.tid = tid;
                    th.percent = Math.max(th.percent, parseDoubleSafe(p[2], 0));
                    int core = parseIntSafe(p[3], -1);
                    if (core >= 0) th.core = core;
                    String allowed = p[4].trim();
                    if ((th.cpus == null || th.cpus.length() == 0) && allowed.length() > 0) {
                        th.cpus = normalizeCpuList(allowed);
                    }
                    if (p[5].trim().length() > 0) th.comm = p[5].trim();
                    byTid.put(tid, th);
                }
            }
            info.threads.addAll(byTid.values());
            for (ThreadSample th : info.threads) {
                if (th.cpus == null || th.cpus.length() == 0) {
                    th.cpus = th.core >= 0 ? updateCpuMask(th.tid, th.core) : "-";
                }
                if (th.comm == null) th.comm = "Thread-" + th.tid;
            }
            info.threads.sort((a, b) -> Double.compare(b.percent, a.percent));
            if (info.pid <= 0 || info.threads.isEmpty()) return null;
            return info;
        } catch (Exception ignored) {
            return null;
        }
    }

    private String readProcessSample() {
        String pkg = packageName == null ? "" : packageName.replace("'", "");
        String script = "pid=" + pid + "; d=/proc/$pid; "
                + "if [ ! -d \"$d\" ] && [ -n '" + pkg + "' ]; then "
                + "np=$(pidof '" + pkg + "' 2>/dev/null | awk '{print $1}'); "
                + "[ -n \"$np\" ] && pid=$np && d=/proc/$pid && echo \"PID|$pid\"; "
                + "fi; "
                + "[ -d \"$d\" ] || exit; "
                + "rss=0; "
                + "while read k v unit; do [ \"$k\" = \"VmRSS:\" ] && { rss=$v; break; }; done < \"$d/status\" 2>/dev/null; "
                + "echo \"RSS|$rss\"; "
                + "read c u n sy id io irq sirq rest < /proc/stat; echo \"TOTAL|$((u+n+sy+id+io+irq+sirq))\"; "
                + "pline=$(cat \"$d/stat\" 2>/dev/null); pright=${pline#*) }; set -- $pright; echo \"PROC|$(( $12 + $13 ))\"; "
                + "pcpu=$(ps -p $pid -o PCPU= 2>/dev/null | head -n 1 | tr -d ' %'); "
                + "[ -z \"$pcpu\" ] && pcpu=$(ps -p $pid -o %CPU= 2>/dev/null | head -n 1 | tr -d ' %'); "
                + "[ -n \"$pcpu\" ] && echo \"PSCPU|$pcpu\"; "
                + "ps -T -p $pid -o TID,PCPU,PSR,COMM 2>/dev/null | while read tid pcpu psr comm rest; do "
                + "[ \"$tid\" = \"TID\" ] && continue; [ -z \"$tid\" ] && continue; "
                + "echo \"PSTH|$tid|$pcpu|$psr|$comm\"; done; "
                + "ps -T -p $pid -o TID,%CPU,PSR,COMM 2>/dev/null | while read tid pcpu psr comm rest; do "
                + "[ \"$tid\" = \"TID\" ] && continue; [ -z \"$tid\" ] && continue; "
                + "echo \"PSTH|$tid|$pcpu|$psr|$comm\"; done; "
                + "while read c u n sy id io irq sirq rest; do "
                + "case \"$c\" in cpu[0-9]*) core=${c#cpu}; echo \"CORE|$core\";; esac; "
                + "done < /proc/stat; "
                + "for t in \"$d\"/task/[0-9]*; do "
                + "[ -d \"$t\" ] || continue; tid=${t##*/}; "
                + "line=$(cat \"$t/stat\" 2>/dev/null) || continue; right=${line#*) }; set -- $right; "
                + "ticks=$(( $12 + $13 )); cpu=${37:--1}; "
                + "comm=$(cat \"$t/comm\" 2>/dev/null); "
                + "allow=$(awk '/Cpus_allowed_list:/ {print $2; exit}' \"$t/status\" 2>/dev/null); [ -n \"$allow\" ] && echo \"ALLOW|$tid|$allow\"; "
                + "echo \"TH|$tid|$cpu|$ticks|$comm\"; "
                + "done";
        String out = RootShell.run(script, 900);
        if (out.length() > 0 && out.contains("|")) {
            return out;
        }
        return "";
    }

    private String resText(long rssKb) {
        if (rssKb >= 1024) {
            return Math.round(rssKb / 1024.0) + "MB";
        }
        return rssKb + "KB";
    }

    private static final class ProcessSnapshot {
        int pid;
        long rssKb;
        double cpuPercent;
        ArrayList<ThreadSample> threads = new ArrayList<>();
    }

    private static final class ThreadSample {
        int tid;
        int core = -1;
        long ticks;
        double percent;
        String cpus;
        String comm;
    }

    private static final class ThreadRow {
        final LinearLayout layout;
        final TextView cpu;
        final TextView cpus;
        final TextView tid;
        final TextView comm;

        ThreadRow(LinearLayout layout, TextView cpu, TextView cpus, TextView tid, TextView comm) {
            this.layout = layout;
            this.cpu = cpu;
            this.cpus = cpus;
            this.tid = tid;
            this.comm = comm;
        }

        void set(String cpuText, String cpusText, String tidText, String commText) {
            cpu.setText(cpuText);
            cpus.setText(cpusText);
            tid.setText(tidText);
            comm.setText(commText);
        }
    }

    private TextView text(String value, int sp, boolean bold, int color) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(sp);
        v.setTextColor(color);
        v.setIncludeFontPadding(false);
        if (bold) v.setTypeface(Typeface.DEFAULT_BOLD);
        return v;
    }

    private TextView monoText(String value, int sp, boolean bold, int color) {
        TextView v = text(value, sp, bold, color);
        v.setTypeface(Typeface.MONOSPACE, bold ? Typeface.BOLD : Typeface.NORMAL);
        return v;
    }

    private String updateCpuMask(int tid, int core) {
        long bit = core >= 0 && core < 63 ? (1L << core) : 0;
        long mask = threadCpuMasks.containsKey(tid) ? threadCpuMasks.get(tid) | bit : bit;
        threadCpuMasks.put(tid, mask);
        int min = -1;
        int max = -1;
        int count = 0;
        for (int i = 0; i < 63; i++) {
            if ((mask & (1L << i)) != 0) {
                if (min < 0) min = i;
                max = i;
                count++;
            }
        }
        if (min < 0) return "-";
        return count <= 1 ? String.valueOf(min) : min + "-" + max;
    }

    private int parseIntSafe(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private long parseLongSafe(String value, long fallback) {
        try {
            return Long.parseLong(value.trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private double parseDoubleSafe(String value, double fallback) {
        try {
            return Double.parseDouble(value.trim().replace("%", ""));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private String normalizeCpuList(String value) {
        if (value == null) return "-";
        value = value.trim();
        if (value.length() == 0) return "-";
        return value.replace(",", "/");
    }

    private void writeWatchRequest() {
        String pkg = packageName == null ? "" : packageName.replace("'", "");
        RootShell.run("mkdir -p /data/adb/battery_stats; echo '" + pid + "|" + pkg + "' > /data/adb/battery_stats/process_watch; chmod 0644 /data/adb/battery_stats/process_watch", 500);
    }

    private String trim(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max);
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void removeWindow() {
        RootShell.run("rm -f /data/adb/battery_stats/process_watch 2>/dev/null", 300);
        if (samplerHandler != null && updater != null) samplerHandler.removeCallbacks(updater);
        if (samplerThread != null) {
            samplerThread.quitSafely();
            samplerThread = null;
        }
        if (windowManager != null && root != null) {
            try {
                windowManager.removeView(root);
            } catch (Exception ignored) {
            }
        }
        root = null;
        content = null;
    }

    @Override public void onDestroy() {
        removeWindow();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }
}
