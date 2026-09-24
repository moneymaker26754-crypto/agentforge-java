package io.github.moneymaker26754.agentforge.infrastructure.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.moneymaker26754.agentforge.core.ChatMessage;
import io.github.moneymaker26754.agentforge.core.CheckpointStore;
import io.github.moneymaker26754.agentforge.core.EventType;
import io.github.moneymaker26754.agentforge.core.RunStatus;
import io.github.moneymaker26754.agentforge.core.RunCheckpoint;
import io.github.moneymaker26754.agentforge.core.SessionEvent;
import io.github.moneymaker26754.agentforge.core.SessionId;
import io.github.moneymaker26754.agentforge.core.SessionSnapshot;
import io.github.moneymaker26754.agentforge.core.Usage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

public final class SqliteCheckpointStore implements CheckpointStore {
    private final String jdbcUrl;
    private final ObjectMapper mapper;

    public SqliteCheckpointStore(Path database, ObjectMapper mapper) {
        try {
            Path parent = database.toAbsolutePath().normalize().getParent();
            if (parent != null) Files.createDirectories(parent);
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot create checkpoint directory", exception);
        }
        this.jdbcUrl = "jdbc:sqlite:" + database.toAbsolutePath().normalize();
        this.mapper = mapper;
        initialize();
    }

    @Override
    public synchronized void append(SessionEvent event) {
        try (Connection connection = connection()) {
            LastEvent last = lastEvent(connection, event.sessionId());
            long expected = last == null ? 0 : last.sequence() + 1;
            if (event.sequence() != expected) {
                throw new IllegalStateException("expected event sequence " + expected + " but got " + event.sequence());
            }
            String previousHash = last == null ? "" : last.hash();
            String hash = calculateHash(event, previousHash);
            try (var statement = connection.prepareStatement("""
                    insert into session_events(session_id, sequence, type, occurred_at, payload, previous_hash, hash)
                    values (?, ?, ?, ?, ?, ?, ?)
                    """)) {
                statement.setString(1, event.sessionId().value());
                statement.setLong(2, event.sequence());
                statement.setString(3, event.type().name());
                statement.setString(4, event.occurredAt().toString());
                statement.setString(5, event.payload());
                statement.setString(6, previousHash);
                statement.setString(7, hash);
                statement.executeUpdate();
            }
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot append checkpoint event", exception);
        }
    }

    @Override
    public synchronized List<SessionEvent> replay(SessionId sessionId) {
        var events = new ArrayList<SessionEvent>();
        try (Connection connection = connection();
                var statement = connection.prepareStatement("""
                        select sequence, type, occurred_at, payload, previous_hash, hash
                        from session_events where session_id = ? order by sequence
                        """)) {
            statement.setString(1, sessionId.value());
            try (ResultSet rows = statement.executeQuery()) {
                String expectedPrevious = "";
                long expectedSequence = 0;
                while (rows.next()) {
                    var event = new SessionEvent(sessionId, rows.getLong("sequence"),
                            EventType.valueOf(rows.getString("type")), Instant.parse(rows.getString("occurred_at")),
                            rows.getString("payload"), rows.getString("previous_hash"), rows.getString("hash"));
                    String calculated = calculateHash(event, expectedPrevious);
                    if (event.sequence() != expectedSequence || !event.previousHash().equals(expectedPrevious)
                            || !event.hash().equals(calculated)) {
                        throw new AuditIntegrityException("audit chain mismatch at sequence " + event.sequence());
                    }
                    events.add(event);
                    expectedPrevious = event.hash();
                    expectedSequence++;
                }
            }
            return List.copyOf(events);
        } catch (AuditIntegrityException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot replay checkpoint events", exception);
        }
    }

