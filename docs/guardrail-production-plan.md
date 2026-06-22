# 生产级 Guardrail（护栏）实施计划

> 创建日期：2026-06-22
> 状态：待排期

## 背景

当前项目的 Guardrail 基于硬编码正则和关键词（`InsuranceUserInputGuardRailImpl`），属于教学演示级别。生产环境需要补充多层防御、动态策略和可观测性。

---

## 一、核心风险矩阵

| 风险 | 严重程度 | 当前覆盖 | 目标覆盖 |
|------|---------|---------|---------|
| DDoS / API 费用失控 | 🔴 高 | ❌ 无 | Layer 1 速率限制 |
| 提示注入导致误操作 | 🔴 高 | ⚠️ 基础正则 | Layer 2 语义检测 |
| PII 泄露（身份证、银行卡） | 🔴 高 | ❌ 无 | Layer 4 输出脱敏 |
| 合规违规（承诺理赔等） | 🔴 高 | ❌ 无 | Layer 4 规则检查 |
| 变体注入（byp@ss 等） | 🟡 中 | ❌ 正则已绕过 | Layer 2 模型分类器 |
| 幻觉导致用户损失 | 🟡 中 | ❌ 无 | Layer 4 引用检查 |
| 多轮累积攻击 | 🟡 中 | ❌ 无 | Layer 3 LLM-Judge |
| 非保险主题消耗 token | 🟢 低 | ⚠️ 9 个黑名单词 | Layer 2 意图分类 |

---

## 二、架构目标

```
用户输入
   │
   ▼
┌─ Layer 1: 前置快速过滤 ──────────────────────────┐
│  速率限制 │ 长度限制 │ 字符集清洗 │ Unicode 规范化  │
└──────────────────────────────────────────────────┘
   │
   ▼
┌─ Layer 2: 语义级检测（50-200ms）──────────────────┐
│  注入分类器 │ 意图分类 │ PII 识别 │ 敏感内容检测    │
└──────────────────────────────────────────────────┘
   │
   ▼
┌─ Layer 3: LLM-as-Judge（可选，采样执行）──────────┐
│  对抗性检测 │ 多轮攻击链 │ 业务合规校验             │
└──────────────────────────────────────────────────┘
   │
   ▼  Agent 执行...
   │
   ▼
┌─ Layer 4: 输出护栏 ──────────────────────────────┐
│  PII 脱敏 │ 幻觉检测 │ 合规约束 │ 质量评分        │
└──────────────────────────────────────────────────┘
```

---

## 三、分阶段实施计划

### Phase 1 — 安全底线（预估：2 天）

> 目标：堵住最明显的风险，先上线

| 任务 | 实现方式 | 优先级 |
|------|---------|-------|
| 速率限制 | `Bucket4j` 或 Spring Boot 内置 + 按用户限流 | P0 |
| 输入长度限制 | 拦截 > 10000 字符的输入 | P0 |
| PII 正则脱敏（输出） | 身份证、银行卡、手机号正则替换为 `***` | P0 |
| 业务合规规则（输出） | 关键词拦截"保证理赔""100%赔付"等 | P0 |
| OWASP 输入清洗 | 使用 `owasp-java-encoder` 清洗控制字符 | P1 |

### Phase 2 — 主要风险覆盖（预估：1-2 周）

> 目标：替代当前教学级正则，建立可观测性

| 任务 | 实现方式 | 优先级 |
|------|---------|-------|
| 扩展提示注入词库 | 收集已知攻击模式，覆盖变体 | P1 |
| RAG 引用检查 | 输出必须引用检索到的文档片段 | P1 |
| 简单意图分类 | Embedding 相似度 + 阈值判断 | P2 |
| 前置过滤配置化 | 将规则从代码抽到 yml | P2 |
| 基础监控 | 拦截率、延迟、API 成本 | P2 |

### Phase 3 — 持续迭代（长期）

> 目标：模型替代规则、动态策略、审计

| 任务 | 实现方式 | 优先级 |
|------|---------|-------|
| 注入检测模型 | 微调 BERT/RoBERTa，ONNX 部署 | P3 |
| LLM-as-Judge | 采样审查高风险输入 | P3 |
| 多轮检测 | 会话上下文攻击链分析 | P3 |
| 配置中心 | Apollo/Nacos 动态下发规则 | P3 |
| 审计日志 | 全量 Guardrail 事件入库 Elastic | P3 |
| 分级处置 | 警告/阻断/人工审核/白名单 | P3 |

---

## 四、技术选型建议

| 场景 | 推荐方案 | 备选方案 |
|------|---------|---------|
| 速率限制 | Bucket4j | Spring Boot Actuator + 自定义 Filter |
| 输入清洗 | OWASP Java Encoder | 自实现白名单 |
| 注入检测（初期） | 扩展正则 + 攻击词库 | OWASP LLM Prompt Injection Dataset |
| 注入检测（长期） | 微调 `microsoft/deberta-v3-base` | 调用 DeepSeek API 做分类 |
| PII 检测 | Microsoft Presidio | Apache Shiro + 自定义正则 |
| 配置管理 | Spring Cloud Config / Nacos | YAML 文件 |
| 监控 | Micrometer + Prometheus + Grafana | 自家监控系统 |
| 审计日志 | ELK (Elasticsearch + Logstash + Kibana) | 数据库 + 定时分析 |

---

## 五、参考资源

- [OWASP LLM Prompt Injection Guide](https://genai.owasp.org/)
- [Guardrails AI](https://github.com/guardrails-ai/guardrails)
- [NVIDIA NeMo Guardrails](https://github.com/NVIDIA/NeMo-Guardrails)
- [Microsoft Presidio (PII Detection)](https://github.com/microsoft/presidio)
- [Bucket4j (Rate Limiting)](https://github.com/vladimir-bukhtoyarov/bucket4j)
