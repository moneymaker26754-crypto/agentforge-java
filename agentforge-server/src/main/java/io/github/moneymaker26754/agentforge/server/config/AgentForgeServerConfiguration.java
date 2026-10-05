package io.github.moneymaker26754.agentforge.server.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.moneymaker26754.agentforge.core.ApprovalHandler;
import io.github.moneymaker26754.agentforge.core.CheckpointStore;
import io.github.moneymaker26754.agentforge.core.PolicyEngine;
import io.github.moneymaker26754.agentforge.core.SandboxExecutor;
import io.github.moneymaker26754.agentforge.core.ToolExecutionGate;
import io.github.moneymaker26754.agentforge.core.ToolHandler;
import io.github.moneymaker26754.agentforge.core.ToolRegistry;
import io.github.moneymaker26754.agentforge.infrastructure.github.CiContextAssembler;
import io.github.moneymaker26754.agentforge.infrastructure.github.GitHubApiClient;
import io.github.moneymaker26754.agentforge.infrastructure.model.DeepSeekModelClient;
import io.github.moneymaker26754.agentforge.infrastructure.model.OllamaModelClient;
import io.github.moneymaker26754.agentforge.infrastructure.sandbox.DockerCommandFactory;
import io.github.moneymaker26754.agentforge.infrastructure.sandbox.DockerSandboxExecutor;
import io.github.moneymaker26754.agentforge.infrastructure.sandbox.LocalSandboxExecutor;
import io.github.moneymaker26754.agentforge.infrastructure.sandbox.RoutingSandboxExecutor;
import io.github.moneymaker26754.agentforge.infrastructure.security.DefaultPolicyEngine;
import io.github.moneymaker26754.agentforge.infrastructure.store.SqliteCheckpointStore;
import io.github.moneymaker26754.agentforge.infrastructure.tool.ReflectiveToolRegistry;
import io.github.moneymaker26754.agentforge.server.runtime.PendingApprovalHandler;
import io.github.moneymaker26754.agentforge.server.runtime.ServerAgentRuntime;
import io.github.moneymaker26754.agentforge.server.runtime.TaskExecutionCoordinator;
import io.github.moneymaker26754.agentforge.server.store.AgentTaskStore;
import io.github.moneymaker26754.agentforge.server.store.SqliteAgentTaskStore;
import io.github.moneymaker26754.agentforge.server.web.MetricsService;
import io.github.moneymaker26754.agentforge.server.web.SessionReportService;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Control-plane wiring: stores, sandboxes, the unified tool bus, the PreToolUse gate, model
 * clients, GitHub integration and the bounded task executor.
 */
@Configuration
public class AgentForgeServerConfiguration {

