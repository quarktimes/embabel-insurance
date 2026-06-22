package com.embabel.insurance.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 统一助手响应结构。
 *
 * <p>包含人类可读的文本、结构化业务数据和推荐操作，支持前端按 type 渲染不同卡片。
 * type 字段决定了前端如何渲染：chat / underwriting_result / claim_result / policy_list / error。
 */
@Schema(description = "AI 助手的统一结构化响应")
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AssistantResponse {

    @Schema(description = "响应类型", example = "underwriting_result",
            allowableValues = {"chat", "underwriting_result", "claim_result", "policy_list", "error"})
    private String type;

    @Schema(description = "会话 ID")
    private String sessionId;

    @Schema(description = "是否为新会话")
    private boolean newSession;

    @Schema(description = "人类可读的文本消息（前端可做打字机效果）")
    private String text;

    @Schema(description = "结构化业务数据，按 type 不同结构不同")
    private Map<String, Object> data;

    @Schema(description = "推荐操作的按钮列表")
    private List<Action> actions;

    @Schema(description = "响应时间戳")
    private LocalDateTime timestamp;

    public AssistantResponse() {}

    // ---- 工厂方法 ----

    public static AssistantResponse chat(String sessionId, boolean newSession, String text) {
        return new AssistantResponse("chat", sessionId, newSession, text, null, null, LocalDateTime.now());
    }

    public static AssistantResponse underwritingResult(String sessionId, String text,
                                                       Map<String, Object> data,
                                                       List<Action> actions) {
        return new AssistantResponse("underwriting_result", sessionId, false, text, data, actions, LocalDateTime.now());
    }

    public static AssistantResponse claimResult(String sessionId, String text,
                                                Map<String, Object> data,
                                                List<Action> actions) {
        return new AssistantResponse("claim_result", sessionId, false, text, data, actions, LocalDateTime.now());
    }

    public static AssistantResponse policyList(String sessionId, String text,
                                               Map<String, Object> data) {
        return new AssistantResponse("policy_list", sessionId, false, text, data, null, LocalDateTime.now());
    }

    public static AssistantResponse error(String sessionId, String text) {
        return new AssistantResponse("error", sessionId, false, text, null, null, LocalDateTime.now());
    }

    // ---- 内部 Action 记录 ----

    /**
     * 前端可渲染为按钮的操作。{@link #action} 为操作标识，{@link #payload} 为携带的参数。
     */
    @Schema(description = "推荐操作按钮")
    public record Action(
            @Schema(description = "按钮显示文本", example = "立即支付") String label,
            @Schema(description = "操作标识", example = "pay") String action,
            @Schema(description = "操作参数") Map<String, Object> payload) {}

    // ---- 全参构造 ----

    public AssistantResponse(String type, String sessionId, boolean newSession,
                             String text, Map<String, Object> data,
                             List<Action> actions, LocalDateTime timestamp) {
        this.type = type;
        this.sessionId = sessionId;
        this.newSession = newSession;
        this.text = text;
        this.data = data;
        this.actions = actions;
        this.timestamp = timestamp;
    }

    // ---- getters / setters ----

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public boolean isNewSession() { return newSession; }
    public void setNewSession(boolean newSession) { this.newSession = newSession; }
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public Map<String, Object> getData() { return data; }
    public void setData(Map<String, Object> data) { this.data = data; }
    public List<Action> getActions() { return actions; }
    public void setActions(List<Action> actions) { this.actions = actions; }
    public LocalDateTime getTimestamp() { return timestamp; }
    public void setTimestamp(LocalDateTime timestamp) { this.timestamp = timestamp; }
}
