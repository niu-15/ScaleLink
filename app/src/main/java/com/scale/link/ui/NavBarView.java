package com.scale.link.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/**
 * 底部导航栏：整条自己画，不用 LinearLayout + TextView 拼。
 *
 * 为什么不用布局拼：图标是自定义 View、文字是 TextView，两者测量高度不同，
 * 再叠加 fontWeight / fontPadding / letterSpacing，很容易出现「图标偏右、文字偏左、
 * 药丸包不住文字」这类错位，而且不同机型表现还不一样。
 *
 * 这里所有坐标都由同一套数值算出来，图标中心 = 文字中心 = 每一项的中心，
 * 在任何屏幕密度下都严格对齐。
 */
public class NavBarView extends View {

    public interface OnSelect {
        void onSelect(int index);
    }

    private static final float BAR_H_DP = 64f;       // 整条高度
    private static final float PILL_INSET_X_DP = 10f; // 药丸左右留白
    private static final float PILL_INSET_Y_DP = 6f;  // 药丸上下留白
    private static final float ICON_DP = 21f;         // 图标框尺寸
    private static final float ICON_TEXT_GAP_DP = 3.0f; // 图标框底 -> 文字顶部
    private static final float STROKE_DP = 1.9f;      // 图标线宽（视觉宽度，非缩放前）
    private static final float LABEL_SP = 9.5f;

    private final Theme t;
    private final String[] labels;

    private int selected = 0;
    private OnSelect listener;

    private final Paint pillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** 整条底色：纯色，不再是玻璃 */
    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** 整条描边：1px 细线代替阴影/光晕 */
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint iconFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF rect = new RectF();
    private final Path path = new Path();

