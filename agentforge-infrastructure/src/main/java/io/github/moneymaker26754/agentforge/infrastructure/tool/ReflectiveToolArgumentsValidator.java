package io.github.moneymaker26754.agentforge.infrastructure.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.moneymaker26754.agentforge.core.ToolArgumentsValidator;
import io.github.moneymaker26754.agentforge.core.ToolDefinition;
import io.github.moneymaker26754.agentforge.core.ToolHandler;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The {@code VALIDATE} stage of the PreToolUse pipeline for reflective {@code @AgentTool} handlers.
 *
 * <p>The registry uses the same rules as defense-in-depth during execution; this class exposes them
 * to the engine so invalid calls are rejected with a {@code VALIDATION_FAILED} event before any
 * executor runs. Tools it does not know (for example MCP tools behind a composite registry) return
 * empty, leaving them to the policy and approval stages.
 */
public final class ReflectiveToolArgumentsValidator implements ToolArgumentsValidator {
    private final ObjectMapper mapper;
    private final Map<String, Class<? extends Record>> argumentTypes;

    public ReflectiveToolArgumentsValidator(ObjectMapper mapper, List<? extends ToolHandler<?>> handlers) {
        this.mapper = mapper;
        var types = new LinkedHashMap<String, Class<? extends Record>>();
        for (ToolHandler<?> handler : handlers) {
            AgentTool annotation = handler.getClass().getAnnotation(AgentTool.class);
            if (annotation != null) {
                types.put(annotation.name(), findArgumentsType(handler.getClass()));
            }
        }
        this.argumentTypes = Map.copyOf(types);
    }

    @Override
    public Optional<String> validate(ToolDefinition definition, String argumentsJson) {
        Class<? extends Record> argumentsType = argumentTypes.get(definition.name());
        if (argumentsType == null) {
            return Optional.empty();
        }
        try {
            JsonNode node = mapper.readTree(argumentsJson);
            String error = validate(node, argumentsType);
            return error == null ? Optional.empty() : Optional.of(error);
        } catch (Exception exception) {
            return Optional.of("arguments are not valid JSON: " + exception.getMessage());
        }
    }

    /** @return the rejection reason, or {@code null} when the node satisfies the arguments contract. */
    static String validate(JsonNode node, Class<? extends Record> argumentsType) {
        if (node == null || !node.isObject()) {
            return "arguments must be a JSON object";
        }
        var allowed = java.util.Arrays.stream(argumentsType.getRecordComponents())
                .map(RecordComponent::getName).collect(java.util.stream.Collectors.toSet());
        var fields = node.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            if (!allowed.contains(field)) {
                return "unknown argument: " + field;
            }
        }
        for (RecordComponent component : argumentsType.getRecordComponents()) {
            ToolParam param = component.getAnnotation(ToolParam.class);
            JsonNode value = node.get(component.getName());
            if (param != null && param.required() && (value == null || value.isNull())) {
                return "missing required argument: " + component.getName();
            }
            if (value != null && !value.isNull() && !matches(value, component.getType())) {
                return "invalid type for argument: " + component.getName();
            }
            if (value != null && value.isNumber() && param != null
                    && (value.asLong() < param.min() || value.asLong() > param.max())) {
                return "argument outside allowed range: " + component.getName();
            }
        }
        return null;
    }

    private static boolean matches(JsonNode value, Class<?> type) {
        if (type == String.class || type.isEnum()) {
            return value.isTextual();
        }
        if (type == boolean.class || type == Boolean.class) {
            return value.isBoolean();
        }
        if (type == int.class || type == Integer.class || type == long.class || type == Long.class) {
            return value.isIntegralNumber();
        }
        if (type == double.class || type == Double.class) {
            return value.isNumber();
        }
        if (List.class.isAssignableFrom(type)) {
            return value.isArray();
        }
        return type.isRecord() && value.isObject();
    }

    static Class<? extends Record> findArgumentsType(Class<?> handlerType) {
        for (Type genericInterface : handlerType.getGenericInterfaces()) {
            if (genericInterface instanceof ParameterizedType parameterized
                    && parameterized.getRawType() == ToolHandler.class) {
                Type argument = parameterized.getActualTypeArguments()[0];
                if (argument instanceof Class<?> argumentClass && argumentClass.isRecord()) {
                    return (Class<? extends Record>) argumentClass;
                }
            }
        }
        throw new IllegalArgumentException("Tool arguments must be a concrete record: " + handlerType.getName());
    }
}
