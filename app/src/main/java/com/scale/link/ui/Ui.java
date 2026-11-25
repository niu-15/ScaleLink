package com.scale.link.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 轻量 UI 工厂：圆角卡片、按钮、标签、分隔线。
 * 不依赖任何第三方库，也不依赖 XML 布局，全部代码构建。
 */
public final class Ui {

    private Ui() {}

    public static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    public static float dpf(Context c, float v) {
        return v * c.getResources().getDisplayMetrics().density;
    }

    // ---------------- 背景 ----------------

    public static GradientDrawable round(Context c, int color, float radiusDp) {
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.RECTANGLE);
        gd.setColor(color);
        gd.setCornerRadius(dpf(c, radiusDp));
        return gd;
    }

    public static GradientDrawable roundStroke(Context c, int color, float radiusDp,
                                               int strokeColor, float strokeDp) {
        GradientDrawable gd = round(c, color, radiusDp);
        gd.setStroke(Math.max(1, dp(c, strokeDp)), strokeColor);
        return gd;
    }

    /** 上下渐变，四角圆角 */
    public static GradientDrawable gradient(Context c, int from, int to, float radiusDp) {
        return gradient(c, new int[]{from, to}, radiusDp);
    }

    /** 多色渐变（可做三段式，层次更细腻） */
    public static GradientDrawable gradient(Context c, int[] colors, float radiusDp) {
        GradientDrawable gd = new GradientDrawable(GradientDrawable.Orientation.TL_BR, colors);
        gd.setShape(GradientDrawable.RECTANGLE);
        gd.setCornerRadius(dpf(c, radiusDp));
        return gd;
    }

    /** 页面背景：纯色，低饱和扁平风格（不再用渐变） */
    public static Drawable pageBg(Context c, Theme t) {
        return round(c, t.bg, 0);
    }

    // ---------------- 纯色材质 ----------------
    // 说明：底部导航（NavBarView）仍保留 Liquid Glass 玻璃+流动效果，
    // 但页面内所有卡片 / 按钮 / 标签统一改为低饱和纯色，不用渐变。

    /** 纯色圆角块（原先的玻璃入口，方法名保留以免影响调用处） */
    public static Drawable glass(Context c, Theme t, float radiusDp) {
        return round(c, t.surface, radiusDp);
    }

    /** 带颜色的纯色块（用于头图/主按钮），方法名保留以免影响调用处 */
    public static Drawable glassTint(Context c, int top, int bottom, float radiusDp, int border) {
        return round(c, top, radiusDp);
    }

    /** a 与 b 按比例混合（ratio 为 b 的占比） */
    private static int blend(int a, int b, float ratio) {
        float r = Math.min(1f, Math.max(0f, ratio));
        int aa = (int) (((a >> 24) & 0xFF) * (1 - r) + ((b >> 24) & 0xFF) * r);
        int ra = (int) (((a >> 16) & 0xFF) * (1 - r) + ((b >> 16) & 0xFF) * r);
        int ga = (int) (((a >> 8) & 0xFF) * (1 - r) + ((b >> 8) & 0xFF) * r);
        int ba = (int) ((a & 0xFF) * (1 - r) + (b & 0xFF) * r);
        return (aa << 24) | (ra << 16) | (ga << 8) | ba;
    }

    /** 顶部圆角、底部直角（用于页头） */
    public static GradientDrawable gradientBottomSquare(Context c, int from, int to, float radiusDp) {
        GradientDrawable gd = gradient(c, from, to, radiusDp);
        float r = dpf(c, radiusDp);
        gd.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        return gd;
    }

    public static Drawable ripple(Drawable content, int rippleColor) {
        return new RippleDrawable(ColorStateList.valueOf(rippleColor), content, null);
    }

    // ---------------- 文字 ----------------

    public static TextView tv(Context c, String s, float sizeSp, int color) {
        TextView v = new TextView(c);
        v.setText(s);
        v.setTextSize(sizeSp);
        v.setTextColor(color);
        v.setIncludeFontPadding(false);
        return v;
    }

    public static TextView tvMedium(Context c, String s, float sizeSp, int color) {
        TextView v = tv(c, s, sizeSp, color);
        v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return v;
    }

    public static TextView tvNumber(Context c, String s, float sizeSp, int color) {
        TextView v = tv(c, s, sizeSp, color);
        v.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        return v;
    }

    /** 带字间距的文字（小字加一点 tracking 会明显更精致） */
    public static TextView tvTracked(Context c, String s, float sizeSp, int color, float tracking) {
        TextView v = tv(c, s, sizeSp, color);
        v.setLetterSpacing(tracking);
        return v;
    }

    /** 小标签：浅底、圆角、小字号 */
    public static TextView chip(Context c, Theme t, String s, int bg, int fg) {
        TextView v = tv(c, s, 11.5f, fg);
        v.setPadding(dp(c, 9), dp(c, 4), dp(c, 9), dp(c, 4));
        v.setBackground(round(c, bg, 9));
        v.setGravity(Gravity.CENTER);
        return v;
    }

    // ---------------- 容器 ----------------

    public static LinearLayout col(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    /** 标准卡片：低饱和纯色 + 极细描边 + 20dp 圆角，扁平简洁 */
    public static LinearLayout card(Context c, Theme t) {
        LinearLayout l = col(c);
        l.setBackground(roundStroke(c, t.surface, 20, t.border, 0.7f));
        l.setPadding(dp(c, 17), dp(c, 17), dp(c, 17), dp(c, 17));
        return l;
    }

    /** 保留方法签名以兼容调用处；扁平风格下不再加投影 */
    public static void softShadow(View v, Theme t, float elevationDp) {
        v.setElevation(0);
    }

    public static LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    public static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    public static LinearLayout.LayoutParams weightLp(float weight, int h) {
        return new LinearLayout.LayoutParams(0, h, weight);
    }

    public static void margin(View v, int l, int tp, int r, int b) {
        ViewGroup.LayoutParams p = v.getLayoutParams();
        if (p instanceof LinearLayout.LayoutParams) {
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) p;
            lp.setMargins(dp(v.getContext(), l), dp(v.getContext(), tp),
                    dp(v.getContext(), r), dp(v.getContext(), b));
            v.setLayoutParams(lp);
        }
    }

    public static View spacer(Context c, int heightDp) {
        View v = new View(c);
        v.setLayoutParams(lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(c, heightDp)));
        return v;
    }

    public static View divider(Context c, Theme t) {
        View v = new View(c);
        v.setBackgroundColor(t.border);
        v.setLayoutParams(lp(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(c, 0.7f))));
        return v;
    }

    /** 主按钮 / 次按钮（TextView 实现，完全可控样式 + 波纹反馈） */
    public static TextView button(Context c, Theme t, String text, int bg, int fg) {
        TextView v = tvMedium(c, text, 14f, fg);
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12));
        v.setLetterSpacing(0.02f);
        int rippleColor = isLight(bg) ? 0x22000000 : 0x33FFFFFF;
        v.setBackground(ripple(round(c, bg, 16), rippleColor));
        v.setClickable(true);
        v.setFocusable(true);
        return v;
    }

    public static TextView primaryButton(Context c, Theme t, String text) {
        TextView v = tvMedium(c, text, 14f, t.onAccent);
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12));
        v.setLetterSpacing(0.02f);
        v.setBackground(ripple(
                round(c, t.accent, 14),
                isLight(t.accent) ? 0x22000000 : 0x33FFFFFF));
        v.setClickable(true);
        v.setFocusable(true);
        return v;
    }

    public static TextView ghostButton(Context c, Theme t, String text) {
        TextView v = tvMedium(c, text, 14f, t.text);
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12));
        v.setLetterSpacing(0.02f);
        v.setBackground(ripple(glass(c, t, 16), 0x1A000000));
        v.setClickable(true);
        v.setFocusable(true);
        return v;
    }

    /**
     * 浅底按钮：浅色填充 + 1px 细描边 + 正文色文字。
     * 和 primaryButton 同一套尺寸，只是不用主色铺满 —— 一排几个动作按钮
     * 全用实心主色会显得很重，浅底反而更耐看。
     */
    public static TextView lightButton(Context c, Theme t, String text) {
        TextView v = tvMedium(c, text, 14f, t.text);
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12));
        v.setLetterSpacing(0.02f);
        v.setBackground(ripple(roundStroke(c, t.btnBg, 14, t.btnBorder, 0.9f), 0x1A000000));
        v.setClickable(true);
        v.setFocusable(true);
        return v;
    }

    public static TextView textButton(Context c, Theme t, String text) {
        TextView v = tvMedium(c, text, 13f, t.accent);
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(c, 8), dp(c, 6), dp(c, 8), dp(c, 6));
        v.setClickable(true);
        v.setFocusable(true);
        return v;
    }

    /** 段落标题：小色点 + 字间距文字 */
    public static LinearLayout sectionTitle(Context c, Theme t, String text) {
        LinearLayout row = row(c);
        row.setPadding(dp(c, 2), dp(c, 6), 0, dp(c, 9));
        View dot = new View(c);
        dot.setBackground(round(c, t.accent, 2));
        LinearLayout.LayoutParams dlp = lp(dp(c, 4), dp(c, 4));
        dlp.rightMargin = dp(c, 7);
        row.addView(dot, dlp);
        TextView v = tvMedium(c, text, 12.5f, t.textDim);
        v.setLetterSpacing(0.06f);
        row.addView(v);
        return row;
    }

    public static boolean isLight(int color) {
        double lum = (0.299 * Color.red(color) + 0.587 * Color.green(color)
                + 0.114 * Color.blue(color)) / 255.0;
        return lum > 0.62;
    }

    /** 半透明白（用于渐变头图上的次级元素） */
    public static int alpha(int color, float a) {
        return Color.argb((int) (a * 255), Color.red(color), Color.green(color), Color.blue(color));
    }

    /** 把 View 变成可点击并带按压缩放动画（提升手感） */
    public static void tap(final View v, final Runnable action) {
        v.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                view.animate().scaleX(0.97f).scaleY(0.97f).setDuration(70)
                        .withEndAction(new Runnable() {
                            @Override public void run() {
                                view.animate().scaleX(1f).scaleY(1f).setDuration(90).start();
                            }
                        }).start();
                if (action != null) action.run();
            }
        });
    }
}
