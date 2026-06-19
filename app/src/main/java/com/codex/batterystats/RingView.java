package com.codex.batterystats;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

public class RingView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int level = 0;

    public RingView(Context context) {
        super(context);
    }

    void setLevel(int level) {
        this.level = Math.max(0, Math.min(100, level));
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float size = Math.min(getWidth(), getHeight()) - dp(18);
        float left = (getWidth() - size) / 2f;
        float top = (getHeight() - size) / 2f;
        RectF oval = new RectF(left, top, left + size, top + size);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(16));
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(Color.rgb(238, 238, 238));
        canvas.drawArc(oval, 135, 270, false, paint);
        paint.setColor(Color.rgb(21, 202, 203));
        canvas.drawArc(oval, 135, 270 * level / 100f, false, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setFakeBoldText(true);
        paint.setTextSize(dp(31));
        paint.setColor(Color.rgb(70, 72, 76));
        canvas.drawText(level + "%", getWidth() / 2f, getHeight() / 2f + dp(11), paint);
        paint.setFakeBoldText(false);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
