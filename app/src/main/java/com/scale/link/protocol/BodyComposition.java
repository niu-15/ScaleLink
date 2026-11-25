package com.scale.link.protocol;

import com.scale.link.model.Measurement;
import com.scale.link.model.UserProfile;

/**
 * BIA 身体成分计算器。
 * 当秤只输出体重+阻抗时，用公开的消费级 BIA 经验公式估算体脂等指标；
 * 当秤直接输出体脂等字段时，只补充 BMI/BMR 等派生指标。
 * 注意：消费级 BIA 为估算值，仅用于长期趋势参考，非医疗诊断。
 */
public class BodyComposition {

    public static Measurement enrich(Measurement m, UserProfile u) {
        if (m == null || u == null) return m;

        double h = u.height / 100.0;           // m
        double bmi = h > 0 ? m.weight / (h * h) : 0;

        // 如果秤没直接给体脂，用「人体测量 + 阻抗修正」估算。
        // 旧版用的是 (BMI×1.533 − 10.2 + 阻抗×系数)，BMI 系数过大，
        // 对 BMI 23、30 岁男性会算出 29% 体脂（实际约 17%），
        // 与京东健康/厂家算法差了 10 个百分点以上，必须换掉。
        if (m.fat <= 0 && bmi > 0) {
            double sex = u.gender == 1 ? 1 : 0;
            // Deurenberg 体脂预测式：学界引用最广、只用 BMI+年龄+性别，稳定性最好
            double f = 1.20 * bmi + 0.23 * u.age - 10.8 * sex - 5.4;

            // 阻抗只做小幅修正（限幅 ±2.5%）：这台秤的阻抗是广播里的单字节字段，
            // 偶尔会缺省或跳变，如果让它主导结果，一包脏数据就能把体脂算飞。
            if (m.resistance > 0 && u.height > 0) {
                double h2r = u.height * u.height / m.resistance;   // 阻抗指数
                double ref = u.gender == 1 ? 51.0 : 47.0;          // 成年人群均值
                double adj = (ref - h2r) * 0.35;
                if (adj > 2.5) adj = 2.5;
                if (adj < -2.5) adj = -2.5;
                f += adj;
            }

            // 个体校准偏移：不同厂商（京东健康/小米等）的私有算法口径不一样，
            // 用户拿官方 App 的读数校一次，之后这套设备就一直对齐那个口径。
            f += u.fatOffset;

            if (f < 3) f = 3;
            if (f > 60) f = 60;
            m.fat = Math.round(f * 10) / 10.0;
            m.note = (m.note == null || m.note.isEmpty() ? "" : m.note + " · ") + "体脂为估算值";
        }

        // BMR：有体脂时用 Katch-McArdle（基于去脂体重，比只看体重准），
        // 否则退回 Mifflin-St Jeor
        double bmr;
        if (m.fat > 0) {
            double leanMass = m.weight * (1 - m.fat / 100.0);
            bmr = 370 + 21.6 * leanMass;
        } else if (u.gender == 1) {
            bmr = 10 * m.weight + 6.25 * u.height - 5 * u.age + 5;
        } else {
            bmr = 10 * m.weight + 6.25 * u.height - 5 * u.age - 161;
        }
        if (m.bmr <= 0) m.bmr = bmr;

        // 派生：水分率、骨骼肌、骨量、内脏脂肪
        // 这几个的口径对齐商用体脂秤（京东健康/小米等）：
        //   水分率 = 去脂体重 × 0.732
        //   骨骼肌 = 去脂体重 × 0.575（旧版算成去脂体重×0.8 又减身高，只剩 41%，明显偏小）
        //   骨量   = 体重 × 4.9%（男）/ 4.3%（女），旧版 3.8% 偏低约 1kg
        if (m.fat > 0) {
            double lean = m.weight * (1 - m.fat / 100.0);
            if (m.water <= 0) {
                double waterPct = lean * 0.732 / m.weight * 100;
                m.water = Math.round(waterPct * 10) / 10.0;
            }
            if (m.muscle <= 0) {
                m.muscle = Math.round(lean * 0.575 * 10) / 10.0;
            }
            if (m.bone <= 0) {
                double boneMass = m.weight * (u.gender == 1 ? 0.049 : 0.043);
                m.bone = Math.round(boneMass * 100) / 100.0;
            }
            if (m.visceral <= 0) {
                double visceral = (bmi - 15) * 0.5 + (m.fat - 10) * 0.12;
                if (u.gender == 0) visceral += 0.5;
                if (visceral < 1) visceral = 1;
                if (visceral > 30) visceral = 30;
                m.visceral = Math.round(visceral * 10) / 10.0;
            }
        }
        return m;
    }

