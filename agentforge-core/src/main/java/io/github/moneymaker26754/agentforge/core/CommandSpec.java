package io.github.moneymaker26754.agentforge.core;

import java.time.Duration;
import java.util.List;

public record CommandSpec(List<String> argv, Duration timeout, int maxOutputBytes) {
    public CommandSpec {
        argv = List.copyOf(argv);
        if (argv.isEmpty() || argv.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("argv must contain non-blank values");
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative() || maxOutputBytes < 1) {
            throw new IllegalArgumentException("timeout and output limit must be positive");
        }
    }
}

