package com.embabel.insurance.agent;

import com.embabel.agent.domain.io.UserInput;
import com.embabel.agent.test.unit.FakeOperationContext;
import com.embabel.agent.test.unit.FakePromptRunner;
import com.embabel.insurance.dto.VehicleInfo;
import com.embabel.insurance.entity.Customer;
import com.embabel.insurance.entity.Quote;
import com.embabel.insurance.entity.Vehicle;
import com.embabel.insurance.repository.QuoteRepository;
import com.embabel.insurance.service.DataService;
import com.embabel.insurance.service.PremiumCalculationService;
import com.embabel.insurance.service.RiskCalculationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 核保 Agent 单元测试 — 使用 Embabel FakeOperationContext 模拟 LLM 交互。
 *
 * <p>测试覆盖：
 * <ol>
 *   <li>extractVehicleInfo — LLM 提示构造与结构化对象提取</li>
 *   <li>lookupCustomer — 客户查找与 sentinel 传播</li>
 *   <li>lookupVehicle — 车辆查找与错误路由</li>
 *   <li>assessRisk — 入口动作：风险评分计算与 @State 分类</li>
 *   <li>LowRiskQuote / MediumRiskReview / HighRiskDecline — 终态 @State</li>
 *   <li>CustomerNotFound / VehicleLookupError / ExtractionFailed — 错误 @State</li>
 * </ol>
 */
class UnderwritingAgentTest {

    private UnderwritingAgent agent;
    private DataService dataService;
    private RiskCalculationService riskCalcService;
    private PremiumCalculationService premiumCalcService;
    private QuoteRepository quoteRepository;

    private Customer testCustomer;
    private Vehicle testVehicle;

    @BeforeEach
    void setUp() {
        dataService = mock(DataService.class);
        riskCalcService = mock(RiskCalculationService.class);
        premiumCalcService = mock(PremiumCalculationService.class);
        quoteRepository = mock(QuoteRepository.class);

        agent = new UnderwritingAgent(dataService, riskCalcService, premiumCalcService, quoteRepository);

        testCustomer = new Customer("test-user", "Test User",
                LocalDate.of(1990, 5, 15), 10, 0,
                "test@test.com", "1234567890");
        testCustomer.setId(1L);

        testVehicle = new Vehicle("ABC123", "RAV4", "Toyota", 2020, 250_000, testCustomer);
        testVehicle.setId(1L);
    }

    // ── extractVehicleInfo ──────────────────────────────────────────

    @Nested
    @DisplayName("extractVehicleInfo — LLM 提取车辆信息")
    class ExtractVehicleInfoTests {

        @Test
        @DisplayName("Should extract vehicle info from natural language via LLM")
        void shouldExtractVehicleInfoViaLlm() {
            when(dataService.getCustomerByUserId("test-user")).thenReturn(Optional.of(testCustomer));

            var context = FakeOperationContext.create();
            var expectedInfo = new VehicleInfo("RAV4", "Toyota", "ABC123");
            context.expectResponse(expectedInfo);

            UserInput input = new UserInput(
                    "I want to insure my Toyota RAV4 license ABC123 userId=test-user", Instant.now());

            VehicleInfo result = agent.extractVehicleInfo(input, context);

            assertNotNull(result);
            assertEquals("RAV4", result.getModel());
            assertEquals("Toyota", result.getBrand());
            assertEquals("ABC123", result.getLicensePlate());

            // 验证 LLM 提示包含关键信息
            var promptRunner = (FakePromptRunner) context.promptRunner();
            String prompt = promptRunner.getLlmInvocations().get(0).getPrompt();
            assertTrue(prompt.contains("RAV4"), "Prompt should contain vehicle model 'RAV4'");
            assertTrue(prompt.contains("Toyota"), "Prompt should contain brand 'Toyota'");
            assertTrue(prompt.contains("licensePlate"), "Prompt should mention licensePlate");
            assertTrue(prompt.contains("vehicle information"), "Prompt should mention vehicle information");
        }

