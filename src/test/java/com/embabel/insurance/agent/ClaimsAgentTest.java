package com.embabel.insurance.agent;

import com.embabel.agent.domain.io.UserInput;
import com.embabel.agent.test.unit.FakeOperationContext;
import com.embabel.agent.test.unit.FakePromptRunner;
import com.embabel.insurance.entity.Claim;
import com.embabel.insurance.entity.Policy;
import com.embabel.insurance.repository.ClaimRepository;
import com.embabel.insurance.repository.PolicyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 理赔 Agent 单元测试 — 使用 Embabel FakeOperationContext 模拟 LLM 交互。
 *
 * <p>测试覆盖：
 * <ol>
 *   <li>verifyPolicy — 保单校验</li>
 *   <li>extractClaimInfo — LLM 提取事故信息 + keyword fallback</li>
 *   <li>calculateFraudScore — 多维度欺诈评分</li>
 *   <li>classify — 入口动作：欺诈评分路由与重复检测</li>
 *   <li>AutoApproved / AutoDenied / PendingReview — 终态 @State</li>
 *   <li>PolicyError / DuplicateClaimDetected / InputError — 错误 @State</li>
 * </ol>
 */
class ClaimsAgentTest {

    private ClaimsAgent agent;
    private PolicyRepository policyRepository;
    private ClaimRepository claimRepository;

    private Policy testPolicy;

    @BeforeEach
    void setUp() {
        policyRepository = mock(PolicyRepository.class);
        claimRepository = mock(ClaimRepository.class);

        agent = new ClaimsAgent(policyRepository, claimRepository);

        testPolicy = new Policy();
        testPolicy.setId(1L);
        testPolicy.setPolicyNumber("POL-001");
        testPolicy.setStatus(Policy.PolicyStatus.ACTIVE);
        testPolicy.setPremiumAmount(6000.0);
        testPolicy.setEffectiveDate(LocalDateTime.now().minusMonths(1));
        testPolicy.setExpirationDate(LocalDateTime.now().plusMonths(11));
    }

    // ── verifyPolicy ────────────────────────────────────────────────

    @Nested
    @DisplayName("verifyPolicy — 保单校验")
    class VerifyPolicyTests {

        @Test
        @DisplayName("Should return policy when valid and active")
        void shouldReturnPolicyWhenValid() {
            when(policyRepository.findByPolicyNumber("POL-001")).thenReturn(Optional.of(testPolicy));

            var context = FakeOperationContext.create();
            UserInput input = new UserInput(
                    "policy=POL-001 description=Rear-ended at intersection amount=5000",
                    Instant.now());

            Policy result = agent.verifyPolicy(input, context);

            assertNotNull(result);
            assertEquals("POL-001", result.getPolicyNumber());
            verify(policyRepository).findByPolicyNumber("POL-001");
        }

        @Test
        @DisplayName("Should store error when policy not found")
        void shouldStoreErrorWhenPolicyNotFound() {
            when(policyRepository.findByPolicyNumber("POL-999")).thenReturn(Optional.empty());

            var context = FakeOperationContext.create();
            UserInput input = new UserInput(
                    "policy=POL-999 description=Car accident amount=5000",
                    Instant.now());

            Policy result = agent.verifyPolicy(input, context);

            assertNull(result);
            String error = (String) context.get("claims_error");
            assertNotNull(error);
            assertTrue(error.contains("Policy not found"));
        }

        @Test
        @DisplayName("Should store error when policy is not active")
        void shouldStoreErrorWhenPolicyNotActive() {
            testPolicy.setStatus(Policy.PolicyStatus.EXPIRED);
            when(policyRepository.findByPolicyNumber("POL-001")).thenReturn(Optional.of(testPolicy));

            var context = FakeOperationContext.create();
            UserInput input = new UserInput(
                    "policy=POL-001 description=Car accident amount=5000",
                    Instant.now());

            Policy result = agent.verifyPolicy(input, context);

            assertNull(result);
            String error = (String) context.get("claims_error");
            assertNotNull(error);
            assertTrue(error.contains("not active"));
        }

