package io.github.moneymaker26754.agentforge.infrastructure.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.moneymaker26754.agentforge.core.RiskLevel;
import io.github.moneymaker26754.agentforge.core.ToolDefinition;
import io.github.moneymaker26754.agentforge.core.ToolDescriptor;
import io.github.moneymaker26754.agentforge.core.ToolRegistry;
import io.github.moneymaker26754.agentforge.core.ToolResult;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CompositeToolRegistryTest {

    @Test
    void resolvesToolsFromTheDelegateThatProvidesThem() {
        var registry = new CompositeToolRegistry(
                List.of(singleTool("first"), singleTool("second")));

        var definition = registry.find("second").orElseThrow();

        assertThat(definition.description()).isEqualTo("second");
    }

    @Test
    void mergesDescriptorsInDelegateOrder() {
        var registry = new CompositeToolRegistry(List.of(singleTool("a"), singleTool("b"), singleTool("c")));

        assertThat(registry.descriptors()).extracting(ToolDescriptor::name).containsExactly("a", "b", "c");
    }

    @Test
    void rejectsDuplicateToolNamesAcrossDelegates() {
        assertThatThrownBy(() -> new CompositeToolRegistry(
                List.of(singleTool("dup"), singleTool("other"), singleTool("dup"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dup");
    }

    @Test
    void rejectsEmptyDelegateList() {
        assertThatThrownBy(() -> new CompositeToolRegistry(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void returnsEmptyForUnknownTool() {
        var registry = new CompositeToolRegistry(singleTool("known"));

        assertThat(registry.find("unknown")).isEmpty();
    }

    private static ToolRegistry singleTool(String name) {
        return singleTool(name, name);
    }

    private static ToolRegistry singleTool(String name, String description) {
        ToolDefinition definition = new ToolDefinition(name, description, "{}", RiskLevel.READ, true,
                (json, context) -> ToolResult.success(name));
        return new ToolRegistry() {
            @Override
            public Optional<ToolDefinition> find(String candidate) {
                return name.equals(candidate) ? Optional.of(definition) : Optional.empty();
            }

            @Override
            public List<ToolDescriptor> descriptors() {
                return List.of(new ToolDescriptor(name, description, "{}"));
            }
        };
    }
}
