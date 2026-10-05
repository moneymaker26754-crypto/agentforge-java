package io.github.moneymaker26754.agentforge.infrastructure.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GitHubApiClientTest {
    private static final String RUNS_JSON = """
            {"total_count":2,"workflow_runs":[
              {"id":101,"name":"CI","conclusion":"failure","status":"completed",
               "head_sha":"deadbeef","created_at":"2026-01-01T00:00:00Z"},
              {"id":102,"name":"CI","conclusion":"success","status":"completed",
               "head_sha":"cafebabe","created_at":"2026-01-02T00:00:00Z"}
            ]}
            """;
    private static final String RUN_JSON = """
            {"id":101,"name":"CI","conclusion":"failure","status":"completed",
             "head_sha":"deadbeef","created_at":"2026-01-01T00:00:00Z"}
            """;
    private static final String JOBS_JSON = """
            {"total_count":3,"jobs":[
              {"id":1,"name":"build","conclusion":"success",
               "started_at":"2026-01-01T00:00:00Z","completed_at":"2026-01-01T00:01:00Z",
               "steps":[{"name":"checkout","conclusion":"success"}]},
              {"id":2,"name":"test","conclusion":"failure",
               "started_at":"2026-01-01T00:00:00Z","completed_at":"2026-01-01T00:02:00Z",
               "steps":[{"name":"checkout","conclusion":"success"},
                        {"name":"unit tests","conclusion":"failure"},
                        {"name":"upload artifact","conclusion":"skipped"}]},
              {"id":3,"name":"deploy","conclusion":"skipped",
               "started_at":null,"completed_at":null,"steps":[]}
            ]}
            """;
    private static final String COMMIT_JSON = """
            {"sha":"abc123",
             "commit":{"message":"fix: broken test","author":{"name":"Alice","email":"alice@example.com"}},
             "author":{"login":"alice"},
             "files":[{"filename":"src/main/java/Foo.java","status":"modified"},
                      {"filename":"src/test/java/FooTest.java","status":"modified"}]}
            """;
    private static final String COMMIT_NO_FILES_JSON = """
            {"sha":"nofiles",
             "commit":{"message":"empty commit","author":{"name":"Bob"}},
             "author":{"login":"bob"}}
            """;
    private static final String COMPARE_JSON = """
            {"status":"ahead","files":[
              {"filename":"a.txt","patch":"@@ -1 +1 @@\\n-old\\n+new"},
              {"filename":"b.txt","patch":null},
              {"filename":"c.txt","patch":"@@ -1 +1 @@\\n-x\\n+y"}
            ]}
            """;

    private HttpServer server;
    private final List<String> requestedUris = Collections.synchronizedList(new ArrayList<>());
    private final List<String> authorizationHeaders = Collections.synchronizedList(new ArrayList<>());
    private final List<String> acceptHeaders = Collections.synchronizedList(new ArrayList<>());
    private final List<String> downloadAuthorizationHeaders = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/repos/acme/widgets/actions/workflows/ci.yml/runs", this::handleRuns);
        server.createContext("/repos/acme/widgets/actions/runs/101", this::handleRun);
        server.createContext("/repos/acme/widgets/actions/runs/101/jobs", this::handleJobs);
        server.createContext("/repos/acme/widgets/actions/jobs/2/logs", this::handleLogs);
        server.createContext("/download", this::handleDownload);
        server.createContext("/repos/acme/widgets/commits/abc123", this::handleCommit);
        server.createContext("/repos/acme/widgets/commits/nofiles", this::handleCommitNoFiles);
        server.createContext("/repos/acme/widgets/compare/main...feature", this::handleCompareSmall);
        server.createContext("/repos/acme/widgets/compare/main...big", this::handleCompareBig);
        server.createContext("/", this::handleNotFound);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void listWorkflowRunsParsesRunsArrayAndQueryLimit() throws IOException {
        List<WorkflowRun> runs = client(null).listWorkflowRuns("acme/widgets", "ci.yml", 10);

        assertThat(runs).hasSize(2);
        WorkflowRun failed = runs.get(0);
        assertThat(failed.id()).isEqualTo(101);
        assertThat(failed.name()).isEqualTo("CI");
        assertThat(failed.conclusion()).isEqualTo("failure");
        assertThat(failed.status()).isEqualTo("completed");
        assertThat(failed.headSha()).isEqualTo("deadbeef");
        assertThat(failed.createdAt()).isEqualTo("2026-01-01T00:00:00Z");
        assertThat(runs.get(1).conclusion()).isEqualTo("success");
        assertThat(requestedUris).anyMatch(uri -> uri.equals("/repos/acme/widgets/actions/workflows/ci.yml/runs?per_page=10"));
    }

    @Test
    void getWorkflowRunParsesSingleRun() throws IOException {
        WorkflowRun run = client(null).getWorkflowRun("acme/widgets", 101);

        assertThat(run.id()).isEqualTo(101);
        assertThat(run.conclusion()).isEqualTo("failure");
        assertThat(run.headSha()).isEqualTo("deadbeef");
    }

    @Test
    void getFailedJobsKeepsOnlyFailedJobsAndFailedSteps() throws IOException {
        List<WorkflowJob> jobs = client(null).getFailedJobs("acme/widgets", 101);

        assertThat(jobs).hasSize(1);
        WorkflowJob job = jobs.get(0);
        assertThat(job.id()).isEqualTo(2);
        assertThat(job.name()).isEqualTo("test");
        assertThat(job.conclusion()).isEqualTo("failure");
        assertThat(job.stepsFailed()).containsExactly("unit tests");
    }

    @Test
    void getJobLogsFollowsRedirectAndTruncatesKeepingHeadAndTail() throws IOException {
        String logs = client(null).getJobLogs("acme/widgets", 2, 1000);

        assertThat(logs).hasSize(1000);
        assertThat(logs).contains("...[truncated]...");
        assertThat(logs).startsWith("line 0001\n");
        assertThat(logs).endsWith("line 5000\n");
    }

    @Test
    void getJobLogsDoesNotForwardAuthorizationToRedirectTarget() throws IOException {
        client("secret-token").getJobLogs("acme/widgets", 2, 500);

        assertThat(downloadAuthorizationHeaders).isEmpty();
    }

    @Test
    void getCommitExtractsShaMessageAuthorAndChangedFiles() throws IOException {
        CommitInfo commit = client(null).getCommit("acme/widgets", "abc123");

        assertThat(commit.sha()).isEqualTo("abc123");
        assertThat(commit.message()).isEqualTo("fix: broken test");
        assertThat(commit.author()).isEqualTo("Alice");
        assertThat(commit.changedFiles()).containsExactly("src/main/java/Foo.java", "src/test/java/FooTest.java");
    }

    @Test
    void getCommitWithoutFilesFieldYieldsEmptyList() throws IOException {
        CommitInfo commit = client(null).getCommit("acme/widgets", "nofiles");

        assertThat(commit.sha()).isEqualTo("nofiles");
        assertThat(commit.author()).isEqualTo("Bob");
        assertThat(commit.changedFiles()).isEmpty();
    }

    @Test
    void getDiffJoinsPatchesAndSkipsNulls() throws IOException {
        String diff = client(null).getDiff("acme/widgets", "main", "feature");

        assertThat(diff).isEqualTo("@@ -1 +1 @@\n-old\n+new\n@@ -1 +1 @@\n-x\n+y");
    }

    @Test
    void getDiffTruncatesAtSixtyThousandChars() throws IOException {
        String diff = client(null).getDiff("acme/widgets", "main", "big");

        assertThat(diff).hasSize(60_000);
        assertThat(diff).contains("...[truncated]...");
        assertThat(diff).startsWith("@@ -1 +1 @@\n");
        assertThat(diff).endsWith("aaaa");
    }

    @Test
    void non2xxThrowsGitHubApiExceptionWithServerMessage() {
        Throwable thrown = catchThrowable(() -> client(null).getWorkflowRun("acme/widgets", 999));

        assertThat(thrown).isInstanceOf(GitHubApiException.class);
        GitHubApiException exception = (GitHubApiException) thrown;
        assertThat(exception.status()).isEqualTo(404);
        assertThat(exception.getMessage()).contains("Not Found");
    }

    @Test
    void addsApiHeadersAndBearerTokenWhenTokenPresent() throws IOException {
        client("secret-token").listWorkflowRuns("acme/widgets", "ci.yml", 5);

        assertThat(authorizationHeaders).containsExactly("Bearer secret-token");
        assertThat(acceptHeaders).containsExactly("application/vnd.github+json");
    }

    @Test
    void omitsAuthorizationHeaderWhenTokenAbsent() throws IOException {
        client(null).listWorkflowRuns("acme/widgets", "ci.yml", 5);

        assertThat(authorizationHeaders).isEmpty();
        assertThat(acceptHeaders).containsExactly("application/vnd.github+json");
    }

    @Test
    void rejectsMalformedRepository() {
        assertThatThrownBy(() -> client(null).listWorkflowRuns("not-a-repository", "ci.yml", 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("owner/name");
    }

    private GitHubApiClient client(String token) {
        return new GitHubApiClient(HttpClient.newHttpClient(), new ObjectMapper(), baseUri(), token);
    }

    private URI baseUri() {
        return URI.create("http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort());
    }

    private void handleRuns(HttpExchange exchange) throws IOException {
        recordHeaders(exchange);
        respond(exchange, 200, RUNS_JSON);
    }

    private void handleRun(HttpExchange exchange) throws IOException {
        respond(exchange, 200, RUN_JSON);
    }

    private void handleJobs(HttpExchange exchange) throws IOException {
        respond(exchange, 200, JOBS_JSON);
    }

    private void handleLogs(HttpExchange exchange) throws IOException {
        String location = baseUri() + "/download";
        exchange.getResponseHeaders().add("Location", location);
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
    }

    private void handleDownload(HttpExchange exchange) throws IOException {
        exchange.getRequestHeaders().forEach((name, values) -> {
            if (name.equalsIgnoreCase("Authorization")) {
                downloadAuthorizationHeaders.addAll(values);
            }
        });
        respond(exchange, 200, downloadBody());
    }

    private void handleCommit(HttpExchange exchange) throws IOException {
        respond(exchange, 200, COMMIT_JSON);
    }

    private void handleCommitNoFiles(HttpExchange exchange) throws IOException {
        respond(exchange, 200, COMMIT_NO_FILES_JSON);
    }

    private void handleCompareSmall(HttpExchange exchange) throws IOException {
        respond(exchange, 200, COMPARE_JSON);
    }

    private void handleCompareBig(HttpExchange exchange) throws IOException {
        // The JSON escape \n keeps the payload valid; Jackson turns it back into a newline.
        String patch = "@@ -1 +1 @@\\n" + "a".repeat(40_000);
        String json = "{\"files\":[{\"filename\":\"big1.txt\",\"patch\":\"" + patch + "\"},"
                + "{\"filename\":\"big2.txt\",\"patch\":\"" + patch + "\"}]}";
        respond(exchange, 200, json);
    }

    private void handleNotFound(HttpExchange exchange) throws IOException {
        respond(exchange, 404, "{\"message\":\"Not Found\"}");
    }

    private void recordHeaders(HttpExchange exchange) {
        requestedUris.add(exchange.getRequestURI().toString());
        exchange.getRequestHeaders().forEach((name, values) -> {
            if (name.equalsIgnoreCase("Authorization")) {
                authorizationHeaders.addAll(values);
            } else if (name.equalsIgnoreCase("Accept")) {
                acceptHeaders.addAll(values);
            }
        });
    }

    private static String downloadBody() {
        StringBuilder body = new StringBuilder();
        for (int line = 1; line <= 5000; line++) {
            body.append("line ").append(String.format("%04d", line)).append('\n');
        }
        return body.toString();
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
