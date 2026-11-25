package com.scale.link.protocol;

import com.scale.link.model.Measurement;

import java.util.UUID;

/**
 * AFU 系体脂秤协议（服务 FFB0 / 通知 FFB2，报文以 0xAC 为帧头）。
 *
 * 帧结构（相对 0xAC 的偏移）：
 *   +0      0xAC 帧头
 *   +3      体重最高字节（以 0x68 为基准，0~400kg 对应 0x68~0x6E）
 *   +4      体重中字节
 *   +5      体重低字节
 *   +6      状态位：0x00/0x01 称重中，0x02~0x05 已稳定
 *   +7      阻抗状态/用户标识
 *   +8..+9  阻抗（大端，200~1200Ω 常见）
 *
 * 体重 = ((b3 - 0x68) * 65536 + b4 * 256 + b5) / 1000.0 kg
 *
 * 该协议与 FFB0 服务上的其他方案（ElinkThings 等）可能共存，
 * 因此这里做严格的基准字节与状态位校验，避免误解析。
 */
public class AfuProtocol implements ScaleProtocol {

    private static final UUID SERVICE = UUID.fromString("0000ffb0-0000-1000-8000-00805f9b34fb");
    private static final UUID WRITE = UUID.fromString("0000ffb1-0000-1000-8000-00805f9b34fb");
    private static final UUID NOTIFY = UUID.fromString("0000ffb2-0000-1000-8000-00805f9b34fb");

    @Override public String name() { return "AFU 体脂秤 (0xAC 帧头)"; }
    @Override public UUID serviceUuid() { return SERVICE; }
    @Override public UUID writeCharacteristicUuid() { return WRITE; }
    @Override public UUID notifyCharacteristicUuid() { return NOTIFY; }

    @Override public byte[] initPayload(ParseContext context) {
        // 标准 AFU 握手包（10 字节）
        return new byte[]{(byte) 0xFD, 0x37, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x37};
    }

    @Override
    public Measurement parse(byte[] d, ParseContext ctx) {
        if (d == null || d.length < 7) return null;

        int off = -1;
        for (int i = 0; i < d.length; i++) {
            if ((d[i] & 0xFF) == 0xAC) { off = i; break; }
        }
        if (off < 0 || d.length - off < 7) return null;

        int b3 = d[off + 3] & 0xFF;
        int b4 = d[off + 4] & 0xFF;
        int b5 = d[off + 5] & 0xFF;
        int status = d[off + 6] & 0xFF;

        // 严格特征校验：不合规就当不是 AFU 帧，交给其它解析器
        if (b3 < 0x68 || b3 > 0x6E) return null;
        if (status > 0x05) return null;

        long raw = (long) (b3 - 0x68) * 65536 + b4 * 256 + b5;
        double kg = raw / 1000.0;
        if (kg <= 0 || kg > 350) return null;

        boolean stable = status >= 0x02 && status <= 0x05;

        Measurement m = new Measurement();
        m.weight = Math.round(kg * 1000) / 1000.0;
        m.timestamp = System.currentTimeMillis();
        m.source = "ble_afu";

        Double imp = impedance(d, off, status);
        if (imp != null) m.resistance = imp;
        m.note = stable ? "稳定" : "测量中";

        return BodyComposition.enrich(m, ctx.user);
    }

    private Double impedance(byte[] d, int off, int status) {
        // 未稳定时阻抗不可信
        if (status < 0x02) return null;
        int b7 = d[off + 7] & 0xFF;
        if (d.length - off >= 10) {
            int b8 = d[off + 8] & 0xFF;
            int b9 = d[off + 9] & 0xFF;
            if ((b8 == 0x00 && b9 == 0x00) || (b8 == 0xFF && b9 == 0xFF)) return null;
            if (b8 > 0) {
                int v = (b8 << 8) | b9;
                if (v >= 100 && v <= 1500) return (double) v;
            }
        }
        // 9 字节变体：阻抗紧跟状态位
        if (d.length - off >= 9 && b7 >= 1 && b7 <= 5) {
            int v = (b7 << 8) | (d[off + 8] & 0xFF);
            if (v >= 100 && v <= 1500) return (double) v;
        }
        return null;
    }

    @Override
    public String debugHint() {
        return "AFU 协议：0xAC 帧头，byte3 以 0x68 为基准，体重=(b3-0x68)*65536+b4*256+b5（克）。";
    }
}
