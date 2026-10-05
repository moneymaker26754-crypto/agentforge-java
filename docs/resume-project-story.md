# AgentForge 简历与项目讲述

## 简历标题

AgentForge Java — CI 失败诊断与受控自动修复 Agent（自研 Agent Runtime + Control Plane）

## 可直接使用的 bullet（业务视角，4 条）

写法为「**能力短语**：做法；业务价值」。完整业务链路（业务痛点 → 数据接入 → Agent 推理 → 上下文控制 → 执行动作 → 权限拦截 → 人工审批 → 恢复 → 验证）与按链路展开的 8 条详版见 [项目梳理与业务亮点](project-narrative.md)。

**CI 失败诊断与受控自动修复 Agent（AgentForge）** ｜ 个人项目 ｜ 2026.09–至今

> CI 失败后要在 Workflow、日志、Commit、Diff 与测试之间反复切换，根因常藏在最近变更里，人工拼接证据耗时；而把文件、命令与 Git 权限直接交给 LLM，又存在误改与越权风险。目标是让 Agent 在可审计、可恢复的边界内完成"定位→修复→验证"，高风险动作仍由人确认。

- **打通 CI 证据链**：以 Webhook 验签与适配层接入 CI，把 Workflow、失败 Job、日志、Commit、Diff 与仓库代码统一收束为标准工具结果，并按仓库+commit+run 幂等建任务；使失败现象可按变更精确关联，替代人工跨系统拼接证据的排查方式。
- **可解释的推理闭环**：把排查 SOP 固化为"计划—校验—权限—执行—观察—反思"六阶段，按 Token 预算只投喂最小必要证据（日志、测试输出、堆栈、Diff、文件清单按比例截断）；模型只产出工具调用与结论，每一步留下审计事件，归因链路可复核。
- **受控执行边界**：所有命令与改动只发生在断网、非 root、限资源的沙箱内，动作按只读、受限执行、人工审批、默认禁止四级分流；需确认的高风险动作生成含参数与依据的待批记录并挂起，人工确认后才会执行。
- **可恢复与可验证**：执行前写意图、执行后写结果，崩溃后从快照与事件重放恢复，结果未知的非幂等动作进入 UNCERTAIN 而非重放；以 38 项确定性回归与离线 CI 闭环 Benchmark 覆盖协议、边界、审批、恢复与预算，形成可重复的回归入口。

选择 3–5 条，避免把全部堆进一段。以下数字来自仓库可复现测试或固定配置，不把尚未运行的 Java21 结果包装成提升。

## 附录：细颗粒 bullet（按技术点备用）

- 基于 Java 21、Spring Boot 与 Picocli 设计五模块 Agent 系统（core / infrastructure / cli / server / eval），把 LLM 视为不可信 Planner：显式六阶段状态机 `PLAN→VALIDATE→PRE_TOOL_USE→EXECUTE→OBSERVE→REFLECT`，覆盖 6 类终止条件、75% 窗口压缩阈值和 30 次/20 分钟/200k 输入 token/30k 输出 token 的多维预算。
- 实现 DeepSeek SSE 与 Ollama NDJSON 双 Provider 流式适配，按 ToolCall index 聚合碎片化 name/arguments，并通过独立契约测试覆盖多调用、畸形 JSON、断流和 usage 映射。
- 设计 `@AgentTool + record` 类型化工具系统与统一 Tool Bus：原生工具、CI 工具与 MCP（官方 Java SDK stdio 桥）统一注册；PreToolUse 门禁按 Schema→Risk→Policy→Approval 拦截，模型无法传入类名或绕过注册表。
- 构建 Docker 默认沙箱（断网、非 root、2 CPU、4GB、256 PID）与显式本地模式，结合路径规范化、符号链接防逃逸、argv 命令、超时和输出截断保护工作区；定向测试容器是唯一受控的网络例外。
- 使用 SQLite append-only 事件流、SHA-256 前序哈希和运行快照实现 resume；人工审批把待批调用落库并挂起为 `WAITING_APPROVAL`，审批 API 决定后恢复；对“已记录执行意图但结果未知”的非幂等调用返回 `UNCERTAIN`，避免崩溃后静默重复副作用。
- 实现 GitHub Actions Webhook/API Adapter（HMAC-SHA256 验签、按 repository+commit+run 幂等建任务）与 Spring Boot Control Plane（Task/Session、审批、指标、有界执行器），并把 CI 上下文按 Token 预算组装为最小必要证据。
- 建立 38 项确定性微基准、2 项离线 CI 闭环 Benchmark 与固定 revision 的 SWE-bench Multilingual Java21 清单；本机 38/38，通过 JSON/CSV/manifest 保存原始结果，尚未执行完整 harness 的 20 题如实标记环境失败。
- 在 Docker 隔离环境完成真实 Java 修复演示：6 次模型迭代、7/7 工具成功、3 次人工审批，消耗 8,332 输入/641 输出 token，估算费用 ¥0.021792，并由测试输出 `PASS CalculatorTest` 验收。

