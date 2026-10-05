package io.github.moneymaker26754.agentforge.infrastructure.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.moneymaker26754.agentforge.core.RiskLevel;
import io.github.moneymaker26754.agentforge.core.ToolDescriptor;
import io.github.moneymaker26754.agentforge.core.ToolResult;
import io.github.moneymaker26754.agentforge.infrastructure.mcp.fixture.McpFixtureServer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class McpToolBridgeTest {

    private static final String FIXTURE_MAIN = McpFixtureServer.class.getName();

    @Test
    void bridgesStdioMcpToolsAndTerminatesServersOnClose(@TempDir Path tempDir) throws Exception {
        String classpath = System.getProperty("java.class.path");
        assumeTrue(classpath != null && !classpath.isBlank(), "surefire classpath property is unavailable");
        String java = javaExecutable();
        Path pidFile = tempDir.resolve("fixture.pid");

        McpToolBridge bridge = new McpToolBridge(new ObjectMapper(), List.of(
                new McpServerConfig("demo", fixtureCommand(java, classpath, pidFile), Map.of())));

        try {
            assertThat(bridge.find("demo.echo")).isPresent();
            assertThat(bridge.find("demo.add")).isPresent();
            assertThat(bridge.descriptors()).extracting(ToolDescriptor::name)
                    .containsExactlyInAnyOrder("demo.echo", "demo.add");

            var echo = bridge.find("demo.echo").orElseThrow();
            assertThat(echo.riskLevel()).isEqualTo(RiskLevel.NETWORK);
            assertThat(echo.idempotent()).isFalse();
            assertThat(echo.jsonSchema()).contains("\"text\"");

            ToolResult echoed = echo.executor().execute("{\"text\":\"hello mcp\"}", null);
            assertThat(echoed).isEqualTo(ToolResult.success("hello mcp"));

            var add = bridge.find("demo.add").orElseThrow();
            ToolResult sum = add.executor().execute("{\"a\":2.5,\"b\":4.0}", null);
            assertThat(sum).isEqualTo(ToolResult.success("6.5"));

            ToolResult invalidArguments = add.executor().execute("{}", null);
            assertThat(invalidArguments.success()).isFalse();
            assertThat(invalidArguments.errorCode()).isEqualTo("MCP_ERROR");
            assertThat(invalidArguments.content()).contains("numbers");

            ToolResult malformedJson = echo.executor().execute("{not json", null);
            assertThat(malformedJson.success()).isFalse();
            assertThat(malformedJson.errorCode()).isEqualTo("MCP_ERROR");
        } finally {
            bridge.close();
        }

        long pid = Long.parseLong(Files.readString(pidFile).trim());
        assertThat(awaitTermination(pid, 10_000))
                .as("fixture process should terminate after bridge close")
                .isTrue();

        ToolResult afterClose = bridge.find("demo.echo").orElseThrow().executor().execute("{\"text\":\"x\"}", null);
        assertThat(afterClose.success()).isFalse();
        assertThat(afterClose.errorCode()).isEqualTo("MCP_ERROR");
    }

    private static List<String> fixtureCommand(String java, String classpath, Path pidFile) {
        return List.of(java, "-Dfixture.pid.file=" + pidFile, "-cp", classpath, FIXTURE_MAIN);
    }

    private static String javaExecutable() {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        return Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java").toString();
    }

    private static boolean awaitTermination(long pid, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (ProcessHandle.of(pid).map(handle -> !handle.isAlive()).orElse(true)) {
                return true;
            }
            Thread.sleep(100);
        }
        return false;
    }
}
