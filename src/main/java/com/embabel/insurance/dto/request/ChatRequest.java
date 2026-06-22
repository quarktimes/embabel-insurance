package com.embabel.insurance.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * 聊天请求 DTO — 会话完全由服务端管理。
 * 首次调用时服务端返回 sessionId，同一会话的后续调用只需发送消息。
 */
@Schema(description = "聊天请求（会话由服务端管理，无需传 sessionId）")
public class ChatRequest {

    @NotBlank(message = "Message is required")
    @Schema(description = "用户消息内容", example = "How do I file a car insurance claim?")
    private String message;

    public ChatRequest() {}

    public ChatRequest(String message) {
        this.message = message;
    }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
}