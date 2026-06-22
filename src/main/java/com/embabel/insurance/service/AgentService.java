package com.embabel.insurance.service;

import com.embabel.agent.core.*;
import com.embabel.agent.domain.io.UserInput;
import com.embabel.insurance.agent.ClaimsAgent;
import com.embabel.insurance.agent.UnderwritingAgent;
import com.embabel.agent.api.common.StuckHandler;
import com.embabel.agent.api.common.StuckHandlerResult;
import com.embabel.insurance.dto.response.ApproveQuoteResponse;
import com.embabel.insurance.dto.response.ReviewClaimResponse;
import com.embabel.insurance.dto.response.UnderwritingResponse;
import com.embabel.insurance.entity.Claim;
import com.embabel.insurance.entity.Quote;
import com.embabel.insurance.repository.ClaimRepository;
import com.embabel.insurance.repository.QuoteRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * Agent 编排服务，负责通过 embabel Agent 框架运行核保、理赔等 Agent。
 *
 * <p>主要职责：
 * <ul>
 *   <li>输入安全检查（未授权指令检测）</li>
 *   <li>Agent 进程的创建、运行和结果提取</li>
 *   <li>非 COMPLETED 状态时从 Blackboard 提取业务错误信息</li>
 *   <li>人工审批报价单和理赔审核的业务逻辑</li>
 * </ul>
 */
@Service
public class AgentService {

    private static final Logger logger = LoggerFactory.getLogger(AgentService.class);

    /** Agent 流程超时时间（秒），超过此时间视为卡住 */
    private static final long PROCESS_TIMEOUT_SECONDS = 120;

    /** 未授权指令检测正则 */
    private static final Pattern UNAUTHORIZED_COMMAND_PATTERN = Pattern.compile(
            "(?i)(ignore\\s+all\\s+rules|auto\\s+approve|bypass\\s+review|override\\s+system|skip\\s+verification)"
    );

    private final AgentPlatform agentPlatform;
    private final UnderwritingAgent underwritingAgentBean;
    private final ClaimsAgent claimsAgentBean;
    private final QuoteRepository quoteRepository;
    private final ClaimRepository claimRepository;


    public AgentService(AgentPlatform agentPlatform,
                        UnderwritingAgent underwritingAgentBean,
                        ClaimsAgent claimsAgentBean,
                        QuoteRepository quoteRepository,
                        ClaimRepository claimRepository) {
        this.agentPlatform = agentPlatform;
        this.underwritingAgentBean = underwritingAgentBean;
        this.claimsAgentBean = claimsAgentBean;
        this.quoteRepository = quoteRepository;
        this.claimRepository = claimRepository;
    }

    /**
     * 检查输入是否包含未授权指令。
     */
    public boolean containsUnauthorizedCommand(String input) {
        return UNAUTHORIZED_COMMAND_PATTERN.matcher(input).find();
    }

    /**
     * 检测 Agent 是否卡住，若卡住则调用 StuckHandler 打印详细错误日志。
     *
     * @param agentBean Agent 的 Spring Bean 实例（用于 StuckHandler 回调）
     * @param process   当前 AgentProcess（可能处于中间状态）
     * @param agentName 便于日志识别的名称，如 "Underwriting" / "Claims" / "Chatbot"
     */
    private void handleAgentStuck(Object agentBean, AgentProcess process, String agentName) {
        logger.error("============================================================");
        logger.error("=== {}Agent PROCESS STUCK — timed out after {} seconds ===",
                agentName, PROCESS_TIMEOUT_SECONDS);
        logger.error("=== Process status: {} ===", process.getStatus());

        // 尝试读取 Blackboard 上任何已写入的错误/状态信息
        try {
            String underwritingError = (String) process.get("underwriting_error");
            if (underwritingError != null) {
                logger.error("=== Blackboard[underwriting_error]: {} ===", underwritingError);
            }
            String claimsError = (String) process.get("claims_error");
            if (claimsError != null) {
                logger.error("=== Blackboard[claims_error]: {} ===", claimsError);
            }
        } catch (Exception e) {
            logger.error("=== Failed to read Blackboard: {} ===", e.getMessage());
        }

        // 如果 Agent Bean 实现了 StuckHandler，回调让其输出自定义诊断信息
        if (agentBean instanceof StuckHandler stuckHandler) {
            try {
                StuckHandlerResult result = stuckHandler.handleStuck(process);
                logger.error("=== StuckHandler result: code={}, message={} ===",
                        result.getCode(), result.getMessage());
            } catch (Exception e) {
                logger.error("=== StuckHandler itself threw exception ===", e);
            }
        } else {
            logger.error("=== Agent does NOT implement StuckHandler — no additional diagnostics ===");
        }

        logger.error("============================================================");
    }

