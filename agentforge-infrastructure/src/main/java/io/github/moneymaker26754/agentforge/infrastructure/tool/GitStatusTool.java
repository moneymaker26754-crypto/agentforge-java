package io.github.moneymaker26754.agentforge.infrastructure.tool;

import io.github.moneymaker26754.agentforge.core.*;
import java.time.Duration;
import java.util.List;

@AgentTool(name = "git_status", description = "Show short Git working tree status", risk = RiskLevel.READ, idempotent = true)
public final class GitStatusTool implements ToolHandler<GitStatusTool.Arguments> {
    private final SandboxExecutor executor;
    public GitStatusTool(SandboxExecutor executor) { this.executor = executor; }
    public record Arguments() {}

    @Override public ToolResult execute(Arguments arguments, ExecutionContext context) {
        var result = executor.execute(new CommandSpec(List.of("git", "status", "--short", "--branch"),
                Duration.ofSeconds(30), 256 * 1024), context);
        return result.exitCode() == 0 ? ToolResult.success(result.stdout())
                : ToolResult.failure("GIT_STATUS_FAILED", result.stderr());
    }
}

