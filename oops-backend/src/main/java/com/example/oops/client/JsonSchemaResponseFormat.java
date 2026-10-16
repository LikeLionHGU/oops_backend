package com.example.oops.client;

import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

final class JsonSchemaResponseFormat {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private JsonSchemaResponseFormat() {}
    static Map<String,Object> forType(Class<?> type) {
        var contract = type.getAnnotation(JsonSchemaOutput.class);
        if (contract == null) return Map.of("type", "json_object");
        if (!contract.name().matches("[a-zA-Z0-9_-]{1,64}") || !contract.resource().startsWith("/")
                || !contract.resource().endsWith(".schema.json") || contract.resource().contains(".."))
            throw new IllegalArgumentException("Trusted classpath output schema required");
        try (var in = type.getResourceAsStream(contract.resource())) {
            if (in == null) throw new IllegalStateException("Output schema missing");
            byte[] bytes = in.readNBytes(32769);
            if (bytes.length > 32768) throw new IllegalArgumentException("Output schema too large");
            var schema = JSON.readTree(bytes);
            if (!schema.isObject() || !schema.path("type").asText().equals("object")
                    || !schema.path("required").isArray() || !schema.path("additionalProperties").isBoolean()
                    || schema.path("additionalProperties").asBoolean())
                throw new IllegalArgumentException("Strict root object schema required");
            return Map.of("type", "json_schema", "json_schema", Map.of(
                    "name", contract.name(), "strict", true, "schema", schema));
        } catch (java.io.IOException ex) { throw new IllegalStateException("Cannot load output schema", ex); }
    }
}
