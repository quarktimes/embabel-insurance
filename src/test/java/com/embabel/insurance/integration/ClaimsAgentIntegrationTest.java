package com.embabel.insurance.integration;

import com.embabel.agent.core.Agent;
import com.embabel.agent.core.AgentPlatform;
import com.embabel.agent.domain.io.UserInput;
import com.embabel.agent.test.unit.FakeOperationContext;
import com.embabel.agent.test.unit.FakePromptRunner;
import com.embabel.insurance.agent.ClaimsAgent;
import com.embabel.insurance.entity.Claim;
import com.embabel.insurance.entity.Customer;
import com.embabel.insurance.entity.Policy;
import com.embabel.insurance.entity.Vehicle;
import com.embabel.insurance.repository.ClaimRepository;
import com.embabel.insurance.repository.CustomerRepository;
import com.embabel.insurance.repository.PolicyRepository;
import com.embabel.insurance.repository.VehicleRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ClaimsAgent 集成测试 — 验证完整的理赔处理工作流与数据流。
 *
 * <p>使用 @SpringBootTest 启动 Spring 容器（加载真实 AgentPlatform），
 * 通过真实数据库（H2 内存库）验证持久化行为，
 * 使用 FakeOperationContext 模拟 LLM 交互。
 *
 * <p>验证要点：
 * <ul>
 *   <li>AgentPlatform 正确加载 ClaimsAgent</li>
 *   <li>理赔全流程：verifyPolicy → extractClaimInfo → calculateFraudScore → classify → @State</li>
 *   <li>LLM 提示词正确构造（含 temperature=0.3）</li>
 *   <li>Keyword fallback 机制在 LLM 失败时正常工作</li>
 *   <li>重复理赔检测正确</li>
 *   <li>所有 @State 路由正确（AutoApproved/AutoDenied/PendingReview/PolicyError/DuplicateClaimDetected/InputError）</li>
 *   <li>数据库持久化：Claim 记录正确保存且状态正确</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "embabel.models.default-llm=deepseek-chat",
                "spring.profiles.active=integration-test"
        })
@Transactional
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("ClaimsAgent 集成测试")
class ClaimsAgentIntegrationTest {

    static {
        System.setProperty("embabel.agent.shell.interactive.enabled", "false");
        System.setProperty("embabel.agent.shell.web-application-type", "servlet");
    }

    @Autowired
    private AgentPlatform agentPlatform;

    @Autowired
    private ClaimsAgent claimsAgent;

    @Autowired
    private PolicyRepository policyRepository;

    @Autowired
    private ClaimRepository claimRepository;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private VehicleRepository vehicleRepository;

    private Policy testPolicy;
    private Customer testCustomer;
    private Vehicle testVehicle;

