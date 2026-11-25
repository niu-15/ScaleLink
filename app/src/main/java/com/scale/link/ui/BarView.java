package com.scale.link.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * 横向圆角进度条（目标完成度），填充为横向渐变。
 */
public class BarView extends View {
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private float progress = 0f;
    private int barFrom, barTo;

    public BarView(Context c) {
        super(c);
        track.setStyle(Paint.Style.FILL);
        bar.setStyle(Paint.Style.FILL);
    }

    public void setColors(int trackColor, int barColor) {
        track.setColor(trackColor);
        barFrom = barTo = barColor;
        invalidate();
    }

    public void setGradient(int from, int to) {
        barFrom = from;
        barTo = to;
        invalidate();
    }

    public void setProgress(float p) {
        progress = Math.max(0f, Math.min(1f, p));
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        float rad = h / 2f;
        r.set(0, 0, w, h);
        canvas.drawRoundRect(r, rad, rad, track);
        if (progress > 0.005f) {
            float fw = Math.max(h, w * progress);
            // 纯色填充，不用渐变
            bar.setColor(barTo);
            r.set(0, 0, fw, h);
            canvas.drawRoundRect(r, rad, rad, bar);
        }
    }
}
