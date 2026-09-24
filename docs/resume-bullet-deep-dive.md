# AgentForge 项目梳理与简历 Bullet 深挖

本文用于把 AgentForge 的简历描述展开成可直接回答的项目材料。内容以当前仓库实现和可复现结果为准，重点同时展示 Agent 能力与 Java 后端工程能力。

## 1. 脱敏口径

回答时可以公开技术方案、模块边界和聚合指标，但不透露以下内容：

- API Key、Authorization、账号信息和环境变量实际值。
- 本机绝对路径、Session ID、个人邮箱和本地环境标识。
- 未公开的目标仓库名称、业务代码、完整 Prompt 和原始日志正文。
- 单次调用的供应商账单明细和可关联个人账号的请求信息。

本文保留以下可验证但不可识别个人的信息：

- 技术栈、接口设计、状态转换和安全边界。
- 自动化测试数量、微基准结果和聚合工具调用结果。
- Docker 的通用资源限制和公开模型类型。

推荐统一使用“一个独立 Java 示例仓库”“一次真实修复任务”“目标工作区”等表述，不说本机目录或具体 Session ID。

## 2. 项目整体定位

AgentForge 是一个基于 Java 21 的 CLI 编程 Agent。它不把大模型当作拥有系统权限的执行者，而是把模型限制为“不可信规划器”：模型负责提出文本答案或声明式 ToolCall，Java 后端负责协议解析、工具注册、参数校验、权限决策、人工审批、沙箱执行、状态持久化和循环终止。

项目解决的不是“如何请求一次模型 API”，而是模型开始调用工具后出现的后端工程问题：

1. 流式 ToolCall 被任意拆分时，如何得到完整、确定的调用。
2. 模型提供的 JSON 参数如何安全绑定到 Java 方法。
3. 文件修改和命令执行如何限制在目标工作区内。
4. Agent 长时间循环时如何限制上下文、Token、费用和时间。
5. 进程崩溃后如何恢复，同时避免重复非幂等副作用。
6. 如何留下可校验、可脱敏的运行记录。

## 3. 一次任务的完整链路

```text
CLI 接收仓库、任务、Provider 和沙箱模式
  -> Spring Boot 组装 ModelClient、ToolRegistry、PolicyEngine、CheckpointStore
  -> AgentEngine 创建系统消息和用户任务
  -> 调用前检查迭代、时间、Token 和费用预算
  -> ContextManager 判断是否需要压缩历史消息
  -> DeepSeek SSE / Ollama NDJSON 流式响应
  -> 按 index 聚合 ToolCall 的 id、name 和 arguments
  -> 根据工具名查询注册表
  -> JSON Schema、字段、类型和范围校验
  -> 风险策略返回 ALLOW / ASK / DENY
  -> 可选人工审批
  -> 先写 TOOL_INTENT 事件
  -> Docker 或本地受限执行器运行工具
  -> 写 TOOL_RESULT 并将结果回灌模型
  -> 保存 SQLite 快照并进入下一轮
  -> 最终回答或任一终止条件结束循环
```

后端工程能力与实现的对应关系如下：

| 后端能力 | 项目实现 |
|---|---|
| 领域建模 | `RunRequest`、`RunResult`、`ToolCall`、`PolicyDecision` 等不可变 record |
| 状态机与编排 | `DefaultAgentEngine` 管理循环、预算、审批、执行和终止 |
| 协议适配 | DeepSeek SSE 与 Ollama NDJSON 独立解析器 |
| 插件化设计 | `ToolHandler` SPI、`@AgentTool`、Spring DI、反射注册表 |
| 输入校验 | JSON Schema、未知字段拒绝、类型与范围校验、Jackson 绑定 |
| 安全治理 | 风险矩阵、人工审批、路径防逃逸、命令白名单、Docker 沙箱 |
| 数据一致性 | SQLite 顺序事件、唯一序号、快照、SHA-256 前序哈希 |
| 故障恢复 | replay、resume、幂等判断和 `UNCERTAIN` 状态 |
| 可观测与脱敏 | Markdown/JSON 报告、敏感字段递归脱敏 |
| 工程质量 | 单元测试、契约测试、安全测试、JaCoCo、Linux/Windows CI |

---

## 4. Bullet 一：Agent Loop、状态机与预算控制

### 简历 Bullet

