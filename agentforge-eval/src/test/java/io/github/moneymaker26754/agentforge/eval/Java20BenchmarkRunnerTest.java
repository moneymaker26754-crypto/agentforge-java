package io.github.moneymaker26754.agentforge.eval;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class Java20BenchmarkRunnerTest {
    @Test
    void preservesAllCasesWhenTheExternalHarnessIsUnavailable() {
        BenchmarkReport report = new Java20BenchmarkRunner().environmentFailed("full", "harness unavailable");

        assertThat(report.suite()).isEqualTo("java20");
        assertThat(report.profile()).isEqualTo("full");
        assertThat(report.results()).hasSize(20).allSatisfy(result -> {
            assertThat(result.status()).isEqualTo("ENVIRONMENT_FAILED");
            assertThat(result.failureReason()).isEqualTo("harness unavailable");
        });
        assertThat(report.metrics().environmentFailed()).isEqualTo(20);
        assertThat(report.metrics().failed()).isZero();
    }
}
