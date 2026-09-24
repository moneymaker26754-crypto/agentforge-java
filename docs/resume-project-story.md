# AgentForge 简历与项目讲述

## 简历标题

AgentForge Java — 支持流式 ToolCall、策略沙箱与断点恢复的 CLI 编程 Agent

## 可直接使用的 bullet

以下数字来自仓库可复现测试或固定配置，不把尚未运行的 Java20 结果包装成提升：

- 基于 Java 21、Spring Boot 与 Picocli 设计四模块 CLI 编程 Agent，自研 Agent Loop，覆盖 6 类终止条件、75% 窗口压缩阈值和 30 次/20 分钟/200k 输入 token/30k 输出 token 的多维预算。
- 实现 DeepSeek SSE 与 Ollama NDJSON 双 Provider 流式适配，按 ToolCall index 聚合碎片化 name/arguments，并通过独立契约测试覆盖多调用、畸形 JSON、断流和 usage 映射。
- 设计 `@AgentTool + record` 类型化工具系统，以反射生成受控 JSON Schema，完成注册、参数校验、Jackson 绑定、风险策略和 Spring DI；模型无法传入类名或绕过注册表。
- 构建 Docker 默认沙箱（断网、非 root、2 CPU、4GB、256 PID）与显式本地模式，结合路径规范化、符号链接防逃逸、argv 命令、超时和输出截断保护工作区。
- 使用 SQLite append-only 事件流、SHA-256 前序哈希和运行快照实现 resume；对“已记录执行意图但结果未知”的非幂等调用返回 `UNCERTAIN`，避免崩溃后静默重复副作用。
- 建立 30 项确定性微基准与固定 revision 的 SWE-bench Multilingual Java20 清单；本机离线微基准 30/30，通过 JSON/CSV/manifest 保存原始结果，尚未执行完整 harness 的 20 题如实标记环境失败。
- 在 Docker 隔离环境完成真实 Java 修复演示：6 次模型迭代、7/7 工具成功、3 次人工审批，消耗 8,332 输入/641 输出 token，估算费用 ¥0.021792，并由测试输出 `PASS CalculatorTest` 验收。

选择 3–5 条，避免把全部堆进一段。若之后获得真实 Java20 结果，再用报告中的 resolved、费用、时长和 p95 替换最后一条，不能提前填写。

## 30 秒项目介绍

我做了一个 Java CLI 编程 Agent，但重点不只是调用模型，而是把 ToolCall 之后的后端工程补完整。模型通过 SSE 或 NDJSON 流式返回工具调用，Java 注册表做 Schema 和参数绑定，策略层决定允许、拒绝还是人工审批，命令默认进断网 Docker。每一步写入带哈希链的 SQLite 事件流，所以进程中断后能恢复；如果非幂等工具的结果不确定，会进入 UNCERTAIN 而不是重复执行。我还做了 30 项基础设施微基准和固定 Java20 清单，区分真实失败与环境失败。

## 2 分钟 STAR 讲述

**Situation**：多数 Agent Demo 把模型回复直接变成 shell 命令，难以解释安全、费用、崩溃恢复和效果数据。

**Task**：我希望做一个适合后端 Agent 岗面试的项目，既能完成 Java 仓库任务，又能在代码层回答 ToolCall、Schema、权限、checkpoint 和 eval。

**Action**：我把系统拆成 core/infrastructure/cli/eval 四层。核心是确定性状态机；Provider 分别解析 SSE 和 NDJSON；工具用注解与 record 建模，模型只看到名字和 Schema；策略按 READ/WRITE/EXECUTE/NETWORK/DESTRUCTIVE 分级；执行前后写 intent/result 事件，SQLite 用前序哈希校验。上下文到 75% 才压缩，预算在模型调用前后都检查。最后固定数据 revision 和题目清单，README 数据只能由报告渲染。

**Result**：全项目单元/契约/安全/恢复测试可通过，30 项离线微基准 30/30；Docker 沙箱边界验证通过，Java20 因完整 harness 尚未执行而产生 20 条环境失败记录，没有把它们算作模型失败，也没有消耗 DeepSeek 费用。

**Reflection**：当前确定性摘要可复现但语义较弱；Java20 harness 还没有在本机完整跑通；OTLP 和异步审批仍是路线图。这些限制让我更明确：Agent 系统的可信度来自可验证边界，而不是演示时一次成功。

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
- “20 题真实通过率”——当前 Java20 是环境失败记录，不是 resolved 结果。

## 复盘模板

1. 原假设是什么？
2. 用哪个可重复实验验证？
3. 指标的分母和失败分类是什么？
4. 结果是否支持假设，是否可能是噪声？
5. 哪个设计选择带来最大收益或复杂度？
6. 下一次只改变哪个变量？
