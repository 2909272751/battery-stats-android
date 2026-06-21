package com.codex.batterystats;

final class ProcessInfo {
    int pid;
    String packageName;
    String processName;
    String friendlyName;
    String user;
    String state;
    String command;
    String cmdline;
    String cpuSet;
    String cGroup;
    String cpusAllowed;
    String oomAdj;
    String oomScore;
    String oomScoreAdj;
    int parentPid;
    long rssKb;
    long shrKb;
    long swapKb;
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

    String shrText() {
        return sizeText(shrKb);
    }

    String swapText() {
        return sizeText(swapKb);
    }

    String displayName() {
        if (friendlyName != null && friendlyName.length() > 0) {
            return friendlyName;
        }
        if (packageName != null && packageName.length() > 0) {
            return packageName;
        }
        return processName == null ? "" : processName;
    }

    String stateText() {
        if (state == null || state.length() == 0) {
            return "--";
        }
        String s = state.substring(0, 1);
        if ("R".equals(s)) return "运行中";
        if ("S".equals(s)) return "睡眠";
        if ("D".equals(s)) return "不可中断睡眠";
        if ("T".equals(s) || "t".equals(s)) return "已停止";
        if ("Z".equals(s)) return "僵尸";
        if ("I".equals(s)) return "空闲";
        return state;
    }

    private static String sizeText(long kb) {
        if (kb <= 0) {
            return "0KB";
        }
        if (kb >= 1024) {
            return Math.round(kb / 1024.0) + "MB";
        }
        return kb + "KB";
    }
}
