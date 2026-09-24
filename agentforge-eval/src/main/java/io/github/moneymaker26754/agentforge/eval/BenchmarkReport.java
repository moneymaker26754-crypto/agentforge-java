package io.github.moneymaker26754.agentforge.eval;

import java.util.List;

public record BenchmarkReport(String suite, String profile, BenchmarkManifest manifest,
        List<BenchmarkCaseResult> results, BenchmarkMetrics metrics) {
    public BenchmarkReport {
        results = List.copyOf(results);
    }

    public static BenchmarkReport of(String suite, String profile, BenchmarkManifest manifest,
            List<BenchmarkCaseResult> results) {
        return new BenchmarkReport(suite, profile, manifest, results, BenchmarkMetrics.from(results));
    }
}
