package com.codex.batterystats;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileReader;
import java.io.InputStreamReader;

final class RootShell {
    private RootShell() {
    }

    static String run(String command, int timeoutMs) {
        Process process = null;
        StringBuilder out = new StringBuilder();
        try {
            process = new ProcessBuilder("su").redirectErrorStream(true).start();
            Process finalProcess = process;
            Thread readerThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(finalProcess.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        synchronized (out) {
                            out.append(line).append('\n');
                        }
                    }
                } catch (Exception ignored) {
                }
            }, "root-shell-reader");
            readerThread.setDaemon(true);
            readerThread.start();
            DataOutputStream os = new DataOutputStream(process.getOutputStream());
            os.writeBytes(command + "\n");
            os.writeBytes("exit\n");
            os.flush();
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                try {
                    int exit = process.exitValue();
                    try {
                        readerThread.join(80);
                    } catch (Exception ignored) {
                    }
                    synchronized (out) {
                        return exit == 0 ? out.toString().trim() : "";
                    }
                } catch (IllegalThreadStateException ignored) {
                    Thread.sleep(30);
                }
            }
            synchronized (out) {
                return out.toString().trim();
            }
        } catch (Exception ignored) {
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
        return "";
    }

    static boolean isAvailable() {
        return "root".equals(run("id -un", 1200).trim());
    }

    static boolean isStatsDaemonRunning() {
        String cmd = "PID=/data/adb/battery_stats/daemon.pid; "
                + "[ -f $PID ] && kill -0 $(cat $PID) 2>/dev/null && echo running || echo stopped";
        return "running".equals(run(cmd, 1200).trim());
    }

    static boolean setStatsDaemonEnabled(boolean enabled) {
        String moduleService = "/data/adb/modules/battery_stats/service.sh";
        String apModuleService = "/data/adb/ap/modules/battery_stats/service.sh";
        String cmd;
        if (enabled) {
            cmd = "mkdir -p /data/adb/battery_stats; "
                    + "echo 1 > /data/adb/battery_stats/enabled; "
                    + "PID=/data/adb/battery_stats/daemon.pid; "
                    + "if [ -f $PID ] && kill -0 $(cat $PID) 2>/dev/null; then echo running; "
                    + "elif [ -f " + moduleService + " ]; then nohup sh " + moduleService + " >/dev/null 2>&1 & echo started; "
                    + "elif [ -f " + apModuleService + " ]; then nohup sh " + apModuleService + " >/dev/null 2>&1 & echo started; "
                    + "else echo missing; fi";
        } else {
            cmd = "mkdir -p /data/adb/battery_stats; "
                    + "echo 0 > /data/adb/battery_stats/enabled; "
                    + "PID=/data/adb/battery_stats/daemon.pid; "
                    + "[ -f $PID ] && kill $(cat $PID) 2>/dev/null; echo stopped";
        }
        String out = run(cmd, 2000);
        return out.contains("running") || out.contains("started") || out.contains("stopped");
    }

    static void setRealtimePageRequested(boolean enabled) {
        String value = enabled ? "1" : "0";
        run("mkdir -p /data/adb/battery_stats; "
                + "echo '" + value + " '$(date +%s) > /data/adb/battery_stats/realtime_page", 1200);
    }

    static void setProcessListRequested(boolean enabled) {
        String value = enabled ? "1" : "0";
        run("mkdir -p /data/adb/battery_stats; "
                + "echo '" + value + " '$(date +%s) > /data/adb/battery_stats/process_list_request", 900);
    }

    static String moduleStatusText() {
        return run("cat /data/adb/battery_stats/module_status 2>/dev/null", 900);
    }

    static String chargeSessionText() {
        return run("tail -n 2500 /data/adb/battery_stats/charge_session.csv 2>/dev/null", 1600);
    }

    static String dischargeSessionText() {
        return run("tail -n 3500 /data/adb/battery_stats/discharge_session.csv 2>/dev/null", 1800);
    }

    static String processListText() {
        String publicCopy = readTextFile("/data/local/tmp/battery_stats_process_list.csv", 512 * 1024);
        if (isFreshProcessList(publicCopy)) {
            return publicCopy;
        }
        String direct = readTextFile("/data/adb/battery_stats/process_list.csv", 512 * 1024);
        if (isFreshProcessList(direct)) {
            return direct;
        }
        String rooted = run("cat /data/adb/battery_stats/process_list.csv 2>/dev/null", 500);
        return isFreshProcessList(rooted) ? rooted : "";
    }

    static String processTopText() {
        String publicCopy = readTextFile("/data/local/tmp/battery_stats_process_top.csv", 128 * 1024);
        if (publicCopy.length() > 0) {
            return publicCopy;
        }
        return run("cat /data/adb/battery_stats/process_top.csv 2>/dev/null", 700);
    }

    private static String readTextFile(String path, int maxChars) {
        File file = new File(path);
        if (!file.isFile() || !file.canRead()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (out.length() + line.length() + 1 > maxChars) {
                    break;
                }
                out.append(line).append('\n');
            }
            return out.toString().trim();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static boolean isFreshProcessList(String text) {
        if (text == null || text.length() == 0 || !text.contains("|")) {
            return false;
        }
        int end = text.indexOf('\n');
        String first = end >= 0 ? text.substring(0, end) : text;
        if (!first.startsWith("TS|")) {
            return true;
        }
        String[] parts = first.split("\\|");
        if (parts.length < 2) {
            return false;
        }
        try {
            long ts = Long.parseLong(parts[1].trim());
            long now = System.currentTimeMillis() / 1000L;
            return Math.abs(now - ts) <= 4;
        } catch (Exception ignored) {
            return false;
        }
    }
}
