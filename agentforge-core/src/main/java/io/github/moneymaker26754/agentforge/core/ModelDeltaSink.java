package io.github.moneymaker26754.agentforge.core;

@FunctionalInterface
public interface ModelDeltaSink {
    void accept(ModelDelta delta);

    static ModelDeltaSink noop() {
        return delta -> {};
    }
}

