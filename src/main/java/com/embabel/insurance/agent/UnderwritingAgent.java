package com.embabel.insurance.agent;

import com.embabel.agent.api.annotation.*;
import com.embabel.agent.api.common.Ai;
import com.embabel.agent.api.common.OperationContext;
import com.embabel.agent.api.common.PlannerType;
import com.embabel.agent.core.Blackboard;
import com.embabel.agent.core.AgentProcess;
import com.embabel.agent.domain.io.UserInput;
import com.embabel.common.ai.model.LlmOptions;
import com.embabel.agent.api.common.StuckHandler;
import com.embabel.agent.api.common.StuckHandlerResult;
import com.embabel.agent.api.common.StuckHandlingResultCode;
import com.embabel.insurance.dto.VehicleInfo;
import com.embabel.insurance.entity.Customer;
import com.embabel.insurance.entity.Quote;
import com.embabel.insurance.entity.Vehicle;
import com.embabel.insurance.guardrail.VehicleInfoGuardRail;
import com.embabel.insurance.repository.QuoteRepository;
import com.embabel.insurance.service.DataService;
import com.embabel.insurance.service.PremiumCalculationService;
import com.embabel.insurance.service.RiskCalculationService;
import com.embabel.insurance.util.ParamExtractor;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

import static com.embabel.insurance.service.LlmSelectionService.ROLE_FAST;

/**
 * 保险核保 Agent，使用 @State 分类模式实现智能风险分级路由。
 *
 * <p>架构（基于 Utility + States 模式）：
 * <ol>
 *   <li>{@code extractVehicleInfo} — LLM 从自然语言中提取车辆信息</li>
 *   <li>{@code lookupCustomer} — 从数据库查找客户档案</li>
 *   <li>{@code lookupVehicle} — 从数据库查找车辆信息</li>
 *   <li>{@code assessRisk} — 入口动作：计算风险评分，分类到对应风险或错误 @State</li>
 *   <li>各 @State 通过 {@code @AchievesGoal @Action} 生成最终的 {@code UnderwritingResult}</li>
 * </ol>
 *
 * <p><b>错误处理策略：</b>不直接抛出异常（会被框架的重试机制吞掉），
 * 而是将错误信息存储到框架的 {@link Blackboard} 上，再通过专用的错误 {@code @State} 路由出去，
 * 确保错误消息能可靠地到达前端。
 */
@Agent(
        description = "核保 Agent，将投保申请按风险评分分级，自动路由到批准、转人工或拒绝",
        planner = PlannerType.UTILITY
)
@Component
public class UnderwritingAgent implements StuckHandler {

    private static final Logger logger = LoggerFactory.getLogger(UnderwritingAgent.class);

    /** Blackboard 上存储核保错误信息的键名 */
    private static final String BLACKBOARD_KEY_ERROR = "underwriting_error";

    private final DataService dataService;
    private final RiskCalculationService riskCalculationService;
    private final PremiumCalculationService premiumCalculationService;
    private final QuoteRepository quoteRepository;

    public UnderwritingAgent(DataService dataService,
                             RiskCalculationService riskCalculationService,
                             PremiumCalculationService premiumCalculationService,
                             QuoteRepository quoteRepository) {
        this.dataService = dataService;
        this.riskCalculationService = riskCalculationService;
        this.premiumCalculationService = premiumCalculationService;
        this.quoteRepository = quoteRepository;
    }

    // ──────────────────────────────────────────────
    //  阶段一：数据采集（LLM + 数据库）
    // ──────────────────────────────────────────────

