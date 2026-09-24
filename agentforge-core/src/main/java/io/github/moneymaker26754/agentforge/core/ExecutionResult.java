package io.github.moneymaker26754.agentforge.core;

public record ExecutionResult(int exitCode, String stdout, String stderr, boolean timedOut, long durationMillis) {}

