package com.embabel.insurance.guardrail;

import com.embabel.agent.api.validation.guardrails.AssistantMessageGuardRail;
import com.embabel.agent.core.Blackboard;
import com.embabel.common.core.thinking.ThinkingBlock;
import com.embabel.common.core.thinking.ThinkingResponse;
import com.embabel.common.core.validation.ValidationError;
import com.embabel.common.core.validation.ValidationResult;
import com.embabel.common.core.validation.ValidationSeverity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 保险客服 LLM 回复护栏的 embabel 框架实现，实现 {@link AssistantMessageGuardRail} 接口。
 *
 * <p>对 LLM 生成的 ThinkingResponse 进行校验，包括：
 * <ul>
 *   <li>思维链内容检查</li>
 *   <li>最终结果内容检查</li>
 *   <li>敏感信息、幻觉迹象、长度异常检测</li>
 * </ul>
 */
public class InsuranceAssistantMessageGuardRailImpl implements AssistantMessageGuardRail {

    private static final Logger logger = LoggerFactory.getLogger(InsuranceAssistantMessageGuardRailImpl.class);

    /** 敏感信息关键词模式 */
    private static final List<String> SENSITIVE_PATTERNS = List.of(
            "credit card",
            "social security",
            "ssn",
            "password",
            "secret key",
            "private key"
    );

    /** 保险合规：禁止 LLM 承诺/保证赔付 */
    private static final List<String> COMPLIANCE_PROHIBITED = List.of(
            "保证理赔", "保证赔付", "保证赔偿",
            "100%赔付", "100%理赔", "百份百赔付",
            "一定赔", "一定赔付", "一定能赔",
            "包赔", "包理赔", "包赔付",
            "肯定能赔", "肯定赔", "肯定理赔",
            "绝对理赔", "绝对赔付", "绝对能赔",
            "无条件赔付", "无条件理赔",
            "承诺赔付", "承诺理赔", "承诺赔偿",
            "担保理赔", "担保赔付",
            "必赔", "必赔付"
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

    @Override
    public String getName() {
        return "InsuranceAssistantMessageGuardRail";
    }

    @Override
    public String getDescription() {
        return "Validates LLM responses for insurance chatbot - checks for sensitive information and hallucination";
    }

    @Override
    public ValidationResult validate(ThinkingResponse<?> response, Blackboard blackboard) {
        logger.debug("Validating LLM thinking response");
        
        var errors = new ArrayList<ValidationError>();
        
        // 校验思维链内容
        if (response.getThinkingBlocks() != null) {
            for (ThinkingBlock thinking : response.getThinkingBlocks()) {
                errors.addAll(validateText(thinking.getContent(), "thinking"));
            }
        }
        
        // 校验最终结果
        if (response.getResult() != null) {
            String resultText = response.getResult().toString();
            errors.addAll(validateText(resultText, "result"));
        }
        
        if (errors.isEmpty()) {
            return ValidationResult.Companion.getVALID();
        }
        
        return new ValidationResult(true, errors);
    }

    @Override
    public ValidationResult validate(String content, Blackboard blackboard) {
        logger.debug("Validating LLM response content");
        
        var errors = validateText(content, "response");
        
        if (errors.isEmpty()) {
            return ValidationResult.Companion.getVALID();
        }
        
        return new ValidationResult(true, errors);
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
                        null
                ));
            }
        }

        // 检查保险合规：禁止承诺/保证赔付
        for (String phrase : COMPLIANCE_PROHIBITED) {
            if (text.contains(phrase)) {
                errors.add(new ValidationError(
                        "compliance-prohibited-commitment",
                        "回复包含保险合规禁止的承诺表述: " + phrase,
                        ValidationSeverity.ERROR,
                        null
                ));
            }
        }

        // 检查幻觉迹象
        for (String indicator : HALLUCINATION_INDICATORS) {
            if (textLower.contains(indicator)) {
                errors.add(new ValidationError(
                        "uncertainty",
                        "Response contains uncertainty indicator: " + indicator,
                        ValidationSeverity.WARNING,
                        null
                ));
                break;
            }
        }
        
        // 检查回复过长
        if (text.length() > 5000) {
            errors.add(new ValidationError(
                    "excessive-length",
                    "Response exceeds recommended length of 5000 characters",
                    ValidationSeverity.WARNING,
                    null
            ));
        }
        
        // 检查回复过短
        if (text.length() < 10) {
            errors.add(new ValidationError(
                    "insufficient-content",
                    "Response appears to be too short",
                    ValidationSeverity.WARNING,
                    null
            ));
        }
        
        return errors;
    }
}