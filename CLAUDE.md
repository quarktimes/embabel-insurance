# CLAUDE.md

本文档指导 Claude Code 在此仓库中的开发工作。

## 构建与运行

```bash
# 启动应用（需要 DEEPSEEK_API_KEY）
./mvnw spring-boot:run

# 运行全部测试
./mvnw test

# 运行单个测试类
./mvnw test -Dtest=UnderwritingAgentIntegrationTest

# 运行 E2E 测试（需要 DEEPSEEK_API_KEY）
./mvnw test -Pe2e

# 跳过集成测试（快速编译检查）
./mvnw test -Dtest='!integration.*,!e2e.*'
```

**环境变量：** `DEEPSEEK_API_KEY`（必需），`LANGFUSE_*`（可选的可观测性配置）。

## 架构概览（三层 Agent 系统）

```
┌─────────────────────────────────────────────┐
│  REST 控制器层                              │
│  ChatController  InsuranceController        │
├─────────────────────────────────────────────┤
│  服务层                                     │
│  ChatService    AgentService   PolicyService│
├─────────────────────────────────────────────┤
│  Embabel Agent 层                           │
│  ChatbotAgent   UnderwritingAgent  Claims   │
│  (RAG/问答)     (核保+状态路由)             │
│               + Guardrails（安全护栏）       │
└─────────────────────────────────────────────┘
```

### 目录结构

- **assistant/** — Phase 1 新增：`IntentClassifier`（关键词意图分类）和 `Intent` 枚举
- **agent/** — 三个 `@Agent` 类，内部使用 `@State` 路由：
  - `ChatbotAgent` — Agentic RAG（Lucene 搜索 → LLM 回答），单状态问答
  - `UnderwritingAgent` — 多状态（APPROVED/REFERRED/DENIED/ERROR），Utility 规划器
  - `ClaimsAgent` — 多状态（APPROVED/DENIED/INVESTIGATING/ERROR），Utility 规划器
- **service/** — `AgentService` 通过 AgentPlatform 编排核保/理赔 Agent；`ChatService` 管理服务端会话 + 通过 AgentInvocation 调用 ChatbotAgent
- **controller/** — REST 端点（`/api/chat`、`/api/insurance/*`、`/api/assistant`），所有接口需要 HTTP Basic 认证（`@PreAuthorize` 实现细粒度权限控制）
- **guardrail/** — Embabel GuardRail 实现，用于输入/输出内容安全校验
- **dto/** — `request/` 和 `response/` 子包，结构化 API 请求响应契约
- **config/** — SecurityConfig、CacheConfig、RAG 配置、DataInitializer、OpenAPI、Guardrail 装配

### 测试策略

| 测试类型 | 位置 | 需要 LLM？ | Profile |
|---------|------|-----------|---------|
| 单元测试 | `agent/*Test.java`、`service/*Test.java` | 否 | default |
| 集成测试 | `integration/*IntegrationTest.java` | 否（FakeOperationContext 模拟 LLM） | default |
| E2E 测试 | `e2e/*E2ETest.java` | 是（DeepSeek） | `e2e` |

集成测试使用 Embabel 的 `FakeOperationContext` 来模拟 LLM 响应，无需真实 API 调用即可覆盖完整的 Agent 状态机流程。

### 关键模式

1. **Agent 状态路由** — Agent 使用 `@State(name = "APPROVED")` + `@Action` 注解标记状态和动作。Utility 规划器根据风险评分/欺诈评分在状态间路由。

2. **Blackboard 错误处理** — Agent 错误存储在 `Blackboard` 上（键如 `underwriting_error`、`claims_error`），而非抛出异常。`AgentService` 在 Agent 完成后读取这些值。

3. **Agent 超时保护** — 所有 Agent 调用通过 `runWithTimeout()`（默认 120s）保护；超时时触发 `StuckHandler` 回调输出诊断信息。

4. **聊天会话管理** — `ChatService` 按 userId 在内存中管理会话，30 分钟 TTL；客户端首次发送消息时获得 `sessionId`，后续调用带回该 ID。

5. **角色权限体系** — 四个角色：ADMIN > (UNDERWRITER, CLAIMS) > USER，支持层级权限。保险核心接口通过 `@PreAuthorize` 注解控制访问。

6. **统一助手（Phase 1）** — `POST /api/assistant` 作为统一入口。`IntentClassifier` 关键词分类（核保/理赔/保单查询/客服），`AssistantService` 路由到对应 Agent/Service，返回结构化 `AssistantResponse`（含 type、text、data、actions）。前端体验在 `src/main/resources/static/index.html`。
