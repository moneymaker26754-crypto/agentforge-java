# AgentForge 技术手册

## 1. 系统心智模型

AgentForge 把 LLM 定义成“可能出错、会重复、输出不完整的规划器”，而不是拥有系统权限的执行者。模型只能产生文本和声明式 ToolCall；Java 后端负责注册发现、Schema 校验、参数绑定、策略判断、审批、执行、审计与终止。这个边界使同一个 Agent Loop 可以替换 Provider，同时把安全和恢复语义留在确定性代码中。

一次运行的主链路是：恢复/初始化消息 → 上下文预算检查 → 流式模型调用 → ToolCall 聚合 → 注册表查找 → 参数与 Schema 校验 → 策略决策 → 可选人工审批 → 写入执行意图 → 沙箱执行 → 写入结果 → 回灌模型。模型给出无 ToolCall 的答案，或者任一预算/终止条件触发时退出。

## 2. Agent Loop 与状态机

`AgentEngine.run(RunRequest)` 创建 SessionId，并记录 `SESSION_STARTED`。运行态显式区分 `RUNNING`、`WAITING_APPROVAL`、`COMPLETED`、`FAILED`、`CANCELLED`、`BUDGET_EXHAUSTED` 和 `UNCERTAIN`，避免用一个布尔值混淆“失败”“需要人决定”和“费用耗尽”。

终止条件彼此独立：无 ToolCall 的最终答案、最大迭代、墙钟超时、输入/输出 token、费用、连续重复调用以及外部取消。检查既发生在模型调用前，也发生在 usage 回来后，因此不会因为一次超大响应而继续执行工具。

重复检测使用 `tool-name + canonical arguments` 的指纹，不使用 call id，因为模型可能每轮生成新 id。达到阈值时返回 `REPEATED_TOOL_CALL`，它是 Loop 级熔断而非工具失败。

## 3. 流式模型协议

### DeepSeek SSE

HTTP 使用 OpenAI-compatible `/chat/completions`。SSE 行可能是 keep-alive、`data: {json}` 或 `[DONE]`。工具名和 arguments 可能在任意字符位置拆分，解析器按 `choices[].delta.tool_calls[].index` 维护 accumulator，分别追加 id、name、arguments。只有流结束、JSON 结构完整后才产生 `ToolCall`。

### Ollama NDJSON

Ollama 每行是独立 JSON，结束标志和 usage 字段名称与 OpenAI 协议不同。实现没有复用 SSE 解析器，而是维护独立 NDJSON 状态机。工具结果消息使用 `tool_name`；DeepSeek 使用 `tool_call_id`，统一领域模型在序列化边界做协议映射。

### 错误映射

传输失败、HTTP 非 2xx、畸形 JSON、意外断流和 usage 缺失是不同问题。解析错误进入 `ModelProtocolException`，网络/限流进入 `ModelTransportException`。usage 缺失不会伪造成零成本的“成功事实”；生产演进应增加本地估算并标记 estimated。

## 4. ToolCall、反射、Schema 与 DI

工具实现 `ToolHandler<A extends Record>`，并用 `@AgentTool` 声明名称、描述和风险。Spring 只扫描容器中真实存在的 Bean；注册表在启动期反射读取泛型参数 record 与 `@ToolParam`，生成受控 JSON Schema。允许的边界类型包括字符串、布尔、数字、枚举和受控集合，不允许模型传类名，更不会按类名反射实例化任意对象。

调用顺序是：工具名查找 → JSON 语法解析 → required/unknown/type/range 检查 → Jackson 转 record → Jakarta/业务校验 → 执行。错误以稳定 code 返回给模型，例如 `UNKNOWN_TOOL`、`INVALID_ARGUMENT`、`POLICY_DENIED`，使下一轮能修正。参数 record 是不可变值，天然适合审计和并发读取。

## 5. 上下文管理

`ContextManager` 用可替换的 `TokenEstimator` 估算窗口；达到模型窗口 75% 才压缩。保留规则为：系统指令、第一条用户目标、确定性摘要以及最近 N 条消息。工具结果不会被无界保留。压缩前后 token 估算写入 `CONTEXT_COMPRESSED` 事件，可计算压缩率。

当前摘要是确定性裁剪，优点是零额外模型费用、完全可重复、恢复后相同；缺点是语义保真度弱。若改为模型摘要，应把摘要 prompt、模型版本、输入哈希和结果都写入事件流，否则无法复现实验。

## 6. 风险策略与人工介入

风险分为 READ、WRITE、EXECUTE、NETWORK、DESTRUCTIVE。默认矩阵：只读允许；写入和命令询问；网络只有显式开启才可询问；破坏性操作拒绝。策略在参数校验之后、执行意图落库之前运行，审批决定也进入审计链。

human-in-the-loop 不是简单 `Scanner`：概念上需要 `WAITING_APPROVAL` 状态、待审批调用、理由、过期策略和恢复点。CLI 首版同步等待输入；快照保存了完整消息和运行元数据，因此可扩展为异步审批。拒绝不会抛异常，而作为结构化工具结果反馈给模型，让模型换方案。

## 7. 文件与命令安全