    @Action(description = "使用 LLM 从用户输入中提取车辆信息，先快速检查 customer 是否存在以避免无效 LLM 调用")
    public VehicleInfo extractVehicleInfo(UserInput userInput, OperationContext context) {
        String rawContent = userInput.getContent().replaceAll("\\s+userId=\\S+", "");
        logger.info("Extracting vehicle info from: {}", rawContent);

        // ── 快速失败：先验证 customer 是否存在 ──
        // 避免 CustomerNotFound 场景下白白等待 120s LLM 超时
        String userId = extractUserId(userInput.getContent());
        var customerOpt = dataService.getCustomerByUserId(userId);
        if (customerOpt.isEmpty()) {
            logger.warn("Customer not found for userId={} — skipping LLM extraction", userId);
            context.bind(BLACKBOARD_KEY_ERROR, "Customer not found: " + userId);
            return new VehicleInfo(null, null, null);
        }

        if (!rawContent.toLowerCase().matches(".*(?:insure|insuring|insurance|car|vehicle|auto|suv|sedan|truck|" +
                "license|plate|车牌|保险|车|投保).*")) {
            logger.info("No vehicle-related keywords found — this is not an insurance request");
            context.bind(BLACKBOARD_KEY_ERROR,
                    "No insurance-related keywords detected in input. Please describe the vehicle and insurance need.");
            return new VehicleInfo(null, null, null);
        }

        Ai ai = context.ai();
        logger.info("AI context available: {}", true);

        String prompt = """
            Extract vehicle information from the user input below.

            Rules:
            - "model" is the vehicle MODEL NAME ONLY (e.g., "RAV4", "Camry", "Model 3"), do NOT include the brand.
            - "brand" is the manufacturer (e.g., "Toyota", "Tesla", "BMW").
            - "licensePlate" is the plate number if mentioned, otherwise null.

            User input: %s

            Respond with ONLY a valid JSON object:
            {"model": "...", "brand": "...", "licensePlate": "..."}
            Do not include any other text, markdown formatting, or explanation.
            """.formatted(rawContent);

        try {
            logger.info("extractVehicleInfo LLM prompt: {}", prompt);
            VehicleInfo info = ai
                    .withLlm(LlmOptions.withLlmForRole(ROLE_FAST))
                    .withGuardRails(new VehicleInfoGuardRail())
                    .createObjectIfPossible(prompt, VehicleInfo.class);

            if (info == null) {
                logger.info("LLM could not extract vehicle info, proceeding with empty vehicle info");
                return new VehicleInfo(null, null, null);
            }

            logger.info("Extracted vehicle info: model={}, brand={}, licensePlate={}",
                    info.getModel(), info.getBrand(), info.getLicensePlate());
            return info;
        } catch (Exception e) {
            logger.error("Error extracting vehicle info: {}", e.getMessage(), e);
            context.bind(BLACKBOARD_KEY_ERROR, "Failed to extract vehicle information: " + e.getMessage());
            return new VehicleInfo(null, null, null);
        }
    }

    @Action(description = "从数据库查找客户档案")
    public Customer lookupCustomer(UserInput userInput, OperationContext context) {
        String userId = extractUserId(userInput.getContent());
        logger.info("Looking up customer with userId: {}", userId);

        var customerOpt = dataService.getCustomerByUserId(userId);
        if (customerOpt.isEmpty()) {
            logger.warn("Customer not found: {}", userId);
            // 只在 blackboard 上还没有 error 时才写入，避免覆盖 extractVehicleInfo 中已写入的同名 error
            String existingError = (String) context.get(BLACKBOARD_KEY_ERROR);
            if (existingError == null || existingError.isBlank()) {
                context.bind(BLACKBOARD_KEY_ERROR, "Customer not found: " + userId);
            }
            // 返回 sentinel 而非 null：确保 Blackboard 上有 Customer 类型对象，
            // 使 assessRisk(Customer, Vehicle, context) 能被规划器匹配，避免 STUCK
            return Customer.lookupFailed();
        }

        Customer customer = customerOpt.get();
        logger.info("Found customer: name={}, age={}, drivingExperience={}, accidentCount={}",
                customer.getName(), customer.getAge(),
                customer.getDrivingExperienceYears(), customer.getAccidentCount());
        return customer;
    }

