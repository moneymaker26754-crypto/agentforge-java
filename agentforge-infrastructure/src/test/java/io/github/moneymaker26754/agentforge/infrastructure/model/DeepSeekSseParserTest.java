package io.github.moneymaker26754.agentforge.infrastructure.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.moneymaker26754.agentforge.core.ModelDelta;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

class DeepSeekSseParserTest {

    @Test
    void assemblesFragmentedContentToolCallsAndUsage() {
        var deltas = new ArrayList<ModelDelta>();
        var parser = new DeepSeekSseParser(new ObjectMapper(), deltas::add, 0.002, 0.008);

        parser.accept("data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"think\",\"tool_calls\":[{\"index\":0,\"id\":\"call-1\",\"function\":{\"name\":\"fs_\",\"arguments\":\"{\\\"pa\"}}]},\"finish_reason\":null}]}");
        parser.accept("data: {\"choices\":[{\"delta\":{\"content\":\"working\",\"tool_calls\":[{\"index\":0,\"function\":{\"name\":\"read\",\"arguments\":\"th\\\":\\\"README.md\\\"}\"}}]},\"finish_reason\":\"tool_calls\"}],\"usage\":{\"prompt_tokens\":1000,\"completion_tokens\":500}}");
        parser.accept("data: [DONE]");

        var response = parser.finish();
        assertThat(response.content()).isEqualTo("working");
        assertThat(response.toolCalls()).singleElement().satisfies(call -> {
            assertThat(call.id()).isEqualTo("call-1");
            assertThat(call.name()).isEqualTo("fs_read");
            assertThat(call.argumentsJson()).isEqualTo("{\"path\":\"README.md\"}");
        });
        assertThat(response.usage().inputTokens()).isEqualTo(1000);
        assertThat(response.usage().outputTokens()).isEqualTo(500);
        assertThat(response.usage().costCny()).isEqualTo(0.006);
        assertThat(deltas).contains(new ModelDelta("", "think"), new ModelDelta("working", ""));
    }

    @Test
    void rejectsMalformedDataFrame() {
        var parser = new DeepSeekSseParser(new ObjectMapper(), delta -> {}, 0, 0);

        assertThatThrownBy(() -> parser.accept("data: {not-json}"))
                .isInstanceOf(ModelProtocolException.class)
                .hasMessageContaining("DeepSeek SSE");
    }
}

