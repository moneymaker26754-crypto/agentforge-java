package io.github.moneymaker26754.agentforge.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.moneymaker26754.agentforge.core.ApprovalDecision;
import io.github.moneymaker26754.agentforge.core.CheckpointStore;
import io.github.moneymaker26754.agentforge.core.DefaultAgentEngine;
import io.github.moneymaker26754.agentforge.core.EventType;
import io.github.moneymaker26754.agentforge.core.ExecutionContext;
import io.github.moneymaker26754.agentforge.core.ModelClient;
import io.github.moneymaker26754.agentforge.core.ModelDeltaSink;
import io.github.moneymaker26754.agentforge.core.ModelResponse;
import io.github.moneymaker26754.agentforge.core.PolicyDecision;
import io.github.moneymaker26754.agentforge.core.ProviderId;
import io.github.moneymaker26754.agentforge.core.RiskLevel;
import io.github.moneymaker26754.agentforge.core.RunBudget;
import io.github.moneymaker26754.agentforge.core.RunRequest;
import io.github.moneymaker26754.agentforge.core.RunStatus;
import io.github.moneymaker26754.agentforge.core.SandboxMode;
import io.github.moneymaker26754.agentforge.core.SessionEvent;
import io.github.moneymaker26754.agentforge.core.SessionId;
import io.github.moneymaker26754.agentforge.core.SessionSnapshot;
import io.github.moneymaker26754.agentforge.core.ToolCall;
import io.github.moneymaker26754.agentforge.core.ToolExecutionGate;
import io.github.moneymaker26754.agentforge.core.ToolHandler;
import io.github.moneymaker26754.agentforge.core.ToolResult;
import io.github.moneymaker26754.agentforge.core.Usage;
import io.github.moneymaker26754.agentforge.infrastructure.security.DefaultPolicyEngine;
import io.github.moneymaker26754.agentforge.infrastructure.tool.AgentTool;
import io.github.moneymaker26754.agentforge.infrastructure.tool.FsPatchTool;
import io.github.moneymaker26754.agentforge.infrastructure.tool.FsReadTool;
import io.github.moneymaker26754.agentforge.infrastructure.tool.ReflectiveToolRegistry;
import io.github.moneymaker26754.agentforge.infrastructure.tool.ToolParam;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Offline CI agent benchmark: a synthetic repository with an injected failing test, driven through
 * the real engine, real file tools, the real PreToolUse gate and a deterministic test oracle.
 *
 * <p>No LLM and no network are involved: the scripted model follows the diagnosis plan
 * {@code read log -> read code -> patch -> targeted test}, so the benchmark measures the runtime
 * closure (loop, validation, policy, approval, observation, reflection) deterministically. Case
 * {@code ci/retry} additionally proves that a failing test observation leads the loop to patch
 * again instead of terminating.
 */
public final class CiAgentBenchmarkRunner implements AutoCloseable {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-29T00:00:00Z"), ZoneOffset.UTC);

    private static final String BROKEN_CALCULATOR = """
            public class Calculator {
                public int divide(int a, int b) {
                    return a / b;
                }
            }
            """;

    private final Path workspace;

