package io.github.moneymaker26754.agentforge.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.moneymaker26754.agentforge.core.*;
import io.github.moneymaker26754.agentforge.infrastructure.github.CiContextAssembler;
import io.github.moneymaker26754.agentforge.infrastructure.github.GitHubApiClient;
import io.github.moneymaker26754.agentforge.infrastructure.sandbox.*;
import io.github.moneymaker26754.agentforge.infrastructure.security.DefaultPolicyEngine;
import io.github.moneymaker26754.agentforge.infrastructure.store.SqliteCheckpointStore;
import io.github.moneymaker26754.agentforge.infrastructure.tool.ReflectiveToolRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class AgentForgeConfiguration {
    @Bean ObjectMapper objectMapper() {
        return new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
    @Bean HttpClient httpClient() { return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build(); }
    @Bean SqliteCheckpointStore checkpointStore(ObjectMapper mapper) {
        return new SqliteCheckpointStore(stateDirectory().resolve("state.db"), mapper);
    }
    @Bean LocalSandboxExecutor localSandboxExecutor() {
        return new LocalSandboxExecutor(Set.of("java", "java.exe", "javac", "javac.exe", "mvn", "mvn.cmd",
                "mvnw", "mvnw.cmd", "gradle", "gradle.bat", "gradlew", "gradlew.bat", "git", "git.exe", "rg", "rg.exe"));
    }
    @Bean DockerSandboxExecutor dockerSandboxExecutor() { return new DockerSandboxExecutor(new DockerCommandFactory("agentforge-sandbox:java21")); }

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

    @Bean PolicyEngine policyEngine() { return new DefaultPolicyEngine(false); }
    @Bean ApprovalHandler approvalHandler() { return new InteractiveApprovalHandler(); }
    @Bean ToolExecutionGate toolExecutionGate(ReflectiveToolRegistry nativeTools, PolicyEngine policy,
            ApprovalHandler approval) {
        return ToolExecutionGate.compose(nativeTools.validator(), policy, approval);
    }
    @Bean GitHubApiClient githubApiClient(HttpClient http, ObjectMapper mapper) {
        String baseUrl = System.getenv().getOrDefault("AGENTFORGE_GITHUB_BASE_URL", "https://api.github.com");
        return new GitHubApiClient(http, mapper, URI.create(baseUrl), System.getenv("GITHUB_TOKEN"));
    }
    @Bean CiContextAssembler ciContextAssembler() { return new CiContextAssembler(); }
    @Bean AgentOperations agentOperations(SqliteCheckpointStore store, ToolRegistry tools, ToolExecutionGate gate,
            ObjectMapper mapper, HttpClient http) { return new DefaultAgentOperations(store, tools, gate, mapper, http); }
    @Bean DiagnosticProvider diagnosticProvider() { return new SystemDiagnosticProvider(); }

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
}
