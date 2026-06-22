package com.embabel.insurance.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * 聊天回复 DTO — sessionId 由服务端管理。
 * 客户端需保存首次返回的 sessionId，后续请求携带该 ID 以延续多轮对话。
 */
@Schema(description = "AI 聊天回复")
public class ChatResponse {

    @Schema(description = "AI 回复内容")
    private String response;

    @Schema(description = "会话 ID（首次对话时创建，后续请求需携带此 ID）")
    private String sessionId;

    @Schema(description = "是否为新会话")
    private boolean newSession;

    @Schema(description = "回复时间戳")
    private LocalDateTime timestamp;

    public ChatResponse() {}

    public ChatResponse(String response, String sessionId, boolean newSession, LocalDateTime timestamp) {
        this.response = response;
        this.sessionId = sessionId;
        this.newSession = newSession;
        this.timestamp = timestamp;
    }

    public String getResponse() { return response; }
    public void setResponse(String response) { this.response = response; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public boolean isNewSession() { return newSession; }
    public void setNewSession(boolean newSession) { this.newSession = newSession; }
    public LocalDateTime getTimestamp() { return timestamp; }
    public void setTimestamp(LocalDateTime timestamp) { this.timestamp = timestamp; }
}