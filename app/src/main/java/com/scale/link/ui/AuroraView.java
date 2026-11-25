package com.scale.link.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;

/**
 * 背景底色层：静态、低饱和的柔和色块。
 *
 * 页面主体已改为纯色扁平风格，这里只保留极淡的两团柔光，
 * 唯一作用是给「唯一保留的底部玻璃导航」提供一点折射层次——
 * 玻璃需要背后有明暗差异才看得出立体感，纯平背景会让底栏变成一块白板。
 * 光斑不流动、不用高饱和色，避免抢页面主体的扁平观感。
 */
public class AuroraView extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int base, c2, c3;

    public AuroraView(Context c, Theme t) {
        super(c);
        base = t.bg;
        // 用主色的极低饱和度版本，仅作极淡的层次
        c2 = lowSat(t.accentSoft);
        c3 = lowSat(t.accentSoft);
    }

    /** 把颜色压到接近背景的明度，饱和度降到很低 */
    private static int lowSat(int color) {
        float a = Color.alpha(color) / 255f * 0.55f;
        int r = Color.red(color), g = Color.green(color), b = Color.blue(color);
        int gray = (int) (0.299f * r + 0.587f * g + 0.114f * b);
        // 往灰度靠 70%，只留一点点色偏
        r = (int) (gray * 0.7f + r * 0.3f);
        g = (int) (gray * 0.7f + g * 0.3f);
        b = (int) (gray * 0.7f + b * 0.3f);
        return Color.argb((int) (a * 255), r, g, b);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        canvas.drawColor(base);
        // 静态柔光团，只服务底栏玻璃的折射，不随时间流动
        blob(canvas, c2, w * 0.5f, h * 0.98f, w * 0.9f, w, h);
        blob(canvas, c3, w * 0.85f, h * 0.55f, w * 0.7f, w, h);
    }

    private void blob(Canvas canvas, int color, float cx, float cy, float radius, int w, int h) {
        int fade = Color.argb(0, Color.red(color), Color.green(color), Color.blue(color));
        paint.setShader(new RadialGradient(cx, cy, radius, color, fade, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, paint);
        paint.setShader(null);
    }
}
