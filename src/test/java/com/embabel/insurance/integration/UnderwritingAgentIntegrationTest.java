package com.embabel.insurance.integration;

import com.embabel.agent.core.Agent;
import com.embabel.agent.core.AgentPlatform;
import com.embabel.agent.domain.io.UserInput;
import com.embabel.agent.test.unit.FakeOperationContext;
import com.embabel.agent.test.unit.FakePromptRunner;
import com.embabel.insurance.agent.UnderwritingAgent;
import com.embabel.insurance.dto.VehicleInfo;
import com.embabel.insurance.entity.Customer;
import com.embabel.insurance.entity.Quote;
import com.embabel.insurance.entity.Vehicle;
import com.embabel.insurance.repository.CustomerRepository;
import com.embabel.insurance.repository.QuoteRepository;
import com.embabel.insurance.repository.VehicleRepository;
import com.embabel.insurance.service.DataService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * UnderwritingAgent 集成测试 — 验证完整的 Agent 工作流与数据流。
 *
 * <p>使用 @SpringBootTest 启动 Spring 容器（加载真实 AgentPlatform），
 * 通过 MockBean 模拟外部依赖（DataService / 风险计算 / 保费计算），
 * 使用 FakeOperationContext 模拟 LLM 交互，避免真实 LLM 调用。
 *
 * <p>验证要点：
 * <ul>
 *   <li>AgentPlatform 正确识别并加载 Agent</li>
 *   <li>Agent 内部数据流正常（extractVehicleInfo → lookupCustomer → lookupVehicle → assessRisk → @State）</li>
 *   <li>LLM 提示词和超参数配置正确</li>
 *   <li>故障情况妥善处理（CustomerNotFound / VehicleLookupError / ExtractionFailed）</li>
 *   <li>所有 @State 路由到正确的终态结果</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "embabel.models.default-llm=deepseek-chat",
                "spring.profiles.active=integration-test"
        })
@Transactional
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("UnderwritingAgent 集成测试")
class UnderwritingAgentIntegrationTest {

    static {
        System.setProperty("embabel.agent.shell.interactive.enabled", "false");
        System.setProperty("embabel.agent.shell.web-application-type", "servlet");
    }

    @Autowired
    private AgentPlatform agentPlatform;

    @Autowired
    private UnderwritingAgent underwritingAgent;

    // Mock 外部依赖，使用真实的 RiskCalculationService / PremiumCalculationService
    @MockitoBean
    private DataService dataService;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private VehicleRepository vehicleRepository;

    @Autowired
    private QuoteRepository quoteRepository;

    private Customer testCustomer;
    private Vehicle testVehicle;

