package io.github.moneymaker26754.agentforge.core;

import java.nio.file.Path;
import java.util.Objects;

public record RunRequest(Path repository, String task, ProviderId provider, SandboxMode sandboxMode, RunBudget budget) {
    public RunRequest {
        repository = Objects.requireNonNull(repository, "repository").toAbsolutePath().normalize();
        if (task == null || task.isBlank()) {
            throw new IllegalArgumentException("task must not be blank");
        }
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(sandboxMode, "sandboxMode");
        Objects.requireNonNull(budget, "budget");
    }
}

