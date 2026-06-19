package com.codex.batterystats;

import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;

public class BatteryMonitorService extends Service {
    private HandlerThread thread;
    private Handler handler;
    private BatteryReader reader;
    private StatsDatabase database;
    private PowerManager.WakeLock wakeLock;

    private final Runnable sampler = new Runnable() {
        @Override
        public void run() {
            BatterySample sample = reader.read();
            database.insert(sample);
            handler.postDelayed(this, nextDelay(sample));
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        reader = new BatteryReader(this);
        database = new StatsDatabase(this);
        thread = new HandlerThread("battery-sampler");
        thread.start();
        handler = new Handler(thread.getLooper());
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BatteryStats:Sampler");
            wakeLock.setReferenceCounted(false);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        handler.removeCallbacks(sampler);
        handler.post(sampler);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
        }
        if (thread != null) {
            thread.quitSafely();
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private long nextDelay(BatterySample sample) {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        boolean interactive = pm != null && pm.isInteractive();
        if (sample != null && sample.isCharging()) {
            return interactive ? 10000 : 30000;
        }
        return interactive ? 15000 : 60000;
    }
}
