package com.embabel.insurance.service;

import com.embabel.common.ai.model.LlmOptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive unit tests for LlmSelectionService.
 * Tests model selection based on task type and complexity.
 */
class LlmSelectionServiceTest {

    private LlmSelectionService service;

    @BeforeEach
    void setUp() {
        service = new LlmSelectionService();
    }

    @Nested
    @DisplayName("Task-Based Model Selection Tests")
    class TaskBasedSelectionTests {

        @Test
        @DisplayName("Should return fast model for simple queries")
        void shouldReturnFastModelForSimpleQueries() {
            LlmOptions options = service.forSimpleQuery();
            assertNotNull(options);
            assertNotNull(options.getCriteria());
        }

        @Test
        @DisplayName("Should return fast model for retrieval tasks")
        void shouldReturnFastModelForRetrieval() {
            LlmOptions options = service.forRetrieval();
            assertNotNull(options);
            assertNotNull(options.getCriteria());
        }

        @Test
        @DisplayName("Should return balanced model for summarization")
        void shouldReturnBalancedModelForSummarization() {
            LlmOptions options = service.forSummarization();
            assertNotNull(options);
            assertNotNull(options.getCriteria());
        }

        @Test
        @DisplayName("Should return powerful model for complex reasoning")
        void shouldReturnPowerfulModelForComplexReasoning() {
            LlmOptions options = service.forComplexReasoning();
            assertNotNull(options);
            assertNotNull(options.getCriteria());
        }

        @Test
        @DisplayName("Should return balanced model for underwriting")
        void shouldReturnBalancedModelForUnderwriting() {
            LlmOptions options = service.forUnderwriting();
            assertNotNull(options);
            assertNotNull(options.getCriteria());
        }

        @Test
        @DisplayName("Should return balanced model for claims processing")
        void shouldReturnBalancedModelForClaims() {
            LlmOptions options = service.forClaims();
            assertNotNull(options);
            assertNotNull(options.getCriteria());
        }

        @Test
        @DisplayName("Should return balanced model for chat")
        void shouldReturnBalancedModelForChat() {
            LlmOptions options = service.forChat();
            assertNotNull(options);
            assertNotNull(options.getCriteria());
        }

        @Test
        @DisplayName("Should return embedding model for embedding tasks")
        void shouldReturnEmbeddingModelForEmbedding() {
            LlmOptions options = service.forEmbedding();
            assertNotNull(options);
            assertNotNull(options.getCriteria());
        }
    }

    @Nested
    @DisplayName("Auto Model Selection Tests")
    class AutoSelectionTests {

        @Test
        @DisplayName("Should return auto model selection options")
        void shouldReturnAutoModelSelection() {
            LlmOptions options = service.forAuto();
            assertNotNull(options);
            assertNotNull(options.getCriteria());
        }

        @Test
        @DisplayName("Should return specific model options")
        void shouldReturnSpecificModel() {
            LlmOptions options = service.forModel("deepseek-chat");
            assertNotNull(options);
            assertNotNull(options.getCriteria());
        }
    }

    @Nested
    @DisplayName("Complexity-Based Model Selection Tests")
    class ComplexityBasedSelectionTests {

        @Test
        @DisplayName("Should return fast model for low complexity (0-30)")
        void shouldReturnFastModelForLowComplexity() {
            LlmOptions options = service.forComplexity(0);
            assertNotNull(options);
            // Low complexity should use fast model
        }

        @Test
        @DisplayName("Should return fast model for complexity 30")
        void shouldReturnFastModelForComplexity30() {
            LlmOptions options = service.forComplexity(30);
            assertNotNull(options);
        }

        @Test
        @DisplayName("Should return balanced model for medium complexity (31-60)")
        void shouldReturnBalancedModelForMediumComplexity() {
            LlmOptions options31 = service.forComplexity(31);
            LlmOptions options60 = service.forComplexity(60);
            assertNotNull(options31);
            assertNotNull(options60);
        }

        @Test
        @DisplayName("Should return powerful model for high complexity (61-100)")
        void shouldReturnPowerfulModelForHighComplexity() {
            LlmOptions options61 = service.forComplexity(61);
            LlmOptions options100 = service.forComplexity(100);
            assertNotNull(options61);
            assertNotNull(options100);
        }