    public NavBarView(Context c, Theme t, String[] labels) {
        super(c);
        this.t = t;
        this.labels = labels;

        pillPaint.setStyle(Paint.Style.FILL);

        // 底栏用纯色：之前这里是 GlassDrawable（竖向渐变 + 顶部高光 + 流动光带 +
        // 8 层边缘折射带），深色模式下那圈半透明的亮边会浮在深底上，看起来就是
        // 「颜色不均匀、边上有一圈光晕」。改成一块实色 + 1px 描边后整条完全均匀。
        bgPaint.setStyle(Paint.Style.FILL);
        bgPaint.setColor(t.dark ? t.surface : 0xFFFFFFFF);

        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(Math.max(1f, Ui.dpf(c, 0.9f)));
        borderPaint.setColor(t.border);

        iconPaint.setStyle(Paint.Style.STROKE);
        iconPaint.setStrokeWidth(Ui.dpf(c, 1.75f));
        iconPaint.setStrokeCap(Paint.Cap.ROUND);
        iconPaint.setStrokeJoin(Paint.Join.ROUND);

        iconFill.setStyle(Paint.Style.FILL);

        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTextSize(LABEL_SP * c.getResources().getDisplayMetrics().scaledDensity);
        textPaint.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                android.graphics.Typeface.NORMAL));

        setClickable(true);
    }

    public void setSelected(int index) {
        if (index < 0 || index >= labels.length) return;
        if (selected == index) return;
        selected = index;
        invalidate();
    }

    public int getSelected() { return selected; }

    public void setOnSelect(OnSelect l) { this.listener = l; }

    public static int heightPx(Context c) {
        return Math.round(Ui.dpf(c, BAR_H_DP));
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        setMeasuredDimension(w, heightPx(getContext()));
    }


    @Override
    protected void onDraw(Canvas canvas) {
        final int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        final float radius = Math.min(h / 2f, Ui.dpf(getContext(), 32));

        // ① 底色：一块实色圆角，不透明、无渐变、无边缘高光，
        // 背后的光晕不会透上来，深色模式下也不会出现一圈亮边
        rect.set(0, 0, w, h);
        canvas.drawRoundRect(rect, radius, radius, bgPaint);

        // ② 描边：一条极细的线代替投影，边界清楚但不发光
        // 描边居中，最外 0.5px 会被 View 边界裁掉，所以内缩半个线宽
        float bw = borderPaint.getStrokeWidth() / 2f;
        rect.set(bw, bw, w - bw, h - bw);
        canvas.drawRoundRect(rect, radius - bw, radius - bw, borderPaint);

        final float itemW = w / (float) labels.length;
        final float iconSize = Ui.dpf(getContext(), ICON_DP);
        final float gap = Ui.dpf(getContext(), ICON_TEXT_GAP_DP);
        final float s = iconSize / 24f;

        // 线宽必须换算回「缩放前」的单位：canvas.scale(s) 会把 strokeWidth 一起放大 s 倍。
        // 之前这里直接用了 dp 线宽，被放大 3 倍后线条糊成实心色块，
        // 远看像四个不知所云的墨团 —— 这才是底栏「看着不对」的真正原因。
        iconPaint.setStrokeWidth(Ui.dpf(getContext(), STROKE_DP) / s);

        // 垂直节奏：图标框 + 间距 + 文字（用字体度量算真实高度），
        // 整块在整条高度里居中。之前是硬编码「图标顶 12dp / 基线 51dp」，
        // 结果图标贴着药丸顶、文字压着药丸底，中间空一大块。
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        final float textH = fm.descent - fm.ascent;
        final float blockH = iconSize + gap + textH;
        final float blockTop = (h - blockH) / 2f;
        final float labelBase = blockTop + iconSize + gap - fm.ascent;

        for (int i = 0; i < labels.length; i++) {
            final float cx = itemW * i + itemW / 2f;   // 每项中心，图标和文字都用它
            final boolean sel = i == selected;

            // ② 选中药丸
            if (sel) {
                float ix = Ui.dpf(getContext(), PILL_INSET_X_DP);
                float iy = Ui.dpf(getContext(), PILL_INSET_Y_DP);
                pillPaint.setColor(Ui.alpha(t.accent, t.dark ? 0.24f : 0.15f));
                rect.set(itemW * i + ix, iy, itemW * (i + 1) - ix, h - iy);
                float pr = Math.min((h - iy * 2) / 2f, Ui.dpf(getContext(), 26));
                canvas.drawRoundRect(rect, pr, pr, pillPaint);
            }

            // ③ 图标：以 cx 为水平中心
            int color = sel ? t.accent : t.textFaint;
            canvas.save();
            canvas.translate(cx - iconSize / 2f, blockTop);
            canvas.scale(s, s);
            drawIcon(canvas, iconTypeFor(i), color);
            canvas.restore();

            // ④ 文字：同样以 cx 居中
            textPaint.setColor(color);
            canvas.drawText(labels[i], cx, labelBase, textPaint);
        }
    }

    /**
     * 图标类型按文字认，不按索引认。
     * 标签页从 4 个减到 3 个后，索引和图标已经对不上了
     * （否则「我的」会画成列表图标），按文字匹配才不会出错。
     */
    private int iconTypeFor(int i) {
        String s = labels[i] == null ? "" : labels[i];
        if (s.contains("首页")) return 0;
        if (s.contains("数据") || s.contains("趋势")) return 1;
        if (s.contains("记录")) return 2;
        return 3;   // 我的
    }

    /**
     * 24×24 归一化坐标里画图标。
     * 统一线宽、统一圆角端点，四个图标视觉重量一致；
     * 之前的「记录」是三根粗横条、「我的」是一个歪的 A 形，远看完全认不出。
     */
    private void drawIcon(Canvas c, int type, int color) {
        iconPaint.setColor(color);
        iconFill.setColor(color);
        path.reset();
        switch (type) {
            case 0: // 首页：屋顶 + 墙体 + 门
                path.moveTo(3.7f, 10.3f);
                path.lineTo(12f, 3.6f);
                path.lineTo(20.3f, 10.3f);
                c.drawPath(path, iconPaint);

                path.reset();
                path.moveTo(5.8f, 9.3f);
                path.lineTo(5.8f, 20.0f);
                path.lineTo(18.2f, 20.0f);
                path.lineTo(18.2f, 9.3f);
                c.drawPath(path, iconPaint);

                path.reset();
                path.moveTo(9.9f, 20.0f);
                path.lineTo(9.9f, 14.2f);
                path.lineTo(14.1f, 14.2f);
                path.lineTo(14.1f, 20.0f);
                c.drawPath(path, iconPaint);
                break;

            case 1: // 趋势：折线 + 右上箭头
                path.moveTo(3.4f, 19.6f);
                path.lineTo(9.6f, 12.6f);
                path.lineTo(13.4f, 16.0f);
                path.lineTo(19.6f, 6.4f);
                c.drawPath(path, iconPaint);

                path.reset();
                path.moveTo(15.2f, 6.4f);
                path.lineTo(19.6f, 6.4f);
                path.lineTo(19.6f, 10.8f);
                c.drawPath(path, iconPaint);
                break;

            case 2: // 记录：三条列表行（圆点 + 横线）
                for (int i = 0; i < 3; i++) {
                    float y = 5.6f + i * 6.4f;
                    c.drawCircle(5.4f, y, 1.35f, iconFill);
                    c.drawLine(9.9f, y, 19.4f, y, iconPaint);
                }
                break;

            default: // 我的：头 + 肩
                c.drawCircle(12f, 7.9f, 3.5f, iconPaint);
                path.moveTo(4.9f, 20.3f);
                path.cubicTo(4.9f, 15.9f, 8.1f, 13.9f, 12f, 13.9f);
                path.cubicTo(15.9f, 13.9f, 19.1f, 15.9f, 19.1f, 20.3f);
                c.drawPath(path, iconPaint);
                break;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_DOWN) return true;
        if (e.getAction() == MotionEvent.ACTION_UP) {
            float itemW = getWidth() / (float) labels.length;
            int idx = (int) (e.getX() / itemW);
            if (idx < 0) idx = 0;
            if (idx >= labels.length) idx = labels.length - 1;
            if (listener != null) listener.onSelect(idx);
            return true;
        }
        return super.onTouchEvent(e);
    }
}
