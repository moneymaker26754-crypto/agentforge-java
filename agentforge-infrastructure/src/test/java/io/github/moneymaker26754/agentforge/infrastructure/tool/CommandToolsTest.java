package io.github.moneymaker26754.agentforge.infrastructure.tool;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.moneymaker26754.agentforge.core.CommandSpec;
import io.github.moneymaker26754.agentforge.core.ExecutionContext;
import io.github.moneymaker26754.agentforge.core.ExecutionResult;
import io.github.moneymaker26754.agentforge.core.SandboxExecutor;
import io.github.moneymaker26754.agentforge.core.SandboxMode;
import io.github.moneymaker26754.agentforge.core.SessionId;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class CommandToolsTest {
    @Test
    void commandToolPassesArgvTimeoutAndOutputLimitToSandbox() {
        var sandbox = new RecordingSandbox();
        var tool = new CommandTool(sandbox);

        var result = tool.execute(new CommandTool.Arguments(List.of("mvn", "test"), 30), context());

        assertThat(result.success()).isTrue();
        assertThat(sandbox.command.argv()).containsExactly("mvn", "test");
        assertThat(sandbox.command.timeout().toSeconds()).isEqualTo(30);
        assertThat(sandbox.command.maxOutputBytes()).isEqualTo(1024 * 1024);
    }

    @Test
    void gitToolsUseFixedReadOnlyArgv() {
        var sandbox = new RecordingSandbox();

        new GitStatusTool(sandbox).execute(new GitStatusTool.Arguments(), context());
        assertThat(sandbox.command.argv()).containsExactly("git", "status", "--short", "--branch");

        new GitDiffTool(sandbox).execute(new GitDiffTool.Arguments(false), context());
        assertThat(sandbox.command.argv()).containsExactly("git", "diff", "--no-ext-diff");
    }

    private ExecutionContext context() {
        return new ExecutionContext(new SessionId("s"), Path.of("."), SandboxMode.LOCAL);
    }

    private static final class RecordingSandbox implements SandboxExecutor {
        CommandSpec command;

        @Override
        public ExecutionResult execute(CommandSpec command, ExecutionContext context) {
            this.command = command;
            return new ExecutionResult(0, "ok", "", false, 1);
        }
    }
}

