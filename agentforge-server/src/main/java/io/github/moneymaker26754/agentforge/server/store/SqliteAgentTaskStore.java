package io.github.moneymaker26754.agentforge.server.store;

import io.github.moneymaker26754.agentforge.server.domain.AgentTask;
import io.github.moneymaker26754.agentforge.server.domain.PendingApprovalRow;
import io.github.moneymaker26754.agentforge.server.domain.TaskStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * SQLite-backed task persistence sharing the control plane's database.
 *
 * <p>Schema changes are additive only: existing databases keep their session event stream and
 * simply gain the {@code agent_tasks} and {@code pending_approvals} tables.
 */
public final class SqliteAgentTaskStore implements AgentTaskStore {
    private final String jdbcUrl;
    private final Clock clock;

    public SqliteAgentTaskStore(Path database, Clock clock) {
        try {
            Path parent = database.toAbsolutePath().normalize().getParent();
            if (parent != null) Files.createDirectories(parent);
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot create task store directory", exception);
        }
        this.jdbcUrl = "jdbc:sqlite:" + database.toAbsolutePath().normalize();
        this.clock = clock;
        initialize();
    }

    @Override
    public synchronized AgentTask insertIdempotent(AgentTask task) {
        try (Connection connection = connection()) {
            // Manual tasks carry no workflow run id and are never deduplicated; only webhook tasks
            // (repository + commit + run) are idempotent.
            if (task.workflowRunId() != null) {
                var existing = findByKey(connection, task.repository(), task.commitSha(), task.workflowRunId());
                if (existing.isPresent()) {
                    return existing.get();
                }
            }
            try (var statement = connection.prepareStatement("""
                    insert into agent_tasks(id, repository, commit_sha, workflow_run_id, status, session_id, prompt,
                                            created_at, updated_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                statement.setString(1, task.id());
                statement.setString(2, task.repository());
                statement.setString(3, task.commitSha());
                if (task.workflowRunId() == null) {
                    statement.setNull(4, java.sql.Types.BIGINT);
                } else {
                    statement.setLong(4, task.workflowRunId());
                }
                statement.setString(5, task.status().name());
                statement.setString(6, task.sessionId());
                statement.setString(7, task.prompt());
                statement.setString(8, task.createdAt());
                statement.setString(9, task.updatedAt());
                statement.executeUpdate();
            }
            return task;
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot insert agent task", exception);
        }
    }

    @Override
    public synchronized Optional<AgentTask> findByKey(String repository, String commitSha, Long workflowRunId) {
        try (Connection connection = connection()) {
            return findByKey(connection, repository, commitSha, workflowRunId);
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot find agent task by key", exception);
        }
    }

    @Override
    public synchronized Optional<AgentTask> findById(String taskId) {
        try (Connection connection = connection();
                var statement = connection.prepareStatement("""
                        select id, repository, commit_sha, workflow_run_id, status, session_id, prompt,
                               created_at, updated_at from agent_tasks where id = ?
                        """)) {
            statement.setString(1, taskId);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? Optional.of(map(row)) : Optional.empty();
            }
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot load agent task", exception);
        }
    }

    @Override
    public synchronized Optional<AgentTask> findBySessionId(String sessionId) {
        try (Connection connection = connection();
                var statement = connection.prepareStatement("""
                        select id, repository, commit_sha, workflow_run_id, status, session_id, prompt,
                               created_at, updated_at from agent_tasks where session_id = ?
                        """)) {
            statement.setString(1, sessionId);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? Optional.of(map(row)) : Optional.empty();
            }
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot load task by session", exception);
        }
    }

    @Override
    public synchronized List<AgentTask> list(int limit) {
        var tasks = new ArrayList<AgentTask>();
        try (Connection connection = connection();
                var statement = connection.prepareStatement("""
                        select id, repository, commit_sha, workflow_run_id, status, session_id, prompt,
                               created_at, updated_at from agent_tasks order by created_at desc, id desc limit ?
                        """)) {
            statement.setInt(1, Math.max(1, limit));
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) tasks.add(map(rows));
            }
            return List.copyOf(tasks);
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot list agent tasks", exception);
        }
    }

    @Override
    public synchronized void update(AgentTask task) {
        try (Connection connection = connection();
                var statement = connection.prepareStatement("""
                        update agent_tasks set status = ?, session_id = ?, prompt = ?, updated_at = ? where id = ?
                        """)) {
            statement.setString(1, task.status().name());
            statement.setString(2, task.sessionId());
            statement.setString(3, task.prompt());
            statement.setString(4, task.updatedAt());
            statement.setString(5, task.id());
            statement.executeUpdate();
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot update agent task", exception);
        }
    }

    @Override
    public synchronized void saveApproval(PendingApprovalRow row) {
        try (Connection connection = connection();
                var statement = connection.prepareStatement("""
                        insert into pending_approvals(approval_id, session_id, tool_name, arguments_json, reason,
                                                      created_at, decision, decided_at)
                        values (?, ?, ?, ?, ?, ?, ?, ?)
                        """)) {
            statement.setString(1, row.approvalId());
            statement.setString(2, row.sessionId());
            statement.setString(3, row.toolName());
            statement.setString(4, row.argumentsJson());
            statement.setString(5, row.reason());
            statement.setString(6, row.createdAt());
            statement.setString(7, row.decision());
            statement.setString(8, row.decidedAt());
            statement.executeUpdate();
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot save pending approval", exception);
        }
    }

    @Override
    public synchronized Optional<PendingApprovalRow> findApproval(String approvalId) {
        try (Connection connection = connection();
                var statement = connection.prepareStatement("""
                        select approval_id, session_id, tool_name, arguments_json, reason, created_at,
                               decision, decided_at from pending_approvals where approval_id = ?
                        """)) {
            statement.setString(1, approvalId);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? Optional.of(mapApproval(row)) : Optional.empty();
            }
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot load pending approval", exception);
        }
    }

    @Override
    public synchronized void decideApproval(String approvalId, String decision, String decidedAt) {
        try (Connection connection = connection();
                var statement = connection.prepareStatement("""
                        update pending_approvals set decision = ?, decided_at = ? where approval_id = ?
                        """)) {
            statement.setString(1, decision);
            statement.setString(2, decidedAt);
            statement.setString(3, approvalId);
            statement.executeUpdate();
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot decide pending approval", exception);
        }
    }

    @Override
    public synchronized Optional<PendingApprovalRow> findPendingApprovalBySession(String sessionId) {
        try (Connection connection = connection();
                var statement = connection.prepareStatement("""
                        select approval_id, session_id, tool_name, arguments_json, reason, created_at,
                               decision, decided_at from pending_approvals
                        where session_id = ? and decision is null order by created_at desc limit 1
                        """)) {
            statement.setString(1, sessionId);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? Optional.of(mapApproval(row)) : Optional.empty();
            }
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot load session approval", exception);
        }
    }

    @Override
    public synchronized List<PendingApprovalRow> listPendingApprovals(int limit) {
        var rows = new ArrayList<PendingApprovalRow>();
        try (Connection connection = connection();
                var statement = connection.prepareStatement("""
                        select approval_id, session_id, tool_name, arguments_json, reason, created_at,
                               decision, decided_at from pending_approvals where decision is null
                        order by created_at desc limit ?
                        """)) {
            statement.setInt(1, Math.max(1, limit));
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) rows.add(mapApproval(result));
            }
            return List.copyOf(rows);
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot list pending approvals", exception);
        }
    }

    private Optional<AgentTask> findByKey(Connection connection, String repository, String commitSha,
            Long workflowRunId) throws Exception {
        try (var statement = connection.prepareStatement("""
                select id, repository, commit_sha, workflow_run_id, status, session_id, prompt,
                       created_at, updated_at from agent_tasks
                where repository = ? and commit_sha = ? and workflow_run_id is ?
                """)) {
            statement.setString(1, repository);
            statement.setString(2, commitSha);
            if (workflowRunId == null) {
                statement.setNull(3, java.sql.Types.BIGINT);
            } else {
                statement.setLong(3, workflowRunId);
            }
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? Optional.of(map(row)) : Optional.empty();
            }
        }
    }

    private AgentTask map(ResultSet row) throws Exception {
        long runId = row.getLong("workflow_run_id");
        Long workflowRunId = row.wasNull() ? null : runId;
        return new AgentTask(row.getString("id"), row.getString("repository"), row.getString("commit_sha"),
                workflowRunId, TaskStatus.valueOf(row.getString("status")), row.getString("session_id"),
                row.getString("prompt"), row.getString("created_at"), row.getString("updated_at"));
    }

    private PendingApprovalRow mapApproval(ResultSet row) throws Exception {
        return new PendingApprovalRow(row.getString("approval_id"), row.getString("session_id"),
                row.getString("tool_name"), row.getString("arguments_json"), row.getString("reason"),
                row.getString("created_at"), row.getString("decision"), row.getString("decided_at"));
    }

    private void initialize() {
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            statement.execute("pragma journal_mode=WAL");
            statement.execute("""
                    create table if not exists agent_tasks(
                      id text primary key,
                      repository text not null,
                      commit_sha text not null default '',
                      workflow_run_id integer,
                      status text not null,
                      session_id text,
                      prompt text not null default '',
                      created_at text not null,
                      updated_at text not null,
                      unique(repository, commit_sha, workflow_run_id)
                    )
                    """);
            statement.execute("""
                    create table if not exists pending_approvals(
                      approval_id text primary key,
                      session_id text not null,
                      tool_name text not null,
                      arguments_json text not null default '',
                      reason text not null default '',
                      created_at text not null,
                      decision text,
                      decided_at text
                    )
                    """);
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot initialize task database", exception);
        }
    }

    private Connection connection() throws Exception {
        return DriverManager.getConnection(jdbcUrl);
    }

    /** Current timestamp for new rows; exposed for tests that need deterministic storage. */
    public String now() {
        return clock.instant().toString();
    }
}
