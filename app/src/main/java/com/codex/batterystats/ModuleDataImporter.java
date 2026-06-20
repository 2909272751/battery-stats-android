package com.codex.batterystats;

import android.os.BatteryManager;

final class ModuleDataImporter {
    private static long lastImportAt;
    private static boolean lastOk;

    private ModuleDataImporter() {
    }

    static boolean importRecent(StatsDatabase database) {
        long now = System.currentTimeMillis();
        if (now - lastImportAt < 3000) {
            return lastOk;
        }
        lastImportAt = now;
        String csv = RootShell.run("tail -n 2500 /data/adb/battery_stats/samples.csv 2>/dev/null", 2000);
        if (csv.length() == 0 || !csv.contains(",")) {
            lastOk = false;
            return false;
        }
        int imported = 0;
        String[] lines = csv.split("\\n");
        for (String line : lines) {
            if (line.startsWith("time_ms") || line.trim().length() == 0) {
                continue;
            }
            String[] p = line.split(",", 9);
            if (p.length < 7) {
                continue;
            }
            try {
                BatterySample sample = new BatterySample();
                sample.timeMs = Long.parseLong(p[0].trim());
                sample.level = Integer.parseInt(p[1].trim());
                sample.status = Integer.parseInt(p[2].trim());
                if (sample.status <= 0) {
                    sample.status = BatteryManager.BATTERY_STATUS_UNKNOWN;
                }
                sample.currentA = Double.parseDouble(p[3].trim());
                sample.voltageV = Double.parseDouble(p[4].trim());
                sample.powerW = BatteryReader.clampPower(Double.parseDouble(p[5].trim()), sample.isCharging());
                sample.tempC = Double.parseDouble(p[6].trim());
                if (p.length >= 9) {
                    sample.screenOn = "1".equals(p[7].trim());
                    sample.foregroundPackage = p[8].trim();
                } else {
                    sample.foregroundPackage = p.length >= 8 ? p[7].trim() : "";
                    sample.screenOn = sample.foregroundPackage != null && sample.foregroundPackage.length() > 0;
                }
                database.insert(sample);
                imported++;
            } catch (Exception ignored) {
            }
        }
        imported += importAppUsage(database);
        lastOk = imported > 0;
        return lastOk;
    }

    private static int importAppUsage(StatsDatabase database) {
        String csv = RootShell.run("tail -n 5000 /data/adb/battery_stats/app_usage.csv 2>/dev/null", 2000);
        if (csv.length() == 0 || !csv.contains(",")) {
            return 0;
        }
        int imported = 0;
        String[] lines = csv.split("\\n");
        for (String line : lines) {
            if (line.startsWith("time_ms") || line.trim().length() == 0) {
                continue;
            }
            String[] p = line.split(",", 7);
            if (p.length < 7) {
                continue;
            }
            try {
                database.insertAppUsage(
                        Long.parseLong(p[0].trim()),
                        p[1].trim(),
                        Long.parseLong(p[2].trim()),
                        Long.parseLong(p[3].trim()),
                        Double.parseDouble(p[4].trim()),
                        Double.parseDouble(p[5].trim()),
                        Long.parseLong(p[6].trim()));
                imported++;
            } catch (Exception ignored) {
            }
        }
        return imported;
    }
}
