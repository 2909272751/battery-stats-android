package com.codex.batterystats;

final class BatterySample {
    long timeMs;
    int level;
    int status;
    double currentA;
    double voltageV;
    double powerW;
    double tempC;
    boolean screenOn;
    String foregroundPackage;

    boolean isCharging() {
        return status == android.os.BatteryManager.BATTERY_STATUS_CHARGING
                || status == android.os.BatteryManager.BATTERY_STATUS_FULL;
    }
}
