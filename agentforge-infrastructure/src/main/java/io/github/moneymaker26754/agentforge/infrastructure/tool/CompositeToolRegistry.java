package io.github.moneymaker26754.agentforge.infrastructure.tool;

import io.github.moneymaker26754.agentforge.core.ToolDefinition;
import io.github.moneymaker26754.agentforge.core.ToolDescriptor;
import io.github.moneymaker26754.agentforge.core.ToolRegistry;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Combines several {@link ToolRegistry} implementations into a single registry while
 * keeping their individual ordering semantics.
 *
 * <p>{@link #find(String)} resolves a tool from the first delegate that provides it;
 * {@link #descriptors()} concatenates the delegate descriptors in delegate order.
 * Tool names must be unique across all delegates; construction fails with an
 * {@link IllegalArgumentException} listing every duplicated name otherwise.</p>
 */
public final class CompositeToolRegistry implements ToolRegistry {

    private final List<ToolRegistry> delegates;

    public CompositeToolRegistry(ToolRegistry... delegates) {
        this(delegates == null ? List.of() : List.of(delegates));
    }

    public CompositeToolRegistry(List<ToolRegistry> delegates) {
        if (delegates == null || delegates.isEmpty()) {
            throw new IllegalArgumentException("At least one delegate ToolRegistry is required");
        }
        for (ToolRegistry delegate : delegates) {
            if (delegate == null) {
                throw new IllegalArgumentException("Delegate ToolRegistry must not be null");
            }
        }
        this.delegates = List.copyOf(delegates);
        verifyUniqueNames();
    }

    private void verifyUniqueNames() {
        Set<String> seen = new HashSet<>();
        Set<String> duplicates = new TreeSet<>();
        for (ToolDescriptor descriptor : descriptors()) {
            if (!seen.add(descriptor.name())) {
                duplicates.add(descriptor.name());
            }
        }
        if (!duplicates.isEmpty()) {
            throw new IllegalArgumentException("Duplicate tool names across registries: "
                    + new ArrayList<>(duplicates));
        }
    }

    @Override
    public Optional<ToolDefinition> find(String name) {
        for (ToolRegistry delegate : delegates) {
            Optional<ToolDefinition> found = delegate.find(name);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    @Override
    public List<ToolDescriptor> descriptors() {
        return delegates.stream()
                .flatMap(registry -> registry.descriptors().stream())
                .toList();
    }
}
