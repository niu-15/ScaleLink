package com.scale.link.data;

import android.os.Handler;
import android.os.Looper;

import com.scale.link.model.Measurement;

import java.util.ArrayList;
import java.util.List;

/**
 * 测量闸门：把「一次称重」产生的多个蓝牙数据包，合并成一条记录。
 *
 * 解决三个真实问题：
 *  1. 刚踩上去时秤会先发单脚/未站稳的瞬时包（例如 39.1kg），几秒后才发真实体重；
 *  2. 一次称重会连续发 3~10 个包，若每个包都入库，记录列表会被瞬间灌满；
 *  3. 固件裁剪会让个别长包解析偏移，出现明显偏离整体的数值。
 *
 * 策略：
 *  - 8 秒内的包视为同一会话；
 *  - 会话内对体重做聚类（±0.5kg），取成员最多的簇，簇内取均值并优先保留带阻抗的包；
 *  - 最后收到包后静默 2.2 秒才提交，确保拿到站稳后的数值；
 *  - 提交前与近期体重基线比对：过低且会话内没有任何接近基线的样本 → 判定为未站稳并丢弃。
 */
public class MeasureGate {

    public interface Listener {
        /** 收到数据包（尚未入库），可用于显示「测量中」 */
        void onLive(double kg, int packetCount, boolean stableSeen);

        /** 确认一次称重，入库 */
        void onCommit(Measurement m, int packetCount);

        /** 丢弃这次读数（附原因） */
        void onReject(double kg, String reason);
    }

    /** 近期体重基线（kg），0 表示没有历史数据 */
    public interface Baseline {
        double recentKg();
    }

    private static final long SESSION_GAP_MS = 8000L;   // 超过这个间隔算新的一次称重
    private static final long QUIET_COMMIT_MS = 2200L;  // 最后一个包之后静默多久提交
    private static final long MAX_SESSION_MS = 30000L;  // 单次会话最长时长
    private static final int MAX_SAMPLES = 60;          // 单个会话最多缓存的包数
    private static final double CLUSTER_WINDOW = 0.5;   // 聚类窗口 kg
    private static final double MIN_KG = 15;            // 低于此值不当成体重
    private static final double LOW_RATIO = 0.72;       // 低于基线这个比例视为未站稳
    private static final double STEP_OFF_RATIO = 0.90;  // 低于本次会话最高稳定值 -> 下秤残留/没站稳

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private final Baseline baseline;
    private final List<Measurement> samples = new ArrayList<>();

    private long sessionStart;
    private long lastPacketTs;
    private boolean stableSeen;
    private boolean committed;
    private int packetCount;

    /** 本次会话里「站稳后」的最大读数，用于识别下秤瞬间的小读数 */
    private double steadyMax;
    private int stableCount;
    /** 解析器是否会给「稳定/测量中」状态（小米/AFU 会给，其他协议族不给） */
    private boolean noteHasState;

    private final Runnable commitTask = () -> commit(false);

    public MeasureGate(Listener listener, Baseline baseline) {
        this.listener = listener;
        this.baseline = baseline;
    }

    /** 收到一个解析出的数据包 */
    public void feed(Measurement m) {
        if (m == null || m.weight <= 0) return;
        long now = System.currentTimeMillis();
        if (now - lastPacketTs > SESSION_GAP_MS) resetSession(now);
        lastPacketTs = now;

        boolean stable = m.note != null && m.note.contains("稳定");
        if (m.note != null && (stable || m.note.contains("测量中"))) noteHasState = true;
        if (stable) {
            stableSeen = true;
            stableCount++;
        }
        if (m.resistance > 0) stableSeen = true;

        samples.add(m);
        packetCount++;
        if (samples.size() > MAX_SAMPLES) samples.remove(0);
        committed = false;

        // 只有「连续两个包几乎一致」的读数才算站稳，用它当本次会话的参考高度，
        // 之后掉到 82% 以下的读数（下秤瞬间）直接丢弃。
        if (isSteady() && m.weight > steadyMax) steadyMax = m.weight;

        listener.onLive(m.weight, packetCount, isSteady());

        handler.removeCallbacks(commitTask);
        if (now - sessionStart >= MAX_SESSION_MS) {
            commit(true);
        } else {
            handler.postDelayed(commitTask, QUIET_COMMIT_MS);
        }
    }

    /**
     * 最近两个包是否几乎一致 —— 这才是真正「站稳」的判据。
     * 有些固件（这款天晟/京东健康 ODM 就是）每个包都把「稳定位」置 1，
     * 所以不能只信秤的稳定位，否则刚踩上去的 20.8 也会被当成稳定值实时刷出来。
     */
    private boolean isSteady() {
        int n = samples.size();
        if (n < 2) return false;
        double last = samples.get(n - 1).weight;
        int same = 1;
        for (int i = n - 2; i >= 0 && i >= n - 4; i--) {
            if (Math.abs(samples.get(i).weight - last) <= 0.3) same++;
            else break;
        }
        return same >= 2;
    }

