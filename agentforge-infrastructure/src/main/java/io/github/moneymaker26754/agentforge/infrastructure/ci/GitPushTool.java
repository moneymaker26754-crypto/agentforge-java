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
import java.util.ArrayList;
import java.util.List;

@AgentTool(name = "gitPush",
        description = "Push workspace commits to the git remote, optionally a specific branch",
        risk = RiskLevel.NETWORK)
public final class GitPushTool implements ToolHandler<GitPushTool.Arguments> {
    private static final int MAX_OUTPUT_BYTES = 1024 * 1024;

    private final SandboxExecutor executor;

    /** Injected with the default routing SandboxExecutor bean (sandboxExecutor). */
    public GitPushTool(SandboxExecutor executor) {
        this.executor = executor;
    }

    public record Arguments(
            @ToolParam(description = "optional branch to push (defaults to the configured upstream)") String branch) {}

    @Override
    public ToolResult execute(Arguments arguments, ExecutionContext context) {
        var argv = new ArrayList<>(List.of("git", "push"));
        String branch = arguments.branch();
        if (branch != null && !branch.isBlank()) {
            argv.add("origin");
            argv.add(branch);
        }
        var result = executor.execute(new CommandSpec(argv, Duration.ofSeconds(120), MAX_OUTPUT_BYTES), context);
        if (result.exitCode() != 0) {
            return ToolResult.failure("GIT_PUSH_FAILED", "git push failed (exit=" + result.exitCode() + ")\n"
                    + result.stdout() + result.stderr());
        }
        return ToolResult.success(branch == null || branch.isBlank()
                ? "pushed to remote"
                : "pushed branch " + branch + " to origin");
    }
}
