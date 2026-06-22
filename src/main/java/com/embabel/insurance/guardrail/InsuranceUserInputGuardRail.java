package com.embabel.insurance.guardrail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 用户输入护栏，校验用户输入的安全性。
 *
 * <p>检查项：
 * <ul>
 *   <li>未授权指令（提示注入攻击）</li>
 *   <li>敏感信息泄露尝试</li>
 *   <li>与保险无关的话题内容</li>
 * </ul>
 */
public class InsuranceUserInputGuardRail {

    private static final Logger logger = LoggerFactory.getLogger(InsuranceUserInputGuardRail.class);

    /** 未授权指令正则模式 */
    private static final List<Pattern> UNAUTHORIZED_PATTERNS = List.of(
            Pattern.compile("(?i)(ignore\\s+all\\s+rules|auto\\s+approve|bypass\\s+review|override\\s+system|skip\\s+verification)"),
            Pattern.compile("(?i)(ignore\\s+previous|forget\\s+all|disregard\\s+instructions)"),
            Pattern.compile("(?i)(system\\s+prompt|admin\\s+mode|developer\\s+mode)"),
            Pattern.compile("(?i)(secret\\s+password|hidden\\s+command|backdoor)"),
            Pattern.compile("(?i)(DROP\\s+TABLE|DELETE\\s+FROM|INSERT\\s+INTO|UPDATE\\s+.+|ALTER\\s+TABLE|TRUNCATE|EXEC\\s*\\(|EXECUTE\\s*\\()")
    );

    /** 与保险无关的话题关键词 */
    private static final List<String> OFF_TOPIC_KEYWORDS = List.of(
            "stock", "weather", "news", "sports", "politics",
            "gambling", "betting", "adult", "illegal", "weapon"
    );

    public record ValidationResult(boolean valid, List<ValidationError> errors) {
        public static ValidationResult VALID = new ValidationResult(true, List.of());
    }

    public record ValidationError(String code, String message, ValidationSeverity severity, String location) {}

    public enum ValidationSeverity {
        INFO, WARN, ERROR, CRITICAL
    }

    public String getName() {
        return "InsuranceUserInputGuardRail";
    }

    public String getDescription() {
        return "Validates user input for insurance chatbot - checks for unauthorized commands and off-topic content";
    }

    public ValidationResult validate(String input) {
        logger.debug("Validating user input: {}", input);
        
        var errors = new ArrayList<ValidationError>();
        
        // 检查未授权指令
        for (Pattern pattern : UNAUTHORIZED_PATTERNS) {
            if (pattern.matcher(input).find()) {
                errors.add(new ValidationError(
                        "unauthorized-command",
                        "Input contains unauthorized commands",
                        ValidationSeverity.CRITICAL,
                        null
                ));
                break;
            }
        }
        
        // 检查提示注入尝试
        if (containsPromptInjection(input)) {
            errors.add(new ValidationError(
                    "prompt-injection",
                    "Potential prompt injection detected",
                    ValidationSeverity.CRITICAL,
                    null
            ));
        }
        
        // 检查无关话题
        String inputLower = input.toLowerCase();
        for (String keyword : OFF_TOPIC_KEYWORDS) {
            if (inputLower.contains(keyword)) {
                errors.add(new ValidationError(
                        "off-topic",
                        "Input appears to be off-topic for insurance services",
                        ValidationSeverity.WARN,
                        null
                ));
                break;
            }
        }
        
        if (errors.isEmpty()) {
            return ValidationResult.VALID;
        }
        
        boolean isValid = errors.stream()
                .noneMatch(e -> e.severity() == ValidationSeverity.CRITICAL);
        
        return new ValidationResult(isValid, errors);
    }

    public boolean isCriticalViolation(String input) {
        ValidationResult result = validate(input);
        return result.errors().stream()
                .anyMatch(e -> e.severity() == ValidationSeverity.CRITICAL);
    }

    private boolean containsPromptInjection(String input) {
        String[] injectionPatterns = {
                "```system",
                "```instructions",
                "you are now",
                "pretend to be",
                "ignore previous",
                "disregard all"
        };
        
        String inputLower = input.toLowerCase();
        for (String pattern : injectionPatterns) {
            if (inputLower.contains(pattern)) {
                return true;
            }
        }
        
        // Check for base64 encoded commands
        if (input.contains("base64") || input.matches(".*[A-Za-z0-9+/]{40,}={0,2}.*")) {
            return true;
        }
        
        return false;
    }
}