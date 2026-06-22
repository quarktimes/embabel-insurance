package com.embabel.insurance.config;

import com.embabel.insurance.guardrail.InsuranceAssistantMessageGuardRailImpl;
import com.embabel.insurance.guardrail.InsuranceUserInputGuardRailImpl;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Guardrail（护栏）配置，将护栏实现注册为 Spring Bean。
 *
 * <p>注册两个护栏：
 * <ul>
 *   <li>{@link InsuranceUserInputGuardRailImpl} — 用户输入校验（注入攻击、无关内容检测）</li>
 *   <li>{@link InsuranceAssistantMessageGuardRailImpl} — LLM 回复校验（敏感信息、幻觉检测）</li>
 * </ul>
 */
@Configuration
public class GuardrailConfiguration {

    @Bean
    public InsuranceUserInputGuardRailImpl insuranceUserInputGuardRail() {
        return new InsuranceUserInputGuardRailImpl();
    }

    @Bean
    public InsuranceAssistantMessageGuardRailImpl insuranceAssistantMessageGuardRail() {
        return new InsuranceAssistantMessageGuardRailImpl();
    }
}