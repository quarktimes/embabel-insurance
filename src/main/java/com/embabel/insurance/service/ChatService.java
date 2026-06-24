package com.embabel.insurance.service;

import com.embabel.agent.api.invocation.AgentInvocation;
import com.embabel.agent.core.AgentPlatform;
import com.embabel.agent.core.ProcessOptions;
import com.embabel.agent.core.Verbosity;
import com.embabel.agent.domain.io.UserInput;
import com.embabel.insurance.agent.ChatbotAgent;
import com.embabel.agent.api.common.StuckHandlerResult;
import com.embabel.insurance.dto.response.ChatResponse;
import com.embabel.insurance.guardrail.InsuranceUserInputGuardRailImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * AI 客服对话服务，服务端管理会话生命周期。
 *
 * <p>会话绑定到 userId，首次使用时自动创建，超时后自动过期（默认 30 分钟）。
 * 客户端只需发送消息，服务端负责管理会话状态。
 *
 * <p>通过 {@link AgentInvocation} 调用 embabel 框架运行 ChatbotAgent。
 * 包含用户输入护栏校验和对话历史截断机制。
 */
@Service
public class ChatService {

    private static final Logger logger = LoggerFactory.getLogger(ChatService.class);

    /** 会话超时时间（秒），默认 30 分钟 */
    private static final long SESSION_TTL_SECONDS = 30 * 60;

    /** 对话历史最大保留轮数 */
    private static final int MAX_HISTORY_TURNS = 20;

    /** Agent 流程超时时间（秒），超过此时间视为卡住 */
    private static final long PROCESS_TIMEOUT_SECONDS = 120;

    private final AgentPlatform agentPlatform;
    private final ChatbotAgent chatbotAgentBean;
    private final InsuranceUserInputGuardRailImpl userInputGuardRail;
    private final CacheService cacheService;

    /** 用户会话映射：userId → (sessionId → SessionRecord) */
    private final Map<String, Map<String, SessionRecord>> userSessions = new ConcurrentHashMap<>();

    public ChatService(AgentPlatform agentPlatform,
                       ChatbotAgent chatbotAgentBean,
                       InsuranceUserInputGuardRailImpl userInputGuardRail,
                       CacheService cacheService) {
        this.agentPlatform = agentPlatform;
        this.chatbotAgentBean = chatbotAgentBean;
        this.userInputGuardRail = userInputGuardRail;
        this.cacheService = cacheService;
    }

