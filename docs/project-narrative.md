# AgentForge 项目梳理：业务问题、目标与亮点

> 本文档从**业务视角**梳理项目：先讲清研发流程里真实存在的问题，再顺着
> **业务痛点 → 数据接入 → Agent 推理 → 上下文控制 → 执行动作 → 权限拦截 → 人工审批 → 恢复 → 验证**
> 这条链路说明每一环的「问题是什么 / 我们怎么做 / 换来了什么」，最后给出可直接用于简历的项目亮点。
>
> 技术实现细节见 [项目梳理文档](project-overview.md) 与 [技术手册](technical-handbook.md)；评测口径见 [评测与复现](evaluation.md)。

## 1. 一句话定位

**把"CI 失败后的人工排查"变成一条可审计、可恢复、可验证的自动诊断任务链**：LLM 只负责规划与推理，工具调度、权限控制、上下文组装、状态恢复、沙箱执行与审计全部留在 Java 后端。

目标读者：研发效能 / 后端 / Agent 方向的工程团队与面试官。

## 2. 业务痛点（工作中的真实场景）

1. **证据分散**：一次 CI 失败的现象分布在 Workflow、Job、日志、Commit、Diff、代码与测试里，人工要在多个系统间反复切换拼接，单次排查耗时长。
2. **日志只呈现现象**：根因往往在最近变更、依赖、配置或测试代码中，定位高度依赖个人经验，重复劳动且难以交接。
3. **想让模型代劳，但不敢授权**：把文件读写、命令执行、Git 权限直接交给 LLM，会带来误改、越权与重复副作用——性质等同于一次线上误操作。
4. **黑盒过程进不了团队流程**：没有审计事件、没有断点恢复、没有人工闸门，团队不敢把它接进 CI 流水线。
5. **效果无法度量**：跑通一次演示不代表稳定可用；缺少确定性回归与可复现的闭环验证，就无法判断"到底有没有真的修好"。

一句话概括：**真正的工程问题不是"LLM 能不能写代码"，而是"Agent 能否在真实流程里稳定、可控、可追溯地执行"。**

## 3. 项目目标（可验收，而非技术清单）

- **输入**：一条 CI 失败（Webhook 或 REST 接口）→ 建立独立 Agent Session；
- **输出**：定位结论 + 最小 Patch + 定向测试结果 + 全过程证据；
- **边界**：高风险动作必须人工确认，不可逆动作默认禁止，测试与 Patch 只在隔离环境执行；
- **可衡量**：任务状态分布、工具调用与失败数、Token 与费用、待审批数、阶段分布（哪些已自动计算见 §6）。

## 4. 主线：沿链路讲清「问题 → 方案 → 价值」

### 4.0 业务痛点（链路起点）

见 §2：证据分散、根因隐蔽、授权风险、过程黑盒、效果不可度量。下面每一环都对应其中一个具体问题。

### 4.1 数据接入

- **业务问题**：失败证据散落在 CI 系统与代码仓库两侧，人工跨系统查询与拼装。
- **我们的做法**：以 Webhook（HMAC-SHA256 验签）与 REST 接口接收 CI 失败事件，由 GitHub Adapter 拉取 Workflow、失败 Job、Job 日志（含 302 跳转下载）、Commit 与 Diff；仓库侧提供 `fs_list` / `fs_read` / `fs_search` / `git_status` / `git_diff` 读取代码与工作区状态。原生工具、CI 工具与 MCP 桥接工具走**同一套工具契约**（名称 + JSON Schema + 风险等级 + 幂等标记）。建任务以 `repository + commit_sha + workflow_run_id` 为幂等键，重复投递只返回原任务。
- **换来了什么**：一次失败的全部证据可按仓库、commit 与 job 精确关联，不再依赖人工拼接；重复 Webhook 不会产生第二次执行。
- **代码证据**：`infrastructure/github`（`GitHubApiClient` / `GitHubWebhookVerifier` / `GitHubWebhookParser`）、`infrastructure/ci`、`server/web/WebhookController`。

### 4.2 Agent 推理

- **业务问题**：模型自由发挥式的"看一眼就改"不可解释，出错后无法复盘卡在哪一步。
- **我们的做法**：把排查 SOP 固化为显式六阶段状态机 `PLAN → VALIDATE → PRE_TOOL_USE → EXECUTE → OBSERVE → REFLECT`；每进入一个阶段写 `PHASE_ENTERED` 审计事件。模型只产出工具调用与最终结论，不接触执行权。预算与熔断同时约束：30 次迭代 / 20 分钟 / 20 万输入 token / 3 万输出 token / ¥50 / 连续 3 次相同调用即熔断。
- **换来了什么**：排查过程从"模型的一次发挥"变成可解释、可回放、可定位卡点的固定流程，失败可以按阶段归因。
- **代码证据**：`core/DefaultAgentEngine`、`core/AgentPhase`、`core/RunBudget`。

