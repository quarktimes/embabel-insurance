package com.embabel.insurance.service;

import com.embabel.insurance.assistant.Intent;
import com.embabel.insurance.assistant.IntentClassifier;
import com.embabel.insurance.dto.response.AssistantResponse;
import com.embabel.insurance.dto.response.PolicyResponse;
import com.embabel.insurance.entity.Customer;
import com.embabel.insurance.entity.Quote;
import com.embabel.insurance.entity.Vehicle;
import com.embabel.insurance.entity.Policy;
import com.embabel.insurance.repository.QuoteRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 统一助手服务，接收用户自然语言输入，进行意图分类并路由到对应的 Agent 或 Service。
 *
 * <p>职责链路：</br>
 * 用户输入 → {@link IntentClassifier#classify(String)} → 路由到对应处理器 → 结构化响应
 *
 * <p>会话管理：按 userId 在内存中管理会话，30 分钟 TTL。
 */
@Service
public class AssistantService {

    private static final Logger logger = LoggerFactory.getLogger(AssistantService.class);

    /** 会话超时时间（秒） */
    private static final long SESSION_TTL_SECONDS = 30 * 60;

    /** 会话存储：userId → (sessionId → SessionRecord) */
    private final Map<String, Map<String, SessionRecord>> sessions = new ConcurrentHashMap<>();

    private final IntentClassifier intentClassifier;
    private final AgentService agentService;
    private final ChatService chatService;
    private final PolicyService policyService;
    private final QuoteRepository quoteRepository;
    private final PaymentService paymentService;

    public AssistantService(IntentClassifier intentClassifier,
                            AgentService agentService,
                            ChatService chatService,
                            PolicyService policyService,
                            QuoteRepository quoteRepository,
                            PaymentService paymentService) {
        this.intentClassifier = intentClassifier;
        this.agentService = agentService;
        this.chatService = chatService;
        this.policyService = policyService;
        this.quoteRepository = quoteRepository;
        this.paymentService = paymentService;
    }

    /**
     * 处理用户消息：意图分类 → 路由 → 返回结构化响应。
     *
     * @param userId   认证用户 ID
     * @param message  用户输入
     * @param sessionId 可选会话 ID，延续已有对话
     * @return 统一结构化响应
     */
    public AssistantResponse handleMessage(String userId, String message, String sessionId) {
        // 1. 解析或创建会话
        boolean isNewSession = false;
        SessionRecord session = resolveSession(userId, sessionId);
        if (session == null) {
            session = createSession(userId);
            isNewSession = true;
        } else {
            session.touch();
        }

        // 2. 意图分类
        Intent intent = intentClassifier.classify(message);
        logger.info("Assistant: userId={}, intent={}, message={}", userId, intent, truncate(message, 60));

        // 3. 路由
        try {
            return switch (intent) {
                case UNDERWRITING -> handleUnderwriting(userId, message, session);
                case CLAIMS -> handleClaims(message, session);
                case POLICY_QUERY -> handlePolicyQuery(userId, session);
                case VIEW_DETAILS -> handleViewDetails(message, session);
                case PAYMENT -> handlePayment(message, session);
                case CHAT -> handleChat(userId, message, session, isNewSession);
            };
        } catch (Exception e) {
            logger.error("Error handling {} intent for user {}", intent, userId, e);
            return AssistantResponse.error(
                    session.id,
                    "抱歉，处理您的问题时遇到错误：" + e.getMessage()
            );
        }
    }

    /**
     * 清除会话。
     */
    public void clearSession(String userId, String sessionId) {
        Map<String, SessionRecord> userSessions = sessions.get(userId);
        if (userSessions != null) {
            userSessions.remove(sessionId);
        }
    }

    // ════════════════════════════════════════════
    //  路由处理器
    // ════════════════════════════════════════════

    private AssistantResponse handleUnderwriting(String userId, String message, SessionRecord session) {
        // 调用 AgentService 执行核保
        var uwResponse = agentService.processUnderwriting(userId, message);

        // 构建结构化 data
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("quoteId", uwResponse.getQuoteId());
        data.put("status", uwResponse.getStatus());
        data.put("riskScore", uwResponse.getRiskScore());
        data.put("premiumAmount", uwResponse.getPremiumAmount());

        // 构建操作按钮
        List<AssistantResponse.Action> actions = new ArrayList<>();
        if ("APPROVED".equals(uwResponse.getStatus()) && uwResponse.getQuoteId() != null) {
            actions.add(new AssistantResponse.Action(
                    "💳 立即支付（¥" + String.format("%.0f", uwResponse.getPremiumAmount()) + "）",
                    "pay",
                    Map.of("quoteId", uwResponse.getQuoteId())
            ));
            actions.add(new AssistantResponse.Action("📋 查看详情", "view_details",
                    Map.of("quoteId", uwResponse.getQuoteId())));
        } else if ("REFERRED".equals(uwResponse.getStatus())) {
            actions.add(new AssistantResponse.Action("等待核保员审批", "wait_approval", Map.of()));
        }

        // 文本消息供打字机展示
        String text = uwResponse.getMessage() != null ? uwResponse.getMessage()
                : "核保结果：" + uwResponse.getStatus();

        return AssistantResponse.underwritingResult(session.id, text, data, actions);
    }

    private AssistantResponse handleClaims(String message, SessionRecord session) {
        // 从消息中提取键值对格式，或直接转发给 AgentService
        var claimResult = agentService.processClaim(message);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("claimNumber", claimResult.claimNumber());
        data.put("status", claimResult.claimStatus());
        data.put("fraudScore", claimResult.fraudScore());
        data.put("approvedAmount", claimResult.approvedAmount());

        String text = claimResult.message() != null && !claimResult.message().isBlank()
                ? claimResult.message()
                : "理赔结果：" + claimResult.claimStatus();

        return AssistantResponse.claimResult(session.id, text, data, List.of());
    }

    private AssistantResponse handlePolicyQuery(String userId, SessionRecord session) {
        List<PolicyResponse> policies = policyService.getPoliciesByUserId(userId);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", policies.size());
        data.put("policies", policies.stream()
                .map(p -> Map.of(
                        "policyNumber", p.getPolicyNumber(),
                        "vehicle", p.getVehicleBrand() + " " + p.getVehicleModel(),
                        "premium", p.getPremiumAmount(),
                        "status", p.getStatus(),
                        "effectiveDate", p.getEffectiveDate() != null ? p.getEffectiveDate().toString() : null,
                        "expirationDate", p.getExpirationDate() != null ? p.getExpirationDate().toString() : null
                ))
                .toList());

        String text;
        if (policies.isEmpty()) {
            text = "您目前没有有效的保单。";
        } else {
            text = "您共有 " + policies.size() + " 份保单：\n";
            for (PolicyResponse p : policies) {
                text += "- " + p.getPolicyNumber()
                        + " | " + p.getVehicleBrand() + " " + p.getVehicleModel()
                        + " | 保费 ¥" + String.format("%.0f", p.getPremiumAmount())
                        + " | " + p.getStatus()
                        + (p.getExpirationDate() != null ? " | 到期 " + p.getExpirationDate().toLocalDate() : "")
                        + "\n";
            }
        }

        return AssistantResponse.policyList(session.id, text, data);
    }

    private AssistantResponse handleViewDetails(String message, SessionRecord session) {
        // 从消息中解析 quoteId，"view_details quoteId=1"
        Long quoteId = null;
        if (message.contains("quoteId=")) {
            try {
                quoteId = Long.parseLong(message.replaceAll(".*quoteId=(\\d+).*", "$1"));
            } catch (NumberFormatException ignored) {}
        }

        if (quoteId == null) {
            return AssistantResponse.error(session.id, "请指定要查看的报价单 ID。");
        }

        Optional<Quote> quoteOpt = quoteRepository.findByIdWithDetails(quoteId);
        if (quoteOpt.isEmpty()) {
            return AssistantResponse.error(session.id, "未找到报价单 #" + quoteId);
        }

        Quote quote = quoteOpt.get();
        Customer customer = quote.getCustomer();
        Vehicle vehicle = quote.getVehicle();

        String text = "📋 **报价单 #" + quote.getId() + "**\n\n"
                + "**投保人：** " + customer.getName() + "\n"
                + "**车辆：** " + vehicle.getBrand() + " " + vehicle.getModel()
                + "（" + vehicle.getLicensePlate() + "）\n"
                + "**车辆年份：** " + vehicle.getYear() + "\n"
                + "**车辆价值：** ¥" + String.format("%,.0f", vehicle.getVehicleValue()) + "\n"
                + "**险种：** " + quote.getCoverageType() + "\n"
                + "**风险评分：** " + String.format("%.0f", quote.getRiskScore()) + "/100\n"
                + "**保费：** ¥" + String.format("%,.0f", quote.getPremiumAmount()) + "\n"
                + "**状态：** " + statusLabel(quote.getStatus()) + "\n"
                + "**创建时间：** " + quote.getCreatedAt().toLocalDate() + "\n"
                + (quote.getExpiresAt() != null ? "**有效期至：** " + quote.getExpiresAt().toLocalDate() : "");

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("quoteId", quote.getId());
        data.put("customerName", customer.getName());
        data.put("vehicle", vehicle.getBrand() + " " + vehicle.getModel());
        data.put("licensePlate", vehicle.getLicensePlate());
        data.put("riskScore", quote.getRiskScore());
        data.put("premiumAmount", quote.getPremiumAmount());
        data.put("coverageType", quote.getCoverageType());
        data.put("status", quote.getStatus().name());
        data.put("createdAt", quote.getCreatedAt().toString());

        List<AssistantResponse.Action> actions = new ArrayList<>();
        if (quote.getStatus() == Quote.QuoteStatus.APPROVED) {
            actions.add(new AssistantResponse.Action(
                    "💳 支付 ¥" + String.format("%,.0f", quote.getPremiumAmount()),
                    "pay", Map.of("quoteId", quote.getId())));
        }

        return AssistantResponse.underwritingResult(session.id, text, data, actions);
    }

    private AssistantResponse handlePayment(String message, SessionRecord session) {
        // 解析 quoteId："支付 quoteId=1" 或 "pay quoteId=1"
        Long quoteId = null;
        if (message.contains("quoteId=")) {
            try {
                quoteId = Long.parseLong(message.replaceAll(".*quoteId=(\\d+).*", "$1"));
            } catch (NumberFormatException ignored) {}
        }

        if (quoteId == null) {
            return AssistantResponse.error(session.id, "请指定要支付的报价单 ID。");
        }

        // 先查询报价单详情（JOIN FETCH 加载客户和车辆，供展示用）
        Quote quote = quoteRepository.findByIdWithDetails(quoteId).orElse(null);
        if (quote == null) {
            return AssistantResponse.error(session.id, "未找到报价单 #" + quoteId);
        }

        try {
            Policy policy = paymentService.payAndIssuePolicy(quoteId, "ALIPAY");

            String text = "✅ **支付成功！**\n\n"
                    + "**保单号：** " + policy.getPolicyNumber() + "\n"
                    + "**投保人：** " + quote.getCustomer().getName() + "\n"
                    + "**车辆：** " + quote.getVehicle().getBrand() + " " + quote.getVehicle().getModel()
                    + "（" + quote.getVehicle().getLicensePlate() + "）\n"
                    + "**险种：** " + policy.getCoverageType() + "\n"
                    + "**保费：** ¥" + String.format("%,.0f", policy.getPremiumAmount()) + "\n"
                    + "**生效日期：** " + policy.getEffectiveDate().toLocalDate() + "\n"
                    + "**到期日期：** " + policy.getExpirationDate().toLocalDate() + "\n"
                    + "**支付方式：** 支付宝\n\n"
                    + "保单已签发，感谢您的投保！";

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("policyNumber", policy.getPolicyNumber());
            data.put("premiumAmount", policy.getPremiumAmount());
            data.put("coverageType", policy.getCoverageType());
            data.put("effectiveDate", policy.getEffectiveDate().toString());
            data.put("expirationDate", policy.getExpirationDate().toString());

            return AssistantResponse.underwritingResult(session.id, text, data, List.of());

        } catch (RuntimeException e) {
            logger.error("Payment failed: {}", e.getMessage());
            return AssistantResponse.error(session.id, "支付失败：" + e.getMessage());
        }
    }

    private static String statusLabel(Quote.QuoteStatus status) {
        return switch (status) {
            case PENDING -> "⏳ 待处理";
            case APPROVED -> "✅ 已批准";
            case REFERRED -> "👤 转人工";
            case DECLINED -> "❌ 已拒绝";
        };
    }

    private AssistantResponse handleChat(String userId, String message, SessionRecord session, boolean isNewSession) {
        // 复用现有 ChatService 的会话机制
        var chatResponse = chatService.processChat(userId, message,
                isNewSession ? null : session.id);

        String sessionId = chatResponse.getSessionId();
        if (sessionId != null) {
            session.id = sessionId;  // 同步 ChatService 返回的 sessionId
        }

        return AssistantResponse.chat(session.id, false, chatResponse.getResponse());
    }

    // ════════════════════════════════════════════
    //  会话管理
    // ════════════════════════════════════════════

    private SessionRecord resolveSession(String userId, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return null;
        Map<String, SessionRecord> userSessions = sessions.get(userId);
        if (userSessions == null) return null;
        SessionRecord record = userSessions.get(sessionId);
        if (record == null) return null;
        if (record.isExpired()) {
            userSessions.remove(sessionId);
            return null;
        }
        return record;
    }

    private SessionRecord createSession(String userId) {
        String newId = UUID.randomUUID().toString();
        SessionRecord record = new SessionRecord(newId);
        sessions.computeIfAbsent(userId, k -> new ConcurrentHashMap<>()).put(newId, record);
        return record;
    }

    /** 简化截断 */
    private static String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) + "..." : s;
    }

    private static class SessionRecord {
        String id;
        volatile Instant lastAccess;

        SessionRecord(String id) {
            this.id = id;
            this.lastAccess = Instant.now();
        }

        void touch() { this.lastAccess = Instant.now(); }

        boolean isExpired() {
            return Instant.now().isAfter(lastAccess.plusSeconds(SESSION_TTL_SECONDS));
        }
    }
}
