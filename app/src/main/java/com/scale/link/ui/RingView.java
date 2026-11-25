package com.scale.link.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

/**
 * 圆环进度（身体评分）：渐变描边 + 圆头，带轻微层次。
 */
public class RingView extends View {
    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint numPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();

    private float progress = 0f;
    private String center = "--";
    private String label = "";
    private final float startAngle = -90f;
    private int gradFrom, gradTo;

    public RingView(Context c) {
        super(c);
        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setStrokeCap(Paint.Cap.ROUND);
        barPaint.setStyle(Paint.Style.STROKE);
        barPaint.setStrokeCap(Paint.Cap.ROUND);
        numPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setTextAlign(Paint.Align.CENTER);
        numPaint.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
    }

    public void setColors(int track, int bar, int numColor, int labelColor) {
        trackPaint.setColor(track);
        barPaint.setColor(bar);
        numPaint.setColor(numColor);
        labelPaint.setColor(labelColor);
        gradFrom = gradTo = bar;
        invalidate();
    }

    /** 弧形渐变（从浅到深，视觉更贵气） */
    public void setGradient(int from, int to) {
        gradFrom = from;
        gradTo = to;
        invalidate();
    }

    public void set(float progress, String center, String label) {
        this.progress = Math.max(0f, Math.min(1f, progress));
        this.center = center;
        this.label = label;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        int size = Math.min(w, h);
        float stroke = size * 0.085f;
        trackPaint.setStrokeWidth(stroke);
        barPaint.setStrokeWidth(stroke);

        float inset = stroke / 2f + 1;
        oval.set((w - size) / 2f + inset, (h - size) / 2f + inset,
                (w + size) / 2f - inset, (h + size) / 2f - inset);

        canvas.drawArc(oval, 0, 360, false, trackPaint);
        if (progress > 0) {
            // 纯色弧，不用渐变
            barPaint.setShader(null);
            barPaint.setColor(gradTo);
            canvas.drawArc(oval, startAngle, 360f * progress, false, barPaint);
        }

        float cx = w / 2f;
        float cy = h / 2f;
        numPaint.setTextSize(size * 0.30f);
        canvas.drawText(center, cx, cy + numPaint.getTextSize() * 0.34f, numPaint);

        if (label != null && !label.isEmpty()) {
            labelPaint.setTextSize(size * 0.105f);
            canvas.drawText(label, cx, cy + numPaint.getTextSize() * 0.36f + size * 0.16f, labelPaint);
        }
    }
}
