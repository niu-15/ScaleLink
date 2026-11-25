package com.scale.link.page;

import android.app.AlertDialog;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.scale.link.MainActivity;
import com.scale.link.model.Measurement;
import com.scale.link.model.UserProfile;
import com.scale.link.ui.Theme;
import com.scale.link.ui.TrendChartView;
import com.scale.link.ui.Ui;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 数据页：指标切换 → 曲线 → 区间切换 → 区间统计 → 全部记录（可折叠）。
 *
 * 以前这里是「趋势 / 记录」两个分段页，顶部一条分段控件把内容切成两半：
 * 想看「这周曲线怎么走、具体哪几天称了」得来回切。
 * 现在合成一条长页 —— 曲线在上、记录在下，记录默认收起，
 * 点标题栏展开，都是同一屏里的事，不用再切标签。
 */
public class DataPage implements Page {

    private static final String[] METRIC_KEYS = {"weight", "fat", "bmi", "muscle"};
    private static final String[] METRIC_NAMES = {"体重", "体脂率", "BMI", "骨骼肌"};
    private static final String[] RANGE_NAMES = {"7天", "30天", "90天", "全部"};
    private static final int[] RANGE_DAYS = {7, 30, 90, 0};

    /** 记录列表一次渲染多少条；再多就分页，避免一屏塞几百个 View */
    private static final int PAGE_SIZE = 30;

    private final MainActivity host;
    private final Theme t;

    private ScrollView root;
    private TrendChartView chart;
    private TextView[] metricTabs, rangeTabs;
    private TextView tvCount, tvLatest, tvAvg, tvMax, tvMin, tvChange;
    private TextView chartHint, dayTab, onceTab;

    private LinearLayout recordBody;
    private TextView recordCount, recordArrow;
    private boolean expanded = false;
    private int shown = PAGE_SIZE;

    private final List<Measurement> records = new ArrayList<>();
    private int metricIdx = 0;
    private int rangeIdx = 1;

    private final SimpleDateFormat dayFmt = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm", Locale.getDefault());

    public DataPage(MainActivity host) {
        this.host = host;
        this.t = host.th;
    }