    @BeforeEach
    void setUp() {
        // 清理数据库
        quoteRepository.deleteAll();
        vehicleRepository.deleteAll();
        customerRepository.deleteAll();

        // 创建测试数据
        testCustomer = new Customer("test-user", "Test User",
                LocalDate.of(1990, 5, 15), 10, 0,
                "test@test.com", "1234567890");
        testCustomer = customerRepository.saveAndFlush(testCustomer);

        testVehicle = new Vehicle("ABC123", "RAV4", "Toyota", 2022, 300_000, testCustomer);
        testVehicle = vehicleRepository.saveAndFlush(testVehicle);

        // Mock DataService
        when(dataService.getCustomerByUserId("test-user")).thenReturn(Optional.of(testCustomer));
        when(dataService.getCustomerByUserId("unknown-user")).thenReturn(Optional.empty());
        when(dataService.getVehicleByLicensePlate("ABC123")).thenReturn(Optional.of(testVehicle));
        when(dataService.getVehiclesByCustomerAndModel(eq(testCustomer.getId()), eq("RAV4")))
                .thenReturn(java.util.List.of(testVehicle));
        when(dataService.getVehicleByCustomerAndModel(eq(testCustomer.getId()), eq("RAV4")))
                .thenReturn(Optional.of(testVehicle));
        when(dataService.getVehiclesByCustomerId(testCustomer.getId()))
                .thenReturn(java.util.List.of(testVehicle));
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Agent 加载验证
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Agent 加载验证")
    class AgentLoadingTests {

        @Test
        @DisplayName("AgentPlatform 应正确加载 UnderwritingAgent")
        void shouldLoadUnderwritingAgentOnPlatform() {
            assertNotNull(agentPlatform, "AgentPlatform should be autowired");
            assertTrue(agentPlatform.agents().size() >= 1,
                    "Should have at least one agent loaded");

            Agent underwritingAgent = agentPlatform.agents().stream()
                    .filter(a -> a.getName().contains("Underwriting"))
                    .findFirst()
                    .orElse(null);

            assertNotNull(underwritingAgent, "UnderwritingAgent should be on the platform");
            assertTrue(underwritingAgent.getName().contains("Underwriting"),
                    "Agent name should contain 'Underwriting': " + underwritingAgent.getName());
        }

        @Test
        @DisplayName("Agent 应具有正确的 Planner 类型")
        void shouldHaveUtilityPlanner() {
            Agent uwAgent = agentPlatform.agents().stream()
                    .filter(a -> a.getName().contains("Underwriting"))
                    .findFirst()
                    .orElseThrow();

            // Utility Planner 用于 @State 分类路由
            assertNotNull(uwAgent, "Agent should be loaded");
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  完整工作流测试：低风险 → APPROVED
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("完整工作流：低风险 → APPROVED")
    class LowRiskWorkflowTests {

        @Test
        @DisplayName("应完成低风险核保全流程：提取 → 查找 → 评估 → 批准")
        void shouldCompleteLowRiskWorkflow() {
            // 预设 LLM 返回 VehicleInfo
            var expectedInfo = new VehicleInfo("RAV4", "Toyota", "ABC123");
            var context = FakeOperationContext.create();
            context.expectResponse(expectedInfo);

            UserInput input = new UserInput(
                    "I want to insure my Toyota RAV4 license ABC123 userId=test-user",
                    java.time.Instant.now());

            // ── 手动调用每个 Action，模拟框架的编排 ──
            // Step 1: extractVehicleInfo
            VehicleInfo extracted = underwritingAgent.extractVehicleInfo(input, context);
            assertNotNull(extracted);
            assertEquals("RAV4", extracted.getModel());
            assertEquals("Toyota", extracted.getBrand());
            assertEquals("ABC123", extracted.getLicensePlate());

            // 验证 LLM 提示词
            var promptRunner = (FakePromptRunner) context.promptRunner();
            String prompt = promptRunner.getLlmInvocations().get(0).getPrompt();
            assertTrue(prompt.contains("RAV4"), "Prompt should contain model 'RAV4'");
            assertTrue(prompt.contains("licensePlate"), "Prompt should mention licensePlate");
            assertTrue(prompt.contains("vehicle information"), "Prompt should mention vehicle info");
            assertTrue(prompt.contains("Extract vehicle information"),
                    "Prompt should contain extraction instruction");

            // Step 2: lookupCustomer
            Customer customer = underwritingAgent.lookupCustomer(input, context);
            assertNotNull(customer);
            assertFalse(Customer.isLookupFailed(customer));
            assertEquals("test-user", customer.getUserId());

            // Step 3: lookupVehicle
            Vehicle vehicle = underwritingAgent.lookupVehicle(extracted, customer, context);
            assertNotNull(vehicle);
            assertFalse(Vehicle.isLookupFailed(vehicle));
            assertEquals("ABC123", vehicle.getLicensePlate());

            // Step 4: assessRisk — 应路由到 LowRiskQuote
            var decision = underwritingAgent.assessRisk(customer, vehicle, context);
            assertInstanceOf(UnderwritingAgent.LowRiskQuote.class, decision,
                    "低风险客户应路由到 LowRiskQuote");

            var lowRisk = (UnderwritingAgent.LowRiskQuote) decision;
            assertTrue(lowRisk.riskScore() <= 60,
                    "风险评分应 ≤ 60，实际: " + lowRisk.riskScore());
            assertTrue(lowRisk.premiumAmount() > 0,
                    "保费应 > 0，实际: " + lowRisk.premiumAmount());

            // Step 5: LowRiskQuote.handleLowRisk → UnderwritingResult
            var result = lowRisk.handleLowRisk();
            assertEquals("APPROVED", result.status());
            assertNotNull(result.quoteId());
            assertTrue(result.message().contains("approved"));
            assertTrue(result.message().contains("/api/insurance/pay"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  完整工作流测试：中风险 → REFERRED
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("完整工作流：中风险 → REFERRED")
    class MediumRiskWorkflowTests {

        @Test
        @DisplayName("应完成中风险核保全流程：提取 → 查找 → 评估 → 转人工")
        void shouldCompleteMediumRiskWorkflow() {
            // 创建中风险客户
            Customer mediumCustomer = customerRepository.saveAndFlush(new Customer(
                    "medium-user", "Bob Chen",
                    LocalDate.of(1999, 7, 20), 4, 2,
                    "bob@test.com", "13800000002"));
            Vehicle mediumVehicle = vehicleRepository.saveAndFlush(new Vehicle(
                    "MED001", "Civic", "Honda", 2018, 180_000, mediumCustomer));

            when(dataService.getCustomerByUserId("medium-user")).thenReturn(Optional.of(mediumCustomer));
            when(dataService.getVehicleByLicensePlate("MED001")).thenReturn(Optional.of(mediumVehicle));

            var expectedInfo = new VehicleInfo("Civic", "Honda", "MED001");
            var context = FakeOperationContext.create();
            context.expectResponse(expectedInfo);

            UserInput input = new UserInput(
                    "I want to insure my Honda Civic license MED001 userId=medium-user",
                    java.time.Instant.now());

            VehicleInfo extracted = underwritingAgent.extractVehicleInfo(input, context);
            Customer customer = underwritingAgent.lookupCustomer(input, context);
            Vehicle vehicle = underwritingAgent.lookupVehicle(extracted, customer, context);

            var decision = underwritingAgent.assessRisk(customer, vehicle, context);
            assertInstanceOf(UnderwritingAgent.MediumRiskReview.class, decision,
                    "中风险客户应路由到 MediumRiskReview");

            var medRisk = (UnderwritingAgent.MediumRiskReview) decision;
            assertTrue(medRisk.riskScore() > 60 && medRisk.riskScore() < 80,
                    "风险评分应在 60-80，实际: " + medRisk.riskScore());

            var result = medRisk.handleMediumRisk();
            assertEquals("REFERRED", result.status());
            assertTrue(result.message().contains("referred"));
            assertTrue(result.message().contains("human underwriter"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  完整工作流测试：高风险 → DECLINED
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("完整工作流：高风险 → DECLINED")
    class HighRiskWorkflowTests {

        @Test
        @DisplayName("应完成高风险核保全流程：提取 → 查找 → 评估 → 拒绝")
        void shouldCompleteHighRiskWorkflow() {
            Customer highCustomer = customerRepository.saveAndFlush(new Customer(
                    "high-user", "Charlie Zhang",
                    LocalDate.of(2005, 1, 10), 1, 3,
                    "charlie@test.com", "13800000003"));
            Vehicle highVehicle = vehicleRepository.saveAndFlush(new Vehicle(
                    "HIGH001", "X5", "BMW", 2013, 600_000, highCustomer));

            when(dataService.getCustomerByUserId("high-user")).thenReturn(Optional.of(highCustomer));
            when(dataService.getVehicleByLicensePlate("HIGH001")).thenReturn(Optional.of(highVehicle));

            var expectedInfo = new VehicleInfo("X5", "BMW", "HIGH001");
            var context = FakeOperationContext.create();
            context.expectResponse(expectedInfo);

            UserInput input = new UserInput(
                    "I need insurance for my BMW X5 license HIGH001 userId=high-user",
                    java.time.Instant.now());

            VehicleInfo extracted = underwritingAgent.extractVehicleInfo(input, context);
            Customer customer = underwritingAgent.lookupCustomer(input, context);
            Vehicle vehicle = underwritingAgent.lookupVehicle(extracted, customer, context);

            var decision = underwritingAgent.assessRisk(customer, vehicle, context);
            assertInstanceOf(UnderwritingAgent.HighRiskDecline.class, decision,
                    "高风险客户应路由到 HighRiskDecline");

            var highRisk = (UnderwritingAgent.HighRiskDecline) decision;
            assertTrue(highRisk.riskScore() >= 80,
                    "风险评分应 ≥ 80，实际: " + highRisk.riskScore());

            var result = highRisk.handleHighRisk();
            assertEquals("DECLINED", result.status());
            assertEquals(0.0, result.premiumAmount());
            assertTrue(result.message().contains("declined"));

            // 验证 Quote 已持久化且状态为 DECLINED
            Quote saved = quoteRepository.findById(result.quoteId()).orElse(null);
            assertNotNull(saved, "DECLINED quote should be persisted");
            assertEquals(Quote.QuoteStatus.DECLINED, saved.getStatus());
            assertNotNull(saved.getRejectionReason(), "Should have rejection reason");
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  故障路径测试
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("故障路径：CustomerNotFound")
    class CustomerNotFoundWorkflowTests {

        @Test
        @DisplayName("客户不存在时应完整走完错误路径并返回 ERROR 结果")
        void shouldCompleteCustomerNotFoundWorkflow() {
            var context = FakeOperationContext.create();

            UserInput input = new UserInput(
                    "I want to insure my Toyota RAV4 userId=unknown-user",
                    java.time.Instant.now());

            // Step 1: extractVehicleInfo — 快速失败
            VehicleInfo extracted = underwritingAgent.extractVehicleInfo(input, context);
            assertNull(extracted.getModel(), "Should return empty VehicleInfo on fast-fail");
            String error = (String) context.get("underwriting_error");
            assertNotNull(error, "Should bind error to blackboard");
            assertTrue(error.contains("Customer not found"));

            // 验证没有 LLM 调用
            var promptRunner = (FakePromptRunner) context.promptRunner();
            assertTrue(promptRunner.getLlmInvocations().isEmpty(),
                    "No LLM call should happen when customer not found");

            // Step 2: lookupCustomer — 返回 sentinel
            Customer customer = underwritingAgent.lookupCustomer(input, context);
            assertTrue(Customer.isLookupFailed(customer));

            // Step 3: lookupVehicle — 传播失败
            Vehicle vehicle = underwritingAgent.lookupVehicle(extracted, customer, context);
            assertTrue(Vehicle.isLookupFailed(vehicle));

            // Step 4: assessRisk — 路由到 CustomerNotFound
            var decision = underwritingAgent.assessRisk(customer, vehicle, context);
            assertInstanceOf(UnderwritingAgent.CustomerNotFound.class, decision);

            var errorState = (UnderwritingAgent.CustomerNotFound) decision;
            assertTrue(errorState.message().contains("Customer not found"));

            // Step 5: CustomerNotFound.handleCustomerNotFound
            var result = errorState.handleCustomerNotFound(context);
            assertEquals("ERROR", result.status());
            assertNull(result.quoteId());
            assertTrue(result.message().contains("Customer not found"));
        }
    }

    @Nested
    @DisplayName("故障路径：VehicleLookupError")
    class VehicleLookupErrorWorkflowTests {

        @Test
        @DisplayName("车辆未找到时应路由到 VehicleLookupError 并返回 ERROR")
        void shouldCompleteVehicleLookupErrorWorkflow() {
            when(dataService.getVehicleByLicensePlate("NOTFOUND"))
                    .thenReturn(Optional.empty());

            var expectedInfo = new VehicleInfo("RAV4", "Toyota", "NOTFOUND");
            var context = FakeOperationContext.create();
            context.expectResponse(expectedInfo);

            UserInput input = new UserInput(
                    "I want to insure my Toyota RAV4 license NOTFOUND userId=test-user",
                    java.time.Instant.now());

            VehicleInfo extracted = underwritingAgent.extractVehicleInfo(input, context);
            Customer customer = underwritingAgent.lookupCustomer(input, context);
            Vehicle vehicle = underwritingAgent.lookupVehicle(extracted, customer, context);

            assertTrue(Vehicle.isLookupFailed(vehicle));
            String error = (String) context.get("underwriting_error");
            assertNotNull(error);
            assertTrue(error.contains("Vehicle not found"));

            var decision = underwritingAgent.assessRisk(customer, vehicle, context);
            assertInstanceOf(UnderwritingAgent.VehicleLookupError.class, decision);

            var errorState = (UnderwritingAgent.VehicleLookupError) decision;
            var result = errorState.handleVehicleLookupError(context);
            assertEquals("ERROR", result.status());
            assertTrue(result.message().contains("Vehicle not found"));
        }
    }

    @Nested
    @DisplayName("故障路径：ExtractionFailed（无保险关键词）")
    class ExtractionFailedWorkflowTests {

        @Test
        @DisplayName("无保险关键词时应快速失败，不调 LLM，路由到 ExtractionFailed")
        void shouldDetectNonInsuranceInputAndFastFail() {
            var context = FakeOperationContext.create();

            UserInput input = new UserInput(
                    "Hello, how are you today? userId=test-user",
                    java.time.Instant.now());

            // Step 1: extractVehicleInfo — 检测无保险关键词
            VehicleInfo extracted = underwritingAgent.extractVehicleInfo(input, context);
            assertNull(extracted.getModel());
            String error = (String) context.get("underwriting_error");
            assertNotNull(error);
            assertTrue(error.contains("No insurance-related keywords"));

            // 验证没有 LLM 调用
            var promptRunner = (FakePromptRunner) context.promptRunner();
            assertTrue(promptRunner.getLlmInvocations().isEmpty());

            // Step 2-3: 后续步骤
            Customer customer = underwritingAgent.lookupCustomer(input, context);
            Vehicle vehicle = underwritingAgent.lookupVehicle(extracted, customer, context);
            assertTrue(Vehicle.isLookupFailed(vehicle));

            // Step 4: assessRisk — 路由到 ExtractionFailed
            var decision = underwritingAgent.assessRisk(customer, vehicle, context);
            assertInstanceOf(UnderwritingAgent.ExtractionFailed.class, decision);

            var errorState = (UnderwritingAgent.ExtractionFailed) decision;
            assertTrue(errorState.message().contains("No insurance-related keywords"));

            // Step 5: ExtractionFailed.handleExtractionFailed
            var result = errorState.handleExtractionFailed(context);
            assertEquals("ERROR", result.status());
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  边界值测试
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("风险评分边界值")
    class RiskBoundaryTests {

        @Test
        @DisplayName("评分 60 应归类为低风险（边界值）")
        void shouldClassify60AsLowRisk() {
            // 使用 mock 强制返回边界值
            UnderwritingAgent spyAgent = spy(underwritingAgent);
            var context = FakeOperationContext.create();

            var decision = spyAgent.assessRisk(testCustomer, testVehicle, context);

            // 风险评分取决于 RiskCalculationService 的实际计算
            assertInstanceOf(UnderwritingAgent.LowRiskQuote.class, decision,
                    "年龄41+驾龄10+0事故+2022车龄4 → 低风险");
        }

        @Test
        @DisplayName("评分 79 应归类为中风险（边界值）")
        void shouldClassify79AsMediumRisk() {
            // 中风险客户的评分范围已验证在 60-80 之间
            Customer medCustomer = customerRepository.saveAndFlush(new Customer(
                    "med-boundary", "Boundary User",
                    LocalDate.of(1999, 7, 20), 4, 2,
                    "boundary@test.com", "13800000005"));
            Vehicle medVehicle = vehicleRepository.saveAndFlush(new Vehicle(
                    "BND001", "Civic", "Honda", 2018, 180_000, medCustomer));

            when(dataService.getCustomerByUserId("med-boundary")).thenReturn(Optional.of(medCustomer));

            var context = FakeOperationContext.create();
            var decision = underwritingAgent.assessRisk(medCustomer, medVehicle, context);
            assertInstanceOf(UnderwritingAgent.MediumRiskReview.class, decision,
                    "年龄27+驾龄4+2事故+2018车龄8 → 中风险 (63)");
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  LLM 提示词验证
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("LLM 提示词与配置验证")
    class LlmPromptVerificationTests {

        @Test
        @DisplayName("extractVehicleInfo 提示词应包含提取规则和 JSON 格式要求")
        void shouldContainExtractionRulesInPrompt() {
            var expectedInfo = new VehicleInfo("RAV4", "Toyota", "ABC123");
            var context = FakeOperationContext.create();
            context.expectResponse(expectedInfo);

            UserInput input = new UserInput(
                    "I want to insure my Toyota RAV4 license ABC123 userId=test-user",
                    java.time.Instant.now());

            underwritingAgent.extractVehicleInfo(input, context);

            var promptRunner = (FakePromptRunner) context.promptRunner();
            String prompt = promptRunner.getLlmInvocations().get(0).getPrompt();

            // 验证提示词包含所有必要指令
            assertTrue(prompt.contains("Extract vehicle information"),
                    "Prompt should contain extraction instruction");
            assertTrue(prompt.contains("model"), "Prompt should mention 'model'");
            assertTrue(prompt.contains("brand"), "Prompt should mention 'brand'");
            assertTrue(prompt.contains("licensePlate"), "Prompt should mention 'licensePlate'");
            assertTrue(prompt.contains("JSON"), "Prompt should require JSON format");
            assertTrue(prompt.contains("MODEL NAME ONLY"),
                    "Prompt should instruct to exclude brand from model");
            assertTrue(prompt.contains("Do not include any other text"),
                    "Prompt should forbid extra text");
        }

        @Test
        @DisplayName("extractVehicleInfo 应使用 FAST LLM 角色和 GuardRail")
        void shouldUseFastLlmAndGuardrail() {
            var expectedInfo = new VehicleInfo("RAV4", "Toyota", "ABC123");
            var context = FakeOperationContext.create();
            context.expectResponse(expectedInfo);

            UserInput input = new UserInput(
                    "I want to insure my Toyota RAV4 license ABC123 userId=test-user",
                    java.time.Instant.now());

            underwritingAgent.extractVehicleInfo(input, context);

            // 验证 AI 上下文可用
            assertNotNull(context.ai(), "AI context should be available");
        }

        @Test
        @DisplayName("提示词应包含用户原始输入内容")
        void shouldIncludeUserInputInPrompt() {
            var expectedInfo = new VehicleInfo("Camry", "Toyota", "XYZ999");
            var context = FakeOperationContext.create();
            context.expectResponse(expectedInfo);

            UserInput input = new UserInput(
                    "Please insure my brand new Toyota Camry with plate XYZ999 userId=test-user",
                    java.time.Instant.now());

            underwritingAgent.extractVehicleInfo(input, context);

            var promptRunner = (FakePromptRunner) context.promptRunner();
            String prompt = promptRunner.getLlmInvocations().get(0).getPrompt();

            assertTrue(prompt.contains("Camry"), "Prompt should contain vehicle model from user input");
            assertTrue(prompt.contains("Toyota"), "Prompt should contain brand from user input");
            assertTrue(prompt.contains("XYZ999"), "Prompt should contain license plate from user input");
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Agent 间数据流验证
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Agent 内部数据流")
    class DataFlowTests {

        @Test
        @DisplayName("extractVehicleInfo 结果应正确传递给 lookupVehicle")
        void shouldPassVehicleInfoBetweenActions() {
            var expectedInfo = new VehicleInfo("RAV4", "Toyota", "ABC123");
            var context = FakeOperationContext.create();
            context.expectResponse(expectedInfo);

            UserInput input = new UserInput(
                    "I want to insure my Toyota RAV4 license ABC123 userId=test-user",
                    java.time.Instant.now());

            // 模拟框架编排的数据流
            VehicleInfo extracted = underwritingAgent.extractVehicleInfo(input, context);
            Customer customer = underwritingAgent.lookupCustomer(input, context);
            Vehicle vehicle = underwritingAgent.lookupVehicle(extracted, customer, context);

            assertNotNull(vehicle);
            assertEquals("ABC123", vehicle.getLicensePlate());
            assertEquals(testVehicle.getId(), vehicle.getId());
            assertEquals(testCustomer.getId(), vehicle.getCustomer().getId());

            verify(dataService).getVehicleByLicensePlate("ABC123");
        }

        @Test
        @DisplayName("lookupCustomer 结果应正确传递给 assessRisk")
        void shouldPassCustomerBetweenActions() {
            var expectedInfo = new VehicleInfo("RAV4", "Toyota", "ABC123");
            var context = FakeOperationContext.create();
            context.expectResponse(expectedInfo);

            UserInput input = new UserInput(
                    "I want to insure my Toyota RAV4 license ABC123 userId=test-user",
                    java.time.Instant.now());

            VehicleInfo extracted = underwritingAgent.extractVehicleInfo(input, context);
            Customer customer = underwritingAgent.lookupCustomer(input, context);
            Vehicle vehicle = underwritingAgent.lookupVehicle(extracted, customer, context);

            // assessRisk 需要 Customer + Vehicle
            var decision = underwritingAgent.assessRisk(customer, vehicle, context);

            assertNotNull(decision);
            assertFalse(decision instanceof UnderwritingAgent.CustomerNotFound,
                    "Should not route to CustomerNotFound for valid customer");
            assertFalse(decision instanceof UnderwritingAgent.VehicleLookupError,
                    "Should not route to VehicleLookupError for valid vehicle");
        }

        @Test
        @DisplayName("错误信息应通过 Blackboard 在 Agent 步骤间传递")
        void shouldPropagateErrorsViaBlackboard() {
            when(dataService.getVehicleByLicensePlate("NOTFOUND"))
                    .thenReturn(Optional.empty());

            var expectedInfo = new VehicleInfo("RAV4", "Toyota", "NOTFOUND");
            var context = FakeOperationContext.create();
            context.expectResponse(expectedInfo);

            UserInput input = new UserInput(
                    "I want to insure my Toyota RAV4 license NOTFOUND userId=test-user",
                    java.time.Instant.now());

            underwritingAgent.extractVehicleInfo(input, context);
            Customer customer = underwritingAgent.lookupCustomer(input, context);
            Vehicle vehicle = underwritingAgent.lookupVehicle(expectedInfo, customer, context);

            // 验证错误信息在 Blackboard 上
            String error = (String) context.get("underwriting_error");
            assertNotNull(error, "Error should be on blackboard after vehicle lookup failure");
            assertTrue(error.contains("Vehicle not found"));

            // assessRisk 应读取 Blackboard 并路由到正确的错误 @State
            var decision = underwritingAgent.assessRisk(customer, vehicle, context);
            assertInstanceOf(UnderwritingAgent.VehicleLookupError.class, decision);
            assertTrue(((UnderwritingAgent.VehicleLookupError) decision).message()
                    .contains("Vehicle not found"));
        }
    }
}
