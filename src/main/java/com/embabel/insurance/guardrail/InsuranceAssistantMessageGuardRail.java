package com.embabel.insurance.guardrail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 保险客服 LLM 回复护栏，校验回复内容的质量和安全性。
 *
 * <p>检查项：
 * <ul>
 *   <li>敏感信息泄露（信用卡、社保号、密码等）</li>
 *   <li>幻觉迹象（不确定表述）</li>
 *   <li>回复长度异常（过长或过短）</li>
 * </ul>
 */
public class InsuranceAssistantMessageGuardRail {

    private static final Logger logger = LoggerFactory.getLogger(InsuranceAssistantMessageGuardRail.class);

    /** 敏感信息关键词模式 */
    private static final List<String> SENSITIVE_PATTERNS = List.of(
            "credit card",
            "social security",
            "ssn",
            "password",
            "secret key",
            "private key"
    );

    /** 幻觉迹象关键词 */
    private static final List<String> HALLUCINATION_INDICATORS = List.of(
            "i'm not sure",
            "i cannot verify",
            "to my knowledge",
            "as far as i know",
            "might be",
            "could be",
            "possibly"
    );

    public record ValidationResult(boolean valid, List<ValidationError> errors) {
        public static ValidationResult VALID = new ValidationResult(true, List.of());
    }

    public record ValidationError(String code, String message, ValidationSeverity severity, String location) {}

    public enum ValidationSeverity {
        INFO, WARN, ERROR, CRITICAL
    }

    public String getName() {
        return "InsuranceAssistantMessageGuardRail";
    }

    public String getDescription() {
        return "Validates LLM responses for insurance chatbot - checks for sensitive information and hallucination";
    }

    public ValidationResult validate(String content) {
        logger.debug("Validating LLM response content");
        
        var errors = validateText(content, "response");
        
        if (errors.isEmpty()) {
            return ValidationResult.VALID;
        }
        
        boolean isValid = errors.stream()
                .noneMatch(e -> e.severity() == ValidationSeverity.CRITICAL);
        
        return new ValidationResult(isValid, errors);
    }

    private List<ValidationError> validateText(String text, String location) {
        var errors = new ArrayList<ValidationError>();
        
        if (text == null || text.isEmpty()) {
            return errors;
        }
        
        String textLower = text.toLowerCase();
        
        // 检查敏感信息
        for (String pattern : SENSITIVE_PATTERNS) {
            if (textLower.contains(pattern)) {
                errors.add(new ValidationError(
                        "sensitive-information",
                        "Response contains potentially sensitive information: " + pattern,
                        ValidationSeverity.ERROR,
                        location
                ));
            }
        }
        
        // 检查幻觉迹象
        for (String indicator : HALLUCINATION_INDICATORS) {
            if (textLower.contains(indicator)) {
                errors.add(new ValidationError(
                        "uncertainty",
                        "Response contains uncertainty indicator: " + indicator,
                        ValidationSeverity.WARN,
                        location
                ));
                break;
            }
        }
        
        // 检查回复过长
        if (text.length() > 5000) {
            errors.add(new ValidationError(
                    "excessive-length",
                    "Response exceeds recommended length of 5000 characters",
                    ValidationSeverity.WARN,
                    location
            ));
        }
        
        // 检查回复过短
        if (text.length() < 10) {
            errors.add(new ValidationError(
                    "insufficient-content",
                    "Response appears to be too short",
                    ValidationSeverity.WARN,
                    location
            ));
        }
        
        return errors;
    }
}