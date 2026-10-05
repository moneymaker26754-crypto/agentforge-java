package io.github.moneymaker26754.agentforge.infrastructure.mcp.fixture;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Real MCP stdio server used as the fixture of {@code McpToolBridgeTest}. Exposes two
 * tools, {@code echo} and {@code add}, over stdio and serves requests until the bridging
 * client closes the transport and the parent process destroys this JVM.
 *
 * <p>When the {@code fixture.pid.file} system property is set, the current process id is
 * written to that file on startup so the test can verify that closing the bridge
 * terminates the server process.</p>
 */
public final class McpFixtureServer {

    private static final String ECHO_SCHEMA =
            "{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}},\"required\":[\"text\"]}";
    private static final String ADD_SCHEMA =
            "{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"number\"},\"b\":{\"type\":\"number\"}},"
                    + "\"required\":[\"a\",\"b\"]}";

    private McpFixtureServer() {
    }

    public static void main(String[] args) throws InterruptedException, IOException {
        recordPid();
        McpJsonMapper mapper = McpJsonDefaults.getMapper();
        McpSchema.Tool echoTool = McpSchema.Tool.builder()
                .name("echo")
                .description("Echoes the given text back")
                .inputSchema(mapper, ECHO_SCHEMA)
                .build();
        McpSchema.Tool addTool = McpSchema.Tool.builder()
                .name("add")
                .description("Adds two numbers")
                .inputSchema(mapper, ADD_SCHEMA)
                .build();
        McpServer.sync(new StdioServerTransportProvider(mapper))
                .serverInfo("mcp-fixture", "1.0.0")
                .toolCall(echoTool, McpFixtureServer::echo)
                .toolCall(addTool, McpFixtureServer::add)
                .build();
        // Serve on stdio until the client destroys this process.
        Thread.currentThread().join();
    }

    private static McpSchema.CallToolResult echo(McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        Object text = request.arguments().get("text");
        if (!(text instanceof String value)) {
            return McpSchema.CallToolResult.builder()
                    .isError(true)
                    .addTextContent("missing or invalid argument: text")
                    .build();
        }
        return McpSchema.CallToolResult.builder().addTextContent(value).build();
    }

    private static McpSchema.CallToolResult add(McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        Object a = request.arguments().get("a");
        Object b = request.arguments().get("b");
        if (!(a instanceof Number left) || !(b instanceof Number right)) {
            return McpSchema.CallToolResult.builder()
                    .isError(true)
                    .addTextContent("missing or invalid arguments: a and b must be numbers")
                    .build();
        }
        double sum = left.doubleValue() + right.doubleValue();
        return McpSchema.CallToolResult.builder().addTextContent(String.valueOf(sum)).build();
    }

    private static void recordPid() throws IOException {
        String pidFile = System.getProperty("fixture.pid.file");
        if (pidFile != null && !pidFile.isBlank()) {
            Files.writeString(Path.of(pidFile), Long.toString(ProcessHandle.current().pid()));
        }
    }
}
