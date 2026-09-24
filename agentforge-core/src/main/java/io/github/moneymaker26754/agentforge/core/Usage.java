package io.github.moneymaker26754.agentforge.core;

public record Usage(long inputTokens, long outputTokens, double costCny) {
    public Usage {
        if (inputTokens < 0 || outputTokens < 0 || costCny < 0) {
            throw new IllegalArgumentException("usage cannot be negative");
        }
    }

    public static Usage zero() {
        return new Usage(0, 0, 0);
    }

    public Usage plus(Usage other) {
        return new Usage(inputTokens + other.inputTokens, outputTokens + other.outputTokens,
                costCny + other.costCny);
    }
}

