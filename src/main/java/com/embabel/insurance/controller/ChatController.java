package com.embabel.insurance.controller;

import com.embabel.insurance.dto.request.ChatRequest;
import com.embabel.insurance.dto.response.ChatResponse;
import com.embabel.insurance.service.ChatService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

/**
 * AI 客服对话接口 — 会话完全由服务端管理。
 *
 * <p>客户端只需发送消息。首次调用时服务端自动创建会话并返回 sessionId；
 * 客户端需保存该 sessionId，后续调用时作为查询参数传入以延续多轮对话。
 */
@RestController
@RequestMapping("/api/chat")
@Tag(name = "Chat", description = "AI 保险客服对话接口（会话由服务端管理）")
@SecurityRequirement(name = "basicAuth")
public class ChatController {

    private static final Logger logger = LoggerFactory.getLogger(ChatController.class);

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @Operation(
            summary = "发送聊天消息",
            description = "向 AI 保险助手发送消息。首次调用时无需传 sessionId，服务端会自动创建会话并返回 sessionId；后续调用请携带返回的 sessionId 以延续多轮对话。"
    )
    @ApiResponse(responseCode = "200", description = "AI 回复",
            content = @Content(schema = @Schema(implementation = ChatResponse.class)))
    @PostMapping
    public ResponseEntity<ChatResponse> chat(
            @Valid @RequestBody ChatRequest request,
            @Parameter(description = "会话 ID（首次调用时不需要传，后续调用请携带上一次返回的 sessionId）")
            @RequestParam(required = false) String sessionId) {

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String userId = auth.getName();

        logger.info("Processing chat for user: {}, session: {}", userId, sessionId);

        ChatResponse response = chatService.processChat(
                userId,
                request.getMessage(),
                sessionId
        );

        return ResponseEntity.ok(response);
    }

    @Operation(summary = "清除会话", description = "清除当前用户的指定聊天会话")
    @ApiResponse(responseCode = "204", description = "会话已清除")
    @DeleteMapping("/sessions/{sessionId}")
    public ResponseEntity<Void> clearSession(
            @Parameter(description = "会话 ID") @PathVariable String sessionId) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String userId = auth.getName();
        chatService.clearSession(userId, sessionId);
        return ResponseEntity.noContent().build();
    }
}