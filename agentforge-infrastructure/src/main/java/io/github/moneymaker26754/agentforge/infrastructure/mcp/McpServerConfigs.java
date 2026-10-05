package io.github.moneymaker26754.agentforge.infrastructure.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;

/**
 * Loads the optional MCP server configuration from the {@code AGENTFORGE_MCP_SERVERS} environment
 * variable (a JSON array of {@code {"name":..., "command":[...], "env":{...}}}). When the variable
 * is absent or blank the result is an empty list and the bridge starts no subprocesses.
 */
public final class McpServerConfigs {
    private static final TypeReference<List<McpServerConfig>> LIST_TYPE = new TypeReference<>() {};

    private McpServerConfigs() {}

    public static List<McpServerConfig> fromEnvironment(ObjectMapper mapper) {
        return fromJson(mapper, System.getenv("AGENTFORGE_MCP_SERVERS"));
    }

    public static List<McpServerConfig> fromJson(ObjectMapper mapper, String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<McpServerConfig> servers = mapper.readValue(json, LIST_TYPE);
            return servers == null ? List.of() : List.copyOf(servers);
        } catch (Exception exception) {
            throw new IllegalArgumentException(
                    "AGENTFORGE_MCP_SERVERS is not a valid JSON array of {name, command, env}: "
                            + exception.getMessage(), exception);
        }
    }
}