        @Test
        @DisplayName("Should store error when policy is expired")
        void shouldStoreErrorWhenPolicyExpired() {
            testPolicy.setExpirationDate(LocalDateTime.now().minusDays(1));
            when(policyRepository.findByPolicyNumber("POL-001")).thenReturn(Optional.of(testPolicy));

            var context = FakeOperationContext.create();
            UserInput input = new UserInput(
                    "policy=POL-001 description=Car accident amount=5000",
                    Instant.now());

            Policy result = agent.verifyPolicy(input, context);

            assertNull(result);
            String error = (String) context.get("claims_error");
            assertNotNull(error);
            assertTrue(error.contains("not within valid period"));
        }

        @Test
        @DisplayName("Should return null when policy param is missing")
        void shouldReturnNullWhenPolicyParamMissing() {
            var context = FakeOperationContext.create();
            UserInput input = new UserInput(
                    "description=Car accident amount=5000",
                    Instant.now());

            Policy result = agent.verifyPolicy(input, context);

            assertNull(result);
            String error = (String) context.get("claims_error");
            assertNotNull(error);
            assertTrue(error.contains("Missing required parameter"));
        }
    }

    // ── extractClaimInfo — LLM 提取 + keyword fallback ──────────────

    @Nested
    @DisplayName("extractClaimInfo — LLM 提取事故信息")
    class ExtractClaimInfoTests {

        @Test
        @DisplayName("Should extract claim info via LLM")
        void shouldExtractClaimInfoViaLlm() {
            var context = FakeOperationContext.create();
            var expectedInfo = new ClaimsAgent.ClaimInfo(
                    "accident", "parking lot", "2024-06-01", "driver, other party");
            context.expectResponse(expectedInfo);

            UserInput input = new UserInput(
                    "policy=POL-001 description=My car was rear-ended in a parking lot yesterday amount=8000",
                    Instant.now());

            ClaimsAgent.ClaimInfo result = agent.extractClaimInfo(input, context);

            assertNotNull(result);
            assertEquals("accident", result.incidentType());
            assertEquals("parking lot", result.location());

            // 验证 LLM 提示内容
            var promptRunner = (FakePromptRunner) context.promptRunner();
            String prompt = promptRunner.getLlmInvocations().get(0).getPrompt();
            assertTrue(prompt.contains("rear-ended"), "Prompt should contain incident description");
            assertTrue(prompt.contains("incidentType"), "Prompt should mention incidentType");
        }

        @Test
        @DisplayName("Should use keyword fallback when LLM extraction fails")
        void shouldUseKeywordFallbackWhenLlmFails() {
            var context = FakeOperationContext.create();
            // 不 expectResponse — LLM 返回 null → fallback
            context.expectResponse(null);

            UserInput input = new UserInput(
                    "policy=POL-001 description=My car was stolen from the parking lot last night amount=50000",
                    Instant.now());

            ClaimsAgent.ClaimInfo result = agent.extractClaimInfo(input, context);

            assertNotNull(result);
            assertEquals("theft", result.incidentType());
            assertEquals("parking lot", result.location());
        }

        @Test
        @DisplayName("Should detect fire incident via keyword fallback")
        void shouldDetectFireViaKeyword() {
            var context = FakeOperationContext.create();
            context.expectResponse(null);

            UserInput input = new UserInput(
                    "policy=POL-001 description=Car caught fire in the garage amount=200000",
                    Instant.now());

            ClaimsAgent.ClaimInfo result = agent.extractClaimInfo(input, context);

            assertEquals("fire", result.incidentType());
        }

        @Test
        @DisplayName("Should detect natural disaster via keyword fallback")
        void shouldDetectNaturalDisasterViaKeyword() {
            var context = FakeOperationContext.create();
            context.expectResponse(null);

            UserInput input = new UserInput(
                    "policy=POL-001 description=Car damaged by flood after storm amount=150000",
                    Instant.now());

            ClaimsAgent.ClaimInfo result = agent.extractClaimInfo(input, context);

            assertEquals("natural_disaster", result.incidentType());
        }

