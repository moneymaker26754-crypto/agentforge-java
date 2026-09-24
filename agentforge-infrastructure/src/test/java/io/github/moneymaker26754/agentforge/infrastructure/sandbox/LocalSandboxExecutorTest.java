package io.github.moneymaker26754.agentforge.infrastructure.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.moneymaker26754.agentforge.core.CommandSpec;
import io.github.moneymaker26754.agentforge.core.ExecutionContext;
import io.github.moneymaker26754.agentforge.core.SandboxMode;
import io.github.moneymaker26754.agentforge.core.SessionId;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class LocalSandboxExecutorTest {

    @Test
    void rejectsExecutableOutsideAllowlist() {
        var executor = new LocalSandboxExecutor(Set.of("java"));
        var result = executor.execute(new CommandSpec(List.of("powershell", "-Command", "whoami"),
                Duration.ofSeconds(5), 1000), context());

        assertThat(result.exitCode()).isEqualTo(-1);
        assertThat(result.stderr()).contains("not allowed");
    }

    @Test
    void executesAllowedArgvCommandAndCapturesBoundedOutput() {
        var executor = new LocalSandboxExecutor(Set.of("java"));
        var result = executor.execute(new CommandSpec(List.of("java", "-version"),
                Duration.ofSeconds(10), 4096), context());

        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout() + result.stderr()).contains("version");
        assertThat(result.timedOut()).isFalse();
    }

    private ExecutionContext context() {
        return new ExecutionContext(new SessionId("sandbox-test"), Path.of("."), SandboxMode.LOCAL);
    }
}

