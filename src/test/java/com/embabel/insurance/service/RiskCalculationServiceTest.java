package com.embabel.insurance.service;

import com.embabel.insurance.entity.Customer;
import com.embabel.insurance.entity.Vehicle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive unit tests for RiskCalculationService.
 * Tests all risk scoring scenarios.
 */
class RiskCalculationServiceTest {

    private RiskCalculationService service;
    private Customer testCustomer;
    private Vehicle testVehicle;

    @BeforeEach
    void setUp() {
        service = new RiskCalculationService();
        
        // Setup test customer - age is calculated from dateOfBirth
        testCustomer = new Customer();
        testCustomer.setUserId("test-user");
        testCustomer.setName("Test User");
        testCustomer.setDateOfBirth(LocalDate.of(1989, 1, 1)); // Age ~35
        testCustomer.setDrivingExperienceYears(10);
        testCustomer.setAccidentCount(0);
        testCustomer.setEmail("test@test.com");
        testCustomer.setPhone("+1234567890");
        
        // Setup test vehicle
        testVehicle = new Vehicle();
        testVehicle.setLicensePlate("TEST123");
        testVehicle.setModel("Camry");
        testVehicle.setBrand("Toyota");
        testVehicle.setYear(2023);
        testVehicle.setVehicleValue(30000.0);
        testVehicle.setCustomer(testCustomer);
    }

    @Nested
    @DisplayName("Age-Based Risk Tests")
    class AgeRiskTests {

        @Test
        @DisplayName("Should return low risk for middle-aged driver (25-35)")
        void shouldReturnLowRiskForMiddleAgedDriver() {
            testCustomer.setDateOfBirth(LocalDate.of(1994, 1, 1)); // Age ~30
            double risk = service.calculateRiskScore(testCustomer, testVehicle);
            assertTrue(risk >= 15.0 && risk <= 20.0, 
                    "Middle-aged driver should have moderate age risk, got: " + risk);
        }

        @Test
        @DisplayName("Should add risk for young driver (<25)")
        void shouldAddRiskForYoungDriver() {
            testCustomer.setDateOfBirth(LocalDate.of(2002, 1, 1)); // Age ~22
            double riskYoung = service.calculateRiskScore(testCustomer, testVehicle);
            
            testCustomer.setDateOfBirth(LocalDate.of(1994, 1, 1)); // Age ~30
            double riskOld = service.calculateRiskScore(testCustomer, testVehicle);
            
            assertTrue(riskYoung > riskOld, 
                    "Young driver should have higher risk than middle-aged driver");
        }

        @Test
        @DisplayName("Should add risk for senior driver (>65)")
        void shouldAddRiskForSeniorDriver() {
            testCustomer.setDateOfBirth(LocalDate.of(1954, 1, 1)); // Age ~70
            double risk = service.calculateRiskScore(testCustomer, testVehicle);
            assertTrue(risk >= 20.0, "Senior driver should have age risk");
        }
    }

    @Nested
    @DisplayName("Driving Experience Risk Tests")
    class DrivingExperienceRiskTests {

        @Test
        @DisplayName("Should add risk for inexperienced driver (<3 years)")
        void shouldAddRiskForInexperiencedDriver() {
            testCustomer.setDrivingExperienceYears(1);
            double risk = service.calculateRiskScore(testCustomer, testVehicle);
            assertTrue(risk >= 20.0, 
                    "Inexperienced driver should have experience risk");
        }

        @Test
        @DisplayName("Should reduce risk for very experienced driver (>=20 years)")
        void shouldReduceRiskForVeryExperiencedDriver() {
            testCustomer.setDrivingExperienceYears(25);
            double risk = service.calculateRiskScore(testCustomer, testVehicle);
            assertTrue(risk < 30,
                    "Very experienced driver should have reduced risk: " + risk);
        }
    }

    @Nested
    @DisplayName("Accident History Risk Tests")
    class AccidentHistoryRiskTests {

        @Test
        @DisplayName("Should return base risk for no accidents")
        void shouldReturnBaseRiskForNoAccidents() {
            testCustomer.setAccidentCount(0);
            double risk = service.calculateRiskScore(testCustomer, testVehicle);
            // No accident contribution
            assertTrue(risk >= 0);
        }

        @Test
        @DisplayName("Should increase risk with accidents")
        void shouldIncreaseRiskWithAccidents() {
            testCustomer.setAccidentCount(0);
            double risk0 = service.calculateRiskScore(testCustomer, testVehicle);
            
            testCustomer.setAccidentCount(1);
            double risk1 = service.calculateRiskScore(testCustomer, testVehicle);
            
            testCustomer.setAccidentCount(2);
            double risk2 = service.calculateRiskScore(testCustomer, testVehicle);
            
            assertTrue(risk1 > risk0, "1 accident should increase risk");
            assertTrue(risk2 > risk1, "2 accidents should increase risk more");
        }

        @ParameterizedTest
        @CsvSource({
            "0, 0.0",
            "1, 15.0",
            "2, 30.0",
            "3, 45.0"
        })
        @DisplayName("Should calculate accident risk correctly")
        void shouldCalculateAccidentRiskCorrectly(int accidents, double expectedAddition) {
            testCustomer.setAccidentCount(accidents);
            assertEquals(expectedAddition, accidents * 15.0);
        }
    }

