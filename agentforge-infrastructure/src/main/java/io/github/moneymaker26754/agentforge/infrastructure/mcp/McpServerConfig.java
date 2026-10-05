package io.github.moneymaker26754.agentforge.infrastructure.mcp;

import java.util.List;
import java.util.Map;

/**
 * Configuration of one MCP stdio server bridged into AgentForge.
 *
 * @param name    unique server name used as the prefix of every bridged tool name
 *                ({@code name + "." + tool.name()})
 * @param command command line used to launch the server subprocess; the first element is
 *                the executable, the remaining elements are its arguments
 * @param env     additional environment variables merged into the subprocess environment
 *                (never {@code null}, defaults to an empty map)
 */
public record McpServerConfig(String name, List<String> command, Map<String, String> env) {

    public McpServerConfig {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("MCP server name must not be blank");
        }
        if (command == null || command.isEmpty()) {
            throw new IllegalArgumentException("MCP server command must not be empty: " + name);
        }
        for (String argument : command) {
            if (argument == null || argument.isBlank()) {
                throw new IllegalArgumentException("MCP server command must not contain blank elements: " + name);
            }
        }
        command = List.copyOf(command);
        env = env == null ? Map.of() : Map.copyOf(env);
    }
}
