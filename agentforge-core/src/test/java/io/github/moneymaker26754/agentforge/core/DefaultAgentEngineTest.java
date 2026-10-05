package io.github.moneymaker26754.agentforge.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DefaultAgentEngineTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-24T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void completesWhenModelReturnsFinalAnswerWithoutTools() {
        var model = new ScriptedModelClient(ModelResponse.finalAnswer("fixed", new Usage(12, 4, 0.01)));
        var store = new MemoryCheckpointStore();
        var engine = engine(model, store, new EchoToolRegistry());

        RunResult result = engine.run(request(RunBudget.defaults()));

        assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(result.answer()).isEqualTo("fixed");
        assertThat(result.usage()).isEqualTo(new Usage(12, 4, 0.01));
        assertThat(store.events).extracting(SessionEvent::type)
                .containsExactly(EventType.SESSION_STARTED, EventType.PHASE_ENTERED, EventType.MODEL_RESPONSE,
                        EventType.PHASE_ENTERED, EventType.SESSION_COMPLETED);
        assertThat(store.events).extracting(SessionEvent::payload)
                .filteredOn(payload -> List.of("PLAN", "REFLECT").contains(payload))
                .containsExactly("PLAN", "REFLECT");
    }

    @Test
    void executesValidatedToolAndReturnsItsResultToModel() {
        var model = new ScriptedModelClient(
                ModelResponse.toolCalls(List.of(new ToolCall("call-1", 0, "echo", "{\"text\":\"hello\"}")), new Usage(8, 2, 0)),
                ModelResponse.finalAnswer("done", new Usage(5, 1, 0)));
        var tool = new EchoToolRegistry();
        var engine = engine(model, new MemoryCheckpointStore(), tool);

        RunResult result = engine.run(request(RunBudget.defaults()));

        assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(tool.invocations).containsExactly("{\"text\":\"hello\"}");
        assertThat(model.requests.get(1).messages())
                .anySatisfy(message -> {
                    assertThat(message.role()).isEqualTo(ChatRole.TOOL);
                    assertThat(message.content()).isEqualTo("hello");
                    assertThat(message.toolCallId()).isEqualTo("call-1");
                });
    }

    @Test
    void stopsAtIterationBudget() {
        var response = ModelResponse.toolCalls(
                List.of(new ToolCall("call", 0, "echo", "{\"text\":\"again\"}")), Usage.zero());
        var model = new RepeatingModelClient(response);
        var budget = new RunBudget(2, Duration.ofMinutes(20), 200_000, 30_000, 50, 5);
        var engine = engine(model, new MemoryCheckpointStore(), new EchoToolRegistry());

        RunResult result = engine.run(request(budget));

        assertThat(result.status()).isEqualTo(RunStatus.BUDGET_EXHAUSTED);
        assertThat(result.terminationReason()).isEqualTo(TerminationReason.MAX_ITERATIONS);
        assertThat(model.calls).isEqualTo(2);
    }

    @Test
    void stopsAfterConfiguredRepeatedToolCallLimit() {
        var response = ModelResponse.toolCalls(
                List.of(new ToolCall("different-id", 0, "echo", "{\"text\":\"same\"}")), Usage.zero());
        var model = new RepeatingModelClient(response);
        var budget = new RunBudget(30, Duration.ofMinutes(20), 200_000, 30_000, 50, 3);
        var engine = engine(model, new MemoryCheckpointStore(), new EchoToolRegistry());

        RunResult result = engine.run(request(budget));

        assertThat(result.status()).isEqualTo(RunStatus.FAILED);
        assertThat(result.terminationReason()).isEqualTo(TerminationReason.REPEATED_TOOL_CALL);
        assertThat(model.calls).isEqualTo(3);
    }

    @Test
    void compressesContextBeforeSendingNextModelRequest() {
        var model = new ScriptedModelClient(
                ModelResponse.toolCalls(List.of(new ToolCall("call-1", 0, "echo", "{\"text\":\"hello\"}")), Usage.zero()),
                ModelResponse.finalAnswer("done", Usage.zero()));
        var store = new MemoryCheckpointStore();
        var context = new ContextManager(String::length, 60, 0.75, 1);
        var engine = new DefaultAgentEngine(model, new EchoToolRegistry(), invocation -> PolicyDecision.allow("test"),
                request -> ApprovalDecision.APPROVE_ONCE, store, CLOCK, context);

        RunResult result = engine.run(new RunRequest(Path.of("."), "a deliberately long task that fills context",
                ProviderId.DEEPSEEK, SandboxMode.LOCAL, RunBudget.defaults()));

        assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(store.events).extracting(SessionEvent::type).contains(EventType.CONTEXT_COMPRESSED);
        assertThat(model.requests.get(1).messages()).anyMatch(message -> message.content().startsWith("[context-summary]"));
    }

    @Test
    void resumesFromLatestSafeSnapshotWithoutRepeatingCompletedTool() {
        var firstModel = new ScriptedModelClient(
                ModelResponse.toolCalls(List.of(new ToolCall("call-1", 0, "echo", "{\"text\":\"hello\"}")), Usage.zero()),
                ModelResponse.finalAnswer("done", Usage.zero()));
        var store = new MemoryCheckpointStore();
        var firstEngine = engine(firstModel, store, new EchoToolRegistry());
        RunResult completed = firstEngine.run(request(RunBudget.defaults()));
        SessionSnapshot checkpoint = store.snapshot.orElseThrow();
        store.events.removeIf(event -> event.type() == EventType.SESSION_COMPLETED);
        store.snapshot = Optional.of(new SessionSnapshot(checkpoint.sessionId(), checkpoint.sequence(), RunStatus.RUNNING,
                checkpoint.messages().subList(0, checkpoint.messages().size() - 1), checkpoint.usage(), checkpoint.createdAt(),
                checkpoint.checkpoint()));

        var resumedModel = new ScriptedModelClient(ModelResponse.finalAnswer("resumed", Usage.zero()));
        RunResult resumed = engine(resumedModel, store, new EchoToolRegistry()).resume(completed.sessionId());

        assertThat(resumed.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(resumed.answer()).isEqualTo("resumed");
        assertThat(resumedModel.requests).hasSize(1);
    }

    @Test
    void returnsUnknownDeniedAndRejectedCallsToModelWithoutExecutingThem() {
        var model = new ScriptedModelClient(
                ModelResponse.toolCalls(List.of(new ToolCall("u", 0, "missing", "{}")), Usage.zero()),
                ModelResponse.toolCalls(List.of(new ToolCall("d", 0, "echo", "{\"text\":\"deny\"}")), Usage.zero()),
                ModelResponse.toolCalls(List.of(new ToolCall("r", 0, "echo", "{\"text\":\"ask\"}")), Usage.zero()),
                ModelResponse.finalAnswer("done", Usage.zero()));
        var policies = new ArrayDeque<>(List.of(PolicyDecision.deny("blocked"), PolicyDecision.ask("confirm")));
        var engine = new DefaultAgentEngine(model, new EchoToolRegistry(), invocation -> policies.removeFirst(),
                request -> ApprovalDecision.REJECT, new MemoryCheckpointStore(), CLOCK);

        RunResult result = engine.run(request(RunBudget.defaults()));

        assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(model.requests).flatExtracting(ChatRequest::messages).extracting(ChatMessage::content)
                .contains("ERROR UNKNOWN_TOOL: missing", "ERROR POLICY_DENIED: blocked", "ERROR USER_REJECTED");
    }

    @Test
    void stopsOnInputOutputAndCostBudgetsAfterUsageArrives() {
        assertBudgetTermination(new Usage(10, 0, 0), new RunBudget(5, Duration.ofMinutes(1), 10, 10, 10, 3),
                TerminationReason.INPUT_TOKEN_BUDGET);
        assertBudgetTermination(new Usage(0, 10, 0), new RunBudget(5, Duration.ofMinutes(1), 10, 10, 10, 3),
                TerminationReason.OUTPUT_TOKEN_BUDGET);
        assertBudgetTermination(new Usage(0, 0, 1), new RunBudget(5, Duration.ofMinutes(1), 10, 10, 1, 3),
                TerminationReason.COST_BUDGET);
    }

    @Test
    void leavesNonIdempotentUnfinishedIntentUncertain() {
        var store = new MemoryCheckpointStore();
        var id = new SessionId("uncertain");
        RunRequest request = request(RunBudget.defaults());
        store.events.add(SessionEvent.of(id, 0, EventType.SESSION_STARTED, CLOCK.instant(), "start"));
        store.events.add(SessionEvent.of(id, 1, EventType.TOOL_INTENT, CLOCK.instant(), "c:write"));
        store.snapshot = Optional.of(new SessionSnapshot(id, 1, RunStatus.RUNNING,
                List.of(ChatMessage.system("s"), ChatMessage.user("goal")), Usage.zero(), CLOCK.instant(),
                RunCheckpoint.from(request, 0, null, 0, CLOCK.instant())));
        ToolRegistry nonIdempotent = new ToolRegistry() {
            @Override public Optional<ToolDefinition> find(String name) {
                return Optional.of(new ToolDefinition("write", "write", "{}", RiskLevel.WRITE, false,
                        (arguments, context) -> ToolResult.success("should not run")));
            }
            @Override public List<ToolDescriptor> descriptors() { return List.of(); }
        };
        var model = new ScriptedModelClient(ModelResponse.finalAnswer("must not call", Usage.zero()));

        RunResult result = engine(model, store, nonIdempotent).resume(id);

        assertThat(result.status()).isEqualTo(RunStatus.UNCERTAIN);
        assertThat(result.terminationReason()).isEqualTo(TerminationReason.UNCERTAIN_TOOL);
        assertThat(model.requests).isEmpty();
    }

    @Test
    void resumeOfCompletedSessionReturnsStoredAnswerWithoutCallingModel() {
        var store = new MemoryCheckpointStore();
        var first = engine(new ScriptedModelClient(ModelResponse.finalAnswer("already done", new Usage(3, 2, 0.1))),
                store, new EchoToolRegistry()).run(request(RunBudget.defaults()));
        var unused = new ScriptedModelClient(ModelResponse.finalAnswer("must not call", Usage.zero()));

        RunResult resumed = engine(unused, store, new EchoToolRegistry()).resume(first.sessionId());

        assertThat(resumed.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(resumed.answer()).isEqualTo("already done");
        assertThat(resumed.usage()).isEqualTo(new Usage(3, 2, 0.1));
        assertThat(unused.requests).isEmpty();
    }

    @Test
    void resumeOfBudgetTerminatedSessionDoesNotRestartLoop() {
        var store = new MemoryCheckpointStore();
        var repeating = new RepeatingModelClient(ModelResponse.toolCalls(
                List.of(new ToolCall("c", 0, "echo", "{\"text\":\"x\"}")), Usage.zero()));
        RunBudget oneIteration = new RunBudget(1, Duration.ofMinutes(1), 100, 100, 10, 3);
        RunResult first = engine(repeating, store, new EchoToolRegistry()).run(request(oneIteration));
        int eventsBeforeResume = store.events.size();
        var unused = new ScriptedModelClient(ModelResponse.finalAnswer("must not call", Usage.zero()));

        RunResult resumed = engine(unused, store, new EchoToolRegistry()).resume(first.sessionId());

        assertThat(resumed.status()).isEqualTo(RunStatus.BUDGET_EXHAUSTED);
        assertThat(resumed.terminationReason()).isEqualTo(TerminationReason.MAX_ITERATIONS);
        assertThat(unused.requests).isEmpty();
        assertThat(store.events).hasSize(eventsBeforeResume);
    }

    @Test
    void traversesDocumentedPhasesAroundToolExecution() {
        var model = new ScriptedModelClient(
                ModelResponse.toolCalls(List.of(new ToolCall("call-1", 0, "echo", "{\"text\":\"hello\"}")), Usage.zero()),
                ModelResponse.finalAnswer("done", Usage.zero()));
        var store = new MemoryCheckpointStore();
        var engine = engine(model, store, new EchoToolRegistry());

        RunResult result = engine.run(request(RunBudget.defaults()));

        assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(store.events).extracting(SessionEvent::type).containsSubsequence(
                EventType.PHASE_ENTERED, EventType.MODEL_RESPONSE,
                EventType.PHASE_ENTERED, EventType.PHASE_ENTERED, EventType.PHASE_ENTERED,
                EventType.TOOL_INTENT, EventType.TOOL_RESULT, EventType.PHASE_ENTERED,
                EventType.SESSION_COMPLETED);
        assertThat(store.events).extracting(SessionEvent::payload)
                .filteredOn(payload -> List.of("PLAN", "VALIDATE", "PRE_TOOL_USE", "EXECUTE", "OBSERVE", "REFLECT")
                        .contains(payload))
                .containsExactly("PLAN", "VALIDATE", "PRE_TOOL_USE", "EXECUTE", "OBSERVE", "PLAN", "REFLECT");
    }

    @Test
    void suspendsInWaitingApprovalWhenHandlerWaits() {
        var tool = new EchoToolRegistry();
        var model = new ScriptedModelClient(
                ModelResponse.toolCalls(List.of(new ToolCall("call-1", 0, "echo", "{\"text\":\"hello\"}")), Usage.zero()));
        var store = new MemoryCheckpointStore();
        var engine = new DefaultAgentEngine(model, tool,
                ToolExecutionGate.compose(null, invocation -> PolicyDecision.ask("needs human"),
                        request -> ApprovalDecision.WAITING),
                store, CLOCK);

        RunResult result = engine.run(request(RunBudget.defaults()));

        assertThat(result.status()).isEqualTo(RunStatus.WAITING_APPROVAL);
        assertThat(result.terminationReason()).isEqualTo(TerminationReason.WAITING_APPROVAL);
        assertThat(tool.invocations).isEmpty();
        assertThat(store.events).extracting(SessionEvent::type)
                .contains(EventType.APPROVAL_REQUESTED, EventType.APPROVAL_PENDING);
        SessionSnapshot snapshot = store.snapshot.orElseThrow();
        assertThat(snapshot.status()).isEqualTo(RunStatus.WAITING_APPROVAL);
        PendingApproval pending = snapshot.checkpoint().pendingApproval();
        assertThat(pending).isNotNull();
        assertThat(pending.approvalId()).isNotBlank();
        assertThat(pending.toolCallId()).isEqualTo("call-1");
        assertThat(pending.toolName()).isEqualTo("echo");
        assertThat(pending.argumentsJson()).isEqualTo("{\"text\":\"hello\"}");
        assertThat(pending.reason()).isEqualTo("needs human");
    }

    @Test
    void resumeOfWaitingSessionWithoutDecisionIsIdempotent() {
        var model = new ScriptedModelClient(
                ModelResponse.toolCalls(List.of(new ToolCall("call-1", 0, "echo", "{\"text\":\"hello\"}")), Usage.zero()));
        var store = new MemoryCheckpointStore();
        var engine = new DefaultAgentEngine(model, new EchoToolRegistry(),
                ToolExecutionGate.compose(null, invocation -> PolicyDecision.ask("needs human"),
                        request -> ApprovalDecision.WAITING),
                store, CLOCK);
        RunResult first = engine.run(request(RunBudget.defaults()));
        int eventsBeforeResume = store.events.size();

        RunResult resumed = engine.resume(first.sessionId());

        assertThat(resumed.status()).isEqualTo(RunStatus.WAITING_APPROVAL);
        assertThat(store.events).hasSize(eventsBeforeResume);
    }

    @Test
    void resumeWithApproveExecutesPendingCallAndContinues() {
        var waitingTool = new EchoToolRegistry();
        var waitingModel = new ScriptedModelClient(
                ModelResponse.toolCalls(List.of(new ToolCall("call-1", 0, "echo", "{\"text\":\"hello\"}")), Usage.zero()));
        var store = new MemoryCheckpointStore();
        var waitingEngine = new DefaultAgentEngine(waitingModel, waitingTool,
                ToolExecutionGate.compose(null, invocation -> PolicyDecision.ask("needs human"),
                        request -> ApprovalDecision.WAITING),
                store, CLOCK);
        RunResult waiting = waitingEngine.run(request(RunBudget.defaults()));
        assertThat(waitingTool.invocations).isEmpty();

        var tool = new EchoToolRegistry();
        var resumedModel = new ScriptedModelClient(ModelResponse.finalAnswer("resumed", Usage.zero()));
        var engine = engine(resumedModel, store, tool);

        RunResult resumed = engine.resume(waiting.sessionId(), ApprovalDecision.APPROVE_ONCE);

        assertThat(resumed.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(resumed.answer()).isEqualTo("resumed");
        assertThat(tool.invocations).containsExactly("{\"text\":\"hello\"}");
        assertThat(store.events).extracting(SessionEvent::type)
                .contains(EventType.APPROVAL_DECIDED, EventType.TOOL_INTENT, EventType.TOOL_RESULT);
        assertThat(store.events).extracting(SessionEvent::type)
                .filteredOn(type -> type == EventType.APPROVAL_PENDING).hasSize(1);
    }

    @Test
    void resumeWithRejectFeedsUserRejectedBackWithoutExecuting() {
        var waitingModel = new ScriptedModelClient(
                ModelResponse.toolCalls(List.of(new ToolCall("call-1", 0, "echo", "{\"text\":\"hello\"}")), Usage.zero()));
        var store = new MemoryCheckpointStore();
        var waitingEngine = new DefaultAgentEngine(waitingModel, new EchoToolRegistry(),
                ToolExecutionGate.compose(null, invocation -> PolicyDecision.ask("needs human"),
                        request -> ApprovalDecision.WAITING),
                store, CLOCK);
        RunResult waiting = waitingEngine.run(request(RunBudget.defaults()));

        var tool = new EchoToolRegistry();
        var resumedModel = new ScriptedModelClient(ModelResponse.finalAnswer("after reject", Usage.zero()));
        var engine = engine(resumedModel, store, tool);

        RunResult resumed = engine.resume(waiting.sessionId(), ApprovalDecision.REJECT);

        assertThat(resumed.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(tool.invocations).isEmpty();
        assertThat(resumedModel.requests).flatExtracting(ChatRequest::messages).extracting(ChatMessage::content)
                .contains("ERROR USER_REJECTED");
    }

    @Test
    void validationFailureSkipsExecutionAndReportsErrorToModel() {
        var tool = new EchoToolRegistry();
        var model = new ScriptedModelClient(
                ModelResponse.toolCalls(List.of(new ToolCall("bad", 0, "echo", "{\"text\":\"bad\"}")), Usage.zero()),
                ModelResponse.finalAnswer("done", Usage.zero()));
        var store = new MemoryCheckpointStore();
        var engine = new DefaultAgentEngine(model, tool, ToolExecutionGate.compose(
                (definition, json) -> json.contains("bad")
                        ? Optional.of("bad arguments rejected")
                        : Optional.empty(),
                invocation -> PolicyDecision.allow("test"), request -> ApprovalDecision.APPROVE_ONCE),
                store, CLOCK);

        RunResult result = engine.run(request(RunBudget.defaults()));

        assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(tool.invocations).isEmpty();
        assertThat(store.events).extracting(SessionEvent::type).contains(EventType.VALIDATION_FAILED);
        assertThat(model.requests).flatExtracting(ChatRequest::messages).extracting(ChatMessage::content)
                .contains("ERROR INVALID_ARGUMENTS: bad arguments rejected");
    }

    @Test
    void sessionMetricsCountPhasesCallsAndFailures() {
        var store = new MemoryCheckpointStore();
        var id = new SessionId("metrics");
        RunRequest request = request(RunBudget.defaults());
        store.events.add(SessionEvent.of(id, 0, EventType.SESSION_STARTED, CLOCK.instant(), "start"));
        store.events.add(SessionEvent.of(id, 1, EventType.PHASE_ENTERED, CLOCK.instant(), "PLAN"));
        store.events.add(SessionEvent.of(id, 2, EventType.MODEL_RESPONSE, CLOCK.instant(), "tool_calls"));
        store.events.add(SessionEvent.of(id, 3, EventType.TOOL_INTENT, CLOCK.instant(), "c1:echo"));
        store.events.add(SessionEvent.of(id, 4, EventType.TOOL_RESULT, CLOCK.instant(), "c1:true"));
        store.events.add(SessionEvent.of(id, 5, EventType.TOOL_INTENT, CLOCK.instant(), "c2:echo"));
        store.events.add(SessionEvent.of(id, 6, EventType.TOOL_RESULT, CLOCK.instant(), "c2:false"));
        store.snapshot = Optional.of(new SessionSnapshot(id, 6, RunStatus.COMPLETED,
                List.of(ChatMessage.system("s"), ChatMessage.user("goal")), new Usage(20, 8, 0.1), CLOCK.instant(),
                RunCheckpoint.from(request, 3, null, 0, CLOCK.instant())));

        SessionMetrics metrics = SessionMetrics.of(store.replay(id), store.latestSnapshot(id));

        assertThat(metrics.iterations()).isEqualTo(3);
        assertThat(metrics.modelCalls()).isEqualTo(1);
        assertThat(metrics.toolCalls()).isEqualTo(2);
        assertThat(metrics.toolFailures()).isEqualTo(1);
        assertThat(metrics.phaseEntries()).containsEntry(AgentPhase.PLAN, 1);
        assertThat(metrics.inputTokens()).isEqualTo(20);
        assertThat(metrics.outputTokens()).isEqualTo(8);
        assertThat(metrics.costCny()).isEqualTo(0.1);
    }

    @Test
    void legacyCheckpointWithoutPhaseDefaultsToPlanAndNullPending() {
        RunCheckpoint checkpoint = RunCheckpoint.from(request(RunBudget.defaults()), 0, null, 0, CLOCK.instant());
        assertThat(checkpoint.effectivePhase()).isEqualTo(AgentPhase.PLAN);
        assertThat(checkpoint.pendingApproval()).isNull();
    }

    private void assertBudgetTermination(Usage usage, RunBudget budget, TerminationReason reason) {
        var model = new ScriptedModelClient(ModelResponse.finalAnswer("not returned", usage));
        RunResult result = engine(model, new MemoryCheckpointStore(), new EchoToolRegistry()).run(request(budget));
        assertThat(result.status()).isEqualTo(RunStatus.BUDGET_EXHAUSTED);
        assertThat(result.terminationReason()).isEqualTo(reason);
    }

    private DefaultAgentEngine engine(ModelClient model, CheckpointStore store, ToolRegistry tools) {
        return new DefaultAgentEngine(model, tools, invocation -> PolicyDecision.allow("test"),
                request -> ApprovalDecision.APPROVE_ONCE, store, CLOCK);
    }

    private RunRequest request(RunBudget budget) {
        return new RunRequest(Path.of("."), "fix the bug", ProviderId.DEEPSEEK, SandboxMode.LOCAL, budget);
    }

    private static final class ScriptedModelClient implements ModelClient {
        private final ArrayDeque<ModelResponse> responses;
        private final List<ChatRequest> requests = new ArrayList<>();

        private ScriptedModelClient(ModelResponse... responses) {
            this.responses = new ArrayDeque<>(List.of(responses));
        }

        @Override
        public ModelResponse exchange(ChatRequest request, ModelDeltaSink sink) {
            requests.add(request);
            return responses.removeFirst();
        }
    }

    private static final class RepeatingModelClient implements ModelClient {
        private final ModelResponse response;
        private int calls;

        private RepeatingModelClient(ModelResponse response) {
            this.response = response;
        }

        @Override
        public ModelResponse exchange(ChatRequest request, ModelDeltaSink sink) {
            calls++;
            return response;
        }
    }

    private static final class EchoToolRegistry implements ToolRegistry {
        private final List<String> invocations = new ArrayList<>();

        @Override
        public Optional<ToolDefinition> find(String name) {
            if (!"echo".equals(name)) {
                return Optional.empty();
            }
            return Optional.of(new ToolDefinition("echo", "echoes text", "{}", RiskLevel.READ, true,
                    (arguments, context) -> {
                        invocations.add(arguments);
                        return ToolResult.success(arguments.contains("hello") ? "hello" : "again");
                    }));
        }

        @Override
        public List<ToolDescriptor> descriptors() {
            return List.of(new ToolDescriptor("echo", "echoes text", "{}"));
        }
    }

    private static final class MemoryCheckpointStore implements CheckpointStore {
        private final List<SessionEvent> events = new ArrayList<>();
        private Optional<SessionSnapshot> snapshot = Optional.empty();

        @Override
        public void append(SessionEvent event) {
            events.add(event);
        }

        @Override
        public List<SessionEvent> replay(SessionId sessionId) {
            return events.stream().filter(event -> event.sessionId().equals(sessionId)).toList();
        }

        @Override
        public void saveSnapshot(SessionSnapshot snapshot) {
            this.snapshot = Optional.of(snapshot);
        }

        @Override
        public Optional<SessionSnapshot> latestSnapshot(SessionId sessionId) {
            return snapshot.filter(value -> value.sessionId().equals(sessionId));
        }
    }
}