### 4.3 上下文控制

- **业务问题**：把整个仓库或全部历史日志塞进 Prompt，既贵又干扰判断，还让结论难以复现。
- **我们的做法**：`CiContextAssembler` 按 Token 预算组装**最小必要证据**——默认 4000 token，失败日志 40% / 测试输出 20% / Diff 20% / 堆栈 10% / 文件清单 10%，堆栈最多 40 行，超预算即截断；引擎侧在模型窗口 75% 阈值处压缩历史，保留系统约束、首条目标与最近 12 条消息，并写 `CONTEXT_COMPRESSED` 事件（压缩前后 token 可估）。
- **换来了什么**：上下文成本可控、无关信息干扰更少；同一失败输入得到稳定可复现的推理口径。
- **代码证据**：`infrastructure/github/CiContextAssembler`、`core/ContextManager`。

### 4.4 执行动作

- **业务问题**：让 Agent 跑测试、改代码、执行命令，等于把宿主环境交出去；一次误操作就可能污染工作区。
- **我们的做法**：所有动作只在沙箱内发生——Docker 非 root（1000:1000）、默认断网、限 2 CPU / 4g 内存 / 256 PID，配超时与输出截断（唯一网络例外是"定向测试"容器需解析依赖，且仍受资源限制）；命令以 argv 形式传入、不经过 shell；补丁按精确文本块替换并拒绝二进制与超大块；路径经真实路径校验，阻止符号链接逃逸。
- **换来了什么**：调试与修复不会越出工作区影响宿主，`;`、`&&`、`$()` 这类注入没有解释机会。
- **代码证据**：`infrastructure/sandbox`（`DockerCommandFactory` / `DockerSandboxExecutor`）、`infrastructure/tool/FsPatchTool`、`infrastructure/security/WorkspaceGuard`。

### 4.5 权限拦截

- **业务问题**：靠 Prompt 说"请小心"约束不住模型，越权与误改必须在执行前被拦。
- **我们的做法**：所有工具调用统一经过 PreToolUse 门禁，按 **Schema → Risk → Policy → Approval** 顺序判定：先校验工具是否存在、参数是否符合 Schema（不合法写 `VALIDATION_FAILED` 且不执行），再按五级风险分流——只读放行、写入与命令询问、联网需显式开启否则拒绝、破坏性默认拒绝。模型只能调用已注册工具，无法传入类名或绕过注册表。
- **换来了什么**：安全边界落在执行层而不是 Prompt 层；高危动作在产生副作用之前就被挡住。
- **代码证据**：`core/ToolExecutionGate`、`infrastructure/security/DefaultPolicyEngine`、`infrastructure/tool/ReflectiveToolArgumentsValidator`。

### 4.6 人工审批

- **业务问题**：高风险动作需要人签字，但人工等待不应该让整条任务链卡死或丢失现场。
- **我们的做法**：遇到需确认的动作，把调用参数、工具与理由落成待批记录（`pending_approvals` 表），会话挂起为 `WAITING_APPROVAL` 并保存快照；审批接口决定后调用 `resume(sessionId, decision)` 继续——批准则重新过一遍校验再执行，拒绝则把 `USER_REJECTED` 作为结构化工具结果回灌，让模型换方案。重复查询挂起会话是幂等的，不产生新事件。
- **换来了什么**：高风险动作始终有人签字，且审批等待不破坏任务状态机与审计连续性。
- **代码证据**：`core/DefaultAgentEngine.resume(SessionId, ApprovalDecision)`、`server/runtime/PendingApprovalHandler`、`server/web/ApprovalController`。

### 4.7 恢复

- **业务问题**：进程崩溃、机器重启、任务被中断后，最怕"不知道刚才那步到底做没做成"，盲目重跑会产生重复副作用。
- **我们的做法**：工具执行前写 `TOOL_INTENT`、执行后写 `TOOL_RESULT`，过程写入 SQLite 追加式事件流并定期保存快照；恢复时从快照 + 事件重放继续。若发现非幂等调用只有意图、没有结果，则进入 `UNCERTAIN` 交给人核对，绝不静默重放。事件之间以 SHA-256 前序哈希串联，篡改会被校验发现（`AuditIntegrityException`）。
- **换来了什么**：失败可续、副作用不重复、审计可验证——这是团队敢把它跑在真实仓库上的前提。
- **代码证据**：`infrastructure/store/SqliteCheckpointStore`、`core/EventType`、`core/RunStatus`、`core/PendingApproval`。