> 基于 Java 21、Spring Boot 与 Picocli 设计模块化 CLI 编程 Agent，自研状态机式 Agent Loop，统一管理上下文压缩、Token/费用预算、超时、重复调用检测及多类终止条件。

### 为什么做

如果只写一个 `while` 循环不断请求模型，会出现三个问题：

- 模型可能持续调用同一个工具，形成死循环。
- 长对话会突破模型窗口或产生不可控费用。
- 超时、预算耗尽和正常完成混在一起，调用方无法判断任务为何停止。

因此我没有把循环控制交给 Prompt，而是将它建模为确定性的 Java 状态机，由后端代码掌握终止权。

### 怎么做

- 在 `agentforge-core` 定义 `AgentEngine`、`RunRequest`、`RunResult`、`RunStatus` 和 `TerminationReason`，核心层不依赖 Spring、SQLite 或具体模型。
- 每次模型调用前检查最大迭代、墙钟时间、输入 Token、输出 Token 和费用；模型返回 usage 后再次检查，避免一次超大响应后继续执行工具。
- 用 ToolCall 的稳定指纹检测连续重复调用，到达阈值后以 `REPEATED_TOOL_CALL` 结束。
- 模型没有返回 ToolCall 时，视为最终回答并进入 `COMPLETED`。
- `ContextManager` 在估算上下文达到窗口阈值后，保留首条系统约束、原始用户任务和最近消息，将较早内容压缩为摘要。
- 使用 `Clock` 注入时间，使墙钟超时可以在单元测试中确定性验证。

### 有什么好处

- 循环行为可预测、可测试，不依赖模型“自觉停止”。
- 终止原因是结构化枚举，CLI、报告和恢复逻辑可以统一处理。
- 核心层通过 SPI 与模型、工具和存储解耦，可以用 scripted model 快速覆盖边界条件。
- 上下文压缩保留系统约束和近期工具交互，降低长任务丢失关键状态的概率。

### 产出与证据

- 默认预算覆盖迭代、墙钟时间、输入/输出 Token、费用和重复调用次数。
- 测试覆盖最终回答、最大迭代、Token 预算、墙钟超时、重复 ToolCall 和上下文压缩。
- 真实修复任务在有限轮次内正常进入 `COMPLETED`，没有依靠外部强制终止。

### 30 秒回答模板

> 我没有把 Agent Loop 写成一个无限 while，而是把运行状态、预算和终止原因都放进 core 层。每轮调用前后都会检查迭代、时间、Token 和费用，还会对 ToolCall 做指纹去重，连续重复就停止。上下文达到阈值后保留系统约束、原始任务和最近消息，再压缩较早历史。这样模型只负责规划，什么时候停止由可测试的 Java 状态机决定。

### 常见追问

**为什么调用前后都检查预算？**

调用前检查可以阻止已超预算的请求；调用后检查可以捕获本次响应刚刚造成的超额。如果只在调用前检查，一次异常大的响应仍可能继续触发高风险工具。

**为什么不用模型做语义摘要？**

当前选择确定性摘要，是为了降低额外调用成本并保证恢复时结果稳定。缺点是语义质量弱于模型摘要，后续可以增加可验证的模型摘要，但原始事件仍应保留。

**这个设计体现什么后端能力？**

体现状态机建模、依赖倒置、预算治理、可测试时间源和异常状态分类，而不是只会拼 Prompt。

---

## 5. Bullet 二：双 Provider 流式协议与类型化工具系统

### 简历 Bullet

> 实现 DeepSeek SSE 与 Ollama NDJSON 双 Provider 流式适配，按 ToolCall index 聚合碎片化参数；基于 `@AgentTool + record + Jackson` 自动生成 JSON Schema，完成工具发现、类型校验和参数绑定。

### 为什么做

ToolCall 在网络上不是一次完整 JSON。SSE 可能把函数名和 arguments 拆到任意字符位置，多个调用还可能交错；Ollama 使用逐行 NDJSON，结束标志、usage 字段和工具结果消息格式也与 OpenAI-compatible 协议不同。

如果直接把某一帧反序列化成方法参数，会出现半截 JSON、调用错位和 Provider 耦合。即使拿到完整 JSON，也不能直接反射执行任意类名或方法名。

### 怎么做

