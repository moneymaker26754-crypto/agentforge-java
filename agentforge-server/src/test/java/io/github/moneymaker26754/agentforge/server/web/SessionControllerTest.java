package io.github.moneymaker26754.agentforge.server.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.moneymaker26754.agentforge.core.AgentPhase;
import io.github.moneymaker26754.agentforge.core.CheckpointStore;
import io.github.moneymaker26754.agentforge.core.EventType;
import io.github.moneymaker26754.agentforge.core.ProviderId;
import io.github.moneymaker26754.agentforge.core.RunBudget;
import io.github.moneymaker26754.agentforge.core.RunCheckpoint;
import io.github.moneymaker26754.agentforge.core.RunRequest;
import io.github.moneymaker26754.agentforge.core.RunStatus;
import io.github.moneymaker26754.agentforge.core.SandboxMode;
import io.github.moneymaker26754.agentforge.core.SessionEvent;
import io.github.moneymaker26754.agentforge.core.SessionId;
import io.github.moneymaker26754.agentforge.core.SessionSnapshot;
import io.github.moneymaker26754.agentforge.core.Usage;
import io.github.moneymaker26754.agentforge.core.ChatMessage;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Drives the real {@link SessionController} against an in-memory checkpoint store. */
class SessionControllerTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-29T00:00:00Z"), ZoneOffset.UTC);

    private final MemoryStore store = new MemoryStore();
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(
            new SessionController(new SessionReportService(store, mapper()), mapper())).build();

    private static ObjectMapper mapper() {
        return new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @BeforeEach
    void seed() {
        SessionId id = new SessionId("s1");
        store.events.add(SessionEvent.of(id, 0, EventType.SESSION_STARTED, CLOCK.instant(), "start"));
        store.events.add(SessionEvent.of(id, 1, EventType.PHASE_ENTERED, CLOCK.instant(), "PLAN"));
        store.events.add(SessionEvent.of(id, 2, EventType.MODEL_RESPONSE, CLOCK.instant(), "stop"));
        store.events.add(SessionEvent.of(id, 3, EventType.TOOL_INTENT, CLOCK.instant(), "c1:fs_read"));
        store.events.add(SessionEvent.of(id, 4, EventType.TOOL_RESULT, CLOCK.instant(), "c1:true"));
        store.snapshot = Optional.of(new SessionSnapshot(id, 4, RunStatus.COMPLETED,
                List.of(ChatMessage.system("s"), ChatMessage.user("t")), new Usage(12, 4, 0.01), CLOCK.instant(),
                RunCheckpoint.from(new RunRequest(Path.of("."), "task", ProviderId.DEEPSEEK, SandboxMode.LOCAL,
                        RunBudget.defaults()), 1, null, 0, CLOCK.instant(), AgentPhase.REFLECT, null)));
    }

    @Test
    void returnsStatusAndMetrics() throws Exception {
        mvc.perform(get("/api/v1/sessions/s1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value("s1"))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.metrics.toolCalls").value(1))
                .andExpect(jsonPath("$.metrics.modelCalls").value(1))
                .andExpect(jsonPath("$.metrics.phaseEntries.PLAN").value(1));
    }

    @Test
    void unknownSessionIsNotFound() throws Exception {
        mvc.perform(get("/api/v1/sessions/missing")).andExpect(status().isNotFound());
    }

    @Test
    void returnsEventsAsJsonArray() throws Exception {
        mvc.perform(get("/api/v1/sessions/s1/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("SESSION_STARTED"))
                .andExpect(jsonPath("$[3].type").value("TOOL_INTENT"));
    }

    @Test
    void rendersMarkdownReport() throws Exception {
        mvc.perform(get("/api/v1/sessions/s1/report"))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("# AgentForge Session s1")));
    }

    static final class MemoryStore implements CheckpointStore {
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
}
