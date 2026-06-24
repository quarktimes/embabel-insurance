# 智能保险平台

基于 [Embabel 框架](https://github.com/embabel/embabel-agent) 的多 Agent 智能保险平台，支持核保、理赔、AI 客服等核心业务流程，集成 RAG 知识库、混合搜索和完整可观测性。

## 功能特性

- **🚗 核保 Agent** — LLM 提取车辆信息 → 风险评分 → 自动路由（批准 / 转人工 / 拒绝）
- **📋 理赔 Agent** — 保单校验 → 欺诈评分 → 自动路由（批准 / 人工审核 / 拒绝）
- **💬 AI 客服** — RAG 知识库问答，BM25 + 向量混合搜索
- **🔀 意图路由** — 自然语言输入，自动识别并路由到对应业务模块
- **🛡️ 安全防护** — Spring Security + Embabel Guardrails 输入校验
- **📊 可观测性** — Prometheus + Grafana 指标监控

## 技术栈

| 组件 | 技术 |
|------|------|
| 框架 | Spring Boot 4.0.1 / Java 21 |
| Agent 框架 | Embabel 2.0.0 (SNAPSHOT) |
| LLM | DeepSeek (deepseek-chat) |
| 数据库 | MySQL 8.x |
| 全文检索 | Lucene BM25 |
| 向量检索 | Qdrant + Ollama (nomic-embed-text) |
| 混合搜索 | RRF 融合排序 |
| 前端 | 静态 HTML (Spring Boot 内嵌) |
| 安全 | Spring Security + HTTP Basic + 角色层级 |
| 可观测性 | Prometheus + Grafana + Langfuse |

## 快速开始

### 前置条件

- Java 21+
- Maven 3.8+
- MySQL 8.0+（运行中）
- DeepSeek API Key
- Docker（Qdrant / Langfuse / Prometheus / Grafana）

### 1. 环境变量

```bash
# 必填
export DEEPSEEK_API_KEY=your-deepseek-api-key

# MySQL（可选，有默认值）
export MYSQL_URL=jdbc:mysql://localhost:3306/embabel_insurance?createDatabaseIfNotExist=true
export MYSQL_USER=root
export MYSQL_PASSWORD=Embabel@2024!
```

### 2. 启动依赖服务

```bash
# 确保 MySQL 已启动并创建数据库
mysql -u root -p -e "CREATE DATABASE IF NOT EXISTS embabel_insurance CHARACTER SET utf8mb4"

# 启动 Docker 服务（Qdrant + 可观测性）
docker compose up -d
```

### 3. 启动应用

```bash
./mvnw spring-boot:run
```

应用默认在 `http://localhost:8080` 启动，启动时自动初始化测试数据并摄入 RAG 文档。

### 4. 体验

打开 **http://localhost:8080** → 登录（user / password）→ 开始对话：

```
🚗 我要给京A88888的RAV4投保              → 核保报价
📋 policy=POL-001 description=刮蹭 amount=5000  → 理赔申请
📄 我的保单                              → 保单查询
💬 综合险是什么？                         → 客服问答
```

## 测试用户

| 用户名 | 密码 | 说明 |
|--------|------|------|
| `user` | password | 普通用户，聊天+查看保单 |
| `low-risk-user` | password | 核保自动通过 |
| `medium-risk-user` | password | 核保转人工 |
| `high-risk-user` | password | 核保拒绝 |
| `underwriter` | underwriter | 核保员，可审批报价 |
| `claims` | claims | 理赔员，可审核理赔 |
| `admin` | admin | 管理员，全部权限 |

## 架构

```
用户输入 (Chat UI / REST API)
    ↓
AssistantController (/api/assistant)
    ↓
IntentClassifier  ─→  CHAT       → HybridSearchService + ChatbotAgent
                   │               (BM25 + Qdrant 混合搜索)
                   ├→  UNDERWRITING → AgentService + UnderwritingAgent
                   ├→  CLAIMS       → AgentService + ClaimsAgent
                   ├→  POLICY_QUERY → PolicyService
                   ├→  PAYMENT      → PaymentService
                   └→  VIEW_DETAILS → QuoteRepository
    ↓
AssistantResponse (结构化 JSON)
    ↓
Chat UI (打字机渲染 + 操作按钮)
```

## API

### 统一助手（推荐）

```bash
# 自然语言输入，自动路由
curl -u user:password -X POST http://localhost:8080/api/assistant \
  -H "Content-Type: application/json" \
  -d '{"message": "我要给京A88888的RAV4投保"}'
```

返回结构化响应：

```json
{
  "type": "underwriting_result",
  "text": "✅ 核保通过，保费 ¥3,680...",
  "data": { "quoteId": 1, "status": "APPROVED", "premiumAmount": 3680 },
  "actions": [{ "label": "💳 立即支付", "action": "pay", "payload": { "quoteId": 1 } }]
}
```

### 原始接口

| 端点 | 方法 | 说明 |
|------|------|------|
| `/api/assistant` | POST | 统一助手入口 |
| `/api/chat` | POST | 客服问答 |
| `/api/insurance/underwrite` | POST | 核保 |
| `/api/insurance/claims` | POST | 理赔 |
| `/api/insurance/policies` | GET | 保单列表 |
| `/api/insurance/pay` | POST | 支付 |
| `/api/insurance/health` | GET | 健康检查 |

Swagger UI: `http://localhost:8080/swagger-ui.html`

## 可观测性

```
应用 (:8080)
  ├─ /actuator/prometheus ← Prometheus (:9090) ← Grafana (:4000)
  └─ OTLP → Langfuse (:3000)
```

| 服务 | 地址 | 说明 |
|------|------|------|
| Grafana | http://localhost:4000 | admin/admin |
| Prometheus | http://localhost:9090 | 指标存储 |
| Langfuse | http://localhost:3000 | LLM 调用追踪 |

## 测试

```bash
# 全部测试
./mvnw test

# 单个测试
./mvnw test -Dtest=UnderwritingAgentIntegrationTest

# E2E 测试（需 API Key）
./mvnw test -Pe2e

# 跳过集成测试（快速编译）
./mvnw test -Dtest='!integration.*,!e2e.*'
```

集成测试使用 `FakeOperationContext` 模拟 LLM 响应，无需真实 API 调用。

## 项目结构

```
src/main/java/com/embabel/insurance/
├── agent/         # 三个 @Agent（Underwriting / Claims / Chatbot）
├── assistant/     # Phase 1：IntentClassifier + Intent 枚举
├── config/        # Security、RAG、Guardrail、DataInitializer 等
├── controller/    # REST 控制器
├── dto/           # 请求/响应 DTO
├── entity/        # JPA 实体
├── guardrail/     # Embabel GuardRail 实现
├── rag/           # EmbeddingService / QdrantSearch / HybridSearch
├── repository/    # Spring Data JPA
├── service/       # AgentService / ChatService / PaymentService 等
└── util/
```

## 许可

Apache 2.0
