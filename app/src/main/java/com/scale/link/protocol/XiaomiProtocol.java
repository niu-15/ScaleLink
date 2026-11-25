package com.scale.link.protocol;

import com.scale.link.model.Measurement;

import java.util.UUID;

/**
 * 小米系体脂秤协议（Xiaomi Mi Body Composition Scale 同款方案，天晟/京东健康 ODM 常用）。
 *
 * 状态字节（byte0）位定义：
 *  bit0 = 磅(lbs) 单位
 *  bit4 = 斤(jin) 单位
 *  bit5 = 已稳定(stabilized)
 *  bit7 = 已下秤(weight removed)
 *
 * byte1-2 = 体重（小端）
 *  - kg  单位：raw / 200
 *  - lbs 单位：raw / 100（磅）
 *  - jin 单位：raw / 100（斤，需再 /2 转 kg）
 *
 * 完整包（10~13 字节）还带年月日时分秒 + 阻抗：
 *  byte3-4 年、byte5 月、byte6 日、byte7 时、byte8 分、byte9 秒
 *  13 字节版本：byte9-10 阻抗（小端），byte11-12 体重（小端）
 *
 * 由于不同 ODM 固件存在裁剪/变体，本解析器做多重候选 + 合理性校验，
 * 并配合 App 内"校准"功能兜底（用户输入秤面读数即可自动纠正缩放/单位）。
 */
public class XiaomiProtocol implements ScaleProtocol {

    /** 小米体脂秤私有服务（部分型号） */
    private static final UUID SERVICE_MI = UUID.fromString("0000fe95-0000-1000-8000-00805f9b34fb");
    /** 体成分服务 */
    private static final UUID SERVICE_BCS = UUID.fromString("0000181b-0000-1000-8000-00805f9b34fb");

    @Override public String name() { return "小米系体脂秤 (0x2E 状态字节)"; }
    @Override public UUID serviceUuid() { return SERVICE_MI; }
    @Override public UUID writeCharacteristicUuid() { return null; }
    @Override public UUID notifyCharacteristicUuid() { return null; } // 由嗅探/全订阅接管

    @Override public byte[] initPayload(ParseContext context) { return null; }

    @Override
    public Measurement parse(byte[] d, ParseContext ctx) {
        if (d == null || d.length < 3) return null;

        int status = d[0] & 0xFF;
        // 小米体重相关包首字节常见 0x2E(46) / 0x22 / 0x26 / 0x62 等；用高位特征过滤
        if (status != 0x2E && status != 0x22 && status != 0x26 && status != 0x62) return null;

        boolean lbs = (status & 0x01) != 0;
        boolean jin = (status & 0x10) != 0;
        boolean stabilized = (status & 0x20) != 0;

        // 体重优先取 byte1-2（同一固件的短包也用这个位置）；
        // 只有该值明显不合理时，才退回长包的 byte11-12 布局。
        // 旧实现无脑按长度取 byte11-12，会把长包里的其他字段当成体重，
        // 于是同一次称重里会冒出 39.1 这种「刚站上去的数值」。
        double kg = weightOf(d, 1, lbs, jin);
        if (kg < 15 || kg > 300) {
            double alt = weightOf(d, 11, lbs, jin);
            if (alt >= 15 && alt <= 300) kg = alt;
        }

        if (kg < 2 || kg > 400) return null;

        Measurement m = new Measurement();
        m.weight = Math.round(kg * 100) / 100.0;
        m.timestamp = System.currentTimeMillis();
        m.source = "ble_mi";
        m.note = (stabilized ? "稳定" : "测量中") + (lbs ? " · 磅" : jin ? " · 斤" : " · 千克");

        // 阻抗：长包 byte9-10
        if (d.length >= 11) {
            int imp = (d[9] & 0xFF) | ((d[10] & 0xFF) << 8);
            if (imp > 100 && imp < 3000) m.resistance = imp;
        }

        return BodyComposition.enrich(m, ctx.user);
    }

    /** 从 offset 处读小端原始值并换算成 kg；越界返回 0 */
    private static double weightOf(byte[] d, int offset, boolean lbs, boolean jin) {
        if (d == null || d.length < offset + 2) return 0;
        int raw = (d[offset] & 0xFF) | ((d[offset + 1] & 0xFF) << 8);
        if (raw <= 0) return 0;
        if (lbs) return raw / 100.0 * 0.45359237;
        if (jin) return raw / 100.0 / 2.0;
        return raw / 200.0;
    }

    @Override
    public String debugHint() {
        return "小米系协议：byte0 状态位(bit4=斤, bit5=稳定)，byte1-2 体重小端/200(kg)。" +
                "若数值差一倍，多为秤按“斤”显示，可用 App 内“校准”一键修正。";
    }
}
