package io.github.moneymaker26754.agentforge.core;

import java.util.Optional;

/**
 * Validates a tool call's raw JSON arguments against the tool's schema before execution.
 *
 * <p>This is the {@code VALIDATE} step of the PreToolUse pipeline. Implementations return the
 * rejection reason when the arguments violate the tool's contract, and {@link Optional#empty()}
 * when they are acceptable. A validator that does not know a tool must return empty rather than
 * fail, so composite registries (for example MCP tools) degrade to policy-only gating.
 */
@FunctionalInterface
public interface ToolArgumentsValidator {
    Optional<String> validate(ToolDefinition definition, String argumentsJson);
}
