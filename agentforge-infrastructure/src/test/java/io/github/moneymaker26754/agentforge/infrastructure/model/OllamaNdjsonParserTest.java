package io.github.moneymaker26754.agentforge.infrastructure.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.moneymaker26754.agentforge.core.ModelDelta;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

class OllamaNdjsonParserTest {

    @Test
    void assemblesMultipleToolCallsAndMissingUsageDefaultsToZero() {
        var deltas = new ArrayList<ModelDelta>();
        var parser = new OllamaNdjsonParser(new ObjectMapper(), deltas::add);

        parser.accept("{\"message\":{\"thinking\":\"inspect\",\"content\":\"\",\"tool_calls\":[{\"function\":{\"index\":0,\"name\":\"fs_read\",\"arguments\":{\"path\":\"pom.xml\"}}},{\"function\":{\"index\":1,\"name\":\"git_diff\",\"arguments\":{}}}]},\"done\":false}");
        parser.accept("{\"message\":{\"content\":\"done\"},\"done\":true,\"done_reason\":\"stop\"}");

        var response = parser.finish();
        assertThat(response.content()).isEqualTo("done");
        assertThat(response.toolCalls()).extracting(call -> call.name())
                .containsExactly("fs_read", "git_diff");
        assertThat(response.toolCalls().get(0).argumentsJson()).isEqualTo("{\"path\":\"pom.xml\"}");
        assertThat(response.usage().inputTokens()).isZero();
        assertThat(deltas).contains(new ModelDelta("", "inspect"), new ModelDelta("done", ""));
    }

    @Test
    void readsOllamaTokenCounters() {
        var parser = new OllamaNdjsonParser(new ObjectMapper(), delta -> {});
        parser.accept("{\"message\":{\"content\":\"ok\"},\"done\":true,\"prompt_eval_count\":12,\"eval_count\":4}");

        var response = parser.finish();

        assertThat(response.usage().inputTokens()).isEqualTo(12);
        assertThat(response.usage().outputTokens()).isEqualTo(4);
    }

    @Test
    void rejectsMalformedNdjsonFrame() {
        var parser = new OllamaNdjsonParser(new ObjectMapper(), delta -> {});

        assertThatThrownBy(() -> parser.accept("no-json"))
                .isInstanceOf(ModelProtocolException.class)
                .hasMessageContaining("Ollama NDJSON");
    }
}

