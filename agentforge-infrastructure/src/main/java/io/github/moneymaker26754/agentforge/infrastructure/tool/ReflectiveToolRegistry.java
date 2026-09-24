package io.github.moneymaker26754.agentforge.infrastructure.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.moneymaker26754.agentforge.core.ToolDefinition;
import io.github.moneymaker26754.agentforge.core.ToolDescriptor;
import io.github.moneymaker26754.agentforge.core.ToolHandler;
import io.github.moneymaker26754.agentforge.core.ToolRegistry;
import io.github.moneymaker26754.agentforge.core.ToolResult;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class ReflectiveToolRegistry implements ToolRegistry {
    private final ObjectMapper mapper;
    private final Map<String, ToolDefinition> definitions;

    public ReflectiveToolRegistry(ObjectMapper mapper, List<? extends ToolHandler<?>> handlers) {
        this.mapper = mapper;
        this.definitions = new LinkedHashMap<>();
        for (ToolHandler<?> handler : handlers) {
            register(handler);
        }
    }

    @Override
    public Optional<ToolDefinition> find(String name) {
        return Optional.ofNullable(definitions.get(name));
    }

    @Override
    public List<ToolDescriptor> descriptors() {
        return definitions.values().stream()
                .map(definition -> new ToolDescriptor(
                        definition.name(), definition.description(), definition.jsonSchema()))
                .toList();
    }

    private void register(ToolHandler<?> handler) {
        Class<?> handlerType = handler.getClass();
        AgentTool annotation = handlerType.getAnnotation(AgentTool.class);
        if (annotation == null) {
            throw new IllegalArgumentException("Tool handler is missing @AgentTool: " + handlerType.getName());
        }
        Class<? extends Record> argumentsType = findArgumentsType(handlerType);
        String schema = generateSchema(argumentsType);
        ToolDefinition definition = new ToolDefinition(annotation.name(), annotation.description(), schema,
                annotation.risk(), annotation.idempotent(),
                (json, context) -> execute(handler, argumentsType, json, context));
        if (definitions.putIfAbsent(annotation.name(), definition) != null) {
            throw new IllegalArgumentException("Duplicate tool name: " + annotation.name());
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private ToolResult execute(ToolHandler handler, Class<? extends Record> argumentsType, String json,
            io.github.moneymaker26754.agentforge.core.ExecutionContext context) {
        try {
            JsonNode node = mapper.readTree(json);
            String validationError = validate(node, argumentsType);
            if (validationError != null) {
                return ToolResult.failure("INVALID_ARGUMENTS", validationError);
            }
            Record arguments = mapper.treeToValue(node, argumentsType);
            return handler.execute(arguments, context);
        } catch (Exception exception) {
            return ToolResult.failure("INVALID_ARGUMENTS", exception.getMessage());
        }
    }

    private String validate(JsonNode node, Class<? extends Record> argumentsType) {
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

    private boolean matches(JsonNode value, Class<?> type) {
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

    private String generateSchema(Class<? extends Record> argumentsType) {
        ObjectNode root = mapper.createObjectNode();
        root.put("type", "object");
        root.put("additionalProperties", false);
        ObjectNode properties = root.putObject("properties");
        ArrayNode required = root.putArray("required");
        for (RecordComponent component : argumentsType.getRecordComponents()) {
            ToolParam param = component.getAnnotation(ToolParam.class);
            ObjectNode property = properties.putObject(component.getName());
            property.put("type", jsonType(component.getType()));
            if (List.class.isAssignableFrom(component.getType())) {
                Type generic = component.getGenericType();
                if (!(generic instanceof ParameterizedType parameterized)
                        || parameterized.getActualTypeArguments().length != 1
                        || parameterized.getActualTypeArguments()[0] != String.class) {
                    throw new IllegalArgumentException("Only List<String> tool arguments are supported: "
                            + argumentsType.getName() + "." + component.getName());
                }
                property.putObject("items").put("type", "string");
            }
            if (param != null) {
                property.put("description", param.description());
                if (param.required()) {
                    required.add(component.getName());
                }
                if (param.min() != Long.MIN_VALUE) {
                    property.put("minimum", param.min());
                }
                if (param.max() != Long.MAX_VALUE) {
                    property.put("maximum", param.max());
                }
            }
            if (component.getType().isEnum()) {
                ArrayNode values = property.putArray("enum");
                for (Object constant : component.getType().getEnumConstants()) {
                    values.add(constant.toString());
                }
            }
        }
        try {
            return mapper.writeValueAsString(root);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Cannot generate schema for " + argumentsType.getName(), exception);
        }
    }

    private String jsonType(Class<?> type) {
        if (type == boolean.class || type == Boolean.class) return "boolean";
        if (type == int.class || type == Integer.class || type == long.class || type == Long.class) return "integer";
        if (type == double.class || type == Double.class) return "number";
        if (List.class.isAssignableFrom(type)) return "array";
        if (type.isRecord()) return "object";
        return "string";
    }

    @SuppressWarnings("unchecked")
    private Class<? extends Record> findArgumentsType(Class<?> handlerType) {
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
