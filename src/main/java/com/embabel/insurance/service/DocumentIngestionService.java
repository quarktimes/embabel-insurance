package com.embabel.insurance.service;

import com.embabel.agent.rag.lucene.LuceneSearchOperations;
import com.embabel.agent.rag.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 文档摄入服务，将保险知识库文档解析并索引到 Lucene RAG 引擎中。
 *
 * <p>将 Markdown 文档解析为 embabel 的 {@link MaterializedDocument} 层级结构，
 * 再通过 {@link LuceneSearchOperations#writeAndChunkDocument} 完成分块和 Lucene 索引。
 *
 * <p>摄入完成后，{@link com.embabel.agent.rag.tools.ToolishRag} 会将文本搜索工具
 * 暴露给 LLM，实现 Agentic RAG 能力。
 */
@Service
public class DocumentIngestionService {

    private static final Logger logger = LoggerFactory.getLogger(DocumentIngestionService.class);

    private final ResourceLoader resourceLoader;
    private final LuceneSearchOperations luceneSearchOperations;

    @Value("${insurance.rag.documents-path:classpath:documents/}")
    private String documentsPath;

    @Value("${insurance.rag.auto-ingest:true}")
    private boolean autoIngest;

    @Value("#{'${insurance.rag.documents}'.split(',')}")
    private List<String> documentFiles;

    private final Map<String, String> ingestedDocumentUris = new ConcurrentHashMap<>();
    private boolean ingested = false;

    public DocumentIngestionService(ResourceLoader resourceLoader,
                                     LuceneSearchOperations luceneSearchOperations) {
        this.resourceLoader = resourceLoader;
        this.luceneSearchOperations = luceneSearchOperations;
    }

    public void autoIngestIfConfigured() {
        if (autoIngest && !ingested) {
            logger.info("Auto-ingesting documents from: {}", documentsPath);
            ingestAllDocuments();
        }
    }

    /**
     * 摄入所有已配置的 Markdown 文档到 Lucene RAG 索引。
     * 每份文档解析为 {@link MaterializedDocument} 后交给
     * {@link LuceneSearchOperations#writeAndChunkDocument} 进行分块和索引。
     */
    public IngestionResult ingestAllDocuments() {
        logger.info("Starting document ingestion into Lucene RAG for {} documents...", documentFiles.size());

        int totalChunks = 0;
        List<String> ingestedFileNames = new ArrayList<>();

        for (String fileName : documentFiles) {
            try {
                int chunks = ingestDocument(fileName);
                totalChunks += chunks;
                ingestedFileNames.add(fileName);
                logger.info("Ingested {} into Lucene with {} chunks", fileName, chunks);
            } catch (Exception e) {
                logger.error("Failed to ingest document: {}", fileName, e);
            }
        }

        ingested = true;
        logger.info("Lucene RAG ingestion completed. Total chunks: {}", totalChunks);
        return new IngestionResult(ingestedFileNames, totalChunks);
    }

    /**
     * 解析单个 Markdown 文件为 MaterializedDocument 并索引到 Lucene。
     *
     * @return 创建的分块数量
     */
    public int ingestDocument(String fileName) {
        logger.info("Ingesting document into Lucene RAG: {}", fileName);

        try {
            String resourcePath = documentsPath + fileName;
            Resource resource = resourceLoader.getResource(resourcePath);
            if (!resource.exists()) {
                logger.warn("Document file not found: {}", resourcePath);
                return 0;
            }

            String content;
            try (var reader = new BufferedReader(
                    new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
                content = reader.lines().collect(Collectors.joining("\n"));
            }

            // 若存在旧版本则先删除
            String uri = "documents/" + fileName;
            String previousDocId = ingestedDocumentUris.get(fileName);
            if (previousDocId != null) {
                luceneSearchOperations.deleteRootAndDescendants(uri);
            }

            // 将 Markdown 解析为 embabel 的层级文档模型
            MaterializedDocument document = parseMarkdownDocument(fileName, content, uri);
            ingestedDocumentUris.put(fileName, document.getId());

            // writeAndChunkDocument 处理：分块 → Lucene 索引 → 提交
            List<String> chunkIds = luceneSearchOperations.writeAndChunkDocument(document);

            logger.info("Indexed document '{}' into Lucene: {} chunks", fileName, chunkIds.size());
            return chunkIds.size();

        } catch (Exception e) {
            logger.error("Error ingesting document: {}", fileName, e);
            throw new RuntimeException("Failed to ingest document: " + fileName, e);
        }
    }

    /**
     * 重新摄入所有文档（先清除已有数据）。
     */
    public IngestionResult reIngestAllDocuments() {
        logger.info("Re-ingesting all documents into Lucene RAG...");
        luceneSearchOperations.clear();
        ingestedDocumentUris.clear();
        ingested = false;
        return ingestAllDocuments();
    }

    /**
     * 将 Markdown 文件解析为 embabel 的层级 MaterializedDocument。
     */
    private MaterializedDocument parseMarkdownDocument(String fileName, String content, String uri) {
        String[] lines = content.split("\n");
        String documentTitle = fileName.replace(".md", "").replace("_", " ");

        List<NavigableSection> chapterSections = new ArrayList<>();
        List<NavigableSection> subsections = new ArrayList<>();
        String currentSectionTitle = null;
        String currentSubsectionTitle = null;
        StringBuilder currentContent = new StringBuilder();

        for (String line : lines) {
            if (line.startsWith("# ") && currentSectionTitle == null) {
                // 一级标题 — 作为文档标题
                documentTitle = line.substring(2).trim();
            } else if (line.startsWith("## ")) {
                // 新章节 — 先刷新前一个子节和章节
                flushSubsection(subsections, currentSubsectionTitle, currentContent);
                flushChapter(chapterSections, currentSectionTitle, subsections);
                subsections = new ArrayList<>();
                currentSectionTitle = line.substring(3).trim();
                currentSubsectionTitle = null;
                currentContent = new StringBuilder();
            } else if (line.startsWith("### ")) {
                // 章节内新小节 — 刷新前一个子节
                flushSubsection(subsections, currentSubsectionTitle, currentContent);
                if (currentSectionTitle == null) {
                    currentSectionTitle = line.substring(4).trim();
                } else {
                    // 将 ### 作为 ## 下的子节
                    currentSubsectionTitle = line.substring(4).trim();
                    currentContent = new StringBuilder();
                }
            } else if (line.startsWith("#### ")) {
                // 子子节
                flushSubsection(subsections, currentSubsectionTitle, currentContent);
                currentSubsectionTitle = line.substring(5).trim();
                currentContent = new StringBuilder();
            } else {
                currentContent.append(line).append("\n");
            }
        }

        // 刷新剩余内容
        flushSubsection(subsections, currentSubsectionTitle, currentContent);
        flushChapter(chapterSections, currentSectionTitle, subsections);

        return new MaterializedDocument(
                UUID.randomUUID().toString(),
                uri,
                documentTitle,
                Instant.now(),
                chapterSections,
                Map.of("source", fileName, "category", getCategory(fileName))
        );
    }

    private void flushSubsection(List<NavigableSection> subsections,
                                  String title, StringBuilder content) {
        if (title != null && content.length() > 0) {
            subsections.add(new LeafSection(
                    UUID.randomUUID().toString(),
                    null, // parent will be set by container
                    title,
                    content.toString().trim(),
                    null,
                    Map.of("type", "subsection")
            ));
            content.setLength(0);
        }
    }

    private void flushChapter(List<NavigableSection> chapters,
                               String title, List<NavigableSection> subsections) {
        if (title != null && !subsections.isEmpty()) {
            chapters.add(new DefaultMaterializedContainerSection(
                    UUID.randomUUID().toString(),
                    null,
                    title,
                    subsections,
                    null,
                    Map.of("type", "section")
            ));
        }
    }

    private String getCategory(String fileName) {
        if (fileName.contains("insurance")) return "policy";
        if (fileName.contains("claims")) return "claims";
        if (fileName.contains("faq")) return "faq";
        return "general";
    }

    public boolean isIngested() {
        return ingested;
    }

    public List<String> getIngestedDocumentNames() {
        return new ArrayList<>(ingestedDocumentUris.keySet());
    }

    // ---- 搜索委托给 LuceneSearchOperations ----

    /**
     * 在 Lucene 索引中执行文本搜索。
     */
    public List<com.embabel.common.core.types.SimilarityResult<com.embabel.agent.rag.model.Chunk>> search(
            String query, int topK, double threshold) {
        return luceneSearchOperations.textSearch(
                com.embabel.common.core.types.TextSimilaritySearchRequest.create(query, threshold, topK),
                com.embabel.agent.rag.model.Chunk.class
        );
    }

    public record IngestionResult(List<String> documentsIngested, int totalChunks) {
        public boolean success() {
            return totalChunks > 0;
        }
    }
}