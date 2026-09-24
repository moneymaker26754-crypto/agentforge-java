package io.github.moneymaker26754.agentforge.eval;

import java.util.List;

public record BenchmarkManifest(String suite, String profile, String dataset, String datasetRevision,
        List<String> instanceIds, double costCapCny, String generatedAt) {
    public BenchmarkManifest {
        instanceIds = List.copyOf(instanceIds);
    }
}
