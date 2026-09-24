package io.github.moneymaker26754.agentforge.cli;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.github.moneymaker26754.agentforge.core.SandboxExecutor;
import io.github.moneymaker26754.agentforge.infrastructure.sandbox.RoutingSandboxExecutor;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import java.nio.file.Path;

class AgentForgeApplicationTest {
    @TempDir
    Path tempDir;

    @Test
    void applicationContextSelectsRoutingSandboxExecutor() {
        String previous = System.getProperty("agentforge.state.dir");
        try {
            System.setProperty("agentforge.state.dir", tempDir.resolve("context-state").toString());
            try (var context = new SpringApplicationBuilder(AgentForgeApplication.class)
                    .web(WebApplicationType.NONE).properties("spring.main.banner-mode=off").run()) {
                assertThat(context.getBean(SandboxExecutor.class)).isInstanceOf(RoutingSandboxExecutor.class);
            }
        } finally {
            restoreStateDirectory(previous);
        }
    }

    @Test
    void stateDirectoryCanBeOverriddenForPortableCliRuns() {
        String previous = System.getProperty("agentforge.state.dir");
        try {
            System.setProperty("agentforge.state.dir", "portable-state");
            assertThat(AgentForgeConfiguration.stateDirectory()).isEqualTo(Path.of("portable-state"));
        } finally {
            restoreStateDirectory(previous);
        }
    }

    @Test
    void packagedEntrypointAcceptsHelpWithoutCreatingGlobalState() {
        String previous = System.getProperty("agentforge.state.dir");
        try {
            System.setProperty("agentforge.state.dir", tempDir.resolve("main-state").toString());
            AgentForgeApplication.main(new String[] {"--help"});
        } finally {
            restoreStateDirectory(previous);
        }
    }

    private static void restoreStateDirectory(String previous) {
        if (previous == null) System.clearProperty("agentforge.state.dir");
        else System.setProperty("agentforge.state.dir", previous);
    }
}
