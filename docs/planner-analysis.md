# Embabel 规划器（PlannerType）分析与优化建议

> 创建日期：2026-06-22
> 状态：待评估

---

## 一、PlannerType 概述

`PlannerType` 是 Embabel 框架的规划器枚举，决定 Agent 如何编排 `@Action` 的执行顺序。

### 4 个枚举值

| 枚举值 | `needsGoals` | 对应的规划器实现 | 本项目使用 |
|--------|-------------|----------------|----------|
| **GOAP** | `true` | `AStarGoapPlanner`（A* 搜索算法） | ❌ |
| **UTILITY** | `false` | `UtilityPlanner`（LLM 实时决策） | ✅ 全部 3 个 Agent |
| **HYBRID** | `true` | `HybridUtilityPlanner`（GOAP + UTILITY） | ❌ |
| **SUPERVISOR** | `true` | `AStarGoapPlanner`（同 GOAP，用于多 Agent 协作） | ❌ |

### 规划器对比

| 维度 | GOAP | UTILITY | HYBRID |
|------|------|---------|--------|
| 规划时机 | **执行前**预计算全部路径 | **执行中**每一步实时决定 | 先用 GOAP 规划大步骤，步骤内用 UTILITY |
| 决策者 | A* 算法（静态图搜索） | **LLM 自己**（动态感知） | 算法 + LLM 结合 |
| 路径确定性 | 高（预计算） | 低（每次可能不同） | 中 |
| 灵活性 | 固定流程 | 高度灵活 | 大方向固定，细节灵活 |
| Token 成本 | **0**（无 LLM 调用） | **约 1500 tokens/次** | 高于 GOAP，低于 UTILITY |

---

## 二、本项目 Agent 的 @Action 依赖分析

### UnderwritingAgent

```java
@Action(description = "使用LLM从用户输入中提取车辆信息")
public VehicleInfo extractVehicleInfo(UserInput input, OperationContext ctx) { ... }

@Action(description = "从数据库查找客户档案")
public Customer lookupCustomer(UserInput input, OperationContext ctx) { ... }

@Action(description = "从数据库查找车辆信息")
public Vehicle lookupVehicle(VehicleInfo info, Customer customer, OperationContext ctx) { ... }

@Action(description = "检查前置阶段错误，再计算风险评分并将投保申请分类到对应风险层级")
public UnderwritingDecision assessRisk(Customer customer, Vehicle vehicle, OperationContext ctx) { ... }
```

**参数依赖图**：

```
extractVehicleInfo(UserInput) → VehicleInfo
       │                           │
       │  lookupCustomer(UserInput) → Customer
       │                           │
       └──────┬────────────────────┘
              │
              ▼
    lookupVehicle(VehicleInfo, Customer) → Vehicle
              │                           │
              └──────┬────────────────────┘
                     │
                     ▼
         assessRisk(Customer, Vehicle) → UnderwritingDecision
                     │
                     ▼
              @State handler → UnderwritingResult
```

**结论：只有 1 条可能的执行路径，无分支选择。**

### ClaimsAgent

```java
verifyPolicy(UserInput, OperationContext) → Policy
    ↓
extractClaimInfo(UserInput, OperationContext) → ClaimInfo
    ↓
calculateFraudScore(ClaimInfo, Policy, OperationContext) → Double
    ↓
classify(ClaimInfo, Policy, OperationContext, Double) → ClaimDecision (@State)
    ↓
@State handler → ClaimResult
```

**结论：同样只有 1 条可能的执行路径。**

### ChatbotAgent

```java
@Action(description = "使用知识库回答用户的保险问题")
public ChatOutput answerQuestion(UserInput input, OperationContext ctx) { ... }
```

**结论：只有 1 个 Action，无规划需求。**

---

## 三、UTILITY 的 Token 成本

每次 UTILITY 规划器调用 LLM 进行"如何执行"的推理：

| 成本项 | 大约 Tokens |
|--------|-----------|
| 系统提示词（含所有工具定义） | ~1000 |
| 当前状态描述 | ~200 |
| LLM 推理思考输出 | ~200 |
| LLM 输出规划结果 | ~100 |
| **每次规划** | **~1500 tokens** |

### 按 Agent 统计

| Agent | 每次请求的规划调用 | tokens/请求 |
|-------|-----------------|-----------|
| UnderwritingAgent | 1 次规划 | ~1500 |
| ClaimsAgent | 1 次规划 | ~1500 |
| ChatbotAgent | 1 次规划 | ~1500 |
| **合计（一次完整流程）** | **最多 3 次** | **~4500** |

