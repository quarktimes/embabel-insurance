package com.embabel.insurance.config;

import com.embabel.insurance.service.DocumentIngestionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * 应用启动时自动摄入知识库文档。
 *
 * <p>通过 {@code insurance.rag.auto-ingest} 配置控制是否启用自动摄入。
 * 若关闭自动摄入，文档将在首次查询时延迟摄入。
 */
@Component
public class DocumentIngestionRunner implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(DocumentIngestionRunner.class);

    private final DocumentIngestionService ingestionService;
    
    @Value("${insurance.rag.auto-ingest:true}")
    private boolean autoIngest;

    public DocumentIngestionRunner(DocumentIngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    @Override
    public void run(String... args) {
        if (autoIngest) {
            logger.info("Auto-ingesting documents on startup...");
            try {
                ingestionService.autoIngestIfConfigured();
                logger.info("Document auto-ingestion completed successfully");
            } catch (Exception e) {
                logger.error("Document auto-ingestion failed", e);
            }
        } else {
            logger.info("Auto-ingest disabled. Documents will be ingested on first query.");
        }
    }
}