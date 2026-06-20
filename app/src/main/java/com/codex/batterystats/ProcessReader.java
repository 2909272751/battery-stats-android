package com.codex.batterystats;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class ProcessReader {
    private static final Map<Integer, Long> LAST_TICKS = new HashMap<>();
    private static long lastTotalTicks;
    private static String lastError = "";

    private ProcessReader() {
    }

    static List<ProcessInfo> read(Context context, String keyword, int sortMode, boolean appOnly) {
        lastError = "";
        String script = "read c u n sy id io irq sirq rest < /proc/stat; "
                + "echo TOTAL\\|$((u+n+sy+id+io+irq+sirq)); "
                + "for d in /proc/[0-9]*; do "
                + "pid=${d##*/}; "
                + "cmd=$(cat \"$d/cmdline\" 2>/dev/null); cmd=${cmd%%:*}; cmd=${cmd%% *}; "
                + "[ -z \"$cmd\" ] && cmd=$(cat \"$d/comm\" 2>/dev/null); "
                + "[ -z \"$cmd\" ] && continue; "
                + "case \"$cmd\" in kworker*|migration*|rcu*|irq/*|kswapd*|watchdog*) continue;; esac; "
                + "pkg=${cmd%%:*}; "
                + "rss=0; while read k v unit; do [ \"$k\" = \"VmRSS:\" ] && { rss=$v; break; }; done < \"$d/status\" 2>/dev/null; "
                + "line=$(cat \"$d/stat\" 2>/dev/null); right=${line#*) }; set -- $right; ticks=$(( $12 + $13 )); "
                + "echo \"$pid|$pkg|$cmd|$rss|$ticks\"; "
                + "done";
        String out = readFromPs();
        boolean fromPsFallback = out.length() > 0;
        if (out.length() == 0 || !out.contains("|")) {
            out = RootShell.run(script, 1800);
            fromPsFallback = false;
        }
        if (out.length() == 0) {
            String probe = RootShell.run("id; echo PROC_COUNT=$(ls /proc/[0-9]* 2>/dev/null | wc -l); ps -A 2>/dev/null | head -n 3", 2500);
            lastError = RootShell.isAvailable()
                    ? "Root 已授权，但 /proc 和 ps 都没有返回进程。诊断: " + compact(probe)
                    : "未获得 Root shell。";
        }
        String[] lines = out.split("\\n");
        long totalTicks = 0;
        ArrayList<ProcessInfo> processes = new ArrayList<>();
        PackageManager pm = context.getPackageManager();
        String q = keyword == null ? "" : keyword.trim().toLowerCase(Locale.US);

        for (String line : lines) {
            if (line.startsWith("TOTAL|")) {
                try {
                    totalTicks = Long.parseLong(line.substring(6).trim());
                } catch (Exception ignored) {
                }
                continue;
            }
            String[] parts = line.split("\\|", 5);
            if (parts.length < 5) {
                continue;
            }
            try {
                ProcessInfo info = new ProcessInfo();
                info.pid = Integer.parseInt(parts[0]);
                info.packageName = parts[1];
                info.processName = parts[2];
                info.rssKb = Long.parseLong(parts[3]);
                String cpuField = parts[4].trim().replace("%", "");
                if (fromPsFallback) {
                    try {
                        info.cpuPercent = Double.parseDouble(cpuField);
                    } catch (Exception ignored) {
                        info.cpuPercent = 0;
                    }
                    info.cpuTicks = 0;
                } else {
                    info.cpuTicks = Long.parseLong(cpuField);
                }
                try {
                    ApplicationInfo appInfo = pm.getApplicationInfo(info.packageName, 0);
                    info.installedApp = true;
                    info.systemApp = (appInfo.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
                } catch (Exception ignored) {
                    info.installedApp = false;
                    info.systemApp = false;
                }
                if (appOnly && !info.installedApp) {
                    continue;
                }
                if (q.length() > 0
                        && !info.packageName.toLowerCase(Locale.US).contains(q)
                        && !info.processName.toLowerCase(Locale.US).contains(q)
                        && !label(context, info.packageName).toLowerCase(Locale.US).contains(q)) {
                    continue;
                }
                if (!fromPsFallback || info.cpuTicks > 0) {
                    long old = LAST_TICKS.containsKey(info.pid) ? LAST_TICKS.get(info.pid) : info.cpuTicks;
                    long totalDelta = Math.max(1, totalTicks - lastTotalTicks);
                    long procDelta = Math.max(0, info.cpuTicks - old);
                    info.cpuPercent = procDelta * Runtime.getRuntime().availableProcessors() * 100.0 / totalDelta;
                }
                processes.add(info);
            } catch (Exception ignored) {
            }
        }

        if (processes.isEmpty() && out.length() > 0) {
            lastError = appOnly ? "已读到 root 输出，但没有匹配到可见安卓应用；可切换为全部进程再试。" : "已读到 root 输出，但没有匹配到进程。";
        }

        LAST_TICKS.clear();
        for (ProcessInfo info : processes) {
            LAST_TICKS.put(info.pid, info.cpuTicks);
        }
        if (totalTicks > 0) {
            lastTotalTicks = totalTicks;
        }

        if (sortMode == 1) {
            processes.sort((a, b) -> Long.compare(b.rssKb, a.rssKb));
        } else {
            processes.sort((a, b) -> Double.compare(b.cpuPercent, a.cpuPercent));
        }
        return processes;
    }

    private static String readFromPs() {
        String script = "echo TOTAL\\|0; "
                + "ps -A -o PID,RSS,PCPU,NAME 2>/dev/null | while read pid rss pcpu name rest; do "
                + "[ \"$pid\" = \"PID\" ] && continue; "
                + "[ -z \"$pid\" ] && continue; "
                + "[ -z \"$name\" ] && name=\"$rest\"; "
                + "[ -z \"$name\" ] && continue; "
                + "pkg=${name%%:*}; "
                + "echo \"$pid|$pkg|$name|$rss|$pcpu\"; "
                + "done";
        String out = RootShell.run(script, 800);
        if (out.contains("|")) {
            return out;
        }
        script = "echo TOTAL\\|0; "
                + "ps -A 2>/dev/null | while read a b c d e f g h i; do "
                + "[ \"$a\" = \"USER\" ] && continue; "
                + "pid=\"$b\"; name=\"$i\"; rss=\"$e\"; "
                + "[ -z \"$name\" ] && name=\"$h\"; "
                + "[ -z \"$pid\" ] && continue; "
                + "pkg=${name%%:*}; "
                + "echo \"$pid|$pkg|$name|$rss|0\"; "
                + "done";
        return RootShell.run(script, 800);
    }

    private static String compact(String text) {
        if (text == null) {
            return "";
        }
        text = text.replace('\n', ' ').replace('\r', ' ').trim();
        return text.length() > 180 ? text.substring(0, 180) : text;
    }

    static String lastError() {
        return lastError;
    }

    static ProcessInfo readPid(Context context, int pid) {
        String script = "read c u n sy id io irq sirq rest < /proc/stat; total=$((u+n+sy+id+io+irq+sirq)); "
                + "d=/proc/" + pid + "; "
                + "[ -d \"$d\" ] || exit; "
                + "cmd=$(cat \"$d/cmdline\" 2>/dev/null); cmd=${cmd%%:*}; cmd=${cmd%% *}; "
                + "[ -z \"$cmd\" ] && cmd=$(cat \"$d/comm\" 2>/dev/null); "
                + "pkg=${cmd%%:*}; rss=0; "
                + "while read k v unit; do [ \"$k\" = \"VmRSS:\" ] && { rss=$v; break; }; done < \"$d/status\" 2>/dev/null; "
                + "line=$(cat \"$d/stat\" 2>/dev/null); right=${line#*) }; set -- $right; ticks=$(( $12 + $13 )); "
                + "echo \"$total|" + pid + "|$pkg|$cmd|$rss|$ticks\"";
        String out = RootShell.run(script, 1200);
        if (out.length() == 0 || !out.contains("|")) {
            out = RootShell.run("ps -p " + pid + " -o PID,RSS,PCPU,NAME 2>/dev/null | while read p rss pcpu name rest; do [ \"$p\" = \"PID\" ] && continue; pkg=${name%%:*}; echo \"0|$p|$pkg|$name|$rss|$pcpu\"; done", 1200);
        }
        String[] p = out.trim().split("\\|", 6);
        if (p.length < 6) return null;
        try {
            ProcessInfo info = new ProcessInfo();
            long total = Long.parseLong(p[0].trim());
            info.pid = Integer.parseInt(p[1].trim());
            info.packageName = p[2].trim().length() == 0 ? p[3].trim() : p[2].trim();
            info.processName = p[3].trim().length() == 0 ? info.packageName : p[3].trim();
            info.rssKb = Long.parseLong(p[4].trim());
            String cpuOrTicks = p[5].trim().replace("%", "");
            if (total > 0) {
                info.cpuTicks = Long.parseLong(cpuOrTicks);
                long oldTicks = LAST_TICKS.containsKey(info.pid) ? LAST_TICKS.get(info.pid) : info.cpuTicks;
                long totalDelta = Math.max(1, total - lastTotalTicks);
                long procDelta = Math.max(0, info.cpuTicks - oldTicks);
                info.cpuPercent = procDelta * Runtime.getRuntime().availableProcessors() * 100.0 / totalDelta;
                LAST_TICKS.put(info.pid, info.cpuTicks);
                lastTotalTicks = total;
            } else {
                info.cpuPercent = Double.parseDouble(cpuOrTicks);
            }
            try {
                ApplicationInfo appInfo = context.getPackageManager().getApplicationInfo(info.packageName, 0);
                info.installedApp = true;
                info.systemApp = (appInfo.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
            } catch (Exception ignored) {
            }
            return info;
        } catch (Exception ignored) {
            return null;
        }
    }

    static String label(Context context, String pkg) {
        try {
            PackageManager pm = context.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception ignored) {
            return pkg;
        }
    }
}
