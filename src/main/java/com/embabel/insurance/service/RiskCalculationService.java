package com.embabel.insurance.service;

import com.embabel.insurance.entity.Customer;
import com.embabel.insurance.entity.Vehicle;
import org.springframework.stereotype.Service;

/**
 * 风险评分计算服务，基于客户画像和车辆信息评估核保风险等级。
 *
 * <p>评分规则：
 * <table>
 *   <tr><th>评估因子</th><th>范围</th><th>调整值</th></tr>
 *   <tr><td rowspan="4">年龄</td><td>&lt; 25</td><td>+25</td></tr>
 *   <tr><td>25–35</td><td>+15</td></tr>
 *   <tr><td>36–64</td><td>0（基准线）</td></tr>
 *   <tr><td>≥ 65</td><td>+20</td></tr>
 *   <tr><td rowspan="4">驾龄</td><td>&lt; 3 年</td><td>+20</td></tr>
 *   <tr><td>3–5 年</td><td>+10</td></tr>
 *   <tr><td>6–19 年</td><td>0（基准线）</td></tr>
 *   <tr><td>≥ 20 年</td><td>−10</td></tr>
 *   <tr><td>事故次数</td><td>每次</td><td>+15</td></tr>
 *   <tr><td rowspan="3">车龄</td><td>&gt; 10 年</td><td>+15</td></tr>
 *   <tr><td>6–10 年</td><td>+8</td></tr>
 *   <tr><td>≤ 5 年</td><td>0（基准线）</td></tr>
 *   <tr><td rowspan="2">车辆价值</td><td>&gt; ¥500,000</td><td>+10</td></tr>
 *   <tr><td>≤ ¥500,000</td><td>0（基准线）</td></tr>
 * </table>
 *
 * <p>最终评分钳制在 [0, 100] 区间。
 * 核保阈值：≤ 60 → 批准，61–79 → 转人工，≥ 80 → 拒绝。
 */
@Service
public class RiskCalculationService {

    /**
     * 计算核保风险评分（0–100）。
     *
     * @param customer 客户信息
     * @param vehicle  车辆信息
     * @return 风险评分（0–100）
     */
    public double calculateRiskScore(Customer customer, Vehicle vehicle) {
        double riskScore = 0.0;

        // ── 年龄 ──────────────────────────────────────────────
        int age = customer.getAge();
        if (age < 25) {
            riskScore += 25;          // 年轻 / 缺乏经验
        } else if (age <= 35) {
            riskScore += 15;          // 青年
        } else if (age >= 65) {
            riskScore += 20;          // 老年
        }
        // else: 36–64 → 基准线（不加分）

        // ── 驾龄 ──────────────────────────────────────────────
        int drivingExperience = customer.getDrivingExperienceYears();
        if (drivingExperience < 3) {
            riskScore += 20;          // 新手
        } else if (drivingExperience <= 5) {
            riskScore += 10;          // 驾龄较短
        } else if (drivingExperience >= 20) {
            riskScore -= 10;          // 老司机奖励
        }
        // else: 6–19 年 → 基准线（不加分）

        // ── 事故记录 ──────────────────────────────────────────
        int accidentCount = customer.getAccidentCount();
        riskScore += accidentCount * 15;

        // ── 车龄 ─────────────────────────────────────────────
        int vehicleAge = java.time.Year.now().getValue() - vehicle.getYear();
        if (vehicleAge > 10) {
            riskScore += 15;          // 老旧车辆
        } else if (vehicleAge > 5) {
            riskScore += 8;           // 车龄偏高
        }
        // else: ≤ 5 年 → 基准线（不加分）

        // ── 车辆价值 ─────────────────────────────────────────
        double vehicleValue = vehicle.getVehicleValue();
        if (vehicleValue > 500_000) {
            riskScore += 10;          // 高价值车辆
        }
        // else: ≤ ¥500k → 基准线（不加分）

        return Math.min(100, Math.max(0, riskScore));
    }
}