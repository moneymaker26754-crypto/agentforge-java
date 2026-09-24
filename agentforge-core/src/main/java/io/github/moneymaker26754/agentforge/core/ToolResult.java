package io.github.moneymaker26754.agentforge.core;

public record ToolResult(boolean success, String content, String errorCode) {
    public static ToolResult success(String content) {
        return new ToolResult(true, content == null ? "" : content, null);
    }

    public static ToolResult failure(String errorCode, String message) {
        return new ToolResult(false, message == null ? "" : message, errorCode);
    }
}

