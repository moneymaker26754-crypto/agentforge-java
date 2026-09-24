package io.github.moneymaker26754.agentforge.infrastructure.tool;

import io.github.moneymaker26754.agentforge.core.*;
import java.time.Duration;
import java.util.List;

@AgentTool(name = "shell_exec", description = "Execute an argv-form command in the configured sandbox", risk = RiskLevel.EXECUTE)
public final class CommandTool implements ToolHandler<CommandTool.Arguments> {
    private final SandboxExecutor executor;

    public CommandTool(SandboxExecutor executor) { this.executor = executor; }

    public record Arguments(
            @ToolParam(description = "executable and arguments", required = true) List<String> argv,
            @ToolParam(description = "timeout in seconds", min = 1, max = 1200) int timeoutSeconds) {}

    @Override public ToolResult execute(Arguments arguments, ExecutionContext context) {
        int timeout = arguments.timeoutSeconds() <= 0 ? 300 : arguments.timeoutSeconds();
        var result = executor.execute(new CommandSpec(arguments.argv(), Duration.ofSeconds(timeout), 1024 * 1024), context);
        String body = "exit=" + result.exitCode() + " timedOut=" + result.timedOut() + "\nstdout:\n"
                + result.stdout() + "\nstderr:\n" + result.stderr();
        return result.exitCode() == 0 && !result.timedOut()
                ? ToolResult.success(body)
                : ToolResult.failure(result.timedOut() ? "COMMAND_TIMEOUT" : "COMMAND_FAILED", body);
    }
}