        @Test
        @DisplayName("Should fast-fail when customer not found — skip LLM call")
        void shouldFastFailWhenCustomerNotFound() {
            when(dataService.getCustomerByUserId("unknown-user")).thenReturn(Optional.empty());

            var context = FakeOperationContext.create();
            UserInput input = new UserInput(
                    "I want to insure my Toyota RAV4 userId=unknown-user", Instant.now());

            VehicleInfo result = agent.extractVehicleInfo(input, context);

            // 返回空 VehicleInfo（快速失败，不调 LLM）
            assertNull(result.getModel());
            assertNull(result.getBrand());
            assertNull(result.getLicensePlate());

            // 验证 blackboard 上有错误信息
            String error = (String) context.get("underwriting_error");
            assertNotNull(error);
            assertTrue(error.contains("Customer not found"));

            // 验证没有 LLM 调用发生
            var promptRunner = (FakePromptRunner) context.promptRunner();
            assertTrue(promptRunner.getLlmInvocations().isEmpty(),
                    "No LLM call should happen when customer not found");
        }

        @Test
        @DisplayName("Should detect non-insurance request — skip LLM call")
        void shouldDetectNonInsuranceRequest() {
            when(dataService.getCustomerByUserId("test-user")).thenReturn(Optional.of(testCustomer));

            var context = FakeOperationContext.create();
            UserInput input = new UserInput(
                    "What's the weather today? userId=test-user", Instant.now());

            VehicleInfo result = agent.extractVehicleInfo(input, context);

            assertNull(result.getModel());
            assertNull(result.getBrand());
            assertNull(result.getLicensePlate());

            String error = (String) context.get("underwriting_error");
            assertNotNull(error);
            assertTrue(error.contains("No insurance-related keywords"));

            // 验证没有 LLM 调用
            var promptRunner = (FakePromptRunner) context.promptRunner();
            assertTrue(promptRunner.getLlmInvocations().isEmpty());
        }

        @Test
        @DisplayName("Should return empty VehicleInfo when LLM returns null")
        void shouldReturnEmptyWhenLlmReturnsNull() {
            when(dataService.getCustomerByUserId("test-user")).thenReturn(Optional.of(testCustomer));

            var context = FakeOperationContext.create();
            context.expectResponse(null); // LLM 返回 null

            UserInput input = new UserInput(
                    "I want to insure my car userId=test-user", Instant.now());

            VehicleInfo result = agent.extractVehicleInfo(input, context);

            assertNull(result.getModel());
            assertNull(result.getBrand());
            assertNull(result.getLicensePlate());
        }
    }

    // ── lookupCustomer ──────────────────────────────────────────────

    @Nested
    @DisplayName("lookupCustomer — 客户查找")
    class LookupCustomerTests {

        @Test
        @DisplayName("Should find customer by userId")
        void shouldFindCustomerByUserId() {
            when(dataService.getCustomerByUserId("test-user")).thenReturn(Optional.of(testCustomer));

            var context = FakeOperationContext.create();
            UserInput input = new UserInput(
                    "I want to insure my car userId=test-user", Instant.now());

            Customer result = agent.lookupCustomer(input, context);

            assertNotNull(result);
            assertEquals("test-user", result.getUserId());
            assertEquals("Test User", result.getName());
            verify(dataService).getCustomerByUserId("test-user");
        }

        @Test
        @DisplayName("Should return sentinel and store error when customer not found")
        void shouldReturnSentinelWhenCustomerNotFound() {
            when(dataService.getCustomerByUserId("unknown")).thenReturn(Optional.empty());

            var context = FakeOperationContext.create();
            UserInput input = new UserInput(
                    "I want to insure my car userId=unknown", Instant.now());

            Customer result = agent.lookupCustomer(input, context);

            assertTrue(Customer.isLookupFailed(result));
            String error = (String) context.get("underwriting_error");
            assertNotNull(error);
            assertTrue(error.contains("Customer not found"));
        }

        @Test
        @DisplayName("Should not overwrite existing error on blackboard")
        void shouldNotOverwriteExistingError() {
            when(dataService.getCustomerByUserId("unknown")).thenReturn(Optional.empty());

            var context = FakeOperationContext.create();
            context.bind("underwriting_error", "Customer not found: unknown"); // 上游已有 error

            UserInput input = new UserInput(
                    "I want to insure my car userId=unknown", Instant.now());

            agent.lookupCustomer(input, context);

            // 不应覆盖已有的错误信息
            String error = (String) context.get("underwriting_error");
            assertEquals("Customer not found: unknown", error);
        }
    }

