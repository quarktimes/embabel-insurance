package com.embabel.insurance.rag;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.*;

/**
 * Qdrant 向量搜索操作（通过 REST API）。
 *
 * <p>将文档 chunk 转为向量后写入 Qdrant，查询时执行向量相似度搜索。
 * 使用 Qdrant REST API（默认端口 6333），避免 gRPC 的 protobuf 依赖。
 */
@Component
public class QdrantSearchOperations {

    private static final Logger logger = LoggerFactory.getLogger(QdrantSearchOperations.class);

    private final RestClient restClient;
    private final String collectionName;
    private final EmbeddingService embeddingService;
    private final boolean available;

    public QdrantSearchOperations(
            @Value("${insurance.rag.qdrant.host:localhost}") String qdrantHost,
            @Value("${insurance.rag.qdrant.rest-port:6333}") int restPort,
            @Value("${insurance.rag.qdrant.collection-name:insurance_docs}") String collectionName,
            EmbeddingService embeddingService) {
        this.collectionName = collectionName;
        this.embeddingService = embeddingService;
        this.restClient = RestClient.builder()
                .baseUrl("http://" + qdrantHost + ":" + restPort)
                .defaultHeader("Content-Type", "application/json")
                .build();

        // 启动时检查 Qdrant 是否可达
        boolean ready = false;
        try {
            var health = restClient.get().retrieve().toEntity(String.class);
            ready = health.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            logger.warn("Qdrant not available at {}:{}. Vector search disabled. Error: {}",
                    qdrantHost, restPort, e.getMessage());
        }
        this.available = ready;
        if (ready) {
            logger.info("Qdrant connected at {}:{}", qdrantHost, restPort);
        }
    }

    /**
     * 写入单个 chunk 到 Qdrant。
     */
    public void writeChunk(String chunkId, String chunkText) {
        if (!available) return;
        Map<String, String> chunks = new HashMap<>();
        chunks.put(chunkId, chunkText);
        writeChunks(chunks);
    }

    /**
     * 批量写入多个 chunk。
     */
    public void writeChunks(Map<String, String> chunks) {
        if (!available || chunks.isEmpty()) return;

        List<String> texts = new ArrayList<>(chunks.values());
        List<List<Float>> vectors = embeddingService.embedBatch(texts);
        var keys = new ArrayList<>(chunks.keySet());

        List<Map<String, Object>> points = new ArrayList<>();
        for (int i = 0; i < keys.size(); i++) {
            List<Float> vec = vectors.get(i);
            if (vec == null || vec.isEmpty()) continue;

            points.add(Map.of(
                    "id", keys.get(i),
                    "vector", vec,
                    "payload", Map.of("text", texts.get(i))
            ));
        }

        if (points.isEmpty()) return;

        try {
            var response = restClient.put()
                    .uri("/collections/{name}/points", collectionName)
                    .body(Map.of("points", points))
                    .retrieve()
                    .toEntity(String.class);

            if (response.getStatusCode().is2xxSuccessful()) {
                logger.debug("Upserted {} points to Qdrant", points.size());
            }
        } catch (Exception e) {
            logger.warn("Failed to upsert {} points to Qdrant: {}", points.size(), e.getMessage());
        }
    }

    /**
     * 向量相似度搜索。
     *
     * @return (chunkId, score) 列表，按相似度降序
     */
    public List<SearchHit> search(String query, int topK, double threshold) {
        if (!available) return List.of();

        try {
            List<Float> queryVector = embeddingService.embed(query);
            if (queryVector == null || queryVector.isEmpty()) return List.of();

            var response = restClient.post()
                    .uri("/collections/{name}/points/search", collectionName)
                    .body(Map.of(
                            "vector", queryVector,
                            "limit", topK,
                            "score_threshold", threshold,
                            "with_payload", true
                    ))
                    .retrieve()
                    .body(QdrantSearchResponse.class);

            if (response == null || response.result() == null) return List.of();

            return response.result().stream()
                    .map(r -> {
                        String text = r.payload() != null
                                ? r.payload().getOrDefault("text", "")
                                : "";
                        return new SearchHit(r.id(), r.score(), text);
                    })
                    .toList();

        } catch (Exception e) {
            logger.warn("Qdrant search failed: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 删除所有 points（清空 collection）。
     */
    public void clear() {
        if (!available) return;
        try {
            restClient.post()
                    .uri("/collections/{name}/points/delete", collectionName)
                    .body(Map.of("filter", Map.of()))
                    .retrieve()
                    .toEntity(String.class);
            logger.info("Cleared all points from Qdrant collection '{}'", collectionName);
        } catch (Exception e) {
            logger.warn("Failed to clear Qdrant collection: {}", e.getMessage());
        }
    }

    public boolean isAvailable() {
        return available;
    }

    /** 搜索结果记录 */
    public record SearchHit(String chunkId, double score, String text) {}

    // ── Qdrant REST API 响应模型 ──

    private record QdrantSearchResponse(List<ScoredPoint> result, String status, double time) {}

    private record ScoredPoint(
            String id,
            double score,
            Map<String, String> payload,
            @JsonProperty("version") long version) {}
}
