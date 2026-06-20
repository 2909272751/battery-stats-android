package com.codex.batterystats;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class StatsDatabase extends SQLiteOpenHelper {
    private static final String DB_NAME = "battery_stats.db";
    private static final int DB_VERSION = 3;

    StatsDatabase(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE samples ("
                + "time_ms INTEGER PRIMARY KEY,"
                + "level INTEGER,"
                + "status INTEGER,"
                + "current_a REAL,"
                + "voltage_v REAL,"
                + "power_w REAL,"
                + "temp_c REAL,"
                + "screen_on INTEGER DEFAULT 0,"
                + "pkg TEXT)");
        db.execSQL("CREATE INDEX idx_samples_status_time ON samples(status, time_ms)");
        createAppUsageTable(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            createAppUsageTable(db);
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE samples ADD COLUMN screen_on INTEGER DEFAULT 0");
        }
    }

    private void createAppUsageTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS app_usage ("
                + "time_ms INTEGER,"
                + "pkg TEXT,"
                + "fg_ms INTEGER,"
                + "bg_ms INTEGER,"
                + "fg_wh REAL,"
                + "bg_wh REAL,"
                + "cpu_ticks INTEGER,"
                + "PRIMARY KEY(time_ms, pkg))");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_app_usage_time ON app_usage(time_ms)");
    }

    void insert(BatterySample sample) {
        ContentValues values = new ContentValues();
        values.put("time_ms", sample.timeMs);
        values.put("level", sample.level);
        values.put("status", sample.status);
        values.put("current_a", sample.currentA);
        values.put("voltage_v", sample.voltageV);
        values.put("power_w", BatteryReader.clampPower(sample.powerW, sample.isCharging()));
        values.put("temp_c", sample.tempC);
        values.put("screen_on", sample.screenOn ? 1 : 0);
        values.put("pkg", sample.foregroundPackage);
        getWritableDatabase().insertWithOnConflict("samples", null, values, SQLiteDatabase.CONFLICT_REPLACE);
        getWritableDatabase().delete("samples", "time_ms < ?", new String[]{String.valueOf(System.currentTimeMillis() - 1000L * 60 * 60 * 24 * 14)});
    }

    void insertAppUsage(long timeMs, String pkg, long fgMs, long bgMs, double fgWh, double bgWh, long cpuTicks) {
        if (pkg == null || pkg.length() == 0) {
            return;
        }
        ContentValues values = new ContentValues();
        values.put("time_ms", timeMs);
        values.put("pkg", pkg);
        values.put("fg_ms", fgMs);
        values.put("bg_ms", bgMs);
        values.put("fg_wh", fgWh);
        values.put("bg_wh", bgWh);
        values.put("cpu_ticks", cpuTicks);
        getWritableDatabase().insertWithOnConflict("app_usage", null, values, SQLiteDatabase.CONFLICT_REPLACE);
        getWritableDatabase().delete("app_usage", "time_ms < ?", new String[]{String.valueOf(System.currentTimeMillis() - 1000L * 60 * 60 * 24 * 14)});
    }

    BatterySample latest() {
        List<BatterySample> samples = query("SELECT * FROM samples ORDER BY time_ms DESC LIMIT 1", null);
        return samples.isEmpty() ? null : samples.get(0);
    }

    List<BatterySample> latestProcess(boolean charging) {
        BatterySample last = latest();
        if (last == null) {
            return new ArrayList<>();
        }
        boolean target = charging;
        List<BatterySample> recent = query("SELECT * FROM samples WHERE time_ms <= ? ORDER BY time_ms DESC LIMIT 2000",
                new String[]{String.valueOf(last.timeMs)});
        ArrayList<BatterySample> process = new ArrayList<>();
        for (BatterySample sample : recent) {
            if (sample.isCharging() != target && !process.isEmpty()) {
                break;
            }
            if (sample.isCharging() == target) {
                process.add(0, sample);
            } else if (process.isEmpty() && !target) {
                return process;
            }
        }
        return process;
    }

    List<AppUsage> appUsageForLatestDischarge(Context context) {
        List<BatterySample> samples = latestProcess(false);
        List<AppUsage> rooted = rootedAppUsage(context, samples);
        if (!rooted.isEmpty()) {
            return rooted;
        }
        HashMap<String, AppUsage> map = new HashMap<>();
        for (int i = 1; i < samples.size(); i++) {
            BatterySample prev = samples.get(i - 1);
            BatterySample cur = samples.get(i);
            long dt = Math.max(0, cur.timeMs - prev.timeMs);
            if (dt <= 0 || prev.foregroundPackage == null || prev.foregroundPackage.length() == 0) {
                continue;
            }
            AppUsage usage = map.get(prev.foregroundPackage);
            if (usage == null) {
                usage = new AppUsage();
                usage.pkg = prev.foregroundPackage;
                usage.label = LabelCache.label(context, prev.foregroundPackage);
                map.put(prev.foregroundPackage, usage);
            }
            usage.durationMs += dt;
            usage.foregroundMs += dt;
            usage.energyWh += prev.powerW * dt / 3600000.0;
            usage.foregroundWh += prev.powerW * dt / 3600000.0;
            usage.avgPowerNumerator += prev.powerW * dt;
            usage.maxTempC = Math.max(usage.maxTempC, prev.tempC);
        }
        ArrayList<AppUsage> list = new ArrayList<>(map.values());
        for (AppUsage usage : list) {
            usage.avgPowerW = usage.durationMs > 0 ? usage.avgPowerNumerator / usage.durationMs : 0;
        }
        list.sort((a, b) -> Double.compare(b.energyWh, a.energyWh));
        return list;
    }

    private List<AppUsage> rootedAppUsage(Context context, List<BatterySample> samples) {
        ArrayList<AppUsage> out = new ArrayList<>();
        if (samples.size() < 2) {
            return out;
        }
        long start = samples.get(0).timeMs;
        long end = samples.get(samples.size() - 1).timeMs;
        HashMap<String, AppUsage> map = new HashMap<>();
        String sql = "SELECT pkg, SUM(fg_ms) fg_ms, SUM(bg_ms) bg_ms, SUM(fg_wh) fg_wh, "
                + "SUM(bg_wh) bg_wh, SUM(cpu_ticks) cpu_ticks FROM app_usage "
                + "WHERE time_ms >= ? AND time_ms <= ? GROUP BY pkg";
        try (Cursor c = getReadableDatabase().rawQuery(sql, new String[]{String.valueOf(start), String.valueOf(end)})) {
            while (c.moveToNext()) {
                AppUsage usage = new AppUsage();
                usage.pkg = c.getString(c.getColumnIndexOrThrow("pkg"));
                usage.label = LabelCache.label(context, usage.pkg);
                usage.foregroundMs = c.getLong(c.getColumnIndexOrThrow("fg_ms"));
                usage.backgroundMs = c.getLong(c.getColumnIndexOrThrow("bg_ms"));
                usage.foregroundWh = c.getDouble(c.getColumnIndexOrThrow("fg_wh"));
                usage.backgroundWh = c.getDouble(c.getColumnIndexOrThrow("bg_wh"));
                usage.cpuTicks = c.getLong(c.getColumnIndexOrThrow("cpu_ticks"));
                usage.durationMs = usage.foregroundMs + usage.backgroundMs;
                usage.energyWh = usage.foregroundWh + usage.backgroundWh;
                if (usage.energyWh <= 0 && usage.cpuTicks <= 0) {
                    continue;
                }
                map.put(usage.pkg, usage);
            }
        }
        out.addAll(map.values());
        for (AppUsage usage : out) {
            usage.avgPowerW = usage.durationMs > 0 ? usage.energyWh * 3600000.0 / usage.durationMs : 0;
            usage.maxTempC = maxTemp(samples);
        }
        out.sort((a, b) -> Double.compare(b.energyWh, a.energyWh));
        return out;
    }

    void clear() {
        getWritableDatabase().delete("samples", null, null);
        getWritableDatabase().delete("app_usage", null, null);
    }

    private List<BatterySample> query(String sql, String[] args) {
        ArrayList<BatterySample> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(sql, args)) {
            while (c.moveToNext()) {
                BatterySample sample = new BatterySample();
                sample.timeMs = c.getLong(c.getColumnIndexOrThrow("time_ms"));
                sample.level = c.getInt(c.getColumnIndexOrThrow("level"));
                sample.status = c.getInt(c.getColumnIndexOrThrow("status"));
                sample.currentA = c.getDouble(c.getColumnIndexOrThrow("current_a"));
                sample.voltageV = c.getDouble(c.getColumnIndexOrThrow("voltage_v"));
                sample.powerW = BatteryReader.clampPower(c.getDouble(c.getColumnIndexOrThrow("power_w")), sample.isCharging());
                sample.tempC = c.getDouble(c.getColumnIndexOrThrow("temp_c"));
                int screenIndex = c.getColumnIndex("screen_on");
                sample.screenOn = screenIndex >= 0 && c.getInt(screenIndex) != 0;
                sample.foregroundPackage = c.getString(c.getColumnIndexOrThrow("pkg"));
                out.add(sample);
            }
        }
        return out;
    }

    static final class AppUsage {
        String pkg;
        String label;
        long durationMs;
        long foregroundMs;
        long backgroundMs;
        double energyWh;
        double foregroundWh;
        double backgroundWh;
        double avgPowerW;
        double avgPowerNumerator;
        double maxTempC;
        long cpuTicks;

        String subtitle() {
            return String.format(Locale.CHINA, "\u524d\u53f0 %.3fWh  \u540e\u53f0 %.3fWh", foregroundWh, backgroundWh);
        }

        String detail() {
            return String.format(Locale.CHINA, "AVG: %.2fW, \u540e\u53f0 %s", avgPowerW, BatteryReader.formatDuration(backgroundMs));
        }
    }

    private static double maxTemp(List<BatterySample> samples) {
        double max = 0;
        for (BatterySample sample : samples) {
            max = Math.max(max, sample.tempC);
        }
        return max;
    }

    private static final class LabelCache {
        private static final Map<String, String> CACHE = new HashMap<>();

        static String label(Context context, String pkg) {
            if (CACHE.containsKey(pkg)) {
                return CACHE.get(pkg);
            }
            String label = pkg;
            try {
                android.content.pm.PackageManager pm = context.getPackageManager();
                label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
            } catch (Exception ignored) {
            }
            CACHE.put(pkg, label);
            return label;
        }
    }
}