    // ── lookupVehicle ───────────────────────────────────────────────

    @Nested
    @DisplayName("lookupVehicle — 车辆查找")
    class LookupVehicleTests {

        @Test
        @DisplayName("Should find vehicle by license plate")
        void shouldFindVehicleByLicensePlate() {
            var context = FakeOperationContext.create();
            var vehicleInfo = new VehicleInfo("RAV4", "Toyota", "ABC123");

            when(dataService.getVehicleByLicensePlate("ABC123"))
                    .thenReturn(Optional.of(testVehicle));

            Vehicle result = agent.lookupVehicle(vehicleInfo, testCustomer, context);

            assertNotNull(result);
            assertEquals("ABC123", result.getLicensePlate());
            assertEquals("RAV4", result.getModel());
        }

        @Test
        @DisplayName("Should find vehicle by model when no license plate")
        void shouldFindVehicleByModel() {
            var context = FakeOperationContext.create();
            var vehicleInfo = new VehicleInfo("RAV4", "Toyota", null);

            when(dataService.getVehiclesByCustomerAndModel(testCustomer.getId(), "RAV4"))
                    .thenReturn(java.util.List.of(testVehicle));
            when(dataService.getVehicleByCustomerAndModel(testCustomer.getId(), "RAV4"))
                    .thenReturn(Optional.of(testVehicle));

            Vehicle result = agent.lookupVehicle(vehicleInfo, testCustomer, context);

            assertNotNull(result);
            assertEquals("RAV4", result.getModel());
        }

        @Test
        @DisplayName("Should return sentinel when customer lookup already failed")
        void shouldPropagateCustomerLookupFailure() {
            var context = FakeOperationContext.create();
            var vehicleInfo = new VehicleInfo("RAV4", "Toyota", "ABC123");
            var failedCustomer = Customer.lookupFailed();

            Vehicle result = agent.lookupVehicle(vehicleInfo, failedCustomer, context);

            assertTrue(Vehicle.isLookupFailed(result));
        }

        @Test
        @DisplayName("Should return sentinel when vehicle info is empty")
        void shouldReturnSentinelForEmptyVehicleInfo() {
            var context = FakeOperationContext.create();
            var emptyInfo = new VehicleInfo(null, null, null);

            Vehicle result = agent.lookupVehicle(emptyInfo, testCustomer, context);

            assertTrue(Vehicle.isLookupFailed(result));
        }

        @Test
        @DisplayName("Should return sentinel with error when license plate not found")
        void shouldReturnSentinelWhenLicensePlateNotFound() {
            var context = FakeOperationContext.create();
            var vehicleInfo = new VehicleInfo("RAV4", "Toyota", "NOTFOUND");

            when(dataService.getVehicleByLicensePlate("NOTFOUND"))
                    .thenReturn(Optional.empty());

            Vehicle result = agent.lookupVehicle(vehicleInfo, testCustomer, context);

            assertTrue(Vehicle.isLookupFailed(result));
            String error = (String) context.get("underwriting_error");
            assertNotNull(error);
            assertTrue(error.contains("Vehicle not found"));
        }

        @Test
        @DisplayName("Should return sentinel with error when multiple vehicles match model")
        void shouldReturnSentinelWhenMultipleVehiclesMatch() {
            var context = FakeOperationContext.create();
            var vehicleInfo = new VehicleInfo("RAV4", "Toyota", null);

            when(dataService.getVehiclesByCustomerAndModel(testCustomer.getId(), "RAV4"))
                    .thenReturn(java.util.List.of(testVehicle, testVehicle));

            Vehicle result = agent.lookupVehicle(vehicleInfo, testCustomer, context);

            assertTrue(Vehicle.isLookupFailed(result));
            String error = (String) context.get("underwriting_error");
            assertNotNull(error);
            assertTrue(error.contains("multiple vehicles"));
        }