### 4.8 验证

- **业务问题**："跑通一次"不等于可用，缺少可重复的验证入口就无法判断能力是否退化。
- **我们的做法**：38 项确定性回归覆盖流协议（6）、工具 Schema（6）、安全边界（8）、沙箱运行时（4）、六阶段循环（6）、恢复与审计（4）、Webhook（2）、上下文预算（2）；另有 2 项**离线 CI 闭环 Benchmark**（读日志 → 定位 → Patch → 定向测试，含"测试失败后二次修补"的重试用例），一个命令即可在无 LLM、无网络、无 Docker 的环境复现；同时固定 revision 的 SWE-bench Java 语言清单 20 题，未真实执行的一律标记环境失败。全量 151 项测试跑在 Linux/Windows CI 上，Linux 额外断言沙箱边界。
- **换来了什么**：能力可回归、边界可证明；外部评测没跑就是没跑，不会包装成模型成绩。
- **代码证据**：`eval/MicroBenchmarkRunner`、`eval/CiAgentBenchmarkRunner`、`eval/Java21Manifest`、`docs/evaluation.md`。

## 5. 简历式项目亮点

> 写法说明：模仿「**能力短语**：做法；业务价值」的紧凑句式，但内容全部取自本项目自身的痛点与方案。

### 5.1 精简版（4 条，可直接放进简历）

**CI 失败诊断与受控自动修复 Agent（AgentForge）** ｜ 个人项目 ｜ 2026.09–至今

> CI 失败后要在 Workflow、日志、Commit、Diff 与测试之间反复切换，根因常藏在最近变更里，人工拼接证据耗时；而把文件、命令与 Git 权限直接交给 LLM，又存在误改与越权风险。目标是让 Agent 在可审计、可恢复的边界内完成"定位→修复→验证"，高风险动作仍由人确认。

- **打通 CI 证据链**：以 Webhook 验签与适配层接入 CI，把 Workflow、失败 Job、日志、Commit、Diff 与仓库代码统一收束为标准工具结果，并按仓库+commit+run 幂等建任务；使失败现象可按变更精确关联，替代人工跨系统拼接证据的排查方式。
- **可解释的推理闭环**：把排查 SOP 固化为"计划—校验—权限—执行—观察—反思"六阶段，按 Token 预算只投喂最小必要证据（日志、测试输出、堆栈、Diff、文件清单按比例截断）；模型只产出工具调用与结论，每一步留下审计事件，归因链路可复核。
- **受控执行边界**：所有命令与改动只发生在断网、非 root、限资源的沙箱内，动作按只读、受限执行、人工审批、默认禁止四级分流；需确认的高风险动作生成含参数与依据的待批记录并挂起，人工确认后才会执行。
- **可恢复与可验证**：执行前写意图、执行后写结果，崩溃后从快照与事件重放恢复，结果未知的非幂等动作进入 UNCERTAIN 而非重放；以 38 项确定性回归与离线 CI 闭环 Benchmark 覆盖协议、边界、审批、恢复与预算，形成可重复的回归入口。

### 5.2 详版（8 条，按链路展开，用于面试或汇报）

