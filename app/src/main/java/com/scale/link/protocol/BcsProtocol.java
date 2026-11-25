package com.scale.link.protocol;

import com.scale.link.model.Measurement;

import java.util.UUID;

/**
 * 蓝牙 SIG 标准体成分服务 BCS (0x181B) / 体重服务 (0x181D)。
 * 测量特征 0x2A9C (Body Composition Measurement) / 0x2A9D (Weight Measurement)。
 * 字段按 IEEE 11073 SPF 格式：标志位 + 体重(2字节, resolution=0.005kg) + 体脂% + 阻抗等。
 */
public class BcsProtocol implements ScaleProtocol {

    private static final UUID SERVICE_BCS = UUID.fromString("0000181b-0000-1000-8000-00805f9b34fb");
    private static final UUID SERVICE_WS = UUID.fromString("0000181d-0000-1000-8000-00805f9b34fb");
    private static final UUID MEAS_BC = UUID.fromString("00002a9c-0000-1000-8000-00805f9b34fb");
    private static final UUID MEAS_W = UUID.fromString("00002a9d-0000-1000-8000-00805f9b34fb");

    @Override public String name() { return "SIG 标准 BCS/体重服务"; }
    @Override public UUID serviceUuid() { return SERVICE_BCS; } // 注册表会同时注册 WS 变体
    @Override public UUID writeCharacteristicUuid() { return null; }
    @Override public UUID notifyCharacteristicUuid() { return MEAS_BC; }

    @Override
    public byte[] initPayload(ParseContext context) { return null; }

    @Override
    public Measurement parse(byte[] d, ParseContext context) {
        if (d == null || d.length < 4) return null;
        int flags = d[0] & 0xFF;
        boolean imperial = (flags & 0x01) != 0;
        int idx = 1;
        if (idx + 1 >= d.length) return null;
        int wRaw = u16(d, idx);
        idx += 2;
        double kg = imperial ? wRaw * 0.01 * 0.45359237 * 2 : wRaw * 0.005;
        if (kg < 1 || kg > 400) return null;

        Measurement m = new Measurement();
        m.weight = Math.round(kg * 100) / 100.0;
        m.timestamp = System.currentTimeMillis();

        // BCS 标志位 bit1=体脂, bit2=水分, bit3=阻抗（IEEE 11073 SFLOAT）
        int off = idx;
        if ((flags & 0x02) != 0 && off + 1 < d.length) {
            m.fat = sfloat(d, off); off += 2;
        }
        if ((flags & 0x04) != 0 && off + 1 < d.length) {
            m.water = sfloat(d, off); off += 2;
        }
        if ((flags & 0x08) != 0 && off + 1 < d.length) {
            m.resistance = sfloat(d, off);
        }
        m.source = "ble_bcs";
        return BodyComposition.enrich(m, context.user);
    }

    @Override
    public String debugHint() { return "SIG 标准服务：直接订阅 0x2A9C/0x2A9D 通知，按 IEEE 11073 格式解析。"; }

    private static int u16(byte[] d, int i) { return (d[i] & 0xFF) | ((d[i+1] & 0xFF) << 8); }

    /** IEEE 11073 SFLOAT: 12位尾数 + 4位指数 */
    private static double sfloat(byte[] d, int i) {
        int raw = u16(d, i);
        int mantissa = raw & 0x0FFF;
        int exponent = (raw >> 12) & 0x0F;
        if (mantissa >= 0x800) mantissa -= 0x1000;
        if (exponent >= 0x8) exponent -= 0x10;
        return mantissa * Math.pow(10, exponent);
    }
}
