package io.github.moneymaker26754.agentforge.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.moneymaker26754.agentforge.core.*;
import io.github.moneymaker26754.agentforge.infrastructure.sandbox.*;
import io.github.moneymaker26754.agentforge.infrastructure.security.DefaultPolicyEngine;
import io.github.moneymaker26754.agentforge.infrastructure.store.SqliteCheckpointStore;
import io.github.moneymaker26754.agentforge.infrastructure.tool.ReflectiveToolRegistry;
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
    @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
    @Bean HttpClient httpClient() { return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build(); }
    @Bean SqliteCheckpointStore checkpointStore(ObjectMapper mapper) {
        return new SqliteCheckpointStore(stateDirectory().resolve("state.db"), mapper);
    }
    @Bean LocalSandboxExecutor localSandboxExecutor() {
        return new LocalSandboxExecutor(Set.of("java", "java.exe", "javac", "javac.exe", "mvn", "mvn.cmd",
                "mvnw", "mvnw.cmd", "gradle", "gradle.bat", "gradlew", "gradlew.bat", "git", "git.exe", "rg", "rg.exe"));
    }
    @Bean DockerSandboxExecutor dockerSandboxExecutor() { return new DockerSandboxExecutor(new DockerCommandFactory("agentforge-sandbox:java21")); }
    @Bean @Primary SandboxExecutor sandboxExecutor(DockerSandboxExecutor docker, LocalSandboxExecutor local) {
        return new RoutingSandboxExecutor(docker, local);
    }
    @Bean ToolRegistry toolRegistry(ObjectMapper mapper, List<ToolHandler<?>> handlers) { return new ReflectiveToolRegistry(mapper, handlers); }
    @Bean PolicyEngine policyEngine() { return new DefaultPolicyEngine(false); }
    @Bean AgentOperations agentOperations(SqliteCheckpointStore store, ToolRegistry tools, PolicyEngine policy,
            ObjectMapper mapper, HttpClient http) { return new DefaultAgentOperations(store, tools, policy, mapper, http); }
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
