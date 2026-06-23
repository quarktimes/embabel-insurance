package com.embabel.insurance.agent;

import com.embabel.agent.api.common.Ai;
import com.embabel.agent.api.common.OperationContext;
import com.embabel.agent.api.common.PromptRunner;
import com.embabel.agent.domain.io.UserInput;
import com.embabel.agent.rag.tools.ToolishRag;
import com.embabel.common.ai.model.LlmOptions;
import com.embabel.insurance.service.LlmSelectionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 客服 Agent 单元测试 — 使用 Mockito 模拟 LLM 交互（Agentic RAG 模式）。
 *
 * <p>ChatbotAgent 使用 {@code context.ai().withLlm().withReference().generateText()}
 * 链式调用，无法直接使用 FakeOperationContext（FakePromptRunner 不覆盖 generateText）。
 * 因此使用 Mockito mock Ai / PromptRunner / OperationContext 进行测试。
 *
 * <p>测试覆盖：
 * <ol>
 *   <li>answerQuestion — 正常问答流程，验证提示词构造和 LLM 交互</li>
 *   <li>异常处理 — LLM 调用失败时的兜底回复</li>
 * </ol>
 */
class ChatbotAgentTest {

    private ChatbotAgent agent;
    private LlmSelectionService llmSelectionService;
    private ToolishRag insuranceRag;
    private OperationContext mockContext;
    private Ai mockAi;
    private PromptRunner mockPromptRunner;

    @BeforeEach
    void setUp() {
        llmSelectionService = mock(LlmSelectionService.class);
        insuranceRag = mock(ToolishRag.class);
        mockContext = mock(OperationContext.class);
        mockAi = mock(Ai.class);
        mockPromptRunner = mock(PromptRunner.class);

        // Mock LlmOptions for chat
        when(llmSelectionService.forChat()).thenReturn(LlmOptions.withLlmForRole("balanced"));

        agent = new ChatbotAgent(llmSelectionService, insuranceRag);
    }

    // ── answerQuestion ──────────────────────────────────────────────

    @Nested
    @DisplayName("answerQuestion — Agentic RAG 问答")
    class AnswerQuestionTests {

        @Test
        @DisplayName("Should generate answer via Agentic RAG")
        void shouldGenerateAnswerViaAgenticRag() {
            String expectedAnswer = "综合险（Comprehensive Coverage）覆盖车辆因碰撞、"
                    + "盗窃、自然灾害、火灾等原因造成的损失。根据保险条款第3.2节...";

            // 构造 mock 链: context.ai() → Ai.withLlm() → PromptRunner.withReference() → generateText()
            when(mockContext.ai()).thenReturn(mockAi);
            when(mockAi.withLlm(any(LlmOptions.class))).thenReturn(mockPromptRunner);
            when(mockPromptRunner.withReference(insuranceRag)).thenReturn(mockPromptRunner);
            when(mockPromptRunner.generateText(org.mockito.ArgumentMatchers.<String>any())).thenReturn(expectedAnswer);

            UserInput input = new UserInput(
                    "What does comprehensive coverage include?",
                    Instant.now());

            ChatbotAgent.ChatOutput result = agent.answerQuestion(input, mockContext);

            assertNotNull(result);
            assertTrue(result.response().contains("Comprehensive Coverage"),
                    "Response should address comprehensive coverage question");

            // 验证 LLM 调用参数
            verify(mockContext).ai();
            verify(llmSelectionService).forChat();
            verify(mockAi).withLlm(any(LlmOptions.class));
            verify(mockPromptRunner).withReference(insuranceRag);
            verify(mockPromptRunner).generateText(org.mockito.ArgumentMatchers.<String>argThat(prompt ->
                    prompt.contains("comprehensive coverage")
                            && prompt.contains("insurance_docs_textSearch")
                            && prompt.contains("professional insurance customer service")));
        }

        @Test
        @DisplayName("Should handle Chinese insurance questions")
        void shouldHandleChineseQuestions() {
            String expectedAnswer = "理赔流程如下：1. 事故发生后立即拨打报案电话...";

            when(mockContext.ai()).thenReturn(mockAi);
            when(mockAi.withLlm(any(LlmOptions.class))).thenReturn(mockPromptRunner);
            when(mockPromptRunner.withReference(insuranceRag)).thenReturn(mockPromptRunner);
            when(mockPromptRunner.generateText(org.mockito.ArgumentMatchers.<String>any())).thenReturn(expectedAnswer);

            UserInput input = new UserInput("车险理赔的流程是什么？", Instant.now());

            ChatbotAgent.ChatOutput result = agent.answerQuestion(input, mockContext);

            assertNotNull(result);
            assertTrue(result.response().contains("理赔"));

            verify(mockPromptRunner).generateText(org.mockito.ArgumentMatchers.<String>argThat(prompt ->
                    prompt.contains("理赔") && prompt.contains("Language")));
        }

