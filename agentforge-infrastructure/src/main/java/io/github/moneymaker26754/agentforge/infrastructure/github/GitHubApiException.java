package io.github.moneymaker26754.agentforge.infrastructure.github;

/** Thrown when the GitHub REST API answers with a non-2xx status code. */
public final class GitHubApiException extends RuntimeException {
    private final int status;

    public GitHubApiException(int status, String message) {
        super(message);
        this.status = status;
    }

    public GitHubApiException(int status, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
