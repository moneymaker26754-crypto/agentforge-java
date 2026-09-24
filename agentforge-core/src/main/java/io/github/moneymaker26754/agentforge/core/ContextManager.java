package io.github.moneymaker26754.agentforge.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class ContextManager {
    private final TokenEstimator estimator;
    private final int modelContextTokens;
    private final double compressionThreshold;
    private final int recentMessages;

    public ContextManager(TokenEstimator estimator, int modelContextTokens, double compressionThreshold,
            int recentMessages) {
        this.estimator = Objects.requireNonNull(estimator, "estimator");
        if (modelContextTokens < 1 || compressionThreshold <= 0 || compressionThreshold > 1 || recentMessages < 1) {
            throw new IllegalArgumentException("invalid context limits");
        }
        this.modelContextTokens = modelContextTokens;
        this.compressionThreshold = compressionThreshold;
        this.recentMessages = recentMessages;
    }

    public ContextPreparation prepare(List<ChatMessage> rawHistory) {
        var immutable = List.copyOf(rawHistory);
        int before = estimate(immutable);
        if (before <= modelContextTokens * compressionThreshold || immutable.size() <= recentMessages + 2) {
            return new ContextPreparation(immutable, false, before, before);
        }

        var prepared = new ArrayList<ChatMessage>();
        immutable.stream().filter(message -> message.role() == ChatRole.SYSTEM).findFirst().ifPresent(prepared::add);
        immutable.stream().filter(message -> message.role() == ChatRole.USER).findFirst().ifPresent(prepared::add);

        int recentStart = Math.max(2, immutable.size() - recentMessages);
        var omitted = immutable.subList(Math.min(2, immutable.size()), recentStart);
        String joined = omitted.stream()
                .map(message -> message.role() + ":" + message.content())
                .reduce((left, right) -> left + " | " + right)
                .orElse("no earlier messages");
        int summaryCharacters = Math.max(32, modelContextTokens);
        String summary = joined.substring(0, Math.min(summaryCharacters, joined.length()));
        prepared.add(ChatMessage.system("[context-summary] " + summary));
        prepared.addAll(immutable.subList(recentStart, immutable.size()));
        return new ContextPreparation(List.copyOf(prepared), true, before, estimate(prepared));
    }

    private int estimate(List<ChatMessage> messages) {
        return messages.stream().mapToInt(message -> estimator.estimate(message.content())).sum();
    }
}

