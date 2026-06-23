package com.embabel.insurance.rag;

import com.embabel.agent.rag.lucene.LuceneSearchOperations;
import com.embabel.agent.rag.model.Chunk;
import com.embabel.common.core.types.SimilarityResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 混合搜索服务，并行执行 Lucene BM25 和 Qdrant 向量搜索，
 * 使用 RRF（Reciprocal Rank Fusion）融合排序。
 *
 * <p>在 {@link com.embabel.insurance.agent.ChatbotAgent} 中用作预检索上下文注入。
 */
@Service
public class HybridSearchService {

    private static final Logger logger = LoggerFactory.getLogger(HybridSearchService.class);

    private final LuceneSearchOperations luceneSearchOperations;
    private final QdrantSearchOperations qdrantSearchOperations;
    private final int rrfK;

    public HybridSearchService(LuceneSearchOperations luceneSearchOperations,
                               QdrantSearchOperations qdrantSearchOperations,
                               @Value("${insurance.rag.hybrid.rrf-k:60}") int rrfK) {
        this.luceneSearchOperations = luceneSearchOperations;
        this.qdrantSearchOperations = qdrantSearchOperations;
        this.rrfK = rrfK;
    }

    /**
     * 混合搜索：BM25 + 向量 → RRF 融合。
     *
     * @return 排序后的文档文本列表，附带来源标记
     */
    public List<ContextResult> search(String query, int topK, double threshold) {
        // 1. Lucene BM25
        var luceneRequest = com.embabel.common.core.types.TextSimilaritySearchRequest.create(query, threshold, topK);
        List<SimilarityResult<Chunk>> bm25Results = luceneSearchOperations.textSearch(luceneRequest, Chunk.class);

        // 2. Qdrant 向量
        List<QdrantSearchOperations.SearchHit> qdrantResults = qdrantSearchOperations.isAvailable()
                ? qdrantSearchOperations.search(query, topK, threshold)
                : List.of();

        if (qdrantResults.isEmpty()) {
            // BM25 only
            return bm25Results.stream()
                    .map(r -> new ContextResult(r.getMatch().getText(), r.getScore(), "bm25"))
                    .toList();
        }

        // 3. RRF 融合
        return mergeByRRF(bm25Results, qdrantResults, topK);
    }

    private List<ContextResult> mergeByRRF(
            List<SimilarityResult<Chunk>> bm25Results,
            List<QdrantSearchOperations.SearchHit> qdrantResults,
            int topK) {

        // BM25: chunk text → rank
        Map<String, Integer> bm25Rank = new HashMap<>();
        Map<String, String> bm25Text = new HashMap<>();
        for (int i = 0; i < bm25Results.size(); i++) {
            Chunk chunk = bm25Results.get(i).getMatch();
            bm25Rank.put(chunk.getId(), i + 1);
            bm25Text.put(chunk.getId(), chunk.getText());
        }

        // Qdrant: chunkId → rank
        Map<String, Integer> qdrantRank = new HashMap<>();
        Map<String, String> qdrantText = new HashMap<>();
        for (int i = 0; i < qdrantResults.size(); i++) {
            var hit = qdrantResults.get(i);
            qdrantRank.put(hit.chunkId(), i + 1);
            qdrantText.put(hit.chunkId(), hit.text());
        }

        // 收集所有唯一 chunkId
        Set<String> allIds = new LinkedHashSet<>();
        bm25Rank.keySet().forEach(allIds::add);
        qdrantRank.keySet().forEach(allIds::add);

        // RRF 融合打分
        record ScoredId(String id, double score) {}
        List<ScoredId> ranked = allIds.stream().map(id -> {
            int bRank = bm25Rank.getOrDefault(id, Integer.MAX_VALUE);
            int qRank = qdrantRank.getOrDefault(id, Integer.MAX_VALUE);
            double score = 1.0 / (rrfK + bRank) + 1.0 / (rrfK + qRank);
            return new ScoredId(id, score);
        }).sorted((a, b) -> Double.compare(b.score, a.score))
                .limit(topK)
                .toList();

        return ranked.stream().map(s -> {
            String text = bm25Text.getOrDefault(s.id, qdrantText.getOrDefault(s.id, ""));
            // 标记来源
            String source = bm25Rank.containsKey(s.id) && qdrantRank.containsKey(s.id) ? "hybrid"
                    : bm25Rank.containsKey(s.id) ? "bm25" : "vector";
            return new ContextResult(text, s.score, source);
        }).toList();
    }

    /** 混合搜索结果 */
    public record ContextResult(String text, double score, String source) {}
}