        @Test
        @DisplayName("Should fallback to customer's only vehicle when no model specified")
        void shouldFallbackToCustomersOnlyVehicle() {
            var context = FakeOperationContext.create();

            // 直接传不是空的 VehicleInfo 需要绕过 isVehicleInfoEmpty 检查
            // 这个 case 实际需要 model 为空但不是 null
            var partialInfo = new VehicleInfo("", null, null);
            when(dataService.getVehiclesByCustomerId(testCustomer.getId()))
                    .thenReturn(java.util.List.of(testVehicle));

            Vehicle result = agent.lookupVehicle(partialInfo, testCustomer, context);

            assertFalse(Vehicle.isLookupFailed(result));
        }
    }

    // ── assessRisk — 入口动作：风险分类 ──────────────────────────────

    @Nested
    @DisplayName("assessRisk — 风险评分与 @State 分类")
    class AssessRiskTests {

        @Test
        @DisplayName("Should classify low risk (score ≤ 60) → LowRiskQuote")
        void shouldClassifyLowRisk() {
            when(riskCalcService.calculateRiskScore(testCustomer, testVehicle)).thenReturn(45.0);
            when(premiumCalcService.calculatePremium(testVehicle, 45.0, "COMPREHENSIVE")).thenReturn(4000.0);
            when(quoteRepository.save(any(Quote.class))).thenAnswer(inv -> {
                Quote q = inv.getArgument(0);
                q.setId(100L);
                return q;
            });

            var context = FakeOperationContext.create();
            var decision = agent.assessRisk(testCustomer, testVehicle, context);

            assertInstanceOf(UnderwritingAgent.LowRiskQuote.class, decision);
            var lowRisk = (UnderwritingAgent.LowRiskQuote) decision;
            assertEquals(45.0, lowRisk.riskScore());
            assertEquals(4000.0, lowRisk.premiumAmount());
        }

        @Test
        @DisplayName("Should classify medium risk (60 < score < 80) → MediumRiskReview")
        void shouldClassifyMediumRisk() {
            when(riskCalcService.calculateRiskScore(testCustomer, testVehicle)).thenReturn(65.0);
            when(premiumCalcService.calculatePremium(testVehicle, 65.0, "COMPREHENSIVE")).thenReturn(6000.0);
            when(quoteRepository.save(any(Quote.class))).thenAnswer(inv -> {
                Quote q = inv.getArgument(0);
                q.setId(200L);
                return q;
            });

            var context = FakeOperationContext.create();
            var decision = agent.assessRisk(testCustomer, testVehicle, context);

            assertInstanceOf(UnderwritingAgent.MediumRiskReview.class, decision);
            var medRisk = (UnderwritingAgent.MediumRiskReview) decision;
            assertEquals(65.0, medRisk.riskScore());
        }

        @Test
        @DisplayName("Should classify high risk (score ≥ 80) → HighRiskDecline")
        void shouldClassifyHighRisk() {
            when(riskCalcService.calculateRiskScore(testCustomer, testVehicle)).thenReturn(85.0);
            when(premiumCalcService.calculatePremium(testVehicle, 85.0, "COMPREHENSIVE")).thenReturn(9000.0);
            when(quoteRepository.save(any(Quote.class))).thenAnswer(inv -> {
                Quote q = inv.getArgument(0);
                q.setId(300L);
                return q;
            });

            var context = FakeOperationContext.create();
            var decision = agent.assessRisk(testCustomer, testVehicle, context);

            assertInstanceOf(UnderwritingAgent.HighRiskDecline.class, decision);
            var highRisk = (UnderwritingAgent.HighRiskDecline) decision;
            assertEquals(85.0, highRisk.riskScore());
        }

        @Test
        @DisplayName("Should classify boundary score 60 as low risk")
        void shouldClassifyBoundary60AsLowRisk() {
            when(riskCalcService.calculateRiskScore(testCustomer, testVehicle)).thenReturn(60.0);
            when(premiumCalcService.calculatePremium(testVehicle, 60.0, "COMPREHENSIVE")).thenReturn(4800.0);
            when(quoteRepository.save(any(Quote.class))).thenAnswer(inv -> {
                Quote q = inv.getArgument(0);
                q.setId(101L);
                return q;
            });

            var context = FakeOperationContext.create();
            var decision = agent.assessRisk(testCustomer, testVehicle, context);

            assertInstanceOf(UnderwritingAgent.LowRiskQuote.class, decision);
        }