    @Action(description = "从数据库查找车辆信息")
    public Vehicle lookupVehicle(VehicleInfo vehicleInfo, Customer customer, OperationContext context) {
        // If customer lookup already failed upstream, propagate
        if (Customer.isLookupFailed(customer)) {
            logger.info("Skipping vehicle lookup — customer lookup already failed");
            return Vehicle.lookupFailed();
        }

        // If vehicle extraction already failed (all fields null), propagate the error
        // instead of fallback-lookup. This prevents cases like P6 (no insurance keywords)
        // from accidentally finding the customer's only vehicle and bypassing the error.
        if (isVehicleInfoEmpty(vehicleInfo)) {
            logger.info("Skipping vehicle lookup — vehicle info extraction already failed (empty VehicleInfo)");
            return Vehicle.lookupFailed();
        }

        logger.info("Looking up vehicle for customer {} with model {}",
                customer.getName(), vehicleInfo.getModel());

        try {
            if (vehicleInfo.getLicensePlate() != null && !vehicleInfo.getLicensePlate().isEmpty()) {
                var result = dataService.getVehicleByLicensePlate(vehicleInfo.getLicensePlate());
                if (result.isEmpty()) {
                    context.bind(BLACKBOARD_KEY_ERROR, "Vehicle not found with license plate: " + vehicleInfo.getLicensePlate());
                    return Vehicle.lookupFailed();
                }
                return result.get();
            }

            if (vehicleInfo.getModel() != null && !vehicleInfo.getModel().isBlank()) {
                var vehicles = dataService.getVehiclesByCustomerAndModel(customer.getId(), vehicleInfo.getModel());
                if (vehicles.size() > 1) {
                    context.bind(BLACKBOARD_KEY_ERROR,
                            "Customer has multiple vehicles matching model '" + vehicleInfo.getModel()
                                    + "'. Please specify a license plate to identify the exact vehicle.");
                    return Vehicle.lookupFailed();
                }
                var result = dataService.getVehicleByCustomerAndModel(customer.getId(), vehicleInfo.getModel());
                if (result.isEmpty()) {
                    context.bind(BLACKBOARD_KEY_ERROR, "Vehicle not found for customer with model: " + vehicleInfo.getModel());
                    return Vehicle.lookupFailed();
                }
                return result.get();
            }

            var customerVehicles = dataService.getVehiclesByCustomerId(customer.getId());
            if (customerVehicles.isEmpty()) {
                context.bind(BLACKBOARD_KEY_ERROR,
                        "No vehicles found for customer '" + customer.getName()
                                + "'. Please add a vehicle before requesting insurance.");
                return Vehicle.lookupFailed();
            }

            if (customerVehicles.size() > 1) {
                context.bind(BLACKBOARD_KEY_ERROR,
                        "Customer '" + customer.getName() + "' has " + customerVehicles.size() + " vehicles. "
                                + "Please specify which vehicle to insure by providing the model or license plate.");
                return Vehicle.lookupFailed();
            }

            return customerVehicles.get(0);
        } catch (Exception e) {
            logger.error("Unexpected error during vehicle lookup", e);
            context.bind(BLACKBOARD_KEY_ERROR, "Vehicle lookup failed: " + e.getMessage());
            return Vehicle.lookupFailed();
        }
    }

    // ──────────────────────────────────────────────
    //  阶段二：风险评估 → @State 分类（入口点）
    // ──────────────────────────────────────────────

    /**
     * 密封接口，定义所有可能的核保结果，包含错误状态。
     * Utility 规划器据此路由到正确的 @AchievesGoal 动作。
     */
    @State
    public sealed interface UnderwritingDecision
            permits LowRiskQuote, MediumRiskReview, HighRiskDecline,
                    CustomerNotFound, VehicleLookupError, ExtractionFailed {}

    /**
     * 入口动作：先检查前置阶段是否出错，再计算风险评分并分类到对应的风险层级。
     */
    @Action(description = "检查前置阶段错误，再计算风险评分并将投保申请分类到对应风险层级")
    public UnderwritingDecision assessRisk(Customer customer, Vehicle vehicle, OperationContext context) {

        // ── 检查数据采集阶段是否有错误 ──
        // Customer/Vehicle 可能为 lookupFailed() sentinel，代表前置查找已失败
        if (Customer.isLookupFailed(customer)) {
            String errorMsg = (String) context.get(BLACKBOARD_KEY_ERROR);
            logger.info("Error detected — customer lookup failed: {}", errorMsg);
            return new CustomerNotFound(
                    errorMsg != null ? errorMsg : "Customer not found");
        }

        if (Vehicle.isLookupFailed(vehicle)) {
            String errorMsg = (String) context.get(BLACKBOARD_KEY_ERROR);
            logger.info("Error detected — vehicle lookup failed: {}", errorMsg);
            // 区分车辆查找失败 vs 信息提取失败
            // VehicleLookupError: 用户有车辆但指定的车辆找不到
            // ExtractionFailed: 无法从输入中提取车辆信息（如无保险关键词、LLM 提取失败）
            if (errorMsg != null && (errorMsg.contains("No insurance-related keywords")
                    || errorMsg.contains("Failed to extract vehicle information"))) {
                return new ExtractionFailed(errorMsg);
            }
            return new VehicleLookupError(
                    errorMsg != null ? errorMsg : "Vehicle not found");
        }

        // ── 正常风险评估 ──
        try {
            double riskScore = riskCalculationService.calculateRiskScore(customer, vehicle);
            double premium = premiumCalculationService.calculatePremium(vehicle, riskScore, "COMPREHENSIVE");

            logger.info("Risk assessment: score={}, premium=¥{} for customer={}, vehicle={}",
                    String.format("%.0f", riskScore), String.format("%.2f", premium),
                    customer.getName(), vehicle.getModel());

            if (riskScore <= 60) {
                logger.info("Classified as LOW risk → LowRiskQuote");
                return new LowRiskQuote(quoteRepository, customer, vehicle, riskScore, premium);
            } else if (riskScore < 80) {
                logger.info("Classified as MEDIUM risk → MediumRiskReview");
                return new MediumRiskReview(quoteRepository, customer, vehicle, riskScore, premium);
            } else {
                logger.info("Classified as HIGH risk → HighRiskDecline");
                return new HighRiskDecline(quoteRepository, customer, vehicle, riskScore);
            }
        } catch (Exception e) {
            logger.error("Error during risk assessment: {}", e.getMessage(), e);
            return new ExtractionFailed("Risk assessment failed: " + e.getMessage());
        }
    }

