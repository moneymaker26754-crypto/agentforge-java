package io.github.moneymaker26754.agentforge.infrastructure.tool;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.moneymaker26754.agentforge.core.ExecutionContext;
import io.github.moneymaker26754.agentforge.core.RiskLevel;
import io.github.moneymaker26754.agentforge.core.SandboxMode;
import io.github.moneymaker26754.agentforge.core.SessionId;
import io.github.moneymaker26754.agentforge.core.ToolHandler;
import io.github.moneymaker26754.agentforge.core.ToolResult;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReflectiveToolRegistryTest {

    @Test
    void discoversAnnotatedHandlerAndGeneratesClosedSchema() {
        var registry = new ReflectiveToolRegistry(new ObjectMapper(), List.of(new EchoTool()));

        var descriptor = registry.descriptors().getFirst();

        assertThat(descriptor.name()).isEqualTo("echo");
        assertThat(descriptor.parametersJsonSchema())
                .contains("\"type\":\"object\"")
                .contains("\"additionalProperties\":false")
                .contains("\"required\":[\"text\"]")
                .contains("say this text");
    }

    @Test
    void bindsValidJsonToRecordAndExecutesRealHandler() {
        var registry = new ReflectiveToolRegistry(new ObjectMapper(), List.of(new EchoTool()));
        var definition = registry.find("echo").orElseThrow();

        ToolResult result = definition.executor().execute("{\"text\":\"hello\",\"uppercase\":true}",
                context());

        assertThat(result).isEqualTo(ToolResult.success("HELLO"));
    }

    @Test
    void rejectsMissingRequiredAndUnknownArgumentsBeforeHandlerRuns() {
        var tool = new EchoTool();
        var registry = new ReflectiveToolRegistry(new ObjectMapper(), List.of(tool));
        var definition = registry.find("echo").orElseThrow();

        ToolResult missing = definition.executor().execute("{\"uppercase\":true}", context());
        ToolResult unknown = definition.executor().execute("{\"text\":\"x\",\"extra\":1}", context());

        assertThat(missing.success()).isFalse();
        assertThat(missing.errorCode()).isEqualTo("INVALID_ARGUMENTS");
        assertThat(unknown.success()).isFalse();
        assertThat(tool.calls).isZero();
    }

    private ExecutionContext context() {
        return new ExecutionContext(new SessionId("test"), Path.of("."), SandboxMode.LOCAL);
    }

    @AgentTool(name = "echo", description = "Echo text", risk = RiskLevel.READ, idempotent = true)
    static final class EchoTool implements ToolHandler<EchoArguments> {
        int calls;

        @Override
        public ToolResult execute(EchoArguments arguments, ExecutionContext context) {
            calls++;
            return ToolResult.success(arguments.uppercase() ? arguments.text().toUpperCase() : arguments.text());
        }
    }

    record EchoArguments(
            @ToolParam(description = "say this text", required = true) String text,
            @ToolParam(description = "uppercase output") boolean uppercase) {}
}