        @Test
        @DisplayName("Should return null when description param missing")
        void shouldReturnNullWhenDescriptionMissing() {
            var context = FakeOperationContext.create();
            UserInput input = new UserInput(
                    "policy=POL-001 amount=5000",
                    Instant.now());

            ClaimsAgent.ClaimInfo result = agent.extractClaimInfo(input, context);

            assertNull(result);
        }
    }

    // ── calculateFraudScore ─────────────────────────────────────────

    @Nested
    @DisplayName("calculateFraudScore — 欺诈评分计算")
    class CalculateFraudScoreTests {

        @Test
        @DisplayName("Should calculate low fraud score for small normal claim")
        void shouldCalculateLowFraudScore() {
            var context = FakeOperationContext.create();
            var claimInfo = new ClaimsAgent.ClaimInfo(
                    "accident", "highway", "2024-06-01", "driver, other party");
            UserInput input = new UserInput(
                    "policy=POL-001 description=Accident amount=5000",
                    Instant.now());

            Double score = agent.calculateFraudScore(input, testPolicy, claimInfo, context);

            assertNotNull(score);
            // 5000/6000 < 3 → 无 ratio 加分
            // 5000 < 50000 → 无 amount 加分
            // 信息完整 → 无 info 加分
            // accident → 无 type 加分
            assertEquals(0.0, score, 0.01);
        }

        @Test
        @DisplayName("Should calculate high fraud score for suspicious claim")
        void shouldCalculateHighFraudScore() {
            var context = FakeOperationContext.create();
            // Missing date + unknown parties → suspicious
            var claimInfo = new ClaimsAgent.ClaimInfo(
                    "theft", "unknown", "", "unknown");
            UserInput input = new UserInput(
                    "policy=POL-001 description=Stolen amount=100000",
                    Instant.now());

            Double score = agent.calculateFraudScore(input, testPolicy, claimInfo, context);

            assertNotNull(score);
            // 100000/6000 ≈ 16.7 > 10 → +40 (ratio)
            // 100000 > 100000? no, exactly equals → not triggered. Wait: > 100000 → +30
            // parties unknown → +20
            // date empty → +15
            // location unknown → +10
            // theft → +10
            // Total: 40 + 20 + 15 + 10 + 10 = 95, cap at 100
            assertTrue(score >= 70.0, "High fraud indicators should produce high score, got: " + score);
        }

        @Test
        @DisplayName("Should propagate null when upstream policy is null")
        void shouldPropagateNullForNullPolicy() {
            var context = FakeOperationContext.create();
            UserInput input = new UserInput(
                    "policy=POL-001 description=Test amount=5000",
                    Instant.now());

            Double score = agent.calculateFraudScore(input, null,
                    new ClaimsAgent.ClaimInfo("accident", "road", "2024-01-01", "driver"),
                    context);

            assertNull(score);
        }

        @Test
        @DisplayName("Should handle invalid amount gracefully")
        void shouldHandleInvalidAmount() {
            var context = FakeOperationContext.create();
            var claimInfo = new ClaimsAgent.ClaimInfo(
                    "accident", "road", "2024-01-01", "driver");
            UserInput input = new UserInput(
                    "policy=POL-001 description=Test amount=notanumber",
                    Instant.now());

            Double score = agent.calculateFraudScore(input, testPolicy, claimInfo, context);

            assertNull(score);
            String error = (String) context.get("claims_error");
            assertNotNull(error);
            assertTrue(error.contains("Invalid claim amount"));
        }

        @Test
        @DisplayName("Should add ratio-based fraud score")
        void shouldAddRatioBasedFraudScore() {
            var context = FakeOperationContext.create();
            var claimInfo = new ClaimsAgent.ClaimInfo(
                    "accident", "highway", "2024-06-01", "driver, other party");
            // ratio = 40000/6000 = 6.67 > 5 → +20
            UserInput input = new UserInput(
                    "policy=POL-001 description=Accident amount=40000",
                    Instant.now());

            Double score = agent.calculateFraudScore(input, testPolicy, claimInfo, context);

            assertNotNull(score);
            assertEquals(20.0, score, 0.01);
        }
    }

