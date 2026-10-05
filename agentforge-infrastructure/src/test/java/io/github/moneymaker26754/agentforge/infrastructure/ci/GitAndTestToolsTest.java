package io.github.moneymaker26754.agentforge.infrastructure.ci;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.moneymaker26754.agentforge.core.CommandSpec;
import io.github.moneymaker26754.agentforge.core.ExecutionContext;
import io.github.moneymaker26754.agentforge.core.ExecutionResult;
import io.github.moneymaker26754.agentforge.core.SandboxExecutor;
import io.github.moneymaker26754.agentforge.core.SandboxMode;
import io.github.moneymaker26754.agentforge.core.SessionId;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** Exercises RunTestTool, GitCommitTool, GitPushTool and CreatePullRequestTool against fakes and a local HTTP stub. */
class GitAndTestToolsTest {

    @Test
    void runTargetedTestBuildsWrapperArgvAndUsesLocalExecutorInLocalMode() {
        var local = new RecordingSandbox(new ExecutionResult(0, "Tests run: 1, Failures: 0", "", false, 42));
        var docker = new RecordingSandbox();
        var tool = new RunTestTool(local, docker);

        var result = tool.execute(new RunTestTool.Arguments("com.example.CalculatorTest#divideByZero", 0),
                context(SandboxMode.LOCAL));

        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("Tests run: 1, Failures: 0");
        assertThat(local.commands()).hasSize(1);
        assertThat(docker.commands()).isEmpty();
        String wrapper = RunTestTool.isWindows() ? "mvnw.cmd" : "./mvnw";
        assertThat(local.commands().getFirst().argv()).containsExactly(
                wrapper, "test", "-Dtest=com.example.CalculatorTest#divideByZero", "-DfailIfNoTests=false");
        // timeoutSeconds 0 exercises the 120 second default
        assertThat(local.commands().getFirst().timeout().toSeconds()).isEqualTo(120);
    }

    @Test
    void runTargetedTestUsesDockerExecutorAndLinuxWrapperInDockerMode() {
        var local = new RecordingSandbox();
        var docker = new RecordingSandbox(new ExecutionResult(0, "ok", "", false, 1));
        var tool = new RunTestTool(local, docker);

        var result = tool.execute(new RunTestTool.Arguments("com.example.*Test", 30), context(SandboxMode.DOCKER));

        assertThat(result.success()).isTrue();
        assertThat(local.commands()).isEmpty();
        assertThat(docker.commands()).hasSize(1);
        assertThat(docker.commands().getFirst().argv()).containsExactly(
                "./mvnw", "test", "-Dtest=com.example.*Test", "-DfailIfNoTests=false");
        assertThat(docker.commands().getFirst().timeout().toSeconds()).isEqualTo(30);
    }

    @Test
    void runTargetedTestReportsTestFailedWithExitCodeWhenTestsFail() {
        var local = new RecordingSandbox(new ExecutionResult(1, "", "BUILD FAILURE", false, 500));
        var tool = new RunTestTool(local, new RecordingSandbox());

        var result = tool.execute(new RunTestTool.Arguments("com.example.CalculatorTest#divideByZero", 0),
                context(SandboxMode.LOCAL));

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo("TEST_FAILED");
        assertThat(result.content()).contains("exit=1").contains("BUILD FAILURE");
    }

    @Test
    void runTargetedTestFailsWhenSandboxTimesOut() {
        var local = new RecordingSandbox(new ExecutionResult(-1, "", "", true, 600_000));
        var tool = new RunTestTool(local, new RecordingSandbox());

        var result = tool.execute(new RunTestTool.Arguments("com.example.CalculatorTest", 0),
                context(SandboxMode.LOCAL));

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo("TEST_FAILED");
        assertThat(result.content()).contains("timedOut=true");
    }

    @Test
    void gitCommitStagesAllThenCommitsWithMessage() {
        var sandbox = new RecordingSandbox();
        var tool = new GitCommitTool(sandbox);

        var result = tool.execute(new GitCommitTool.Arguments("fix: repair null check"), context(SandboxMode.LOCAL));

        assertThat(result.success()).isTrue();
        assertThat(sandbox.commands()).hasSize(2);
        assertThat(sandbox.commands().get(0).argv()).containsExactly("git", "add", "-A");
        assertThat(sandbox.commands().get(1).argv()).containsExactly("git", "commit", "-m", "fix: repair null check");
    }

    @Test
    void gitCommitRejectsBlankMessageWithoutRunningGit() {
        var sandbox = new RecordingSandbox();
        var tool = new GitCommitTool(sandbox);

        var result = tool.execute(new GitCommitTool.Arguments("   "), context(SandboxMode.LOCAL));

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo("GIT_COMMIT_FAILED");
        assertThat(sandbox.commands()).isEmpty();
    }

    @Test
    void gitCommitReportsFailureWhenGitAddFails() {
        var sandbox = new RecordingSandbox(new ExecutionResult(128, "", "fatal: not a git repository", false, 1));
        var tool = new GitCommitTool(sandbox);

        var result = tool.execute(new GitCommitTool.Arguments("fix"), context(SandboxMode.LOCAL));

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo("GIT_COMMIT_FAILED");
        assertThat(result.content()).contains("fatal: not a git repository");
        assertThat(sandbox.commands()).hasSize(1);
    }