    /**
     * 通过 Agentic RAG 流水线处理聊天消息。
     *
     * <p>若未提供有效的 sessionId（或会话已过期），自动创建新会话并返回给客户端。
     *
     * @param userId    认证用户 ID
     * @param message   用户提问内容
     * @param sessionId 可选的会话 ID，用于延续已有对话
     * @return AI 生成的回复及会话元数据
     */
    public ChatResponse processChat(String userId, String message, String sessionId) {
        logger.info("Processing chat for user: {}, session: {}", userId, sessionId);

        // 解析或创建会话
        boolean isNewSession = false;
        SessionRecord session = resolveSession(userId, sessionId);
        if (session == null) {
            session = createSession(userId);
            isNewSession = true;
            logger.info("Created new session {} for user {}", session.id, userId);
        } else {
            session.touch();
        }

        // 应用用户输入护栏校验
        var validationResult = userInputGuardRail.validate(message, null);
        if (!validationResult.isValid()) {
            logger.warn("User input failed guardrail: {}", validationResult.getErrors());
            return new ChatResponse(
                    "您的问题包含不被允许的内容，请重新表述。",
                    session.id, isNewSession, LocalDateTime.now()
            );
        }

        // 缓存检查：仅首次对话（无历史）使用缓存
        if (session.history.isEmpty()) {
            String normalizedKey = message.trim().toLowerCase();
            String cached = cacheService.getCachedLlmResponse(normalizedKey);
            if (cached != null) {
                logger.info("LLM cache hit for: {}", message.length() > 50 ? message.substring(0, 50) + "..." : message);
                session.history.add("User: " + message);
                session.history.add("Bot: " + cached);
                return new ChatResponse(cached, session.id, isNewSession, LocalDateTime.now());
            }
        }

        // 构建包含对话历史的增强消息
        String enrichedMessage;
        if (session.history.isEmpty()) {
            enrichedMessage = message;
        } else {
            String history = String.join("\n", session.history);
            enrichedMessage = "Conversation history:\n" + history + "\n\nNew question: " + message;
        }

        try {
            UserInput input = new UserInput(enrichedMessage);

            var processOptions = new ProcessOptions()
                    .withVerbosity(new Verbosity()
                            .withShowPrompts(true)
                            .withShowLlmResponses(true)
                            .withDebug(true));

            var invocation = AgentInvocation.builder(agentPlatform)
                    .options(processOptions)
                    .build(ChatbotAgent.ChatOutput.class);

            // 带超时保护地调用 Agent（超时则触发 StuckHandler）
            ChatbotAgent.ChatOutput output;
            try {
                output = CompletableFuture
                        .supplyAsync(() -> invocation.invoke(input))
                        .get(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                handleChatbotStuck();
                throw new RuntimeException("Chatbot agent timed out after " + PROCESS_TIMEOUT_SECONDS + "s", e);
            }

            // 更新对话历史并截断，防止无限制增长
            session.history.add("User: " + message);
            session.history.add("Bot: " + output.response());
            while (session.history.size() > MAX_HISTORY_TURNS * 2) {
                session.history.remove(0);
                session.history.remove(0);
            }

            // 缓存首次对话的 LLM 响应
            if (session.history.size() <= 2) { // 刚加了 User/Bot 两条
                String normalizedKey = message.trim().toLowerCase();
                cacheService.cacheLlmResponse(normalizedKey, output.response());
            }

            logger.info("Chat response generated for session {} (length={})",
                    session.id, output.response().length());

            return new ChatResponse(output.response(), session.id, isNewSession, LocalDateTime.now());

        } catch (Exception e) {
            logger.error("Error processing chat via AgentPlatform", e);
            return new ChatResponse(
                    "抱歉，我在处理您的问题时遇到了困难。请稍后再试，或拨打客服热线400-XXX-XXXX获取帮助。",
                    session.id, isNewSession, LocalDateTime.now()
            );
        }
    }

    /**
     * 清除指定用户的聊天会话。
     */
    public void clearSession(String userId, String sessionId) {
        Map<String, SessionRecord> sessions = userSessions.get(userId);
        if (sessions != null) {
            sessions.remove(sessionId);
            logger.info("Session {} cleared for user {}", sessionId, userId);
        }
    }

    // ---- StuckHandler 支持 ----

    /**
     * ChatbotAgent 卡住时的诊断处理：回调 Bean 的 StuckHandler 打印日志。
     */
    private void handleChatbotStuck() {
        logger.error("============================================================");
        logger.error("=== ChatbotAgent PROCESS STUCK — timed out after {} seconds ===",
                PROCESS_TIMEOUT_SECONDS);
        logger.error("=== Likely stuck on: Agentic RAG LLM call (search + generate) ===");

        try {
            // ChatService 使用 AgentInvocation 而非 AgentProcess，所以传 null
            StuckHandlerResult result = chatbotAgentBean.handleStuck(null);
            logger.error("=== StuckHandler result: code={}, message={} ===",
                    result.getCode(), result.getMessage());
        } catch (Exception e) {
            logger.error("=== Failed to invoke ChatbotAgent StuckHandler ===", e);
        }

        logger.error("============================================================");
    }

    // ---- 内部辅助方法 ----

    /**
     * 解析用户的已有会话，遵循 TTL 过期策略。
     * 若会话不存在或已过期则返回 null。
     */
    private SessionRecord resolveSession(String userId, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        Map<String, SessionRecord> sessions = userSessions.get(userId);
        if (sessions == null) {
            return null;
        }
        SessionRecord session = sessions.get(sessionId);
        if (session == null) {
            return null;
        }
        if (session.isExpired()) {
            sessions.remove(sessionId);
            logger.info("Session {} expired for user {}", sessionId, userId);
            return null;
        }
        return session;
    }

    /**
     * 为用户创建新会话。
     */
    private SessionRecord createSession(String userId) {
        String newId = UUID.randomUUID().toString();
        SessionRecord session = new SessionRecord(newId);
        userSessions
                .computeIfAbsent(userId, k -> new ConcurrentHashMap<>())
                .put(newId, session);
        return session;
    }

    /**
     * 内部会话状态：对话历史 + 过期时间。
     */
    private static class SessionRecord {
        final String id;
        final List<String> history = new ArrayList<>();
        volatile Instant lastAccess;

        SessionRecord(String id) {
            this.id = id;
            this.lastAccess = Instant.now();
        }

        void touch() {
            this.lastAccess = Instant.now();
        }

        boolean isExpired() {
            return Instant.now().isAfter(lastAccess.plusSeconds(SESSION_TTL_SECONDS));
        }
    }
}
