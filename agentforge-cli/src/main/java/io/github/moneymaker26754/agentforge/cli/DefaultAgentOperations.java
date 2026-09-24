package io.github.moneymaker26754.agentforge.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.moneymaker26754.agentforge.core.*;
import io.github.moneymaker26754.agentforge.infrastructure.model.*;
import io.github.moneymaker26754.agentforge.infrastructure.store.SensitiveDataRedactor;
import io.github.moneymaker26754.agentforge.infrastructure.store.SqliteCheckpointStore;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Scanner;

public final class DefaultAgentOperations implements AgentOperations {
    private final SqliteCheckpointStore store;
    private final ToolRegistry tools;
    private final PolicyEngine policy;
    private final ObjectMapper mapper;
    private final HttpClient http;
    private final SensitiveDataRedactor redactor;

    public DefaultAgentOperations(SqliteCheckpointStore store, ToolRegistry tools, PolicyEngine policy,
            ObjectMapper mapper, HttpClient http) {
        this.store = store;
        this.tools = tools;
        this.policy = policy;
        this.mapper = mapper;
        this.http = http;
        this.redactor = new SensitiveDataRedactor(mapper);
    }

    @Override public RunResult run(Path repository, String task, ProviderId provider, SandboxMode sandbox) {
        var engine = engine(client(provider));
        return engine.run(new RunRequest(repository, task, provider, sandbox, RunBudget.defaults()));
    }

    @Override public RunResult resume(SessionId id) {
        var snapshot = store.latestSnapshot(id)
                .orElseThrow(() -> new IllegalArgumentException("unknown session: " + id));
        if (snapshot.checkpoint() == null) throw new IllegalStateException("session has no resumable metadata: " + id);
        ProviderId provider = ProviderId.valueOf(snapshot.checkpoint().provider());
        return engine(client(provider)).resume(id);
    }

    @Override public List<SessionId> sessions() { return store.listSessions(); }
    @Override public List<SessionEvent> session(SessionId id) { return store.replay(id); }

    @Override public String report(SessionId id, String format) {
        var events = store.replay(id);
        if ("json".equalsIgnoreCase(format)) {
            try { return redactor.redact(mapper.writeValueAsString(events)); }
            catch (Exception exception) { throw new IllegalStateException("cannot render JSON report", exception); }
        }
        var builder = new StringBuilder("# AgentForge Session ").append(id).append("\n\n");
        builder.append("| Seq | Time | Event | Payload |\n|---:|---|---|---|\n");
        events.forEach(event -> builder.append('|').append(event.sequence()).append('|')
                .append(event.occurredAt()).append('|').append(event.type()).append('|')
                .append(redactor.redact(event.payload()).replace("|", "\\|")).append("|\n"));
        return builder.toString();
    }

    private DefaultAgentEngine engine(ModelClient client) {
        return new DefaultAgentEngine(client, tools, policy, this::approve, store, Clock.systemUTC());
    }

    private ApprovalDecision approve(ApprovalRequest request) {
        System.err.printf("Approve %s (%s)? [y/N] ", request.invocation().call().name(), request.reason());
        String answer = new Scanner(System.in).nextLine().trim().toLowerCase(Locale.ROOT);
        return answer.equals("y") || answer.equals("yes") ? ApprovalDecision.APPROVE_ONCE : ApprovalDecision.REJECT;
    }

    private ModelClient client(ProviderId provider) {
        if (provider == ProviderId.OLLAMA) {
            return new OllamaModelClient(http, URI.create(environment("OLLAMA_BASE_URL", "http://localhost:11434")), mapper);
        }
        String key = System.getenv("DEEPSEEK_API_KEY");
        if (key == null || key.isBlank()) throw new IllegalStateException("DEEPSEEK_API_KEY is not set");
        return new DeepSeekModelClient(http, URI.create(environment("DEEPSEEK_BASE_URL", "https://api.deepseek.com")),
                key, mapper, decimal("DEEPSEEK_INPUT_CNY_PER_1K", 0.002),
                decimal("DEEPSEEK_OUTPUT_CNY_PER_1K", 0.008));
    }

    private static String environment(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static double decimal(String name, double fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : Double.parseDouble(value);
    }
}
