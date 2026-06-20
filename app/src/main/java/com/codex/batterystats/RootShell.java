package com.codex.batterystats;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStreamReader;

final class RootShell {
    private RootShell() {
    }

    static String run(String command, int timeoutMs) {
        Process process = null;
        try {
            process = new ProcessBuilder("su").redirectErrorStream(true).start();
            DataOutputStream os = new DataOutputStream(process.getOutputStream());
            os.writeBytes(command + "\n");
            os.writeBytes("exit\n");
            os.flush();
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                try {
                    int exit = process.exitValue();
                    BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                    StringBuilder out = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        out.append(line).append('\n');
                    }
                    return exit == 0 ? out.toString().trim() : "";
                } catch (IllegalThreadStateException ignored) {
                    Thread.sleep(30);
                }
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
}
