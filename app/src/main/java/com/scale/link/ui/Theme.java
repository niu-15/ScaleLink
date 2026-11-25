package com.scale.link.ui;

import android.content.Context;

/**
 * 双主题色板：浅色（默认）/ 深色。
 * 所有颜色集中在此，页面里禁止硬编码色值，保证切换主题后整体一致。
 */
public final class Theme {

    public static final String PREF = "scalelink_pref";
    public static final String KEY_DARK = "dark_mode";

    public boolean dark;

    public int bg;            // 页面底色
    public int bgTop, bgBottom; // 页面背景渐变（极轻，用于制造质感）
    public int surface;       // 卡片
    public int surfaceAlt;    // 次级卡片/输入区
    public int text;          // 主文字
    public int textDim;       // 次要文字
    public int textFaint;     // 辅助文字
    public int accent;        // 主色
    public int accentDeep;    // 主色深
    public int accentLight;   // 主色浅（渐变用）
    public int accentSoft;    // 主色浅底
    public int onAccent;      // 主色上的文字

    public int headerFrom, headerMid, headerTo, onHeader, headerSub;   // 头图渐变

    public int border;        // 描边
    public int warn, danger, good;
    public int track;         // 进度条底槽
    public int grid;          // 图表网格
    public int chipBg, chipFg;

    // 次级按钮：浅底 + 细边（比卡片再浅/再亮一档，但没有主色那么抢眼）
    public int btnBg, btnBorder;

    // 液态玻璃
    public int glassTop, glassBottom, glassBorder, glassBorderSoft;
    public int glassEdgeBright, glassEdgeDim, glassSheen, glassGlaze;
    public int auroraBase, aurora1, aurora2, aurora3;

    private Theme() {}

    public static Theme light() {
        Theme t = new Theme();
        t.dark = false;
        // 冷调白：比纯白略深一点，卡片才浮得起来；不掺黄，避免发脏
        t.bg = 0xFFF4F6F8;
        t.bgTop = 0xFFF9FBFC;
        t.bgBottom = 0xFFEFF3F6;
        t.surface = 0xFFFFFFFF;
        t.surfaceAlt = 0xFFF2F5F7;
        // 文字：近黑但带一点冷灰，比纯黑柔和、比深灰有力度
        t.text = 0xFF141B1F;
        t.textDim = 0xFF5F6C73;
        t.textFaint = 0xFF828C93;
        // 主色：低饱和蓝灰（石板蓝），沉但不闷
        t.accent = 0xFF3E5C6B;
        t.accentDeep = 0xFF2C4450;
        t.accentLight = 0xFF6C8B9B;
        t.accentSoft = 0xFFE7EDF1;
        t.onAccent = 0xFFFFFFFF;
        t.headerFrom = 0xFF4A6E80;
        t.headerMid = 0xFF3E5C6B;
        t.headerTo = 0xFF2C4450;
        t.onHeader = 0xFFFFFFFF;
        t.headerSub = 0xFFC3D4DD;
        t.border = 0xFFE3E8EC;
        t.warn = 0xFFB5843D;
        t.danger = 0xFFB15C56;
        t.good = 0xFF3E5C6B;
        t.track = 0xFFE6ECF0;
        t.grid = 0xFFECF1F4;
        t.chipBg = 0xFFE9EFF3;
        t.btnBg = 0xFFF5F8FA;
        t.btnBorder = 0xFFDBE3E8;
        t.chipFg = 0xFF354A55;
        // 玻璃：白底半透明，边缘更亮
        t.glassTop = 0xC6FFFFFF;
        t.glassBottom = 0x8CFFFFFF;
        t.glassBorder = 0xEBFFFFFF;
        t.glassBorderSoft = 0x99FFFFFF;
        // Liquid Glass：镜面边左上亮、右下暗；顶部高光；底部极淡暗面
        t.glassEdgeBright = 0xFFFFFFFF;
        t.glassEdgeDim = 0x8AFFFFFF;
        t.glassSheen = 0x70FFFFFF;
        t.glassGlaze = 0x0F0E1E26;
        t.auroraBase = 0xFFF8FAFC;
        t.aurora1 = 0x4D8FADC0;
        t.aurora2 = 0x3D7E9DB8;
        t.aurora3 = 0x2ECBB79A;
        return t;
    }

    public static Theme dark() {
        Theme t = new Theme();
        t.dark = true;
        t.bg = 0xFF0F1417;
        t.bgTop = 0xFF141A1E;
        t.bgBottom = 0xFF0B1013;
        t.surface = 0xFF1A2126;
        t.surfaceAlt = 0xFF222B31;
        t.text = 0xFFE8EDF0;
        t.textDim = 0xFFA2AEB6;
        t.textFaint = 0xFF6E7A82;
        t.accent = 0xFF7C9BAA;
        t.accentDeep = 0xFF5A7787;
        t.accentLight = 0xFFA3BCC8;
        t.accentSoft = 0xFF1E2A31;
        t.onAccent = 0xFF0C1216;
        t.headerFrom = 0xFF2A3B45;
        t.headerMid = 0xFF22313A;
        t.headerTo = 0xFF18242B;
        t.onHeader = 0xFFE8EDF0;
        t.headerSub = 0xFFA6BCC7;
        t.border = 0xFF28323A;
        t.warn = 0xFFC79A5E;
        t.danger = 0xFFC27370;
        t.good = 0xFF7C9BAA;
        t.track = 0xFF1F2A31;
        t.grid = 0xFF1F2A31;
        t.chipBg = 0xFF1B2730;
        t.btnBg = 0xFF232C33;
        t.btnBorder = 0xFF39454D;
        t.chipFg = 0xFFB4C6D0;
        // 暗色玻璃：极低白覆盖 + 细白边
        t.glassTop = 0x24FFFFFF;
        t.glassBottom = 0x0FFFFFFF;
        t.glassBorder = 0x2EFFFFFF;
        t.glassBorderSoft = 0x1AFFFFFF;
        t.glassEdgeBright = 0x40FFFFFF;
        t.glassEdgeDim = 0x12FFFFFF;
        t.glassSheen = 0x1FFFFFFF;
        t.glassGlaze = 0x33000000;
        t.auroraBase = 0xFF080D10;
        t.aurora1 = 0x6612384A;
        t.aurora2 = 0x5912364A;
        t.aurora3 = 0x4D4A2E12;
        return t;
    }

    public static boolean isDarkMode(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(KEY_DARK, false);
    }

    public static void setDarkMode(Context c, boolean dark) {
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean(KEY_DARK, dark).apply();
    }

    public static Theme get(Context c) {
        return isDarkMode(c) ? dark() : light();
    }
}
