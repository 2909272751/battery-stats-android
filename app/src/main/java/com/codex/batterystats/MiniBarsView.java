package com.codex.batterystats;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

public class MiniBarsView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float[] values = new float[]{0.30f, 0.45f, 0.38f, 0.56f, 0.42f, 0.33f, 0.24f, 0.18f};

    public MiniBarsView(Context context) {
        super(context);
    }

    void setValues(float[] values) {
        if (values != null && values.length > 0) {
            this.values = values;
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float gap = dp(3);
        float barW = Math.max(dp(4), (getWidth() - gap * (values.length - 1)) / values.length);
        paint.setColor(Color.rgb(177, 211, 247));
        for (int i = 0; i < values.length; i++) {
            float v = Math.max(0.06f, Math.min(1f, values[i]));
            float left = i * (barW + gap);
            float top = getHeight() - getHeight() * v;
            canvas.drawRoundRect(left, top, left + barW, getHeight(), dp(2), dp(2), paint);
        }
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
