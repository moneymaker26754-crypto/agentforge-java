package io.github.moneymaker26754.agentforge.cli;

public record Diagnostic(String name, boolean available, String detail) {
    public boolean required() {
        return !"ollama".equalsIgnoreCase(name) && !"deepseek".equalsIgnoreCase(name);
    }
}
