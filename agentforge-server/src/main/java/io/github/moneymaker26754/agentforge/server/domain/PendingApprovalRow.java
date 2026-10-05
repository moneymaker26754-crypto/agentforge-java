package io.github.moneymaker26754.agentforge.server.domain;

/** A human approval decision stored while a session is suspended in {@code WAITING_APPROVAL}. */
public record PendingApprovalRow(String approvalId, String sessionId, String toolName, String argumentsJson,
        String reason, String createdAt, String decision, String decidedAt) {}
