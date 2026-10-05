package io.github.moneymaker26754.agentforge.infrastructure.ci;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.moneymaker26754.agentforge.core.ExecutionContext;
import io.github.moneymaker26754.agentforge.core.SandboxMode;
import io.github.moneymaker26754.agentforge.core.SessionId;
import io.github.moneymaker26754.agentforge.infrastructure.github.GitHubApiClient;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Exercises the GitHub reading tools against a local com.sun.net.httpserver stub. */
class GitHubToolsTest {
    private HttpServer server;
    private GitHubApiClient client;
    private ObjectMapper mapper;

    @BeforeEach
    void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        mapper = new ObjectMapper();
        client = new GitHubApiClient(HttpClient.newHttpClient(), mapper,
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()), null);
    }

    @AfterEach
    void stopStub() {
        server.stop(0);
    }

    @Test
    void getWorkflowRunReturnsJsonSummary() throws Exception {
        server.createContext("/repos/acme/proj/actions/runs/123", exchange -> respond(exchange, 200,
                "{\"id\":123,\"name\":\"CI\",\"conclusion\":\"failure\",\"status\":\"completed\","
                        + "\"head_sha\":\"abc1234\",\"created_at\":\"2026-09-29T10:00:00Z\"}"));
        var tool = new GetWorkflowRunTool(client, mapper);

        var result = tool.execute(new GetWorkflowRunTool.Arguments("acme/proj", 123), context());

        assertThat(result.success()).isTrue();
        var summary = mapper.readTree(result.content());
        assertThat(summary.path("run").path("id").asLong()).isEqualTo(123);
        assertThat(summary.path("run").path("name").asText()).isEqualTo("CI");
        assertThat(summary.path("run").path("conclusion").asText()).isEqualTo("failure");
        assertThat(summary.path("run").path("headSha").asText()).isEqualTo("abc1234");
    }

    @Test
    void getWorkflowRunMaps404ToGithubApiError() {
        server.createContext("/repos/acme/proj/actions/runs/999",
                exchange -> respond(exchange, 404, "{\"message\":\"Not Found\"}"));
        var tool = new GetWorkflowRunTool(client, mapper);

        var result = tool.execute(new GetWorkflowRunTool.Arguments("acme/proj", 999), context());

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo("GITHUB_API_ERROR");
        assertThat(result.content()).contains("404");
    }

    @Test
    void getFailedJobsListsOnlyFailedJobsWithFailedSteps() throws Exception {
        server.createContext("/repos/acme/proj/actions/runs/123/jobs", exchange -> respond(exchange, 200,
                "{\"jobs\":["
                        + "{\"id\":7,\"name\":\"build\",\"conclusion\":\"failure\","
                        + "\"started_at\":\"t1\",\"completed_at\":\"t2\","
                        + "\"steps\":[{\"name\":\"Checkout\",\"conclusion\":\"success\"},"
                        + "{\"name\":\"Run tests\",\"conclusion\":\"failure\"}]},"
                        + "{\"id\":8,\"name\":\"lint\",\"conclusion\":\"success\",\"steps\":[]}]}"));
        var tool = new GetFailedJobsTool(client, mapper);

        var result = tool.execute(new GetFailedJobsTool.Arguments("acme/proj", 123), context());

        assertThat(result.success()).isTrue();
        var summary = mapper.readTree(result.content());
        assertThat(summary.path("count").asInt()).isEqualTo(1);
        assertThat(summary.path("jobs").get(0).path("name").asText()).isEqualTo("build");
        assertThat(summary.path("jobs").get(0).path("stepsFailed").get(0).asText()).isEqualTo("Run tests");
    }

    @Test
    void getJobLogsReturnsRawLogText() {
        server.createContext("/repos/acme/proj/actions/jobs/7/logs",
                exchange -> respond(exchange, 200, "line one\nline two\n"));
        var tool = new GetJobLogsTool(client);

        // maxChars 0 exercises the 40000 default
        var result = tool.execute(new GetJobLogsTool.Arguments("acme/proj", 7, 0), context());

        assertThat(result.success()).isTrue();
        assertThat(result.content()).isEqualTo("line one\nline two\n");
    }

    @Test
    void getCommitReturnsCommitJsonSummary() throws Exception {
        server.createContext("/repos/acme/proj/commits/abc1234", exchange -> respond(exchange, 200,
                "{\"sha\":\"abc1234\",\"commit\":{\"message\":\"fix: null check\",\"author\":{\"name\":\"Dev\"}},"
                        + "\"files\":[{\"filename\":\"src/main/Foo.java\"},{\"filename\":\"src/test/FooTest.java\"}]}"));
        var tool = new GetCommitTool(client, mapper);

        var result = tool.execute(new GetCommitTool.Arguments("acme/proj", "abc1234"), context());

        assertThat(result.success()).isTrue();
        var summary = mapper.readTree(result.content());
        assertThat(summary.path("commit").path("sha").asText()).isEqualTo("abc1234");
        assertThat(summary.path("commit").path("message").asText()).isEqualTo("fix: null check");
        assertThat(summary.path("commit").path("author").asText()).isEqualTo("Dev");
        assertThat(summary.path("commit").path("changedFiles").get(1).asText()).isEqualTo("src/test/FooTest.java");
    }

    @Test
    void getDiffReturnsConcatenatedPatchText() {
        server.createContext("/repos/acme/proj/compare/main...fix", exchange -> respond(exchange, 200,
                "{\"files\":[{\"patch\":\"@@ -1 +1 @@\\n-old\\n+new\"}]}"));
        var tool = new GetDiffTool(client);

        var result = tool.execute(new GetDiffTool.Arguments("acme/proj", "main", "fix"), context());

        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("+new").contains("-old");
    }

    private ExecutionContext context() {
        return new ExecutionContext(new SessionId("ci-github-tools"), Path.of("."), SandboxMode.LOCAL);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