    /**
     * 带超时保护地运行 AgentProcess，超时则触发 StuckHandler 并抛出异常。
     */
    private AgentProcess runWithTimeout(Agent agent, Object agentBean, AgentProcess process, String agentName) {
        try {
            return CompletableFuture
                    .supplyAsync(process::run)
                    .get(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            handleAgentStuck(agentBean, process, agentName);
            throw new RuntimeException(
                    "Agent process '" + agentName + "' timed out after " + PROCESS_TIMEOUT_SECONDS + "s", e);
        } catch (Exception e) {
            logger.error("Agent process '{}' failed with exception", agentName, e);
            throw new RuntimeException("Agent process '" + agentName + "' failed: " + e.getMessage(), e);
        }
    }

    /**
     * 运行核保 Agent，处理投保申请并返回核保结果。
     */
    public UnderwritingResponse processUnderwriting(String userId, String userInput) {
        if (containsUnauthorizedCommand(userInput)) {
            logger.warn("Unauthorized command detected in input: {}", userInput);
            throw new IllegalArgumentException("Input contains unauthorized commands");
        }

        logger.info("Processing underwriting request for user: {}, input: {}", userId, userInput);

        try {
            String enrichedInput = userInput + " userId=" + userId;
            UserInput input = new UserInput(enrichedInput);

            // 查找 UnderwritingAgent
            Agent underwritingAgent = agentPlatform.agents().stream()
                .filter(a -> a.getName().contains("Underwriting"))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("UnderwritingAgent not found"));

            logger.info("Found agent: {}", underwritingAgent.getName());
            var processOptions = new ProcessOptions()
                .withVerbosity(new Verbosity()
                    .withShowPrompts(true)
                    .withShowLlmResponses(true)
                    .withDebug(true));

            // 创建并运行 Agent 进程
            AgentProcess process = agentPlatform.createAgentProcessFrom(
                underwritingAgent,
                processOptions,
                input
            );

            // 运行进程（带超时保护，超时则触发 StuckHandler）
            AgentProcess completedProcess = runWithTimeout(underwritingAgent, underwritingAgentBean, process, "Underwriting");

            // 先尝试获取正常路径的结果
            UnderwritingAgent.UnderwritingResult result = completedProcess.last(UnderwritingAgent.UnderwritingResult.class);

            if (result != null) {
                logger.info("Underwriting completed: quoteId={}, status={}, riskScore={}",
                        result.quoteId(), result.status(), result.riskScore());

                return new UnderwritingResponse(
                        result.quoteId(),
                        result.status(),
                        result.riskScore(),
                        result.premiumAmount(),
                        result.message(),
                        result.createdAt()
                );
            }

            // last() 为 null — 可能走的是 @State 错误路由（CustomerNotFound/VehicleLookupError/ExtractionFailed）
            // 这些 ErrorState 的 @Action 虽然执行了，但框架不把 UnderwritingResult 存入 outputs。
            // 需要从 Blackboard 读取错误信息，包装成 UnderwritingResponse(status="ERROR") 正常返回。
            String blackboardError = (String) completedProcess.get("underwriting_error");
            if (blackboardError != null && !blackboardError.isBlank()) {
                logger.info("Underwriting business error: {}", blackboardError);
                return new UnderwritingResponse(
                        null, "ERROR", 0.0, 0.0,
                        blackboardError, java.time.LocalDateTime.now()
                );
            }

            // 既没有结果也没有 Blackboard 错误 — 流程真的失败了
            if (completedProcess.getStatus() != AgentProcessStatusCode.COMPLETED) {
                handleAgentStuck(underwritingAgentBean, completedProcess, "Underwriting");
                throw new RuntimeException("Agent process failed with status: " + completedProcess.getStatus());
            }

            throw new RuntimeException("Agent process did not produce UnderwritingResult");

        } catch (Exception e) {
            logger.error("Error processing underwriting request", e);
            throw new RuntimeException("Failed to process underwriting: " + e.getMessage(), e);
        }
    }

