package com.mindflow.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.*;

import java.util.*;

/**
 * 将 JSON Schema（Jackson ObjectNode 格式）转换为 LangChain4j 的 ToolSpecification 参数。
 *
 * 覆盖：string/number/integer/boolean/array(object) 类型、required、enum、description。
 * 不覆盖（目前）：$ref、allOf / oneOf / anyOf（由 McpSchemaSanitizer 在更上游清洗）。
 */
class JsonSchemaConverter {

    private JsonSchemaConverter() {}

    static void convert(ObjectNode schema, ToolSpecification.Builder builder) {
        JsonObjectSchema.Builder parametersBuilder = JsonObjectSchema.builder();

        // 收集 required
        Set<String> required = new HashSet<>();
        if (schema.has("required") && schema.get("required").isArray()) {
            schema.get("required").forEach(n -> { if (n.isTextual()) required.add(n.asText()); });
        }

        // 处理 properties
        if (schema.has("properties") && schema.get("properties").isObject()) {
            ObjectNode props = (ObjectNode) schema.get("properties");
            Iterator<Map.Entry<String, JsonNode>> fields = props.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                String name = entry.getKey();
                JsonNode prop = entry.getValue();
                if (prop.isObject()) {
                    addProperty(parametersBuilder, name, (ObjectNode) prop);
                }
            }
        }

        // 设置 required
        if (!required.isEmpty()) {
            parametersBuilder.required(required.toArray(new String[0]));
        }

        builder.parameters(parametersBuilder.build());
    }

    private static void addProperty(JsonObjectSchema.Builder builder, String name, ObjectNode prop) {
        String type = prop.has("type") ? prop.get("type").asText("string") : "string";
        String description = prop.has("description") ? prop.get("description").asText() : "";

        switch (type) {
            case "string" -> {
                if (prop.has("enum") && prop.get("enum").isArray()) {
                    List<String> enumValues = new ArrayList<>();
                    prop.get("enum").forEach(e -> enumValues.add(e.asText()));
                    builder.addEnumProperty(name, enumValues, description);
                } else {
                    builder.addStringProperty(name, description);
                }
            }
            case "integer", "number" -> builder.addIntegerProperty(name, description);
            case "boolean" -> builder.addBooleanProperty(name, description);
            case "array" -> {
                String itemsType = "string";
                if (prop.has("items") && prop.get("items").isObject()
                        && prop.get("items").has("type")) {
                    itemsType = prop.get("items").get("type").asText();
                }
                JsonArraySchema.Builder arrayBuilder = JsonArraySchema.builder()
                        .description(description)
                        .items(createItemSchema(itemsType));
                builder.addProperty(name, arrayBuilder.build());
            }
            default -> builder.addStringProperty(name, description);
        }
    }

    private static JsonSchemaElement createItemSchema(String type) {
        return switch (type) {
            case "string" -> JsonStringSchema.builder().build();
            case "integer", "number" -> JsonIntegerSchema.builder().build();
            case "boolean" -> JsonBooleanSchema.builder().build();
            default -> JsonStringSchema.builder().build();
        };
    }
}
