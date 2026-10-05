package io.github.moneymaker26754.agentforge.infrastructure.github;

import java.util.List;
import java.util.Objects;

/**
 * Evidence record for a single CI failure, consumed by {@link CiContextAssembler}.
 *
 * <p>All components are nullable; the compact constructor normalizes every null
 * String to {@code ""} and every null list to an immutable empty list (list elements
 * that are null are dropped). {@code workflowRunId} uses {@link Long} so that
 * "unknown" can be represented as {@code null}; callers that only have the
 * primitive {@code long} convention should pass {@code null} instead of 0.
 *
 * <p>The record is a pure data carrier: no IO, no dependencies beyond {@code java.base}.
 */
public record CiFailureContext(
        String repository,
        String commitSha,
        Long workflowRunId,
        String workflowName,
        List<String> failedJobNames,
        String jobLogExcerpt,
        String testOutput,
        String stackTrace,
        List<String> changedFiles,
        String diffExcerpt,
        List<String> relatedFiles,
        String buildCommand) {

    public CiFailureContext {
        repository = repository == null ? "" : repository;
        commitSha = commitSha == null ? "" : commitSha;
        workflowName = workflowName == null ? "" : workflowName;
        failedJobNames = normalizeList(failedJobNames);
        jobLogExcerpt = jobLogExcerpt == null ? "" : jobLogExcerpt;
        testOutput = testOutput == null ? "" : testOutput;
        stackTrace = stackTrace == null ? "" : stackTrace;
        changedFiles = normalizeList(changedFiles);
        diffExcerpt = diffExcerpt == null ? "" : diffExcerpt;
        relatedFiles = normalizeList(relatedFiles);
        buildCommand = buildCommand == null ? "" : buildCommand;
    }

    private static List<String> normalizeList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream().filter(Objects::nonNull).toList();
    }
}
