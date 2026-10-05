package io.github.moneymaker26754.agentforge.infrastructure.ci;

import io.github.moneymaker26754.agentforge.core.ExecutionContext;
import io.github.moneymaker26754.agentforge.core.RiskLevel;
import io.github.moneymaker26754.agentforge.core.ToolHandler;
import io.github.moneymaker26754.agentforge.core.ToolResult;
import io.github.moneymaker26754.agentforge.infrastructure.github.GitHubApiClient;
import io.github.moneymaker26754.agentforge.infrastructure.tool.AgentTool;
import io.github.moneymaker26754.agentforge.infrastructure.tool.ToolParam;

@AgentTool(name = "getJobLogs",
        description = "Fetch the log output of a GitHub Actions job for CI failure diagnosis",
        risk = RiskLevel.READ, idempotent = true)
public final class GetJobLogsTool implements ToolHandler<GetJobLogsTool.Arguments> {
    private static final int DEFAULT_MAX_CHARS = 40_000;
    private static final int MIN_MAX_CHARS = 1_000;
    private static final int MAX_MAX_CHARS = 200_000;

    private final GitHubApiClient client;

    public GetJobLogsTool(GitHubApiClient client) {
        this.client = client;
    }

    public record Arguments(
            @ToolParam(description = "repository in owner/name form", required = true) String repository,
            @ToolParam(description = "GitHub Actions job id", required = true) long jobId,
            @ToolParam(description = "maximum number of log characters to return", min = 1000, max = 200000)
            int maxChars) {}

    @Override
    public ToolResult execute(Arguments arguments, ExecutionContext context) {
        try {
            int limit = arguments.maxChars() <= 0 ? DEFAULT_MAX_CHARS
                    : Math.max(MIN_MAX_CHARS, Math.min(MAX_MAX_CHARS, arguments.maxChars()));
            return ToolResult.success(client.getJobLogs(arguments.repository(), arguments.jobId(), limit));
        } catch (Exception exception) {
            return ToolResult.failure("GITHUB_API_ERROR", CiTools.errorMessage(exception));
        }
    }
}