路径检查先拒绝绝对路径和 `..`，再把目标规范化到 workspace；对已存在路径调用 real path，防止符号链接逃逸；新文件检查最近的已存在父目录。补丁工具使用精确旧块替换和大小上限，拒绝二进制内容。

命令工具只接收 argv 数组，不接收 shell 字符串，所以 `;`、`&&`、`$()` 不获得解释机会。执行器还有 allowlist、工作目录、超时、输出截断。Agent Loop 按模型返回顺序串行执行工具调用，避免工具之间看到不可重复的工作区状态；只读批次并行是路线图项，当前未实现。

## 8. Docker 与本地执行

默认 Docker 命令包含：`--network none`、`--cpus 2`、`--memory 4g`、`--pids-limit 256`、`--user 1000:1000`、只挂载目标工作区并固定 `/workspace`。超时由宿主 Java 进程控制，超时后销毁容器进程。

本地模式必须显式选择。它适合开发和 Docker 不可用时的低风险命令，但不是安全沙箱：Java allowlist 无法防御被允许二进制自身的漏洞，也无法提供内核级文件/网络隔离。

## 9. Checkpoint、Resume 与事件溯源

SQLite 有两层数据：append-only `session_events` 与最新 `session_snapshots`。每个事件的哈希为：

```text
SHA-256(previousHash + sessionId + sequence + type + occurredAt + payload)
```

重放时同时校验连续 sequence、previous hash 和当前 hash；任何篡改都会抛 `AuditIntegrityException`。快照是性能优化而不是事实来源，包含消息、usage、仓库、Provider、沙箱、预算、迭代数、重复调用状态和开始时间。

工具执行遵循 intent/result 双写：执行前落 `TOOL_INTENT`，完成后落 `TOOL_RESULT`。恢复发现 intent 无 result 时，幂等工具允许从安全快照继续；非幂等工具不能判断外部副作用是否发生，返回 `UNCERTAIN` 要求人决定，绝不静默重放。

## 10. 并发与背压

并行只适用于声明为幂等且风险为 READ 的工具，当前版本尚未实现：Agent Loop 按模型返回顺序逐个执行工具调用，写操作与命令串行，相当于为 workspace 提供一条可审计的提交日志。虚拟线程目前只用在子进程 I/O 上——`LocalSandboxExecutor` 用虚拟线程并发消费 stdout 与 stderr，避免单侧管道写满导致子进程阻塞（微基准 `runtime/concurrent-stream-drain` 覆盖该不变量）。若要做只读并行，还需要依赖分析、按 call index 回灌、全局并发上限与结构化取消。

流式解析中的 accumulator 按 call index 分区；同一 index 的碎片保持顺序。模型网络线程只负责解析并投递 delta，不直接执行工具，避免慢命令反向阻塞 HTTP body 消费。

## 11. 可观测性与脱敏

关键维度包括 session、iteration、provider/model、tool、risk、policy outcome、duration、usage、cost 和 termination reason。SQLite 保存可验证事实；Markdown/JSON 报告用于人读与机器处理。脱敏器递归处理 JSON 中的 token/key/password/authorization 字段，也替换 Bearer 模式；API Key 从环境读取，永不进入 prompt、事件或 manifest。

OTLP 是可选扩展：建议只发指标和 span attribute，不发原始源码、prompt 或工具输出；否则可观测后端会成为新的敏感数据副本。

## 12. 评测设计

30 个微基准是离线确定性回归，覆盖流碎片、未知工具、非法参数、路径逃逸、策略拒绝、超时、截断、重复调用、恢复和哈希篡改。它们证明不变量，不证明模型“会修 Bug”。

Java20 清单来自 `SWE-bench/SWE-bench_Multilingual` 固定 revision，识别 6 个 Java 仓库后按 instance_id 字典序取前 20，失败题不替换。任务失败与环境失败分栏；总费用达到 ¥50 必须停止并保留已有记录。

指标至少包括 resolved rate、测试通过率、输入/输出 token、估算费用、总时长、工具成功率、Schema 错误率、压缩率、审批次数和工具 p50/p95。baseline/full 比较必须使用同一 10 题、同一 Provider、同一时间与步骤预算；负向结果同样进入报告。

## 13. 关键权衡

1. 自研 Loop 而非直接用大型框架：代码路径短、面试可解释、恢复语义可控；代价是生态适配少。
2. record + 白名单 Schema 而非任意反射：牺牲工具签名自由度，换取启动期失败和更小攻击面。
3. 事件流 + 快照而非只存最终状态：多一次写入，换取崩溃定位、审计和可重复恢复。
4. Docker 默认而非本地默认：启动更重，但默认安全方向正确。
5. 确定性压缩而非模型摘要：语义能力较弱，但成本、延迟和复现更稳定。

## 14. 从代码出发的阅读顺序

先读 `DefaultAgentEngine` 理解控制流，再读 `DeepSeekSseParser`/`OllamaNdjsonParser` 理解协议差异；随后看 `ReflectiveToolRegistry` 和 `DefaultPolicyEngine`；最后看 `SqliteCheckpointStore`、`DockerCommandFactory` 与 eval 模块。测试文件与生产类一一对应，是准备面试追问最快的入口。
