package io.github.moneymaker26754.agentforge.infrastructure.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.moneymaker26754.agentforge.core.ChatMessage;
import io.github.moneymaker26754.agentforge.core.ChatRequest;
import io.github.moneymaker26754.agentforge.core.ToolCall;

final class ModelRequestJson {
    private ModelRequestJson() {}

    static String deepSeek(ObjectMapper mapper, ChatRequest request) {
        ObjectNode root = base(mapper, request);
        root.put("stream", true);
        root.putObject("stream_options").put("include_usage", true);
        return write(mapper, root);
    }

    static String ollama(ObjectMapper mapper, ChatRequest request) {
        ObjectNode root = base(mapper, request);
        root.put("stream", true);
        return write(mapper, root);
    }

    private static ObjectNode base(ObjectMapper mapper, ChatRequest request) {
        ObjectNode root = mapper.createObjectNode();
        root.put("model", request.model());
        ArrayNode messages = root.putArray("messages");
        for (ChatMessage message : request.messages()) {
            ObjectNode json = messages.addObject();
            json.put("role", message.role().name().toLowerCase());
            json.put("content", message.content());
            if (message.toolCallId() != null) {
                json.put("tool_call_id", message.toolCallId());
                json.put("tool_name", message.toolCallId());
            }
            if (!message.toolCalls().isEmpty()) {
                ArrayNode calls = json.putArray("tool_calls");
                for (ToolCall call : message.toolCalls()) {
                    ObjectNode callJson = calls.addObject();
                    callJson.put("id", call.id());
                    callJson.put("type", "function");
                    ObjectNode function = callJson.putObject("function");
                    function.put("name", call.name());
                    function.put("arguments", call.argumentsJson());
                }
            }
        }
        ArrayNode tools = root.putArray("tools");
        request.tools().forEach(descriptor -> {
            ObjectNode tool = tools.addObject();
            tool.put("type", "function");
            ObjectNode function = tool.putObject("function");
            function.put("name", descriptor.name());
            function.put("description", descriptor.description());
            try {
                function.set("parameters", mapper.readTree(descriptor.parametersJsonSchema()));
            } catch (Exception exception) {
                throw new IllegalArgumentException("Invalid schema for tool " + descriptor.name(), exception);
            }
        });
        return root;
    }

    private static String write(ObjectMapper mapper, ObjectNode root) {
        try {
            return mapper.writeValueAsString(root);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Cannot serialize model request", exception);
        }
    }
}

