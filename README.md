# 智能保险平台 — Embabel Java Agent 教程项目

![Java](https://img.shields.io/badge/java-%23ED8B00.svg?style=for-the-badge&logo=openjdk&logoColor=white)
![Spring](https://img.shields.io/badge/spring-%236DB33F.svg?style=for-the-badge&logo=spring&logoColor=white)
![Apache Maven](https://img.shields.io/badge/Apache%20Maven-C71A36?style=for-the-badge&logo=Apache%20Maven&logoColor=white)

基于 [Embabel 框架](https://github.com/embabel/embabel-agent) 构建的智能保险平台，演示多 Agent 协作、LLM 驱动决策、RAG 知识检索和安全防护等核心能力。

## 功能特性

- **核保 Agent（UnderwritingAgent）** — LLM 提取车辆信息 → 客户/车辆查找 → 风险评分 → 自动路由（批准/转人工/拒绝）
- **理赔 Agent（ClaimsAgent）** — 保单校验 → LLM 事故提取 → 欺诈评分 → 自动路由（批准/拒绝/人工审核）
- **AI 客服 Agent（ChatbotAgent）** — 基于 Lucene RAG 的保险知识问答，Agentic RAG（先搜后答）
- **多 Agent 协作** — 核保 → 支付 → 理赔完整业务生命周期
- **安全防护（Guardrails）** — Spring Security + Embabel Guardrails 输入校验
- **缓存机制** — LLM 响应缓存 + RAG 搜索缓存

## 技术栈

| 组件 | 技术 |
|------|------|
| 框架 | Spring Boot 3.4.0 |
| Agent 框架 | Embabel 0.3.5 |
| LLM 提供商 | DeepSeek（deepseek-chat / deepseek-reasoner） |
| 数据库 | H2（内存模式，开箱即用） |
| RAG 引擎 | Embabel Lucene（BM25 全文检索）+ Apache Tika（文档解析） |
| 安全 | Spring Security + HTTP Basic 认证 + 角色层级权限 |
| API 文档 | SpringDoc OpenAPI（Swagger UI） |
| 可观测性 | Spring Boot Actuator + Micrometer Prometheus + OpenTelemetry Langfuse |
| 测试 | Embabel FakeOperationContext（无 LLM 调用的集成测试） |

## 快速开始

### 前置条件

- Java 21+
- Maven 3.8+
- DeepSeek API Key

### 1. 设置 API Key

```bash
export DEEPSEEK_API_KEY=your-deepseek-api-key
```

### 2. 启动应用

```bash
./mvnw spring-boot:run
```

应用默认在 `http://localhost:8080` 启动，使用 H2 内存数据库，启动时自动初始化测试数据。

### 3. 体验 Agent

应用启动后进入 Embabel Shell 模式，直接输入自然语言即可调用 Agent：

```
# 核保：描述你的车辆和保险需求
我要给京A12345的Toyota RAV4投保，userId=user

# 理赔：提交理赔申请
policy=POL-001 description=停车场被刮蹭 amount=5000 userId=user

# 客服：询问保险知识
什么是综合险？理赔流程是怎样的？
```

### 4. REST API

也可以通过 REST API 调用（需要 HTTP Basic 认证）：

```bash
# 核保请求
curl -u user:password -X POST http://localhost:8080/api/insurance/underwrite \
  -H "Content-Type: application/json" \
  -d '{"content": "我要给京A12345的Toyota RAV4投保，userId=user"}'

# 理赔请求
curl -u user:password -X POST http://localhost:8080/api/insurance/claim \
  -H "Content-Type: application/json" \
  -d '{"content": "policy=POL-001 description=停车场被刮蹭 amount=5000 userId=user"}'
```

Swagger UI：`http://localhost:8080/swagger-ui.html`（免认证访问）

### 5. 测试用户

| 用户名 | 密码 | 角色 | 权限 |
|--------|------|------|------|
| `user` | `password` | USER | 聊天、查看保单 |
| `underwriter` | `underwriter` | UNDERWRITER | 核保处理、审批报价 |
| `claims` | `claims` | CLAIMS | 理赔处理、审核理赔单 |
| `admin` | `admin` | ADMIN | 全部权限 |

## 项目结构

```
src/main/java/com/embabel/insurance/
├── agent/
│   ├── UnderwritingAgent.java    # 核保 Agent（Utility 规划 + @State 分类）
│   ├── ClaimsAgent.java          # 理赔 Agent（Utility 规划 + @State 分类）
│   └── ChatbotAgent.java         # AI 客服 Agent（Agentic RAG）
├── config/
│   ├── CacheConfiguration.java   # 缓存配置
│   ├── DataInitializer.java      # 测试数据初始化
│   ├── DocumentIngestionRunner.java  # 启动时 RAG 文档摄入
│   ├── GuardrailConfiguration.java   # Embabel Guardrails 配置
│   ├── OpenApiConfig.java        # Swagger 配置
│   ├── RagConfiguration.java     # Lucene RAG 引擎配置
│   └── SecurityConfig.java       # Spring Security 配置
├── controller/
│   ├── ChatController.java       # 聊天 API
│   ├── InsuranceController.java  # 核保/理赔 API
│   └── RagAdminController.java   # RAG 管理 API
├── dto/                          # 数据传输对象
├── entity/                       # JPA 实体（Customer、Policy、Claim、Quote、Vehicle）
├── guardrail/                    # Embabel Guardrail 实现
├── repository/                   # Spring Data JPA 仓库
├── service/
│   ├── AgentService.java         # Agent 编排服务（核保/理赔/支付流程）
│   ├── CacheService.java         # 缓存管理
│   ├── ChatService.java          # 聊天编排服务
│   ├── DataService.java          # 数据查询服务
│   ├── LlmSelectionService.java  # LLM 模型选择（fast/balanced/powerful）
│   ├── PaymentService.java       # 支付服务
│   ├── PolicyService.java        # 保单查询服务
│   ├── PremiumCalculationService.java  # 保费计算
│   └── RiskCalculationService.java     # 风险评分
└── EmbabelApplication.java       # Spring Boot 入口

src/test/java/com/embabel/insurance/
├── integration/                  # 集成测试（FakeOperationContext，无 LLM 调用）
│   ├── UnderwritingAgentIntegrationTest.java
│   ├── ClaimsAgentIntegrationTest.java
│   ├── ChatbotAgentIntegrationTest.java
│   └── MultiAgentE2EIntegrationTest.java
└── e2e/                          # E2E 测试（需要真实 LLM）
```

## 可观测性（可选）

项目内置了完整的可观测性支持，默认关闭以保持快速启动体验。

### 快速开启 Prometheus + Actuator

1. 编辑 `pom.xml`：取消 `spring-boot-starter-actuator` 和 `micrometer-registry-prometheus` 依赖的注释
2. 编辑 `src/main/resources/application.yml`：
   - 将 `embabel.observability.enabled` 改为 `true`
   - 取消 `management` 段注释

启动后即可访问：
- `http://localhost:8080/actuator/health` — 健康检查
- `http://localhost:8080/actuator/metrics` — 应用指标
- `http://localhost:8080/actuator/prometheus` — Prometheus 格式指标

### 开启 Langfuse（LLM 调用追踪）

在 Prometheus 基础上：

1. 编辑 `pom.xml`：取消 `opentelemetry-exporter-langfuse` 依赖的注释
2. 设置 Langfuse 环境变量：

```bash
export LANGFUSE_PUBLIC_KEY=pk-xxx
export LANGFUSE_SECRET_KEY=sk-xxx
```

启动本地 Langfuse（Docker）：

```bash
docker run -p 3000:3000 -e LANGFUSE_ENABLE_EXPERIMENTAL_FEATURES=true langfuse/langfuse:latest
```

> 或使用 [Langfuse Cloud](https://cloud.langfuse.com/)，将 `management.langfuse.endpoint` 改为云版地址。

## 测试

### 运行全部测试

```bash
./mvnw test
```

### 集成测试（无需 API Key）

集成测试使用 Embabel 的 `FakeOperationContext` 模拟 LLM 响应，无需真实 API 调用，覆盖完整的 Agent 工作流。

### E2E 测试（需要 DeepSeek API Key）

```bash
export DEEPSEEK_API_KEY=your-key
./mvnw test -Pe2e
```

## 许可

本项目基于 Apache 2.0 许可开源 - 详见 [LICENSE](LICENSE) 文件。