    @Override
    public View build() {
        ScrollView sv = new ScrollView(host);
        sv.setClipToPadding(false);

        LinearLayout page = Ui.col(host);
        page.setPadding(Ui.dp(host, 16), Ui.dp(host, 12), Ui.dp(host, 16), Ui.dp(host, 28));
        sv.addView(page, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        // ① 指标切换
        LinearLayout metricRow = Ui.row(host);
        metricTabs = new TextView[METRIC_NAMES.length];
        for (int i = 0; i < METRIC_NAMES.length; i++) {
            final int idx = i;
            TextView tv = segment(METRIC_NAMES[i], i == metricIdx);
            metricTabs[i] = tv;
            LinearLayout.LayoutParams lp = Ui.weightLp(1f, Ui.dp(host, 38));
            if (i > 0) lp.leftMargin = Ui.dp(host, 6);
            metricRow.addView(tv, lp);
            Ui.tap(tv, () -> { metricIdx = idx; syncTabs(); refresh(); });
        }
        page.addView(metricRow);
        page.addView(Ui.spacer(host, 14));

        // ② 曲线（卡内带一行小标题，右侧切换「按天 / 按次」）
        LinearLayout chartCard = Ui.card(host, t);
        chartCard.setPadding(Ui.dp(host, 12), Ui.dp(host, 10), Ui.dp(host, 10), Ui.dp(host, 6));

        LinearLayout chartHead = Ui.row(host);
        chartHead.setGravity(Gravity.CENTER_VERTICAL);
        chartHint = Ui.tv(host, "", 11f, t.textFaint);
        chartHead.addView(chartHint, Ui.weightLp(1f, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout modeBox = Ui.row(host);
        dayTab = miniSeg("按天", host.chartDaily());
        onceTab = miniSeg("按次", !host.chartDaily());
        modeBox.addView(dayTab);
        LinearLayout.LayoutParams mlp = Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        mlp.leftMargin = Ui.dp(host, 6);
        modeBox.addView(onceTab, mlp);
        chartHead.addView(modeBox);
        chartCard.addView(chartHead);

        Ui.tap(dayTab, () -> setDaily(true));
        Ui.tap(onceTab, () -> setDaily(false));

        chart = new TrendChartView(host);
        chart.setStyle(t);
        LinearLayout.LayoutParams chartLp = Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(host, 232));
        chartLp.topMargin = Ui.dp(host, 4);
        chartCard.addView(chart, chartLp);
        page.addView(chartCard);
        page.addView(Ui.spacer(host, 14));

        // ③ 区间切换
        LinearLayout rangeRow = Ui.row(host);
        rangeTabs = new TextView[RANGE_NAMES.length];
        for (int i = 0; i < RANGE_NAMES.length; i++) {
            final int idx = i;
            TextView tv = segment(RANGE_NAMES[i], i == rangeIdx);
            rangeTabs[i] = tv;
            LinearLayout.LayoutParams lp = Ui.weightLp(1f, Ui.dp(host, 34));
            if (i > 0) lp.leftMargin = Ui.dp(host, 6);
            rangeRow.addView(tv, lp);
            Ui.tap(tv, () -> { rangeIdx = idx; syncTabs(); refresh(); });
        }
        page.addView(rangeRow);
        page.addView(Ui.spacer(host, 14));

        // ④ 区间统计
        LinearLayout stats = Ui.card(host, t);
        tvCount = Ui.tv(host, "", 12f, t.textDim);
        stats.addView(tvCount);
        LinearLayout statRow = Ui.row(host);
        statRow.setPadding(0, Ui.dp(host, 12), 0, 0);
        tvLatest = statCell(statRow, "最新", true);
        tvAvg = statCell(statRow, "平均", false);
        tvMax = statCell(statRow, "最高", false);
        tvMin = statCell(statRow, "最低", false);
        stats.addView(statRow);
        tvChange = Ui.tv(host, "", 12f, t.textDim);
        tvChange.setPadding(0, Ui.dp(host, 12), 0, 0);
        stats.addView(tvChange);
        page.addView(stats);
        page.addView(Ui.spacer(host, 14));

        // ⑤ 全部记录：标题栏可点，展开/收起列表
        LinearLayout recordCard = Ui.card(host, t);
        recordCard.setPadding(Ui.dp(host, 14), Ui.dp(host, 4), Ui.dp(host, 12), Ui.dp(host, 4));

        LinearLayout head = Ui.row(host);
        head.setPadding(0, Ui.dp(host, 12), 0, Ui.dp(host, 12));
        LinearLayout headCol = Ui.col(host);
        headCol.addView(Ui.tv(host, "全部记录", 13.5f, t.text));
        recordCount = Ui.tv(host, "", 11f, t.textFaint);
        recordCount.setPadding(0, Ui.dp(host, 2), 0, 0);
        headCol.addView(recordCount);
        head.addView(headCol, Ui.weightLp(1f, ViewGroup.LayoutParams.WRAP_CONTENT));

        recordArrow = Ui.tv(host, "›", 18f, t.textFaint);
        recordArrow.setRotation(expanded ? 90f : 0f);
        head.addView(recordArrow);
        Ui.tap(head, this::toggleRecords);
        recordCard.addView(head);

        recordBody = Ui.col(host);
        recordBody.setPadding(0, 0, 0, Ui.dp(host, 8));
        recordBody.setVisibility(expanded ? View.VISIBLE : View.GONE);
        recordCard.addView(recordBody);

        page.addView(recordCard);

        root = sv;
        refresh();
        return sv;
    }

    // ---------------- 记录：折叠 ----------------

    private void toggleRecords() {
        expanded = !expanded;
        recordArrow.animate().rotation(expanded ? 90f : 0f).setDuration(160).start();
        if (expanded) {
            renderRecords();
            recordBody.setVisibility(View.VISIBLE);
            recordBody.setAlpha(0f);
            recordBody.animate().alpha(1f).setDuration(180).start();
        } else {
            recordBody.setVisibility(View.GONE);
        }
    }

    /** 按当前 shown 条数铺开记录行 */
    private void renderRecords() {
        if (recordBody == null) return;
        recordBody.removeAllViews();
        int n = Math.min(shown, records.size());
        for (int i = 0; i < n; i++) {
            recordBody.addView(recordRow(records.get(i)));
        }
        if (records.size() > n) {
            TextView more = Ui.ghostButton(host, t,
                    "显示更多（还有 " + (records.size() - n) + " 条）");
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = Ui.dp(host, 4);
            recordBody.addView(more, lp);
            Ui.tap(more, () -> { shown += PAGE_SIZE; renderRecords(); });
        }
    }

    private View recordRow(final Measurement m) {
        LinearLayout row = Ui.row(host);
        // 行在卡片内部，所以用比卡片略深一档的底色区分，
        // 不然白底套白底，一整列会糊成一片没有边界
        row.setBackground(Ui.round(host, t.surfaceAlt, 14));
        row.setPadding(Ui.dp(host, 13), Ui.dp(host, 11), Ui.dp(host, 13), Ui.dp(host, 11));

        LinearLayout left = Ui.col(host);
        String day = dayFmt.format(new Date(m.timestamp));
        if (day.equals(dayFmt.format(Calendar.getInstance().getTime()))) day = "今天";
        left.addView(Ui.tvMedium(host, day + "  " + timeFmt.format(new Date(m.timestamp)), 13f, t.text));

        LinearLayout tagRow = Ui.row(host);
        tagRow.setPadding(0, Ui.dp(host, 6), 0, 0);
        if (m.fat > 0) {
            tagRow.addView(Ui.chip(host, t, String.format(Locale.getDefault(), "体脂 %.1f%%", m.fat),
                    t.accentSoft, t.accent));
        }
        if (m.muscle > 0) {
            TextView c = Ui.chip(host, t, String.format(Locale.getDefault(), "骨骼肌 %.1f", m.muscle),
                    t.surface, t.textDim);
            LinearLayout.LayoutParams lp = Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = Ui.dp(host, 6);
            tagRow.addView(c, lp);
        }
        String src = "manual".equals(m.source) ? "手动" : "体脂秤";
        TextView sc = Ui.chip(host, t, src, t.surface, t.textFaint);
        LinearLayout.LayoutParams lp = Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = Ui.dp(host, 6);
        tagRow.addView(sc, lp);
        left.addView(tagRow);

        if (m.note != null && !m.note.isEmpty()) {
            TextView n = Ui.tv(host, m.note, 11.5f, t.textDim);
            n.setPadding(0, Ui.dp(host, 6), 0, 0);
            left.addView(n);
        }
        row.addView(left, Ui.weightLp(1f, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout right = Ui.col(host);
        right.setGravity(Gravity.END);
        TextView w = Ui.tvMedium(host, host.wFmt(m.weight), 20f, t.text);
        w.setGravity(Gravity.END);
        TextView u = Ui.tv(host, host.wUnit(), 10.5f, t.textFaint);
        u.setGravity(Gravity.END);
        right.addView(w);
        right.addView(u);
        row.addView(right);

        Ui.tap(row, () -> showActions(m));

        LinearLayout wrap = Ui.col(host);
        wrap.setPadding(0, 0, 0, Ui.dp(host, 8));
        wrap.addView(row, Ui.matchWrap());
        return wrap;
    }

    private void showActions(final Measurement m) {
        new AlertDialog.Builder(host)
                .setTitle(new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                        .format(new Date(m.timestamp)) + " · " + host.wFmt(m.weight) + host.wUnit())
                .setItems(new String[]{"编辑备注", "删除这条记录"}, (d, which) -> {
                    if (which == 0) editNote(m);
                    else confirmDelete(m);
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void editNote(final Measurement m) {
        EditText et = new EditText(host);
        et.setHint("备注，如「饭后」「运动后」");
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        et.setText(m.note == null ? "" : m.note);
        et.setPadding(Ui.dp(host, 20), Ui.dp(host, 10), Ui.dp(host, 20), Ui.dp(host, 4));
        new AlertDialog.Builder(host)
                .setTitle("编辑备注")
                .setView(et)
                .setPositiveButton("保存", (d, w) -> {
                    host.db.updateNote(m.id, et.getText().toString().trim());
                    refresh();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmDelete(final Measurement m) {
        new AlertDialog.Builder(host)
                .setTitle("删除记录")
                .setMessage("删除后无法恢复，确定删除这条记录？")
                .setPositiveButton("删除", (d, w) -> {
                    host.db.deleteMeasurement(m.id);
                    refresh();
                    host.refreshAll();
                    host.toast("已删除");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ---------------- 组件 ----------------

    /** 同一天只留最后一次（入参已按时间升序） */
    private List<TrendChartView.Pt> mergeByDay(List<TrendChartView.Pt> raw) {
        List<TrendChartView.Pt> out = new ArrayList<>();
        String lastDay = null;
        for (TrendChartView.Pt p : raw) {
            String d = dayFmt.format(new Date(p.t));
            if (d.equals(lastDay) && !out.isEmpty()) {
                out.set(out.size() - 1, p);
            } else {
                out.add(p);
                lastDay = d;
            }
        }
        return out;
    }

    private void setDaily(boolean daily) {
        if (host.chartDaily() == daily) return;
        host.setChartDaily(daily);
        refresh();
    }

    private void syncModeTabs() {
        if (dayTab == null) return;
        boolean daily = host.chartDaily();
        dayTab.setTextColor(daily ? t.onAccent : t.textDim);
        dayTab.setBackground(daily ? Ui.round(host, t.accent, 10) : Ui.round(host, t.surfaceAlt, 10));
        onceTab.setTextColor(daily ? t.textDim : t.onAccent);
        onceTab.setBackground(daily ? Ui.round(host, t.surfaceAlt, 10) : Ui.round(host, t.accent, 10));
    }

    /** 曲线卡右上角的小开关，比主分段控件矮一档 */
    private TextView miniSeg(String text, boolean selected) {
        TextView tv = Ui.tvMedium(host, text, 11f, selected ? t.onAccent : t.textDim);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(Ui.dp(host, 10), Ui.dp(host, 5), Ui.dp(host, 10), Ui.dp(host, 5));
        tv.setBackground(selected ? Ui.round(host, t.accent, 10) : Ui.round(host, t.surfaceAlt, 10));
        tv.setClickable(true);
        return tv;
    }

    private TextView segment(String text, boolean selected) {
        TextView tv = Ui.tvMedium(host, text, 12.5f, selected ? t.onAccent : t.textDim);
        tv.setGravity(Gravity.CENTER);
        tv.setLetterSpacing(0.02f);
        tv.setBackground(selected ? Ui.round(host, t.accent, 14) : Ui.glass(host, t, 14));
        tv.setClickable(true);
        return tv;
    }

    private TextView statCell(LinearLayout parent, String label, boolean first) {
        LinearLayout col = Ui.col(host);
        if (!first) {
            View divider = new View(host);
            divider.setBackgroundColor(t.border);
            LinearLayout wrap = Ui.row(host);
            wrap.addView(divider, Ui.lp(1, Ui.dp(host, 30)));
            parent.addView(wrap, Ui.lp(Ui.dp(host, 8), Ui.dp(host, 40)));
        }
        TextView l = Ui.tv(host, label, 11f, t.textFaint);
        TextView v = Ui.tvMedium(host, "--", 16f, t.text);
        v.setPadding(0, Ui.dp(host, 4), 0, 0);
        col.addView(l);
        col.addView(v);
        parent.addView(col, Ui.weightLp(1f, ViewGroup.LayoutParams.WRAP_CONTENT));
        return v;
    }

    private void syncTabs() {
        for (int i = 0; i < metricTabs.length; i++) {
            boolean sel = i == metricIdx;
            metricTabs[i].setTextColor(sel ? t.onAccent : t.textDim);
            metricTabs[i].setBackground(sel ? Ui.round(host, t.accent, 14) : Ui.glass(host, t, 14));
        }
        for (int i = 0; i < rangeTabs.length; i++) {
            boolean sel = i == rangeIdx;
            rangeTabs[i].setTextColor(sel ? t.onAccent : t.textDim);
            rangeTabs[i].setBackground(sel ? Ui.round(host, t.accent, 14) : Ui.glass(host, t, 14));
        }
    }

    @Override
    public void refresh() {
        if (root == null) return;
        UserProfile u = host.user();
        String key = METRIC_KEYS[metricIdx];
        boolean jin = host.useJin();
        String unit;
        switch (key) {
            case "fat": unit = "%"; break;
            case "bmi": unit = ""; break;
            case "muscle": unit = jin ? "斤" : "kg"; break;
            default: unit = jin ? "斤" : "kg";
        }

        List<Measurement> all = host.db.getMeasurements(host.currentUserId, 100000);
        long from = 0;
        int days = RANGE_DAYS[rangeIdx];
        if (days > 0) from = System.currentTimeMillis() - days * 24L * 3600L * 1000L;

        List<TrendChartView.Pt> raw = new ArrayList<>();
        for (int i = all.size() - 1; i >= 0; i--) {           // 转为时间升序
            Measurement m = all.get(i);
            if (from > 0 && m.timestamp < from) continue;
            float v;
            switch (key) {
                case "fat": v = (float) m.fat; break;
                case "bmi": v = (float) (u.height > 0 ? m.weight / Math.pow(u.height / 100.0, 2) : 0); break;
                case "muscle": v = (float) m.muscle; break;
                default: v = (float) m.weight;
            }
            if (v <= 0) continue;
            if ("weight".equals(key) || "muscle".equals(key)) v = (float) host.wDisp(v);
            raw.add(new TrendChartView.Pt(m.timestamp, v));
        }

        // 一天称好几次时，每个点都画上去会把曲线拉成一排锯齿
        boolean daily = host.chartDaily();
        List<TrendChartView.Pt> pts = daily ? mergeByDay(raw) : raw;
        List<Float> values = new ArrayList<>(pts.size());
        for (TrendChartView.Pt p : pts) values.add(p.v);

        chartHint.setText(daily ? "每天取最后一次" : "按每次称重");
        syncModeTabs();

        // 「目标体重」入口已经移除，曲线不再画那条改不掉的目标虚线
        chart.setStyle(t);
        chart.setSeries(pts, unit, METRIC_NAMES[metricIdx], 0);

        String countUnit = daily ? "天" : "条记录";
        tvCount.setText(String.format(Locale.getDefault(), "%s · %d %s",
                RANGE_NAMES[rangeIdx], pts.size(), countUnit));

        if (values.isEmpty()) {
            tvLatest.setText("--"); tvAvg.setText("--"); tvMax.setText("--"); tvMin.setText("--");
            tvChange.setText("这个区间还没有数据");
        } else {
            float sum = 0, max = -Float.MAX_VALUE, min = Float.MAX_VALUE;
            for (float v : values) {
                sum += v;
                if (v > max) max = v;
                if (v < min) min = v;
            }
            float avg = sum / values.size();
            float first = values.get(0), last = values.get(values.size() - 1);

            tvLatest.setText(String.format(Locale.getDefault(), "%.1f", last));
            tvAvg.setText(String.format(Locale.getDefault(), "%.1f", avg));
            tvMax.setText(String.format(Locale.getDefault(), "%.1f", max));
            tvMin.setText(String.format(Locale.getDefault(), "%.1f", min));
            // 「最新」只有一个数字，看不出是哪一天，补在标题行上
            tvCount.setText(String.format(Locale.getDefault(), "%s · %d %s · 最新 %s",
                    RANGE_NAMES[rangeIdx], pts.size(), countUnit,
                    new SimpleDateFormat("M月d日", Locale.getDefault())
                            .format(new Date(pts.get(pts.size() - 1).t))));

            float delta = last - first;
            String pct = first != 0
                    ? String.format(Locale.getDefault(), "（%+.1f%%）", delta / first * 100) : "";
            if (Math.abs(delta) < 0.05f) {
                tvChange.setText("区间内基本持平");
                tvChange.setTextColor(t.textDim);
            } else {
                boolean good = "weight".equals(key) || "fat".equals(key) ? delta < 0
                        : "muscle".equals(key) ? delta > 0 : true;
                tvChange.setText(String.format(Locale.getDefault(), "区间变化 %+.1f%s %s",
                        delta, unit, pct));
                tvChange.setTextColor(good ? t.accent : t.warn);
            }
        }

        // 记录列表：始终是全部记录，和上面的区间无关
        records.clear();
        records.addAll(all);
        Calendar now = Calendar.getInstance();
        int monthCount = 0;
        for (Measurement m : records) {
            Calendar c = Calendar.getInstance();
            c.setTimeInMillis(m.timestamp);
            if (c.get(Calendar.YEAR) == now.get(Calendar.YEAR)
                    && c.get(Calendar.MONTH) == now.get(Calendar.MONTH)) monthCount++;
        }
        recordCount.setText(records.isEmpty()
                ? "还没有记录"
                : String.format(Locale.getDefault(), "共 %d 条 · 本月 %d 条 · 点开可编辑或删除",
                records.size(), monthCount));

        if (expanded) renderRecords();
    }
}
