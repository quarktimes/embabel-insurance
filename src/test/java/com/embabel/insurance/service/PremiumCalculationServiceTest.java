package com.embabel.insurance.service;

import com.embabel.insurance.entity.Vehicle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for PremiumCalculationService.
 * Tests premium calculation for all risk tiers, coverage types, and edge cases.
 */
class PremiumCalculationServiceTest {

    private PremiumCalculationService service;
    private Vehicle vehicle;

    @BeforeEach
    void setUp() {
        service = new PremiumCalculationService();

        vehicle = new Vehicle();
        vehicle.setLicensePlate("TEST123");
        vehicle.setModel("Camry");
        vehicle.setBrand("Toyota");
        vehicle.setYear(2023);
        vehicle.setVehicleValue(300_000.0);
    }

    @Nested
    @DisplayName("Base Premium Calculation Tests")
    class BasePremiumTests {

        @Test
        @DisplayName("Should calculate base premium as 2% of vehicle value")
        void shouldCalculateBasePremium() {
            // base = 300_000 * 0.02 = 6_000, low risk multiplier 0.8, comprehensive 1.0
            // → 6000 * 0.8 * 1.0 = 4800
            double premium = service.calculatePremium(vehicle, 30.0, "COMPREHENSIVE");
            assertEquals(4800.0, premium, 0.01, "Expected 4800, got: " + premium);
        }

        @Test
        @DisplayName("Should return 0 premium for 0 vehicle value")
        void shouldReturnZeroForZeroValue() {
            vehicle.setVehicleValue(0.0);
            double premium = service.calculatePremium(vehicle, 50.0, "COMPREHENSIVE");
            assertEquals(0.0, premium, 0.01);
        }
    }

    @Nested
    @DisplayName("Risk Tier Multiplier Tests")
    class RiskTierTests {

        @Test
        @DisplayName("Should apply low risk multiplier (0.8) for risk < 40")
        void shouldApplyLowRiskMultiplier() {
            double premium = service.calculatePremium(vehicle, 0.0, "COMPREHENSIVE");
            // base = 300_000 * 0.02 = 6_000, multiplier = 0.8, coverage = 1.0
            assertEquals(4800.0, premium, 0.01);

            premium = service.calculatePremium(vehicle, 39.0, "COMPREHENSIVE");
            assertEquals(4800.0, premium, 0.01);
        }

        @Test
        @DisplayName("Should apply medium risk multiplier (1.0) for 40 ≤ risk < 70")
        void shouldApplyMediumRiskMultiplier() {
            double premium = service.calculatePremium(vehicle, 40.0, "COMPREHENSIVE");
            // base = 6_000, multiplier = 1.0, coverage = 1.0
            assertEquals(6000.0, premium, 0.01);

            premium = service.calculatePremium(vehicle, 69.0, "COMPREHENSIVE");
            assertEquals(6000.0, premium, 0.01);
        }

        @Test
        @DisplayName("Should apply high risk multiplier (1.5) for risk ≥ 70")
        void shouldApplyHighRiskMultiplier() {
            double premium = service.calculatePremium(vehicle, 70.0, "COMPREHENSIVE");
            // base = 6_000, multiplier = 1.5, coverage = 1.0
            assertEquals(9000.0, premium, 0.01);

            premium = service.calculatePremium(vehicle, 100.0, "COMPREHENSIVE");
            assertEquals(9000.0, premium, 0.01);
        }
    }

    @Nested
    @DisplayName("Coverage Type Multiplier Tests")
    class CoverageTypeTests {

        @Test
        @DisplayName("Should apply THIRD_PARTY coverage multiplier (0.5)")
        void shouldApplyThirdPartyCoverage() {
            double premium = service.calculatePremium(vehicle, 50.0, "THIRD_PARTY");
            // base = 6_000, multiplier = 1.0, coverage = 0.5
            assertEquals(3000.0, premium, 0.01);
        }

        @Test
        @DisplayName("Should apply THIRD_PARTY_FIRE_THEFT coverage multiplier (0.75)")
        void shouldApplyThirdPartyFireTheftCoverage() {
            double premium = service.calculatePremium(vehicle, 50.0, "THIRD_PARTY_FIRE_THEFT");
            // base = 6_000, multiplier = 1.0, coverage = 0.75
            assertEquals(4500.0, premium, 0.01);
        }

