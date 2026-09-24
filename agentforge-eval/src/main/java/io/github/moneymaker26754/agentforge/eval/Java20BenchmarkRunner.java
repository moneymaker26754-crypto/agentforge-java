package io.github.moneymaker26754.agentforge.eval;

public final class Java20BenchmarkRunner {
    public BenchmarkReport environmentFailed(String profile, String reason) {
        BenchmarkManifest base = Java20Manifest.manifest();
        var manifest = new BenchmarkManifest(base.suite(), profile, base.dataset(), base.datasetRevision(),
                base.instanceIds(), base.costCapCny(), java.time.Instant.now().toString());
        var results = manifest.instanceIds().stream()
                .map(id -> new BenchmarkCaseResult(id, "swe-bench-java", "ENVIRONMENT_FAILED", 0, 0, 0, 0, reason))
                .toList();
        return BenchmarkReport.of("java20", profile, manifest, results);
    }
}
