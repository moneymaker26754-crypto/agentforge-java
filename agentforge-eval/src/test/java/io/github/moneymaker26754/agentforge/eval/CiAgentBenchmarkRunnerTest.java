package io.github.moneymaker26754.agentforge.eval;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CiAgentBenchmarkRunnerTest {

    @Test
    void runsOfflineClosedLoopWithBothCasesPassing() {
        try (var runner = new CiAgentBenchmarkRunner()) {
            BenchmarkReport report = runner.run("full");

            assertThat(report.suite()).isEqualTo("ci-agent");
            assertThat(report.results()).hasSize(2);
            assertThat(report.results()).allSatisfy(result ->
                    assertThat(result.status()).isEqualTo("PASSED"));
            assertThat(report.results()).extracting(BenchmarkCaseResult::id)
                    .containsExactly("ci/happy-path", "ci/retry");
        }
    }
}
