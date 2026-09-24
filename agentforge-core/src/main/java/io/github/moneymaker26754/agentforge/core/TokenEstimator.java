package io.github.moneymaker26754.agentforge.core;

@FunctionalInterface
public interface TokenEstimator {
    int estimate(String text);
}

