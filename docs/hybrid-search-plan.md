# 混合搜索方案：Lucene BM25 + Qdrant 向量

## 1. 背景

当前 RAG 只用 Lucene BM25 全文检索，依赖关键词匹配。对于语义相近但措辞不同的问题（如"新车被刮找不到人" vs "无法找到第三方特约险"），BM25 无法将两者关联。

## 2. 目标架构

```
用户提问
    ↓
  IntentClassifier → CHAT
    ↓
  ChatbotAgent（Agentic RAG）
    ↓
  HybridSearchOperations（实现 SearchOperations 接口）
    ├─── LuceneSearchOperations（BM25 全文检索）── 保留现有实现
    │         ↑
    │     Lucene 索引（文本 + 倒排）
    │
    └─── QdrantSearchOperations（向量相似度）── 新增
              ↑
          Qdrant 向量库（HNSW 索引）
              ↑
        EmbeddingService（DeepSeek text-embedding-3-small）
    ↓
  RRF 融合排序
    ↓
  ToolishRag → LLM 综合回答
```

## 3. 新增/修改文件清单

### 新增文件（4 个）

| # | 文件 | 职责 |
|---|------|------|
| 1 | `config/QdrantConfig.java` | Qdrant 客户端 Bean + Collection 初始化 |
| 2 | `rag/EmbeddingService.java` | 调用 DeepSeek Embedding API（文本 → 向量） |
| 3 | `rag/QdrantSearchOperations.java` | 实现搜索接口，写入/查询 Qdrant |
| 4 | `rag/HybridSearchOperations.java` | 实现 SearchOperations，内部调 BM25 + Qdrant，RRF 融合 |

### 修改文件（2 个）

| # | 文件 | 改动 |
|---|------|------|
| 1 | `pom.xml` | 新增 Qdrant client 依赖 |
| 2 | `config/RagConfiguration.java` | `insuranceRag` bean 的 searchOperations 替换为 HybridSearchOperations |

## 4. 数据流

### 写入（文档摄入）

```
Markdown 文档 → DocumentIngestionService
    ↓
  LuceneSearchOperations.writeAndChunkDocument()
    ├── 分块 → 写入 Lucene 文本索引
    └── 回调 → QdrantSearchOperations.writeChunk()
               ├── EmbeddingService.embed(chunkText) → 向量
               └── Qdrant.upsert(points) → 写入向量库
```

### 查询（混合搜索）

```
用户提问 → HybridSearchOperations.search()
    ↓
  (并行)
  ├── Lucene.textSearch(query) → BM25 结果集 A
  └── Qdrant.search(EmbeddingService.embed(query)) → 向量结果集 B
    ↓
  RRF 融合：(A ∪ B) 按 score = 1/(rank + k) 重排
    ↓
  TopK 结果 → ToolishRag → LLM
```

## 5. RRF 融合算法

```
Score = 1 / (rank_in_lucene + k) + 1 / (rank_in_qdrant + k)
```

- `k = 60`（经验值，防止 BM25 精确匹配被向量噪声淹没）
- 只在单边出现的结果：另一边的 rank 视为无穷大，贡献为 0
- 两边都命中的结果：得分更高，排更前

## 6. 配置

```yaml
insurance:
  rag:
    qdrant:
      host: ${QDRANT_HOST:localhost}
      port: ${QDRANT_PORT:6333}
      collection-name: insurance_docs
      vector-size: 1536
    embedding:
      enabled: true
      model: text-embedding-3-small
      base-url: https://api.deepseek.com/v1
      api-key: ${DEEPSEEK_API_KEY}
      dimensions: 1536
    hybrid:
      rrf-k: 60
      bm25-weight: 0.5
      vector-weight: 0.5
```

## 7. 实施步骤

| 步骤 | 内容 | 预估代码量 |
|------|------|-----------|
| 1 | pom.xml 加 Qdrant client 依赖 | 1 行 |
| 2 | `QdrantConfig.java` — QdrantClient bean + 启动时 `createCollection` | ~50 行 |
| 3 | `EmbeddingService.java` — 调 DeepSeek Embedding API | ~60 行 |
| 4 | `QdrantSearchOperations.java` — 向量写入 + 向量搜索 | ~120 行 |
| 5 | `HybridSearchOperations.java` — BM25 + Qdrant 并行 + RRF 融合 | ~80 行 |
| 6 | `RagConfiguration.java` — 替换 ToolishRag 的 searchOperations | ~10 行 |
| 7 | Docker Compose 加入 Qdrant | ~10 行 |
| 8 | 测试验证 | — |

**总计：约 320 行新代码，2 个修改，1 个 Docker 服务。**

## 8. 验证方式

### 测试问题

> "我刚买的新车停在路边被人刮了，但附近没有监控找不到人，这种情况我的保险能赔吗？"

### BM25 单独的效果
- 关键词匹配：刮蹭、赔偿、保险
- 容易遗漏「无法找到第三方」相关的语义匹配

### 混合搜索的效果
- BM25 命中：刮蹭、赔偿、保险 → 得分中
- 向量命中：「找不到肇事者」→「无法找到第三方特约险」→ 得分高
- RRF 融合后：混合结果排前，覆盖更全面

可在 `faq.md` 中补一条"无法找到第三方"相关的 QA 来验证。

---

要继续实施吗？
