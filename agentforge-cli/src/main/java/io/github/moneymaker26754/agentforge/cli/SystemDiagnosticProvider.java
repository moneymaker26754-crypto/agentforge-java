package io.github.moneymaker26754.agentforge.cli;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public final class SystemDiagnosticProvider implements DiagnosticProvider {
    private final HttpClient httpClient;
    private final URI ollamaVersionEndpoint;
    private final Duration httpTimeout;
    private final Supplier<String> deepSeekKey;

    public SystemDiagnosticProvider() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build(),
                URI.create(environment("OLLAMA_BASE_URL", "http://localhost:11434")).resolve("/api/version"),
                Duration.ofSeconds(3), () -> System.getenv("DEEPSEEK_API_KEY"));
    }

    SystemDiagnosticProvider(HttpClient httpClient, URI ollamaVersionEndpoint, Duration httpTimeout,
            Supplier<String> deepSeekKey) {
        this.httpClient = httpClient;
        this.ollamaVersionEndpoint = ollamaVersionEndpoint;
        this.httpTimeout = httpTimeout;
        this.deepSeekKey = deepSeekKey;
    }

    @Override public List<Diagnostic> diagnose() {
        var result = new ArrayList<Diagnostic>();
        int feature = Runtime.version().feature();
        result.add(new Diagnostic("java", feature >= 21, "Java " + feature));
        result.add(process("docker", List.of("docker", "info", "--format", "{{.ServerVersion}}"), true));
        result.add(ollama());
        String key = deepSeekKey.get();
        result.add(new Diagnostic("deepseek", key != null && !key.isBlank(),
                key != null && !key.isBlank() ? "configured" : "DEEPSEEK_API_KEY is not set"));
        return List.copyOf(result);
    }

    private Diagnostic ollama() {
        try {
            var request = HttpRequest.newBuilder(ollamaVersionEndpoint).timeout(httpTimeout).GET().build();
            int status = httpClient.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            boolean available = status / 100 == 2;
            return new Diagnostic("ollama", available,
                    available ? "available" : "HTTP " + status + " from " + ollamaVersionEndpoint);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new Diagnostic("ollama", false, "probe interrupted");
        } catch (Exception exception) {
            return new Diagnostic("ollama", false, "optional provider unavailable");
        }
    }

    private Diagnostic process(String name, List<String> argv, boolean required) {
        try {
            Process process = new ProcessBuilder(argv).redirectErrorStream(true).start();
            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            if (!finished) process.destroyForcibly();
            boolean available = finished && process.exitValue() == 0;
            String detail = available ? "available" : (required ? "daemon unavailable" : "optional provider unavailable");
            return new Diagnostic(name, available, detail);
        } catch (Exception exception) {
            return new Diagnostic(name, false, exception.getMessage());
        }
    }

    private static String environment(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
