package io.github.moneymaker26754.agentforge.infrastructure.github;

/**
 * Thrown when an incoming GitHub webhook payload does not represent a failed CI
 * run, or cannot be parsed at all. Callers treat it as "nothing to do" rather
 * than as an error.
 */
public final class GitHubWebhookIgnoreException extends RuntimeException {
    public GitHubWebhookIgnoreException(String message) {
        super(message);
    }

    public GitHubWebhookIgnoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
