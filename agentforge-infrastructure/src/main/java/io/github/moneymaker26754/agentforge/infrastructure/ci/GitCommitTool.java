package io.github.moneymaker26754.agentforge.infrastructure.ci;

import io.github.moneymaker26754.agentforge.core.CommandSpec;
import io.github.moneymaker26754.agentforge.core.ExecutionContext;
import io.github.moneymaker26754.agentforge.core.RiskLevel;
import io.github.moneymaker26754.agentforge.core.SandboxExecutor;
import io.github.moneymaker26754.agentforge.core.ToolHandler;
import io.github.moneymaker26754.agentforge.core.ToolResult;
import io.github.moneymaker26754.agentforge.infrastructure.tool.AgentTool;
import io.github.moneymaker26754.agentforge.infrastructure.tool.ToolParam;
import java.time.Duration;
import java.util.List;

@AgentTool(name = "gitCommit",
        description = "Stage all workspace changes (git add -A) and create a commit with the given message",
        risk = RiskLevel.WRITE)
public final class GitCommitTool implements ToolHandler<GitCommitTool.Arguments> {
    private static final int MAX_OUTPUT_BYTES = 1024 * 1024;

    private final SandboxExecutor executor;

    /** Injected with the default routing SandboxExecutor bean (sandboxExecutor). */
    public GitCommitTool(SandboxExecutor executor) {
        this.executor = executor;
    }

    public record Arguments(
            @ToolParam(description = "commit message", required = true, min = 1) String message) {}

    @Override
    public ToolResult execute(Arguments arguments, ExecutionContext context) {
        if (arguments.message() == null || arguments.message().isBlank()) {
            return ToolResult.failure("GIT_COMMIT_FAILED", "commit message must not be blank");
        }
        var addResult = executor.execute(new CommandSpec(List.of("git", "add", "-A"),
                Duration.ofSeconds(30), MAX_OUTPUT_BYTES), context);
        if (addResult.exitCode() != 0) {
            return ToolResult.failure("GIT_COMMIT_FAILED", "git add -A failed (exit=" + addResult.exitCode() + ")\n"
                    + addResult.stdout() + addResult.stderr());
        }
        var commitResult = executor.execute(new CommandSpec(List.of("git", "commit", "-m", arguments.message()),
                Duration.ofSeconds(60), MAX_OUTPUT_BYTES), context);
        if (commitResult.exitCode() != 0) {
            return ToolResult.failure("GIT_COMMIT_FAILED", "git commit failed (exit=" + commitResult.exitCode() + ")\n"
                    + commitResult.stdout() + commitResult.stderr());
        }
        return ToolResult.success("committed workspace changes: " + arguments.message());
    }
}
