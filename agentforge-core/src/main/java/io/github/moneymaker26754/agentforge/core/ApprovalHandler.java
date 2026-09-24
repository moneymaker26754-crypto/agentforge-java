package io.github.moneymaker26754.agentforge.core;

@FunctionalInterface
public interface ApprovalHandler {
    ApprovalDecision decide(ApprovalRequest request);
}

