package io.github.moneymaker26754.agentforge.core;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Runtime accounting derived from a session's append-only event stream and its latest snapshot.
 *
 * <p>Metrics are computed from the audit trail itself, so no extra bookkeeping state needs to be
 * carried through the loop: tool calls, failures, approvals and phase entries are all countable
 * events. Token usage comes from the snapshot because {@code MODEL_RESPONSE} events only record the
 * finish reason.
 */
public record SessionMetrics(int iterations, int modelCalls, int toolCalls, int toolFailures,
        int validationFailures, int approvalsRequested, int approvalsDecided,
        Map<AgentPhase, Integer> phaseEntries, long inputTokens, long outputTokens, double costCny,
        String startedAt, String endedAt) {

    public static SessionMetrics of(List<SessionEvent> events, Optional<SessionSnapshot> snapshot) {
        int modelCalls = 0;
        int toolCalls = 0;
        int toolFailures = 0;
        int validationFailures = 0;
        int approvalsRequested = 0;
        int approvalsDecided = 0;
        Map<AgentPhase, Integer> phaseEntries = new LinkedHashMap<>();
        for (AgentPhase phase : AgentPhase.values()) {
            phaseEntries.put(phase, 0);
        }
        String startedAt = events.isEmpty() ? "" : events.getFirst().occurredAt().toString();
        String endedAt = events.isEmpty() ? "" : events.getLast().occurredAt().toString();
        for (SessionEvent event : events) {
            switch (event.type()) {
                case MODEL_RESPONSE -> modelCalls++;
                case TOOL_INTENT -> toolCalls++;
                case TOOL_RESULT -> {
                    if (event.payload().endsWith(":false")) {
                        toolFailures++;
                    }
                }
                case VALIDATION_FAILED -> validationFailures++;
                case APPROVAL_REQUESTED -> approvalsRequested++;
                case APPROVAL_DECIDED -> approvalsDecided++;
                case PHASE_ENTERED -> {
                    try {
                        phaseEntries.computeIfPresent(AgentPhase.valueOf(event.payload()),
                                (phase, count) -> count + 1);
                    } catch (IllegalArgumentException ignored) {
                        // A payload from a newer/older version is not a phase we know; skip it.
                    }
                }
                default -> {
                    // Remaining event types do not feed metrics.
                }
            }
        }
        Usage usage = snapshot.map(SessionSnapshot::usage).orElseGet(Usage::zero);
        int iterations = snapshot.map(SessionSnapshot::checkpoint).filter(c -> c != null)
                .map(RunCheckpoint::iterations).orElse(0);
        return new SessionMetrics(iterations, modelCalls, toolCalls, toolFailures, validationFailures,
                approvalsRequested, approvalsDecided, Map.copyOf(phaseEntries), usage.inputTokens(),
                usage.outputTokens(), usage.costCny(), startedAt, endedAt);
    }
}
