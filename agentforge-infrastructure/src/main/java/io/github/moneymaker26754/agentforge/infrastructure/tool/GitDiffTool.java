package io.github.moneymaker26754.agentforge.infrastructure.tool;

import io.github.moneymaker26754.agentforge.core.*;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@AgentTool(name = "git_diff", description = "Show unstaged or staged Git diff", risk = RiskLevel.READ, idempotent = true)
public final class GitDiffTool implements ToolHandler<GitDiffTool.Arguments> {
    private final SandboxExecutor executor;
    public GitDiffTool(SandboxExecutor executor) { this.executor = executor; }
    public record Arguments(@ToolParam(description = "show staged changes") boolean staged) {}

    @Override public ToolResult execute(Arguments arguments, ExecutionContext context) {
        var argv = new ArrayList<>(List.of("git", "diff", "--no-ext-diff"));
        if (arguments.staged()) argv.add("--cached");
        var result = executor.execute(new CommandSpec(argv, Duration.ofSeconds(30), 1024 * 1024), context);
        return result.exitCode() == 0 ? ToolResult.success(result.stdout())
                : ToolResult.failure("GIT_DIFF_FAILED", result.stderr());
    }
}

