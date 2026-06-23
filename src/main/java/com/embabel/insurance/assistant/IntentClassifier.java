package com.embabel.insurance.assistant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 意图分类器，通过关键词匹配将用户输入路由到对应业务模块。
 *
 * <p>Phase 1 使用关键词匹配快速分类，后续可升级为 LLM 分类以处理更复杂的场景。
 * 分类规则：<ul>
 *   <li>含核保/投保/报价等关键词 → {@link Intent#UNDERWRITING}</li>
 *   <li>含理赔/事故/索赔等关键词 → {@link Intent#CLAIMS}</li>
 *   <li>含保单/查询/我的保险等关键词 → {@link Intent#POLICY_QUERY}</li>
 *   <li>其他 → {@link Intent#CHAT}（走 AI 客服 RAG）</li>
 * </ul>
 */
@Component
public class IntentClassifier {

    private static final Logger logger = LoggerFactory.getLogger(IntentClassifier.class);

    private static final List<String> UNDERWRITING_KEYWORDS = List.of(
            "投保", "核保", "报价", "保费", "车险", "上保险",
            "insure", "underwriting", "quote", "premium"
    );

    private static final List<String> CLAIMS_KEYWORDS = List.of(
            "理赔", "事故", "索赔", "赔偿", "出险",
            "刮蹭", "碰撞", "追尾", "报保险",
            "claim", "accident", "collision"
    );

    private static final List<String> POLICY_KEYWORDS = List.of(
            "保单", "查询", "我的保险", "查看保单", "续保",
            "policy", "my policies"
    );

    /**
     * 从用户消息中识别意图。
     *
     * @param message 用户原始输入
     * @return 识别到的意图，永不返回 null
     */
    public Intent classify(String message) {
        if (message == null || message.isBlank()) {
            return Intent.CHAT;
        }

        String lower = message.toLowerCase();

        // 优先匹配精确的操作命令
        if (lower.startsWith("view_details")) {
            return Intent.VIEW_DETAILS;
        }

        // 按分数规则匹配，而非命中即返回，避免歧义
        int uwScore = countKeywords(lower, UNDERWRITING_KEYWORDS);
        int claimScore = countKeywords(lower, CLAIMS_KEYWORDS);
        int policyScore = countKeywords(lower, POLICY_KEYWORDS);

        // 得分相同按优先级：核保 > 理赔 > 保单查询 > 客服
        Intent result = Intent.CHAT;
        int maxScore = 0;

        if (uwScore > maxScore) { maxScore = uwScore; result = Intent.UNDERWRITING; }
        if (claimScore > maxScore) { maxScore = claimScore; result = Intent.CLAIMS; }
        if (policyScore > maxScore) { maxScore = policyScore; result = Intent.POLICY_QUERY; }

        logger.debug("Intent classified: {} (scores: uw={}, claim={}, policy={})",
                result, uwScore, claimScore, policyScore);

        return result;
    }

    /**
     * 统计消息中包含的关键词数量。
     */
    private static int countKeywords(String lowerMsg, List<String> keywords) {
        int count = 0;
        for (String kw : keywords) {
            if (lowerMsg.contains(kw)) {
                count++;
            }
        }
        return count;
    }
}
