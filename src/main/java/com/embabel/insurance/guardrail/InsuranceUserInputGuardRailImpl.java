package com.embabel.insurance.guardrail;

import com.embabel.agent.api.validation.guardrails.UserInputGuardRail;
import com.embabel.agent.core.Blackboard;
import com.embabel.common.core.validation.ValidationError;
import com.embabel.common.core.validation.ValidationResult;
import com.embabel.common.core.validation.ValidationSeverity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 用户输入护栏的 embabel 框架实现，实现 {@link UserInputGuardRail} 接口。
 *
 * <p>校验用户输入的安全性，检测未授权指令、提示注入和无关话题。
 */
public class InsuranceUserInputGuardRailImpl implements UserInputGuardRail {

    private static final Logger logger = LoggerFactory.getLogger(InsuranceUserInputGuardRailImpl.class);

    /** 未授权指令正则模式 */
    private static final List<Pattern> UNAUTHORIZED_PATTERNS = List.of(
            Pattern.compile("(?i)(ignore\\s+all\\s+rules|auto\\s+approve|bypass\\s+review|override\\s+system|skip\\s+verification)"),
            Pattern.compile("(?i)(ignore\\s+previous|forget\\s+all|disregard\\s+instructions)"),
            Pattern.compile("(?i)(system\\s+prompt|admin\\s+mode|developer\\s+mode)"),
            Pattern.compile("(?i)(SQL\\s+injection|XSS|script\\s+injection)"),
            Pattern.compile("(?i)(secret\\s+password|hidden\\s+command|backdoor)")
    );

    /** 与保险无关的话题关键词 */
    private static final List<String> OFF_TOPIC_KEYWORDS = List.of(
            "stock", "weather", "news", "sports", "politics",
            "gambling", "adult", "illegal", "weapon"
    );

    @Override
    public String getName() {
        return "InsuranceUserInputGuardRail";
    }

    @Override
    public String getDescription() {
        return "Validates user input for insurance chatbot - checks for unauthorized commands and off-topic content";
    }

    @Override
    public ValidationResult validate(String input, Blackboard blackboard) {
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
                        ValidationSeverity.WARNING,
                        null
                ));
                break;
            }
        }
        
        if (errors.isEmpty()) {
            return ValidationResult.Companion.getVALID();
        }
        
        return new ValidationResult(false, errors);
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
        
        // 检查 Base64 编码的指令
        if (input.contains("base64") || input.matches(".*[A-Za-z0-9+/]{50,}={0,2}.*")) {
            return true;
        }
        
        return false;
    }
}