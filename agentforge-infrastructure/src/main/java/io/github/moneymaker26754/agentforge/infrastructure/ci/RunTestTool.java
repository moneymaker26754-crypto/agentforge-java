package io.github.moneymaker26754.agentforge.infrastructure.ci;

import io.github.moneymaker26754.agentforge.core.CommandSpec;
import io.github.moneymaker26754.agentforge.core.ExecutionContext;
import io.github.moneymaker26754.agentforge.core.ExecutionResult;
import io.github.moneymaker26754.agentforge.core.RiskLevel;
import io.github.moneymaker26754.agentforge.core.SandboxExecutor;
import io.github.moneymaker26754.agentforge.core.SandboxMode;
import io.github.moneymaker26754.agentforge.core.ToolHandler;
import io.github.moneymaker26754.agentforge.core.ToolResult;
import io.github.moneymaker26754.agentforge.infrastructure.tool.AgentTool;
import io.github.moneymaker26754.agentforge.infrastructure.tool.ToolParam;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Qualifier;

@AgentTool(name = "runTargetedTest",
        description = "Run one targeted Maven test (e.g. com.example.CalculatorTest#divideByZero) via the workspace Maven wrapper in the configured sandbox",
        risk = RiskLevel.EXECUTE)
public final class RunTestTool implements ToolHandler<RunTestTool.Arguments> {
    private static final int DEFAULT_TIMEOUT_SECONDS = 120;
    private static final int MAX_OUTPUT_BYTES = 1024 * 1024;
    private static final int MAX_OUTPUT_CHARS = 200_000;

    private final SandboxExecutor localExecutor;
    private final SandboxExecutor dockerExecutor;

    /**
     * Spring injects the plain local executor and the test-specific Docker executor.
     * {@code testDockerSandboxExecutor} must be a DockerSandboxExecutor built on a
     * DockerCommandFactory variant with network access (tests may download dependencies).
     */
    public RunTestTool(@Qualifier("localSandboxExecutor") SandboxExecutor localExecutor,
            @Qualifier("testDockerSandboxExecutor") SandboxExecutor dockerExecutor) {
        this.localExecutor = localExecutor;
        this.dockerExecutor = dockerExecutor;
    }

    public record Arguments(
            @ToolParam(description = "test selector, e.g. com.example.CalculatorTest#divideByZero or com.example.*Test",
                    required = true) String testSelector,
            @ToolParam(description = "test timeout in seconds", min = 10, max = 600) int timeoutSeconds) {}

    @Override
    public ToolResult execute(Arguments arguments, ExecutionContext context) {
        int timeout = arguments.timeoutSeconds() <= 0 ? DEFAULT_TIMEOUT_SECONDS : arguments.timeoutSeconds();
        SandboxExecutor executor = context.sandboxMode() == SandboxMode.DOCKER ? dockerExecutor : localExecutor;
        var spec = new CommandSpec(buildArgv(arguments.testSelector(), context.sandboxMode()),
                Duration.ofSeconds(timeout), MAX_OUTPUT_BYTES);
        ExecutionResult result = executor.execute(spec, context);
        String body = CiTools.truncate("exit=" + result.exitCode() + " timedOut=" + result.timedOut()
                + "\nstdout:\n" + result.stdout() + "\nstderr:\n" + result.stderr(), MAX_OUTPUT_CHARS);
        if (result.exitCode() == 0 && !result.timedOut()) {
            return ToolResult.success(body);
        }
        return ToolResult.failure("TEST_FAILED", body);
    }

    static List<String> buildArgv(String testSelector, SandboxMode mode) {
        // No -q: a green run must still produce visible test output for the agent to observe.
        return List.of(mavenWrapper(mode), "test", "-Dtest=" + testSelector, "-DfailIfNoTests=false");
    }

    static String mavenWrapper(SandboxMode mode) {
        if (mode == SandboxMode.DOCKER) return "./mvnw"; // the container always runs Linux
        return isWindows() ? "mvnw.cmd" : "./mvnw";
    }

    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
