package io.github.moneymaker26754.agentforge.infrastructure.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.moneymaker26754.agentforge.core.ChatMessage;
import io.github.moneymaker26754.agentforge.core.EventType;
import io.github.moneymaker26754.agentforge.core.RunStatus;
import io.github.moneymaker26754.agentforge.core.RunCheckpoint;
import io.github.moneymaker26754.agentforge.core.SessionEvent;
import io.github.moneymaker26754.agentforge.core.SessionId;
import io.github.moneymaker26754.agentforge.core.SessionSnapshot;
import io.github.moneymaker26754.agentforge.core.Usage;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteCheckpointStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void appendsAndVerifiesLinkedEventsInSequence() {
        var store = store();
        var id = new SessionId("session-1");
        store.append(SessionEvent.of(id, 0, EventType.SESSION_STARTED, Instant.parse("2026-01-01T00:00:00Z"), "start"));
        store.append(SessionEvent.of(id, 1, EventType.MODEL_RESPONSE, Instant.parse("2026-01-01T00:00:01Z"), "answer"));

        List<SessionEvent> events = store.replay(id);

        assertThat(events).hasSize(2);
        assertThat(events.get(0).hash()).hasSize(64);
        assertThat(events.get(1).previousHash()).isEqualTo(events.get(0).hash());
        assertThat(events).extracting(SessionEvent::sequence).containsExactly(0L, 1L);
    }

    @Test
    void detectsPayloadTamperingDuringReplay() throws Exception {
        Path database = tempDir.resolve("tamper.db");
        var store = new SqliteCheckpointStore(database, new ObjectMapper());
        var id = new SessionId("session-2");
        store.append(SessionEvent.of(id, 0, EventType.SESSION_STARTED, Instant.parse("2026-01-01T00:00:00Z"), "safe"));
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                var statement = connection.prepareStatement("update session_events set payload = 'changed' where session_id = ?")) {
            statement.setString(1, id.value());
            statement.executeUpdate();
        }

        assertThatThrownBy(() -> store.replay(id))
                .isInstanceOf(AuditIntegrityException.class)
                .hasMessageContaining("sequence 0");
    }

    @Test
    void savesAndRestoresLatestSnapshot() {
        var store = store();
        var id = new SessionId("session-3");
        var checkpoint = new RunCheckpoint(tempDir.toString(), "fix", "DEEPSEEK", "DOCKER", 30, 1_200_000,
                200_000, 30_000, 50, 3, 4, "fingerprint", 1, "2026-01-01T00:00:00Z");
        var snapshot = new SessionSnapshot(id, 25, RunStatus.WAITING_APPROVAL,
                List.of(ChatMessage.system("system"), ChatMessage.user("goal")),
                new Usage(10, 3, 0.2), Instant.parse("2026-01-01T00:00:00Z"), checkpoint);

        store.saveSnapshot(snapshot);

        assertThat(store.latestSnapshot(id)).contains(snapshot);
    }

    private SqliteCheckpointStore store() {
        return new SqliteCheckpointStore(tempDir.resolve("state.db"), new ObjectMapper());
    }
}
