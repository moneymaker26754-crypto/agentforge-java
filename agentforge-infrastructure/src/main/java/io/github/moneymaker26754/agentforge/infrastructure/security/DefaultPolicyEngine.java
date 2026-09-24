package io.github.moneymaker26754.agentforge.infrastructure.security;

import io.github.moneymaker26754.agentforge.core.PolicyDecision;
import io.github.moneymaker26754.agentforge.core.PolicyEngine;
import io.github.moneymaker26754.agentforge.core.RiskLevel;
import io.github.moneymaker26754.agentforge.core.ToolInvocation;

public final class DefaultPolicyEngine implements PolicyEngine {
    private final boolean networkAllowed;

    public DefaultPolicyEngine(boolean networkAllowed) {
        this.networkAllowed = networkAllowed;
    }

    @Override
    public PolicyDecision decide(ToolInvocation invocation) {
        RiskLevel risk = invocation.definition().riskLevel();
        return switch (risk) {
            case READ -> PolicyDecision.allow("read-only workspace operation");
            case WRITE -> PolicyDecision.ask("tool changes workspace files");
            case EXECUTE -> PolicyDecision.ask("tool starts a process");
            case NETWORK -> networkAllowed
                    ? PolicyDecision.ask("tool accesses the network")
                    : PolicyDecision.deny("network access is disabled");
            case DESTRUCTIVE -> PolicyDecision.deny("destructive tools are not available");
        };
    }
}

