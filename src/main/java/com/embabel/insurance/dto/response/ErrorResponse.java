package com.embabel.insurance.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 统一错误响应。
 *
 * <p>所有 Controller 异常通过 {@link com.embabel.insurance.config.GlobalExceptionHandler}
 * 转换为本结构返回，前端直接展示 message 字段即可。
 */
@Schema(description = "统一错误响应")
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ErrorResponse {

    @Schema(description = "错误码", example = "CUSTOMER_NOT_FOUND")
    private String code;

    @Schema(description = "用户友好的错误消息", example = "未找到客户信息，请确认用户名是否正确")
    private String message;

    @Schema(description = "技术详情（仅在 debug 模式下返回）")
    private String detail;

    public ErrorResponse() {}

    public ErrorResponse(String code, String message) {
        this.code = code;
        this.message = message;
    }

    public ErrorResponse(String code, String message, String detail) {
        this.code = code;
        this.message = message;
        this.detail = detail;
    }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
}
