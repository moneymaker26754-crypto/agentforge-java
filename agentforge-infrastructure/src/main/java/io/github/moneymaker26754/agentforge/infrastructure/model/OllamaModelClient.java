package io.github.moneymaker26754.agentforge.infrastructure.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.moneymaker26754.agentforge.core.ChatRequest;
import io.github.moneymaker26754.agentforge.core.ModelClient;
import io.github.moneymaker26754.agentforge.core.ModelDeltaSink;
import io.github.moneymaker26754.agentforge.core.ModelResponse;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public final class OllamaModelClient implements ModelClient {
    private final HttpClient client;
    private final URI endpoint;
    private final ObjectMapper mapper;

    public OllamaModelClient(HttpClient client, URI baseUri, ObjectMapper mapper) {
        this.client = client;
        this.endpoint = baseUri.resolve("/api/chat");
        this.mapper = mapper;
    }

    @Override
    public ModelResponse exchange(ChatRequest request, ModelDeltaSink sink) {
        var httpRequest = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofMinutes(20))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(ModelRequestJson.ollama(mapper, request)))
                .build();
        try {
            var response = client.send(httpRequest, HttpResponse.BodyHandlers.ofLines());
            if (response.statusCode() / 100 != 2) {
                throw new ModelTransportException("Ollama returned HTTP " + response.statusCode());
            }
            var parser = new OllamaNdjsonParser(mapper, sink);
            try (var lines = response.body()) {
                lines.forEach(parser::accept);
            }
            return parser.finish();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ModelTransportException("Ollama request interrupted", exception);
        } catch (java.io.IOException exception) {
            throw new ModelTransportException("Ollama request failed", exception);
        }
    }
}