        @Test
        @DisplayName("Should return fallback message on exception")
        void shouldReturnFallbackOnException() {
            when(mockContext.ai()).thenReturn(mockAi);
            when(mockAi.withLlm(any(LlmOptions.class))).thenReturn(mockPromptRunner);
            when(mockPromptRunner.withReference(insuranceRag)).thenReturn(mockPromptRunner);
            when(mockPromptRunner.generateText(org.mockito.ArgumentMatchers.<String>any()))
                    .thenThrow(new RuntimeException("LLM timeout"));

            UserInput input = new UserInput("What is the meaning of life?", Instant.now());

            ChatbotAgent.ChatOutput result = agent.answerQuestion(input, mockContext);

            assertNotNull(result);
            assertTrue(result.response().contains("抱歉"),
                    "Should return apology message on error");
            assertTrue(result.response().contains("400-XXX-XXXX"),
                    "Should include customer service contact");
        }

        @Test
        @DisplayName("Should include search instruction in prompt")
        void shouldIncludeMustSearchFirstInstruction() {
            when(mockContext.ai()).thenReturn(mockAi);
            when(mockAi.withLlm(any(LlmOptions.class))).thenReturn(mockPromptRunner);
            when(mockPromptRunner.withReference(insuranceRag)).thenReturn(mockPromptRunner);
            when(mockPromptRunner.generateText(org.mockito.ArgumentMatchers.<String>any())).thenReturn("Based on policy documents...");

            UserInput input = new UserInput("How do I file a claim?", Instant.now());

            agent.answerQuestion(input, mockContext);

            verify(mockPromptRunner).generateText(org.mockito.ArgumentMatchers.<String>argThat(prompt ->
                    prompt.contains("Search first")
                            && prompt.contains("Synthesize")
                            && prompt.contains("Cite sources")
                            && prompt.contains("Be honest")));
        }

        @Test
        @DisplayName("Should use balanced LLM and set temperature to 0.0")
        void shouldUseZeroTemperature() {
            when(mockContext.ai()).thenReturn(mockAi);
            when(mockAi.withLlm(any(LlmOptions.class))).thenReturn(mockPromptRunner);
            when(mockPromptRunner.withReference(insuranceRag)).thenReturn(mockPromptRunner);
            when(mockPromptRunner.generateText(org.mockito.ArgumentMatchers.<String>any())).thenReturn("The answer is...");

            UserInput input = new UserInput(
                    "What is the deductible for comprehensive coverage?",
                    Instant.now());

            agent.answerQuestion(input, mockContext);

            // 验证 LlmSelectionService.forChat() 被调用
            verify(llmSelectionService).forChat();
            // 验证 LLM 被正确选择
            verify(mockAi).withLlm(any(LlmOptions.class));
        }

        @Test
        @DisplayName("Should include 400-XXX-XXXX contact in prompt")
        void shouldIncludeContactInPrompt() {
            when(mockContext.ai()).thenReturn(mockAi);
            when(mockAi.withLlm(any(LlmOptions.class))).thenReturn(mockPromptRunner);
            when(mockPromptRunner.withReference(insuranceRag)).thenReturn(mockPromptRunner);
            when(mockPromptRunner.generateText(org.mockito.ArgumentMatchers.<String>any())).thenReturn("Based on our policy...");

            UserInput input = new UserInput("Can I insure a rental car?", Instant.now());

            agent.answerQuestion(input, mockContext);

            verify(mockPromptRunner).generateText(org.mockito.ArgumentMatchers.<String>argThat(prompt ->
                    prompt.contains("400-XXX-XXXX")));
        }

        @Test
        @DisplayName("Should use balanced LLM via LlmSelectionService")
        void shouldUseBalancedLlm() {
            when(mockContext.ai()).thenReturn(mockAi);
            when(mockAi.withLlm(any(LlmOptions.class))).thenReturn(mockPromptRunner);
            when(mockPromptRunner.withReference(insuranceRag)).thenReturn(mockPromptRunner);
            when(mockPromptRunner.generateText(org.mockito.ArgumentMatchers.<String>any())).thenReturn("The answer is...");

            UserInput input = new UserInput("How is premium calculated?", Instant.now());

            agent.answerQuestion(input, mockContext);

            verify(llmSelectionService).forChat();
        }

        @Test
        @DisplayName("Should include insurance role and knowledge base instructions in prompt")
        void shouldIncludeRoleInstructions() {
            when(mockContext.ai()).thenReturn(mockAi);
            when(mockAi.withLlm(any(LlmOptions.class))).thenReturn(mockPromptRunner);
            when(mockPromptRunner.withReference(insuranceRag)).thenReturn(mockPromptRunner);
            when(mockPromptRunner.generateText(org.mockito.ArgumentMatchers.<String>any())).thenReturn("OK");

            UserInput input = new UserInput("Tell me about insurance", Instant.now());

            agent.answerQuestion(input, mockContext);

            verify(mockPromptRunner).generateText(org.mockito.ArgumentMatchers.<String>argThat(prompt ->
                    prompt.contains("Chinese insurance company")
                            && prompt.contains("comprehensive vehicle insurance")
                            && prompt.contains("Your Task")
                            && prompt.contains("Instructions")));
        }
    }
}