- **数据接入**：以 Webhook（HMAC 验签）与 API 适配层接入 GitHub Actions，把 Workflow、失败 Job、日志、Commit、Diff 与仓库侧代码、Git 状态收束为同一契约的工具结果（原生工具与 MCP 工具同规格），并以仓库+commit+run 为幂等键建任务；使分散在多个系统的失败证据可按变更精确关联，不再人工拼接。
- **Agent 推理**：把排查 SOP 固化为"计划—校验—权限—执行—观察—反思"六阶段状态机，模型只产出工具调用与结论、不接触执行权，阶段进入与工具意图/结果全部落审计事件；让排查从模型的自由发挥变成可解释、可回放、可定位卡点的固定流程。
- **上下文控制**：不把整仓塞进 Prompt，按 Token 预算组装最小必要证据（失败日志 40%、测试输出 20%、Diff 20%、堆栈 10%、文件清单 10%，超限截断），并在引擎侧于 75% 窗口阈值压缩、保留系统约束与最近对话；降低无关上下文干扰与 Token 成本，保证推理口径稳定可复现。
- **执行动作**：所有动作只在沙箱内发生——非 root、断网、限 CPU/内存/PID、超时与输出截断，命令以 argv 形式不经 shell，补丁按精确文本块替换并拒绝二进制与超大块，路径经真实路径校验防符号链接逃逸；确保调试与修复不会越出工作区影响宿主环境。
- **权限拦截**：在 PreToolUse 阶段对工具存在性、参数 Schema、风险等级做确定性校验，按只读放行、写入与执行询问、联网需显式开启、破坏性默认拒绝分级；把越权与误改挡在执行之前，而不是依赖 Prompt 去约束模型。
- **人工审批**：需确认的动作把调用参数与理由落成待批记录，会话挂起为 WAITING_APPROVAL，由审批接口决定后恢复——批准则重跑校验再执行，拒绝则把拒绝结果作为结构化反馈交回模型换方案，重复查询保持幂等；让高风险动作始终有人签字，同时不阻塞任务状态。
- **恢复**：执行前写意图、执行后写结果，崩溃后从快照与事件重放恢复；非幂等且结果未知的调用进入 UNCERTAIN 而非重放，事件以 SHA-256 前序哈希串联防篡改；保证失败可续、副作用不重复、审计可验证。
- **验证**：用 38 项确定性回归覆盖流协议、工具 Schema、安全边界、沙箱运行时、六阶段循环、审批挂起与恢复、Webhook 验签与上下文预算，另加 2 项离线 CI 闭环 Benchmark（日志→定位→Patch→定向测试，含失败重试），并固定 SWE-bench Java 语言清单；让能力可回归、边界可证明，未真实执行的外部评测如实标记环境失败。

## 6. 指标口径与诚实边界

**已自动计算**（`SessionMetrics` 与 `GET /api/v1/metrics`）：任务总数与状态分布、会话数、工具调用数与失败数、平均工具调用数、输入/输出 token、费用、待审批数、六阶段分布。

**仅定义口径、尚未自动计算**：Patch 成功率、P95 运行时、恢复成功率、不安全动作率——它们写在指标定义里，等真实数据接入后再报数字。

**明确不宣称**：生产环境部署；真实 SWE-bench resolved 率（Java21 的 20 题为环境失败占位，不是模型成绩）；exactly-once 语义（外部命令与数据库无法原子提交，因此才有 `UNCERTAIN`）；支持所有模型与所有命令（当前两种 Provider 与受限工具集）。

## 7. 追问准备（4 问 4 答）

| 追问 | 回答要点 |
|---|---|
| 业务痛点是什么？ | CI 失败后的证据分散与根因隐蔽，人工排查耗时且依赖经验；同时不敢把权限直接交给模型。 |
| 为什么不能让 LLM 直接做？ | 模型是不可信 Planner：它会重复、会出错、会被仓库内容注入；执行权一旦交出去，误改与越权就无法回收。 |
| Runtime 如何控制？ | 数据接入统一契约、推理固定六阶段、上下文按预算组装、执行进沙箱、权限在 PreToolUse 拦截、高风险走审批、状态双写可恢复。 |
| 如何证明有效？ | 38 项确定性回归 + 2 项离线 CI 闭环 Benchmark + Linux/Windows CI；真实凭据下的端到端验证作为补充，不混入口径。 |

## 8. 证据索引

| 关注点 | 代码 / 文档入口 |
|---|---|
| 状态机与预算 | `core/DefaultAgentEngine`、`core/AgentPhase`、`core/RunBudget` |
| PreToolUse 门禁 | `core/ToolExecutionGate`、`infrastructure/security/DefaultPolicyEngine` |
| 上下文组装 | `infrastructure/github/CiContextAssembler`、`core/ContextManager` |
| 沙箱与工作区安全 | `infrastructure/sandbox`、`infrastructure/security/WorkspaceGuard` |
| 持久化与恢复 | `infrastructure/store/SqliteCheckpointStore`、`core/EventType` |
| 控制面 | `server/web/WebhookController`、`server/runtime/TaskExecutionCoordinator`、`server/store/SqliteAgentTaskStore` |
| 评测 | `eval/MicroBenchmarkRunner`、`eval/CiAgentBenchmarkRunner`、`docs/evaluation.md` |
| 简历与面试素材 | `docs/resume-project-story.md`、`docs/interview-guide.md` |
