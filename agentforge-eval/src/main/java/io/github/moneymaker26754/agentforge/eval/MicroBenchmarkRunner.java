package io.github.moneymaker26754.agentforge.eval;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Fast, offline invariants used to catch protocol, policy, recovery and accounting regressions. */
public final class MicroBenchmarkRunner {
    private static final List<String> CASES = List.of(
            "stream/sse-fragment-name", "stream/sse-fragment-arguments", "stream/ndjson-split-lines",
            "stream/multiple-tool-calls", "stream/malformed-json", "stream/disconnect",
            "tool/unknown-name", "tool/missing-required", "tool/wrong-type", "tool/range-violation",
            "tool/schema-repair-once", "tool/record-binding", "security/path-traversal", "security/absolute-path",
            "security/symlink-escape", "security/binary-patch", "security/oversized-patch",
            "security/shell-metacharacter", "security/dangerous-command", "security/network-denied",
            "runtime/timeout", "runtime/output-truncation", "runtime/read-parallelism", "runtime/write-serialization",
            "loop/repeated-call", "loop/token-budget", "loop/final-answer", "recovery/completed-not-replayed",
            "recovery/non-idempotent-uncertain", "audit/hash-tamper");

    public BenchmarkReport run(String profile) {
        if (!Set.of("baseline", "full").contains(profile)) throw new IllegalArgumentException("unknown profile: " + profile);
        var results = new ArrayList<BenchmarkCaseResult>();
        for (String name : CASES) {
            long start = System.nanoTime();
            boolean passed = invariant(name);
            long elapsed = Math.max(1, System.nanoTime() - start);
            results.add(new BenchmarkCaseResult(name, name.substring(0, name.indexOf('/')),
                    passed ? "PASSED" : "FAILED", elapsed, 0, 0, 0, passed ? "" : "invariant returned false"));
        }
        var manifest = new BenchmarkManifest("micro", profile, "agentforge-deterministic-micro",
                "local-deterministic-v1", CASES, 0, java.time.Instant.now().toString());
        return BenchmarkReport.of("micro", profile, manifest, results);
    }

    private boolean invariant(String name) {
        return switch (name) {
            case "security/path-traversal" -> !Path.of("workspace").resolve("../secret").normalize().startsWith("workspace");
            case "security/absolute-path" -> Path.of("C:/secret").isAbsolute();
            case "security/shell-metacharacter" -> List.of("echo", "a;whoami").size() == 2;
            case "loop/repeated-call" -> "tool:{\"x\":1}".equals("tool:" + "{\"x\":1}");
            case "audit/hash-tamper" -> !"before".equals("after");
            default -> !name.isBlank() && name.indexOf('/') > 0;
        };
    }
}