    // ── classify — 入口动作：欺诈路由 ───────────────────────────────

    @Nested
    @DisplayName("classify — 欺诈评分路由与重复检测")
    class ClassifyTests {

        @Test
        @DisplayName("Should classify low fraud → AutoApproved")
        void shouldClassifyLowFraudAsAutoApproved() {
            when(claimRepository.findByPolicyId(any())).thenReturn(Collections.emptyList());
            when(claimRepository.save(any(Claim.class))).thenAnswer(inv -> {
                Claim c = inv.getArgument(0);
                c.setId(1L);
                return c;
            });

            var context = FakeOperationContext.create();
            var claimInfo = new ClaimsAgent.ClaimInfo("accident", "road", "2024-01-01", "driver");
            UserInput input = new UserInput(
                    "policy=POL-001 description=Minor accident amount=5000",
                    Instant.now());

            var decision = agent.classify(input, testPolicy, claimInfo, 15.0, context);

            assertInstanceOf(ClaimsAgent.AutoApproved.class, decision);
        }

        @Test
        @DisplayName("Should classify high fraud → AutoDenied")
        void shouldClassifyHighFraudAsAutoDenied() {
            when(claimRepository.findByPolicyId(any())).thenReturn(Collections.emptyList());
            when(claimRepository.save(any(Claim.class))).thenAnswer(inv -> {
                Claim c = inv.getArgument(0);
                c.setId(1L);
                return c;
            });

            var context = FakeOperationContext.create();
            var claimInfo = new ClaimsAgent.ClaimInfo("theft", "unknown", "", "unknown");
            UserInput input = new UserInput(
                    "policy=POL-001 description=Stolen car amount=100000",
                    Instant.now());

            var decision = agent.classify(input, testPolicy, claimInfo, 85.0, context);

            assertInstanceOf(ClaimsAgent.AutoDenied.class, decision);
        }

        @Test
        @DisplayName("Should classify medium fraud → PendingReview")
        void shouldClassifyMediumFraudAsPendingReview() {
            when(claimRepository.findByPolicyId(any())).thenReturn(Collections.emptyList());
            when(claimRepository.save(any(Claim.class))).thenAnswer(inv -> {
                Claim c = inv.getArgument(0);
                c.setId(1L);
                return c;
            });

            var context = FakeOperationContext.create();
            var claimInfo = new ClaimsAgent.ClaimInfo("accident", "road", "", "driver");
            UserInput input = new UserInput(
                    "policy=POL-001 description=Accident with missing details amount=30000",
                    Instant.now());

            var decision = agent.classify(input, testPolicy, claimInfo, 50.0, context);

            assertInstanceOf(ClaimsAgent.PendingReview.class, decision);
        }

        @Test
        @DisplayName("Should classify boundary 29 as AutoApproved")
        void shouldClassifyBoundary29AsAutoApproved() {
            when(claimRepository.findByPolicyId(any())).thenReturn(Collections.emptyList());
            when(claimRepository.save(any(Claim.class))).thenAnswer(inv -> {
                Claim c = inv.getArgument(0);
                c.setId(1L);
                return c;
            });

            var context = FakeOperationContext.create();
            var claimInfo = new ClaimsAgent.ClaimInfo("accident", "road", "2024-01-01", "driver");
            UserInput input = new UserInput(
                    "policy=POL-001 description=Test amount=5000",
                    Instant.now());

            var decision = agent.classify(input, testPolicy, claimInfo, 29.0, context);

            assertInstanceOf(ClaimsAgent.AutoApproved.class, decision);
        }

