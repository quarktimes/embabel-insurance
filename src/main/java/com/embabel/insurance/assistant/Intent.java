package com.embabel.insurance.assistant;

/**
 * 用户意图枚举，由 {@link IntentClassifier} 从用户输入中识别。
 */
public enum Intent {
    /** 保险客服问答（RAG 知识库） */
    CHAT,
    /** 核保投保 */
    UNDERWRITING,
    /** 理赔申请 */
    CLAIMS,
    /** 保单查询 */
    POLICY_QUERY,
    /** 查看报价单详情 */
    VIEW_DETAILS,
    /** 支付保费 */
    PAYMENT
}
