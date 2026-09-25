package io.github.moneymaker26754.agentforge.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.moneymaker26754.agentforge.core.ApprovalDecision;
import io.github.moneymaker26754.agentforge.core.ChatMessage;
import io.github.moneymaker26754.agentforge.core.CheckpointStore;
import io.github.moneymaker26754.agentforge.core.CommandSpec;
import io.github.moneymaker26754.agentforge.core.DefaultAgentEngine;
import io.github.moneymaker26754.agentforge.core.EventType;
import io.github.moneymaker26754.agentforge.core.ExecutionContext;
import io.github.moneymaker26754.agentforge.core.ModelClient;
import io.github.moneymaker26754.agentforge.core.ModelDeltaSink;
import io.github.moneymaker26754.agentforge.core.ModelResponse;
import io.github.moneymaker26754.agentforge.core.PolicyDecision;
import io.github.moneymaker26754.agentforge.core.PolicyOutcome;
import io.github.moneymaker26754.agentforge.core.ProviderId;
import io.github.moneymaker26754.agentforge.core.RiskLevel;
import io.github.moneymaker26754.agentforge.core.RunBudget;
import io.github.moneymaker26754.agentforge.core.RunCheckpoint;
import io.github.moneymaker26754.agentforge.core.RunRequest;
import io.github.moneymaker26754.agentforge.core.RunResult;
import io.github.moneymaker26754.agentforge.core.RunStatus;
import io.github.moneymaker26754.agentforge.core.SandboxMode;
import io.github.moneymaker26754.agentforge.core.SessionEvent;
import io.github.moneymaker26754.agentforge.core.SessionId;
import io.github.moneymaker26754.agentforge.core.SessionSnapshot;
import io.github.moneymaker26754.agentforge.core.TerminationReason;
import io.github.moneymaker26754.agentforge.core.ToolCall;
import io.github.moneymaker26754.agentforge.core.ToolDefinition;
import io.github.moneymaker26754.agentforge.core.ToolDescriptor;
import io.github.moneymaker26754.agentforge.core.ToolHandler;
import io.github.moneymaker26754.agentforge.core.ToolInvocation;
import io.github.moneymaker26754.agentforge.core.ToolRegistry;
import io.github.moneymaker26754.agentforge.core.ToolResult;
import io.github.moneymaker26754.agentforge.core.Usage;
import io.github.moneymaker26754.agentforge.infrastructure.model.DeepSeekSseParser;
import io.github.moneymaker26754.agentforge.infrastructure.model.ModelProtocolException;
import io.github.moneymaker26754.agentforge.infrastructure.model.OllamaNdjsonParser;
import io.github.moneymaker26754.agentforge.infrastructure.sandbox.LocalSandboxExecutor;
import io.github.moneymaker26754.agentforge.infrastructure.security.DefaultPolicyEngine;
import io.github.moneymaker26754.agentforge.infrastructure.security.WorkspaceGuard;
import io.github.moneymaker26754.agentforge.infrastructure.security.WorkspaceViolationException;
import io.github.moneymaker26754.agentforge.infrastructure.store.AuditIntegrityException;
import io.github.moneymaker26754.agentforge.infrastructure.store.SqliteCheckpointStore;
import io.github.moneymaker26754.agentforge.infrastructure.tool.AgentTool;
import io.github.moneymaker26754.agentforge.infrastructure.tool.FsPatchTool;
import io.github.moneymaker26754.agentforge.infrastructure.tool.ReflectiveToolRegistry;
import io.github.moneymaker26754.agentforge.infrastructure.tool.ToolParam;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Executes the 30 deterministic micro benchmark cases against the real production components.
 *
 * <p>Each case calls the same classes the CLI uses — stream parsers, the reflective tool registry,
 * the workspace guard, the policy engine, the local sandbox and the agent engine with an in-memory store —
 * and returns {@code false} when the component stops honouring the invariant the case is named after.
 * A case that throws (protocol exception, violation, missing file) is reported as a failure by the runner.
 *
 * <p>Cases that need a filesystem use one temporary workspace created per run; {@link #close()} removes it.
 */
final class MicroCaseChecks implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-24T00:00:00Z"), ZoneOffset.UTC);

    /** Long-running / high-volume probe executed through the local sandbox (compiled by the JDK source launcher). */
    private static final String PROBE_SOURCE = """
            public class Probe {
                public static void main(String[] args) throws Exception {
                    switch (args[0]) {
                        case "sleep" -> Thread.sleep(Long.parseLong(args[1]));
                        case "args" -> System.out.println(String.join("|",
                                java.util.Arrays.copyOfRange(args, 1, args.length)));
                        case "flood" -> flood(Integer.parseInt(args[1]), false);
                        case "floodboth" -> flood(Integer.parseInt(args[1]), true);
                        default -> throw new IllegalArgumentException("unknown mode: " + args[0]);
                    }
                    System.out.flush();
                    System.err.flush();
                }

                private static void flood(int bytes, boolean both) throws Exception {
                    byte[] chunk = new byte[4096];
                    java.util.Arrays.fill(chunk, (byte) 'x');
                    for (int written = 0; written < bytes; written += chunk.length) {
                        int count = Math.min(chunk.length, bytes - written);
                        System.out.write(chunk, 0, count);
                        System.out.flush();
                        if (both) {
                            System.err.write(chunk, 0, count);
                            System.err.flush();
                        }
                    }
                }
            }
            """;

    private final Path workspace;
    private final String javaCommand;
    private final String javaExecutable;

    MicroCaseChecks() {
        try {
            this.workspace = Files.createTempDirectory("agentforge-micro-");
        } catch (IOException exception) {
            throw new UncheckedIOException("cannot create micro benchmark workspace", exception);
        }
        this.javaCommand = ProcessHandle.current().info().command().orElse("java");
        this.javaExecutable = Path.of(javaCommand).getFileName().toString().toLowerCase(Locale.ROOT);
    }

    /** @return true only when the invariant behind {@code name} still holds in the current build. */
    boolean verify(String name) throws Exception {
        return switch (name) {
            // ---- stream protocol adaptation -------------------------------------------------
            case "stream/sse-fragment-name" -> sseFragmentName();
            case "stream/sse-fragment-arguments" -> sseFragmentArguments();
            case "stream/ndjson-split-lines" -> ndjsonSplitLines();
            case "stream/multiple-tool-calls" -> multipleToolCalls();
            case "stream/malformed-json" -> malformedJsonRejected();
            case "stream/disconnect" -> disconnectedStreamKeepsPartialResult();

            // ---- typed tool registry --------------------------------------------------------
            case "tool/unknown-name" -> unknownToolIsNotRegistered();
            case "tool/missing-required" -> missingRequiredArgumentRejected();
            case "tool/wrong-type" -> wrongArgumentTypeRejected();
            case "tool/range-violation" -> outOfRangeArgumentRejected();
            case "tool/closed-schema" -> schemaIsClosedAndRejectsUnknownFields();
            case "tool/record-binding" -> validJsonBindsToRecordAndExecutes();

            // ---- layered security -----------------------------------------------------------
            case "security/path-traversal" -> rejectsParentTraversal();
            case "security/absolute-path" -> rejectsAbsolutePath();
            case "security/symlink-escape" -> rejectsSymlinkEscape();
            case "security/binary-patch" -> refusesPatchOnBinaryFile();
            case "security/oversized-patch" -> refusesOversizedPatch();
            case "security/shell-metacharacter" -> shellMetacharactersStayLiteral();
            case "security/dangerous-command" -> destructiveRiskIsDenied();
            case "security/network-denied" -> networkRiskIsDenied();

            // ---- sandbox runtime ------------------------------------------------------------
            case "runtime/timeout" -> timedOutCommandIsKilled();
            case "runtime/output-truncation" -> oversizedOutputIsTruncated();
            case "runtime/concurrent-stream-drain" -> bothStreamsDrainWithoutDeadlock();
            case "runtime/serial-tool-order" -> toolCallsRunSeriallyInCallOrder();

            // ---- agent loop -----------------------------------------------------------------
            case "loop/repeated-call" -> repeatedToolCallTerminates();
            case "loop/token-budget" -> tokenBudgetTerminates();
            case "loop/final-answer" -> finalAnswerCompletes();

            // ---- recovery and audit ---------------------------------------------------------
            case "recovery/completed-not-replayed" -> completedSessionIsNotReplayed();
            case "recovery/non-idempotent-uncertain" -> nonIdempotentIntentBecomesUncertain();
            case "audit/hash-tamper" -> tamperedEventBreaksHashChain();

            default -> throw new IllegalArgumentException("unknown micro benchmark case: " + name);
        };
    }

    // ---------------------------------------------------------------------------------------
    // stream/*: fragmented protocol frames must reassemble into one deterministic ToolCall
    // ---------------------------------------------------------------------------------------

    private boolean sseFragmentName() {
        var response = deepSeekFragmentResponse();
        return response.toolCalls().size() == 1
                && "call-1".equals(response.toolCalls().getFirst().id())
                && "fs_read".equals(response.toolCalls().getFirst().name());
    }

    private boolean sseFragmentArguments() {
        var response = deepSeekFragmentResponse();
        return response.toolCalls().size() == 1
                && "{\"path\":\"README.md\"}".equals(response.toolCalls().getFirst().argumentsJson());
    }

    private io.github.moneymaker26754.agentforge.core.ModelResponse deepSeekFragmentResponse() {
        var parser = new DeepSeekSseParser(MAPPER, delta -> { }, 0.002, 0.008);
        parser.accept("data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call-1\","
                + "\"function\":{\"name\":\"fs_\",\"arguments\":\"{\\\"pa\"}}]}}]}");
        parser.accept("data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,"
                + "\"function\":{\"name\":\"read\",\"arguments\":\"th\\\":\\\"README.md\\\"}\"}}]}}]}");
        return parser.finish();
    }

    private boolean ndjsonSplitLines() {
        var parser = new OllamaNdjsonParser(MAPPER, delta -> { });
        parser.accept("{\"message\":{\"content\":\"par\"},\"done\":false}");
        parser.accept("{\"message\":{\"content\":\"tial\"},\"done\":true,\"done_reason\":\"stop\"}");
        var response = parser.finish();
        return "partial".equals(response.content()) && "stop".equals(response.finishReason());
    }

    private boolean multipleToolCalls() {
        var parser = new OllamaNdjsonParser(MAPPER, delta -> { });
        parser.accept("{\"message\":{\"content\":\"\",\"tool_calls\":["
                + "{\"function\":{\"index\":0,\"name\":\"fs_read\",\"arguments\":{\"path\":\"pom.xml\"}}},"
                + "{\"function\":{\"index\":1,\"name\":\"git_diff\",\"arguments\":{}}}]},\"done\":false}");
        var calls = parser.finish().toolCalls();
        return calls.size() == 2
                && "fs_read".equals(calls.get(0).name()) && calls.get(0).index() == 0
                && "git_diff".equals(calls.get(1).name()) && calls.get(1).index() == 1;
    }

    private boolean malformedJsonRejected() {
        var parser = new DeepSeekSseParser(MAPPER, delta -> { }, 0, 0);
        try {
            parser.accept("data: {not-json}");
            return false;
        } catch (ModelProtocolException expected) {
            return true;
        }
    }

    private boolean disconnectedStreamKeepsPartialResult() {
        // A dropped connection never delivers [DONE]; whatever arrived must still be usable.
        var parser = new DeepSeekSseParser(MAPPER, delta -> { }, 0, 0);
        parser.accept("data: {\"choices\":[{\"delta\":{\"content\":\"half\"}}]}");
        var response = parser.finish();
        return "half".equals(response.content()) && "unknown".equals(response.finishReason());
    }

    // ---------------------------------------------------------------------------------------
    // tool/*: the reflective registry must validate before any handler body runs
    // ---------------------------------------------------------------------------------------

    private boolean unknownToolIsNotRegistered() {
        var registry = new ReflectiveToolRegistry(MAPPER, List.of(new EchoTool()));
        return registry.find("definitely-not-registered").isEmpty()
                && registry.find("echo").isPresent();
    }

    private boolean missingRequiredArgumentRejected() {
        var tool = new EchoTool();
        var result = execute(tool, "{\"uppercase\":true}");
        return rejected(result) && tool.invocations.isEmpty();
    }

    private boolean wrongArgumentTypeRejected() {
        var tool = new EchoTool();
        var result = execute(tool, "{\"text\":123}");
        return rejected(result) && tool.invocations.isEmpty();
    }

    private boolean outOfRangeArgumentRejected() {
        var tool = new EchoTool();
        // count declares min = 1 / max = 5 in the arguments record.
        var result = execute(tool, "{\"text\":\"x\",\"count\":99}");
        return rejected(result) && tool.invocations.isEmpty();
    }

    private boolean schemaIsClosedAndRejectsUnknownFields() {
        var tool = new EchoTool();
        var registry = new ReflectiveToolRegistry(MAPPER, List.of(tool));
        String schema = registry.descriptors().getFirst().parametersJsonSchema();
        var unknownField = execute(tool, "{\"text\":\"x\",\"surprise\":1}");
        return schema.contains("\"additionalProperties\":false")
                && schema.contains("\"required\":[\"text\"]")
                && schema.contains("\"argv\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}")
                && rejected(unknownField)
                && tool.invocations.isEmpty();
    }

    private boolean validJsonBindsToRecordAndExecutes() {
        var tool = new EchoTool();
        var result = execute(tool, "{\"text\":\"hello\",\"uppercase\":true,\"count\":3,\"argv\":[\"a\",\"b\"]}");
        return result.success()
                && "HELLO:3:a,b".equals(result.content())
                && tool.invocations.equals(List.of("hello"));
    }

    private boolean rejected(ToolResult result) {
        return !result.success() && "INVALID_ARGUMENTS".equals(result.errorCode());
    }

    private ToolResult execute(EchoTool tool, String argumentsJson) {
        var definition = new ReflectiveToolRegistry(MAPPER, List.of(tool)).find("echo").orElseThrow();
        return definition.executor().execute(argumentsJson, context());
    }

    // ---------------------------------------------------------------------------------------
    // security/*: every escape hatch must stay closed
    // ---------------------------------------------------------------------------------------

    private boolean rejectsParentTraversal() {
        try {
            new WorkspaceGuard(workspace).resolveForWrite("../escape.txt");
            return false;
        } catch (WorkspaceViolationException expected) {
            return true;
        }
    }

    private boolean rejectsAbsolutePath() {
        try {
            new WorkspaceGuard(workspace).resolveForWrite(workspace.resolve("absolute.txt").toString());
            return false;
        } catch (WorkspaceViolationException expected) {
            return true;
        }
    }

    private boolean rejectsSymlinkEscape() throws IOException {
        Path outside = Files.createTempDirectory("agentforge-outside-");
        Files.writeString(outside.resolve("secret.txt"), "secret");
        Path link = workspace.resolve("escape-link");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (IOException | UnsupportedOperationException unsupported) {
            // Host cannot create symlinks (e.g. Windows without the privilege): fall back to asserting
            // the real-path boundary itself, which is the mechanism that blocks the escape.
            Files.writeString(workspace.resolve("inside.txt"), "ok");
            return new WorkspaceGuard(workspace).resolveExisting("inside.txt")
                    .equals(workspace.resolve("inside.txt").toRealPath());
        }
        try {
            new WorkspaceGuard(workspace).resolveExisting("escape-link/secret.txt");
            return false;
        } catch (WorkspaceViolationException expected) {
            return true;
        }
    }

    private boolean refusesPatchOnBinaryFile() throws IOException {
        Path binary = workspace.resolve("blob.bin");
        byte[] original = {'a', 'b', 'c', 0, 'd', 'e', 'f'};
        Files.write(binary, original);
        var result = new FsPatchTool().execute(new FsPatchTool.Arguments("blob.bin", "abc", "xyz"), context());
        return !result.success()
                && "BINARY_FILE".equals(result.errorCode())
                && java.util.Arrays.equals(Files.readAllBytes(binary), original);
    }

    private boolean refusesOversizedPatch() {
        String oversized = "x".repeat(1024 * 1024 + 1);
        var result = new FsPatchTool().execute(new FsPatchTool.Arguments("absent.txt", oversized, "y"), context());
        return !result.success() && "PATCH_TOO_LARGE".equals(result.errorCode());
    }

    private boolean shellMetacharactersStayLiteral() throws Exception {
        // End-to-end proof that argv never reaches a shell: the probe prints its own argv verbatim.
        Path probe = writeProbe();
        var result = new LocalSandboxExecutor(Set.of(javaExecutable)).execute(
                new CommandSpec(List.of(javaCommand, probe.toString(), "args", "a;whoami", "$(id)", "b|c"),
                        Duration.ofSeconds(60), 65_536),
                context());
        return result.exitCode() == 0 && "a;whoami|$(id)|b|c".equals(result.stdout().trim());
    }

    private boolean destructiveRiskIsDenied() {
        var policy = new DefaultPolicyEngine(false);
        return policy.decide(invocation(RiskLevel.DESTRUCTIVE)).outcome() == PolicyOutcome.DENY
                && policy.decide(invocation(RiskLevel.WRITE)).outcome() == PolicyOutcome.ASK
                && policy.decide(invocation(RiskLevel.READ)).outcome() == PolicyOutcome.ALLOW;
    }

    private boolean networkRiskIsDenied() {
        return new DefaultPolicyEngine(false).decide(invocation(RiskLevel.NETWORK)).outcome() == PolicyOutcome.DENY;
    }

    private ToolInvocation invocation(RiskLevel risk) {
        var definition = new ToolDefinition("tool", "micro invocation", "{}", risk, risk == RiskLevel.READ,
                (json, ctx) -> ToolResult.success("ok"));
        return new ToolInvocation(new ToolCall("call", 0, "tool", "{}"), definition, context());
    }

    // ---------------------------------------------------------------------------------------
    // runtime/*: the local sandbox must stay bounded and the loop must stay ordered
    // ---------------------------------------------------------------------------------------

    private boolean timedOutCommandIsKilled() throws Exception {
        Path probe = writeProbe();
        var result = new LocalSandboxExecutor(Set.of(javaExecutable)).execute(
                new CommandSpec(List.of(javaCommand, probe.toString(), "sleep", "10000"), Duration.ofSeconds(3), 65_536),
                context());
        return result.timedOut() && result.exitCode() == -1;
    }

    private boolean oversizedOutputIsTruncated() throws Exception {
        Path probe = writeProbe();
        var result = new LocalSandboxExecutor(Set.of(javaExecutable)).execute(
                new CommandSpec(List.of(javaCommand, probe.toString(), "flood", "200000"), Duration.ofSeconds(60), 1024),
                context());
        return result.exitCode() == 0
                && !result.timedOut()
                && result.stdout().endsWith("[TRUNCATED]")
                && result.stdout().length() < 4096;
    }

    private boolean bothStreamsDrainWithoutDeadlock() throws Exception {
        // The probe interleaves writes to stdout and stderr. Draining the pipes one after another would
        // fill the second pipe buffer and block the child before it ever closes the first stream.
        Path probe = writeProbe();
        var result = new LocalSandboxExecutor(Set.of(javaExecutable)).execute(
                new CommandSpec(List.of(javaCommand, probe.toString(), "floodboth", "262144"), Duration.ofSeconds(60), 8192),
                context());
        return result.exitCode() == 0
                && !result.timedOut()
                && result.stdout().endsWith("[TRUNCATED]")
                && result.stderr().endsWith("[TRUNCATED]");
    }

    private boolean toolCallsRunSeriallyInCallOrder() {
        var executed = new ArrayList<String>();
        ToolRegistry registry = new ToolRegistry() {
            @Override public Optional<ToolDefinition> find(String name) {
                if (!name.startsWith("step")) {
                    return Optional.empty();
                }
                return Optional.of(new ToolDefinition(name, name, "{}", RiskLevel.READ, true, (json, ctx) -> {
                    executed.add("start-" + name);
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    executed.add("end-" + name);
                    return ToolResult.success(name);
                }));
            }

            @Override public List<ToolDescriptor> descriptors() {
                return List.of();
            }
        };
        var model = new ScriptedModel(
                new ModelResponse("", List.of(new ToolCall("c1", 0, "step-1", "{}"),
                        new ToolCall("c2", 1, "step-2", "{}")), Usage.zero(), "tool_calls"),
                new ModelResponse("done", List.of(), Usage.zero(), "stop"));

        RunResult result = engine(model, new MemoryStore(), registry).run(request(RunBudget.defaults()));

        return result.status() == RunStatus.COMPLETED
                && executed.equals(List.of("start-step-1", "end-step-1", "start-step-2", "end-step-2"));
    }

    // ---------------------------------------------------------------------------------------
    // loop/*: the deterministic state machine owns every termination decision
    // ---------------------------------------------------------------------------------------

    private boolean repeatedToolCallTerminates() {
        var model = new ScriptedModel(new ModelResponse("", List.of(
                new ToolCall("different-id", 0, "echo", "{\"text\":\"same\"}")), Usage.zero(), "tool_calls"));
        var budget = new RunBudget(30, Duration.ofMinutes(20), 200_000, 30_000, 50, 3);

        RunResult result = engine(model, new MemoryStore(), echoRegistry()).run(request(budget));

        return result.status() == RunStatus.FAILED
                && result.terminationReason() == TerminationReason.REPEATED_TOOL_CALL
                && model.requests == 3;
    }

    private boolean tokenBudgetTerminates() {
        var model = new ScriptedModel(new ModelResponse("not returned", List.of(), new Usage(10, 0, 0), "stop"));
        var budget = new RunBudget(5, Duration.ofMinutes(1), 10, 10, 10, 3);

        RunResult result = engine(model, new MemoryStore(), echoRegistry()).run(request(budget));

        return result.status() == RunStatus.BUDGET_EXHAUSTED
                && result.terminationReason() == TerminationReason.INPUT_TOKEN_BUDGET;
    }

    private boolean finalAnswerCompletes() {
        var store = new MemoryStore();
        var model = new ScriptedModel(new ModelResponse("fixed", List.of(), new Usage(1, 1, 0), "stop"));

        RunResult result = engine(model, store, echoRegistry()).run(request(RunBudget.defaults()));

        return result.status() == RunStatus.COMPLETED
                && "fixed".equals(result.answer())
                && result.terminationReason() == TerminationReason.FINAL_ANSWER
                && store.events.stream().anyMatch(event -> event.type() == EventType.SESSION_COMPLETED);
    }

    // ---------------------------------------------------------------------------------------
    // recovery/* and audit/*: resume must not silently repeat side effects
    // ---------------------------------------------------------------------------------------

    private boolean completedSessionIsNotReplayed() {
        var store = new MemoryStore();
        var completed = engine(new ScriptedModel(new ModelResponse("already done", List.of(),
                new Usage(3, 2, 0.1), "stop")), store, echoRegistry()).run(request(RunBudget.defaults()));
        var unused = new ScriptedModel(new ModelResponse("must not call", List.of(), Usage.zero(), "stop"));

        RunResult resumed = engine(unused, store, echoRegistry()).resume(completed.sessionId());

        return resumed.status() == RunStatus.COMPLETED
                && "already done".equals(resumed.answer())
                && unused.requests == 0;
    }

    private boolean nonIdempotentIntentBecomesUncertain() {
        var store = new MemoryStore();
        var id = new SessionId("micro-uncertain");
        RunRequest request = request(RunBudget.defaults());
        store.events.add(SessionEvent.of(id, 0, EventType.SESSION_STARTED, CLOCK.instant(), "start"));
        store.events.add(SessionEvent.of(id, 1, EventType.TOOL_INTENT, CLOCK.instant(), "call:fs_patch"));
        store.snapshot = Optional.of(new SessionSnapshot(id, 1, RunStatus.RUNNING,
                List.of(ChatMessage.system("system"), ChatMessage.user("task")), Usage.zero(), CLOCK.instant(),
                RunCheckpoint.from(request, 0, null, 0, CLOCK.instant())));
        ToolRegistry nonIdempotent = new ToolRegistry() {
            @Override public Optional<ToolDefinition> find(String name) {
                return Optional.of(new ToolDefinition("fs_patch", "patch", "{}", RiskLevel.WRITE, false,
                        (json, ctx) -> ToolResult.success("must not run")));
            }

            @Override public List<ToolDescriptor> descriptors() {
                return List.of();
            }
        };
        var model = new ScriptedModel(new ModelResponse("must not call", List.of(), Usage.zero(), "stop"));

        RunResult result = engine(model, store, nonIdempotent).resume(id);

        return result.status() == RunStatus.UNCERTAIN
                && result.terminationReason() == TerminationReason.UNCERTAIN_TOOL
                && model.requests == 0;
    }

    private boolean tamperedEventBreaksHashChain() throws Exception {
        Path database = Files.createTempDirectory("agentforge-audit-").resolve("state.db");
        var store = new SqliteCheckpointStore(database, MAPPER);
        var id = new SessionId("micro-tamper-" + System.nanoTime());
        store.append(SessionEvent.of(id, 0, EventType.SESSION_STARTED, Instant.parse("2026-01-01T00:00:00Z"), "safe"));
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                var statement = connection.prepareStatement(
                        "update session_events set payload = 'changed' where session_id = ?")) {
            statement.setString(1, id.value());
            statement.executeUpdate();
        }
        try {
            store.replay(id);
            return false;
        } catch (AuditIntegrityException expected) {
            return true;
        }
    }

    // ---------------------------------------------------------------------------------------
    // fixtures
    // ---------------------------------------------------------------------------------------

    private Path writeProbe() throws IOException {
        Path probe = workspace.resolve("Probe.java");
        if (!Files.exists(probe)) {
            Files.writeString(probe, PROBE_SOURCE, StandardCharsets.UTF_8);
        }
        return probe;
    }

    private ExecutionContext context() {
        return new ExecutionContext(new SessionId("micro"), workspace, SandboxMode.LOCAL);
    }

    private RunRequest request(RunBudget budget) {
        return new RunRequest(workspace, "micro benchmark task", ProviderId.DEEPSEEK, SandboxMode.LOCAL, budget);
    }

    private static DefaultAgentEngine engine(ModelClient model, CheckpointStore store, ToolRegistry registry) {
        return new DefaultAgentEngine(model, registry, invocation -> PolicyDecision.allow("micro benchmark"),
                request -> ApprovalDecision.APPROVE_ONCE, store, CLOCK);
    }

    private static ToolRegistry echoRegistry() {
        return new ToolRegistry() {
            @Override public Optional<ToolDefinition> find(String name) {
                if (!"echo".equals(name)) {
                    return Optional.empty();
                }
                return Optional.of(new ToolDefinition("echo", "echo", "{}", RiskLevel.READ, true,
                        (json, ctx) -> ToolResult.success(json)));
            }

            @Override public List<ToolDescriptor> descriptors() {
                return List.of(new ToolDescriptor("echo", "echo", "{}"));
            }
        };
    }

    @Override
    public void close() {
        deleteRecursively(workspace);
    }

    private static void deleteRecursively(Path path) {
        try (var entries = Files.walk(path)) {
            entries.sorted(java.util.Comparator.reverseOrder()).forEach(entry -> {
                try {
                    Files.deleteIfExists(entry);
                } catch (IOException ignored) {
                    // Best effort: a locked SQLite file must not fail the benchmark run.
                }
            });
        } catch (IOException ignored) {
            // Best effort cleanup only.
        }
    }

    /** Model client that replays the scripted responses in order and repeats the last one. */
    private static final class ScriptedModel implements ModelClient {
        private final ArrayDeque<ModelResponse> responses;
        private int requests;

        private ScriptedModel(ModelResponse... responses) {
            this.responses = new ArrayDeque<>(List.of(responses));
        }

        @Override
        public ModelResponse exchange(io.github.moneymaker26754.agentforge.core.ChatRequest request,
                ModelDeltaSink sink) {
            requests++;
            return responses.size() > 1 ? responses.removeFirst() : responses.getFirst();
        }
    }

    /** In-memory checkpoint store mirroring the append-only contract without SQLite. */
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

    /** Registered tool used by the tool/* cases; exercises every schema branch the generator supports. */
    @AgentTool(name = "echo", description = "Echo bound arguments", risk = RiskLevel.READ, idempotent = true)
    static final class EchoTool implements ToolHandler<EchoArguments> {
        private final List<String> invocations = new ArrayList<>();

        @Override
        public ToolResult execute(EchoArguments arguments, ExecutionContext context) {
            invocations.add(arguments.text());
            String text = arguments.uppercase() ? arguments.text().toUpperCase(Locale.ROOT) : arguments.text();
            return ToolResult.success(text + ":" + arguments.count() + ":" + String.join(",", arguments.argv()));
        }
    }

    record EchoArguments(
            @ToolParam(description = "text to echo", required = true) String text,
            @ToolParam(description = "uppercase the output") boolean uppercase,
            @ToolParam(description = "repeat count", min = 1, max = 5) int count,
            @ToolParam(description = "extra arguments") List<String> argv) { }
}