## 30 秒项目介绍

我做了一个 CI 失败诊断与受控自动修复 Agent，但重点不只是调用模型，而是把 ToolCall 之后的后端工程补完整。模型通过 SSE 或 NDJSON 流式返回工具调用，Java 注册表做 Schema 和参数绑定，PreToolUse 门禁统一做风险、策略和审批，命令默认进断网 Docker，测试与 Patch 在沙箱里执行。每一步写入带哈希链的 SQLite 事件流，所以进程中断后能恢复；人工审批可以让会话挂起再由审批 API 恢复；如果非幂等工具的结果不确定，会进入 UNCERTAIN 而不是重复执行。Control Plane 接 GitHub Webhook 幂等建任务，我还做了 38 项基础设施微基准、离线 CI 闭环 Benchmark 和固定 Java21 清单，区分真实失败与环境失败。

## 2 分钟 STAR 讲述

**Situation**：多数 Agent Demo 把模型回复直接变成 shell 命令，难以解释安全、费用、崩溃恢复和效果数据。

**Task**：我希望做一个适合后端 Agent 岗面试的项目，既能完成 Java 仓库任务，又能在代码层回答 ToolCall、Schema、权限、checkpoint 和 eval。

**Action**：我把系统拆成 core/infrastructure/cli/server/eval 五层。核心是显式六阶段状态机与 PreToolUse 门禁；Provider 分别解析 SSE 和 NDJSON；工具用注解与 record 建模，模型只看到名字和 Schema；策略按 READ/WRITE/EXECUTE/NETWORK/DESTRUCTIVE 分级；执行前后写 intent/result 事件，SQLite 用前序哈希校验。上下文到 75% 才压缩，预算在模型调用前后都检查。Control Plane 用 Webhook 验签和业务键幂等建任务，把 CI 上下文按 Token 预算组装。最后固定数据 revision 和题目清单，README 数据只能由报告渲染。

**Result**：全项目单元/契约/安全/恢复/控制面测试 151 项通过，38 项离线微基准 38/38、2 项离线 CI 闭环 Benchmark 通过；Docker 沙箱边界验证通过，Java21 因完整 harness 尚未执行而产生 20 条环境失败记录，没有把它们算作模型失败，也没有消耗 DeepSeek 费用。

**Reflection**：当前确定性摘要可复现但语义较弱；Java21 harness 还没有在本机完整跑通；多实例共享存储和 OTLP 仍是路线图（异步审批已由 Control Plane 落地）。这些限制让我更明确：Agent 系统的可信度来自可验证边界，而不是演示时一次成功。

## 三个技术难点

### 1. 流式 ToolCall 不是一次 JSON

难点在于工具名和 arguments 可以任意拆分，多工具又交错到达。我的做法是按 index 建 accumulator，分别追加字段，到 finish 后才构造不可变 ToolCall。DeepSeek 和 Ollama 保留独立解析器，统一只发生在领域消息层。

### 2. Exactly-once 做不到时如何诚实恢复

外部命令执行与 SQLite 写结果不是一个原子事务。执行后崩溃会留下 intent、没有 result。对于只读幂等工具可以重放；写入/命令可能已产生副作用，因此状态必须是 UNCERTAIN，让人查看工作区和审计日志再决定。这比宣称 exactly-once 更符合分布式系统现实。

### 3. 安全不是关键词黑名单

我把安全拆成能力最小化和纵深防御：工具注册白名单、Schema、路径 real path、策略矩阵、审批、argv、Docker 资源/网络边界、超时和输出上限。任何一层都不单独承诺绝对安全，但组合后把模型能影响的范围缩小并留下证据。

## 追问时的证据路径

- Loop/预算：`DefaultAgentEngine` 与对应测试。
- 流协议：`DeepSeekSseParserTest`、`OllamaNdjsonParserTest`。
- Schema/反射：`ReflectiveToolRegistryTest`。
- 安全：`WorkspaceGuardTest`、`DockerCommandFactoryTest`。
- 恢复：`SqliteCheckpointStoreTest`、`DefaultAgentEngineTest`。
- 数据：`build/reports/agentforge` 中 JSON/CSV/manifest。

## 不应使用的表达

- “生产级”“零风险”“exactly-once”——当前证据不支持。
- “性能提升 50%”——没有同条件对照和足够样本。
- “支持所有模型/命令”——目前只有两种 Provider 和受限工具集。
- “20 题真实通过率”——当前 Java21 是环境失败记录，不是 resolved 结果。

## 复盘模板

1. 原假设是什么？
2. 用哪个可重复实验验证？
3. 指标的分母和失败分类是什么？
4. 结果是否支持假设，是否可能是噪声？
5. 哪个设计选择带来最大收益或复杂度？
6. 下一次只改变哪个变量？
