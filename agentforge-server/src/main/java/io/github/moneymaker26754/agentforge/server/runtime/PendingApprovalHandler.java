package io.github.moneymaker26754.agentforge.server.runtime;

import io.github.moneymaker26754.agentforge.core.ApprovalDecision;
import io.github.moneymaker26754.agentforge.core.ApprovalHandler;
import io.github.moneymaker26754.agentforge.core.ApprovalRequest;
import io.github.moneymaker26754.agentforge.server.domain.PendingApprovalRow;
import io.github.moneymaker26754.agentforge.server.store.AgentTaskStore;
import java.time.Clock;
import java.util.Objects;

/**
 * Approval handler for the control plane: instead of blocking on a console prompt, a high-risk tool
 * call is persisted as a {@code pending_approvals} row and the session suspends in
 * {@code WAITING_APPROVAL} until a decision arrives through the approval API.
 */
public final class PendingApprovalHandler implements ApprovalHandler {
    private final AgentTaskStore store;
    private final Clock clock;

    public PendingApprovalHandler(AgentTaskStore store, Clock clock) {
        this.store = Objects.requireNonNull(store);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public ApprovalDecision decide(ApprovalRequest request) {
        store.saveApproval(new PendingApprovalRow(request.approvalId(),
                request.invocation().context().sessionId().value(), request.invocation().call().name(),
                request.invocation().call().argumentsJson(), request.reason(), clock.instant().toString(), null,
                null));
        return ApprovalDecision.WAITING;
    }
}
