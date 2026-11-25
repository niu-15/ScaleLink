package com.scale.link.protocol;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 体重字段自动探测（对未知/私有协议兜底）。
 *
 * 思路：不预设「体重在第几个字节」，而是把每个数据包里所有可能的
 * 2 字节字段 × 几种缩放系数都当成候选假设；一次称重会收到多个包，
 * 真正的体重字段在每一包里都给出**同一个数值**，而计数器、时间戳、
 * 序号这类字段每包都在变 —— 于是「跨包最稳定的候选」就是体重。
 *
 * 这样做的好处是：不需要知道固件布局，也不怕厂商换字节偏移。
 */
public class WeightVoter {

    private static final double[] SCALES = {200, 100, 400, 50};  // raw/scale = kg
    private static final double MIN_KG = 25;                     // 合理体重下限
    private static final double MAX_KG = 250;                    // 合理体重上限
    private static final int MAX_PACKETS = 40;                   // 缓存包数
    private static final int MIN_PACKETS = 4;                    // 少于这个数不下结论
    private static final double TOL = 0.6;                       // 视为「同一个体重」的容差

    private final List<byte[]> packets = new ArrayList<>();

    public static class Result {
        public double kg;
        public int offset;      // 字节偏移（从 0 开始）
        public double scale;    // 缩放系数
        public int hits;        // 一致的包数
        public int total;       // 参与统计的包数

        public String describe() {
            return String.format(Locale.getDefault(), "字节%d-%d ÷%d（%d/%d 包一致）",
                    offset + 1, offset + 2, (int) scale, hits, total);
        }
    }

    public void feed(byte[] packet) {
        if (packet == null || packet.length < 2) return;
        packets.add(packet.clone());
        while (packets.size() > MAX_PACKETS) packets.remove(0);
    }

    public void reset() {
        packets.clear();
    }

    public int packetCount() {
        return packets.size();
    }

    /**
     * 给出当前最可信的体重。样本不足或没有稳定字段时返回 null。
     *
     * @param baselineKg 近期体重（用于在 2 倍歧义时挑更合理的那个；0 表示没有）
     */
    public Result resolve(double baselineKg) {
        if (packets.size() < MIN_PACKETS) return null;

        // 候选池：key = 偏移/缩放，value = 每个包算出的值
        Map<String, List<Double>> pool = new HashMap<>();
        for (byte[] d : packets) {
            for (int o = 0; o + 1 < d.length; o++) {
                int raw = (d[o] & 0xFF) | ((d[o + 1] & 0xFF) << 8);
                if (raw == 0) continue;
                for (double s : SCALES) {
                    double v = raw / s;
                    if (v < MIN_KG || v > MAX_KG) continue;
                    String key = o + "/" + (int) s;
                    List<Double> list = pool.get(key);
                    if (list == null) {
                        list = new ArrayList<>();
                        pool.put(key, list);
                    }
                    list.add(v);
                }
            }
        }

        Result best = null;
        double bestScore = -1;
        int total = packets.size();
        for (Map.Entry<String, List<Double>> e : pool.entrySet()) {
            List<Double> vals = e.getValue();
            if (vals.size() < Math.max(3, total / 2)) continue;   // 该字段必须在大多数包里出现

            double mode = modeOf(vals);
            int hits = 0;
            double sum = 0;
            for (double v : vals) {
                if (Math.abs(v - mode) <= TOL) {
                    hits++;
                    sum += v;
                }
            }
            double ratio = hits / (double) vals.size();
            double kg = sum / Math.max(1, hits);

            // 分数：稳定次数为主，其次是一致率
            double score = hits * (0.5 + ratio);
            // 与近期体重差太多就降权（应对 2 倍歧义、常量字段误判）
            if (baselineKg > 0) {
                double dev = Math.abs(kg - baselineKg) / baselineKg;
                if (dev > 0.25) score *= 0.35;
            }
            if (score > bestScore) {
                String[] parts = e.getKey().split("/");
                Result r = new Result();
                r.offset = Integer.parseInt(parts[0]);
                r.scale = Double.parseDouble(parts[1]);
                r.kg = Math.round(kg * 10) / 10.0;
                r.hits = hits;
                r.total = total;
                best = r;
                bestScore = score;
            }
        }
        return best;
    }

    /** 出现次数最多的值（按 0.1kg 取整后统计） */
    private static double modeOf(List<Double> vals) {
        Map<Long, Integer> freq = new HashMap<>();
        for (double v : vals) {
            long k = Math.round(v * 10);
            Integer c = freq.get(k);
            freq.put(k, c == null ? 1 : c + 1);
        }
        long bestKey = 0;
        int bestCount = -1;
        for (Map.Entry<Long, Integer> e : freq.entrySet()) {
            if (e.getValue() > bestCount) {
                bestCount = e.getValue();
                bestKey = e.getKey();
            }
        }
        return bestKey / 10.0;
    }

    /** 调试用：把当前最稳定的几个候选列出来 */
    public List<String> explain() {
        List<String> out = new ArrayList<>();
        if (packets.isEmpty()) return out;
        Map<String, List<Double>> pool = new HashMap<>();
        for (byte[] d : packets) {
            for (int o = 0; o + 1 < d.length; o++) {
                int raw = (d[o] & 0xFF) | ((d[o + 1] & 0xFF) << 8);
                if (raw == 0) continue;
                for (double s : SCALES) {
                    double v = raw / s;
                    if (v < MIN_KG || v > MAX_KG) continue;
                    String key = o + "/" + (int) s;
                    List<Double> list = pool.get(key);
                    if (list == null) {
                        list = new ArrayList<>();
                        pool.put(key, list);
                    }
                    list.add(v);
                }
            }
        }
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, List<Double>> e : pool.entrySet()) {
            double mode = modeOf(e.getValue());
            int hits = 0;
            for (double v : e.getValue()) if (Math.abs(v - mode) <= TOL) hits++;
            String[] p = e.getKey().split("/");
            lines.add(String.format(Locale.getDefault(), "%2d-%2d ÷%-3s = %6.1f kg  一致 %d/%d",
                    Integer.parseInt(p[0]) + 1, Integer.parseInt(p[0]) + 2, p[1], mode, hits, e.getValue().size()));
        }
        Collections.sort(lines);
        for (int i = 0; i < Math.min(8, lines.size()); i++) out.add(lines.get(i));
        return out;
    }
}
