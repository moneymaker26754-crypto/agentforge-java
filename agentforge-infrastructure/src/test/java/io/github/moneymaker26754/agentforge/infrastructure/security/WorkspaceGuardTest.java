package io.github.moneymaker26754.agentforge.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceGuardTest {

    @TempDir
    Path workspace;

    @Test
    void resolvesAChildInsideWorkspace() throws Exception {
        Files.writeString(workspace.resolve("safe.txt"), "ok");
        var guard = new WorkspaceGuard(workspace);

        Path resolved = guard.resolveExisting("safe.txt");

        assertThat(resolved).isEqualTo(workspace.resolve("safe.txt").toRealPath());
    }

    @Test
    void rejectsParentTraversalAndAbsolutePaths() {
        var guard = new WorkspaceGuard(workspace);

        assertThatThrownBy(() -> guard.resolveForWrite("../escape.txt"))
                .isInstanceOf(WorkspaceViolationException.class);
        assertThatThrownBy(() -> guard.resolveForWrite(workspace.resolve("absolute.txt").toString()))
                .isInstanceOf(WorkspaceViolationException.class);
    }
}

