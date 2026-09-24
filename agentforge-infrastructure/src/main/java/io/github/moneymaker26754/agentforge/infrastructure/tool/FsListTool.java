package io.github.moneymaker26754.agentforge.infrastructure.tool;

import io.github.moneymaker26754.agentforge.core.*;
import io.github.moneymaker26754.agentforge.infrastructure.security.WorkspaceGuard;
import java.nio.file.Files;

@AgentTool(name = "fs_list", description = "List files below a workspace directory", risk = RiskLevel.READ, idempotent = true)
public final class FsListTool implements ToolHandler<FsListTool.Arguments> {
    public record Arguments(
            @ToolParam(description = "workspace-relative directory", required = true) String path,
            @ToolParam(description = "maximum entries", min = 1, max = 2000) int maxEntries) {}

    @Override public ToolResult execute(Arguments arguments, ExecutionContext context) {
        try {
            var root = new WorkspaceGuard(context.workspace()).resolveExisting(arguments.path());
            int limit = arguments.maxEntries() <= 0 ? 200 : arguments.maxEntries();
            try (var stream = Files.walk(root, 4)) {
                String value = stream.limit(limit)
                        .map(path -> context.workspace().toAbsolutePath().normalize().relativize(path.toAbsolutePath().normalize()).toString())
                        .sorted().reduce((left, right) -> left + System.lineSeparator() + right).orElse("");
                return ToolResult.success(value);
            }
        } catch (Exception exception) {
            return ToolResult.failure("LIST_FAILED", exception.getMessage());
        }
    }
}

