package io.github.moneymaker26754.agentforge.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class CoreValueValidationTest {
    @Test void commandSpecValidatesEveryBoundary() {
        assertThatThrownBy(() -> new CommandSpec(List.of(), Duration.ofSeconds(1), 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CommandSpec(List.of(""), Duration.ofSeconds(1), 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CommandSpec(List.of("ok"), null, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CommandSpec(List.of("ok"), Duration.ZERO, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CommandSpec(List.of("ok"), Duration.ofSeconds(-1), 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CommandSpec(List.of("ok"), Duration.ofSeconds(1), 0)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new CommandSpec(List.of("git", "status"), Duration.ofSeconds(1), 10).argv()).containsExactly("git", "status");
    }

    @Test void budgetsAndUsageRejectInvalidValues() {
        assertThatThrownBy(() -> new RunBudget(0, Duration.ofSeconds(1), 1, 1, 0, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RunBudget(1, Duration.ZERO, 1, 1, 0, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RunBudget(1, Duration.ofSeconds(1), 0, 1, 0, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RunBudget(1, Duration.ofSeconds(1), 1, 0, 0, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RunBudget(1, Duration.ofSeconds(1), 1, 1, -1, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RunBudget(1, Duration.ofSeconds(1), 1, 1, 0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Usage(-1, 0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Usage(0, -1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Usage(0, 0, -1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void messagesResponsesAndResultsNormalizeNullableProviderValues() {
        var response = new ModelResponse(null, null, null, null);
        assertThat(response.content()).isEmpty();
        assertThat(response.toolCalls()).isEmpty();
        assertThat(response.usage()).isEqualTo(Usage.zero());
        assertThat(response.finishReason()).isEqualTo("unknown");
        assertThat(ToolResult.success(null).content()).isEmpty();
        assertThat(ToolResult.failure("E", null).content()).isEmpty();
        assertThat(ChatMessage.user(null).content()).isEmpty();
    }

    @Test void identifiersAndRequestsValidateRequiredFields() {
        assertThatThrownBy(() -> new SessionId(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SessionId(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RunRequest(Path.of("."), " ", ProviderId.OLLAMA, SandboxMode.LOCAL, RunBudget.defaults()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new SessionId("s").toString()).isEqualTo("s");
        assertThat(SessionId.random().value()).isNotBlank();
    }
}
