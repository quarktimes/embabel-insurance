package com.embabel.insurance.controller;

import com.embabel.insurance.dto.response.AssistantResponse;
import com.embabel.insurance.service.AssistantService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 统一 AI 助手对话入口。
 *
 * <p>接收用户自然语言输入，内部进行意图分类，自动路由到核保、理赔、保单查询或 AI 客服。
 * 返回结构化 JSON，前端可按 type 渲染不同交互卡片。
 */
@RestController
@RequestMapping("/api/assistant")
@Tag(name = "Assistant", description = "统一 AI 助手 — 自然语言输入，自动路由到核保/理赔/客服/查询")
@SecurityRequirement(name = "basicAuth")
public class AssistantController {

    private static final Logger logger = LoggerFactory.getLogger(AssistantController.class);

    private final AssistantService assistantService;

    public AssistantController(AssistantService assistantService) {
        this.assistantService = assistantService;
    }

    @Operation(summary = "发送助手消息",
            description = "统一入口。发送自然语言，系统自动识别意图（核保/理赔/保单查询/客服问答），返回结构化结果。首次调用无需 sessionId。")
    @ApiResponse(responseCode = "200", description = "结构化响应",
            content = @Content(schema = @Schema(implementation = AssistantResponse.class)))
    @PostMapping
    public ResponseEntity<AssistantResponse> handleMessage(
            @RequestBody Map<String, String> body,
            @Parameter(description = "会话 ID（首次不用传）")
            @RequestParam(required = false) String sessionId) {

        String message = body.getOrDefault("message", "");
        if (message.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String userId = auth.getName();

        logger.info("Assistant request: user={}, session={}, msg={}",
                userId, sessionId, truncate(message, 60));

        AssistantResponse response = assistantService.handleMessage(userId, message, sessionId);

        return ResponseEntity.ok(response);
    }

    @Operation(summary = "清除会话", description = "清除指定助手会话")
    @DeleteMapping("/sessions/{sessionId}")
    public ResponseEntity<Void> clearSession(@PathVariable String sessionId) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assistantService.clearSession(auth.getName(), sessionId);
        return ResponseEntity.noContent().build();
    }

    private static String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
