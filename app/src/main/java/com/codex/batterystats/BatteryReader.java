package com.codex.batterystats;

import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.PowerManager;
import android.text.TextUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.Locale;

final class BatteryReader {
    private final Context context;
    private String cachedForeground = "";
    private long cachedForegroundAt = 0;

    BatteryReader(Context context) {
        this.context = context.getApplicationContext();
    }

    BatterySample read() {
        Intent battery = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        BatterySample sample = new BatterySample();
        sample.timeMs = System.currentTimeMillis();
        if (battery == null) {
            return sample;
        }
        int scale = Math.max(1, battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100));
        sample.level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) * 100 / scale;
        sample.status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN);
        int plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        if (plugged != 0 && sample.status != BatteryManager.BATTERY_STATUS_FULL) {
            sample.status = BatteryManager.BATTERY_STATUS_CHARGING;
        }
        sample.tempC = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0;

        double voltageUv = readFirstPositive(
                "/sys/class/power_supply/battery/voltage_now",
                "/sys/class/power_supply/bms/voltage_now");
        if (voltageUv <= 0) {
            voltageUv = battery.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) * 1000.0;
        }
        sample.voltageV = normalizeVoltage(voltageUv);

        double currentUa = readFirstNonZero(
                "/sys/class/power_supply/battery/current_now",
                "/sys/class/power_supply/bms/current_now",
                "/sys/class/power_supply/maxfg/current_now");
        if (currentUa == 0) {
            BatteryManager manager = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
            if (manager != null) {
                currentUa = manager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
            }
        }
        sample.currentA = normalizeCurrent(currentUa);
        if (sample.isCharging() && sample.currentA < 0) {
            sample.currentA = -sample.currentA;
        }
        if (!sample.isCharging() && sample.currentA > 0) {
            sample.currentA = -sample.currentA;
        }
        sample.powerW = sample.voltageV * Math.abs(sample.currentA);
        sample.screenOn = isScreenOn();
        sample.foregroundPackage = foregroundPackage();
        return sample;
    }

    private boolean isScreenOn() {
        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isInteractive();
        } catch (Exception ignored) {
            return false;
        }
    }

    private String foregroundPackage() {
        long now = System.currentTimeMillis();
        if (!TextUtils.isEmpty(cachedForeground) && now - cachedForegroundAt < 60000) {
            return cachedForeground;
        }
        String root = RootShell.run("dumpsys window | grep -E 'mCurrentFocus|mFocusedApp' | head -n 1", 900);
        String parsed = parsePackage(root);
        if (!TextUtils.isEmpty(parsed)) {
            cachedForeground = parsed;
            cachedForegroundAt = now;
            return cachedForeground;
        }
        UsageStatsManager usm = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
        if (usm == null) {
            return "";
        }
        long end = System.currentTimeMillis();
        UsageEvents events = usm.queryEvents(end - 120000, end);
        UsageEvents.Event event = new UsageEvents.Event();
        String current = "";
        while (events.hasNextEvent()) {
            events.getNextEvent(event);
            if (event.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND
                    || event.getEventType() == UsageEvents.Event.ACTIVITY_RESUMED) {
                current = event.getPackageName();
            }
        }
        cachedForeground = current == null ? "" : current;
        cachedForegroundAt = now;
        return cachedForeground;
    }

    private static String parsePackage(String text) {
        if (TextUtils.isEmpty(text)) {
            return "";
        }
        String[] parts = text.split("[ /}]");
        for (String part : parts) {
            if (part.contains(".") && !part.startsWith("u0") && !part.contains("=")) {
                return part.trim();
            }
        }
        return "";
    }

    private static double normalizeVoltage(double raw) {
        double value = Math.abs(raw);
        if (value > 100000) {
            return value / 1000000.0;
        }
        if (value > 100) {
            return value / 1000.0;
        }
        return value;
    }

    private static double normalizeCurrent(double raw) {
        double abs = Math.abs(raw);
        if (abs > 100000) {
            return raw / 1000000.0;
        }
        if (abs > 10000) {
            return raw / 1000000.0;
        }
        if (abs > 100) {
            return raw / 1000.0;
        }
        return raw;
    }

    private static double readFirstPositive(String... paths) {
        for (String path : paths) {
            double v = readDouble(path);
            if (v > 0) {
                return v;
            }
        }
        return 0;
    }

    private static double readFirstNonZero(String... paths) {
        for (String path : paths) {
            double v = readDouble(path);
            if (Math.abs(v) > 0.001) {
                return v;
            }
        }
        return 0;
    }

    private static double readDouble(String path) {
        try (BufferedReader reader = new BufferedReader(new FileReader(new File(path)))) {
            return Double.parseDouble(reader.readLine().trim());
        } catch (Exception ignored) {
            return 0;
        }
    }

    static String formatDuration(long ms) {
        long minutes = Math.max(0, ms / 60000);
        long h = minutes / 60;
        long m = minutes % 60;
        if (h > 0) {
            return String.format(Locale.CHINA, "%dh%02dm", h, m);
        }
        return String.format(Locale.CHINA, "%dm", m);
    }
}
