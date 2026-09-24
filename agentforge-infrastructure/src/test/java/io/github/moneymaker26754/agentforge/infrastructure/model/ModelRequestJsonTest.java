package io.github.moneymaker26754.agentforge.infrastructure.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.moneymaker26754.agentforge.core.ChatMessage;
import io.github.moneymaker26754.agentforge.core.ChatRequest;
import java.util.List;
import org.junit.jupiter.api.Test;

class ModelRequestJsonTest {
    @Test
    void ollamaToolResultUsesFunctionNameRatherThanCallId() throws Exception {
        var mapper = new ObjectMapper();
        var request = new ChatRequest("model", List.of(ChatMessage.tool("call-7", "echo", "ok")), List.of());

        var json = mapper.readTree(ModelRequestJson.ollama(mapper, request));

        assertThat(json.at("/messages/0/tool_name").asText()).isEqualTo("echo");
        assertThat(json.at("/messages/0/tool_call_id").isMissingNode()).isTrue();
    }
}
