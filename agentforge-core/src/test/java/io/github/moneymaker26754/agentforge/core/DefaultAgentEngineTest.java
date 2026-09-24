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
                .containsExactly(EventType.SESSION_STARTED, EventType.MODEL_RESPONSE, EventType.SESSION_COMPLETED);
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
