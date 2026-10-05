package io.github.moneymaker26754.agentforge.infrastructure.github;

import java.util.List;

/**
 * A single commit, as returned by the commits endpoint. changedFiles lists the
 * filenames from the "files" array; it is empty when the API omitted that
 * array (for example when the commit is large).
 */
public record CommitInfo(
        String sha,
        String message,
        String author,
        List<String> changedFiles) {}
