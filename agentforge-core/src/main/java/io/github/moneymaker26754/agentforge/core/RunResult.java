package io.github.moneymaker26754.agentforge.core;

public record RunResult(SessionId sessionId, RunStatus status, String answer, Usage usage,
        TerminationReason terminationReason, int iterations) {}

