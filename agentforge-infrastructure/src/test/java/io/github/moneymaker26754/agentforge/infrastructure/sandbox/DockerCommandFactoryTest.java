package io.github.moneymaker26754.agentforge.infrastructure.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.moneymaker26754.agentforge.core.CommandSpec;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class DockerCommandFactoryTest {

    @Test
    void buildsResourceLimitedNetworklessNonRootCommand() {
        var factory = new DockerCommandFactory("agentforge-sandbox:21");
        var spec = new CommandSpec(List.of("mvn", "test"), Duration.ofMinutes(10), 1024 * 1024);

        List<String> command = factory.create(Path.of("C:/work/repo"), spec);

        assertThat(command).containsSubsequence("docker", "run", "--rm", "--network", "none");
        assertThat(command).contains("--cpus", "2", "--memory", "4g", "--pids-limit", "256",
                "--user", "1000:1000", "-w", "/workspace", "agentforge-sandbox:21", "mvn", "test");
        assertThat(command.stream().filter("-v"::equals).count()).isEqualTo(1);
        assertThat(command).anyMatch(value -> value.endsWith(":/workspace"));
    }
}

