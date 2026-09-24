package io.github.moneymaker26754.agentforge.core;

public interface ModelClient {
    ModelResponse exchange(ChatRequest request, ModelDeltaSink sink);
}

