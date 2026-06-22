package com.embabel.insurance.agent;

import com.embabel.agent.api.annotation.*;
import com.embabel.agent.api.common.Ai;
import com.embabel.agent.api.common.OperationContext;
import com.embabel.agent.core.AgentProcess;
import com.embabel.agent.core.Blackboard;
import com.embabel.agent.domain.io.UserInput;
import com.embabel.common.ai.model.LlmOptions;
import com.embabel.agent.api.common.StuckHandler;
import com.embabel.agent.api.common.StuckHandlerResult;
import com.embabel.agent.api.common.StuckHandlingResultCode;
import com.embabel.insurance.entity.Claim;
import com.embabel.insurance.entity.Policy;
import com.embabel.insurance.repository.ClaimRepository;
import com.embabel.insurance.repository.PolicyRepository;
import com.embabel.insurance.util.ParamExtractor;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * 保险理赔处理 Agent，使用 @State 分类模式实现智能路由。
 *
 * <p>处理流水线：
 * <ol>
 *   <li>{@code verifyPolicy} — 校验保单是否存在且处于有效状态</li>
 *   <li>{@code extractClaimInfo} — 通过 LLM 从自然语言描述中提取事故结构化信息</li>
 *   <li>{@code calculateFraudScore} — 多维度欺诈风险评估</li>
 *   <li>{@code classify} — 入口动作：根据欺诈评分将理赔分类到对应的风险或错误 @State</li>
 *   <li>各 @State 通过 {@code @AchievesGoal @Action} 生成最终的 {@code ClaimResult}</li>
 * </ol>
 *
 * <p><b>错误处理策略：</b>不直接抛出异常（会被框架的重试机制吞掉），
 * 而是将错误信息存储到框架的 {@link Blackboard} 上，再通过专用的错误 {@code @State} 路由出去，
 * 确保错误消息能可靠地到达前端。
 */
@Agent(
        description = "理赔处理 Agent，评估理赔申请并自动路由到批准、拒绝或人工审核"
)
@Component
public class ClaimsAgent implements StuckHandler {

    private static final Logger logger = LoggerFactory.getLogger(ClaimsAgent.class);

    /** Blackboard 上存储理赔错误信息的键名 */
    private static final String BLACKBOARD_KEY_ERROR = "claims_error";

    private final PolicyRepository policyRepository;
    private final ClaimRepository claimRepository;

    public ClaimsAgent(PolicyRepository policyRepository, ClaimRepository claimRepository) {
        this.policyRepository = policyRepository;
        this.claimRepository = claimRepository;
    }

    // ──────────────────────────────────────────────
    //  公共类型定义
    // ──────────────────────────────────────────────

    /** 理赔输入参数 */
    public record ClaimInput(String policyNumber, String description, double claimedAmount) {}

    /** 理赔处理结果 */
    public record ClaimResult(String claimNumber, String claimStatus, double fraudScore,
                              double approvedAmount, String message) {}

    /** 从事故描述中提取的结构化信息 */
    public record ClaimInfo(String incidentType, String location, String date, String partiesInvolved) {}

    // ──────────────────────────────────────────────
    //  阶段一：保单校验
    // ──────────────────────────────────────────────

    @Action(description = "校验保单是否存在且有效（状态为 ACTIVE 且在有效期内）")
    public Policy verifyPolicy(UserInput userInput, OperationContext context) {
        String content = userInput.getContent();
        String policyNumber = extractParam(content, "policy", context);
        if (policyNumber == null) {
            return null; // error already stored by extractParam
        }

        logger.info("Verifying policy: {}", policyNumber);

        Optional<Policy> policyOpt = policyRepository.findByPolicyNumber(policyNumber);
        if (policyOpt.isEmpty()) {
            context.bind(BLACKBOARD_KEY_ERROR, "Policy not found: " + policyNumber);
            return null;
        }

        Policy policy = policyOpt.get();
        if (policy.getStatus() != Policy.PolicyStatus.ACTIVE) {
            context.bind(BLACKBOARD_KEY_ERROR, "Policy is not active: " + policyNumber);
            return null;
        }

        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(policy.getEffectiveDate()) || now.isAfter(policy.getExpirationDate())) {
            context.bind(BLACKBOARD_KEY_ERROR, "Policy is not within valid period: " + policyNumber);
            return null;
        }

