package com.embabel.insurance.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * Embedding 服务，通过 DeepSeek Embedding API 将文本转为向量。
 *
 * <p>使用 DeepSeek text-embedding-3-small 模型（1536 维），
 * 兼容 OpenAI 的 Embedding API 格式。
 */
@Service
public class EmbeddingService {

    private static final Logger logger = LoggerFactory.getLogger(EmbeddingService.class);

    private final RestClient restClient;
    private final String model;

    public EmbeddingService(
            @Value("${insurance.rag.embedding.base-url:https://api.deepseek.com/v1}") String baseUrl,
            @Value("${insurance.rag.embedding.api-key:${DEEPSEEK_API_KEY:}}") String apiKey,
            @Value("${insurance.rag.embedding.model:text-embedding-3-small}") String model) {
        this.model = model;
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    /**
     * 将单段文本转为向量。
     */
    public List<Float> embed(String text) {
        var results = embedBatch(List.of(text));
        return results.isEmpty() ? null : results.get(0);
    }

    /**
     * 将多段文本批量转为向量。
     *
     * @param texts 文本列表
     * @return 向量列表，顺序与输入一致
     */
    public List<List<Float>> embedBatch(List<String> texts) {
        var request = new EmbeddingRequest(model, texts);
        try {
            EmbeddingResponse response = restClient.post()
                    .uri("/embeddings")
                    .body(request)
                    .retrieve()
                    .body(EmbeddingResponse.class);

            if (response == null || response.data() == null) {
                logger.warn("Embedding API returned null response");
                return texts.stream().map(t -> List.<Float>of()).toList();
            }

            // 按 index 排序确保顺序一致
            return response.data().stream()
                    .sorted((a, b) -> Integer.compare(a.index(), b.index()))
                    .map(d -> d.embedding())
                    .toList();

        } catch (Exception e) {
            logger.error("Embedding API call failed for {} texts (first: '{}')", texts.size(),
                    texts.isEmpty() ? "" : truncate(texts.get(0), 40), e);
            return texts.stream().map(t -> List.<Float>of()).toList();
        }
    }

    private static String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) + "..." : s;
    }

    // ── API 请求/响应模型 ──

    private record EmbeddingRequest(String model, List<String> input) {}

    private record EmbeddingResponse(List<EmbeddingData> data, String model, Usage usage) {}

    private record EmbeddingData(int index, List<Float> embedding, String object) {}

    private record Usage(int prompt_tokens, int total_tokens) {}
}