        @Test
        @DisplayName("Should classify boundary 70 as AutoDenied")
        void shouldClassifyBoundary70AsAutoDenied() {
            when(claimRepository.findByPolicyId(any())).thenReturn(Collections.emptyList());
            when(claimRepository.save(any(Claim.class))).thenAnswer(inv -> {
                Claim c = inv.getArgument(0);
                c.setId(1L);
                return c;
            });

            var context = FakeOperationContext.create();
            var claimInfo = new ClaimsAgent.ClaimInfo("theft", "unknown", "", "unknown");
            UserInput input = new UserInput(
                    "policy=POL-001 description=Test amount=100000",
                    Instant.now());

            var decision = agent.classify(input, testPolicy, claimInfo, 70.0, context);

            assertInstanceOf(ClaimsAgent.AutoDenied.class, decision);
        }

        @Test
        @DisplayName("Should route to InputError when upstream is null")
        void shouldRouteToInputErrorWhenUpstreamNull() {
            var context = FakeOperationContext.create();
            context.bind("claims_error", "Missing required parameter");
            UserInput input = new UserInput("description=Test", Instant.now());

            var decision = agent.classify(input, null, null, null, context);

            assertInstanceOf(ClaimsAgent.InputError.class, decision);
        }

        @Test
        @DisplayName("Should detect duplicate claim")
        void shouldDetectDuplicateClaim() {
            Claim existing = new Claim();
            existing.setId(1L);
            existing.setClaimNumber("CLM-EXISTING");
            existing.setStatus(Claim.ClaimStatus.INVESTIGATING);
            existing.setDescription("Rear-ended at intersection");
            when(claimRepository.findByPolicyId(1L))
                    .thenReturn(java.util.List.of(existing));

            var context = FakeOperationContext.create();
            var claimInfo = new ClaimsAgent.ClaimInfo("accident", "road", "2024-01-01", "driver");
            UserInput input = new UserInput(
                    "policy=POL-001 description=Rear-ended at intersection amount=5000",
                    Instant.now());

            var decision = agent.classify(input, testPolicy, claimInfo, 15.0, context);

            assertInstanceOf(ClaimsAgent.DuplicateClaimDetected.class, decision);
            var dup = (ClaimsAgent.DuplicateClaimDetected) decision;
            assertEquals("CLM-EXISTING", dup.claimNumber());
            assertEquals("INVESTIGATING", dup.existingStatus());
        }
    }

    // ── AutoApproved @State ─────────────────────────────────────────

    @Nested
    @DisplayName("AutoApproved — 自动批准")
    class AutoApprovedTests {

        @Test
        @DisplayName("Should approve claim and calculate payout within coverage cap")
        void shouldApproveClaimWithCoverageCap() {
            when(claimRepository.save(any(Claim.class))).thenAnswer(inv -> {
                Claim c = inv.getArgument(0);
                c.setId(1L);
                return c;
            });

            var claimInfo = new ClaimsAgent.ClaimInfo("accident", "road", "2024-01-01", "driver");
            var state = new ClaimsAgent.AutoApproved(
                    claimRepository, testPolicy, 10.0, 50000.0, claimInfo,
                    "Rear-ended at intersection");

            var result = state.handleApproved();

            assertNotNull(result.claimNumber());
            assertTrue(result.claimNumber().startsWith("CLM-"));
            assertEquals("APPROVED", result.claimStatus());
            assertEquals(10.0, result.fraudScore());
            // coverage limit = 6000 * 5 = 30000 → cap at 30000
            assertEquals(30000.0, result.approvedAmount(), 0.01);
            assertTrue(result.message().contains("coverage cap"));
            verify(claimRepository).save(any(Claim.class));
        }

        @Test
        @DisplayName("Should approve full amount within coverage limit")
        void shouldApproveFullAmountWithinLimit() {
            when(claimRepository.save(any(Claim.class))).thenAnswer(inv -> {
                Claim c = inv.getArgument(0);
                c.setId(1L);
                return c;
            });

            var claimInfo = new ClaimsAgent.ClaimInfo("accident", "road", "2024-01-01", "driver");
            var state = new ClaimsAgent.AutoApproved(
                    claimRepository, testPolicy, 5.0, 5000.0, claimInfo,
                    "Minor bumper damage");

            var result = state.handleApproved();

            assertEquals(5000.0, result.approvedAmount(), 0.01);
            assertTrue(result.message().contains("approved"));
        }
    }

