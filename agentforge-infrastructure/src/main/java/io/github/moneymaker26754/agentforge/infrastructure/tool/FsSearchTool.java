package io.github.moneymaker26754.agentforge.infrastructure.tool;

import io.github.moneymaker26754.agentforge.core.*;
import io.github.moneymaker26754.agentforge.infrastructure.security.WorkspaceGuard;
import java.nio.file.Files;
import java.util.ArrayList;

@AgentTool(name = "fs_search", description = "Search UTF-8 workspace files for literal text", risk = RiskLevel.READ, idempotent = true)
public final class FsSearchTool implements ToolHandler<FsSearchTool.Arguments> {
    public record Arguments(
            @ToolParam(description = "literal text", required = true) String query,
            @ToolParam(description = "workspace-relative root", required = true) String path,
            @ToolParam(description = "maximum matches", min = 1, max = 500) int maxResults) {}

    @Override public ToolResult execute(Arguments arguments, ExecutionContext context) {
        try {
            var root = new WorkspaceGuard(context.workspace()).resolveExisting(arguments.path());
            int limit = arguments.maxResults() <= 0 ? 100 : arguments.maxResults();
            var results = new ArrayList<String>();
            try (var paths = Files.walk(root)) {
                for (var path : paths.filter(Files::isRegularFile).toList()) {
                    if (results.size() >= limit) break;
                    if (Files.size(path) > 512 * 1024) continue;
                    try {
                        var lines = Files.readAllLines(path);
                        for (int index = 0; index < lines.size() && results.size() < limit; index++) {
                            if (lines.get(index).contains(arguments.query())) {
                                results.add(context.workspace().relativize(path) + ":" + (index + 1) + ":" + lines.get(index));
                            }
                        }
                    } catch (Exception ignored) {
                        // Unreadable and non-text files do not abort a repository search.
                    }
                }
            }
            return ToolResult.success(String.join(System.lineSeparator(), results));
        } catch (Exception exception) {
            return ToolResult.failure("SEARCH_FAILED", exception.getMessage());
        }
    }
}

