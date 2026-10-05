package io.github.moneymaker26754.agentforge.core;

/**
 * A tool call that was suspended in {@code PRE_TOOL_USE} waiting for a human decision.
 *
 * <p>Stored inside the session checkpoint so a restarted process can resume the session with
 * {@code AgentEngine.resume(sessionId, decision)} without losing which call, arguments and policy
 * reason the approval was about. The {@code approvalId} is the idempotency key the control plane
 * uses to submit the decision.
 */
public record PendingApproval(String approvalId, String toolCallId, String toolName, String argumentsJson,
        String reason) {}
