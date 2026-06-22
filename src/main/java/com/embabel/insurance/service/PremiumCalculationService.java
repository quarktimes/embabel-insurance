package com.embabel.insurance.service;

import com.embabel.insurance.entity.Vehicle;
import org.springframework.stereotype.Service;

/**
 * 保费计算服务，基于车辆价值、风险评分和险种类型计算保费。
 *
 * <p>公式：保费 = 车辆价值 × 基准费率(2%) × 风险系数 × 险种系数
 */
@Service
public class PremiumCalculationService {

    /** 基准费率：车辆价值的 2% */
    private static final double BASE_PREMIUM_RATE = 0.02;
    /** 低风险系数（风险评分 &lt; 40） */
    private static final double RISK_MULTIPLIER_LOW = 0.8;
    /** 中风险系数（40 ≤ 风险评分 &lt; 70） */
    private static final double RISK_MULTIPLIER_MEDIUM = 1.0;
    /** 高风险系数（风险评分 ≥ 70） */
    private static final double RISK_MULTIPLIER_HIGH = 1.5;

    public double calculatePremium(Vehicle vehicle, double riskScore, String coverageType) {
        double basePremium = vehicle.getVehicleValue() * BASE_PREMIUM_RATE;
        double riskMultiplier;

        if (riskScore < 40) {
            riskMultiplier = RISK_MULTIPLIER_LOW;
        } else if (riskScore < 70) {
            riskMultiplier = RISK_MULTIPLIER_MEDIUM;
        } else {
            riskMultiplier = RISK_MULTIPLIER_HIGH;
        }

        double coverageMultiplier = getCoverageMultiplier(coverageType);

        return basePremium * riskMultiplier * coverageMultiplier;
    }

    private double getCoverageMultiplier(String coverageType) {
        return switch (coverageType.toUpperCase()) {
            case "THIRD_PARTY" -> 0.5;
            case "THIRD_PARTY_FIRE_THEFT" -> 0.75;
            case "COMPREHENSIVE" -> 1.0;
            default -> 1.0;
        };
    }
}