- 为 DeepSeek 和 Ollama 保留独立解析器，不假设两个协议完全一致。
- `ToolCallAccumulator` 使用有序 Map 按 index 保存部分调用，分别追加 id、name 和 arguments，流结束后才创建不可变 `ToolCall`。
- 在协议序列化边界处理差异：DeepSeek 工具结果使用 `tool_call_id`，Ollama 使用 `tool_name`。
- 使用 `@AgentTool` 作为 Spring `@Component` 元注解，启动时收集所有 `ToolHandler<?>` Bean。
- 从 `ToolHandler<A extends Record>` 的泛型参数定位参数 record，通过 record component 和 `@ToolParam` 生成 JSON Schema。
- Schema 明确 `additionalProperties=false`，执行前拒绝未知字段、缺失必填项、错误类型和越界数值，再由 Jackson 转成参数 record。
- 模型只看到工具名、描述和 Schema，不能提交 Java 类名。

### 有什么好处

- Provider 差异被隔离在基础设施层，Agent Loop 只处理统一领域对象。
- 工具参数在进入业务代码前已经完成结构化校验，错误可以作为明确的工具结果反馈给模型。
- 新增工具只需要实现 `ToolHandler` 并声明注解与参数 record，不需要修改核心循环。
- 工具注册白名单阻断了模型通过类名进行任意反射调用的路径。

### 产出与证据

- 已接入 DeepSeek OpenAI-compatible 流式接口和 Ollama 原生 NDJSON 接口。
- 契约测试覆盖函数名/参数碎片、多个 ToolCall、畸形 JSON、usage 映射和工具结果字段差异。
- 工具注册测试覆盖重复名称、未知字段、缺失参数、错误类型、数值范围和 `List<String>` Schema。

### 30 秒回答模板

> 流式 ToolCall 的难点是它不是一次完整 JSON。我按调用 index 建 accumulator，分别追加函数名和参数片段，只有流结束后才生成 ToolCall。DeepSeek 和 Ollama 的协议不同，所以各自保留解析器，只在领域层统一。工具侧用 `@AgentTool` 扫描 Spring Bean，从参数 record 生成 Schema，先校验未知字段、类型和范围，再用 Jackson 绑定，因此模型只能调用注册过的工具，不能指定任意 Java 类。

### 常见追问

**为什么不共用一个流式解析器？**

可以复用 accumulator 和领域模型，但不能强行复用传输状态机。SSE 和 NDJSON 的帧边界、完成信号、usage 位置和工具消息字段不同，强行统一会把大量 Provider 条件判断塞进同一个解析器。

**为什么参数必须是 record？**

record 结构固定、组件可反射、不可变，适合作为工具输入 DTO。它比任意 POJO 更容易限制支持类型并稳定生成 Schema。

**Schema 能否完全保证安全？**

不能。Schema 解决结构和类型问题，路径权限、命令能力和副作用还要由策略层与沙箱处理，这也是项目采用纵深防御的原因。

---

## 6. Bullet 三：权限策略与 Docker 沙箱

### 简历 Bullet

> 构建分层安全执行体系，通过工具白名单、路径规范化、符号链接防逃逸、argv 命令、风险审批及 Docker 非 root/断网/资源限制，约束模型对宿主工作区的影响范围。

### 为什么做

模型输出和仓库内容都可能不可信。如果模型能直接拼接 Shell 字符串或访问任意路径，Prompt Injection 就可能转化为文件泄露、命令注入或破坏性修改。单靠关键词黑名单无法覆盖路径编码、符号链接和合法命令的危险参数。

### 怎么做

- 工具注册表本身就是能力白名单，模型无法调用系统中未注册的能力。
- 每个工具声明 `READ / WRITE / EXECUTE / NETWORK / DESTRUCTIVE` 风险等级。
- 策略矩阵对只读操作自动允许，对写入和进程执行要求人工审批，对默认网络和破坏性操作直接拒绝。
- `WorkspaceGuard` 拒绝绝对路径，对相对路径做 normalize，并对已存在路径或最近存在父目录调用 `toRealPath()`，防止 `..` 和符号链接逃逸工作区。
- 命令参数使用 `List<String>` 传入 `ProcessBuilder`，不经过 Shell 字符串拼接。
- 本地执行器限制可执行文件白名单、工作目录、超时和输出大小；虚拟线程并发读取 stdout/stderr，避免单侧管道写满导致子进程阻塞。
- Docker 模式固定非 root 用户、关闭网络，并限制 CPU、内存和 PID，只挂载目标工作区。
- 补丁工具要求 expected 文本唯一匹配，并限制替换块大小，避免模糊修改和超大写入。

### 有什么好处

