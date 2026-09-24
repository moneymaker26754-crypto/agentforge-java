package io.github.moneymaker26754.agentforge.core;

import java.util.List;
import java.util.Optional;

public interface ToolRegistry {
    Optional<ToolDefinition> find(String name);

    List<ToolDescriptor> descriptors();
}

