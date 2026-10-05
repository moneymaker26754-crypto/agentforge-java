package io.github.moneymaker26754.agentforge.core;

import java.util.Objects;
import java.util.Optional;

/**
 * Unified PreToolUse interception layer every tool call must pass through.
 *
 * <p>The chain is {@code Schema -> Risk -> Policy -> Approval}: {@link #validate(ToolDefinition, String)}
 * checks the arguments, {@link #policy(ToolInvocation)} applies the risk classification and permission
 * policy, and {@link #approve(ApprovalRequest)} asks the human-in-the-loop for high-risk calls. The
 * engine drives the chain and writes the corresponding {@link EventType} audit events, so gate
 * implementations stay side-effect free.
 */
public interface ToolExecutionGate {
    Optional<String> validate(ToolDefinition definition, String argumentsJson);

    PolicyDecision policy(ToolInvocation invocation);

    ApprovalDecision approve(ApprovalRequest request);

    /** Composes the three pipeline stages from their standalone SPIs. */
    static ToolExecutionGate compose(ToolArgumentsValidator validator, PolicyEngine policyEngine,
            ApprovalHandler approvalHandler) {
        Objects.requireNonNull(policyEngine, "policyEngine");
        Objects.requireNonNull(approvalHandler, "approvalHandler");
        return new ToolExecutionGate() {
            @Override
            public Optional<String> validate(ToolDefinition definition, String argumentsJson) {
                return validator == null ? Optional.empty() : validator.validate(definition, argumentsJson);
            }

            @Override
            public PolicyDecision policy(ToolInvocation invocation) {
                return policyEngine.decide(invocation);
            }

            @Override
            public ApprovalDecision approve(ApprovalRequest request) {
                return approvalHandler.decide(request);
            }
        };
    }
}
