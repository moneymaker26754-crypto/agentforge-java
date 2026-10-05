package io.github.moneymaker26754.agentforge.core;

/**
 * Explicit phases of the agent state machine.
 *
 * <p>The loop follows {@code PLAN -> VALIDATE -> PRE_TOOL_USE -> EXECUTE -> OBSERVE -> REFLECT}:
 * {@code PLAN} performs budget checks, context preparation and the model exchange; {@code VALIDATE}
 * checks tool existence, argument schemas and repetition guards; {@code PRE_TOOL_USE} runs the
 * unified risk/policy/approval gate; {@code EXECUTE} records the tool intent and runs the executor;
 * {@code OBSERVE} records the result and feeds it back to the model; {@code REFLECT} decides whether
 * to continue, finish or wait for human approval. Each phase entry is audited as a
 * {@link EventType#PHASE_ENTERED} event.
 */
public enum AgentPhase {
    PLAN,
    VALIDATE,
    PRE_TOOL_USE,
    EXECUTE,
    OBSERVE,
    REFLECT
}
