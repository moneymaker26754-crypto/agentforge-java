# AgentForge 业务驱动优化计划：CI 失败诊断与受控自动修复 Agent

> 依据《AgentForge：业务驱动型 Java Agent 优化方案》制定。目标：把 AgentForge 从 CLI 技术 Demo
> 升级为 CI Failure Diagnosis & Controlled Auto-Fix Agent。

## 目标

- 主场景：CI 失败 → 日志阅读 → 代码定位 → 诊断 → 定向测试 → Patch → 验证。
- 核心思想：LLM 是不可信 Planner，Java Agent Runtime 负责 Tool 调度、权限控制、上下文组装、
  状态恢复、沙箱执行与审计。

## 补齐清单（文档 §16 的七大缺口）

| # | 缺口 | 现状 | 落地 |
|---|------|------|------|
| 1 | MCP Tool 接入 | 无 | `infrastructure.mcp.McpToolBridge`（stdio）+ `CompositeToolRegistry` |
| 2 | PreToolUse 统一拦截层 | 隐式分散 | core `ToolExecutionGate`（Schema→Risk→Policy→Approval） |
| 3 | GitHub Actions Webhook/API Adapter | 无 | `infrastructure.github`（API 客户端 + HMAC 验签） |
| 4 | Spring Boot Control Plane | non-web | 新模块 `agentforge-server`（Webhook/Task/Session/审批/指标 API） |
| 5 | AgentTask/AgentSession 服务端持久化 | 仅事件流 | SQLite 追加 `agent_tasks`/`task_sessions`/`pending_approvals` |
| 6 | CI 闭环 | 无 | CI 工具 + CiContextAssembler + `ci` 子命令 + 离线 CI Benchmark |
| 7 | Runtime Eval 与 CI Benchmark | 部分 | 新确定性用例 + `CiAgentBenchmarkRunner` + Java20→Java21 更名 |

## 架构（模块划分）

- `agentforge-core`：显式状态机 PLAN→VALIDATE→PRE_TOOL_USE→EXECUTE→OBSERVE→REFLECT；
  `ToolExecutionGate`；`AgentPhase`；`SessionMetrics`；`ApprovalDecision.WAITING`；
  `RunStatus.WAITING_APPROVAL`（复用既有枚举值，未新增）；`ToolArgumentsValidator` SPI。
- `agentforge-infrastructure` 新增包：`infrastructure.github`（GitHubApiClient、
  GitHubWebhookVerifier、CiContextAssembler）、`infrastructure.ci`（GitHub 读取工具、
  RunTestTool、GitCommitTool、GitPushTool/CreatePullRequestTool）、`infrastructure.mcp`
  （McpToolBridge，官方 SDK io.modelcontextprotocol.sdk:mcp 1.1.4）+ `CompositeToolRegistry`。
- `agentforge-server`（新）：Spring Boot Web Control Plane。REST：
  `POST /api/v1/webhooks/github`、`POST/GET /api/v1/tasks[/{id}]`、
  `GET /api/v1/sessions/{id}[/events|/report]`、`POST /api/v1/approvals/{id}`、
  `GET /api/v1/metrics`、actuator health。TaskExecutionCoordinator 固定线程池（默认 2），
  不引入 MQ。幂等键 repository+commit_sha+workflow_run_id。
- `agentforge-cli`：新增 `ci` 子命令。
- `agentforge-eval`：确定性用例扩充 + `CiAgentBenchmarkRunner` + Java21 更名。

## 兼容性边界

- 不破坏 `ToolRegistry`/`PolicyEngine`/`CheckpointStore`/`SandboxExecutor`/`ToolHandler` 接口。
- `EventType`/`RunStatus`/`ApprovalDecision` 只增枚举值；SQLite 只做加法迁移；
  `RunCheckpoint` 新增 `phase`/`pendingApproval` 可空字段，旧快照必须可恢复。
- 原有 30 项确定性用例全部保持通过（全链路回归基线）。
- 工具消息格式不变：`ERROR UNKNOWN_TOOL:` / `ERROR INVALID_ARGUMENTS:` / `ERROR POLICY_DENIED:` /
  `ERROR USER_REJECTED:`。

## 任务

1. 基线 + 版本 0.2.0-SNAPSHOT + MCP 依赖（已完成）。
2. core 状态机 + PreToolUse + WAITING + SessionMetrics（coordinator）。
3. GitHub Adapter（subagent）。
4. CI 工具（subagent）。
5. CiContextAssembler（subagent）。
6. MCP Bridge（subagent）。
7. agentforge-server 模块 + 持久化 + coverage 脚本改 5 模块。
8. CLI `ci` 子命令。
9. Eval 扩展。
10. 文档（README 重写、docs/project-overview.md、handbook、evaluation）。
11. 全量验证 + 分阶段提交 + push origin main。

## 验证

- 每任务针对性测试；阶段末 `./mvnw -B verify`；收尾 `python scripts/check_coverage.py`。
- 验收：旧 30 用例 + 新用例全绿；离线 CI Benchmark 演示闭环；文档就位；远程 main 包含全部提交。

## 假设

- 本地无 GITHUB_TOKEN 与 LLM key：GitHub 与模型路径用 stub/离线脚本化用例验证。
- `git push` 依赖本机既有凭据；失败则停止并报告，不做凭据变通。
