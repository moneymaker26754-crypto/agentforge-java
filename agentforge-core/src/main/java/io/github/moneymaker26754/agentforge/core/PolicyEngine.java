package io.github.moneymaker26754.agentforge.core;

@FunctionalInterface
public interface PolicyEngine {
    PolicyDecision decide(ToolInvocation invocation);
}

