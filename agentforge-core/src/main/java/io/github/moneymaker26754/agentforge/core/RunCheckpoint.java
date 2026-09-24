package io.github.moneymaker26754.agentforge.core;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

/** JSON-friendly run metadata needed to continue a session after process restart. */
public record RunCheckpoint(String repository, String task, String provider, String sandboxMode,
        int maxIterations, long maxWallMillis, long maxInputTokens, long maxOutputTokens, double maxCostCny,
        int repeatedToolLimit, int iterations, String previousFingerprint, int repeatedCalls, String startedAt) {

    public static RunCheckpoint from(RunRequest request, int iterations, String previousFingerprint,
            int repeatedCalls, Instant startedAt) {
        RunBudget budget = request.budget();
        return new RunCheckpoint(request.repository().toString(), request.task(), request.provider().name(),
                request.sandboxMode().name(), budget.maxIterations(), budget.maxWallTime().toMillis(),
                budget.maxInputTokens(), budget.maxOutputTokens(), budget.maxCostCny(), budget.repeatedToolLimit(),
                iterations, previousFingerprint, repeatedCalls, startedAt.toString());
    }

    public RunRequest request() {
        return new RunRequest(Path.of(repository), task, ProviderId.valueOf(provider), SandboxMode.valueOf(sandboxMode),
                new RunBudget(maxIterations, Duration.ofMillis(maxWallMillis), maxInputTokens, maxOutputTokens,
                        maxCostCny, repeatedToolLimit));
    }

    public Instant startedInstant() {
        return Instant.parse(startedAt);
    }
}