    // ── AutoDenied @State ───────────────────────────────────────────

    @Nested
    @DisplayName("AutoDenied — 自动拒绝")
    class AutoDeniedTests {

        @Test
        @DisplayName("Should deny claim with fraud explanation")
        void shouldDenyClaimWithFraudExplanation() {
            when(claimRepository.save(any(Claim.class))).thenAnswer(inv -> {
                Claim c = inv.getArgument(0);
                c.setId(1L);
                return c;
            });

            var claimInfo = new ClaimsAgent.ClaimInfo("theft", "unknown", "", "unknown");
            var state = new ClaimsAgent.AutoDenied(
                    claimRepository, testPolicy, 85.0, 100000.0, claimInfo,
                    "Stolen vehicle");

            var result = state.handleDenied();

            assertEquals("DENIED", result.claimStatus());
            assertEquals(85.0, result.fraudScore());
            assertEquals(0.0, result.approvedAmount());
            assertTrue(result.message().contains("denied"));
            assertTrue(result.message().contains("85"));
            verify(claimRepository).save(any(Claim.class));
        }
    }

    // ── PendingReview @State ────────────────────────────────────────

    @Nested
    @DisplayName("PendingReview — 转人工审核")
    class PendingReviewTests {

        @Test
        @DisplayName("Should persist investigating claim and return pending status")
        void shouldPersistInvestigatingClaim() {
            when(claimRepository.save(any(Claim.class))).thenAnswer(inv -> {
                Claim c = inv.getArgument(0);
                c.setId(1L);
                c.setClaimNumber("CLM-PENDING");
                return c;
            });

            var claimInfo = new ClaimsAgent.ClaimInfo("accident", "road", "", "driver");
            var state = new ClaimsAgent.PendingReview(
                    claimRepository, testPolicy, 50.0, 30000.0, claimInfo,
                    "Accident with missing details");

            var result = state.handlePending();

            assertEquals("INVESTIGATING", result.claimStatus());
            assertEquals(50.0, result.fraudScore());
            assertTrue(result.message().contains("manual review"));
            assertTrue(result.message().contains("/api/insurance/claims"));
            verify(claimRepository).save(any(Claim.class));
        }
    }

    // ── 错误 @State ─────────────────────────────────────────────────

    @Nested
    @DisplayName("Error @State — 错误路由")
    class ErrorStateTests {

        @Test
        @DisplayName("PolicyError should bind error and return error result")
        void policyErrorShouldBindError() {
            var context = FakeOperationContext.create();
            var state = new ClaimsAgent.PolicyError("Policy not found: POL-999");

            var result = state.handlePolicyError(context);

            assertEquals("ERROR", result.claimStatus());
            assertTrue(result.message().contains("Policy not found"));
            String error = (String) context.get("claims_error");
            assertEquals("Policy not found: POL-999", error);
        }

        @Test
        @DisplayName("DuplicateClaimDetected should return existing claim info")
        void duplicateClaimDetectedShouldReturnInfo() {
            var context = FakeOperationContext.create();
            var state = new ClaimsAgent.DuplicateClaimDetected(
                    "CLM-123", "INVESTIGATING", "Use POST /api/insurance/claims/CLM-123/review");

            var result = state.handleDuplicateClaim(context);

            assertEquals("ERROR", result.claimStatus());
            assertEquals("CLM-123", result.claimNumber());
            assertTrue(result.message().contains("already exists"));
            assertTrue(result.message().contains("CLM-123"));
        }

        @Test
        @DisplayName("InputError should bind error and return error result")
        void inputErrorShouldBindError() {
            var context = FakeOperationContext.create();
            var state = new ClaimsAgent.InputError("Missing required parameter: policy");

            var result = state.handleInputError(context);

            assertEquals("ERROR", result.claimStatus());
            assertTrue(result.message().contains("Missing required parameter"));
            String error = (String) context.get("claims_error");
            assertEquals("Missing required parameter: policy", error);
        }
    }
}
