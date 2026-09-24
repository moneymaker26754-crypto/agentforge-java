package io.github.moneymaker26754.agentforge.core;

import java.nio.file.Path;

public record ExecutionContext(SessionId sessionId, Path workspace, SandboxMode sandboxMode) {}

