package io.github.moneymaker26754.agentforge.server.runtime;

import io.github.moneymaker26754.agentforge.core.AgentEngine;
import io.github.moneymaker26754.agentforge.core.ApprovalDecision;
import io.github.moneymaker26754.agentforge.core.CheckpointStore;
import io.github.moneymaker26754.agentforge.core.DefaultAgentEngine;
import io.github.moneymaker26754.agentforge.core.ModelClient;
import io.github.moneymaker26754.agentforge.core.RunRequest;
import io.github.moneymaker26754.agentforge.core.RunResult;
import io.github.moneymaker26754.agentforge.core.SessionId;
import io.github.moneymaker26754.agentforge.core.ToolExecutionGate;
import io.github.moneymaker26754.agentforge.core.ToolRegistry;
import java.time.Clock;
import java.util.Objects;

/** Builds a fresh {@link AgentEngine} per invocation so no run state is shared between sessions. */
public final class ServerAgentRuntime {
    private final ModelClient modelClient;
    private final ToolRegistry toolRegistry;
    private final ToolExecutionGate gate;
    private final CheckpointStore checkpointStore;
    private final Clock clock;

    public ServerAgentRuntime(ModelClient modelClient, ToolRegistry toolRegistry, ToolExecutionGate gate,
            CheckpointStore checkpointStore, Clock clock) {
        this.modelClient = Objects.requireNonNull(modelClient);
        this.toolRegistry = Objects.requireNonNull(toolRegistry);
        this.gate = Objects.requireNonNull(gate);
        this.checkpointStore = Objects.requireNonNull(checkpointStore);
        this.clock = Objects.requireNonNull(clock);
    }

    public RunResult run(RunRequest request) {
        return engine().run(request);
    }

    public RunResult resume(SessionId sessionId) {
        return engine().resume(sessionId);
    }

    public RunResult resume(SessionId sessionId, ApprovalDecision decision) {
        return engine().resume(sessionId, decision);
    }

    private DefaultAgentEngine engine() {
        return new DefaultAgentEngine(modelClient, toolRegistry, gate, checkpointStore, clock);
    }
}