        @Test
        @DisplayName("Should classify boundary score 79 as medium risk")
        void shouldClassifyBoundary79AsMediumRisk() {
            when(riskCalcService.calculateRiskScore(testCustomer, testVehicle)).thenReturn(79.0);
            when(premiumCalcService.calculatePremium(testVehicle, 79.0, "COMPREHENSIVE")).thenReturn(7000.0);
            when(quoteRepository.save(any(Quote.class))).thenAnswer(inv -> {
                Quote q = inv.getArgument(0);
                q.setId(201L);
                return q;
            });

            var context = FakeOperationContext.create();
            var decision = agent.assessRisk(testCustomer, testVehicle, context);

            assertInstanceOf(UnderwritingAgent.MediumRiskReview.class, decision);
        }

        @Test
        @DisplayName("Should route to CustomerNotFound when customer lookup failed")
        void shouldRouteToCustomerNotFound() {
            var failedCustomer = Customer.lookupFailed();

            var context = FakeOperationContext.create();
            context.bind("underwriting_error", "Customer not found: unknown-user");

            var decision = agent.assessRisk(failedCustomer, testVehicle, context);

            assertInstanceOf(UnderwritingAgent.CustomerNotFound.class, decision);
            var error = (UnderwritingAgent.CustomerNotFound) decision;
            assertTrue(error.message().contains("Customer not found"));
        }

        @Test
        @DisplayName("Should route to VehicleLookupError when vehicle lookup failed")
        void shouldRouteToVehicleLookupError() {
            var failedVehicle = Vehicle.lookupFailed();

            var context = FakeOperationContext.create();
            context.bind("underwriting_error", "Vehicle not found with license plate: XXX");

            var decision = agent.assessRisk(testCustomer, failedVehicle, context);

            assertInstanceOf(UnderwritingAgent.VehicleLookupError.class, decision);
            var error = (UnderwritingAgent.VehicleLookupError) decision;
            assertTrue(error.message().contains("Vehicle not found"));
        }

        @Test
        @DisplayName("Should route to ExtractionFailed when no insurance keywords")
        void shouldRouteToExtractionFailed() {
            var failedVehicle = Vehicle.lookupFailed();

            var context = FakeOperationContext.create();
            context.bind("underwriting_error",
                    "No insurance-related keywords detected in input.");

            var decision = agent.assessRisk(testCustomer, failedVehicle, context);

            assertInstanceOf(UnderwritingAgent.ExtractionFailed.class, decision);
        }

        @Test
        @DisplayName("Should route to ExtractionFailed on risk assessment exception")
        void shouldRouteToExtractionFailedOnException() {
            when(riskCalcService.calculateRiskScore(testCustomer, testVehicle))
                    .thenThrow(new RuntimeException("Calculation error"));

            var context = FakeOperationContext.create();
            var decision = agent.assessRisk(testCustomer, testVehicle, context);

            assertInstanceOf(UnderwritingAgent.ExtractionFailed.class, decision);
        }
    }

    // ── LowRiskQuote @State ─────────────────────────────────────────

    @Nested
    @DisplayName("LowRiskQuote — 自动批准")
    class LowRiskQuoteTests {

        @Test
        @DisplayName("Should issue approved quote and return success result")
        void shouldIssueApprovedQuote() {
            when(quoteRepository.save(any(Quote.class))).thenAnswer(inv -> {
                Quote q = inv.getArgument(0);
                q.setId(100L);
                return q;
            });

            var state = new UnderwritingAgent.LowRiskQuote(
                    quoteRepository, testCustomer, testVehicle, 30.0, 4800.0);

            var result = state.handleLowRisk();

            assertEquals(100L, result.quoteId());
            assertEquals("APPROVED", result.status());
            assertEquals(30.0, result.riskScore());
            assertEquals(4800.0, result.premiumAmount());
            assertTrue(result.message().contains("approved"));
            assertTrue(result.message().contains("4800"));
            assertTrue(result.message().contains("/api/insurance/pay"));
            verify(quoteRepository).save(any(Quote.class));
        }
    }

