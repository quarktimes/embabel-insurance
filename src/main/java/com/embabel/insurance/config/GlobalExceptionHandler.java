package com.embabel.insurance.config;

import com.embabel.insurance.dto.response.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器。
 *
 * <p>将所有 Controller 抛出的异常转换为统一的 {@link ErrorResponse} 格式，
 * 避免技术细节泄漏给前端，同时保持错误信息对用户友好。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 参数错误 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException e) {
        String msg = e.getMessage();
        if (msg != null && msg.contains("unauthorized")) {
            return error("UNAUTHORIZED_COMMAND", "输入包含未授权的指令。", msg, HttpStatus.UNPROCESSABLE_ENTITY);
        }
        return error("INVALID_INPUT", "输入参数有误：" + friendlyMessage(msg), msg, HttpStatus.UNPROCESSABLE_ENTITY);
    }

    /** 运行时业务异常 */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ErrorResponse> handleRuntime(RuntimeException e) {
        String msg = e.getMessage();
        if (msg == null) {
            return error("INTERNAL_ERROR", "系统繁忙，请稍后重试。", null, HttpStatus.INTERNAL_SERVER_ERROR);
        }

        // 找不到资源
        if (msg.contains("not found") || msg.contains("未找到")) {
            return error("NOT_FOUND", "未找到相关信息，请检查输入是否正确。", msg, HttpStatus.NOT_FOUND);
        }

        // 业务状态不允许
        if (msg.contains("not approved") || msg.contains("expired")) {
            return error("INVALID_STATE", "当前状态不允许此操作，请检查后再试。", msg, HttpStatus.BAD_REQUEST);
        }

        // Agent 超时
        if (msg.contains("timed out") || msg.contains("timeout")) {
            return error("TIMEOUT", "处理超时，请稍后重试。", msg, HttpStatus.REQUEST_TIMEOUT);
        }

        // Agent 流程失败
        if (msg.contains("Agent process")) {
            return error("AGENT_ERROR", "业务处理遇到问题，请稍后重试或联系客服。", msg, HttpStatus.INTERNAL_SERVER_ERROR);
        }

        return error("INTERNAL_ERROR", "系统繁忙，请稍后重试。", msg, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    /** 通用兜底 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception e) {
        logger.error("Unhandled exception", e);
        return error("INTERNAL_ERROR", "系统繁忙，请稍后重试。",
                e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
    }

    // ── 辅助方法 ──

    private ResponseEntity<ErrorResponse> error(String code, String message, String detail, HttpStatus status) {
        if (detail != null && !detail.equals(message)) {
            logger.warn("{}: {}", code, detail);
        }
        return ResponseEntity.status(status).body(new ErrorResponse(code, message, detail));
    }

    /**
     * 将异常消息转为友好提示。默认直接使用技术消息作为友好消息，
     * 真正的友好提示在调用方通过 message 参数传入。
     */
    private static String friendlyMessage(String msg) {
        if (msg == null) return "";
        if (msg.contains("Customer not found")) return "找不到客户，请确认用户ID是否正确。";
        if (msg.contains("Vehicle not found")) return "找不到车辆信息，请确认车牌号是否正确。";
        if (msg.contains("Policy not found")) return "找不到保单，请确认保单号是否正确。";
        if (msg.contains("Quote not found")) return "找不到报价单，请确认报价单ID是否正确。";
        if (msg.contains("Claim not found")) return "找不到理赔单，请确认理赔单号是否正确。";
        if (msg.contains("Duplicate")) return "该理赔申请已存在，请勿重复提交。";
        return msg;
    }
}