        @Test
        @DisplayName("Should handle boundary values correctly")
        void shouldHandleBoundaryValues() {
            // Test all boundary values
            LlmOptions options0 = service.forComplexity(0);
            LlmOptions options30 = service.forComplexity(30);
            LlmOptions options31 = service.forComplexity(31);
            LlmOptions options60 = service.forComplexity(60);
            LlmOptions options61 = service.forComplexity(61);
            LlmOptions options100 = service.forComplexity(100);

            assertAll(
                () -> assertNotNull(options0),
                () -> assertNotNull(options30),
                () -> assertNotNull(options31),
                () -> assertNotNull(options60),
                () -> assertNotNull(options61),
                () -> assertNotNull(options100)
            );
        }
    }

    @Nested
    @DisplayName("Model Role Constants Tests")
    class RoleConstantsTests {

        @Test
        @DisplayName("Should have correct fast role constant")
        void shouldHaveCorrectFastRoleConstant() {
            assertEquals("fast", LlmSelectionService.ROLE_FAST);
        }

        @Test
        @DisplayName("Should have correct balanced role constant")
        void shouldHaveCorrectBalancedRoleConstant() {
            assertEquals("balanced", LlmSelectionService.ROLE_BALANCED);
        }

        @Test
        @DisplayName("Should have correct powerful role constant")
        void shouldHaveCorrectPowerfulRoleConstant() {
            assertEquals("powerful", LlmSelectionService.ROLE_POWERFUL);
        }

        @Test
        @DisplayName("Should have correct embedding role constant")
        void shouldHaveCorrectEmbeddingRoleConstant() {
            assertEquals("embedding", LlmSelectionService.ROLE_EMBEDDING);
        }
    }

    @Nested
    @DisplayName("Edge Case Tests")
    class EdgeCaseTests {

        @Test
        @DisplayName("Should handle negative complexity gracefully")
        void shouldHandleNegativeComplexity() {
            LlmOptions options = service.forComplexity(-1);
            assertNotNull(options);
            // Negative should be treated as 0 (fast model)
        }

        @Test
        @DisplayName("Should handle complexity above 100")
        void shouldHandleComplexityAbove100() {
            LlmOptions options = service.forComplexity(150);
            assertNotNull(options);
            // Should be treated as high complexity (powerful model)
        }

        @Test
        @DisplayName("Should handle very large complexity values")
        void shouldHandleVeryLargeComplexity() {
            LlmOptions options = service.forComplexity(Integer.MAX_VALUE);
            assertNotNull(options);
        }

        @Test
        @DisplayName("Should handle null model name gracefully")
        void shouldHandleNullModelName() {
            assertDoesNotThrow(() -> service.forModel(null));
        }

        @Test
        @DisplayName("Should handle empty model name")
        void shouldHandleEmptyModelName() {
            LlmOptions options = service.forModel("");
            assertNotNull(options);
        }
    }

    @Nested
    @DisplayName("Consistency Tests")
    class ConsistencyTests {

        @Test
        @DisplayName("Should return consistent options for same task type")
        void shouldReturnConsistentOptionsForSameTask() {
            LlmOptions options1 = service.forSimpleQuery();
            LlmOptions options2 = service.forSimpleQuery();
            assertEquals(options1.getCriteria().toString(), options2.getCriteria().toString());
        }

        @Test
        @DisplayName("Should return consistent options for same complexity")
        void shouldReturnConsistentOptionsForSameComplexity() {
            LlmOptions options1 = service.forComplexity(25);
            LlmOptions options2 = service.forComplexity(25);
            assertEquals(options1.getCriteria().toString(), options2.getCriteria().toString());
        }

        @Test
        @DisplayName("Should return different options for different tasks")
        void shouldReturnDifferentOptionsForDifferentTasks() {
            LlmOptions fastOptions = service.forSimpleQuery();
            LlmOptions powerfulOptions = service.forComplexReasoning();
            // Different tasks should potentially use different models
            assertNotEquals(fastOptions.getCriteria().toString(),
                           powerfulOptions.getCriteria().toString());
        }
    }
}