    @Test
    void gitPushUsesUpstreamDefaultWhenNoBranchGiven() {
        var sandbox = new RecordingSandbox();
        var tool = new GitPushTool(sandbox);

        var result = tool.execute(new GitPushTool.Arguments(null), context(SandboxMode.LOCAL));

        assertThat(result.success()).isTrue();
        assertThat(sandbox.commands().getFirst().argv()).containsExactly("git", "push");
    }

    @Test
    void gitPushPushesExplicitBranchToOrigin() {
        var sandbox = new RecordingSandbox();
        var tool = new GitPushTool(sandbox);

        var result = tool.execute(new GitPushTool.Arguments("fix/ci-flakiness"), context(SandboxMode.LOCAL));

        assertThat(result.success()).isTrue();
        assertThat(sandbox.commands().getFirst().argv()).containsExactly("git", "push", "origin", "fix/ci-flakiness");
    }

    @Test
    void createPullRequestPostsJsonAndReturnsHtmlUrl() throws Exception {
        var seenMethod = new AtomicReference<String>();
        var seenAuthorization = new AtomicReference<String>();
        var seenBody = new AtomicReference<String>();
        HttpServer server = startServer(exchange -> {
            seenMethod.set(exchange.getRequestMethod());
            seenAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            seenBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 201, "{\"html_url\":\"https://github.com/acme/proj/pull/42\"}");
        }, "/repos/acme/proj/pulls");
        System.setProperty("agentforge.github.token", "test-token");
        try {
            var tool = new CreatePullRequestTool(HttpClient.newHttpClient(), new ObjectMapper(),
                    "http://127.0.0.1:" + server.getAddress().getPort());

            var result = tool.execute(
                    new CreatePullRequestTool.Arguments("acme/proj", "Fix CI", "fix/ci", "main", "Repairs the build"),
                    context(SandboxMode.LOCAL));

            assertThat(result.success()).isTrue();
            assertThat(result.content()).contains("https://github.com/acme/proj/pull/42");
            assertThat(seenMethod.get()).isEqualTo("POST");
            assertThat(seenAuthorization.get()).isEqualTo("Bearer " + effectiveToken());
            assertThat(seenBody.get())
                    .contains("\"title\":\"Fix CI\"")
                    .contains("\"head\":\"fix/ci\"")
                    .contains("\"base\":\"main\"")
                    .contains("\"body\":\"Repairs the build\"");
        } finally {
            System.clearProperty("agentforge.github.token");
            server.stop(0);
        }
    }

    @Test
    void createPullRequestMapsApiErrorResponse() throws Exception {
        HttpServer server = startServer(exchange -> respond(exchange, 422, "{\"message\":\"Validation Failed\"}"),
                "/repos/acme/proj/pulls");
        System.setProperty("agentforge.github.token", "test-token");
        try {
            var tool = new CreatePullRequestTool(HttpClient.newHttpClient(), new ObjectMapper(),
                    "http://127.0.0.1:" + server.getAddress().getPort());

            var result = tool.execute(new CreatePullRequestTool.Arguments("acme/proj", "Fix", "head", "main", null),
                    context(SandboxMode.LOCAL));

            assertThat(result.success()).isFalse();
            assertThat(result.errorCode()).isEqualTo("GITHUB_API_ERROR");
            assertThat(result.content()).contains("HTTP 422").contains("Validation Failed");
        } finally {
            System.clearProperty("agentforge.github.token");
            server.stop(0);
        }
    }

    @Test
    void createPullRequestFailsWhenNoTokenIsConfigured() {
        // The plan assumes GITHUB_TOKEN is absent on build machines; skip when one is present.
        Assumptions.assumeTrue(System.getenv("GITHUB_TOKEN") == null || System.getenv("GITHUB_TOKEN").isBlank());
        System.clearProperty("agentforge.github.token");
        var tool = new CreatePullRequestTool(HttpClient.newHttpClient(), new ObjectMapper(), "http://127.0.0.1:1");

        var result = tool.execute(new CreatePullRequestTool.Arguments("acme/proj", "Fix", "head", "main", null),
                context(SandboxMode.LOCAL));

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo("GITHUB_API_ERROR");
        assertThat(result.content()).contains("GITHUB_TOKEN");
    }

    private static String effectiveToken() {
        String env = System.getenv("GITHUB_TOKEN");
        return env != null && !env.isBlank() ? env : "test-token";
    }

    private ExecutionContext context(SandboxMode mode) {
        return new ExecutionContext(new SessionId("ci-tools"), Path.of("."), mode);
    }

    private static HttpServer startServer(com.sun.net.httpserver.HttpHandler handler, String path) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(path, handler);
        server.start();
        return server;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static final class RecordingSandbox implements SandboxExecutor {
        private final List<CommandSpec> commands = new ArrayList<>();
        private final ExecutionResult fixed;

        RecordingSandbox() {
            this(new ExecutionResult(0, "ok", "", false, 1));
        }

        RecordingSandbox(ExecutionResult fixed) {
            this.fixed = fixed;
        }

        @Override
        public ExecutionResult execute(CommandSpec command, ExecutionContext context) {
            commands.add(command);
            return fixed;
        }

        List<CommandSpec> commands() {
            return commands;
        }
    }
}