- 将安全从“模型是否听话”转化为后端可执行的能力约束。
- 即使一层校验失效，注册表、策略、路径守卫、argv 和容器仍能提供后续边界。
- 人工审批只出现在有副作用的写入和执行阶段，兼顾自动化效率与控制权。
- 输出截断和超时防止工具消耗无限内存或让 Agent 永久等待。

### 产出与证据

- Docker 沙箱验证了非 root、默认断网以及 CPU、内存和 PID 限制。
- 安全测试覆盖 `..`、绝对路径、符号链接逃逸、非法可执行文件、命令超时、输出截断和策略拒绝。
- 真实修复流程中的补丁、编译和测试均经过风险判断与人工审批。

### 30 秒回答模板

> 我把模型和仓库内容都当作不可信输入。模型只能调用注册工具，参数先过 Schema；路径再经过 normalize 和 real path 校验，命令通过 argv 交给 ProcessBuilder，不拼 Shell。策略层对读操作放行，对写和执行要求审批，对网络和破坏性能力默认拒绝。命令最终进入非 root、断网并有限额的 Docker。每一层都不是绝对安全，但组合后能把模型的影响范围压缩到工作区并留下审批记录。

### 常见追问

**argv 为什么比 Shell 字符串安全？**

argv 不会把空格、分号、管道符和命令替换重新解释为 Shell 语法，减少命令注入面。但被允许的程序本身仍可能有危险参数，因此还需要白名单、审批和容器。

**Docker 是否等于绝对安全？**

不是。Docker 共享宿主内核，daemon 权限和镜像供应链仍是风险。这里的定位是缩小默认权限和影响半径，不宣称虚拟机级隔离。

**为什么写操作需要人工审批？**

Schema 只能证明参数格式正确，不能判断业务意图是否正确。审批让用户在副作用真正发生前看到工具和风险原因。

---

## 7. Bullet 四：Checkpoint、事件溯源与崩溃恢复

### 简历 Bullet

> 基于 SQLite append-only 事件流、运行快照与 SHA-256 前序哈希链实现 checkpoint/resume；针对执行意图已落库但结果未知的非幂等调用进入 `UNCERTAIN`，避免崩溃恢复时静默重复副作用。

### 为什么做

Agent 任务可能持续多轮模型调用和工具执行，进程可能在任意位置退出。最危险的窗口是：工具已经产生副作用，但结果还没有写入数据库。如果恢复逻辑简单地重放最后一步，可能再次修改文件或再次执行命令。

数据库事务也无法与外部进程组成真正的原子事务，所以不能声称实现了 exactly-once。

### 怎么做

- SQLite 保存两类数据：不可变的 `session_events` 和最新的 `session_snapshots`。
- 事件使用 `(session_id, sequence)` 主键保证会话内顺序；append 时检查下一个期望序号。
- 每个事件哈希包含前序哈希、会话、序号、类型、时间和 payload；replay 时重新计算并验证完整链。
- 模型响应后和工具结果后保存快照，快照包含消息、usage、请求参数、迭代次数、重复调用指纹和开始时间。
- 工具执行前先写 `TOOL_INTENT`，完成后再写 `TOOL_RESULT`。
- resume 时重放事件并读取快照；如果发现没有匹配 result 的 intent，检查工具幂等属性。非幂等工具返回 `UNCERTAIN`，要求人工核对，而不是自动重放。
- 已完成或已终止的会话直接返回持久化结果，不重复调用模型。

### 有什么好处

- 崩溃恢复不依赖内存状态，CLI 可以跨进程继续任务。
- 明确区分“失败”“预算耗尽”和“副作用未知”，调用方不会把不确定状态误认为普通失败。
- 哈希链能够发现事件被修改、删除、插入或重排，而不只是保存普通日志。
- 承认外部副作用无法天然 exactly-once，比静默重试更符合分布式系统的一致性现实。

### 产出与证据

- 测试覆盖事件顺序、哈希篡改检测、快照读取、完成会话恢复、终止会话恢复和未决非幂等调用。
- 真实任务的模型响应、审批、执行意图、工具结果和完成事件均可按序回放。
- 报告导出前会递归脱敏 Token、Key、Password、Authorization 和 Bearer 内容。

### 30 秒回答模板