    @Bean ObjectMapper objectMapper() {
        return new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Bean HttpClient httpClient() { return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build(); }

    @Bean Clock clock() { return Clock.systemUTC(); }

    @Bean CheckpointStore checkpointStore(ObjectMapper mapper) {
        return new SqliteCheckpointStore(stateDirectory().resolve("server.db"), mapper);
    }

    @Bean AgentTaskStore agentTaskStore(Clock clock) {
        return new SqliteAgentTaskStore(stateDirectory().resolve("server.db"), clock);
    }

    @Bean LocalSandboxExecutor localSandboxExecutor() {
        return new LocalSandboxExecutor(Set.of("java", "java.exe", "javac", "javac.exe", "mvn", "mvn.cmd",
                "mvnw", "mvnw.cmd", "gradle", "gradle.bat", "gradlew", "gradlew.bat", "git", "git.exe", "rg", "rg.exe"));
    }

    @Bean DockerSandboxExecutor dockerSandboxExecutor() {
        return new DockerSandboxExecutor(new DockerCommandFactory("agentforge-sandbox:java21"));
    }

    /** Test runs may resolve dependencies; the flag is the only network exception to the sandbox. */
    @Bean(name = "testDockerSandboxExecutor")
    DockerSandboxExecutor testDockerSandboxExecutor() {
        return new DockerSandboxExecutor(new DockerCommandFactory("agentforge-sandbox:java21", true));
    }

    @Bean @Primary SandboxExecutor sandboxExecutor(
            @org.springframework.beans.factory.annotation.Qualifier("dockerSandboxExecutor") DockerSandboxExecutor docker,
            LocalSandboxExecutor local) {
        return new RoutingSandboxExecutor(docker, local);
    }

    @Bean ReflectiveToolRegistry nativeToolRegistry(ObjectMapper mapper, List<ToolHandler<?>> handlers) {
        return new ReflectiveToolRegistry(mapper, handlers);
    }

    /** Opt-in MCP bridge: no subprocesses are started unless AGENTFORGE_MCP_SERVERS is configured. */
    @Bean(destroyMethod = "close")
    io.github.moneymaker26754.agentforge.infrastructure.mcp.McpToolBridge mcpToolBridge(ObjectMapper mapper) {
        return new io.github.moneymaker26754.agentforge.infrastructure.mcp.McpToolBridge(mapper,
                io.github.moneymaker26754.agentforge.infrastructure.mcp.McpServerConfigs.fromEnvironment(mapper));
    }

    @Bean @Primary ToolRegistry toolRegistry(ReflectiveToolRegistry nativeTools,
            io.github.moneymaker26754.agentforge.infrastructure.mcp.McpToolBridge mcp) {
        return new io.github.moneymaker26754.agentforge.infrastructure.tool.CompositeToolRegistry(
                List.of(nativeTools, mcp));
    }

    @Bean PolicyEngine policyEngine(ServerProperties properties) {
        return new DefaultPolicyEngine(properties.networkAllowed());
    }

    @Bean ApprovalHandler approvalHandler(AgentTaskStore taskStore, Clock clock) {
        return new PendingApprovalHandler(taskStore, clock);
    }

    @Bean ToolExecutionGate toolExecutionGate(ReflectiveToolRegistry nativeTools, PolicyEngine policy,
            ApprovalHandler approval) {
        return ToolExecutionGate.compose(nativeTools.validator(), policy, approval);
    }

    @Bean GitHubApiClient githubApiClient(HttpClient http, ObjectMapper mapper, ServerProperties properties) {
        String token = System.getenv("GITHUB_TOKEN");
        return new GitHubApiClient(http, mapper, URI.create(properties.githubBaseUrl()), token);
    }

    @Bean CiContextAssembler ciContextAssembler() { return new CiContextAssembler(); }

    @Bean io.github.moneymaker26754.agentforge.core.ModelClient modelClient(HttpClient http, ObjectMapper mapper,
            ServerProperties properties) {
        String provider = properties.provider().toLowerCase(Locale.ROOT);
        if ("ollama".equals(provider)) {
            return new OllamaModelClient(http,
                    URI.create(environment("OLLAMA_BASE_URL", "http://localhost:11434")), mapper);
        }
        String key = System.getenv("DEEPSEEK_API_KEY");
        if (key == null || key.isBlank()) {
            // Fail at task execution, not at startup: the control plane stays up and the task is
            // recorded as FAILED with a clear reason instead of preventing the server from booting.
            return (request, sink) -> {
                throw new IllegalStateException("DEEPSEEK_API_KEY is not set and provider is "
                        + properties.provider() + "; set DEEPSEEK_API_KEY or AGENTFORGE_PROVIDER=ollama");
            };
        }
        return new DeepSeekModelClient(http, URI.create(environment("DEEPSEEK_BASE_URL", "https://api.deepseek.com")),
                key, mapper, decimal("DEEPSEEK_INPUT_CNY_PER_1K", 0.002), decimal("DEEPSEEK_OUTPUT_CNY_PER_1K", 0.008));
    }

    @Bean ServerAgentRuntime serverAgentRuntime(io.github.moneymaker26754.agentforge.core.ModelClient modelClient,
            ToolRegistry tools, ToolExecutionGate gate, CheckpointStore checkpointStore, Clock clock) {
        return new ServerAgentRuntime(modelClient, tools, gate, checkpointStore, clock);
    }

    @Bean TaskExecutionCoordinator taskExecutionCoordinator(AgentTaskStore taskStore, ServerAgentRuntime runtime,
            ServerProperties properties, Clock clock) {
        return new TaskExecutionCoordinator(taskStore, runtime, properties, clock);
    }

    @Bean SessionReportService sessionReportService(CheckpointStore checkpointStore, ObjectMapper mapper) {
        return new SessionReportService(checkpointStore, mapper);
    }

    @Bean MetricsService metricsService(AgentTaskStore taskStore, CheckpointStore checkpointStore) {
        return new MetricsService(taskStore, checkpointStore);
    }

    static Path stateDirectory() {
        String property = System.getProperty("agentforge.state.dir");
        if (property != null && !property.isBlank()) return Path.of(property);
        String configured = System.getenv("AGENTFORGE_STATE_DIR");
        if (configured != null && !configured.isBlank()) return Path.of(configured);
        String local = System.getenv("LOCALAPPDATA");
        if (local != null && !local.isBlank()) return Path.of(local, "AgentForge");
        String xdg = System.getenv("XDG_STATE_HOME");
        if (xdg != null && !xdg.isBlank()) return Path.of(xdg, "agentforge");
        return Path.of(System.getProperty("user.home"), ".agentforge");
    }

    private static String environment(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static double decimal(String name, double fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : Double.parseDouble(value);
    }
}
