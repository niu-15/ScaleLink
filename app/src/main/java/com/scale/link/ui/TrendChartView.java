package com.scale.link.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 趋势折线（自绘，无第三方库）：平滑曲线 + 渐变面积 + 目标线 + 触摸查看数值。
 */
public class TrendChartView extends View {

    /** 图表数据点 */
    public static class Pt {
        public final long t;
        public final float v;
        public Pt(long t, float v) { this.t = t; this.v = v; }
    }

    private final List<Pt> pts = new ArrayList<>();
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint targetPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bubblePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private String unit = "kg";
    private String metricLabel = "体重";
    private float target = 0;
    private int accent = 0xFF3E5C6B;
    private int textDim = 0xFF5F6C73;
    private int surface = 0xFFFFFFFF;
    private int border = 0xFFE3E8EC;

    private int touchIndex = -1;
    private final SimpleDateFormat dayFmt = new SimpleDateFormat("MM/dd", Locale.getDefault());
    private final SimpleDateFormat hmFmt = new SimpleDateFormat("HH:mm", Locale.getDefault());

    public TrendChartView(Context c) {
        super(c);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(2.6f * c.getResources().getDisplayMetrics().density);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setStrokeJoin(Paint.Join.ROUND);
        fillPaint.setStyle(Paint.Style.FILL);
        dotPaint.setStyle(Paint.Style.FILL);
        targetPaint.setStyle(Paint.Style.STROKE);
        targetPaint.setStrokeWidth(1.4f * c.getResources().getDisplayMetrics().density);
        targetPaint.setPathEffect(new DashPathEffect(new float[]{8, 7}, 0));
        bubblePaint.setStyle(Paint.Style.FILL);
        textPaint.setTextAlign(Paint.Align.CENTER);
    }

    public void setStyle(Theme t) {
        accent = t.accent;
        textDim = t.textDim;
        surface = t.surface;
        border = t.border;
        linePaint.setColor(accent);
        dotPaint.setColor(accent);
        // 玻璃卡片上的网格要足够淡但不消失
        gridPaint.setColor(Ui.alpha(t.text, t.dark ? 0.10f : 0.07f));
        textPaint.setColor(t.textDim);
        targetPaint.setColor(t.warn);
        invalidate();
    }