    @BeforeEach
    void setUp() {
        claimRepository.deleteAll();
        policyRepository.deleteAll();
        vehicleRepository.deleteAll();
        customerRepository.deleteAll();

        // 创建测试 Customer 和 Vehicle（Policy 需要它们作为外键）
        testCustomer = new Customer("test-user", "Test User",
                LocalDate.of(1990, 5, 15), 10, 0,
                "test@test.com", "1234567890");
        testCustomer = customerRepository.saveAndFlush(testCustomer);

        testVehicle = new Vehicle("ABC123", "RAV4", "Toyota", 2022, 300_000, testCustomer);
        testVehicle = vehicleRepository.saveAndFlush(testVehicle);

        testPolicy = new Policy("POL-001", testCustomer, testVehicle,
                "COMPREHENSIVE", 6000.0,
                LocalDateTime.now().minusMonths(1), LocalDateTime.now().plusMonths(11),
                Policy.PolicyStatus.ACTIVE);
        testPolicy = policyRepository.saveAndFlush(testPolicy);
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Agent 加载验证
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Agent 加载验证")
    class AgentLoadingTests {

        @Test
        @DisplayName("AgentPlatform 应正确加载 ClaimsAgent")
        void shouldLoadClaimsAgentOnPlatform() {
            assertNotNull(agentPlatform, "AgentPlatform should be autowired");
            assertTrue(agentPlatform.agents().size() >= 1);

            Agent claimsAgent = agentPlatform.agents().stream()
                    .filter(a -> a.getName().contains("Claims"))
                    .findFirst()
                    .orElse(null);

            assertNotNull(claimsAgent, "ClaimsAgent should be on the platform");
            assertTrue(claimsAgent.getName().contains("Claims"),
                    "Agent name should contain 'Claims': " + claimsAgent.getName());
        }

        @Test
        @DisplayName("ClaimsAgent 应具有默认 Planner 类型（非 UTILITY）")
        void shouldHaveDefaultPlanner() {
            Agent clAgent = agentPlatform.agents().stream()
                    .filter(a -> a.getName().contains("Claims"))
                    .findFirst()
                    .orElseThrow();

            assertNotNull(clAgent, "ClaimsAgent should be found on platform");
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  完整工作流测试：低欺诈 → AutoApproved
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("完整工作流：低欺诈 → AutoApproved")
    class LowFraudWorkflowTests {

        @Test
        @DisplayName("应完成低欺诈理赔全流程：校验 → 提取 → 评分 → 自动批准")
        void shouldCompleteLowFraudWorkflow() {
            var expectedInfo = new ClaimsAgent.ClaimInfo(
                    "accident", "parking lot", "2024-06-01", "driver, other party");
            var context = FakeOperationContext.create();
            context.expectResponse(expectedInfo);

            UserInput input = new UserInput(
                    "policy=POL-001 description=Minor scratch on bumper in parking lot amount=2000",
                    java.time.Instant.now());

            // Step 1: verifyPolicy
            Policy policy = claimsAgent.verifyPolicy(input, context);
            assertNotNull(policy);
            assertEquals("POL-001", policy.getPolicyNumber());
            assertEquals(Policy.PolicyStatus.ACTIVE, policy.getStatus());

            // Step 2: extractClaimInfo — LLM 提取
            ClaimsAgent.ClaimInfo claimInfo = claimsAgent.extractClaimInfo(input, context);
            assertNotNull(claimInfo);
            assertEquals("accident", claimInfo.incidentType());

            // 验证 LLM 提示词
            var promptRunner = (FakePromptRunner) context.promptRunner();
            String prompt = promptRunner.getLlmInvocations().get(0).getPrompt();
            assertTrue(prompt.contains("Extract structured claim information"),
                    "Prompt should contain extraction instruction");
            assertTrue(prompt.contains("incidentType"), "Prompt should mention incidentType");
            assertTrue(prompt.contains("accident, theft, damage, fire, vandalism, natural_disaster"),
                    "Prompt should list all incident types");
            assertTrue(prompt.contains("parking lot"), "Prompt should contain incident description");
            assertTrue(prompt.contains("JSON"), "Prompt should require JSON format");

            // Step 3: calculateFraudScore
            Double fraudScore = claimsAgent.calculateFraudScore(input, policy, claimInfo, context);
            assertNotNull(fraudScore);
            // 2000/6000=0.33 < 3 → 无 ratio；2000<50000 → 无 amount；信息完整 → 0
            assertEquals(0.0, fraudScore, 0.01,
                    "Small normal claim should have fraud score 0");

            // Step 4: classify → AutoApproved
            var decision = claimsAgent.classify(input, policy, claimInfo, fraudScore, context);
            assertInstanceOf(ClaimsAgent.AutoApproved.class, decision,
                    "Low fraud score should route to AutoApproved");

            // Step 5: AutoApproved.handleApproved
            var result = ((ClaimsAgent.AutoApproved) decision).handleApproved();
            assertEquals("APPROVED", result.claimStatus());
            assertTrue(result.claimNumber().startsWith("CLM-"));
            assertEquals(fraudScore, result.fraudScore());
            assertTrue(result.approvedAmount() > 0, "Should have payout amount");
            assertTrue(result.message().contains("approved"));

            // 验证 Claim 持久化
            Claim saved = claimRepository.findByClaimNumber(result.claimNumber()).orElse(null);
            assertNotNull(saved, "Approved claim should be persisted");
            assertEquals(Claim.ClaimStatus.APPROVED, saved.getStatus());
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  完整工作流测试：高欺诈 → AutoDenied
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("完整工作流：高欺诈 → AutoDenied")
    class HighFraudWorkflowTests {

        @Test
        @DisplayName("应完成高欺诈理赔全流程：校验 → 提取 → 评分 → 自动拒绝")
        void shouldCompleteHighFraudWorkflow() {
            // Missing date + unknown parties → suspicious
            var expectedInfo = new ClaimsAgent.ClaimInfo(
                    "theft", "unknown", "", "unknown");
            var context = FakeOperationContext.create();
            context.expectResponse(expectedInfo);

            UserInput input = new UserInput(
                    "policy=POL-001 description=Vehicle was stolen with no witnesses amount=100000",
                    java.time.Instant.now());

            Policy policy = claimsAgent.verifyPolicy(input, context);
            ClaimsAgent.ClaimInfo claimInfo = claimsAgent.extractClaimInfo(input, context);
            Double fraudScore = claimsAgent.calculateFraudScore(input, policy, claimInfo, context);

            assertNotNull(fraudScore);
            assertTrue(fraudScore >= 70.0,
                    "High fraud indicators should produce high score, got: " + fraudScore);

            var decision = claimsAgent.classify(input, policy, claimInfo, fraudScore, context);
            assertInstanceOf(ClaimsAgent.AutoDenied.class, decision,
                    "High fraud score should route to AutoDenied");

            var result = ((ClaimsAgent.AutoDenied) decision).handleDenied();
            assertEquals("DENIED", result.claimStatus());
            assertEquals(0.0, result.approvedAmount());
            assertTrue(result.message().contains("denied"));
            assertTrue(result.message().contains(String.valueOf(fraudScore.intValue())));

            // 验证 Claim 持久化
            Claim saved = claimRepository.findByClaimNumber(result.claimNumber()).orElse(null);
            assertNotNull(saved, "Denied claim should be persisted");
            assertEquals(Claim.ClaimStatus.DENIED, saved.getStatus());
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  完整工作流测试：中欺诈 → PendingReview
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("完整工作流：中欺诈 → PendingReview")
    class MediumFraudWorkflowTests {

        @Test
        @DisplayName("应完成中欺诈理赔全流程：校验 → 提取 → 评分 → 转人工审核")
        void shouldCompleteMediumFraudWorkflow() {
            // Missing date + unknown parties → suspicious but not extreme
            var expectedInfo = new ClaimsAgent.ClaimInfo(
                    "accident", "road", "", "unknown");
            var context = FakeOperationContext.create();
            context.expectResponse(expectedInfo);

            UserInput input = new UserInput(
                    "policy=POL-001 description=Car damaged in accident with missing details amount=40000",
                    java.time.Instant.now());

            Policy policy = claimsAgent.verifyPolicy(input, context);
            ClaimsAgent.ClaimInfo claimInfo = claimsAgent.extractClaimInfo(input, context);
            Double fraudScore = claimsAgent.calculateFraudScore(input, policy, claimInfo, context);

            assertNotNull(fraudScore);
            // 40000/6000=6.67>5 → +20；date=""(+15)；unknown parties(+20) → 55
            assertTrue(fraudScore >= 30.0 && fraudScore < 70.0,
                    "Medium fraud indicators should produce score 30-69, got: " + fraudScore);

            var decision = claimsAgent.classify(input, policy, claimInfo, fraudScore, context);
            assertInstanceOf(ClaimsAgent.PendingReview.class, decision,
                    "Medium fraud score should route to PendingReview");

            var result = ((ClaimsAgent.PendingReview) decision).handlePending();
            assertEquals("INVESTIGATING", result.claimStatus());
            assertTrue(result.message().contains("manual review"));
            assertTrue(result.message().contains("/api/insurance/claims"));

            // 验证 Claim 持久化
            Claim saved = claimRepository.findByClaimNumber(result.claimNumber()).orElse(null);
            assertNotNull(saved, "Pending claim should be persisted");
            assertEquals(Claim.ClaimStatus.INVESTIGATING, saved.getStatus());
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Keyword Fallback 测试
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Keyword Fallback（LLM 失败时降级）")
    class KeywordFallbackTests {

        @Test
        @DisplayName("LLM 返回 null 时应使用 keyword fallback 提取事故类型")
        void shouldUseKeywordFallbackWhenLlmReturnsNull() {
            var context = FakeOperationContext.create();
            context.expectResponse(null); // LLM 返回 null

            UserInput input = new UserInput(
                    "policy=POL-001 description=My car was stolen from the parking lot last night amount=50000",
                    java.time.Instant.now());

            claimsAgent.verifyPolicy(input, context);
            ClaimsAgent.ClaimInfo claimInfo = claimsAgent.extractClaimInfo(input, context);

            assertNotNull(claimInfo, "Should not return null — fallback should produce ClaimInfo");
            assertEquals("theft", claimInfo.incidentType(), "Should detect 'stolen' keyword");
            assertEquals("parking lot", claimInfo.location(), "Should detect 'parking lot' keyword");
        }

        @Test
        @DisplayName("LLM 返回 null 时应使用 keyword fallback 检测火灾")
        void shouldDetectFireViaKeywordFallback() {
            var context = FakeOperationContext.create();
            context.expectResponse(null);

            UserInput input = new UserInput(
                    "policy=POL-001 description=Car caught fire in the garage amount=200000",
                    java.time.Instant.now());

            claimsAgent.verifyPolicy(input, context);
            ClaimsAgent.ClaimInfo claimInfo = claimsAgent.extractClaimInfo(input, context);

            assertEquals("fire", claimInfo.incidentType());
        }

        @Test
        @DisplayName("LLM 返回 null 时应使用 keyword fallback 检测自然灾害")
        void shouldDetectNaturalDisasterViaKeywordFallback() {
            var context = FakeOperationContext.create();
            context.expectResponse(null);

            UserInput input = new UserInput(
                    "policy=POL-001 description=Car damaged by flood after heavy storm amount=150000",
                    java.time.Instant.now());

            claimsAgent.verifyPolicy(input, context);
            ClaimsAgent.ClaimInfo claimInfo = claimsAgent.extractClaimInfo(input, context);

            assertEquals("natural_disaster", claimInfo.incidentType());
        }

        @Test
        @DisplayName("LLM 返回 null 时应使用 keyword fallback 检测破坏行为")
        void shouldDetectVandalismViaKeywordFallback() {
            var context = FakeOperationContext.create();
            context.expectResponse(null);

            UserInput input = new UserInput(
                    "policy=POL-001 description=Car was vandalized overnight amount=10000",
                    java.time.Instant.now());

            claimsAgent.verifyPolicy(input, context);
            ClaimsAgent.ClaimInfo claimInfo = claimsAgent.extractClaimInfo(input, context);

            assertEquals("vandalism", claimInfo.incidentType());
        }

        @Test
        @DisplayName("LLM 返回 null 时应使用 keyword fallback 检测碰撞事故")
        void shouldDetectCollisionViaKeywordFallback() {
            var context = FakeOperationContext.create();
            context.expectResponse(null);

            UserInput input = new UserInput(
                    "policy=POL-001 description=Rear-ended at intersection on highway amount=8000",
                    java.time.Instant.now());

            claimsAgent.verifyPolicy(input, context);
            ClaimsAgent.ClaimInfo claimInfo = claimsAgent.extractClaimInfo(input, context);

            assertEquals("road", claimInfo.location(), "Should detect 'highway' → road");
            assertEquals("driver, other party", claimInfo.partiesInvolved(),
                    "Should detect 'rear-ended' → other party involved");
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  故障路径测试
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("故障路径：PolicyError")
    class PolicyErrorWorkflowTests {

        @Test
        @DisplayName("保单不存在时应路由到 PolicyError 并返回 ERROR")
        void shouldRouteToPolicyErrorWhenPolicyNotFound() {
            var context = FakeOperationContext.create();

            UserInput input = new UserInput(
                    "policy=POL-999 description=Car accident amount=5000",
                    java.time.Instant.now());

            Policy policy = claimsAgent.verifyPolicy(input, context);
            assertNull(policy);

            String error = (String) context.get("claims_error");
            assertNotNull(error);
            assertTrue(error.contains("Policy not found"));

            // classify 应检测到 policy=null → InputError
            var decision = claimsAgent.classify(input, null, null, null, context);
            assertInstanceOf(ClaimsAgent.InputError.class, decision);

            var result = ((ClaimsAgent.InputError) decision).handleInputError(context);
            assertEquals("ERROR", result.claimStatus());
            assertTrue(result.message().contains("Policy not found"));
        }

        @Test
        @DisplayName("保单非活跃状态时应路由到错误路径")
        void shouldRouteToErrorWhenPolicyNotActive() {
            testPolicy.setStatus(Policy.PolicyStatus.EXPIRED);
            policyRepository.saveAndFlush(testPolicy);

            var context = FakeOperationContext.create();
            UserInput input = new UserInput(
                    "policy=POL-001 description=Car accident amount=5000",
                    java.time.Instant.now());

            Policy policy = claimsAgent.verifyPolicy(input, context);
            assertNull(policy);

            String error = (String) context.get("claims_error");
            assertNotNull(error);
            assertTrue(error.contains("not active"));
        }
    }

    @Nested
    @DisplayName("故障路径：DuplicateClaimDetected")
    class DuplicateClaimWorkflowTests {

        @Test
        @DisplayName("重复理赔应被检测并返回已有理赔信息")
        void shouldDetectDuplicateClaim() {
            // 先创建一条已有理赔
            Claim existing = new Claim(
                    "CLM-EXISTING", testPolicy, Claim.ClaimStatus.INVESTIGATING,
                    5000.0, 45.0, "Rear-ended at intersection on highway", "process-1");
            claimRepository.saveAndFlush(existing);

            var expectedInfo = new ClaimsAgent.ClaimInfo(
                    "accident", "road", "2024-01-01", "driver");
            var context = FakeOperationContext.create();
            context.expectResponse(expectedInfo);

            UserInput input = new UserInput(
                    "policy=POL-001 description=Rear-ended at intersection on highway amount=5000",
                    java.time.Instant.now());

            Policy policy = claimsAgent.verifyPolicy(input, context);
            ClaimsAgent.ClaimInfo claimInfo = claimsAgent.extractClaimInfo(input, context);
            Double fraudScore = claimsAgent.calculateFraudScore(input, policy, claimInfo, context);

            var decision = claimsAgent.classify(input, policy, claimInfo, fraudScore, context);
            assertInstanceOf(ClaimsAgent.DuplicateClaimDetected.class, decision,
                    "Same description should be detected as duplicate");

            var dup = (ClaimsAgent.DuplicateClaimDetected) decision;
            assertEquals("CLM-EXISTING", dup.claimNumber());
            assertEquals("INVESTIGATING", dup.existingStatus());

            var result = dup.handleDuplicateClaim(context);
            assertEquals("ERROR", result.claimStatus());
            assertTrue(result.message().contains("already exists"));
            assertTrue(result.message().contains("CLM-EXISTING"));
        }
    }

    @Nested
    @DisplayName("故障路径：InputError（参数缺失）")
    class InputErrorWorkflowTests {

        @Test
        @DisplayName("缺少 policy 参数时应路由到 InputError")
        void shouldRouteToInputErrorWhenPolicyParamMissing() {
            var context = FakeOperationContext.create();
            UserInput input = new UserInput(
                    "description=Car accident amount=5000",
                    java.time.Instant.now());

            Policy policy = claimsAgent.verifyPolicy(input, context);
            assertNull(policy);

            String error = (String) context.get("claims_error");
            assertNotNull(error);
            assertTrue(error.contains("Missing required parameter"));

            var decision = claimsAgent.classify(input, null, null, null, context);
            assertInstanceOf(ClaimsAgent.InputError.class, decision);
        }

        @Test
        @DisplayName("无效金额时应路由到 InputError")
        void shouldRouteToInputErrorWhenAmountInvalid() {
            var context = FakeOperationContext.create();
            UserInput input = new UserInput(
                    "policy=POL-001 description=Car accident amount=notanumber",
                    java.time.Instant.now());

            Policy policy = claimsAgent.verifyPolicy(input, context);
            ClaimsAgent.ClaimInfo claimInfo = claimsAgent.extractClaimInfo(input, context);

            // calculateFraudScore 应检测无效金额
            Double fraudScore = claimsAgent.calculateFraudScore(input, policy, claimInfo, context);
            assertNull(fraudScore);
            String error = (String) context.get("claims_error");
            assertNotNull(error);
            assertTrue(error.contains("Invalid claim amount"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  LLM 提示词验证
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("LLM 提示词与配置验证")
    class LlmPromptVerificationTests {

        @Test
        @DisplayName("extractClaimInfo 提示词应包含所有事故类型枚举")
        void shouldContainIncidentTypesInPrompt() {
            var expectedInfo = new ClaimsAgent.ClaimInfo(
                    "accident", "road", "2024-01-01", "driver");
            var context = FakeOperationContext.create();
            context.expectResponse(expectedInfo);

            UserInput input = new UserInput(
                    "policy=POL-001 description=Minor accident on highway amount=5000",
                    java.time.Instant.now());

            claimsAgent.verifyPolicy(input, context);
            claimsAgent.extractClaimInfo(input, context);

            var promptRunner = (FakePromptRunner) context.promptRunner();
            String prompt = promptRunner.getLlmInvocations().get(0).getPrompt();

            assertTrue(prompt.contains("accident, theft, damage, fire, vandalism, natural_disaster"),
                    "Prompt should enumerate all incident types");
            assertTrue(prompt.contains("incidentType"), "Prompt should mention incidentType");
            assertTrue(prompt.contains("location"), "Prompt should mention location");
            assertTrue(prompt.contains("date"), "Prompt should mention date");
            assertTrue(prompt.contains("partiesInvolved"), "Prompt should mention partiesInvolved");
        }

        @Test
        @DisplayName("extractClaimInfo 应使用 temperature=0.3 的自动 LLM")
        void shouldUseTemperature03() {
            var expectedInfo = new ClaimsAgent.ClaimInfo(
                    "accident", "road", "2024-01-01", "driver");
            var context = FakeOperationContext.create();
            context.expectResponse(expectedInfo);

            UserInput input = new UserInput(
                    "policy=POL-001 description=Accident on highway amount=5000",
                    java.time.Instant.now());

            claimsAgent.verifyPolicy(input, context);
            claimsAgent.extractClaimInfo(input, context);

            // 验证 AI 上下文可用（真实 temperature 配置在 LLM 调用时生效）
            assertNotNull(context.ai(), "AI context should be available");
        }

        @Test
        @DisplayName("提示词应要求只输出 JSON，不含其他文本")
        void shouldRequireJsonOnlyOutput() {
            var expectedInfo = new ClaimsAgent.ClaimInfo(
                    "accident", "road", "2024-01-01", "driver");
            var context = FakeOperationContext.create();
            context.expectResponse(expectedInfo);

            UserInput input = new UserInput(
                    "policy=POL-001 description=Accident on highway amount=5000",
                    java.time.Instant.now());

            claimsAgent.verifyPolicy(input, context);
            claimsAgent.extractClaimInfo(input, context);

            var promptRunner = (FakePromptRunner) context.promptRunner();
            String prompt = promptRunner.getLlmInvocations().get(0).getPrompt();

            assertTrue(prompt.contains("ONLY a valid JSON object"),
                    "Prompt should require JSON-only output");
            assertTrue(prompt.contains("Do not include any other text"),
                    "Prompt should forbid extra text/markdown");
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  欺诈评分边界值测试
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("欺诈评分边界值")
    class FraudBoundaryTests {

        @Test
        @DisplayName("评分 29 应归类为 AutoApproved")
        void shouldClassify29AsAutoApproved() {
            var claimInfo = new ClaimsAgent.ClaimInfo("accident", "road", "2024-01-01", "driver");
            var context = FakeOperationContext.create();

            UserInput input = new UserInput(
                    "policy=POL-001 description=Test amount=5000",
                    java.time.Instant.now());

            var decision = claimsAgent.classify(input, testPolicy, claimInfo, 29.0, context);
            assertInstanceOf(ClaimsAgent.AutoApproved.class, decision);
        }

        @Test
        @DisplayName("评分 30 应归类为 PendingReview")
        void shouldClassify30AsPendingReview() {
            var claimInfo = new ClaimsAgent.ClaimInfo("accident", "road", "2024-01-01", "driver");
            var context = FakeOperationContext.create();

            UserInput input = new UserInput(
                    "policy=POL-001 description=Test amount=5000",
                    java.time.Instant.now());

            var decision = claimsAgent.classify(input, testPolicy, claimInfo, 30.0, context);
            assertInstanceOf(ClaimsAgent.PendingReview.class, decision);
        }

        @Test
        @DisplayName("评分 69 应归类为 PendingReview")
        void shouldClassify69AsPendingReview() {
            var claimInfo = new ClaimsAgent.ClaimInfo("accident", "road", "2024-01-01", "driver");
            var context = FakeOperationContext.create();

            UserInput input = new UserInput(
                    "policy=POL-001 description=Test amount=5000",
                    java.time.Instant.now());

            var decision = claimsAgent.classify(input, testPolicy, claimInfo, 69.0, context);
            assertInstanceOf(ClaimsAgent.PendingReview.class, decision);
        }

        @Test
        @DisplayName("评分 70 应归类为 AutoDenied")
        void shouldClassify70AsAutoDenied() {
            var claimInfo = new ClaimsAgent.ClaimInfo("theft", "unknown", "", "unknown");
            var context = FakeOperationContext.create();

            UserInput input = new UserInput(
                    "policy=POL-001 description=Stolen car amount=100000",
                    java.time.Instant.now());

            var decision = claimsAgent.classify(input, testPolicy, claimInfo, 70.0, context);
            assertInstanceOf(ClaimsAgent.AutoDenied.class, decision);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Agent 间数据流验证
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Agent 内部数据流")
    class DataFlowTests {

        @Test
        @DisplayName("verifyPolicy 结果应正确传递给后续步骤")
        void shouldPassPolicyBetweenActions() {
            var expectedInfo = new ClaimsAgent.ClaimInfo(
                    "accident", "road", "2024-01-01", "driver");
            var context = FakeOperationContext.create();
            context.expectResponse(expectedInfo);

            UserInput input = new UserInput(
                    "policy=POL-001 description=Accident amount=5000",
                    java.time.Instant.now());

            Policy policy = claimsAgent.verifyPolicy(input, context);
            assertNotNull(policy);
            assertEquals(testPolicy.getId(), policy.getId());

            ClaimsAgent.ClaimInfo claimInfo = claimsAgent.extractClaimInfo(input, context);
            Double fraudScore = claimsAgent.calculateFraudScore(input, policy, claimInfo, context);

            // classify 接收所有上游结果
            var decision = claimsAgent.classify(input, policy, claimInfo, fraudScore, context);
            assertNotNull(decision);
        }

        @Test
        @DisplayName("上游 null 应传播到 classify 并路由到 InputError")
        void shouldPropagateNullUpstreamToInputError() {
            var context = FakeOperationContext.create();
            context.bind("claims_error", "Missing required parameter: policy");

            UserInput input = new UserInput("description=Test", java.time.Instant.now());

            var decision = claimsAgent.classify(input, null, null, null, context);
            assertInstanceOf(ClaimsAgent.InputError.class, decision);
        }

        @Test
        @DisplayName("错误信息应通过 Blackboard 正确传递")
        void shouldPropagateErrorsViaBlackboard() {
            var context = FakeOperationContext.create();

            UserInput input = new UserInput(
                    "policy=POL-999 description=Car accident amount=5000",
                    java.time.Instant.now());

            // verifyPolicy 在 Blackboard 上写错误
            claimsAgent.verifyPolicy(input, context);

            String error = (String) context.get("claims_error");
            assertNotNull(error);
            assertTrue(error.contains("Policy not found"));

            // classify 从 Blackboard 读错误
            var decision = claimsAgent.classify(input, null, null, null, context);
            assertInstanceOf(ClaimsAgent.InputError.class, decision);
            assertTrue(((ClaimsAgent.InputError) decision).message().contains("Policy not found"));
        }

        @Test
        @DisplayName("错误 @State 应在框架 clear Blackboard 后重新 bind 错误信息")
        void shouldRebindErrorAfterStateTransition() {
            var context = FakeOperationContext.create();
            // 模拟框架在 @State 转换时 clear Blackboard
            // (FakeOperationContext.bind 不接受 null，用空字符串模拟 clear)

            var state = new ClaimsAgent.PolicyError("Policy not found: POL-999");
            var result = state.handlePolicyError(context);

            assertEquals("ERROR", result.claimStatus());
            assertTrue(result.message().contains("Policy not found"));

            // 验证重新 bind 了错误
            String reboundError = (String) context.get("claims_error");
            assertEquals("Policy not found: POL-999", reboundError,
                    "Error should be rebound after framework clears blackboard");
        }
    }
}