> SQLite 里我同时保存 append-only 事件和最新快照。工具执行前先写 intent，结束后写 result；每个事件都带前序哈希，恢复时会校验顺序和哈希链。外部命令和数据库不可能组成同一个事务，所以如果恢复时只看到非幂等 intent、看不到 result，我不会宣称 exactly-once，也不会自动重放，而是返回 UNCERTAIN 让用户核对工作区。这避免了恢复过程静默制造第二次副作用。

### 常见追问

**事件流和快照为什么要同时存在？**

事件流保留事实和审计能力，但每次从头还原成本会随会话增长；快照提供快速恢复状态。快照损坏时仍可以通过事件检查问题，二者职责不同。

**哈希链能防止有权限的人重写整个数据库吗？**

不能完全防止。如果攻击者能重写所有事件和根哈希，单库内校验无法识别。它主要用于发现局部篡改和意外损坏；更高安全等级需要把链头定期写入外部可信存储或签名服务。

**为什么不直接重放幂等工具？**

幂等属性只说明重复执行结果可接受，不代表必须立即重放。当前实现允许恢复循环继续，由模型根据恢复状态重新规划；关键保证是非幂等调用不会被静默重复。

---

## 8. Bullet 五：自动化验证与真实产出

### 简历 Bullet

> 建立覆盖协议解析、参数校验、路径安全、预算终止和故障恢复的自动化验证体系；完成 54 项测试、30/30 确定性微基准和 Linux/Windows CI，并在真实修复任务中实现 7/7 工具调用成功。

### 为什么做

Agent 的一次成功演示不能证明系统可靠。模型行为有随机性，外部环境也可能失败；如果不区分协议错误、工具错误、环境错误和任务失败，就很容易只展示成功案例。

因此需要把确定性的基础设施能力和模型解决真实任务的能力分开验证。

### 怎么做

- 核心状态机使用 scripted model 和内存式测试依赖，精确构造最终回答、重复调用、预算耗尽和崩溃位置。
- Provider 测试直接输入拆分后的 SSE/NDJSON 帧，验证协议聚合而不依赖真实网络。
- 工具与安全测试覆盖未知工具、非法参数、路径逃逸、策略拒绝、超时和输出截断。
- SQLite 测试修改存储事件，验证哈希链能够检测篡改。
- 建立 30 个离线微基准，覆盖协议、工具、安全、运行时、循环、恢复和审计类别。
- GitHub Actions 在 Linux 和 Windows 执行 Maven verify 和覆盖率门槛，Linux 额外构建并检查 Docker 沙箱边界。
- 使用一次真实模型任务完成读取、补丁、编译和测试闭环，验证各组件能够协同工作。

### 有什么好处

- 随机模型行为与确定性基础设施缺陷可以分开定位。
- 跨平台 CI 能发现 Windows 路径、行尾和进程行为与 Linux 的差异。
- 失败样本和环境失败不会被包装成模型能力，数据口径更可信。
- 指标可以映射回具体代码和测试，不是无法复现的简历数字。

### 产出与证据

- 54 项单元、契约、安全、恢复和 CLI 测试通过。
- 30 项确定性微基准全部通过。
- GitHub Actions 在 Linux/Windows 运行，Linux 额外验证 Docker。
- 一次脱敏真实修复任务完成 6 轮模型响应、7 次工具调用且全部成功，最终编译与测试通过。
- 当前没有把尚未执行完整 harness 的外部数据集记录宣称为 resolved 结果。

### 30 秒回答模板

> 我把 Agent 的测试分成两层：第一层是确定性基础设施，用 scripted model 和协议帧覆盖状态机、流式解析、安全策略和崩溃恢复；第二层才是真实模型端到端任务。当前有 54 项自动化测试和 30 项微基准，并在 Linux、Windows 和 Docker 环境验证。真实修复任务中 7 次工具调用全部成功并通过最终测试，但我没有把未跑完整 harness 的数据包装成模型通过率。

### 常见追问

**微基准 30/30 是否代表模型能力强？**

不代表。它证明协议解析、安全边界和恢复不变量没有回归，不等价于模型解决真实 Issue 的成功率。模型效果需要独立数据集和统一预算评估。

**为什么还需要真实模型任务？**

scripted model 能精确覆盖分支，但不能证明真实模型会正确选择工具和组织参数。真实任务用于验证组件集成，两者不能互相替代。

**为什么强调环境失败？**

构建镜像、依赖下载或测试 harness 失败不应算作模型失败，也不能从分母中偷偷删除。单独分类才能保证指标可解释。

---

## 9. 两分钟完整回答模板

