package com.scale.link.model;

/**
 * 一次身体测量记录
 */
public class Measurement {
    public long id;
    public long timestamp;          // 毫秒时间戳
    public int user_id;
    public double weight;           // kg
    public double fat;              // 体脂率 %
    public double water;            // 水分率 %
    public double muscle;           // 骨骼肌 kg
    public double bone;             // 骨量 kg
    public double visceral;         // 内脏脂肪等级
    public double bmr;              // 基础代谢 kcal
    public double resistance;       // 阻抗 ohm（原始）
    public String source;           // 数据来源：ble_ffb0 / ble_faa0 / ble_bcs / manual
    public String note;

    public Measurement() {}

    public boolean hasBodyComposition() {
        return fat > 0 && resistance > 0;
    }
}
