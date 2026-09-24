package io.github.moneymaker26754.agentforge.core;

public enum TerminationReason {
    FINAL_ANSWER,
    MAX_ITERATIONS,
    WALL_TIME,
    INPUT_TOKEN_BUDGET,
    OUTPUT_TOKEN_BUDGET,
    COST_BUDGET,
    REPEATED_TOOL_CALL,
    USER_CANCELLED,
    POLICY_DENIED,
    UNRECOVERABLE_ERROR,
    UNCERTAIN_TOOL
}

