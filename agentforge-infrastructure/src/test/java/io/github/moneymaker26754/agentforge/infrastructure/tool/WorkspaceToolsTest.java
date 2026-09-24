package io.github.moneymaker26754.agentforge.infrastructure.tool;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.moneymaker26754.agentforge.core.ExecutionContext;
import io.github.moneymaker26754.agentforge.core.SandboxMode;
import io.github.moneymaker26754.agentforge.core.SessionId;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceToolsTest {
    @TempDir
    Path workspace;

    @Test
    void readsRequestedLineSlice() throws Exception {
        Files.writeString(workspace.resolve("a.txt"), "one\ntwo\nthree\n");

        var result = new FsReadTool().execute(new FsReadTool.Arguments("a.txt", 2, 1), context());

        assertThat(result.success()).isTrue();
        assertThat(result.content()).isEqualTo("two");
    }

    @Test
    void listsAndSearchesWorkspaceTextFiles() throws Exception {
        Files.createDirectories(workspace.resolve("src"));
        Files.writeString(workspace.resolve("src/App.java"), "class App { // needle\n}");

        var listed = new FsListTool().execute(new FsListTool.Arguments("src", 20), context());
        var searched = new FsSearchTool().execute(new FsSearchTool.Arguments("needle", "src", 20), context());

        assertThat(listed.content()).contains("src" + java.io.File.separator + "App.java");
        assertThat(searched.content()).contains("src" + java.io.File.separator + "App.java:1:");
    }

    @Test
    void patchRequiresOneExactMatch() throws Exception {
        Files.writeString(workspace.resolve("one.txt"), "before");
        Files.writeString(workspace.resolve("many.txt"), "x x");
        var tool = new FsPatchTool();

        var success = tool.execute(new FsPatchTool.Arguments("one.txt", "before", "after"), context());
        var ambiguous = tool.execute(new FsPatchTool.Arguments("many.txt", "x", "y"), context());

        assertThat(success.success()).isTrue();
        assertThat(Files.readString(workspace.resolve("one.txt"))).isEqualTo("after");
        assertThat(ambiguous.errorCode()).isEqualTo("PATCH_AMBIGUOUS");
        assertThat(Files.readString(workspace.resolve("many.txt"))).isEqualTo("x x");
    }

    private ExecutionContext context() {
        return new ExecutionContext(new SessionId("s"), workspace, SandboxMode.LOCAL);
    }
}

