package com.scale.link.protocol;

import com.scale.link.model.Measurement;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 协议注册表：按 GATT 服务 UUID 自动探测匹配 */
public class ProtocolRegistry {
    private static final List<ScaleProtocol> ALL = new ArrayList<>();
    static {
        // 小米系放最前：其状态字节特征明显，优先匹配可避免被通用 BCS 误解析
        ALL.add(new XiaomiProtocol());
        ALL.add(new Ffb0Protocol());
        ALL.add(new BcsProtocol());
        ALL.add(new Faa0Protocol());
        // AFU 与 FFB0 共用服务号，只在数据包层面兜底识别，不参与服务探测
        ALL.add(new AfuProtocol());
    }

    /** 根据发现的服务 UUID 列表匹配协议 */
    public static ScaleProtocol detect(List<UUID> serviceUuids) {
        for (ScaleProtocol p : ALL) {
            if (p instanceof AfuProtocol) continue;
            for (UUID u : serviceUuids) {
                if (p.serviceUuid().equals(u)) return p;
                // 标准体重服务的另一个入口
                if (p instanceof BcsProtocol &&
                        u.equals(UUID.fromString("0000181d-0000-1000-8000-00805f9b34fb"))) {
                    return new BcsProtocol() {
                        @Override public UUID notifyCharacteristicUuid() {
                            return UUID.fromString("00002a9d-0000-1000-8000-00805f9b34fb");
                        }
                    };
                }
            }
        }
        return null;
    }

    /** 嗅探模式：对未知协议尝试所有已知解析器 */
    public static Measurement tryParseAll(byte[] data, ParseContext ctx) {
        for (ScaleProtocol p : ALL) {
            Measurement m = p.parse(data, ctx);
            if (m != null && m.weight > 0) return m;
        }
        return null;
    }

    public static List<ScaleProtocol> all() { return ALL; }
}
