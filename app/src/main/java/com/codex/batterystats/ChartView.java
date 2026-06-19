package com.codex.batterystats;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class ChartView extends View {
    static final int TYPE_POWER_TIME = 1;
    static final int TYPE_LEVEL_TIME = 2;
    static final int TYPE_CURRENT_LEVEL = 3;
    static final int TYPE_USAGE = 4;
    static final int TYPE_TEMP_TIME = 5;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private List<BatterySample> samples = new ArrayList<>();
    private int type = TYPE_POWER_TIME;

    public ChartView(Context context) {
        super(context);
    }

    public ChartView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    void setData(List<BatterySample> samples, int type) {
        this.samples = samples == null ? new ArrayList<>() : samples;
        this.type = type;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        float left = dp(36);
        float top = dp(12);
        float right = w - dp(18);
        float bottom = h - dp(28);
        drawGrid(canvas, left, top, right, bottom);
        if (samples.size() < 2) {
            paint.setColor(Color.rgb(160, 160, 160));
            paint.setTextSize(dp(13));
            canvas.drawText("绛夊緟閲囨牱鏁版嵁", left + dp(10), (top + bottom) / 2, paint);
            return;
        }
        drawLabels(canvas, left, top, right, bottom);
        drawLine(canvas, left, top, right, bottom, false);
        if (type == TYPE_POWER_TIME) {
            drawLine(canvas, left, top, right, bottom, true);
        }
    }

    private void drawGrid(Canvas canvas, float left, float top, float right, float bottom) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1);
        paint.setColor(Color.rgb(230, 232, 235));
        for (int i = 0; i <= 5; i++) {
            float y = top + (bottom - top) * i / 5f;
            canvas.drawLine(left, y, right, y, paint);
        }
        for (int i = 0; i <= 6; i++) {
            float x = left + (right - left) * i / 6f;
            canvas.drawLine(x, top, x, bottom, paint);
        }
    }

    private void drawLabels(Canvas canvas, float left, float top, float right, float bottom) {
        paint.setStyle(Paint.Style.FILL);
        paint.setTextSize(dp(11));
        paint.setColor(Color.rgb(145, 148, 153));
        String yTop;
        String yMid;
        if (type == TYPE_POWER_TIME) {
            yTop = String.format(Locale.CHINA, "%.0fW", Math.max(5, maxPower()));
            yMid = "0W";
        } else if (type == TYPE_CURRENT_LEVEL) {
            yTop = String.format(Locale.CHINA, "%.0fA", Math.max(1, maxCurrent()));
            yMid = "0A";
        } else if (type == TYPE_TEMP_TIME) {
            yTop = String.format(Locale.CHINA, "%.0f掳C", Math.max(40, maxTemp()));
            yMid = String.format(Locale.CHINA, "%.0f掳C", Math.max(0, minTemp()));
        } else {
            yTop = "100%";
            yMid = "0%";
        }
        canvas.drawText(yTop, dp(3), top + dp(4), paint);
        canvas.drawText(yMid, dp(8), bottom, paint);
        canvas.drawText("0", left, bottom + dp(20), paint);
        canvas.drawText(BatteryReader.formatDuration(samples.get(samples.size() - 1).timeMs - samples.get(0).timeMs),
                right - dp(34), bottom + dp(20), paint);
    }

    private void drawLine(Canvas canvas, float left, float top, float right, float bottom, boolean secondary) {
        double minX = type == TYPE_CURRENT_LEVEL ? minLevel() : samples.get(0).timeMs;
        double maxX = type == TYPE_CURRENT_LEVEL ? maxLevel() : samples.get(samples.size() - 1).timeMs;
        if (Math.abs(maxX - minX) < 0.001) {
            maxX = minX + 1;
        }
        double maxY = maxY(secondary);
        if (maxY <= 0) {
            maxY = 1;
        }
        path.reset();
        for (int i = 0; i < samples.size(); i++) {
            BatterySample s = samples.get(i);
            double vx = type == TYPE_CURRENT_LEVEL ? s.level : s.timeMs;
            double vy = valueY(s, secondary);
            float x = (float) (left + (vx - minX) / (maxX - minX) * (right - left));
            float y = (float) (bottom - Math.max(0, Math.min(1, vy / maxY)) * (bottom - top));
            if (i == 0) {
                path.moveTo(x, y);
            } else {
                path.lineTo(x, y);
            }
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeWidth(secondary ? dp(2) : dp(3));
        paint.setColor(secondary ? Color.argb(90, 22, 137, 216) : Color.rgb(22, 137, 216));
        canvas.drawPath(path, paint);
    }

    private double valueY(BatterySample s, boolean secondary) {
        if (secondary) {
            return s.level;
        }
        if (type == TYPE_LEVEL_TIME || type == TYPE_USAGE) {
            return s.level;
        }
        if (type == TYPE_CURRENT_LEVEL) {
            return Math.abs(s.currentA);
        }
        if (type == TYPE_TEMP_TIME) {
            return s.tempC - Math.max(0, minTemp());
        }
        return s.powerW;
    }

    private double maxY(boolean secondary) {
        if (secondary || type == TYPE_LEVEL_TIME || type == TYPE_USAGE) {
            return 100;
        }
        if (type == TYPE_CURRENT_LEVEL) {
            return Math.max(1, maxCurrent() * 1.15);
        }
        if (type == TYPE_TEMP_TIME) {
            return Math.max(5, maxTemp() - Math.max(0, minTemp()));
        }
        return Math.max(5, maxPower() * 1.15);
    }

    private double maxPower() {
        double max = 0;
        for (BatterySample s : samples) {
            max = Math.max(max, s.powerW);
        }
        return max;
    }

    private double maxCurrent() {
        double max = 0;
        for (BatterySample s : samples) {
            max = Math.max(max, Math.abs(s.currentA));
        }
        return max;
    }

    private double maxTemp() {
        double max = 0;
        for (BatterySample s : samples) {
            max = Math.max(max, s.tempC);
        }
        return max;
    }

    private double minTemp() {
        double min = 100;
        for (BatterySample s : samples) {
            if (s.tempC > 0) {
                min = Math.min(min, s.tempC);
            }
        }
        return min == 100 ? 0 : min;
    }

    private double minLevel() {
        double min = 100;
        for (BatterySample s : samples) {
            min = Math.min(min, s.level);
        }
        return min;
    }

    private double maxLevel() {
        double max = 0;
        for (BatterySample s : samples) {
            max = Math.max(max, s.level);
        }
        return max;
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
