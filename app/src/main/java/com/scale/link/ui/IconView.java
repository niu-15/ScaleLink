package com.scale.link.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/**
 * 底部导航图标：全部用 Canvas 画线，避免引入图片资源。
 */
public class IconView extends View {
    public static final int HOME = 0;
    public static final int TREND = 1;
    public static final int LIST = 2;
    public static final int USER = 3;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int type;
    private int color;

    public IconView(Context c, int type, int color) {
        super(c);
        this.type = type;
        this.color = color;
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
    }

    public void setColor(int color) {
        this.color = color;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        float s = Math.min(w, h) / 24f;   // 归一化到 24x24
        canvas.save();
        canvas.translate((w - 24 * s) / 2f, (h - 24 * s) / 2f);
        canvas.scale(s, s);
        p.setColor(color);
        p.setStrokeWidth(1.9f);

        Path path = new Path();
        switch (type) {
            case HOME:
                path.moveTo(4f, 10.6f);
                path.lineTo(12f, 4.2f);
                path.lineTo(20f, 10.6f);
                path.lineTo(20f, 19.2f);
                path.lineTo(14.6f, 19.2f);
                path.lineTo(14.6f, 13.8f);
                path.lineTo(9.4f, 13.8f);
                path.lineTo(9.4f, 19.2f);
                path.lineTo(4f, 19.2f);
                path.close();
                canvas.drawPath(path, p);
                break;
            case TREND:
                path.moveTo(4f, 18.4f);
                path.lineTo(9.2f, 12.2f);
                path.lineTo(13.2f, 15.4f);
                path.lineTo(20f, 6.6f);
                canvas.drawPath(path, p);
                canvas.drawCircle(20f, 6.6f, 1.5f, fill());
                canvas.drawLine(4f, 20.4f, 20f, 20.4f, p);
                break;
            case LIST:
                for (int i = 0; i < 3; i++) {
                    float y = 7f + i * 5f;
                    canvas.drawCircle(5.2f, y, 1.3f, fill());
                    canvas.drawLine(9f, y, 19.4f, y, p);
                }
                break;
            case USER:
            default:
                canvas.drawCircle(12f, 8.6f, 3.6f, p);
                path.moveTo(4.8f, 19.6f);
                path.addArc(4.8f, 10.4f, 19.2f, 25.2f, 180f, 180f);
                canvas.drawPath(path, p);
                break;
        }
        canvas.restore();
    }

    private Paint fill() {
        Paint f = new Paint(Paint.ANTI_ALIAS_FLAG);
        f.setStyle(Paint.Style.FILL);
        f.setColor(color);
        return f;
    }
}