    /**
     * 人工审批 REFERRED 状态的报价单，将其转为 APPROVED 状态。
     *
     * <p>仅 REFERRED 状态的报价单可以被审批。审批后报价单状态变为 APPROVED，
     * 用户可继续调用 {@code POST /api/insurance/pay} 完成支付。
     *
     * @param quoteId       报价单 ID
     * @param premiumAmount 可选：核保员覆盖的保费金额。若为 null 则保留系统计算的保费
     * @param comment       审批备注
     * @return 审批结果
     */
    @Transactional
    public ApproveQuoteResponse approveQuote(Long quoteId, Double premiumAmount, String comment) {
        Quote quote = quoteRepository.findById(quoteId)
                .orElseThrow(() -> new RuntimeException("Quote not found: " + quoteId));

        // 1. 仅 REFERRED 状态可被审批
        if (quote.getStatus() != Quote.QuoteStatus.REFERRED) {
            throw new RuntimeException(
                    "Only REFERRED quotes can be approved. Current status: " + quote.getStatus());
        }

        // 2. 检查是否已过期
        if (quote.getExpiresAt() != null && quote.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new RuntimeException("Quote has expired at: " + quote.getExpiresAt());
        }

        // 3. 如果核保员指定了保费则使用指定值，否则保留系统计算值
        double finalPremium;
        if (premiumAmount != null && premiumAmount > 0) {
            finalPremium = premiumAmount;
            logger.info("Underwriter override premium: ¥{} → ¥{} for quote #{}",
                    quote.getPremiumAmount(), finalPremium, quoteId);
        } else {
            finalPremium = quote.getPremiumAmount();
        }

        // 4. 更新报价单状态
        quote.setPremiumAmount(finalPremium);
        quote.setStatus(Quote.QuoteStatus.APPROVED);
        Quote saved = quoteRepository.save(quote);

        String message = String.format(
                "Quote #%d approved by human underwriter. Premium: ¥%.2f. "
                        + "To issue the policy, call POST /api/insurance/pay with quoteId=%d",
                saved.getId(), finalPremium, saved.getId());

        if (comment != null && !comment.isBlank()) {
            message = message + " Comment: " + comment;
        }

        logger.info("Quote #{} approved: riskScore={}, premium=¥{}, comment={}",
                saved.getId(), saved.getRiskScore(), finalPremium,
                comment != null ? comment : "(none)");

        return new ApproveQuoteResponse(
                saved.getId(),
                saved.getStatus().name(),
                saved.getRiskScore(),
                finalPremium,
                saved.getCustomer().getName(),
                saved.getVehicle().getModel(),
                message,
                LocalDateTime.now()
        );
    }

    // ──────────────────────────────────────────────
    //  理赔处理（通过 ClaimsAgent）
    // ──────────────────────────────────────────────

    /**
     * 通过 ClaimsAgent 运行理赔处理流程。
     *
     * <p>输入格式为键值对：{@code "policy=POL-xxx description=... amount=xxx"}。
     * 重复理赔（相同保单 + 相同描述）会被拒绝。
     * 进入 INVESTIGATING 状态的理赔，需使用专用的审核接口
     * {@code POST /api/insurance/claims/{claimNumber}/review} 进行批准或拒绝，
     * 无需重新调用本方法。
     */
    public ClaimsAgent.ClaimResult processClaim(String userInput) {
        if (containsUnauthorizedCommand(userInput)) {
            logger.warn("Unauthorized command detected in claim input: {}", userInput);
            throw new IllegalArgumentException("Input contains unauthorized commands");
        }

        logger.info("Processing claim: {}", userInput);

        try {
            UserInput input = new UserInput(userInput);

            Agent claimsAgent = agentPlatform.agents().stream()
                    .filter(a -> a.getName().contains("Claims"))
                    .findFirst()
                    .orElseThrow(() -> new RuntimeException("ClaimsAgent not found"));

            logger.info("Found agent: {}", claimsAgent.getName());

            var processOptions = new ProcessOptions()
                    .withVerbosity(new Verbosity()
                            .withShowPrompts(true)
                            .withShowLlmResponses(true)
                            .withDebug(true));

            AgentProcess process = agentPlatform.createAgentProcessFrom(
                    claimsAgent, processOptions, input);

            // 运行进程（带超时保护，超时则触发 StuckHandler）
            AgentProcess completedProcess = runWithTimeout(claimsAgent, claimsAgentBean, process, "Claims");

            // 先尝试获取正常路径的结果
            ClaimsAgent.ClaimResult result = completedProcess.last(ClaimsAgent.ClaimResult.class);

            if (result != null) {
                logger.info("Claim processed: claimNumber={}, status={}, fraudScore={}",
                        result.claimNumber(), result.claimStatus(), result.fraudScore());
                return result;
            }

            // last() 为 null — 可能走的是 @State 错误路由（InputError/PolicyError/DuplicateClaimDetected）
            // 这些 ErrorState 的 @Action 虽然执行了，但框架不把 ClaimResult 存入 outputs。
            // 需要从 Blackboard 读取错误信息，包装成 ClaimResult 正常返回。
            String blackboardError = (String) completedProcess.get("claims_error");
            if (blackboardError != null && !blackboardError.isBlank()) {
                logger.info("Claim business error: {}", blackboardError);
                return new ClaimsAgent.ClaimResult("", "ERROR", 0.0, 0.0, blackboardError);
            }

            // 既没有结果也没有 Blackboard 错误 — 流程真的失败了
            if (completedProcess.getStatus() != AgentProcessStatusCode.COMPLETED) {
                handleAgentStuck(claimsAgentBean, completedProcess, "Claims");
                throw new RuntimeException("Claim agent process failed with status: " + completedProcess.getStatus());
            }

            throw new RuntimeException("Claim agent process did not produce ClaimResult");

        } catch (Exception e) {
            logger.error("Error processing claim", e);
            throw new RuntimeException("Failed to process claim: " + e.getMessage(), e);
        }
    }

