package io.github.moneymaker26754.agentforge.eval;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ReadmeMetricsRendererTest {
    @Test
    void replacesOnlyGeneratedMetricsRegion() {
        String readme = "before\n<!-- BENCHMARK:START -->\nold\n<!-- BENCHMARK:END -->\nafter\n";
        BenchmarkReport report = new MicroBenchmarkRunner().run("full");

        String rendered = new ReadmeMetricsRenderer().render(readme, report);

        assertThat(rendered).startsWith("before\n").endsWith("after\n");
        assertThat(rendered).contains("| micro | full | 30 | 30 | 100.0% |");
        assertThat(rendered).doesNotContain("\nold\n");
    }
}
