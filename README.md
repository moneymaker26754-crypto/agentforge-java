# AgentForge

**CI 失败诊断与受控自动修复 Agent —— 一个把不可信 LLM 关进可控、可恢复、可审计执行框架的 Java Agent Runtime。**

AgentForge 面向真实的软件工程闭环：CI 失败后，自动完成日志阅读、代码定位、问题诊断、测试复现与 Patch 验证。LLM 只负责规划与推理；工具调度、权限控制、上下文组装、状态恢复、沙箱执行与审计全部由 Java 后端确定性控制。

## 项目定位

- 主场景：CI Failure Diagnosis & Controlled Auto-Fix（CI 失败诊断与受控自动修复）。
- 核心思想：不是"让 LLM 自由执行"，而是"让不可信的 LLM 在可控、可恢复、可审计的执行框架里完成真实工程任务"。
- 交付形态：一套核心 Runtime + 一个主业务场景 —— CLI（`agentforge ci`）与 Control Plane（`agentforge-server`）共用同一份核心代码。

## 解决什么问题

- **CI 失败排查成本高**：失败后需要在 Workflow、Job、日志、Commit、Diff、代码与测试之间反复切换，人工拼接上下文。
- **直接给 Agent 权限太危险**：普通代码 Agent 直接拥有文件修改、命令执行和 Git 权限，会带来越权、错误修改和重复副作用。AgentForge 把所有 Tool 调用放进 `Schema → Risk → Policy → Approval` 的统一拦截层，高风险操作必须人工审批，测试与 Patch 在 Docker 沙箱中执行。
- **上下文失控**：不把整个仓库塞进 Prompt，而是根据 CI 任务动态组装最小必要上下文（失败 Job/日志/堆栈/最近 Commit/Diff/相关文件），按 Token 预算截断。
- **长任务不可恢复**：每个 CI 任务独立拥有 Session、预算、Checkpoint 和事件流；工具调用前记录 TOOL_INTENT、完成后记录 TOOL_RESULT，故障后从 Snapshot + Event Replay 恢复；非幂等调用结果未知时进入 `UNCERTAIN`，绝不静默重放。
- **行为不可验证**：确定性评测体系覆盖流协议、Schema、安全边界、预算与故障恢复，离线 CI Benchmark 验证"失败 → 定位 → Patch → 定向测试"完整闭环。

## 技术与框架

| 领域 | 技术 |
|---|---|
| 语言与构建 | Java 21、Maven 多模块（core / infrastructure / cli / server / eval） |
| 应用框架 | Spring Boot 3（Control Plane Web 服务 + Actuator）、Picocli CLI |
| Agent Runtime | 自研六阶段状态机 `PLAN → VALIDATE → PRE_TOOL_USE → EXECUTE → OBSERVE → REFLECT`，Checkpoint/Resume、UNCERTAIN、预算控制 |
| 模型接入 | DeepSeek（OpenAI 兼容 SSE）与 Ollama（NDJSON），增量 ToolCall 按 index 聚合 |
| 工具层 | `@AgentTool` 注解 + Java record 类型化工具、JSON Schema 生成与校验、MCP Java SDK（stdio）统一 Tool Bus |
| 安全边界 | Docker 沙箱（非 root/断网/资源限制）、argv 进程执行、路径逃逸防护、风险分级策略与人工审批 |
| GitHub 集成 | GitHub Actions REST API Adapter、Webhook HMAC-SHA256 验签、按 repository+commit+run 幂等建任务 |
| 持久化 | SQLite 追加式事件流 + 快照，SHA-256 哈希链审计，AgentTask/AgentSession/审批记录 |
| 评测 | 38 项确定性 Runtime 回归用例、离线 CI Agent Benchmark、SWE-bench Java 清单、JaCoCo 门禁 + GitHub Actions（Linux/Windows） |

## 模块

```text
agentforge-core            状态机、PreToolUse 门禁、上下文、预算与核心 SPI
agentforge-infrastructure  模型客户端、GitHub 适配器、CI 工具、MCP 桥、沙箱、策略、SQLite
agentforge-cli             Spring Boot non-web CLI：run / ci / resume / session / report / eval
agentforge-server          Control Plane：Webhook、Task/Session API、审批、指标
agentforge-eval            确定性用例、CI Agent Benchmark、SWE-bench 清单
```

## 快速开始

需要 JDK 21；默认沙箱模式还需要 Docker。Ollama 仅在使用本地模型时需要。

```bash
./mvnw verify
docker build -f Dockerfile.sandbox -t agentforge-sandbox:java21 .
java -jar agentforge-cli/target/agentforge-cli-0.2.0-SNAPSHOT.jar doctor

# CLI 编程任务
java -jar agentforge-cli/target/agentforge-cli-0.2.0-SNAPSHOT.jar run \
  --repo /path/to/repo --task "修复失败测试并解释原因" --provider deepseek

# CI 失败诊断（自动拉取失败 Job/日志/Commit/Diff 组装上下文）
java -jar agentforge-cli/target/agentforge-cli-0.2.0-SNAPSHOT.jar ci \
  --repo /path/to/repo --github-repo owner/repo --run 123456 --provider deepseek

# Control Plane
java -jar agentforge-server/target/agentforge-server-0.2.0-SNAPSHOT.jar
```

关键环境变量：`DEEPSEEK_API_KEY`、`GITHUB_TOKEN`（可选，提升 GitHub API 限额）、`GITHUB_WEBHOOK_SECRET`（Control Plane 验签）。

更详细的实现说明见 [项目梳理文档](docs/project-overview.md) 与 [技术手册](docs/technical-handbook.md)。

## License

[MIT](LICENSE)
