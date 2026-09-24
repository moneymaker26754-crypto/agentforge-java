package io.github.moneymaker26754.agentforge.eval;

public record BenchmarkCaseResult(String id, String category, String status, long durationNanos,
        long inputTokens, long outputTokens, double costCny, String failureReason) {
    public boolean passed() { return "PASSED".equals(status); }
}
