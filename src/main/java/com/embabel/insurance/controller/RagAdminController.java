package com.embabel.insurance.controller;

import com.embabel.insurance.service.DocumentIngestionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * RAG 知识库管理接口，仅限管理员访问。
 *
 * <p>提供文档摄入、重新摄入、状态查询等功能。
 * 所有接口需要 {@code rag:admin} 权限。
 */
@RestController
@RequestMapping("/api/admin/rag")
@PreAuthorize("hasAuthority('rag:admin')")
@Tag(name = "RAG Admin", description = "文档摄入与知识库管理（管理员专用）")
@SecurityRequirement(name = "basicAuth")
public class RagAdminController {

    private static final Logger logger = LoggerFactory.getLogger(RagAdminController.class);

    private final DocumentIngestionService ingestionService;
    private final List<String> availableDocuments;

    public RagAdminController(DocumentIngestionService ingestionService,
                              @Value("#{'${insurance.rag.documents}'.split(',')}") List<String> availableDocuments) {
        this.ingestionService = ingestionService;
        this.availableDocuments = availableDocuments;
    }

    @Operation(summary = "获取摄入状态", description = "查询当前文档摄入的状态和已摄入的文档列表")
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        boolean ingested = ingestionService.isIngested();
        List<String> documents = ingestionService.getIngestedDocumentNames();

        return ResponseEntity.ok(Map.of(
                "ingested", ingested,
                "documentsCount", documents.size(),
                "documents", documents
        ));
    }

    @Operation(summary = "触发文档摄入", description = "开始摄入所有知识库文档（保险合同、理赔指南、FAQ 等）")
    @PostMapping("/ingest")
    public ResponseEntity<Map<String, Object>> ingestDocuments() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String userId = auth.getName();

        logger.info("Admin user {} triggering document ingestion", userId);

        DocumentIngestionService.IngestionResult result = ingestionService.ingestAllDocuments();

        return ResponseEntity.ok(Map.of(
                "success", result.success(),
                "documentsIngested", result.documentsIngested(),
                "totalChunks", result.totalChunks()
        ));
    }

    @Operation(summary = "重新摄入所有文档", description = "清除已有数据后重新摄入全部知识库文档")
    @PostMapping("/reingest")
    public ResponseEntity<Map<String, Object>> reIngestDocuments() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String userId = auth.getName();

        logger.info("Admin user {} triggering document re-ingestion", userId);

        DocumentIngestionService.IngestionResult result = ingestionService.reIngestAllDocuments();

        return ResponseEntity.ok(Map.of(
                "success", result.success(),
                "documentsIngested", result.documentsIngested(),
                "totalChunks", result.totalChunks(),
                "message", "Documents re-ingested successfully"
        ));
    }

    @Operation(summary = "摄入单个文档", description = "摄入指定名称的单个知识库文档")
    @PostMapping("/ingest/{documentName}")
    public ResponseEntity<Map<String, Object>> ingestSingleDocument(
            @Parameter(description = "文档名称", example = "claims_guide.md")
            @PathVariable String documentName) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String userId = auth.getName();

        logger.info("Admin user {} ingesting single document: {}", userId, documentName);

        int chunks = ingestionService.ingestDocument(documentName);

        return ResponseEntity.ok(Map.of(
                "success", chunks > 0,
                "documentName", documentName,
                "chunksAdded", chunks
        ));
    }

    @Operation(summary = "列出可用文档", description = "获取所有可供摄入的知识库文档列表")
    @GetMapping("/documents")
    public ResponseEntity<List<String>> listAvailableDocuments() {
        return ResponseEntity.ok(availableDocuments);
    }
}
