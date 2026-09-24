package io.github.moneymaker26754.agentforge.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.moneymaker26754.agentforge.core.ExecutionContext;
import io.github.moneymaker26754.agentforge.core.PolicyOutcome;
import io.github.moneymaker26754.agentforge.core.RiskLevel;
import io.github.moneymaker26754.agentforge.core.SandboxMode;
import io.github.moneymaker26754.agentforge.core.SessionId;
import io.github.moneymaker26754.agentforge.core.ToolCall;
import io.github.moneymaker26754.agentforge.core.ToolDefinition;
import io.github.moneymaker26754.agentforge.core.ToolInvocation;
import io.github.moneymaker26754.agentforge.core.ToolResult;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class DefaultPolicyEngineTest {

    @Test
    void allowsReadsAsksForWritesAndExecutionAndDeniesDestructiveCalls() {
        var policy = new DefaultPolicyEngine(false);

        assertThat(policy.decide(invocation(RiskLevel.READ)).outcome()).isEqualTo(PolicyOutcome.ALLOW);
        assertThat(policy.decide(invocation(RiskLevel.WRITE)).outcome()).isEqualTo(PolicyOutcome.ASK);
        assertThat(policy.decide(invocation(RiskLevel.EXECUTE)).outcome()).isEqualTo(PolicyOutcome.ASK);
        assertThat(policy.decide(invocation(RiskLevel.NETWORK)).outcome()).isEqualTo(PolicyOutcome.DENY);
        assertThat(policy.decide(invocation(RiskLevel.DESTRUCTIVE)).outcome()).isEqualTo(PolicyOutcome.DENY);
    }

    private ToolInvocation invocation(RiskLevel risk) {
        var definition = new ToolDefinition("tool", "description", "{}", risk, risk == RiskLevel.READ,
                (json, context) -> ToolResult.success("ok"));
        var context = new ExecutionContext(new SessionId("s"), Path.of("."), SandboxMode.LOCAL);
        return new ToolInvocation(new ToolCall("c", 0, "tool", "{}"), definition, context);
    }
}