    // ──────────────────────────────────────────────
    //  @State 实现 — 正常风险层级
    // ──────────────────────────────────────────────

    /**
     * 低风险（评分 ≤ 60）：自动批准并签发热度报价单。
     */
    @State
    public record LowRiskQuote(QuoteRepository quoteRepository, Customer customer, Vehicle vehicle,
                               double riskScore, double premiumAmount)
            implements UnderwritingDecision {

        @AchievesGoal(
                description = "Process low-risk insurance application — auto-approve and issue quote",
                value = 1.0,
                tags = {"insurance", "underwriting", "approved"},
                examples = {"My RAV4 was rear-ended last week", "I need insurance for my Toyota"}
        )
        @Action(description = "Issue approved quote for low-risk application")
        public UnderwritingResult handleLowRisk() {
            Quote quote = new Quote(
                    customer, vehicle, riskScore, premiumAmount,
                    Quote.QuoteStatus.APPROVED, "COMPREHENSIVE"
            );

            Quote saved = quoteRepository.save(quote);
            String message = "Quote approved. Premium: ¥" + String.format("%.2f", premiumAmount) +
                    ". To issue the policy, call POST /api/insurance/pay with quoteId=" + saved.getId();

            return new UnderwritingResult(
                    saved.getId(), "APPROVED", riskScore, premiumAmount,
                    message, saved.getCreatedAt()
            );
        }
    }

    /**
     * 中风险（60 &lt; 评分 &lt; 80）：转人工核保员审核。
     */
    @State
    public record MediumRiskReview(QuoteRepository quoteRepository, Customer customer, Vehicle vehicle,
                                   double riskScore, double premiumAmount)
            implements UnderwritingDecision {

        @AchievesGoal(
                description = "Process medium-risk insurance application — refer to human underwriter",
                value = 1.0,
                tags = {"insurance", "underwriting", "referred"},
                examples = {"I'm 25 with 3 years driving, want to insure BMW 3 Series"}
        )
        @Action(description = "Refer medium-risk application to human underwriter")
        public UnderwritingResult handleMediumRisk() {
            Quote quote = new Quote(
                    customer, vehicle, riskScore, premiumAmount,
                    Quote.QuoteStatus.REFERRED, "COMPREHENSIVE"
            );

            Quote saved = quoteRepository.save(quote);
            String message = String.format(
                    "Quote referred to human underwriter for review. "
                            + "System-computed premium: ¥%.2f. "
                            + "To approve, call POST /api/insurance/quotes/%d/approve",
                    premiumAmount, saved.getId());

            return new UnderwritingResult(
                    saved.getId(), "REFERRED", riskScore, premiumAmount,
                    message, saved.getCreatedAt()
            );
        }
    }

    /**
     * 高风险（评分 ≥ 80）：拒绝申请并附带原因说明。
     */
    @State
    public record HighRiskDecline(QuoteRepository quoteRepository, Customer customer, Vehicle vehicle,
                                  double riskScore)
            implements UnderwritingDecision {

        @AchievesGoal(
                description = "Process high-risk insurance application — decline with reason",
                value = 1.0,
                tags = {"insurance", "underwriting", "declined"},
                examples = {"5 accidents last year, want to insure Ferrari F40"}
        )
        @Action(description = "Decline high-risk application")
        public UnderwritingResult handleHighRisk() {
            Quote quote = new Quote(
                    customer, vehicle, riskScore, 0.0,
                    Quote.QuoteStatus.DECLINED, "COMPREHENSIVE"
            );
            quote.setRejectionReason("Risk score (" + String.format("%.0f", riskScore) +
                    ") exceeds acceptable threshold");

            Quote saved = quoteRepository.save(quote);
            return new UnderwritingResult(
                    saved.getId(), "DECLINED", riskScore, 0.0,
                    "Quote declined. Reason: " + quote.getRejectionReason(),
                    saved.getCreatedAt()
            );
        }
    }

    // ──────────────────────────────────────────────
    //  @State 实现 — 错误状态
    // ──────────────────────────────────────────────