    public CiAgentBenchmarkRunner() {
        try {
            this.workspace = Files.createTempDirectory("agentforge-ci-benchmark-");
            Files.writeString(workspace.resolve("ci-failure.log"), """
                    [ERROR] Tests run: 2, Failures: 1, Errors: 0
                    [ERROR]   CalculatorTest.testDivideByZero:12
                    [ERROR] java.lang.ArithmeticException: / by zero
                    [ERROR] \tat com.example.Calculator.divide(Calculator.java:4)
                    [ERROR] \tat com.example.CalculatorTest.testDivideByZero(CalculatorTest.java:12)
                    """, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException("cannot create CI benchmark workspace", exception);
        }
    }

    public BenchmarkReport run(String profile) {
        var results = new ArrayList<BenchmarkCaseResult>();
        results.add(runCase("ci/happy-path", happyPath()));
        results.add(runCase("ci/retry", retryPath()));
        var manifest = new BenchmarkManifest("ci-agent", profile, "agentforge-ci-offline",
                "local-deterministic-v1", results.stream().map(BenchmarkCaseResult::id).toList(), 0,
                Instant.now().toString());
        return BenchmarkReport.of("ci-agent", profile, manifest, results);
    }

    private BenchmarkCaseResult runCase(String name, CaseResult outcome) {
        long start = System.nanoTime();
        boolean passed = outcome.passed();
        String failure = passed ? "" : outcome.failure();
        long elapsed = Math.max(1, System.nanoTime() - start);
        return new BenchmarkCaseResult(name, "ci-agent", passed ? "PASSED" : "FAILED", elapsed,
                0, 0, 0, failure);
    }

    private CaseResult happyPath() {
        try {
            Files.writeString(workspace.resolve("Calculator.java"), BROKEN_CALCULATOR, StandardCharsets.UTF_8);
            var oracle = new TestOracleTool();
            var store = new MemoryStore();
            var engine = engine(new ScriptedModel(
                    toolCall("c1", "fs_read", "{\"path\":\"ci-failure.log\"}"),
                    toolCall("c2", "fs_read", "{\"path\":\"Calculator.java\"}"),
                    toolCall("c3", "fs_patch",
                            "{\"path\":\"Calculator.java\",\"expected\":\"return a / b;\","
                                    + "\"replacement\":\"return b == 0 ? 0 : a / b;\"}"),
                    toolCall("c4", "benchmarkTestOracle", "{\"testSelector\":\"CalculatorTest#testDivideByZero\"}"),
                    ModelResponse.finalAnswer("fixed the division by zero", Usage.zero())), store, oracle);
            var result = engine.run(request());
            String patched = Files.readString(workspace.resolve("Calculator.java"), StandardCharsets.UTF_8);
            return new CaseResult(result.status() == RunStatus.COMPLETED && oracle.invocations == 1
                    && patched.contains("b == 0")
                    && store.events.stream().noneMatch(event -> event.type() == EventType.TOOL_RESULT
                            && event.payload().endsWith(":false")),
                    "status=" + result.status() + " oracleCalls=" + oracle.invocations + " patched=" + patched);
        } catch (IOException exception) {
            return new CaseResult(false, exception.toString());
        }
    }

    private CaseResult retryPath() {
        try {
            Files.writeString(workspace.resolve("Calculator.java"), BROKEN_CALCULATOR, StandardCharsets.UTF_8);
            var oracle = new TestOracleTool();
            var store = new MemoryStore();
            var engine = engine(new ScriptedModel(
                    toolCall("c1", "fs_read", "{\"path\":\"ci-failure.log\"}"),
                    toolCall("c2", "fs_read", "{\"path\":\"Calculator.java\"}"),
                    toolCall("c3", "fs_patch",
                            "{\"path\":\"Calculator.java\",\"expected\":\"return a / b;\","
                                    + "\"replacement\":\"return 1;\"}"),
                    toolCall("c4", "benchmarkTestOracle", "{\"testSelector\":\"CalculatorTest#testDivideByZero\"}"),
                    toolCall("c5", "fs_read", "{\"path\":\"Calculator.java\"}"),
                    toolCall("c6", "fs_patch",
                            "{\"path\":\"Calculator.java\",\"expected\":\"return 1;\","
                                    + "\"replacement\":\"return b == 0 ? 0 : a / b;\"}"),
                    toolCall("c7", "benchmarkTestOracle", "{\"testSelector\":\"CalculatorTest#testDivideByZero\"}"),
                    ModelResponse.finalAnswer("second patch passes", Usage.zero())), store, oracle);
            var result = engine.run(request());
            String patched = Files.readString(workspace.resolve("Calculator.java"), StandardCharsets.UTF_8);
            long failedResults = store.events.stream().filter(event -> event.type() == EventType.TOOL_RESULT
                    && event.payload().endsWith(":false")).count();
            return new CaseResult(result.status() == RunStatus.COMPLETED && oracle.invocations == 2
                    && failedResults == 1 && patched.contains("b == 0"),
                    "status=" + result.status() + " oracleCalls=" + oracle.invocations
                            + " failedObservations=" + failedResults);
        } catch (IOException exception) {
            return new CaseResult(false, exception.toString());
        }
    }

    private DefaultAgentEngine engine(ModelClient model, MemoryStore store, TestOracleTool oracle) {
        var registry = new ReflectiveToolRegistry(MAPPER, List.of(new FsReadTool(), new FsPatchTool(), oracle));
        var gate = ToolExecutionGate.compose(registry.validator(), new DefaultPolicyEngine(false),
                request -> ApprovalDecision.APPROVE_ONCE);
        return new DefaultAgentEngine(model, registry, gate, store, CLOCK);
    }

    private RunRequest request() {
        return new RunRequest(workspace, "Diagnose the reported CI failure, fix the root cause and verify.",
                ProviderId.DEEPSEEK, SandboxMode.LOCAL, RunBudget.defaults());
    }

    private static ModelResponse toolCall(String id, String name, String argumentsJson) {
        return new ModelResponse("", List.of(new ToolCall(id, 0, name, argumentsJson)), Usage.zero(), "tool_calls");
    }

    @Override
    public void close() {
        try (var entries = Files.walk(workspace)) {
            entries.sorted(java.util.Comparator.reverseOrder()).forEach(entry -> {
                try {
                    Files.deleteIfExists(entry);
                } catch (IOException ignored) {
                    // Best effort cleanup.
                }
            });
        } catch (IOException ignored) {
            // Best effort cleanup.
        }
    }

    private record CaseResult(boolean passed, String failure) {}

    private static final class ScriptedModel implements ModelClient {
        private final ArrayDeque<ModelResponse> responses;

        private ScriptedModel(ModelResponse... responses) {
            this.responses = new ArrayDeque<>(List.of(responses));
        }

        @Override
        public ModelResponse exchange(io.github.moneymaker26754.agentforge.core.ChatRequest request,
                ModelDeltaSink sink) {
            return responses.size() > 1 ? responses.removeFirst() : responses.getFirst();
        }
    }

    private static final class MemoryStore implements CheckpointStore {
        private final List<SessionEvent> events = new ArrayList<>();
        private Optional<SessionSnapshot> snapshot = Optional.empty();

        @Override public void append(SessionEvent event) {
            events.add(event);
        }

        @Override public List<SessionEvent> replay(SessionId sessionId) {
            return events.stream().filter(event -> event.sessionId().equals(sessionId)).toList();
        }

        @Override public void saveSnapshot(SessionSnapshot value) {
            this.snapshot = Optional.of(value);
        }

        @Override public Optional<SessionSnapshot> latestSnapshot(SessionId sessionId) {
            return snapshot.filter(value -> value.sessionId().equals(sessionId));
        }
    }

    /**
     * Deterministic test oracle: "the targeted test passes" iff the workspace source contains the
     * zero-check fix. Stands in for {@code runTargetedTest} so the benchmark needs no Maven, no
     * network and no Docker, while still observing a genuine execution-phase result. The name is
     * distinct from the production tool so Spring component scans never register duplicates.
     */
    @AgentTool(name = "benchmarkTestOracle", description = "Deterministic benchmark test oracle",
            risk = RiskLevel.EXECUTE)
    public static final class TestOracleTool implements ToolHandler<TestOracleTool.Arguments> {
        private int invocations;

        public record Arguments(
                @ToolParam(description = "test selector", required = true) String testSelector) {}

        @Override
        public ToolResult execute(Arguments arguments, ExecutionContext context) {
            invocations++;
            try {
                String source = Files.readString(context.workspace().resolve("Calculator.java"),
                        StandardCharsets.UTF_8);
                if (source.contains("b == 0")) {
                    return ToolResult.success("Tests run: 2, Failures: 0, Errors: 0");
                }
                return ToolResult.failure("TEST_FAILED",
                        "CalculatorTest.testDivideByZero: ArithmeticException: / by zero");
            } catch (IOException exception) {
                return ToolResult.failure("TEST_FAILED", exception.getMessage());
            }
        }
    }
}
