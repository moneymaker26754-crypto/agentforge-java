package io.github.moneymaker26754.agentforge.infrastructure.tool;

import io.github.moneymaker26754.agentforge.core.*;
import io.github.moneymaker26754.agentforge.infrastructure.security.WorkspaceGuard;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

@AgentTool(name = "fs_patch", description = "Replace one exact text block in a workspace file", risk = RiskLevel.WRITE)
public final class FsPatchTool implements ToolHandler<FsPatchTool.Arguments> {
    public record Arguments(
            @ToolParam(description = "workspace-relative file", required = true) String path,
            @ToolParam(description = "exact existing text", required = true) String expected,
            @ToolParam(description = "replacement text", required = true) String replacement) {}

    @Override public ToolResult execute(Arguments arguments, ExecutionContext context) {
        try {
            if (arguments.expected().isEmpty() || arguments.expected().length() > 1024 * 1024
                    || arguments.replacement().length() > 1024 * 1024) {
                return ToolResult.failure("PATCH_TOO_LARGE", "patch blocks must be between 1 byte and 1 MiB");
            }
            var path = new WorkspaceGuard(context.workspace()).resolveExisting(arguments.path());
            String content = Files.readString(path, StandardCharsets.UTF_8);
            if (content.indexOf('\0') >= 0) return ToolResult.failure("BINARY_FILE", "binary files are not supported");
            int first = content.indexOf(arguments.expected());
            if (first < 0) return ToolResult.failure("PATCH_MISMATCH", "expected text was not found");
            if (content.indexOf(arguments.expected(), first + 1) >= 0) return ToolResult.failure("PATCH_AMBIGUOUS", "expected text appears more than once");
            String updated = content.substring(0, first) + arguments.replacement() + content.substring(first + arguments.expected().length());
            Files.writeString(path, updated, StandardCharsets.UTF_8);
            return ToolResult.success("patched " + arguments.path());
        } catch (Exception exception) {
            return ToolResult.failure("PATCH_FAILED", exception.getMessage());
        }
    }
}

