package io.github.moneymaker26754.agentforge.infrastructure.sandbox;

import io.github.moneymaker26754.agentforge.core.CommandSpec;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class DockerCommandFactory {
    private final String image;
    private final boolean allowNetwork;

    public DockerCommandFactory(String image) {
        this(image, false);
    }

    /**
     * @param allowNetwork when true the {@code --network none} flag is omitted. Used only for the
     *                     controlled test-run sandbox, which must resolve build dependencies while
     *                     remaining resource-limited; the default sandbox stays network-isolated.
     */
    public DockerCommandFactory(String image, boolean allowNetwork) {
        this.image = image;
        this.allowNetwork = allowNetwork;
    }

    public List<String> create(Path workspace, CommandSpec spec) {
        var command = new ArrayList<>(List.of(
                "docker", "run", "--rm"));
        if (!allowNetwork) {
            command.add("--network");
            command.add("none");
        }
        command.addAll(List.of(
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