    /**
     * 错误状态：数据库中未找到客户。
     */
    @State
    public record CustomerNotFound(String message) implements UnderwritingDecision {

        @AchievesGoal(
                description = "Handle customer-not-found error — return descriptive error to caller",
                value = 1.0,
                tags = {"insurance", "underwriting", "error", "customer"}
        )
        @Action(description = "Return customer-not-found error result")
        public UnderwritingResult handleCustomerNotFound(OperationContext context) {
            logger.warn("CustomerNotFound state: {}", message);
            // 框架在 @State 转换时会 clear Blackboard，需要重新 bind 错误信息
            // 以便 AgentService.processUnderwriting 能通过 completedProcess.get() 读取
            context.bind(BLACKBOARD_KEY_ERROR, message);
            return new UnderwritingResult(
                    null, "ERROR", 0.0, 0.0,
                    message, LocalDateTime.now()
            );
        }
    }

    /**
     * 错误状态：车辆查找失败（未找到、多个匹配等）。
     */
    @State
    public record VehicleLookupError(String message) implements UnderwritingDecision {

        @AchievesGoal(
                description = "Handle vehicle-lookup error — return descriptive error to caller",
                value = 1.0,
                tags = {"insurance", "underwriting", "error", "vehicle"}
        )
        @Action(description = "Return vehicle-lookup-error result")
        public UnderwritingResult handleVehicleLookupError(OperationContext context) {
            logger.warn("VehicleLookupError state: {}", message);
            // 框架在 @State 转换时会 clear Blackboard，需要重新 bind 错误信息
            context.bind(BLACKBOARD_KEY_ERROR, message);
            return new UnderwritingResult(
                    null, "ERROR", 0.0, 0.0,
                    message, LocalDateTime.now()
            );
        }
    }

    /**
     * 错误状态：LLM 提取或风险评估过程发生意外异常。
     */
    @State
    public record ExtractionFailed(String message) implements UnderwritingDecision {

        @AchievesGoal(
                description = "Handle extraction/assessment failure — return descriptive error to caller",
                value = 1.0,
                tags = {"insurance", "underwriting", "error", "extraction"}
        )
        @Action(description = "Return extraction-failed error result")
        public UnderwritingResult handleExtractionFailed(OperationContext context) {
            logger.warn("ExtractionFailed state: {}", message);
            // 框架在 @State 转换时会 clear Blackboard，需要重新 bind 错误信息
            context.bind(BLACKBOARD_KEY_ERROR, message);
            return new UnderwritingResult(
                    null, "ERROR", 0.0, 0.0,
                    message, LocalDateTime.now()
            );
        }
    }

    // ──────────────────────────────────────────────
    //  公共类型与辅助方法
    // ──────────────────────────────────────────────

    /**
     * 最终输出类型 — 非 @State，规划器将其视为终态结果。
     */
    public record UnderwritingResult(
            Long quoteId,
            String status,
            double riskScore,
            double premiumAmount,
            String message,
            LocalDateTime createdAt
    ) {}

    // ──────────────────────────────────────────────
    //  StuckHandler 实现 — 卡住时输出诊断日志
    // ──────────────────────────────────────────────

    @NotNull
    @Override
    public StuckHandlerResult handleStuck(AgentProcess agentProcess) {
        logger.error("============================================================");
        logger.error("=== UnderwritingAgent STUCK DIAGNOSTICS ===");
        logger.error("=== Process status: {} ===", agentProcess.getStatus());

        // 检查 LLM 相关步骤是否卡住 (extractVehicleInfo 是最可能的卡点)
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

        logger.error("=== Likely stuck in: extractVehicleInfo (LLM call) or assessRisk ===");
        logger.error("=== This agent needs VehicleInfo LLM extraction + RiskAssessment before routing ===");
        logger.error("============================================================");

        return new StuckHandlerResult(
                "UnderwritingAgent stuck — likely LLM timeout during extractVehicleInfo or assessRisk routing",
                this, StuckHandlingResultCode.NO_RESOLUTION, agentProcess);
    }

    /**
     * 从输入中提取 userId 参数（委托给 ParamExtractor 工具类）。
     */
    private String extractUserId(String input) {
        String userId = ParamExtractor.extract(input, "userId");
        return userId != null ? userId : "user";
    }

    /**
     * 检查 VehicleInfo 是否为空（所有字段均为 null），
     * 表示 extractVehicleInfo 阶段已经判定失败。
     */
    private static boolean isVehicleInfoEmpty(VehicleInfo info) {
        return info.getModel() == null
                && info.getBrand() == null
                && info.getLicensePlate() == null;
    }
}
