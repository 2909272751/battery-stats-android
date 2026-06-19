package com.codex.batterystats;

final class ProcessInfo {
    int pid;
    String packageName;
    String processName;
    long rssKb;
    long cpuTicks;
    double cpuPercent;
    boolean installedApp;
    boolean systemApp;

    String resText() {
        if (rssKb >= 1024) {
            return Math.round(rssKb / 1024.0) + "MB";
        }
        return rssKb + "KB";
    }
}