    /** 断开连接 / 退出时，把还没提交的会话结算掉 */
    public void flush() {
        handler.removeCallbacks(commitTask);
        if (!samples.isEmpty()) commit(true);
    }

    public void reset() {
        handler.removeCallbacks(commitTask);
        samples.clear();
        packetCount = 0;
        stableSeen = false;
        committed = false;
        sessionStart = 0;
        lastPacketTs = 0;
        steadyMax = 0;
        stableCount = 0;
        noteHasState = false;
    }

    private void resetSession(long now) {
        samples.clear();
        packetCount = 0;
        stableSeen = false;
        committed = false;
        sessionStart = now;
        steadyMax = 0;
        stableCount = 0;
        noteHasState = false;
    }

    private void commit(boolean force) {
        handler.removeCallbacks(commitTask);
        if (samples.isEmpty() || committed) return;
        if (!force && System.currentTimeMillis() - lastPacketTs < QUIET_COMMIT_MS - 120) return;

        Measurement best = pickBest();
        final int count = packetCount;
        final boolean hadStable = stableSeen;
        final double sMax = steadyMax;
        final boolean stateKnown = noteHasState;

        samples.clear();
        packetCount = 0;
        stableSeen = false;
        committed = true;
        steadyMax = 0;
        stableCount = 0;

        if (best == null) return;
        if (best.weight < MIN_KG) {
            listener.onReject(best.weight, "读数过轻，可能还没站稳");
            return;
        }

        // 关键判断①：秤自己说还没稳定、而且只收到一两个包，就不要落库。
        // 「刚踩上去 39.1」这类数字就是这种瞬时包，直接记下来会被当成真体重。
        // 包多的时候交给聚类判断，避免某些固件从不置稳定位导致一条都记不上。
        if (stateKnown && !hadStable && count <= 2) {
            listener.onReject(best.weight, "没等到「稳定」信号，可能只踩了一下");
            return;
        }

        // 关键判断②：站完往下走时的残余读数（例如 78kg 掉到 62kg）不能记
        if (sMax > 0 && best.weight < sMax * STEP_OFF_RATIO) {
            listener.onReject(best.weight, String.format(java.util.Locale.getDefault(),
                    "%.1fkg 是下秤瞬间的残余读数（站稳时 %.1fkg），已忽略", best.weight, sMax));
            return;
        }

        double base = baseline == null ? 0 : baseline.recentKg();
        if (base > 0 && best.weight < base * LOW_RATIO) {
            listener.onReject(best.weight, String.format(java.util.Locale.getDefault(),
                    "%.1fkg 约为近期体重的 %.0f%%，多半只踩了一只脚，已忽略",
                    best.weight, best.weight / base * 100));
            return;
        }

        if (base > 0 && Math.abs(best.weight - base) / base > 0.25) {
            String n = best.note == null ? "" : best.note;
            best.note = n.isEmpty() ? "与近期体重差异较大" : n + " · 与近期差异较大";
        }

        listener.onCommit(best, count);
    }

    /**
     * 会话内聚类：以每个样本为中心，统计 ±CLUSTER_WINDOW 内的成员数，
     * 取成员最多的簇（并列时取较大的体重，因为单脚读数只会更小）。
     * 簇内取体重均值，并优先选择带阻抗的那一条作为载体。
     *
     * 只要本次会话里出现过 ≥2 个「稳定」包，就只在稳定包里挑 ——
     * 秤在站稳之前发的那一串瞬时值（20.8 / 67.6 / 41.6 …）全部不参与。
     */
    private Measurement pickBest() {
        if (samples.isEmpty()) return null;
        List<Measurement> pool = samples;
        if (stableCount >= 2) {
            List<Measurement> stableOnly = new ArrayList<>();
            for (Measurement s : samples) {
                if (s.note != null && s.note.contains("稳定")) stableOnly.add(s);
            }
            if (stableOnly.size() >= 2) pool = stableOnly;
        }
        int bestCount = -1;
        double bestCenter = 0;
        for (Measurement s : pool) {
            int cnt = 0;
            for (Measurement o : pool) {
                if (Math.abs(o.weight - s.weight) <= CLUSTER_WINDOW) cnt++;
            }
            if (cnt > bestCount || (cnt == bestCount && s.weight > bestCenter)) {
                bestCount = cnt;
                bestCenter = s.weight;
            }
        }

        Measurement pick = null;
        double sum = 0;
        int n = 0;
        for (Measurement s : pool) {
            if (Math.abs(s.weight - bestCenter) > CLUSTER_WINDOW) continue;
            sum += s.weight;
            n++;
            if (pick == null) {
                pick = s;
            } else {
                boolean sImp = s.resistance > 0;
                boolean pImp = pick.resistance > 0;
                if (sImp && !pImp) pick = s;
                else if (sImp == pImp && s.weight > pick.weight) pick = s;
            }
        }
        if (pick == null) return null;
        if (n > 1) pick.weight = Math.round((sum / n) * 100) / 100.0;
        return pick;
    }
}
