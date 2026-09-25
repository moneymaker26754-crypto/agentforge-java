package io.github.moneymaker26754.agentforge.eval;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Fast, offline invariants used to catch protocol, policy, recovery and accounting regressions.
 *
 * <p>Every case drives the real production components (stream parsers, reflective tool registry,
 * workspace guard, policy engine, local sandbox, agent engine and SQLite store) and fails when the
 * behaviour it is named after changes. See {@link MicroCaseChecks} for the individual invariants.
 */
public final class MicroBenchmarkRunner {
    private static final List<String> CASES = List.of(
            "stream/sse-fragment-name", "stream/sse-fragment-arguments", "stream/ndjson-split-lines",
            "stream/multiple-tool-calls", "stream/malformed-json", "stream/disconnect",
            "tool/unknown-name", "tool/missing-required", "tool/wrong-type", "tool/range-violation",
            "tool/closed-schema", "tool/record-binding", "security/path-traversal", "security/absolute-path",
            "security/symlink-escape", "security/binary-patch", "security/oversized-patch",
            "security/shell-metacharacter", "security/dangerous-command", "security/network-denied",
            "runtime/timeout", "runtime/output-truncation", "runtime/concurrent-stream-drain",
            "runtime/serial-tool-order", "loop/repeated-call", "loop/token-budget", "loop/final-answer",
            "recovery/completed-not-replayed", "recovery/non-idempotent-uncertain", "audit/hash-tamper");

    public BenchmarkReport run(String profile) {
        if (!Set.of("baseline", "full").contains(profile)) throw new IllegalArgumentException("unknown profile: " + profile);
        var results = new ArrayList<BenchmarkCaseResult>();
        try (var checks = new MicroCaseChecks()) {
            for (String name : CASES) {
                long start = System.nanoTime();
                boolean passed;
                String failure = "";
                try {
                    passed = checks.verify(name);
                    if (!passed) {
                        failure = "invariant no longer holds";
                    }
                } catch (Exception exception) {
                    passed = false;
                    failure = exception.getClass().getSimpleName() + ": " + exception.getMessage();
                }
                long elapsed = Math.max(1, System.nanoTime() - start);
                results.add(new BenchmarkCaseResult(name, name.substring(0, name.indexOf('/')),
                        passed ? "PASSED" : "FAILED", elapsed, 0, 0, 0, failure));
            }
        }
        var manifest = new BenchmarkManifest("micro", profile, "agentforge-deterministic-micro",
                "local-deterministic-v1", CASES, 0, java.time.Instant.now().toString());
        return BenchmarkReport.of("micro", profile, manifest, results);
    }
}