    /** 体脂率评价 */
    public static String evaluateFat(double fat, int gender, int age) {
        if (fat <= 0) return "—";
        double lo = gender == 1 ? 10 : 18;
        double hi = gender == 1 ? 20 : 28;
        if (age > 50) { lo += 2; hi += 2; }
        if (fat < lo) return "偏低";
        if (fat <= hi) return "正常";
        if (fat <= hi + 7) return "偏高";
        return "过高";
    }

    /** BMI 评价（中国标准） */
    public static String evaluateBmi(double bmi) {
        if (bmi <= 0) return "—";
        if (bmi < 18.5) return "偏瘦";
        if (bmi < 24) return "正常";
        if (bmi < 28) return "超重";
        return "肥胖";
    }

    public static double bmi(Measurement m, UserProfile u) {
        if (m == null || u == null || u.height <= 0) return 0;
        double h = u.height / 100.0;
        return m.weight / (h * h);
    }

    /** 去脂体重（瘦体重） */
    public static double leanMass(Measurement m) {
        if (m == null || m.fat <= 0) return 0;
        return m.weight * (1 - m.fat / 100.0);
    }

    /** 水分率评价 */
    public static String evaluateWater(double water, int gender) {
        if (water <= 0) return "—";
        double lo = gender == 1 ? 55 : 50;
        double hi = gender == 1 ? 65 : 60;
        if (water < lo - 3) return "偏低";
        if (water < lo) return "略低";
        if (water <= hi) return "正常";
        return "偏高";
    }

    /** 内脏脂肪等级评价（1-9 正常，10-14 偏高，15+ 过高） */
    public static String evaluateVisceral(double v) {
        if (v <= 0) return "—";
        if (v < 10) return "正常";
        if (v < 15) return "偏高";
        return "过高";
    }

    /**
     * 综合健康评分（0-100）：由 BMI、体脂率、水分率、肌肉比例合成的参考值，
     * 仅用于观察自身趋势，不具医学意义。
     */
    public static int healthScore(Measurement m, UserProfile u) {
        if (m == null || u == null || m.weight <= 0) return 0;
        double bmi = bmi(m, u);
        if (bmi <= 0) return 0;

        double bmiScore = 100 - Math.abs(bmi - 21.0) * 7.0;

        double fatScore;
        if (m.fat > 0) {
            double center = u.gender == 1 ? 16 : 24;
            if (u.age > 50) center += 2;
            fatScore = 100 - Math.abs(m.fat - center) * 4.2;
        } else {
            fatScore = 62; // 无体脂数据时给中性分，避免虚高
        }

        double waterScore;
        if (m.water > 0) {
            double center = u.gender == 1 ? 60 : 55;
            waterScore = 100 - Math.abs(m.water - center) * 4.5;
        } else {
            waterScore = 62;
        }

        double muscleScore;
        if (m.muscle > 0) {
            double ratio = m.muscle / m.weight;                 // 肌肉占体重比
            double center = u.gender == 1 ? 0.45 : 0.36;
            muscleScore = 100 - Math.abs(ratio - center) * 260;
        } else {
            muscleScore = 62;
        }

        double s = bmiScore * 0.34 + fatScore * 0.32 + waterScore * 0.16 + muscleScore * 0.18;
        return (int) Math.max(30, Math.min(99, Math.round(s)));
    }

    public static String scoreLabel(int score) {
        if (score <= 0) return "—";
        if (score >= 85) return "很好";
        if (score >= 72) return "良好";
        if (score >= 60) return "一般";
        return "待改善";
    }
}
