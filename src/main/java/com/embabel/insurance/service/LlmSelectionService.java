package com.embabel.insurance.service;

import com.embabel.common.ai.model.LlmOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * LLM 选择服务，根据任务类型匹配合适的模型，实现成本与性能的最优平衡。
 *
 * <p>模型角色定义（在配置文件中指定具体模型名）：
 * <ul>
 *   <li>{@code fast} — 快速廉价的轻量模型，用于简单查询和检索</li>
 *   <li>{@code balanced} — 平衡模型，用于聊天、核保、理赔等常规任务</li>
 *   <li>{@code powerful} — 强力模型，用于复杂推理</li>
 * </ul>
 * <p>注：本项目当前使用纯文本 BM25 检索（未启用向量/嵌入搜索），
 * 因此 {@code embedding} 角色未在配置中定义。
 *
 * <p>也支持按复杂度评分自动选择：0-30 → fast、31-60 → balanced、61-100 → powerful。
 */
@Service
public class LlmSelectionService {

    private static final Logger logger = LoggerFactory.getLogger(LlmSelectionService.class);

    /** 模型角色常量 */
    public static final String ROLE_FAST = "fast";
    public static final String ROLE_BALANCED = "balanced";
    public static final String ROLE_POWERFUL = "powerful";
    public static final String ROLE_EMBEDDING = "embedding";

    /**
     * 简单问答任务（使用快速廉价模型）。
     */
    public LlmOptions forSimpleQuery() {
        logger.debug("Selecting LLM for simple query (fast model)");
        return LlmOptions.withLlmForRole(ROLE_FAST);
    }

    /**
     * RAG 检索任务。
     */
    public LlmOptions forRetrieval() {
        logger.debug("Selecting LLM for retrieval (fast model)");
        return LlmOptions.withLlmForRole(ROLE_FAST);
    }

    /**
     * 文档摘要任务。
     */
    public LlmOptions forSummarization() {
        logger.debug("Selecting LLM for summarization (balanced model)");
        return LlmOptions.withLlmForRole(ROLE_BALANCED);
    }

    /**
     * 复杂推理任务（使用强力模型）。
     */
    public LlmOptions forComplexReasoning() {
        logger.debug("Selecting LLM for complex reasoning (powerful model)");
        return LlmOptions.withLlmForRole(ROLE_POWERFUL);
    }

    /**
     * 核保决策任务。
     */
    public LlmOptions forUnderwriting() {
        logger.debug("Selecting LLM for underwriting (balanced model)");
        return LlmOptions.withLlmForRole(ROLE_BALANCED);
    }

    /**
     * 理赔处理任务。
     */
    public LlmOptions forClaims() {
        logger.debug("Selecting LLM for claims processing (balanced model)");
        return LlmOptions.withLlmForRole(ROLE_BALANCED);
    }

    /**
     * 通用客服对话任务。
     */
    public LlmOptions forChat() {
        logger.debug("Selecting LLM for chat (balanced model)");
        return LlmOptions.withLlmForRole(ROLE_BALANCED);
    }

    /**
     * 嵌入操作任务。
     */
    public LlmOptions forEmbedding() {
        logger.debug("Selecting LLM for embedding");
        return LlmOptions.withLlmForRole(ROLE_EMBEDDING);
    }

    /**
     * 自动选择模型。
     */
    public LlmOptions forAuto() {
        return LlmOptions.withAutoLlm();
    }

    /**
     * 按指定模型名称选择。
     */
    public LlmOptions forModel(String modelName) {
        logger.debug("Selecting specific model: {}", modelName);
        if (modelName == null || modelName.isBlank()) {
            logger.warn("Null or blank model name provided, using auto selection");
            return forAuto();
        }
        return LlmOptions.withModel(modelName);
    }

    /**
     * 按任务复杂度评分（0-100）自动选择模型。
     * <ul>
     *   <li>0-30：简单任务 → 快速模型</li>
     *   <li>31-60：中等任务 → 平衡模型</li>
     *   <li>61-100：复杂任务 → 强力模型</li>
     * </ul>
     */
    public LlmOptions forComplexity(int complexityScore) {
        if (complexityScore <= 30) {
            return forSimpleQuery();
        } else if (complexityScore <= 60) {
            return forSummarization();
        } else {
            return forComplexReasoning();
        }
    }
}