package io.github.moneymaker26754.agentforge.core;

import java.time.Duration;

public record RunBudget(int maxIterations, Duration maxWallTime, long maxInputTokens, long maxOutputTokens,
        double maxCostCny, int repeatedToolLimit) {
    public RunBudget {
        if (maxIterations < 1 || maxWallTime.isNegative() || maxWallTime.isZero()
                || maxInputTokens < 1 || maxOutputTokens < 1 || maxCostCny < 0 || repeatedToolLimit < 1) {
            throw new IllegalArgumentException("run budget values must be positive");
        }
    }

    public static RunBudget defaults() {
        return new RunBudget(30, Duration.ofMinutes(20), 200_000, 30_000, 50, 3);
    }
}

