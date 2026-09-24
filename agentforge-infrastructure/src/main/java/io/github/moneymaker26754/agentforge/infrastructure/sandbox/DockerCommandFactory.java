package io.github.moneymaker26754.agentforge.infrastructure.sandbox;

import io.github.moneymaker26754.agentforge.core.CommandSpec;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class DockerCommandFactory {
    private final String image;

    public DockerCommandFactory(String image) {
        this.image = image;
    }

    public List<String> create(Path workspace, CommandSpec spec) {
        var command = new ArrayList<>(List.of(
                "docker", "run", "--rm",
                "--network", "none",
                "--cpus", "2",
                "--memory", "4g",
                "--pids-limit", "256",
                "--user", "1000:1000",
                "-v", workspace.toAbsolutePath().normalize() + ":/workspace",
                "-w", "/workspace",
                image));
        command.addAll(spec.argv());
        return List.copyOf(command);
    }
}