    /**
     * 人工审核 INVESTIGATING 状态的理赔单。
     *
     * <p>仅 INVESTIGATING 状态的理赔单可被审核。审核员可批准或拒绝。
     * 审核结果为终态决议，无需再次调用 Agent。
     *
     * @param claimNumber    理赔单号（如 "CLM-C1E3A50"）
     * @param decision       审核决定："APPROVED" 或 "DENIED"
     * @param reviewerNotes  可选：审核员备注
     * @param approvedAmount 可选：赔付金额覆盖（不提供则按保费上限计算）
     * @return 审核结果
     */
    @Transactional
    public ReviewClaimResponse reviewClaim(String claimNumber, String decision, String reviewerNotes, Double approvedAmount) {
        Claim claim = claimRepository.findByClaimNumber(claimNumber)
                .orElseThrow(() -> new RuntimeException("Claim not found: " + claimNumber));

        // Only INVESTIGATING claims can be reviewed
        if (claim.getStatus() != Claim.ClaimStatus.INVESTIGATING) {
            throw new RuntimeException(
                    "Only INVESTIGATING claims can be reviewed. Current status: " + claim.getStatus());
        }

        if (!"APPROVED".equals(decision) && !"DENIED".equals(decision)) {
            throw new RuntimeException("Decision must be APPROVED or DENIED, got: " + decision);
        }

        if ("APPROVED".equals(decision)) {
            claim.setStatus(Claim.ClaimStatus.APPROVED);

            // Calculate payout: use reviewer's amount, or cap at 5x premium
            double coverageLimit = claim.getPolicy().getPremiumAmount() * 5;
            double finalAmount;
            if (approvedAmount != null && approvedAmount > 0) {
                finalAmount = Math.min(approvedAmount, coverageLimit);
                logger.info("Reviewer override payout: ¥{} → ¥{} (capped at coverage limit) for claim {}",
                        approvedAmount, finalAmount, claimNumber);
            } else {
                finalAmount = Math.min(claim.getClaimedAmount(), coverageLimit);
            }
            claim.setPaidAmount(finalAmount);
        } else {
            claim.setStatus(Claim.ClaimStatus.DENIED);
            claim.setPaidAmount(0.0);
        }

        Claim saved = claimRepository.save(claim);

        String message;
        if ("APPROVED".equals(decision)) {
            message = String.format(
                    "Claim %s approved by reviewer. Payout: ¥%.0f. "
                            + "Payment will be processed within 3 business days.",
                    saved.getClaimNumber(), saved.getPaidAmount());
        } else {
            message = String.format(
                    "Claim %s denied by reviewer. Please contact claims department for further assistance.",
                    saved.getClaimNumber());
        }

        if (reviewerNotes != null && !reviewerNotes.isBlank()) {
            message = message + " Notes: " + reviewerNotes;
        }

        logger.info("Claim {} reviewed: decision={}, paidAmount=¥{}, notes={}",
                saved.getClaimNumber(), decision, saved.getPaidAmount(),
                reviewerNotes != null ? reviewerNotes : "(none)");

        return new ReviewClaimResponse(
                saved.getId(),
                saved.getClaimNumber(),
                saved.getStatus().name(),
                saved.getClaimedAmount(),
                saved.getPaidAmount(),
                saved.getFraudScore(),
                reviewerNotes,
                LocalDateTime.now(),
                message
        );
    }
}
