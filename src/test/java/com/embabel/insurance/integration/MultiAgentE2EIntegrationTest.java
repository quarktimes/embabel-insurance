package com.embabel.insurance.integration;

import com.embabel.agent.domain.io.UserInput;
import com.embabel.agent.test.unit.FakeOperationContext;
import com.embabel.agent.test.unit.FakePromptRunner;
import com.embabel.insurance.agent.ClaimsAgent;
import com.embabel.insurance.agent.UnderwritingAgent;
import com.embabel.insurance.dto.VehicleInfo;
import com.embabel.insurance.entity.Claim;
import com.embabel.insurance.entity.Customer;
import com.embabel.insurance.entity.Policy;
import com.embabel.insurance.entity.Quote;
import com.embabel.insurance.entity.Vehicle;
import com.embabel.insurance.repository.*;
import com.embabel.insurance.service.DataService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * 多 Agent 端到端集成测试 — 验证核保→支付→理赔的完整业务链路。
 *
 * <p>模拟真实场景：用户投保成功 → 支付签发保单 → 对该保单提交理赔。
 * 覆盖 UnderwritingAgent 和 ClaimsAgent 之间的数据流转。
 *
 * <p>使用 @SpringBootTest 启动 Spring 容器，FakeOperationContext 模拟 LLM，
 * MockBean 模拟 DataService 的外部依赖。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "embabel.models.default-llm=deepseek-chat",
                "spring.profiles.active=integration-test"
        })
@Transactional
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("多 Agent 端到端集成测试")
class MultiAgentE2EIntegrationTest {

    static {
        System.setProperty("embabel.agent.shell.interactive.enabled", "false");
        System.setProperty("embabel.agent.shell.web-application-type", "servlet");
    }

    @Autowired
    private UnderwritingAgent underwritingAgent;

    @Autowired
    private ClaimsAgent claimsAgent;

    @MockitoBean
    private DataService dataService;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private VehicleRepository vehicleRepository;

    @Autowired
    private QuoteRepository quoteRepository;

    @Autowired
    private PolicyRepository policyRepository;

    @Autowired
    private ClaimRepository claimRepository;

    @BeforeEach
    void setUp() {
        claimRepository.deleteAll();
        policyRepository.deleteAll();
        quoteRepository.deleteAll();
        vehicleRepository.deleteAll();
        customerRepository.deleteAll();
    }

    // ═══════════════════════════════════════════════════════════════════
    //  场景一：低风险用户 → 核保批准 → 支付 → 小额理赔自动批准
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("场景一：低风险核保 → 小额理赔自动批准")
    @Transactional
    class Scenario1_LowRiskUnderwriting_SmallClaimApproved {

        private Long quoteId;
        private String policyNumber;

