package io.github.moneymaker26754.agentforge.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class SystemDiagnosticProviderTest {
    @Test
    void probesOllamaOverHttpAndReportsDeepSeekConfiguration() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/version", exchange -> {
            byte[] body = "{\"version\":\"test\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/version");
            var provider = new SystemDiagnosticProvider(HttpClient.newHttpClient(), endpoint,
                    Duration.ofSeconds(2), () -> "configured-key");

            var diagnostics = provider.diagnose();

            assertThat(diagnostics).anySatisfy(item -> {
                assertThat(item.name()).isEqualTo("ollama");
                assertThat(item.available()).isTrue();
            }).anySatisfy(item -> {
                assertThat(item.name()).isEqualTo("deepseek");
                assertThat(item.available()).isTrue();
                assertThat(item.required()).isFalse();
            });
        } finally {
            server.stop(0);
        }
    }

    @Test
    void missingDeepSeekKeyIsAnOptionalWarning() {
        var provider = new SystemDiagnosticProvider(HttpClient.newHttpClient(),
                URI.create("http://127.0.0.1:1/api/version"), Duration.ofMillis(100), () -> " ");

        assertThat(provider.diagnose()).anySatisfy(item -> {
            assertThat(item.name()).isEqualTo("deepseek");
            assertThat(item.available()).isFalse();
            assertThat(item.required()).isFalse();
        });
    }
}
