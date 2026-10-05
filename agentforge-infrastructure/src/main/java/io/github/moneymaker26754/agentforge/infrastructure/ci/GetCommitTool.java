package io.github.moneymaker26754.agentforge.infrastructure.ci;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.moneymaker26754.agentforge.core.ExecutionContext;
import io.github.moneymaker26754.agentforge.core.RiskLevel;
import io.github.moneymaker26754.agentforge.core.ToolHandler;
import io.github.moneymaker26754.agentforge.core.ToolResult;
import io.github.moneymaker26754.agentforge.infrastructure.github.GitHubApiClient;
import io.github.moneymaker26754.agentforge.infrastructure.tool.AgentTool;
import io.github.moneymaker26754.agentforge.infrastructure.tool.ToolParam;

@AgentTool(name = "getCommit",
        description = "Fetch commit metadata (message, author, changed files) for CI failure diagnosis",
        risk = RiskLevel.READ, idempotent = true)
public final class GetCommitTool implements ToolHandler<GetCommitTool.Arguments> {
    private final GitHubApiClient client;
    private final ObjectMapper mapper;

    public GetCommitTool(GitHubApiClient client, ObjectMapper mapper) {
        this.client = client;
        this.mapper = mapper;
    }

    public record Arguments(
            @ToolParam(description = "repository in owner/name form", required = true) String repository,
            @ToolParam(description = "commit SHA", required = true) String sha) {}

    @Override
    public ToolResult execute(Arguments arguments, ExecutionContext context) {
        try {
            var commit = client.getCommit(arguments.repository(), arguments.sha());
            ObjectNode summary = mapper.createObjectNode();
            summary.set("commit", mapper.valueToTree(commit));
            return ToolResult.success(mapper.writeValueAsString(summary));
        } catch (Exception exception) {
            return ToolResult.failure("GITHUB_API_ERROR", CiTools.errorMessage(exception));
        }
    }
}
