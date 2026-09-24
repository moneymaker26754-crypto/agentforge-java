package io.github.moneymaker26754.agentforge.eval;

import java.util.Locale;

public final class ReadmeMetricsRenderer {
    public static final String START = "<!-- BENCHMARK:START -->";
    public static final String END = "<!-- BENCHMARK:END -->";

    public String render(String readme, BenchmarkReport report) {
        int start = readme.indexOf(START);
        int end = readme.indexOf(END);
        if (start < 0 || end < start) throw new IllegalArgumentException("README benchmark markers are missing");
        BenchmarkMetrics metrics = report.metrics();
        String table = START + "\n| Suite | Profile | Total | Passed | Pass rate |\n"
                + "|---|---|---:|---:|---:|\n"
                + String.format(Locale.ROOT, "| %s | %s | %d | %d | %.1f%% |\n", report.suite(), report.profile(),
                        metrics.total(), metrics.passed(), metrics.passRate() * 100)
                + END;
        return readme.substring(0, start) + table + readme.substring(end + END.length());
    }
}