        @Test
        @DisplayName("完整链路：核保 APPROVED → 支付签发保单 → 小额理赔 AutoApproved")
        void shouldCompleteFullInsuranceLifecycle() {
            // ── Phase 1: 创建测试数据 ──
            Customer customer = customerRepository.saveAndFlush(new Customer(
                    "test-user", "Test User",
                    LocalDate.of(1990, 5, 15), 10, 0,
                    "test@test.com", "1234567890"));
            Vehicle vehicle = vehicleRepository.saveAndFlush(new Vehicle(
                    "ABC123", "RAV4", "Toyota", 2022, 300_000, customer));

            when(dataService.getCustomerByUserId("test-user")).thenReturn(Optional.of(customer));
            when(dataService.getVehicleByLicensePlate("ABC123")).thenReturn(Optional.of(vehicle));
            when(dataService.getVehiclesByCustomerAndModel(eq(customer.getId()), eq("RAV4")))
                    .thenReturn(java.util.List.of(vehicle));
            when(dataService.getVehicleByCustomerAndModel(eq(customer.getId()), eq("RAV4")))
                    .thenReturn(Optional.of(vehicle));

            // ── Phase 2: 核保（UnderwritingAgent） ──
            var uwContext = FakeOperationContext.create();
            var vehicleInfo = new VehicleInfo("RAV4", "Toyota", "ABC123");
            uwContext.expectResponse(vehicleInfo);

            UserInput uwInput = new UserInput(
                    "I want to insure my Toyota RAV4 license ABC123 userId=test-user",
                    java.time.Instant.now());

            // 执行核保全流程
            VehicleInfo extracted = underwritingAgent.extractVehicleInfo(uwInput, uwContext);
            assertNotNull(extracted);
            assertEquals("RAV4", extracted.getModel());

            // 验证 LLM 提示词
            var uwPromptRunner = (FakePromptRunner) uwContext.promptRunner();
            String uwPrompt = uwPromptRunner.getLlmInvocations().get(0).getPrompt();
            assertTrue(uwPrompt.contains("Extract vehicle information"));
            assertTrue(uwPrompt.contains("RAV4"));
            assertTrue(uwPrompt.contains("Toyota"));

            Customer foundCustomer = underwritingAgent.lookupCustomer(uwInput, uwContext);
            Vehicle foundVehicle = underwritingAgent.lookupVehicle(extracted, foundCustomer, uwContext);

            var uwDecision = underwritingAgent.assessRisk(foundCustomer, foundVehicle, uwContext);
            assertInstanceOf(UnderwritingAgent.LowRiskQuote.class, uwDecision);

            var uwResult = ((UnderwritingAgent.LowRiskQuote) uwDecision).handleLowRisk();
            assertEquals("APPROVED", uwResult.status());
            quoteId = uwResult.quoteId();
            assertNotNull(quoteId, "Should generate quote ID");

            // 验证 Quote 持久化
            Quote quote = quoteRepository.findById(quoteId).orElse(null);
            assertNotNull(quote);
            assertEquals(Quote.QuoteStatus.APPROVED, quote.getStatus());
            assertTrue(quote.getPremiumAmount() > 0);

            // ── Phase 3: 支付签发保单 ──
            // 模拟支付逻辑（与 AgentService 中的逻辑一致）
            quote.setStatus(Quote.QuoteStatus.APPROVED); // 确保状态
            Policy policy = new Policy(
                    "POL-" + java.util.UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                    quote.getCustomer(), quote.getVehicle(),
                    quote.getCoverageType(), quote.getPremiumAmount(),
                    LocalDateTime.now(), LocalDateTime.now().plusYears(1),
                    Policy.PolicyStatus.ACTIVE
            );
            policy = policyRepository.saveAndFlush(policy);
            policyNumber = policy.getPolicyNumber();

            assertNotNull(policyNumber, "Should generate policy number");
            assertTrue(policyNumber.startsWith("POL-"));
            assertEquals(Policy.PolicyStatus.ACTIVE, policy.getStatus());

            // ── Phase 4: 小额理赔（ClaimsAgent） ──
            var clContext = FakeOperationContext.create();
            var claimInfo = new ClaimsAgent.ClaimInfo(
                    "accident", "parking lot", "2024-06-01", "driver, other party");
            clContext.expectResponse(claimInfo);

            UserInput clInput = new UserInput(
                    "policy=" + policyNumber + " description=Minor scratch on bumper in parking lot amount=2000",
                    java.time.Instant.now());

            // 执行理赔全流程
            Policy verifiedPolicy = claimsAgent.verifyPolicy(clInput, clContext);
            assertNotNull(verifiedPolicy);
            assertEquals(policyNumber, verifiedPolicy.getPolicyNumber());

            ClaimsAgent.ClaimInfo extractedClaim = claimsAgent.extractClaimInfo(clInput, clContext);
            assertNotNull(extractedClaim);
            assertEquals("accident", extractedClaim.incidentType());

            // 验证 ClaimsAgent 的 LLM 提示词（在 extractClaimInfo 调用后检查）
            var clPromptRunner = (FakePromptRunner) clContext.promptRunner();
            var llmInvocations = clPromptRunner.getLlmInvocations();
            assertFalse(llmInvocations.isEmpty(), "ClaimsAgent should have LLM invocations");
            String clPrompt = llmInvocations.get(0).getPrompt();
            assertTrue(clPrompt.contains("Extract structured claim information"));
            assertTrue(clPrompt.contains("incidentType"));

            Double fraudScore = claimsAgent.calculateFraudScore(clInput, verifiedPolicy, extractedClaim, clContext);
            assertNotNull(fraudScore);
            assertTrue(fraudScore < 30, "Small normal claim should have low fraud score");

            var clDecision = claimsAgent.classify(clInput, verifiedPolicy, extractedClaim, fraudScore, clContext);
            assertInstanceOf(ClaimsAgent.AutoApproved.class, clDecision);

            var clResult = ((ClaimsAgent.AutoApproved) clDecision).handleApproved();
            assertEquals("APPROVED", clResult.claimStatus());
            assertTrue(clResult.approvedAmount() > 0);

            // ── Phase 5: 数据完整性验证 ──
            // 验证 Quote 和 Policy 关联
            assertEquals(customer.getId(), policy.getCustomer().getId());
            assertEquals(vehicle.getId(), policy.getVehicle().getId());

            // 验证 Claim 持久化
            Claim claim = claimRepository.findByClaimNumber(clResult.claimNumber()).orElse(null);
            assertNotNull(claim);
            assertEquals(Claim.ClaimStatus.APPROVED, claim.getStatus());
            assertEquals(policy.getId(), claim.getPolicy().getId());

            // 验证 Policy 状态未受理赔影响
            Policy refreshedPolicy = policyRepository.findById(policy.getId()).orElse(null);
            assertNotNull(refreshedPolicy);
            assertEquals(Policy.PolicyStatus.ACTIVE, refreshedPolicy.getStatus());
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  场景二：中风险核保 → 人工审批 → 大额理赔 → AutoDenied
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("场景二：中风险核保 → 大额理赔自动拒绝")
    @Transactional
    class Scenario2_MediumRiskUnderwriting_LargeClaimDenied {

        @Test
        @DisplayName("中风险核保 REFERRED → 人工审批 → 大额欺诈理赔 AutoDenied")
        void shouldDenyLargeFraudulentClaimAfterMediumRiskApproval() {
            // ── Phase 1: 创建中风险测试数据 ──
            Customer customer = customerRepository.saveAndFlush(new Customer(
                    "medium-user", "Bob Chen",
                    LocalDate.of(1999, 7, 20), 4, 2,
                    "bob@test.com", "13800000002"));
            Vehicle vehicle = vehicleRepository.saveAndFlush(new Vehicle(
                    "MED001", "Civic", "Honda", 2018, 180_000, customer));

            when(dataService.getCustomerByUserId("medium-user")).thenReturn(Optional.of(customer));
            when(dataService.getVehicleByLicensePlate("MED001")).thenReturn(Optional.of(vehicle));
            when(dataService.getVehiclesByCustomerAndModel(eq(customer.getId()), eq("Civic")))
                    .thenReturn(java.util.List.of(vehicle));
            when(dataService.getVehicleByCustomerAndModel(eq(customer.getId()), eq("Civic")))
                    .thenReturn(Optional.of(vehicle));

            // ── Phase 2: 核保 → REFERRED → 人工审批 ──
            var uwContext = FakeOperationContext.create();
            uwContext.expectResponse(new VehicleInfo("Civic", "Honda", "MED001"));

            UserInput uwInput = new UserInput(
                    "I want to insure my Honda Civic license MED001 userId=medium-user",
                    java.time.Instant.now());

            VehicleInfo extracted = underwritingAgent.extractVehicleInfo(uwInput, uwContext);
            Customer foundCustomer = underwritingAgent.lookupCustomer(uwInput, uwContext);
            Vehicle foundVehicle = underwritingAgent.lookupVehicle(extracted, foundCustomer, uwContext);

            var uwDecision = underwritingAgent.assessRisk(foundCustomer, foundVehicle, uwContext);
            assertInstanceOf(UnderwritingAgent.MediumRiskReview.class, uwDecision,
                    "中风险客户应路由到 REFERRED");

            var uwResult = ((UnderwritingAgent.MediumRiskReview) uwDecision).handleMediumRisk();
            assertEquals("REFERRED", uwResult.status());
            Long quoteId = uwResult.quoteId();

            // 人工审批：将 Quote 从 REFERRED 改为 APPROVED
            Quote quote = quoteRepository.findById(quoteId).orElseThrow();
            quote.setStatus(Quote.QuoteStatus.APPROVED);
            quoteRepository.saveAndFlush(quote);

            // ── Phase 3: 支付签发保单 ──
            Policy policy = policyRepository.saveAndFlush(new Policy(
                    "POL-" + java.util.UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                    customer, vehicle, "COMPREHENSIVE", quote.getPremiumAmount(),
                    LocalDateTime.now(), LocalDateTime.now().plusYears(1),
                    Policy.PolicyStatus.ACTIVE
            ));

            // ── Phase 4: 大额理赔 → AutoDenied ──
            var clContext = FakeOperationContext.create();
            clContext.expectResponse(new ClaimsAgent.ClaimInfo(
                    "theft", "unknown", "", "unknown"));

            UserInput clInput = new UserInput(
                    "policy=" + policy.getPolicyNumber()
                            + " description=Vehicle was stolen with no witnesses amount=100000",
                    java.time.Instant.now());

            Policy verifiedPolicy = claimsAgent.verifyPolicy(clInput, clContext);
            ClaimsAgent.ClaimInfo claimInfo = claimsAgent.extractClaimInfo(clInput, clContext);
            Double fraudScore = claimsAgent.calculateFraudScore(clInput, verifiedPolicy, claimInfo, clContext);

            assertNotNull(fraudScore);
            assertTrue(fraudScore >= 70.0,
                    "High fraud indicators should produce high score, got: " + fraudScore);

            var clDecision = claimsAgent.classify(clInput, verifiedPolicy, claimInfo, fraudScore, clContext);
            assertInstanceOf(ClaimsAgent.AutoDenied.class, clDecision,
                    "High fraud score should route to AutoDenied");

            var clResult = ((ClaimsAgent.AutoDenied) clDecision).handleDenied();
            assertEquals("DENIED", clResult.claimStatus());
            assertEquals(0.0, clResult.approvedAmount());

            // ── Phase 5: 验证 ──
            Claim claim = claimRepository.findByClaimNumber(clResult.claimNumber()).orElse(null);
            assertNotNull(claim);
            assertEquals(Claim.ClaimStatus.DENIED, claim.getStatus());
            assertEquals(fraudScore, claim.getFraudScore());
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  场景三：高风险核保 → DECLINED（不可支付，不可理赔）
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("场景三：高风险核保 → DECLINED → 终态")
    @Transactional
    class Scenario3_HighRiskDeclined {

        @Test
        @DisplayName("高风险核保拒绝后不应有后续保单或理赔")
        void shouldDeclineHighRiskAndPreventFurtherActions() {
            Customer customer = customerRepository.saveAndFlush(new Customer(
                    "high-user", "Charlie Zhang",
                    LocalDate.of(2005, 1, 10), 1, 3,
                    "charlie@test.com", "13800000003"));
            Vehicle vehicle = vehicleRepository.saveAndFlush(new Vehicle(
                    "HIGH001", "X5", "BMW", 2013, 600_000, customer));

            when(dataService.getCustomerByUserId("high-user")).thenReturn(Optional.of(customer));
            when(dataService.getVehicleByLicensePlate("HIGH001")).thenReturn(Optional.of(vehicle));

            var uwContext = FakeOperationContext.create();
            uwContext.expectResponse(new VehicleInfo("X5", "BMW", "HIGH001"));

            UserInput uwInput = new UserInput(
                    "I need insurance for my BMW X5 license HIGH001 userId=high-user",
                    java.time.Instant.now());

            VehicleInfo extracted = underwritingAgent.extractVehicleInfo(uwInput, uwContext);
            Customer foundCustomer = underwritingAgent.lookupCustomer(uwInput, uwContext);
            Vehicle foundVehicle = underwritingAgent.lookupVehicle(extracted, foundCustomer, uwContext);

            var uwDecision = underwritingAgent.assessRisk(foundCustomer, foundVehicle, uwContext);
            assertInstanceOf(UnderwritingAgent.HighRiskDecline.class, uwDecision,
                    "高风险客户应路由到 DECLINED");

            var uwResult = ((UnderwritingAgent.HighRiskDecline) uwDecision).handleHighRisk();
            assertEquals("DECLINED", uwResult.status());
            assertEquals(0.0, uwResult.premiumAmount());

            // 验证 Quote 已持久化且状态为 DECLINED
            Quote quote = quoteRepository.findById(uwResult.quoteId()).orElse(null);
            assertNotNull(quote);
            assertEquals(Quote.QuoteStatus.DECLINED, quote.getStatus());
            assertNotNull(quote.getRejectionReason());

            // 验证没有保单被创建
            assertEquals(0, policyRepository.count(),
                    "No policy should be created for DECLINED quote");
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  场景四：重复理赔检测
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("场景四：重复理赔检测")
    @Transactional
    class Scenario4_DuplicateClaimDetection {

        @Test
        @DisplayName("相同保单+相同描述应被检测为重复理赔")
        void shouldDetectDuplicateClaimAcrossAgentCalls() {
            // ── 创建测试数据 ──
            Customer customer = customerRepository.saveAndFlush(new Customer(
                    "test-user", "Test User",
                    LocalDate.of(1990, 5, 15), 10, 0,
                    "test@test.com", "1234567890"));
            Vehicle vehicle = vehicleRepository.saveAndFlush(new Vehicle(
                    "ABC123", "RAV4", "Toyota", 2022, 300_000, customer));

            when(dataService.getCustomerByUserId("test-user")).thenReturn(Optional.of(customer));
            when(dataService.getVehicleByLicensePlate("ABC123")).thenReturn(Optional.of(vehicle));

            // ── 核保 → 保单 ──
            var uwContext = FakeOperationContext.create();
            uwContext.expectResponse(new VehicleInfo("RAV4", "Toyota", "ABC123"));
            UserInput uwInput = new UserInput(
                    "I want to insure my Toyota RAV4 license ABC123 userId=test-user",
                    java.time.Instant.now());
            underwritingAgent.extractVehicleInfo(uwInput, uwContext);
            Customer fc = underwritingAgent.lookupCustomer(uwInput, uwContext);
            Vehicle fv = underwritingAgent.lookupVehicle(
                    new VehicleInfo("RAV4", "Toyota", "ABC123"), fc, uwContext);
            var uwDecision = underwritingAgent.assessRisk(fc, fv, uwContext);
            var uwResult = ((UnderwritingAgent.LowRiskQuote) uwDecision).handleLowRisk();

            Policy policy = policyRepository.saveAndFlush(new Policy(
                    "POL-DUP001", customer, vehicle, "COMPREHENSIVE",
                    uwResult.premiumAmount(),
                    LocalDateTime.now(), LocalDateTime.now().plusYears(1),
                    Policy.PolicyStatus.ACTIVE
            ));

            // ── 第一次理赔 → 成功 ──
            String uniqueDesc = "Duplicate test " + System.currentTimeMillis();
            var clContext1 = FakeOperationContext.create();
            clContext1.expectResponse(new ClaimsAgent.ClaimInfo(
                    "accident", "road", "2024-01-01", "driver"));

            UserInput clInput1 = new UserInput(
                    "policy=" + policy.getPolicyNumber()
                            + " description=" + uniqueDesc + " amount=3000",
                    java.time.Instant.now());

            Policy p1 = claimsAgent.verifyPolicy(clInput1, clContext1);
            ClaimsAgent.ClaimInfo ci1 = claimsAgent.extractClaimInfo(clInput1, clContext1);
            Double fs1 = claimsAgent.calculateFraudScore(clInput1, p1, ci1, clContext1);
            var dec1 = claimsAgent.classify(clInput1, p1, ci1, fs1, clContext1);

            assertInstanceOf(ClaimsAgent.AutoApproved.class, dec1,
                    "First claim should be auto-approved (low fraud score)");
            ((ClaimsAgent.AutoApproved) dec1).handleApproved();

            // ── 第二次理赔（相同描述）→ 重复检测 ──
            var clContext2 = FakeOperationContext.create();
            clContext2.expectResponse(new ClaimsAgent.ClaimInfo(
                    "accident", "road", "2024-01-01", "driver"));

            UserInput clInput2 = new UserInput(
                    "policy=" + policy.getPolicyNumber()
                            + " description=" + uniqueDesc + " amount=3000",
                    java.time.Instant.now());

            Policy p2 = claimsAgent.verifyPolicy(clInput2, clContext2);
            ClaimsAgent.ClaimInfo ci2 = claimsAgent.extractClaimInfo(clInput2, clContext2);
            Double fs2 = claimsAgent.calculateFraudScore(clInput2, p2, ci2, clContext2);
            var dec2 = claimsAgent.classify(clInput2, p2, ci2, fs2, clContext2);

            assertInstanceOf(ClaimsAgent.DuplicateClaimDetected.class, dec2,
                    "Second claim with same description should be detected as duplicate");

            var dup = (ClaimsAgent.DuplicateClaimDetected) dec2;
            assertNotNull(dup.claimNumber(), "Should reference existing claim number");
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  场景五：理赔 → PendingReview → 人工审核
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("场景五：理赔转人工审核")
    @Transactional
    class Scenario5_ClaimPendingReview {

        @Test
        @DisplayName("中欺诈评分应进入 INVESTIGATING 状态，可通过人工审核批准或拒绝")
        void shouldRouteToPendingReviewAndAllowManualDecision() {
            // ── 创建测试数据 ──
            Customer customer = customerRepository.saveAndFlush(new Customer(
                    "test-user", "Test User",
                    LocalDate.of(1990, 5, 15), 10, 0,
                    "test@test.com", "1234567890"));
            Vehicle vehicle = vehicleRepository.saveAndFlush(new Vehicle(
                    "ABC123", "RAV4", "Toyota", 2022, 300_000, customer));

            when(dataService.getCustomerByUserId("test-user")).thenReturn(Optional.of(customer));
            when(dataService.getVehicleByLicensePlate("ABC123")).thenReturn(Optional.of(vehicle));

            Policy policy = policyRepository.saveAndFlush(new Policy(
                    "POL-REV001", customer, vehicle, "COMPREHENSIVE", 6000.0,
                    LocalDateTime.now(), LocalDateTime.now().plusYears(1),
                    Policy.PolicyStatus.ACTIVE
            ));

            // ── 提交中欺诈理赔 ──
            var clContext = FakeOperationContext.create();
            clContext.expectResponse(new ClaimsAgent.ClaimInfo(
                    "accident", "road", "", "unknown"));

            UserInput clInput = new UserInput(
                    "policy=" + policy.getPolicyNumber()
                            + " description=Car damaged in accident with missing details amount=40000",
                    java.time.Instant.now());

            Policy p = claimsAgent.verifyPolicy(clInput, clContext);
            ClaimsAgent.ClaimInfo ci = claimsAgent.extractClaimInfo(clInput, clContext);
            Double fs = claimsAgent.calculateFraudScore(clInput, p, ci, clContext);
            var decision = claimsAgent.classify(clInput, p, ci, fs, clContext);

            assertInstanceOf(ClaimsAgent.PendingReview.class, decision,
                    "Medium fraud score should route to PendingReview");

            var result = ((ClaimsAgent.PendingReview) decision).handlePending();
            assertEquals("INVESTIGATING", result.claimStatus());
            assertTrue(result.message().contains("manual review"));

            // ── 验证 Claim 持久化 ──
            Claim claim = claimRepository.findByClaimNumber(result.claimNumber()).orElse(null);
            assertNotNull(claim);
            assertEquals(Claim.ClaimStatus.INVESTIGATING, claim.getStatus());

            // ── 人工审核：批准 ──
            claim.setStatus(Claim.ClaimStatus.APPROVED);
            claim.setPaidAmount(Math.min(claim.getClaimedAmount(),
                    claim.getPolicy().getPremiumAmount() * 5));
            claimRepository.saveAndFlush(claim);

            Claim approvedClaim = claimRepository.findById(claim.getId()).orElse(null);
            assertNotNull(approvedClaim);
            assertEquals(Claim.ClaimStatus.APPROVED, approvedClaim.getStatus());
            assertTrue(approvedClaim.getPaidAmount() > 0);
        }
    }
}
