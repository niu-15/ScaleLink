package com.scale.link.protocol;

import com.scale.link.model.Measurement;

import java.util.UUID;

/**
 * 体脂秤协议接口。每种协议族实现此接口，由 ProtocolRegistry 自动探测匹配。
 */
public interface ScaleProtocol {
    /** 协议名称 */
    String name();

    /** 该协议使用的主服务 UUID（用于自动匹配） */
    UUID serviceUuid();

    /** 写特征 UUID（可能需要向秤写入用户参数/认证），无则为 null */
    UUID writeCharacteristicUuid();

    /** 通知特征 UUID（秤主动上报测量数据） */
    UUID notifyCharacteristicUuid();

    /**
     * 解析通知数据。返回 Measurement，或 null 表示此包不是测量数据。
     * @param data 通知的原始字节
     * @param context 上下文（含用户参数，供 BIA 计算使用）
     */
    Measurement parse(byte[] data, ParseContext context);

    /** 连接后需要初始化时写入的字节序列（认证/用户信息/单位时间同步），无则返回 null */
    byte[] initPayload(ParseContext context);

    /** 辅助信息：抓包分析提示 */
    String debugHint();
}