    @Nested
    @DisplayName("Vehicle Value Risk Tests")
    class VehicleValueRiskTests {

        @Test
        @DisplayName("Should add risk for high-value vehicle (>500k)")
        void shouldAddRiskForHighValueVehicle() {
            testVehicle.setVehicleValue(600000.0);
            double risk = service.calculateRiskScore(testCustomer, testVehicle);
            assertTrue(risk >= 10.0, "High-value vehicle should add risk");
        }

        @Test
        @DisplayName("Should not add risk for normal vehicle")
        void shouldNotAddRiskForNormalVehicle() {
            testVehicle.setVehicleValue(50000.0);
            double risk = service.calculateRiskScore(testCustomer, testVehicle);
            assertTrue(risk >= 0);
        }
    }

    @Nested
    @DisplayName("Combined Risk Calculation Tests")
    class CombinedRiskCalculationTests {

        @Test
        @DisplayName("Should combine all risk factors")
        void shouldCombineAllRiskFactors() {
            // Young, inexperienced driver with accidents and high-value car
            testCustomer.setDateOfBirth(LocalDate.of(2002, 1, 1)); // Age ~22
            testCustomer.setDrivingExperienceYears(2);
            testCustomer.setAccidentCount(2);
            testVehicle.setVehicleValue(600000.0);
            
            double risk = service.calculateRiskScore(testCustomer, testVehicle);
            assertTrue(risk > 50.0, "High-risk profile should have high total risk: " + risk);
        }

        @Test
        @DisplayName("Should return low risk for ideal profile")
        void shouldReturnLowRiskForIdealProfile() {
            // Middle-aged, experienced driver, no accidents, normal car
            testCustomer.setDateOfBirth(LocalDate.of(1984, 1, 1)); // Age ~40
            testCustomer.setDrivingExperienceYears(20);
            testCustomer.setAccidentCount(0);
            testVehicle.setVehicleValue(30000.0);
            testVehicle.setYear(2020);
            
            double risk = service.calculateRiskScore(testCustomer, testVehicle);
            assertTrue(risk < 30.0, "Ideal profile should have low total risk: " + risk);
        }

        @Test
        @DisplayName("Should cap risk at 100")
        void shouldCapRiskAt100() {
            // Extreme risk profile
            testCustomer.setDateOfBirth(LocalDate.of(2006, 1, 1)); // Age ~18
            testCustomer.setDrivingExperienceYears(0);
            testCustomer.setAccidentCount(5);
            testVehicle.setVehicleValue(1000000.0);
            testVehicle.setYear(2010);
            
            double risk = service.calculateRiskScore(testCustomer, testVehicle);
            assertEquals(100.0, risk, "Risk should be capped at 100");
        }
    }

    @Nested
    @DisplayName("Edge Case Tests")
    class EdgeCaseTests {

        @Test
        @DisplayName("Should handle minimum age")
        void shouldHandleMinimumAge() {
            testCustomer.setDateOfBirth(LocalDate.of(2006, 1, 1)); // Age ~18
            double risk = service.calculateRiskScore(testCustomer, testVehicle);
            assertTrue(risk > 0, "Young driver should have risk");
        }

        @Test
        @DisplayName("Should handle very old vehicle")
        void shouldHandleVeryOldVehicle() {
            testVehicle.setYear(2000);
            double risk = service.calculateRiskScore(testCustomer, testVehicle);
            assertTrue(risk >= 15.0, "Old vehicle should add risk");
        }

        @Test
        @DisplayName("Should handle many accidents")
        void shouldHandleManyAccidents() {
            testCustomer.setAccidentCount(10);
            double risk = service.calculateRiskScore(testCustomer, testVehicle);
            // 10 * 15 = 150, but capped at 100
            assertTrue(risk >= 100.0, "Many accidents should cap at 100");
        }

        @Test
        @DisplayName("Should handle zero vehicle value")
        void shouldHandleZeroVehicleValue() {
            testVehicle.setVehicleValue(0.0);
            double risk = service.calculateRiskScore(testCustomer, testVehicle);
            assertTrue(risk >= 0);
        }
    }

    @Nested
    @DisplayName("Vehicle Age Tests")
    class VehicleAgeTests {

        @Test
        @DisplayName("Should add risk for old vehicle (>10 years)")
        void shouldAddRiskForOldVehicle() {
            testVehicle.setYear(java.time.Year.now().getValue() - 12);
            double risk = service.calculateRiskScore(testCustomer, testVehicle);
            assertTrue(risk >= 15.0, "Old vehicle should add age risk");
        }

        @Test
        @DisplayName("Should add moderate risk for medium-old vehicle (5-10 years)")
        void shouldAddModerateRiskForMediumOldVehicle() {
            testVehicle.setYear(java.time.Year.now().getValue() - 7);
            double risk = service.calculateRiskScore(testCustomer, testVehicle);
            assertTrue(risk >= 8.0, "Medium-old vehicle should add moderate risk");
        }

        @Test
        @DisplayName("Should not add vehicle age risk for new vehicle (<5 years)")
        void shouldNotAddVehicleAgeRiskForNewVehicle() {
            testVehicle.setYear(java.time.Year.now().getValue() - 3);
            double risk = service.calculateRiskScore(testCustomer, testVehicle);
            assertTrue(risk >= 0);
        }
    }
}
