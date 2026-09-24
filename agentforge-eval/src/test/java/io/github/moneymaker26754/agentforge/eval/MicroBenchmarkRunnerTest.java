package io.github.moneymaker26754.agentforge.eval;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MicroBenchmarkRunnerTest {
    @Test
    void runsExactlyThirtyDeterministicScenariosAndAggregatesMetrics() {
        BenchmarkReport report = new MicroBenchmarkRunner().run("full");

        assertThat(report.suite()).isEqualTo("micro");
        assertThat(report.results()).hasSize(30);
        assertThat(report.results()).extracting(BenchmarkCaseResult::id).doesNotHaveDuplicates();
        assertThat(report.metrics().total()).isEqualTo(30);
        assertThat(report.metrics().passed()).isEqualTo(30);
        assertThat(report.metrics().passRate()).isEqualTo(1.0);
        assertThat(report.manifest().datasetRevision()).isEqualTo("local-deterministic-v1");
    }
}
