package io.github.moneymaker26754.agentforge.server.store;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.moneymaker26754.agentforge.server.domain.AgentTask;
import io.github.moneymaker26754.agentforge.server.domain.PendingApprovalRow;
import io.github.moneymaker26754.agentforge.server.domain.TaskStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteAgentTaskStoreTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-29T00:00:00Z"), ZoneOffset.UTC);

    @TempDir Path tempDir;

    private SqliteAgentTaskStore store() {
        return new SqliteAgentTaskStore(tempDir.resolve("server.db"), CLOCK);
    }

    @Test
    void insertIsIdempotentByRepositoryCommitAndRun() {
        var store = store();
        AgentTask first = AgentTask.create("t1", "owner/repo", "abc123", 42L, "prompt", store.now());
        AgentTask duplicate = AgentTask.create("t2", "owner/repo", "abc123", 42L, "prompt", store.now());

        store.insertIdempotent(first);
        AgentTask second = store.insertIdempotent(duplicate);

        assertThat(second.id()).isEqualTo("t1");
        assertThat(store.list(10)).hasSize(1);
    }

    @Test
    void manualTasksWithNullWorkflowRunIdDoNotCollide() {
        var store = store();
        store.insertIdempotent(AgentTask.create("m1", "owner/repo", "abc", null, "p", store.now()));
        AgentTask second = store.insertIdempotent(AgentTask.create("m2", "owner/repo", "abc", null, "p", store.now()));

        assertThat(second.id()).isEqualTo("m2");
        assertThat(store.list(10)).hasSize(2);
    }

    @Test
    void updatesStatusAndSession() {
        var store = store();
        AgentTask task = store.insertIdempotent(AgentTask.create("t1", "r", "c", 1L, "p", store.now()));

        store.update(task.withSession("s1", TaskStatus.RUNNING, store.now()));

        AgentTask updated = store.findById("t1").orElseThrow();
        assertThat(updated.status()).isEqualTo(TaskStatus.RUNNING);
        assertThat(updated.sessionId()).isEqualTo("s1");
        assertThat(store.findBySessionId("s1")).contains(updated);
    }

    @Test
    void approvalsArePersistedListedAndDecided() {
        var store = store();
        store.saveApproval(new PendingApprovalRow("a1", "s1", "gitCommit", "{}", "confirm", store.now(), null, null));

        assertThat(store.findApproval("a1")).isPresent();
        assertThat(store.findPendingApprovalBySession("s1")).isPresent();
        assertThat(store.listPendingApprovals(10)).hasSize(1);

        store.decideApproval("a1", "APPROVE_ONCE", store.now());

        PendingApprovalRow decided = store.findApproval("a1").orElseThrow();
        assertThat(decided.decision()).isEqualTo("APPROVE_ONCE");
        assertThat(store.listPendingApprovals(10)).isEmpty();
    }

    @Test
    void databaseFileIsCreated() {
        store();
        assertThat(Files.exists(tempDir.resolve("server.db"))).isTrue();
    }
}