        logger.info("Policy {} is valid and active", policyNumber);
        return policy;
    }

    // ──────────────────────────────────────────────
    //  阶段二：事故信息提取（LLM 驱动）
    // ──────────────────────────────────────────────

    @Action(description = "使用 LLM 从自然语言描述中提取结构化事故信息")
    public ClaimInfo extractClaimInfo(UserInput userInput, OperationContext context) {
        String content = userInput.getContent();
        String description = extractParam(content, "description", context);
        if (description == null) {
            return null; // error already stored by extractParam
        }

        logger.info("Extracting claim info from description: {}", description);

        // Use LLM for extraction when Ai context is available
        Ai ai = context.ai();
        try {
            String prompt = """
                Extract structured claim information from the incident description below.

                Rules:
                - "incidentType" must be one of: accident, theft, damage, fire, vandalism, natural_disaster
                - "location" is where the incident occurred (e.g., parking lot, highway, residential area)
                - "date" is the incident date if mentioned, otherwise empty string
                - "partiesInvolved" describes who was involved (e.g., driver only, driver+other party, unknown)

                Incident description: %s

                Respond with ONLY a valid JSON object:
                {"incidentType": "...", "location": "...", "date": "...", "partiesInvolved": "..."}
                Do not include any other text, markdown formatting, or explanation.
                """.formatted(description);

            ClaimInfo info = ai
                .withLlm(LlmOptions.withAutoLlm().withTemperature(0.3))
                .createObjectIfPossible(prompt, ClaimInfo.class);

            if (info != null) {
                logger.info("LLM extracted claim info: type={}, location={}, parties={}",
                    info.incidentType(), info.location(), info.partiesInvolved());
                return info;
            }
        } catch (Exception e) {
            logger.warn("LLM extraction failed, falling back to keyword extraction: {}", e.getMessage());
        }

        // Fallback: keyword-based extraction
        return extractClaimInfoSimple(description);
    }

    private ClaimInfo extractClaimInfoSimple(String description) {
        String incidentType = "accident";
        String location = "unknown";
        String date = "";
        String partiesInvolved = "driver";

        if (description.toLowerCase().contains("theft") || description.toLowerCase().contains("stolen")) {
            incidentType = "theft";
        } else if (description.toLowerCase().contains("fire")) {
            incidentType = "fire";
        } else if (description.toLowerCase().contains("vandal")) {
            incidentType = "vandalism";
        } else if (description.toLowerCase().contains("flood") || description.toLowerCase().contains("storm")
                || description.toLowerCase().contains("earthquake")) {
            incidentType = "natural_disaster";
        } else if (description.toLowerCase().contains("damage") || description.toLowerCase().contains("broken")
                || description.toLowerCase().contains("cracked")) {
            incidentType = "damage";
        }

        if (description.toLowerCase().contains("parking lot") || description.toLowerCase().contains("parked")) {
            location = "parking lot";
        } else if (description.toLowerCase().contains("highway") || description.toLowerCase().contains("road")) {
            location = "road";
        } else if (description.toLowerCase().contains("home") || description.toLowerCase().contains("driveway")) {
            location = "residential area";
        }

        if (description.toLowerCase().contains("other") || description.toLowerCase().contains("hit")
                || description.toLowerCase().contains("collision") || description.toLowerCase().contains("rear-ended")) {
            partiesInvolved = "driver, other party";
        }

        return new ClaimInfo(incidentType, location, date, partiesInvolved);
    }

    // ──────────────────────────────────────────────
    //  阶段三：欺诈评分计算
    // ──────────────────────────────────────────────

    @Action(description = "基于理赔金额、保费和事故详情计算多维度欺诈风险评分")
    public Double calculateFraudScore(UserInput userInput, Policy policy, ClaimInfo claimInfo, OperationContext context) {
        // Propagate upstream failures
        if (policy == null || claimInfo == null) {
            return null;
        }

        String content = userInput.getContent();
        String amountStr = extractParam(content, "amount", context);
        if (amountStr == null) {
            return null; // error already stored
        }

        double claimedAmount;
        try {
            claimedAmount = Double.parseDouble(amountStr);
        } catch (NumberFormatException e) {
            context.bind(BLACKBOARD_KEY_ERROR, "Invalid claim amount: " + amountStr);
            return null;
        }

        logger.info("Calculating fraud score for claim amount: {}", claimedAmount);

        double score = 0.0;

        // 因子1：理赔金额与保费比值（远超年保费则高度可疑）
        if (policy.getPremiumAmount() > 0) {
            double ratio = claimedAmount / policy.getPremiumAmount();
            if (ratio > 10) {
                score += 40;
                logger.warn("High claim-to-premium ratio: {}x (claimed={} vs premium={})",
                        String.format("%.1f", ratio), claimedAmount, policy.getPremiumAmount());
            } else if (ratio > 5) {
                score += 20;
            } else if (ratio > 3) {
                score += 10;
            }
        }

        // 因子2：理赔绝对金额
        if (claimedAmount > 100000) {
            score += 30;
        } else if (claimedAmount > 50000) {
            score += 15;
        }

        // 因子3：事故信息完整度（缺少细节则可疑）
        if (claimInfo.partiesInvolved() != null && claimInfo.partiesInvolved().contains("unknown")) {
            score += 20;
        }
        if (claimInfo.date() == null || claimInfo.date().isEmpty()) {
            score += 15;
        }
        if (claimInfo.location() == null || claimInfo.location().isEmpty()
                || "unknown".equalsIgnoreCase(claimInfo.location())) {
            score += 10;
        }

        // 因子4：事故类型风险权重
        if ("theft".equals(claimInfo.incidentType())) {
            score += 10;
        }

        double finalScore = Math.min(score, 100.0);
        logger.info("Calculated fraud score: {} (factors: amount/ratio/details/type)", finalScore);
        return finalScore;
    }

    // ──────────────────────────────────────────────
    //  阶段四：分类路由 → @State 分发（入口点）
    // ──────────────────────────────────────────────

    /**
     * 密封接口，定义所有可能的理赔决策结果，包含错误状态。
     */
    @State
    public sealed interface ClaimDecision
            permits AutoApproved, AutoDenied, PendingReview,
                    PolicyError, DuplicateClaimDetected, InputError {}

    /**
     * 入口动作：先检查前置阶段是否出错，再根据欺诈评分将理赔分类到对应决策状态。
     */
    @Action(description = "检查前置阶段错误，再根据欺诈评分将理赔分类到对应的决策层级")
    public ClaimDecision classify(UserInput userInput, Policy policy, ClaimInfo claimInfo, Double fraudScore,
                                  OperationContext context) {
        // ── 检查前置阶段是否有错误 ──
        if (policy == null || claimInfo == null || fraudScore == null) {
            String errorMsg = (String) context.get(BLACKBOARD_KEY_ERROR);
            logger.info("Error detected — missing input: policy={}, claimInfo={}, fraudScore={}, msg={}",
                    policy != null, claimInfo != null, fraudScore != null, errorMsg);
            return new InputError(
                    errorMsg != null ? errorMsg : "Missing required input for claim processing");
        }

        String content = userInput.getContent();
        String amountStr = extractParam(content, "amount", context);
        double claimedAmount;
        try {
            claimedAmount = amountStr != null ? Double.parseDouble(amountStr) : 0;
        } catch (NumberFormatException e) {
            return new InputError("Invalid claim amount: " + amountStr);
        }

        // ── 提取原始描述（用于重复检测和持久化） ──
        String description = extractParam(content, "description", context);

        // ── 检查是否为重复理赔 ──
        if (description != null) {
            Optional<Claim> existingClaim = claimRepository.findByPolicyId(policy.getId()).stream()
                    .filter(c -> description.equals(c.getDescription()))
                    .findFirst();

            if (existingClaim.isPresent()) {
                Claim claim = existingClaim.get();
                String suggestion = claim.getStatus() == Claim.ClaimStatus.INVESTIGATING
                        ? " Use POST /api/insurance/claims/" + claim.getClaimNumber() + "/review to approve or deny."
                        : " No further action needed.";
                return new DuplicateClaimDetected(
                        claim.getClaimNumber(), claim.getStatus().name(), suggestion);
            }
        }

        // ── 正常分类路由 ──
        // 统一将原始 description 传入各 @State，由它们负责持久化时使用
        if (fraudScore < 30) {
            logger.info("Low fraud risk (score={}) → AutoApproved", fraudScore);
            return new AutoApproved(claimRepository, policy, fraudScore, claimedAmount,
                    claimInfo, description != null ? description : "");
        } else if (fraudScore >= 70) {
            logger.info("High fraud risk (score={}) → AutoDenied", fraudScore);
            return new AutoDenied(claimRepository, policy, fraudScore, claimedAmount,
                    claimInfo, description != null ? description : "");
        } else {
            logger.info("Medium fraud risk (score={}) → PendingReview", fraudScore);
            return new PendingReview(claimRepository, policy, fraudScore, claimedAmount,
                    claimInfo, description != null ? description : "");
        }
    }

    // ──────────────────────────────────────────────
    //  @State 实现 — 正常决策层级
    // ──────────────────────────────────────────────

    /**
     * 低风险理赔（欺诈评分 &lt; 30）：自动批准并计算赔付金额。
     */
    @State
    public record AutoApproved(
            ClaimRepository claimRepository,
            Policy policy,
            Double fraudScore,
            Double claimedAmount,
            ClaimInfo claimInfo,
            String description
    ) implements ClaimDecision {

        @AchievesGoal(
                description = "Auto-approve low-risk claim and calculate payout",
                value = 1.0,
                tags = {"claims", "approved", "auto"},
                examples = {"Minor bumper damage, clear liability, low claim amount"}
        )
        @Action(description = "Auto-approve claim with coverage-limited payout")
        public ClaimResult handleApproved() {
            String claimNumber = "CLM-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

            double coverageLimit = policy.getPremiumAmount() * 5;
            double approvedAmount = Math.min(claimedAmount, coverageLimit);

            Claim claim = new Claim(
                    claimNumber, policy, Claim.ClaimStatus.APPROVED,
                    claimedAmount, fraudScore,
                    description != null ? description : claimInfo.toString(),
                    UUID.randomUUID().toString()
            );
            claim.setPaidAmount(approvedAmount);
            claimRepository.save(claim);

            String message;
            if (approvedAmount < claimedAmount) {
                message = String.format(
                        "Claim approved with coverage cap. Requested ¥%.0f, approved ¥%.0f (limit: 5x premium). "
                                + "Payment within 3 business days.",
                        claimedAmount, approvedAmount);
            } else {
                message = "Claim approved. Payment will be processed within 3 business days.";
            }

            logger.info("Claim {} auto-approved: paidAmount=¥{}", claimNumber, approvedAmount);
            return new ClaimResult(claimNumber, "APPROVED", fraudScore, approvedAmount, message);
        }
    }

    /**
     * 高风险理赔（欺诈评分 ≥ 70）：自动拒绝并给出欺诈风险说明。
     */
    @State
    public record AutoDenied(
            ClaimRepository claimRepository,
            Policy policy,
            Double fraudScore,
            Double claimedAmount,
            ClaimInfo claimInfo,
            String description
    ) implements ClaimDecision {

        @AchievesGoal(
                description = "Auto-deny high-risk claim due to fraud suspicion",
                value = 1.0,
                tags = {"claims", "denied", "fraud"},
                examples = {"Suspiciously high claim amount with missing incident details"}
        )
        @Action(description = "Auto-deny claim with fraud explanation")
        public ClaimResult handleDenied() {
            String claimNumber = "CLM-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

            Claim claim = new Claim(
                    claimNumber, policy, Claim.ClaimStatus.DENIED,
                    claimedAmount, fraudScore,
                    description != null ? description : claimInfo.toString(),
                    UUID.randomUUID().toString()
            );
            claimRepository.save(claim);

            String message = String.format(
                    "Claim denied due to high fraud risk (score: %.0f/100). "
                            + "Please contact claims department for further assistance.",
                    fraudScore);

            logger.info("Claim {} auto-denied: fraudScore={}", claimNumber, fraudScore);
            return new ClaimResult(claimNumber, "DENIED", fraudScore, 0.0, message);
        }
    }

    /**
     * 中风险理赔（30 ≤ 欺诈评分 &lt; 70）：转人工审核，持久化到数据库。
     */
    @State
    public record PendingReview(
            ClaimRepository claimRepository,
            Policy policy,
            Double fraudScore,
            Double claimedAmount,
            ClaimInfo claimInfo,
            String description
    ) implements ClaimDecision {

        @AchievesGoal(
                description = "Route medium-risk claim to human review — persisted to DB for cross-restart durability",
                value = 1.0,
                tags = {"claims", "review", "manual"},
                examples = {"Moderate claim amount with some missing details"}
        )
        @Action(description = "Persist claim as INVESTIGATING and return pending status")
        public ClaimResult handlePending() {
            String claimNumber = "CLM-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

            Claim claim = new Claim(
                    claimNumber, policy, Claim.ClaimStatus.INVESTIGATING,
                    claimedAmount, fraudScore,
                    description != null ? description : claimInfo.toString(),
                    UUID.randomUUID().toString()
            );
            claim = claimRepository.save(claim);

            String message = String.format(
                    "Claim %s requires manual review (fraud score: %.0f/100). "
                            + "A claims adjuster will review within 24 hours. "
                            + "To approve or deny, call POST /api/insurance/claims/%s/review",
                    claim.getClaimNumber(), fraudScore, claim.getClaimNumber());

            logger.info("Claim {} pending manual review: fraudScore={}, amount=¥{}",
                    claim.getClaimNumber(), fraudScore, claimedAmount);
            return new ClaimResult(claim.getClaimNumber(), "INVESTIGATING", fraudScore, claimedAmount, message);
        }
    }

    // ──────────────────────────────────────────────
    //  @State 实现 — 错误状态
    // ──────────────────────────────────────────────

    /**
     * 错误状态：保单校验失败（未找到、非活跃状态或已过期）。
     */
    @State
    public record PolicyError(String message) implements ClaimDecision {

        @AchievesGoal(
                description = "Handle policy verification error — return descriptive error to caller",
                value = 1.0,
                tags = {"claims", "error", "policy"}
        )
        @Action(description = "Return policy-error result")
        public ClaimResult handlePolicyError(OperationContext context) {
            logger.warn("PolicyError state: {}", message);
            // 框架在 @State 转换时会 clear Blackboard，需要重新 bind 错误信息
            context.bind(BLACKBOARD_KEY_ERROR, message);
            return new ClaimResult("", "ERROR", 0.0, 0.0, message);
        }
    }

    /**
     * 错误状态：检测到重复理赔。
     */
    @State
    public record DuplicateClaimDetected(String claimNumber, String existingStatus, String suggestion)
            implements ClaimDecision {

        @AchievesGoal(
                description = "Handle duplicate claim — return existing claim info to caller",
                value = 1.0,
                tags = {"claims", "error", "duplicate"}
        )
        @Action(description = "Return duplicate-claim error result")
        public ClaimResult handleDuplicateClaim(OperationContext context) {
            String message = String.format(
                    "Claim already exists for this policy and description. "
                            + "Claim %s (status: %s). %s",
                    claimNumber, existingStatus,
                    suggestion != null ? suggestion : "");
            logger.warn("DuplicateClaimDetected state: {}", message);
            // 框架在 @State 转换时会 clear Blackboard，需要重新 bind 错误信息
            context.bind(BLACKBOARD_KEY_ERROR, message);
            return new ClaimResult(claimNumber, "ERROR", 0.0, 0.0, message);
        }
    }

    /**
     * 错误状态：输入参数缺失或无效。
     */
    @State
    public record InputError(String message) implements ClaimDecision {

        @AchievesGoal(
                description = "Handle input parameter error — return descriptive error to caller",
                value = 1.0,
                tags = {"claims", "error", "input"}
        )
        @Action(description = "Return input-error result")
        public ClaimResult handleInputError(OperationContext context) {
            logger.warn("InputError state: {}", message);
            // 框架在 @State 转换时会 clear Blackboard，需要重新 bind 错误信息
            context.bind(BLACKBOARD_KEY_ERROR, message);
            return new ClaimResult("", "ERROR", 0.0, 0.0, message);
        }
    }

    // ──────────────────────────────────────────────
    //  StuckHandler 实现 — 卡住时输出诊断日志
    // ──────────────────────────────────────────────

    @NotNull
    @Override
    public StuckHandlerResult handleStuck(AgentProcess agentProcess) {
        logger.error("============================================================");
        logger.error("=== ClaimsAgent STUCK DIAGNOSTICS ===");
        logger.error("=== Process status: {} ===", agentProcess.getStatus());

        // 检查 Blackboard 上是否有业务错误
        try {
            String blackboardError = (String) agentProcess.get(BLACKBOARD_KEY_ERROR);
            if (blackboardError != null && !blackboardError.isBlank()) {
                logger.error("=== Blackboard error: {} ===", blackboardError);
            } else {
                logger.error("=== Blackboard error: (none) ===");
            }
        } catch (Exception e) {
            logger.error("=== Failed to read Blackboard: {} ===", e.getMessage());
        }

        logger.error("=== Likely stuck in: extractClaimInfo (LLM call) or classify routing ===");
        logger.error("=== This agent needs ClaimInfo LLM extraction + FraudScore calculation before routing ===");
        logger.error("============================================================");

        return new StuckHandlerResult(
                "ClaimsAgent stuck — likely LLM timeout during extractClaimInfo or classify routing",
                this, StuckHandlingResultCode.NO_RESOLUTION, agentProcess);
    }

    // ──────────────────────────────────────────────
    //  辅助方法：从用户输入中提取命名参数（委托给 ParamExtractor 工具类）
    // ──────────────────────────────────────────────

    /**
     * 从格式化输入中提取命名参数。若参数缺失则返回 {@code null}
     * 并将错误信息存储到 Blackboard 上。
     *
     * <p>只有在 blackboard 上没有已有 error 时才写入，避免覆盖上游更有价值的错误信息。
     */
    private String extractParam(String content, String paramName, OperationContext context) {
        String value = ParamExtractor.extract(content, paramName);
        if (value == null) {
            bindErrorIfAbsent(context, "Missing required parameter: " + paramName + " in input: " + content);
        }
        return value;
    }

    /**
     * 只在 blackboard 上没有已有 error 时才写入，保留上游最有价值的错误。
     */
    private void bindErrorIfAbsent(OperationContext context, String errorMsg) {
        String existing = (String) context.get(BLACKBOARD_KEY_ERROR);
        if (existing == null || existing.isBlank()) {
            context.bind(BLACKBOARD_KEY_ERROR, errorMsg);
        }
    }
}
