package io.github.moneymaker26754.agentforge.infrastructure.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.moneymaker26754.agentforge.core.RiskLevel;
import io.github.moneymaker26754.agentforge.core.ToolDefinition;
import io.github.moneymaker26754.agentforge.core.ToolDescriptor;
import io.github.moneymaker26754.agentforge.core.ToolRegistry;
import io.github.moneymaker26754.agentforge.core.ToolResult;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Bridges one or more MCP servers over stdio into the AgentForge {@link ToolRegistry} API.
 *
 * <p>For every configured server a subprocess is launched by the MCP SDK
 * ({@link StdioClientTransport}), the client is initialized, and each tool exposed by the
 * server is registered under the name {@code server.name + "." + tool.name}. Tool
 * executions are blocking calls on the synchronous MCP client; transport or protocol
 * failures are translated into {@code MCP_ERROR} tool results instead of being thrown.</p>
 *
 * <p>Initialization failures are fatal: the constructor throws
 * {@link IllegalStateException} naming the failing server, after closing any clients and
 * subprocesses already started.</p>
 */
public final class McpToolBridge implements ToolRegistry, AutoCloseable {

    private static final McpJsonMapper MCP_MAPPER = McpJsonDefaults.getMapper();
    private static final String DEFAULT_INPUT_SCHEMA = "{\"type\":\"object\",\"additionalProperties\":true}";
    private static final TypeReference<Map<String, Object>> ARGUMENTS_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper mapper;
    private final Map<String, ToolDefinition> definitions = new LinkedHashMap<>();
    private final List<McpSyncClient> clients = new ArrayList<>();

    public McpToolBridge(ObjectMapper mapper, List<McpServerConfig> servers) {
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
        Objects.requireNonNull(servers, "servers must not be null");
        try {
            for (McpServerConfig server : servers) {
                connect(server);
            }
        } catch (RuntimeException failure) {
            close();
            throw failure;
        }
    }

    private void connect(McpServerConfig server) {
        McpSyncClient client = createClient(server);
        try {
            client.initialize();
            List<McpSchema.Tool> tools = client.listTools().tools();
            for (McpSchema.Tool tool : tools) {
                registerTool(client, server, tool);
            }
            clients.add(client);
        } catch (RuntimeException failure) {
            client.closeGracefully();
            throw new IllegalStateException(
                    "Failed to initialize MCP server '" + server.name() + "': " + failure.getMessage(), failure);
        }
    }

    private McpSyncClient createClient(McpServerConfig server) {
        List<String> command = server.command();
        ServerParameters parameters = ServerParameters.builder(command.get(0))
                .args(command.subList(1, command.size()))
                .env(server.env())
                .build();
        StdioClientTransport transport = new StdioClientTransport(parameters, MCP_MAPPER);
        return McpClient.sync(transport)
                .clientInfo(new McpSchema.Implementation("agentforge", "0.2.0"))
                .build();
    }

    private void registerTool(McpSyncClient client, McpServerConfig server, McpSchema.Tool tool) {
        String name = server.name() + "." + tool.name();
        if (definitions.containsKey(name)) {
            throw new IllegalStateException("Duplicate MCP tool name: " + name);
        }
        String description = tool.description() != null ? tool.description() : tool.name();
        String schema = tool.inputSchema() == null ? DEFAULT_INPUT_SCHEMA : serializeSchema(tool.inputSchema());
        ToolDefinition definition = new ToolDefinition(name, description, schema, RiskLevel.NETWORK, false,
                (json, context) -> callTool(client, tool.name(), json));
        definitions.put(name, definition);
    }

    private String serializeSchema(McpSchema.JsonSchema schema) {
        try {
            return MCP_MAPPER.writeValueAsString(schema);
        } catch (IOException failure) {
            return DEFAULT_INPUT_SCHEMA;
        }
    }

    private ToolResult callTool(McpSyncClient client, String toolName, String argumentsJson) {
        try {
            JsonNode node = mapper.readTree(argumentsJson);
            Map<String, Object> arguments = mapper.convertValue(node, ARGUMENTS_TYPE);
            McpSchema.CallToolResult result = client.callTool(new McpSchema.CallToolRequest(toolName, arguments));
            String text = extractText(result);
            if (Boolean.TRUE.equals(result.isError())) {
                return ToolResult.failure("MCP_ERROR", text);
            }
            return ToolResult.success(text);
        } catch (IOException | RuntimeException failure) {
            return ToolResult.failure("MCP_ERROR",
                    "MCP tool '" + toolName + "' failed: " + failure.getMessage());
        }
    }

    private static String extractText(McpSchema.CallToolResult result) {
        StringBuilder text = new StringBuilder();
        for (McpSchema.Content content : result.content()) {
            if (content instanceof McpSchema.TextContent textContent && textContent.text() != null) {
                text.append(textContent.text());
            }
        }
        return text.toString();
    }

    @Override
    public Optional<ToolDefinition> find(String name) {
        return Optional.ofNullable(definitions.get(name));
    }

    @Override
    public List<ToolDescriptor> descriptors() {
        return definitions.values().stream()
                .map(definition -> new ToolDescriptor(
                        definition.name(), definition.description(), definition.jsonSchema()))
                .toList();
    }

    /**
     * Closes every MCP client, which shuts the stdio transports down and destroys the
     * server subprocesses (the SDK's graceful close terminates the process and awaits its
     * exit).
     */
    @Override
    public void close() {
        for (McpSyncClient client : clients) {
            client.closeGracefully();
        }
        clients.clear();
    }
}
