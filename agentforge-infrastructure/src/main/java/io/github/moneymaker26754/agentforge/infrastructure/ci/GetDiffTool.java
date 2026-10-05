package io.github.moneymaker26754.agentforge.infrastructure.ci;

import io.github.moneymaker26754.agentforge.core.ExecutionContext;
import io.github.moneymaker26754.agentforge.core.RiskLevel;
import io.github.moneymaker26754.agentforge.core.ToolHandler;
import io.github.moneymaker26754.agentforge.core.ToolResult;
import io.github.moneymaker26754.agentforge.infrastructure.github.GitHubApiClient;
import io.github.moneymaker26754.agentforge.infrastructure.tool.AgentTool;
import io.github.moneymaker26754.agentforge.infrastructure.tool.ToolParam;

@AgentTool(name = "getDiff",
        description = "Fetch the diff between two refs of a repository for CI failure diagnosis",
        risk = RiskLevel.READ, idempotent = true)
public final class GetDiffTool implements ToolHandler<GetDiffTool.Arguments> {
    private final GitHubApiClient client;

    public GetDiffTool(GitHubApiClient client) {
        this.client = client;
    }

    public record Arguments(
            @ToolParam(description = "repository in owner/name form", required = true) String repository,
            @ToolParam(description = "base ref to diff from", required = true) String baseRef,
            @ToolParam(description = "head ref to diff to", required = true) String headRef) {}

    @Override
    public ToolResult execute(Arguments arguments, ExecutionContext context) {
        try {
            return ToolResult.success(client.getDiff(arguments.repository(), arguments.baseRef(), arguments.headRef()));
        } catch (Exception exception) {
            return ToolResult.failure("GITHUB_API_ERROR", CiTools.errorMessage(exception));
        }
    }
}
