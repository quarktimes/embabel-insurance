package com.embabel.insurance.config;

import com.embabel.agent.rag.ingestion.ContentChunker;
import com.embabel.agent.rag.ingestion.ChunkTransformer;
import com.embabel.agent.rag.lucene.LuceneSearchOperations;
import com.embabel.agent.rag.tools.ToolishRag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * RAG（检索增强生成）配置。
 *
 * <p>Lucene BM25 全文检索通过 {@link ToolishRag} 暴露为 Agentic RAG 搜索工具，
 * 供 LLM 自主调用。Qdrant 向量搜索由 {@link com.embabel.insurance.rag.HybridSearchService}
 * 在 {@link com.embabel.insurance.agent.ChatbotAgent} 中作为预检索上下文注入。
 */
@Configuration
public class RagConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(RagConfiguration.class);

    @Value("${insurance.rag.lucene.name:insurance-lucene}")
    private String luceneName;

    @Value("${insurance.rag.lucene.chunk-size:1000}")
    private int chunkSize;

    @Value("${insurance.rag.lucene.chunk-overlap:200}")
    private int chunkOverlap;

    @Bean
    public LuceneSearchOperations luceneSearchOperations() {
        logger.info("Creating LuceneSearchOperations (BM25): name={}, chunkSize={}, chunkOverlap={}",
                luceneName, chunkSize, chunkOverlap);

        var chunkerConfig = new ContentChunker.Config(chunkSize, chunkOverlap, 100);

        var ops = new LuceneSearchOperations(
                luceneName,
                /* enhancers */ List.of(),
                /* embeddingService */ null,
                /* keywordExtractor */ null,
                /* vectorWeight */ 0.0,
                /* chunkerConfig */ chunkerConfig,
                /* chunkTransformer */ ChunkTransformer.NO_OP,
                /* indexPath */ null
        );

        logger.info("LuceneSearchOperations created: text-only BM25 mode");
        return ops;
    }

    @Bean
    public ToolishRag insuranceRag(LuceneSearchOperations luceneSearchOperations) {
        logger.info("Creating ToolishRag wrapping LuceneSearchOperations '{}'", luceneName);

        return new ToolishRag(
                "insurance_docs",
                """
                        Search insurance policy documents, claims guides, and FAQs about \
                        comprehensive vehicle insurance. Use textSearch for full-text search.""",
                luceneSearchOperations,
                """
                        Always search the knowledge base before answering user questions \
                        about insurance. Use Chinese keywords for Chinese questions and \
                        English keywords for English questions."""
        );
    }
}
