package com.scale.link.protocol;

import com.scale.link.model.Measurement;

import java.util.UUID;

/**
 * ElinkThings / SWAN 平台协议（BLE Service 0xFFB0）。
 * 国内大量 ODM 体脂秤（含天晟系部分型号）使用此平台。
 * 服务: FFB0  写: FFB1  通知: FFB2/FFB3
 *
 * 数据格式（以 openScale 社区逆向为准，常见变体）：
 *  - 重量: 2 字节，scale=200（即 raw/200 得 kg），小端
 *  - 阻抗: 2 字节，小端
 * 不同固件版本字段位置略有差异，故实现多候选偏移解析 + 合理性校验，
 * 并在嗅探模式下记录原始字节供进一步适配。
 */
public class Ffb0Protocol implements ScaleProtocol {

    private static final UUID SERVICE = UUID.fromString("0000ffb0-0000-1000-8000-00805f9b34fb");
    private static final UUID WRITE = UUID.fromString("0000ffb1-0000-1000-8000-00805f9b34fb");
    private static final UUID NOTIFY = UUID.fromString("0000ffb2-0000-1000-8000-00805f9b34fb");

    @Override public String name() { return "ElinkThings/SWAN (FFB0)"; }
    @Override public UUID serviceUuid() { return SERVICE; }
    @Override public UUID writeCharacteristicUuid() { return WRITE; }
    @Override public UUID notifyCharacteristicUuid() { return NOTIFY; }

    @Override
    public byte[] initPayload(ParseContext context) {
        // 请求用户信息写入（校验位命令），不同固件命令不同；返回 null 表示仅订阅通知即可
        return null;
    }

    @Override
    public Measurement parse(byte[] d, ParseContext context) {
        // 常见包长 13~20 字节
        if (d == null || d.length < 7) return null;

        Measurement m = new Measurement();

        // 尝试候选偏移：weight 在 idx=2 或 idx=3，scale=200；阻抗在随后 2 字节
        int[][] candidates = {{2, 4}, {3, 5}, {4, 6}, {1, 3}};
        for (int[] c : candidates) {
            int wi = c[0], ri = c[1];
            if (ri + 1 >= d.length) continue;
            int wRaw = u16(d, wi);
            double kg = wRaw / 200.0;
            // 合理性校验：1kg ~ 400kg
            if (kg >= 1.0 && kg <= 400.0) {
                m.weight = Math.round(kg * 100) / 100.0;
                int rRaw = u16(d, ri);
                m.resistance = rRaw;
                m.source = "ble_ffb0";
                m.timestamp = System.currentTimeMillis();
                return BodyComposition.enrich(m, context.user);
            }
        }
        return null;
    }

    @Override
    public String debugHint() {
        return "FFB0 平台：请记录通知原始 HEX，重点看第 2~7 字节；" +
                "体重常见为 raw/200。若识别不准，把 HEX 发回作者适配偏移。";
    }

    private static int u16(byte[] d, int i) {
        return (d[i] & 0xFF) | ((d[i + 1] & 0xFF) << 8);
    }
}
