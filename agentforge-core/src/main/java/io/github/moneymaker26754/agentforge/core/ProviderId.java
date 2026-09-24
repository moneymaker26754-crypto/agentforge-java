package io.github.moneymaker26754.agentforge.core;

public enum ProviderId {
    DEEPSEEK("deepseek-flash"),
    OLLAMA("qwen2.5-coder:7b");

    private final String defaultModel;

    ProviderId(String defaultModel) {
        this.defaultModel = defaultModel;
    }

    public String defaultModel() {
        return defaultModel;
    }
}

