package io.github.moneymaker26754.agentforge.infrastructure.ci;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.moneymaker26754.agentforge.core.ExecutionContext;
import io.github.moneymaker26754.agentforge.core.RiskLevel;
import io.github.moneymaker26754.agentforge.core.ToolHandler;
import io.github.moneymaker26754.agentforge.core.ToolResult;
import io.github.moneymaker26754.agentforge.infrastructure.tool.AgentTool;
import io.github.moneymaker26754.agentforge.infrastructure.tool.ToolParam;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;

@AgentTool(name = "createPullRequest",
        description = "Create a GitHub pull request via the GitHub API (requires GITHUB_TOKEN in the environment)",
        risk = RiskLevel.NETWORK)
public final class CreatePullRequestTool implements ToolHandler<CreatePullRequestTool.Arguments> {
    private static final String DEFAULT_BASE_URL = "https://api.github.com";
    private static final int ERROR_BODY_CHARS = 1000;

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final String baseUrl;

    public CreatePullRequestTool(HttpClient http, ObjectMapper mapper,
            @Value("${agentforge.github.baseUrl:" + DEFAULT_BASE_URL + "}") String baseUrl) {
        this.http = http;
        this.mapper = mapper;
        this.baseUrl = baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL : baseUrl;
    }

    public record Arguments(
            @ToolParam(description = "repository in owner/name form", required = true) String repository,
            @ToolParam(description = "pull request title", required = true) String title,
            @ToolParam(description = "head branch carrying the changes", required = true) String head,
            @ToolParam(description = "base branch to merge into", required = true) String base,
            @ToolParam(description = "optional pull request body") String body) {}

    @Override
    public ToolResult execute(Arguments arguments, ExecutionContext context) {
        String token = token();
        if (token == null || token.isBlank()) {
            return ToolResult.failure("GITHUB_API_ERROR", "GITHUB_TOKEN environment variable is not set");
        }
        try {
            ObjectNode payload = mapper.createObjectNode();
            payload.put("title", arguments.title());
            payload.put("head", arguments.head());
            payload.put("base", arguments.base());
            if (arguments.body() != null && !arguments.body().isBlank()) {
                payload.put("body", arguments.body());
            }
            var request = HttpRequest.newBuilder(URI.create(baseUrl + "/repos/" + arguments.repository() + "/pulls"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + token)
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)))
                    .build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                return ToolResult.failure("GITHUB_API_ERROR", "GitHub returned HTTP " + response.statusCode() + ": "
                        + CiTools.truncate(response.body(), ERROR_BODY_CHARS));
            }
            JsonNode body = mapper.readTree(response.body());
            String htmlUrl = body.path("html_url").asText("");
            return ToolResult.success("created pull request: " + htmlUrl);
        } catch (Exception exception) {
            return ToolResult.failure("GITHUB_API_ERROR", CiTools.errorMessage(exception));
        }
    }

    /** Reads GITHUB_TOKEN from the environment; the system property agentforge.github.token is a test/dev fallback. */
    private static String token() {
        String token = System.getenv("GITHUB_TOKEN");
        if (token == null || token.isBlank()) token = System.getProperty("agentforge.github.token");
        return token;
    }
}
