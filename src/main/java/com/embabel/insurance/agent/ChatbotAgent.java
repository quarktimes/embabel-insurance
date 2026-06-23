package com.embabel.insurance.agent;

import com.embabel.agent.api.annotation.*;
import com.embabel.agent.api.common.OperationContext;
import com.embabel.agent.api.common.PlannerType;
import com.embabel.agent.core.AgentProcess;
import com.embabel.agent.domain.io.UserInput;
import com.embabel.agent.rag.tools.ToolishRag;
import com.embabel.common.ai.model.LlmOptions;
import com.embabel.agent.api.common.StuckHandler;
import com.embabel.agent.api.common.StuckHandlerResult;
import com.embabel.agent.api.common.StuckHandlingResultCode;
import com.embabel.insurance.service.LlmSelectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 保险 AI 客服 Agent，基于 embabel 的 Agentic RAG（智能检索增强生成）机制。
 *
 * <p>工作流程：通过 {@link ToolishRag} 注入知识库检索工具，
 * 在 {@code context.ai().withReference(toolishRag)} 中注册
 * {@code insurance_docs_textSearch} 工具，让 LLM 自主决定何时检索、
 * 如何构造查询词、如何迭代搜索，最终从文档片段中综合生成答案。
 *
 * <p>这是真正的 Agentic RAG 模式：LLM 自主完成“检索→阅读→综合→回答”的完整闭环。
 */
@Agent(
        description = "保险 AI 客服 Agent，使用 Agentic RAG 回答客户问题。"
                + "通过 insurance_docs 文本搜索工具从保险条款、理赔指南和 FAQ 中"
                + "检索相关信息后再作答。",
        planner = PlannerType.UTILITY
)
@Component
public class ChatbotAgent implements StuckHandler {

    private static final Logger logger = LoggerFactory.getLogger(ChatbotAgent.class);

    private final LlmSelectionService llmSelectionService;
    private final ToolishRag insuranceRag;

    public ChatbotAgent(LlmSelectionService llmSelectionService, ToolishRag insuranceRag) {
        this.llmSelectionService = llmSelectionService;
        this.insuranceRag = insuranceRag;
    }

    /**
     * 聊天输入记录，供 ChatService 调用时使用。
     */
    public record ChatInput(String message, String sessionId) {}

    /**
     * 聊天输出记录，包含生成的回答及引用来源。
     */
    public record ChatOutput(String response, List<DocumentReference> sources) {}

    /**
     * 回答中引用的文档片段信息。
     */
    public record DocumentReference(String documentTitle, String sectionTitle, String excerpt, double score) {}

    /**
     * 使用 Agentic RAG 回答用户的保险相关问题。
     *
     * <p>通过 {@code withReference()} 挂载 {@link ToolishRag}，
     * LLM 可调用 {@code insurance_docs_textSearch} 检索知识库中的相关文档片段，
     * 再综合生成最终答案。
     *
     * @param userInput 用户提问内容（可能包含 ChatService 追加的 userId 前缀）
     * @param context   操作上下文，提供对 AI（LLM）接口的访问
     * @return 生成的回答及引用来源
     */
    @AchievesGoal(
            description = "answer_question",
            value = 1.0,
            tags = {"chat", "rag", "faq"},
            examples = {
                    "What is comprehensive coverage?",
                    "How do I file a claim?",
                    "理赔流程是什么？",
                    "保费如何计算？"
            }
    )
    @Action(description = "使用知识库回答用户的保险问题。"
            + "始终先通过 insurance_docs 的 textSearch 工具检索，"
            + "再基于检索结果综合生成完整答案。")
    public ChatOutput answerQuestion(UserInput userInput, OperationContext context) {
        String message = userInput.getContent().trim();

        logger.info("Answering question via Agentic RAG: {}", message);

        LlmOptions chatOptions = llmSelectionService.forChat();

        try {
            String answer = context.ai()
                    .withLlm(chatOptions.withTemperature(0.0))
                    .withReference(insuranceRag)
                    .generateText("""
                            You are a professional insurance customer service agent for a \
                            Chinese insurance company specializing in comprehensive vehicle \
                            insurance.

                            ## Your Task
                            Answer the user's question accurately using information from the \
                            knowledge base.

                            ## Instructions
                            1. **Search at most ONCE**: Use `insurance_docs_textSearch` to find \
                            relevant information. Do NOT search again after getting results — \
                            answer with what you have.
                            2. **Synthesize answer**: Based on the search results, provide a \
                            clear, accurate answer that directly addresses the user's question.
                            3. **Cite sources**: Reference the document name and section when \
                            presenting information from the knowledge base.
                            4. **Be honest**: If the knowledge base doesn't contain enough \
                            information, tell the user honestly and suggest contacting customer \
                            service at 400-XXX-XXXX.
                            5. **Language**: Respond in the same language as the user's question.
                            6. **CRITICAL — NO search loops**: Search once, then answer. \
                            Do not search multiple times or try different query formulations. \
                            If the first search finds nothing useful, tell the user and suggest \
                            contacting customer service.

                            ## User Question
                            %s
                            """.formatted(message));

            logger.info("Generated answer via Agentic RAG (length={})", answer.length());

            return new ChatOutput(answer, List.of());
        } catch (Exception e) {
            logger.error("Error generating answer via Agentic RAG", e);
            return new ChatOutput(
                    "抱歉，我在处理您的问题时遇到了困难。请稍后再试，或拨打客服热线400-XXX-XXXX获取帮助。",
                    List.of()
            );
        }
    }

    // ──────────────────────────────────────────────
    //  StuckHandler 实现 — 卡住时输出诊断日志
    // ──────────────────────────────────────────────

    @Override
    public StuckHandlerResult handleStuck(AgentProcess agentProcess) {
        logger.error("============================================================");
        logger.error("=== ChatbotAgent STUCK DIAGNOSTICS ===");
        if (agentProcess != null) {
            logger.error("=== Process status: {} ===", agentProcess.getStatus());
        } else {
            logger.error("=== Process status: N/A (AgentInvocation mode, no AgentProcess) ===");
        }
        logger.error("=== Likely stuck in: answerQuestion (Agentic RAG LLM call) ===");
        logger.error("=== This agent uses ToolishRag for insurance_docs_textSearch, ===");
        logger.error("=== then LLM synthesizes answer — both can hang on LLM timeout ===");
        logger.error("============================================================");

        return new StuckHandlerResult(
                "ChatbotAgent stuck — likely LLM timeout during Agentic RAG (search + generate)",
                this, StuckHandlingResultCode.NO_RESOLUTION, agentProcess);
    }
}