    // ── MediumRiskReview @State ─────────────────────────────────────

    @Nested
    @DisplayName("MediumRiskReview — 转人工审核")
    class MediumRiskReviewTests {

        @Test
        @DisplayName("Should persist referred quote and return pending result")
        void shouldPersistReferredQuote() {
            when(quoteRepository.save(any(Quote.class))).thenAnswer(inv -> {
                Quote q = inv.getArgument(0);
                q.setId(200L);
                return q;
            });

            var state = new UnderwritingAgent.MediumRiskReview(
                    quoteRepository, testCustomer, testVehicle, 65.0, 6000.0);

            var result = state.handleMediumRisk();

            assertEquals(200L, result.quoteId());
            assertEquals("REFERRED", result.status());
            assertEquals(65.0, result.riskScore());
            assertEquals(6000.0, result.premiumAmount());
            assertTrue(result.message().contains("referred"));
            assertTrue(result.message().contains("human underwriter"));
            verify(quoteRepository).save(any(Quote.class));
        }
    }

    // ── HighRiskDecline @State ──────────────────────────────────────

    @Nested
    @DisplayName("HighRiskDecline — 拒绝申请")
    class HighRiskDeclineTests {

        @Test
        @DisplayName("Should persist declined quote with rejection reason")
        void shouldPersistDeclinedQuote() {
            when(quoteRepository.save(any(Quote.class))).thenAnswer(inv -> {
                Quote q = inv.getArgument(0);
                q.setId(300L);
                return q;
            });

            var state = new UnderwritingAgent.HighRiskDecline(
                    quoteRepository, testCustomer, testVehicle, 88.0);

            var result = state.handleHighRisk();

            assertEquals(300L, result.quoteId());
            assertEquals("DECLINED", result.status());
            assertEquals(88.0, result.riskScore());
            assertEquals(0.0, result.premiumAmount());
            assertTrue(result.message().contains("declined"));
            verify(quoteRepository).save(any(Quote.class));
        }
    }

    // ── 错误 @State ─────────────────────────────────────────────────

    @Nested
    @DisplayName("Error @State — 错误路由")
    class ErrorStateTests {

        @Test
        @DisplayName("CustomerNotFound should bind error and return error result")
        void customerNotFoundShouldBindError() {
            var context = FakeOperationContext.create();
            var state = new UnderwritingAgent.CustomerNotFound("Customer not found: test-user");

            var result = state.handleCustomerNotFound(context);

            assertEquals("ERROR", result.status());
            assertNull(result.quoteId());
            assertTrue(result.message().contains("Customer not found"));

            // 验证重新 bind 了错误信息（框架切换 @State 时 clear Blackboard）
            String error = (String) context.get("underwriting_error");
            assertEquals("Customer not found: test-user", error);
        }

        @Test
        @DisplayName("VehicleLookupError should bind error and return error result")
        void vehicleLookupErrorShouldBindError() {
            var context = FakeOperationContext.create();
            var state = new UnderwritingAgent.VehicleLookupError("Vehicle not found");

            var result = state.handleVehicleLookupError(context);

            assertEquals("ERROR", result.status());
            assertNull(result.quoteId());
            assertTrue(result.message().contains("Vehicle not found"));

            String error = (String) context.get("underwriting_error");
            assertEquals("Vehicle not found", error);
        }

        @Test
        @DisplayName("ExtractionFailed should bind error and return error result")
        void extractionFailedShouldBindError() {
            var context = FakeOperationContext.create();
            var state = new UnderwritingAgent.ExtractionFailed(
                    "Failed to extract vehicle information");

            var result = state.handleExtractionFailed(context);

            assertEquals("ERROR", result.status());
            assertNull(result.quoteId());
            assertTrue(result.message().contains("Failed to extract"));

            String error = (String) context.get("underwriting_error");
            assertEquals("Failed to extract vehicle information", error);
        }
    }
}
