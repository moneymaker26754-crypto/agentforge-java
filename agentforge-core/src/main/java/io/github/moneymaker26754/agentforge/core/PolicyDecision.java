package io.github.moneymaker26754.agentforge.core;

public record PolicyDecision(PolicyOutcome outcome, String reason) {
    public static PolicyDecision allow(String reason) {
        return new PolicyDecision(PolicyOutcome.ALLOW, reason);
    }

    public static PolicyDecision ask(String reason) {
        return new PolicyDecision(PolicyOutcome.ASK, reason);
    }

    public static PolicyDecision deny(String reason) {
        return new PolicyDecision(PolicyOutcome.DENY, reason);
    }
}