### 成本估算

按 DeepSeek 价格 ¥0.5/M 输入 tokens：

| 请求量/天 | 规划 tokens/天 | 规划成本/天 | 规划成本/月 |
|----------|--------------|-----------|-----------|
| 1,000 | 4,500,000 | ¥2.25 | ¥67.5 |
| 10,000 | 45,000,000 | ¥22.5 | ¥675 |
| 100,000 | 450,000,000 | ¥225 | ¥6,750 |

> 注意：这**只是规划消耗**，不包含实际业务处理的 LLM 调用（如 `extractVehicleInfo` 中的 LLM 提取、ChatbotAgent 的检索+生成等）。

---

## 四、改用 GOAP 的可行性分析

### 判断标准

UTILITY 在**有多个可选执行路径、需要 LLM 根据上下文做决策**时才有价值。当参数依赖图唯一确定执行顺序时，GOAP 的 A* 算法能直接推导出相同结果，且 Token 成本为 0。

### 各 Agent 评估

| Agent | 执行路径数 | 需要 LLM 决策？ | 改用 GOAP？ |
|-------|----------|---------------|-----------|
| **UnderwritingAgent** | 1 条（顺序确定） | ❌ | ✅ **可以** |
| **ClaimsAgent** | 1 条（顺序确定） | ❌ | ✅ **可以** |
| **ChatbotAgent** | 1 个 Action，无规划 | ❌ | 无规划器开销，改不改一样 |

### 真正需要 UTILITY 的场景

当 Agent 有多个可能的执行路径，且参数类型相同时，才需要 LLM 来决策：

```java
// 真正的 UTILITY 场景举例
@Action public Intent classifyIntent(UserInput input) { ... }

// 多个路径，参数类型相同，无法用参数依赖区分：
// → LLM 需要判断走哪条
if (intent == QUERY_PRICE)  → 走价格查询流程
if (intent == FILE_CLAIM)   → 走理赔流程
if (intent == COMPLAIN)     → 走投诉流程
```

### 更改方式

```java
// 当前
@Agent(
    description = "核保 Agent...",
    planner = PlannerType.UTILITY    // ← 改用 LLM 规划
)

// 改为
@Agent(
    description = "核保 Agent...",
    planner = PlannerType.GOAP       // ← 改用 A* 算法规划
)
```

只需改动 `@Agent` 注解的 `planner` 属性，不需要修改任何 `@Action` 方法的代码。

---

## 五、HYBRID 与 SUPERVISOR

### HYBRID（混合规划）

先用 GOAP 做高层路径规划（确定"要做什么"），然后在每个步骤中用 UTILITY 做具体执行（确定"怎么做"）。

```
GOAP 层：规划大步骤
  ├─ 步骤1: 核验身份
  ├─ 步骤2: 风险评估     ← A* 算法确定此顺序
  └─ 步骤3: 生成报价

UTILITY 层（每一步内）：
  步骤1 → LLM 自主决定：查数据库还是问用户补充信息？
  步骤2 → LLM 自主决定：调用哪个评分模型？
```

适合"大方向固定但执行细节需要灵活"的场景。

### SUPERVISOR（监督规划）

底层与 GOAP 使用同一个 `AStarGoapPlanner`，但框架在处理 `@GoalRef`、`@SubAgent` 等多 Agent 协作注解时的行为不同。用于**多 Agent 分工**场景 — 一个 Supervisor Agent 负责任务分解和委派给子 Agent。

---

## 六、总结

| Agent | 当前规划器 | 建议规划器 | 理由 |
|-------|----------|-----------|------|
| UnderwritingAgent | UTILITY | **GOAP** | 执行路径唯一，无需 LLM 决策 |
| ClaimsAgent | UTILITY | **GOAP** | 执行路径唯一，无需 LLM 决策 |
| ChatbotAgent | UTILITY | 保持 UTILITY | 仅 1 个 Action，无规划成本 |

### 预估收益

- Token 成本：**3 个 Agent 每次请求节省约 4500 tokens**
- 成本节省：每天 1 万请求约节省 ¥22.5/天
- 延迟降低：省去每次 ~500ms 的规划 LLM 调用
- 代码改动量：**3 行**（每个 `@Agent` 的 `planner` 属性）

### 潜在风险

- 如果未来某 Agent 的 @Action 有多个分支（相同参数类型但不同语义），GOAP 无法区分，需要改回 UTILITY
- A* 搜索在 @Action 数量极多时（>50 个）可能有性能问题，本项目每个 Agent 只有 4-5 个 @Action，无影响
