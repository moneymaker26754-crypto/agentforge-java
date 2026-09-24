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

public final class DeepSeekModelClient implements ModelClient {
    private final HttpClient client;
    private final URI endpoint;
    private final String apiKey;
    private final ObjectMapper mapper;
    private final double inputPricePerThousand;
    private final double outputPricePerThousand;

    public DeepSeekModelClient(HttpClient client, URI baseUri, String apiKey, ObjectMapper mapper,
            double inputPricePerThousand, double outputPricePerThousand) {
        this.client = client;
        this.endpoint = baseUri.resolve("/chat/completions");
        this.apiKey = apiKey;
        this.mapper = mapper;
        this.inputPricePerThousand = inputPricePerThousand;
        this.outputPricePerThousand = outputPricePerThousand;
    }

    @Override
    public ModelResponse exchange(ChatRequest request, ModelDeltaSink sink) {
        var httpRequest = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofMinutes(10))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(ModelRequestJson.deepSeek(mapper, request)))
                .build();
        try {
            var response = client.send(httpRequest, HttpResponse.BodyHandlers.ofLines());
            if (response.statusCode() / 100 != 2) {
                throw new ModelTransportException("DeepSeek returned HTTP " + response.statusCode());
            }
            var parser = new DeepSeekSseParser(mapper, sink, inputPricePerThousand, outputPricePerThousand);
            try (var lines = response.body()) {
                lines.forEach(parser::accept);
            }
            return parser.finish();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ModelTransportException("DeepSeek request interrupted", exception);
        } catch (java.io.IOException exception) {
            throw new ModelTransportException("DeepSeek request failed", exception);
        }
    }
}

