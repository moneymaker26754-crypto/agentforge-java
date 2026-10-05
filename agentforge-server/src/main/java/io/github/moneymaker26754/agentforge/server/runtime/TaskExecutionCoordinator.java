package io.github.moneymaker26754.agentforge.server.runtime;

import io.github.moneymaker26754.agentforge.core.ProviderId;
import io.github.moneymaker26754.agentforge.core.RunBudget;
import io.github.moneymaker26754.agentforge.core.RunRequest;
import io.github.moneymaker26754.agentforge.core.RunResult;
import io.github.moneymaker26754.agentforge.core.RunStatus;
import io.github.moneymaker26754.agentforge.core.SandboxMode;
import io.github.moneymaker26754.agentforge.server.config.ServerProperties;
import io.github.moneymaker26754.agentforge.server.domain.AgentTask;
import io.github.moneymaker26754.agentforge.server.domain.TaskStatus;
import io.github.moneymaker26754.agentforge.server.store.AgentTaskStore;
import jakarta.annotation.PreDestroy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bounded worker pool that turns persisted tasks into agent sessions.
 *
 * <p>No message broker: a fixed thread pool (default 2) enforces the global agent concurrency cap,
 * each task owns exactly one session, and every lifecycle transition is persisted so a crash never
 * loses the task-to-session mapping.
 */
public final class TaskExecutionCoordinator {
    private static final Logger LOGGER = LoggerFactory.getLogger(TaskExecutionCoordinator.class);

    private final AgentTaskStore taskStore;
    private final ServerAgentRuntime runtime;
    private final ServerProperties properties;
    private final Clock clock;
    private final ExecutorService executor;

    public TaskExecutionCoordinator(AgentTaskStore taskStore, ServerAgentRuntime runtime,
            ServerProperties properties, Clock clock) {
        this.taskStore = Objects.requireNonNull(taskStore);
        this.runtime = Objects.requireNonNull(runtime);
        this.properties = Objects.requireNonNull(properties);
        this.clock = Objects.requireNonNull(clock);
        this.executor = Executors.newFixedThreadPool(properties.maxConcurrentTasks());
    }

    public void submit(AgentTask task) {
        executor.submit(() -> execute(task));
    }

    private void execute(AgentTask task) {
        try {
            RunRequest request = request(task);
            taskStore.update(task.withStatus(TaskStatus.RUNNING, now()));
            RunResult result = runtime.run(request);
            taskStore.update(task.withSession(result.sessionId().value(), map(result.status()), now()));
            LOGGER.info("task {} session {} finished with {}", task.id(), result.sessionId(), result.status());
        } catch (Exception exception) {
            LOGGER.error("task {} failed", task.id(), exception);
            taskStore.update(task.withStatus(TaskStatus.FAILED, now()));
        }
    }

    private RunRequest request(AgentTask task) {
        Path repository = resolveRepository(task.repository());
        if (!Files.isDirectory(repository)) {
            throw new IllegalArgumentException("repository is not a directory: " + repository);
        }
        ProviderId provider = ProviderId.valueOf(properties.provider().toUpperCase(Locale.ROOT));
        SandboxMode sandbox = SandboxMode.valueOf(properties.sandboxMode().toUpperCase(Locale.ROOT));
        return new RunRequest(repository, task.prompt(), provider, sandbox, RunBudget.defaults());
    }

    private Path resolveRepository(String repository) {
        if (properties.reposRoot() == null || properties.reposRoot().isBlank()) {
            return Path.of(repository).toAbsolutePath().normalize();
        }
        return Path.of(properties.reposRoot()).resolve(repository).normalize();
    }

    private TaskStatus map(RunStatus status) {
        return switch (status) {
            case COMPLETED -> TaskStatus.COMPLETED;
            case WAITING_APPROVAL -> TaskStatus.WAITING_APPROVAL;
            case BUDGET_EXHAUSTED -> TaskStatus.BUDGET_EXHAUSTED;
            case UNCERTAIN -> TaskStatus.UNCERTAIN;
            default -> TaskStatus.FAILED;
        };
    }

    private String now() {
        return clock.instant().toString();
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}
