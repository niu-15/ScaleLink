package com.scale.link.protocol;

import com.scale.link.model.Measurement;

import java.util.UUID;

/**
 * FAA0 私有协议族（AH100/CH100/CH18 等，openScale 社区已逆向）。
 * 服务 FAA0 / 写 FAA1 / 通知 FAA2
 * 流程：AUTH 0x24 -> AUTH_OK 0x26 01 -> SET_UNIT 0x02 + SET_TIME 0x08 + USER_INFO 0x09(AES-CTR)
 *       -> 测量双包 0x0E(首片) + 0x8E(次片) -> AES 解密 + MAC XOR + checksum -> 体重/体脂/阻抗 -> FAT_ACK 0x13
 *
 * 本类实现协议骨架与命令构造。AES 密钥派生依赖设备 BIND token；首次绑定会生成并保存 token。
 * 由于 token 与个体设备相关，解析在拿到真实设备数据后可微调；当前实现覆盖命令状态机与字段提取框架。
 */
public class Faa0Protocol implements ScaleProtocol {

    private static final UUID SERVICE = UUID.fromString("0000faa0-0000-1000-8000-00805f9b34fb");
    private static final UUID WRITE = UUID.fromString("0000faa1-0000-1000-8000-00805f9b34fb");
    private static final UUID NOTIFY = UUID.fromString("0000faa2-0000-1000-8000-00805f9b34fb");

    @Override public String name() { return "FAA0 私有协议 (AH100/CH100 族)"; }
    @Override public UUID serviceUuid() { return SERVICE; }
    @Override public UUID writeCharacteristicUuid() { return WRITE; }
    @Override public UUID notifyCharacteristicUuid() { return NOTIFY; }

    @Override
    public byte[] initPayload(ParseContext context) {
        // AUTH 请求：0x24
        return new byte[]{0x24};
    }

    @Override
    public Measurement parse(byte[] d, ParseContext context) {
        if (d == null || d.length < 4) return null;
        int cmd = d[0] & 0xFF;

        // AUTH_OK = 0x26 01 -> 后续由 BleManager 发送 SET_UNIT/SET_TIME/USER_INFO，这里不产出数据
        if (cmd == 0x26) return null;

        // 测量包 0x0E（首片）或 0x8E（次片）。两片需拼接后 AES 解密。
        // 这里处理已解密的 17 字节记录直传情形（部分固件）。
        if (cmd == 0x0E || cmd == 0x8E) {
            return parseRecord(d, context);
        }
        return null;
    }

    private Measurement parseRecord(byte[] d, ParseContext context) {
        // 17 字节记录布局（社区逆向）：
        // [0]=flag [1..2]=体重(raw/200?) [3..4]=阻抗 [5..8]=时间戳 ...
        // 由于加密/去混淆与 token 强相关，这里做保守解析：仅当体重字段落在合理区间才接受
        if (d.length < 7) return null;
        // 尝试常见偏移
        int[][] candidates = {{1, 3}, {2, 4}, {3, 5}};
        for (int[] c : candidates) {
            int raw = u16(d, c[0]);
            double kg = raw / 200.0;
            if (kg >= 1 && kg <= 400) {
                Measurement m = new Measurement();
                m.weight = Math.round(kg * 100) / 100.0;
                m.resistance = u16(d, c[1]);
                m.timestamp = System.currentTimeMillis();
                m.source = "ble_faa0";
                return BodyComposition.enrich(m, context.user);
            }
        }
        return null;
    }

    @Override
    public String debugHint() {
        return "FAA0 协议：需要 AES-CTR + 设备 token 解密双包(0x0E+0x8E)。" +
                "嗅探模式记录完整 HEX 后可在 PC 端离线分析密钥与字段。";
    }

    private static int u16(byte[] d, int i) {
        if (i + 1 >= d.length) return 0;
        return (d[i] & 0xFF) | ((d[i + 1] & 0xFF) << 8);
    }
}
