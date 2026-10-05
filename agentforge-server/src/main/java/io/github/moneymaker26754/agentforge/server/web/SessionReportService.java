package io.github.moneymaker26754.agentforge.server.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.moneymaker26754.agentforge.core.CheckpointStore;
import io.github.moneymaker26754.agentforge.core.SessionId;
import io.github.moneymaker26754.agentforge.core.SessionMetrics;
import io.github.moneymaker26754.agentforge.core.SessionSnapshot;
import io.github.moneymaker26754.agentforge.infrastructure.store.SensitiveDataRedactor;
import java.util.List;
import java.util.Optional;

/** Renders session state, metrics and audit reports with sensitive data redacted. */
public final class SessionReportService {
    private final CheckpointStore checkpointStore;
    private final SensitiveDataRedactor redactor;
    private final ObjectMapper mapper;

    public SessionReportService(CheckpointStore checkpointStore, ObjectMapper mapper) {
        this.checkpointStore = checkpointStore;
        this.redactor = new SensitiveDataRedactor(mapper);
        this.mapper = mapper;
    }

    public Optional<SessionSnapshot> snapshot(SessionId sessionId) {
        return checkpointStore.latestSnapshot(sessionId);
    }

    public List<io.github.moneymaker26754.agentforge.core.SessionEvent> events(SessionId sessionId) {
        return checkpointStore.replay(sessionId);
    }

    public SessionMetrics metrics(SessionId sessionId) {
        return SessionMetrics.of(events(sessionId), snapshot(sessionId));
    }

    public String redactedEventsJson(SessionId sessionId) {
        try {
            return redactor.redact(mapper.writeValueAsString(events(sessionId)));
        } catch (Exception exception) {
            throw new IllegalStateException("cannot render events", exception);
        }
    }

    public String report(SessionId sessionId, String format) {
        var events = events(sessionId);
        if ("json".equalsIgnoreCase(format)) {
            try {
                return redactor.redact(mapper.writeValueAsString(events));
            } catch (Exception exception) {
                throw new IllegalStateException("cannot render JSON report", exception);
            }
        }
        var builder = new StringBuilder("# AgentForge Session ").append(sessionId).append("\n\n");
        builder.append("| Seq | Time | Event | Payload |\n|---:|---|---|---|\n");
        events.forEach(event -> builder.append('|').append(event.sequence()).append('|')
                .append(event.occurredAt()).append('|').append(event.type()).append('|')
                .append(redactor.redact(event.payload()).replace("|", "\\|")).append("|\n"));
        return builder.toString();
    }
}