    @Override
    public synchronized void saveSnapshot(SessionSnapshot snapshot) {
        try (Connection connection = connection();
                var statement = connection.prepareStatement("""
                        insert into session_snapshots(session_id, sequence, status, messages_json, usage_json, created_at, checkpoint_json)
                        values (?, ?, ?, ?, ?, ?, ?)
                        on conflict(session_id) do update set sequence=excluded.sequence, status=excluded.status,
                        messages_json=excluded.messages_json, usage_json=excluded.usage_json, created_at=excluded.created_at,
                        checkpoint_json=excluded.checkpoint_json
                        """)) {
            statement.setString(1, snapshot.sessionId().value());
            statement.setLong(2, snapshot.sequence());
            statement.setString(3, snapshot.status().name());
            statement.setString(4, mapper.writeValueAsString(snapshot.messages()));
            statement.setString(5, mapper.writeValueAsString(snapshot.usage()));
            statement.setString(6, snapshot.createdAt().toString());
            statement.setString(7, snapshot.checkpoint() == null ? null : mapper.writeValueAsString(snapshot.checkpoint()));
            statement.executeUpdate();
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot save session snapshot", exception);
        }
    }

    @Override
    public synchronized Optional<SessionSnapshot> latestSnapshot(SessionId sessionId) {
        try (Connection connection = connection();
                var statement = connection.prepareStatement("""
                        select sequence, status, messages_json, usage_json, created_at, checkpoint_json
                        from session_snapshots where session_id = ?
                        """)) {
            statement.setString(1, sessionId.value());
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return Optional.empty();
                List<ChatMessage> messages = mapper.readValue(row.getString("messages_json"), new TypeReference<>() {});
                Usage usage = mapper.readValue(row.getString("usage_json"), Usage.class);
                String checkpointJson = row.getString("checkpoint_json");
                RunCheckpoint checkpoint = checkpointJson == null ? null : mapper.readValue(checkpointJson, RunCheckpoint.class);
                return Optional.of(new SessionSnapshot(sessionId, row.getLong("sequence"),
                        RunStatus.valueOf(row.getString("status")), messages, usage,
                        Instant.parse(row.getString("created_at")), checkpoint));
            }
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot load session snapshot", exception);
        }
    }

    public synchronized List<SessionId> listSessions() {
        var ids = new ArrayList<SessionId>();
        try (Connection connection = connection();
                var statement = connection.prepareStatement("select distinct session_id from session_events order by session_id");
                var rows = statement.executeQuery()) {
            while (rows.next()) ids.add(new SessionId(rows.getString(1)));
            return List.copyOf(ids);
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot list sessions", exception);
        }
    }

    private void initialize() {
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            statement.execute("pragma journal_mode=WAL");
            statement.execute("""
                    create table if not exists session_events(
                      session_id text not null,
                      sequence integer not null,
                      type text not null,
                      occurred_at text not null,
                      payload text not null,
                      previous_hash text not null,
                      hash text not null,
                      primary key(session_id, sequence)
                    )
                    """);
            statement.execute("""
                    create table if not exists session_snapshots(
                      session_id text primary key,
                      sequence integer not null,
                      status text not null,
                      messages_json text not null,
                      usage_json text not null,
                      created_at text not null,
                      checkpoint_json text
                    )
                    """);
            try {
                statement.execute("alter table session_snapshots add column checkpoint_json text");
            } catch (Exception ignored) {
                // Existing databases already containing the column are valid.
            }
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot initialize checkpoint database", exception);
        }
    }

    private Connection connection() throws Exception {
        return DriverManager.getConnection(jdbcUrl);
    }

    private LastEvent lastEvent(Connection connection, SessionId sessionId) throws Exception {
        try (var statement = connection.prepareStatement("""
                select sequence, hash from session_events where session_id = ? order by sequence desc limit 1
                """)) {
            statement.setString(1, sessionId.value());
            try (var row = statement.executeQuery()) {
                return row.next() ? new LastEvent(row.getLong(1), row.getString(2)) : null;
            }
        }
    }

    private String calculateHash(SessionEvent event, String previousHash) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String canonical = previousHash + "\n" + event.sessionId().value() + "\n" + event.sequence() + "\n"
                    + event.type().name() + "\n" + event.occurredAt() + "\n" + event.payload();
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot hash audit event", exception);
        }
    }

    private record LastEvent(long sequence, String hash) {}
}