        @Test
        @DisplayName("Should apply COMPREHENSIVE coverage multiplier (1.0)")
        void shouldApplyComprehensiveCoverage() {
            double premium = service.calculatePremium(vehicle, 50.0, "COMPREHENSIVE");
            assertEquals(6000.0, premium, 0.01);
        }

        @Test
        @DisplayName("Should default to 1.0 for unknown coverage type")
        void shouldDefaultForUnknownCoverage() {
            double premium = service.calculatePremium(vehicle, 50.0, "UNKNOWN");
            assertEquals(6000.0, premium, 0.01);
        }

        @Test
        @DisplayName("Should be case-insensitive for coverage type")
        void shouldBeCaseInsensitive() {
            double premium = service.calculatePremium(vehicle, 50.0, "comprehensive");
            assertEquals(6000.0, premium, 0.01);

            premium = service.calculatePremium(vehicle, 50.0, "third_party");
            assertEquals(3000.0, premium, 0.01);
        }
    }

    @Nested
    @DisplayName("Combined Calculation Tests")
    class CombinedCalculationTests {

        @Test
        @DisplayName("Should combine all factors correctly")
        void shouldCombineAllFactors() {
            // Low risk + THIRD_PARTY: 6000 * 0.8 * 0.5 = 2400
            double premium = service.calculatePremium(vehicle, 20.0, "THIRD_PARTY");
            assertEquals(2400.0, premium, 0.01);

            // High risk + COMPREHENSIVE: 6000 * 1.5 * 1.0 = 9000
            premium = service.calculatePremium(vehicle, 80.0, "COMPREHENSIVE");
            assertEquals(9000.0, premium, 0.01);

            // Medium risk + THIRD_PARTY_FIRE_THEFT: 6000 * 1.0 * 0.75 = 4500
            premium = service.calculatePremium(vehicle, 50.0, "THIRD_PARTY_FIRE_THEFT");
            assertEquals(4500.0, premium, 0.01);
        }

        @Test
        @DisplayName("Should scale with vehicle value")
        void shouldScaleWithVehicleValue() {
            vehicle.setVehicleValue(500_000.0);
            double premium = service.calculatePremium(vehicle, 50.0, "COMPREHENSIVE");
            // base = 500_000 * 0.02 = 10_000, multiplier = 1.0
            assertEquals(10000.0, premium, 0.01);
        }
    }

    @Nested
    @DisplayName("Edge Case Tests")
    class EdgeCaseTests {

        @Test
        @DisplayName("Should handle very high vehicle value")
        void shouldHandleVeryHighValue() {
            vehicle.setVehicleValue(10_000_000.0);
            double premium = service.calculatePremium(vehicle, 50.0, "COMPREHENSIVE");
            // base = 200_000, multiplier = 1.0
            assertEquals(200_000.0, premium, 0.01);
        }

        @Test
        @DisplayName("Should handle negative risk score gracefully")
        void shouldHandleNegativeRiskScore() {
            // negative risk should be treated same as 0 → low risk (0.8)
            double premium = service.calculatePremium(vehicle, -10.0, "COMPREHENSIVE");
            assertEquals(4800.0, premium, 0.01);
        }

        @Test
        @DisplayName("Should handle risk score exactly at boundaries")
        void shouldHandleBoundaryRiskScores() {
            // Boundary: risk = 39 → low (0.8)
            double premium39 = service.calculatePremium(vehicle, 39.0, "COMPREHENSIVE");
            assertEquals(4800.0, premium39, 0.01);

            // Boundary: risk = 40 → medium (1.0)
            double premium40 = service.calculatePremium(vehicle, 40.0, "COMPREHENSIVE");
            assertEquals(6000.0, premium40, 0.01);

            // Boundary: risk = 69 → medium (1.0)
            double premium69 = service.calculatePremium(vehicle, 69.0, "COMPREHENSIVE");
            assertEquals(6000.0, premium69, 0.01);

            // Boundary: risk = 70 → high (1.5)
            double premium70 = service.calculatePremium(vehicle, 70.0, "COMPREHENSIVE");
            assertEquals(9000.0, premium70, 0.01);
        }
    }
}
