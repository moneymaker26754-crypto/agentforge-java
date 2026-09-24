package io.github.moneymaker26754.agentforge.infrastructure.tool;

import io.github.moneymaker26754.agentforge.core.*;
import io.github.moneymaker26754.agentforge.infrastructure.security.WorkspaceGuard;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

@AgentTool(name = "fs_read", description = "Read a UTF-8 text file inside the workspace", risk = RiskLevel.READ, idempotent = true)
public final class FsReadTool implements ToolHandler<FsReadTool.Arguments> {
    public record Arguments(
            @ToolParam(description = "workspace-relative file path", required = true) String path,
            @ToolParam(description = "first one-based line", min = 1, max = 1_000_000) int startLine,
            @ToolParam(description = "maximum lines", min = 1, max = 2000) int maxLines) {}

    @Override public ToolResult execute(Arguments arguments, ExecutionContext context) {
        try {
            var path = new WorkspaceGuard(context.workspace()).resolveExisting(arguments.path());
            if (!Files.isRegularFile(path) || Files.size(path) > 512 * 1024) {
                return ToolResult.failure("FILE_NOT_READABLE", "file is non-regular or larger than 512 KiB");
            }
            String text = Files.readString(path, StandardCharsets.UTF_8);
            if (text.indexOf('\0') >= 0) return ToolResult.failure("BINARY_FILE", "binary files are not supported");
            var lines = text.lines().toList();
            int start = Math.max(1, arguments.startLine()) - 1;
            int limit = arguments.maxLines() <= 0 ? 400 : arguments.maxLines();
            if (start >= lines.size()) return ToolResult.success("");
            return ToolResult.success(String.join(System.lineSeparator(), lines.subList(start, Math.min(lines.size(), start + limit))));
        } catch (Exception exception) {
            return ToolResult.failure("READ_FAILED", exception.getMessage());
        }
    }
}