    public void setSeries(List<Pt> data, String unit, String metricLabel, double targetValue) {
        pts.clear();
        if (data != null) pts.addAll(data);
        this.unit = unit == null ? "" : unit;
        this.metricLabel = metricLabel == null ? "" : metricLabel;
        this.target = (float) targetValue;
        touchIndex = -1;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth(), h = getHeight();
        if (w < 20 || h < 20) return;
        float d = getResources().getDisplayMetrics().density;

        float padL = 34 * d, padR = 10 * d, padT = 26 * d, padB = 26 * d;
        float cw = w - padL - padR;
        float ch = h - padT - padB;

        textPaint.setTextSize(10.5f * d);

        if (pts.isEmpty()) {
            textPaint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText("这个区间还没有数据", w / 2f, h / 2f - 4 * d, textPaint);
            textPaint.setTextSize(9.5f * d);
            canvas.drawText("连接体脂秤称一次，或手动添加记录", w / 2f, h / 2f + 14 * d, textPaint);
            return;
        }

        float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
        for (Pt p : pts) {
            if (p.v < min) min = p.v;
            if (p.v > max) max = p.v;
        }
        if (target > 0) {
            if (target < min) min = target;
            if (target > max) max = target;
        }
        // Y 轴刻度取整到「整数步长」。
        // 原来是把 min~max 直接三等分再各自取整，会出现
        // 139.1 / 138.9 / 138.7 / 138.6 这种一节 0.2、一节 0.1 的刻度
        float dataSpan = max - min;
        if (dataSpan < 0.4f) dataSpan = 0.4f;
        float step = niceStep(dataSpan * 1.35f / 3f);
        min = (float) Math.floor(min / step) * step;
        int lines = Math.max(3, (int) Math.ceil((max - min) / step - 0.001f));
        max = min + step * lines;
        float span = max - min;

        // 横向网格 + Y 轴标签
        boolean oneDecimal = step < 1f;
        textPaint.setTextAlign(Paint.Align.RIGHT);
        for (int i = 0; i <= lines; i++) {
            float y = padT + ch * i / (float) lines;
            canvas.drawLine(padL, y, padL + cw, y, gridPaint);
            float v = max - step * i;
            String s = String.format(Locale.getDefault(), oneDecimal ? "%.1f" : "%.0f", v);
            canvas.drawText(s, padL - 6 * d, y + 3.5f * d, textPaint);
        }

        int n = pts.size();
        float stepX = n > 1 ? cw / (n - 1) : 0;
        Path line = new Path();
        Path area = new Path();
        float firstX = 0, firstY = 0, lastX = 0, lastY = 0;

        for (int i = 0; i < n; i++) {
            float x = padL + (n == 1 ? cw / 2f : stepX * i);
            float y = padT + ch * (1 - (pts.get(i).v - min) / span);
            if (i == 0) {
                line.moveTo(x, y);
                area.moveTo(x, padT + ch);
                area.lineTo(x, y);
                firstX = x; firstY = y;
            } else {
                float px = padL + stepX * (i - 1);
                float py = padT + ch * (1 - (pts.get(i - 1).v - min) / span);
                float cx = (px + x) / 2f;
                line.cubicTo(cx, py, cx, y, x, y);
                area.cubicTo(cx, py, cx, y, x, y);
            }
            if (i == n - 1) { lastX = x; lastY = y; }
        }

        area.lineTo(lastX, padT + ch);
        area.close();
        fillPaint.setColor(Ui.alpha(accent, 0.13f));
        canvas.drawPath(area, fillPaint);

        if (target > 0) {
            float ty = padT + ch * (1 - (target - min) / span);
            canvas.drawLine(padL, ty, padL + cw, ty, targetPaint);
            textPaint.setTextAlign(Paint.Align.LEFT);
            textPaint.setColor(Color.parseColor("#E8892B"));
            canvas.drawText("目标", padL + 3 * d, ty - 4 * d, textPaint);
            textPaint.setColor(textDim);
        }

        canvas.drawPath(line, linePaint);

        // 数据点（点多时只画端点，避免糊成一片）
        if (n <= 16) {
            for (int i = 0; i < n; i++) {
                float x = padL + (n == 1 ? cw / 2f : stepX * i);
                float y = padT + ch * (1 - (pts.get(i).v - min) / span);
                canvas.drawCircle(x, y, 2.4f * d, dotPaint);
                canvas.drawCircle(x, y, 1.2f * d, dotPaint);
            }
        } else {
            canvas.drawCircle(firstX, firstY, 3f * d, dotPaint);
            canvas.drawCircle(lastX, lastY, 3.6f * d, dotPaint);
        }

        // 只有一个点时（比如「按天」且只有一天的记录），整张图就一颗孤零零的圆点，
        // 把数值标在它上面，至少能看出这个点是多少
        if (n == 1) {
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setTextSize(11.5f * d);
            textPaint.setColor(accent);
            canvas.drawText(String.format(Locale.getDefault(), "%.1f%s", pts.get(0).v, unit),
                    firstX, firstY - 11 * d, textPaint);
            textPaint.setColor(textDim);
            textPaint.setTextSize(9.5f * d);
        }

        // X 轴日期：首 / 中 / 尾三个刻度。
        // 以前只有首尾两个，30 天以上的图看不出中间是哪一天
        textPaint.setTextAlign(Paint.Align.LEFT);
        textPaint.setTextSize(9.5f * d);
        String xa = dayFmt.format(new Date(pts.get(0).t));
        String xb = n > 1 ? dayFmt.format(new Date(pts.get(n - 1).t)) : xa;
        // 同一天称了多次时，两端写成同一个「11/25」等于没写，改成时刻
        boolean sameDay = n > 1 && xa.equals(xb);
        if (sameDay) {
            xa = hmFmt.format(new Date(pts.get(0).t));
            xb = hmFmt.format(new Date(pts.get(n - 1).t));
        }
        canvas.drawText(xa, padL, h - 8 * d, textPaint);

        if (n > 1) {
            textPaint.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText(xb, padL + cw, h - 8 * d, textPaint);
        }
        if (n >= 3) {
            textPaint.setTextAlign(Paint.Align.CENTER);
            int mid = n / 2;
            String xm = sameDay ? hmFmt.format(new Date(pts.get(mid).t))
                    : dayFmt.format(new Date(pts.get(mid).t));
            canvas.drawText(xm, padL + cw / 2f, h - 8 * d, textPaint);
        }

        // 触摸指示
        if (touchIndex >= 0 && touchIndex < n) {
            float x = padL + (n == 1 ? cw / 2f : stepX * touchIndex);
            float y = padT + ch * (1 - (pts.get(touchIndex).v - min) / span);
            Paint v = new Paint(Paint.ANTI_ALIAS_FLAG);
            v.setColor(Ui.alpha(accent, 0.35f));
            v.setStrokeWidth(1.2f * d);
            canvas.drawLine(x, padT, x, padT + ch, v);
            canvas.drawCircle(x, y, 7 * d, bubblePaint(accent));
            canvas.drawCircle(x, y, 3.4f * d, bubblePaint(surface));

            String s = String.format(Locale.getDefault(), "%.1f%s", pts.get(touchIndex).v, unit);
            String dt = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                    .format(new Date(pts.get(touchIndex).t));
            Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
            tp.setTextSize(10.5f * d);
            tp.setColor(Ui.isLight(surface) ? 0xFFFFFFFF : 0xFF10201B);
            float tw = Math.max(tp.measureText(s), tp.measureText(dt)) + 16 * d;
            float bx = Math.min(Math.max(x - tw / 2f, padL), padL + cw - tw);
            float by = Math.max(padT - 2 * d, y - 40 * d);
            bubblePaint.setColor(accent);
            canvas.drawRoundRect(bx, by, bx + tw, by + 32 * d, 8 * d, 8 * d, bubblePaint);
            tp.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(s, bx + tw / 2f, by + 13 * d, tp);
            tp.setTextSize(9f * d);
            tp.setColor(Ui.alpha(Ui.isLight(accent) ? 0xFF000000 : 0xFFFFFFFF, 0.8f));
            canvas.drawText(dt, bx + tw / 2f, by + 25 * d, tp);
        }
    }

    /** 取一个不小于 raw 的「整齐」步长，让刻度落在 0.5 / 1 / 2 这种数上 */
    private float niceStep(float raw) {
        float[] steps = {0.1f, 0.2f, 0.5f, 1f, 2f, 5f, 10f, 20f, 50f, 100f, 200f};
        for (float s : steps) {
            if (s >= raw) return s;
        }
        return 500f;
    }

    private Paint bubblePaint(int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        return p;
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (pts.isEmpty()) return false;
        float d = getResources().getDisplayMetrics().density;
        float padL = 34 * d, padR = 10 * d;
        float cw = getWidth() - padL - padR;
        int n = pts.size();
        switch (e.getAction()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE: {
                float rel = (e.getX() - padL) / Math.max(1f, cw);
                rel = Math.max(0f, Math.min(1f, rel));
                int idx = Math.round(rel * (n - 1));
                if (idx != touchIndex) {
                    touchIndex = idx;
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    invalidate();
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                touchIndex = -1;
                invalidate();
                return true;
        }
        return super.onTouchEvent(e);
    }
}