> 我做了一个 Java CLI 编程 Agent，核心思路是把模型当作不可信规划器，把执行权留在确定性后端。
>
> 用户提交仓库和任务后，core 层的状态机先检查迭代、时间、Token 和费用预算，再调用 Provider。DeepSeek 是 SSE，Ollama 是 NDJSON，我分别实现解析器，按 ToolCall index 聚合碎片化的函数名和参数。完整调用进入工具注册表后，系统从 `@AgentTool` 和参数 record 生成 JSON Schema，先拒绝未知字段、错误类型和越界参数，再用 Jackson 绑定。
>
> 安全上我做了多层边界：模型只能调用白名单工具；路径会做 normalize 和 real path 检查；命令使用 argv，不经过 Shell；写入和执行要经过风险策略与人工审批；默认再放入非 root、断网并有限额的 Docker。这样即使仓库里存在 Prompt Injection，模型也不能直接获得宿主权限。
>
> 可靠性上，运行过程写入 SQLite 事件流和快照。工具执行前写 intent，执行后写 result，事件之间用 SHA-256 前序哈希连接。恢复时如果发现一个非幂等工具只有 intent 没有 result，我不会自动重放，而是返回 UNCERTAIN，让用户核对实际副作用。这是因为数据库和外部命令无法真正做到原子提交。
>
> 最后我用 scripted model、协议帧、安全测试和故障注入验证确定性部分，并用真实模型完成了一次从读取、修改到编译测试的闭环。项目体现的不只是模型 API 调用，还包括状态机、协议适配、类型系统、安全治理、事件溯源和跨平台工程化。

## 10. 回答时应主动说明的取舍

- **没有宣称 exactly-once**：外部命令与数据库无法原子提交，非幂等未决调用使用 `UNCERTAIN`。
- **没有宣称 Docker 绝对安全**：它用于缩小权限和影响范围，不等于虚拟机或完整恶意代码沙箱。
- **没有把微基准当模型效果**：30 项微基准验证基础设施不变量，不代表真实 Issue 解决率。
- **没有强行统一 Provider 协议**：统一领域模型，保留 SSE/NDJSON 各自的传输解析器。
- **没有把安全只交给 Schema**：Schema、策略、路径守卫、审批和容器分别解决不同风险。
- **没有声称多 ToolCall 并行执行**：当前核心循环按调用顺序串行执行；虚拟线程用于同时消费子进程 stdout/stderr，避免管道阻塞。
- **没有声称已实现生产级分布式调度**：当前定位是单 Agent、单仓库 CLI，重点是把边界和恢复语义做清楚。

## 11. 证据索引

| 主题 | 代码或测试入口 |
|---|---|
| Agent Loop 与预算 | `DefaultAgentEngine`、`ContextManager`、`DefaultAgentEngineTest` |
| Provider 流式解析 | `DeepSeekSseParser`、`OllamaNdjsonParser` 及对应测试 |
| ToolCall 聚合 | `ToolCallAccumulator` |
| Schema 与参数绑定 | `ReflectiveToolRegistry`、`ReflectiveToolRegistryTest` |
| 风险策略 | `DefaultPolicyEngine`、`DefaultPolicyEngineTest` |
| 工作区安全 | `WorkspaceGuard`、`WorkspaceGuardTest` |
| Docker 边界 | `DockerCommandFactory`、`DockerCommandFactoryTest` |
| 本地进程与虚拟线程 | `LocalSandboxExecutor`、`LocalSandboxExecutorTest` |
| SQLite 与哈希链 | `SqliteCheckpointStore`、`SqliteCheckpointStoreTest` |
| 日志脱敏 | `SensitiveDataRedactor`、`SensitiveDataRedactorTest` |
| CLI 装配 | `AgentForgeConfiguration`、`DefaultAgentOperations` |

## 12. 最终简历建议

简历篇幅有限时优先保留四条：

1. Agent Loop 与预算控制：展示领域建模和状态机能力。
2. 双 Provider 与类型化工具：展示协议、反射、Schema 和 DI。
3. 安全策略与 Docker：展示后端安全和系统边界意识。
4. SQLite 恢复与 `UNCERTAIN`：展示一致性、幂等和故障恢复思考。

自动化验证可以合并到项目末尾作为结果句。回答时遵循“问题背景 → 设计决策 → 具体实现 → 好处 → 可验证产出 → 局限”顺序，避免只背技术名词。
