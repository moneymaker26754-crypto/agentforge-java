package io.github.moneymaker26754.agentforge.infrastructure.sandbox;

import io.github.moneymaker26754.agentforge.core.CommandSpec;
import io.github.moneymaker26754.agentforge.core.ExecutionContext;
import io.github.moneymaker26754.agentforge.core.ExecutionResult;
import io.github.moneymaker26754.agentforge.core.SandboxExecutor;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public final class LocalSandboxExecutor implements SandboxExecutor {
    private final Set<String> allowedExecutables;

    public LocalSandboxExecutor(Set<String> allowedExecutables) {
        this.allowedExecutables = allowedExecutables.stream()
                .map(value -> value.toLowerCase(Locale.ROOT)).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    @Override
    public ExecutionResult execute(CommandSpec command, ExecutionContext context) {
        String executable = java.nio.file.Path.of(command.argv().getFirst()).getFileName().toString().toLowerCase(Locale.ROOT);
        if (!allowedExecutables.contains(executable)) {
            return new ExecutionResult(-1, "", "executable is not allowed: " + executable, false, 0);
        }
        Instant started = Instant.now();
        try {
            Process process = new ProcessBuilder(command.argv()).directory(context.workspace().toFile()).start();
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                Future<String> stdout = executor.submit(() -> readBounded(process.getInputStream(), command.maxOutputBytes()));
                Future<String> stderr = executor.submit(() -> readBounded(process.getErrorStream(), command.maxOutputBytes()));
                boolean finished = process.waitFor(command.timeout().toMillis(), TimeUnit.MILLISECONDS);
                if (!finished) {
                    process.destroy();
                    if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly();
                }
                int exitCode = finished ? process.exitValue() : -1;
                return new ExecutionResult(exitCode, stdout.get(5, TimeUnit.SECONDS), stderr.get(5, TimeUnit.SECONDS),
                        !finished, Duration.between(started, Instant.now()).toMillis());
            }
        } catch (Exception exception) {
            return new ExecutionResult(-1, "", exception.getMessage(), false,
                    Duration.between(started, Instant.now()).toMillis());
        }
    }

    private String readBounded(InputStream input, int limit) throws Exception {
        var output = new ByteArrayOutputStream(Math.min(limit, 8192));
        byte[] buffer = new byte[4096];
        int total = 0;
        int read;
        boolean truncated = false;
        while ((read = input.read(buffer)) >= 0) {
            int accepted = Math.min(read, Math.max(0, limit - total));
            if (accepted > 0) output.write(buffer, 0, accepted);
            total += read;
            if (total > limit) truncated = true;
        }
        String value = output.toString(StandardCharsets.UTF_8);
        return truncated ? value + System.lineSeparator() + "[TRUNCATED]" : value;
    }
}

