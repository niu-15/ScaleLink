package com.scale.link.page;

import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.scale.link.MainActivity;
import com.scale.link.model.Measurement;
import com.scale.link.model.UserProfile;
import com.scale.link.protocol.BodyComposition;
import com.scale.link.ui.RingView;
import com.scale.link.ui.Theme;
import com.scale.link.ui.Ui;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 首页：今日数据大屏 —— 体重、健康评分、目标进度、身体成分九宫格、设备入口。
 */
public class HomePage implements Page {

    private final MainActivity host;
    private final Theme t;

    private TextView tvGreeting, tvWeight, tvUnit, tvDate, tvDelta, tvDeltaLabel, tvStatus;
    private TextView tvAlt;
    private TextView tvScoreLabel, tvScoreBody;
    private RingView ring;
    private LinearLayout scoreCard;
    private Metric[] metrics;
    private View root;

    private static class Metric {
        TextView value, unit, hint;
        String key;
    }

    public HomePage(MainActivity host) {
        this.host = host;
        this.t = host.th;
    }

    @Override
    public View build() {
        ScrollView sv = new ScrollView(host);
        sv.setFillViewport(true);
        sv.setClipToPadding(false);

        LinearLayout page = Ui.col(host);
        page.setPadding(Ui.dp(host, 16), Ui.dp(host, 14), Ui.dp(host, 16), Ui.dp(host, 28));
        sv.addView(page, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        page.addView(buildHeader());
        page.addView(Ui.spacer(host, 14));
        page.addView(buildScoreCard());
        page.addView(Ui.spacer(host, 20));

        page.addView(Ui.sectionTitle(host, t, "身体成分"));
        page.addView(buildMetricGrid());
        page.addView(Ui.spacer(host, 20));

        root = sv;
        refresh();
        return sv;
    }

    // ---------------- 头图 ----------------

    private View buildHeader() {
        LinearLayout header = Ui.col(host);
        // 纯色头图：低饱和主色，扁平无渐变
        header.setBackground(Ui.round(host, t.headerMid, 22));
        header.setPadding(Ui.dp(host, 20), Ui.dp(host, 17), Ui.dp(host, 20), Ui.dp(host, 18));
        header.setElevation(0);

        LinearLayout topRow = Ui.row(host);
        tvGreeting = Ui.tvTracked(host, "早上好", 12f, Ui.alpha(t.onHeader, 0.78f), 0.04f);
        topRow.addView(tvGreeting, Ui.weightLp(1f, ViewGroup.LayoutParams.WRAP_CONTENT));

        tvStatus = Ui.tv(host, "未连接", 11f, t.onHeader);
        tvStatus.setPadding(Ui.dp(host, 10), Ui.dp(host, 5), Ui.dp(host, 10), Ui.dp(host, 5));
        tvStatus.setBackground(Ui.round(host, Ui.alpha(0xFFFFFF, t.dark ? 0.10f : 0.16f), 12));
        tvStatus.setGravity(Gravity.CENTER);
        tvStatus.setLetterSpacing(0.04f);
        Ui.tap(tvStatus, () -> host.openDeviceDialog());
        topRow.addView(tvStatus);
        header.addView(topRow);

        TextView wLabel = Ui.tvTracked(host, "体重", 10.5f, Ui.alpha(t.onHeader, 0.62f), 0.14f);
        wLabel.setPadding(0, Ui.dp(host, 16), 0, 0);
        header.addView(wLabel);

        LinearLayout wRow = Ui.row(host);
        wRow.setGravity(Gravity.BOTTOM);
        tvWeight = Ui.tvNumber(host, "--", 54f, t.onHeader);
        tvWeight.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        tvWeight.setLetterSpacing(-0.01f);
        tvUnit = Ui.tv(host, "kg", 14f, Ui.alpha(t.onHeader, 0.7f));
        tvUnit.setLetterSpacing(0.06f);
        wRow.addView(tvWeight);
        LinearLayout.LayoutParams unitLp = Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        unitLp.leftMargin = Ui.dp(host, 7);
        unitLp.bottomMargin = Ui.dp(host, 10);
        wRow.addView(tvUnit, unitLp);
        header.addView(wRow);

        // 用「斤」显示时，同时给出 kg 换算，避免把 156.2 斤误当成 156 kg
        tvAlt = Ui.tv(host, "", 11f, Ui.alpha(t.onHeader, 0.6f));
        tvAlt.setLetterSpacing(0.02f);
        tvAlt.setPadding(0, Ui.dp(host, 3), 0, 0);
        header.addView(tvAlt);

        LinearLayout deltaRow = Ui.row(host);
        deltaRow.setPadding(0, Ui.dp(host, 4), 0, 0);
        tvDelta = Ui.tv(host, "", 11f, t.onHeader);
        tvDelta.setPadding(Ui.dp(host, 8), Ui.dp(host, 3), Ui.dp(host, 8), Ui.dp(host, 3));
        tvDelta.setBackground(Ui.round(host, Ui.alpha(0xFFFFFF, t.dark ? 0.12f : 0.18f), 10));
        tvDelta.setGravity(Gravity.CENTER);
        tvDeltaLabel = Ui.tv(host, "", 11f, t.headerSub);
        LinearLayout.LayoutParams dlp = Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.leftMargin = Ui.dp(host, 8);
        deltaRow.addView(tvDelta, dlp);
        deltaRow.addView(tvDeltaLabel);
        header.addView(deltaRow);

        tvDate = Ui.tv(host, "", 10.5f, Ui.alpha(t.headerSub, 0.9f));
        tvDate.setPadding(0, Ui.dp(host, 10), 0, 0);
        tvDate.setLetterSpacing(0.02f);
        header.addView(tvDate);
        return header;
    }

    // ---------------- 评分 ----------------

    private View buildScoreCard() {
        scoreCard = Ui.card(host, t);
        LinearLayout row = Ui.row(host);

        ring = new RingView(host);
        ring.setColors(t.track, t.accent, t.text, t.textDim);
        LinearLayout.LayoutParams ringLp = Ui.lp(Ui.dp(host, 92), Ui.dp(host, 92));
        row.addView(ring, ringLp);

        LinearLayout info = Ui.col(host);
        LinearLayout.LayoutParams infoLp = Ui.weightLp(1f, ViewGroup.LayoutParams.WRAP_CONTENT);
        infoLp.leftMargin = Ui.dp(host, 16);

        tvScoreLabel = Ui.tvMedium(host, "身体评分", 14f, t.text);
        tvScoreBody = Ui.tv(host, "暂无数据", 12f, t.textDim);
        tvScoreBody.setPadding(0, Ui.dp(host, 6), 0, 0);
        tvScoreBody.setLineSpacing(Ui.dpf(host, 2), 1f);
        info.addView(tvScoreLabel);
        info.addView(tvScoreBody);
        row.addView(info, infoLp);

        scoreCard.addView(row);
        return scoreCard;
    }

    // ---------------- 指标网格 ----------------

    private View buildMetricGrid() {
        String[] keys = {"fat", "bmi", "water", "muscle", "bone", "visceral", "bmr", "lean"};
        String[] labels = {"体脂率", "BMI", "水分率", "骨骼肌", "骨量", "内脏脂肪", "基础代谢", "去脂体重"};
        String[] units = {"%", "", "%", "kg", "kg", "级", "kcal", "kg"};
        metrics = new Metric[keys.length];

        LinearLayout grid = Ui.col(host);
        for (int r = 0; r < 4; r++) {
            LinearLayout row = Ui.row(host);
            for (int c = 0; c < 2; c++) {
                int i = r * 2 + c;
                Metric m = new Metric();
                m.key = keys[i];
                LinearLayout tile = Ui.col(host);
                tile.setBackground(Ui.glass(host, t, 20));
                Ui.softShadow(tile, t, 3);
                tile.setPadding(Ui.dp(host, 14), Ui.dp(host, 13), Ui.dp(host, 14), Ui.dp(host, 13));

                TextView label = Ui.tvTracked(host, labels[i], 10.5f, t.textFaint, 0.06f);
                tile.addView(label);

                LinearLayout vRow = Ui.row(host);
                vRow.setGravity(Gravity.BOTTOM);
                vRow.setPadding(0, Ui.dp(host, 7), 0, 0);
                m.value = Ui.tvMedium(host, "--", 21f, t.text);
                m.unit = Ui.tv(host, units[i], 10.5f, t.textFaint);
                vRow.addView(m.value);
                LinearLayout.LayoutParams ulp = Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                ulp.leftMargin = Ui.dp(host, 3);
                ulp.bottomMargin = Ui.dp(host, 3);
                vRow.addView(m.unit, ulp);
                tile.addView(vRow);

                m.hint = Ui.tv(host, "—", 10.5f, t.textFaint);
                m.hint.setPadding(0, Ui.dp(host, 4), 0, 0);
                tile.addView(m.hint);

                LinearLayout.LayoutParams tlp = Ui.weightLp(1f, ViewGroup.LayoutParams.WRAP_CONTENT);
                if (c == 0) tlp.rightMargin = Ui.dp(host, 5); else tlp.leftMargin = Ui.dp(host, 5);
                row.addView(tile, tlp);
                metrics[i] = m;
            }
            LinearLayout.LayoutParams rlp = Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            if (r > 0) rlp.topMargin = Ui.dp(host, 10);
            grid.addView(row, rlp);
        }
        return grid;
    }

    // ---------------- 刷新 ----------------

    /** 正在称重：不显示不稳定的瞬时值，只提示「测量中」 */
    public void setMeasuring(boolean on) {
        if (root == null) return;
        if (!on) {
            refresh();
            return;
        }
        tvDate.setText("测量中，请站稳别动…");
        tvDeltaLabel.setText("正在读取体重");
        tvDelta.setVisibility(View.GONE);
    }

    /** 测量过程中的实时提示（由主界面推送，不含会乱跳的瞬时体重） */
    public void setLive(String text) {
        if (root == null) return;
        tvDate.setText(text);
    }

    @Override
    public void refresh() {
        if (root == null) return;
        UserProfile u = host.user();
        int hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        String greet = hour < 6 ? "夜深了" : hour < 11 ? "早上好" : hour < 14 ? "中午好"
                : hour < 18 ? "下午好" : "晚上好";
        // 名字为空或还是默认的「我」时，别拼成「晚上好，我」这种别扭的句子
        String nm = u.name == null ? "" : u.name.trim();
        tvGreeting.setText(nm.isEmpty() || "我".equals(nm) ? greet : greet + "，" + nm);

        boolean jin = host.useJin();
        tvUnit.setText(jin ? "斤" : "kg");
        // 骨骼肌 / 骨量 / 去脂体重也是质量，跟着主单位走。
        // 否则同一屏里体重是「斤」、成分是「kg」，容易把 33.0 读成 33 斤
        String massUnit = jin ? "斤" : "kg";
        metrics[3].unit.setText(massUnit);
        metrics[4].unit.setText(massUnit);
        metrics[7].unit.setText(massUnit);

        List<Measurement> data = host.db.getMeasurements(host.currentUserId, 1);
        Measurement latest = data.isEmpty() ? null : data.get(0);
        Measurement prev = null;
        List<Measurement> two = host.db.getMeasurements(host.currentUserId, 2);
        if (two.size() > 1) prev = two.get(1);

        SimpleDateFormat fmt = new SimpleDateFormat("M月d日 HH:mm", Locale.getDefault());

        if (latest == null) {
            tvWeight.setText("--");
            tvAlt.setText("");
            tvDelta.setVisibility(View.GONE);
            tvDeltaLabel.setText("还没有记录，称一次或手动添加");
            tvDate.setText("");
        } else {
            tvWeight.setText(host.wFmt(latest.weight));
            tvAlt.setText(jin
                    ? String.format(Locale.getDefault(), "≈ %.1f kg", latest.weight)
                    : String.format(Locale.getDefault(), "≈ %.1f 斤", latest.weight * 2));
            if (prev != null) {
                // 阈值按「显示单位」判断：以前拿 kg 的差值 <0.05 判持平，
                // 但显示的是斤，就会出现「· 0.1斤」这种持平符号配 0.1 变化的怪组合
                double dd = host.wDisp(latest.weight - prev.weight);
                String arrow = dd > 0.05 ? "↑" : dd < -0.05 ? "↓" : "·";
                tvDelta.setVisibility(View.VISIBLE);
                tvDelta.setText(String.format(Locale.getDefault(), "%s %.1f %s",
                        arrow, Math.abs(dd), jin ? "斤" : "kg"));
                tvDelta.setTextColor(dd > 0.05 ? 0xFFF0D2B4 : dd < -0.05 ? 0xFFBCD8E4 : t.onHeader);
                tvDeltaLabel.setText("较上次");
            } else {
                tvDelta.setVisibility(View.GONE);
                tvDeltaLabel.setText("首次记录");
            }
            String src = "ble".equals(latest.source) || (latest.source != null && latest.source.startsWith("ble"))
                    ? "体脂秤" : "manual".equals(latest.source) ? "手动录入"
                    : (latest.source == null || latest.source.isEmpty() ? "未知" : latest.source);
            tvDate.setText(fmt.format(new Date(latest.timestamp)) + " · 来源 " + src);
        }

        // 评分
        int score = latest == null ? 0 : BodyComposition.healthScore(latest, u);
        ring.set(score / 100f, latest == null ? "--" : String.valueOf(score), "分");
        tvScoreLabel.setText("身体评分 · " + BodyComposition.scoreLabel(score));
        if (latest == null) {
            tvScoreBody.setText("完成一次测量后生成评分");
        } else {
            double bmi = BodyComposition.bmi(latest, u);
            StringBuilder sb = new StringBuilder();
            sb.append("体型 BMI ").append(String.format(Locale.getDefault(), "%.1f", bmi))
                    .append(" · ").append(BodyComposition.evaluateBmi(bmi));
            if (latest.fat > 0) {
                sb.append("\n体脂 ").append(String.format(Locale.getDefault(), "%.1f%%", latest.fat))
                        .append(" · ").append(BodyComposition.evaluateFat(latest.fat, u.gender, u.age));
            }
            if (latest.water > 0) {
                sb.append("\n水分 ").append(String.format(Locale.getDefault(), "%.1f%%", latest.water))
                        .append(" · ").append(BodyComposition.evaluateWater(latest.water, u.gender));
            }
            tvScoreBody.setText(sb.toString());
        }

        // 指标
        double bmi = latest == null ? 0 : BodyComposition.bmi(latest, u);
        boolean fatEstimated = latest != null && latest.fat > 0 && latest.resistance <= 0;
        setMetric(0, latest == null || latest.fat <= 0 ? "--" : fmt1(latest.fat),
                latest == null || latest.fat <= 0 ? "赤脚站立才有"
                        : fatEstimated ? "估算值 · 赤脚更准"
                        : BodyComposition.evaluateFat(latest.fat, u.gender, u.age),
                latest != null && latest.fat > 0);
        setMetric(1, latest == null ? "--" : fmt1(bmi),
                latest == null ? "—" : BodyComposition.evaluateBmi(bmi), bmi > 0 && bmi >= 18.5 && bmi < 24);
        setMetric(2, latest == null || latest.water <= 0 ? "--" : fmt1(latest.water),
                latest == null || latest.water <= 0 ? "—" : BodyComposition.evaluateWater(latest.water, u.gender),
                latest != null && latest.water >= 50);
        setMetric(3, latest == null || latest.muscle <= 0 ? "--" : fmt1(host.wDisp(latest.muscle)),
                latest == null || latest.muscle <= 0 ? "—"
                        : (latest.weight > 0 ? "占体重 " + Math.round(latest.muscle / latest.weight * 100) + "%" : "—"),
                latest != null && latest.muscle > 0);
        setMetric(4, latest == null || latest.bone <= 0 ? "--"
                        : String.format(Locale.getDefault(), jin ? "%.1f" : "%.2f", host.wDisp(latest.bone)),
                "估算值", latest != null && latest.bone > 0);
        setMetric(5, latest == null || latest.visceral <= 0 ? "--" : fmt1(latest.visceral),
                latest == null || latest.visceral <= 0 ? "—" : BodyComposition.evaluateVisceral(latest.visceral),
                latest != null && latest.visceral > 0 && latest.visceral < 10);
        setMetric(6, latest == null || latest.bmr <= 0 ? "--" : String.valueOf(Math.round(latest.bmr)),
                "千卡/天", latest != null && latest.bmr > 0);
        double lean = BodyComposition.leanMass(latest);
        setMetric(7, latest == null || lean <= 0 ? "--" : fmt1(host.wDisp(lean)), "除脂肪以外", lean > 0);

        // 顶部状态徽标：这台秤读广播，所以「已连接」改成更有意义的措辞
        tvStatus.setText(host.connected ? "已连接" : "守候中");
        tvStatus.setBackground(Ui.round(host, Ui.alpha(0xFFFFFF, host.connected ? 0.26f : 0.14f), 12));
    }

    private void setMetric(int i, String value, String hint, boolean good) {
        Metric m = metrics[i];
        m.value.setText(value);
        m.hint.setText(hint);
        m.hint.setTextColor(good ? t.accent : t.textFaint);
    }

    private String fmt1(double v) {
        return String.format(Locale.getDefault(), "%.1f", v);
    }
}
