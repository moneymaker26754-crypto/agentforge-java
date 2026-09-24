package io.github.moneymaker26754.agentforge.eval;

import java.util.List;

public final class Java20Manifest {
    public static final String REVISION = "846e647b9f33c0b51b739d005d13d85493c9af09";
    private static final List<String> IDS = List.of(
            "apache__druid-13704", "apache__druid-14092", "apache__druid-14136", "apache__druid-15402",
            "apache__druid-16875", "apache__lucene-11760", "apache__lucene-12022", "apache__lucene-12196",
            "apache__lucene-12212", "apache__lucene-12626", "apache__lucene-13170", "apache__lucene-13301",
            "apache__lucene-13494", "apache__lucene-13704", "google__gson-1014", "google__gson-1093",
            "google__gson-1100", "google__gson-2024", "google__gson-2061", "google__gson-2134");

    private Java20Manifest() {}

    public static BenchmarkManifest manifest() {
        return new BenchmarkManifest("java20", "full", "SWE-bench/SWE-bench_Multilingual", REVISION,
                IDS, 50.0, "1970-01-01T00:00:00Z");
    }
}
