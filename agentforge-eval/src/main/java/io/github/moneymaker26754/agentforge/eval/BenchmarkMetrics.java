package io.github.moneymaker26754.agentforge.eval;

import java.util.List;

public record BenchmarkMetrics(int total, int passed, int failed, int environmentFailed, double passRate,
        long inputTokens, long outputTokens, double estimatedCostCny, double p50ToolMillis, double p95ToolMillis) {
    static BenchmarkMetrics from(List<BenchmarkCaseResult> results) {
        int passed = (int) results.stream().filter(BenchmarkCaseResult::passed).count();
        int environmental = (int) results.stream().filter(r -> "ENVIRONMENT_FAILED".equals(r.status())).count();
        var durations = results.stream().map(BenchmarkCaseResult::durationNanos).sorted().toList();
        return new BenchmarkMetrics(results.size(), passed, results.size() - passed - environmental, environmental,
                results.isEmpty() ? 0 : (double) passed / results.size(),
                results.stream().mapToLong(BenchmarkCaseResult::inputTokens).sum(),
                results.stream().mapToLong(BenchmarkCaseResult::outputTokens).sum(),
                results.stream().mapToDouble(BenchmarkCaseResult::costCny).sum(), percentile(durations, .50),
                percentile(durations, .95));
    }

    private static double percentile(List<Long> values, double quantile) {
        if (values.isEmpty()) return 0;
        int index = Math.min(values.size() - 1, (int) Math.ceil(values.size() * quantile) - 1);
        return values.get(index) / 1_000_000.0;
    }
}
