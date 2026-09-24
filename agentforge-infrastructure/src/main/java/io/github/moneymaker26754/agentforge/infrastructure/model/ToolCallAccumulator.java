package io.github.moneymaker26754.agentforge.infrastructure.model;

import io.github.moneymaker26754.agentforge.core.ToolCall;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

final class ToolCallAccumulator {
    private final Map<Integer, PartialCall> calls = new TreeMap<>();

    void append(int index, String id, String nameFragment, String argumentsFragment, boolean replaceArguments) {
        var partial = calls.computeIfAbsent(index, ignored -> new PartialCall());
        if (id != null && !id.isBlank()) {
            partial.id = id;
        }
        if (nameFragment != null) {
            partial.name.append(nameFragment);
        }
        if (argumentsFragment != null) {
            if (replaceArguments) {
                partial.arguments.setLength(0);
            }
            partial.arguments.append(argumentsFragment);
        }
    }

    List<ToolCall> finish(String idPrefix) {
        var result = new ArrayList<ToolCall>();
        calls.forEach((index, partial) -> result.add(new ToolCall(
                partial.id == null ? idPrefix + index : partial.id,
                index,
                partial.name.toString(),
                partial.arguments.isEmpty() ? "{}" : partial.arguments.toString())));
        return List.copyOf(result);
    }

    private static final class PartialCall {
        private String id;
        private final StringBuilder name = new StringBuilder();
        private final StringBuilder arguments = new StringBuilder();
    }
}

