package com.example.oops.client;

import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.json.JsonMapper;

final class JsonSchemaResponseFormat {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private JsonSchemaResponseFormat() {}
    static Map<String,Object> forType(Class<?> type, String request) {
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
            if (contract.discoveryIdScope()) bindDiscoveryIds(schema, request);
            return Map.of("type", "json_schema", "json_schema", Map.of(
                    "name", contract.name(), "strict", true, "schema", schema));
        } catch (java.io.IOException ex) { throw new IllegalStateException("Cannot load output schema", ex); }
    }

    private static void bindDiscoveryIds(JsonNode schema, String request) {
        if (request == null) throw new IllegalArgumentException("Discovery request required");
        var input = JSON.readTree(request);
        if (input == null || !input.isObject()) throw new IllegalArgumentException("Discovery request object required");
        Set<String> primary = ids(input.path("primaryIds"), false);
        Set<String> raw = ids(input.path("raw"), true);
        if (!raw.containsAll(primary)) throw new IllegalArgumentException("Primary IDs must belong to raw");
        // Shared definitions avoid repeating enums in every evidence/role slot.
        // A fresh schema tree is loaded per request; overlapping batches cannot leak permissions.
        bindEnum(schema.path("$defs").path("primaryId"), primary);
        bindEnum(schema.path("$defs").path("rawId"), raw);
    }

    private static Set<String> ids(JsonNode array, boolean rows) {
        if (!array.isArray() || array.isEmpty() || array.size() > 128)
            throw new IllegalArgumentException("Nonempty bounded discovery IDs required");
        Set<String> ids = new LinkedHashSet<>();
        for (var row : array) {
            var id = rows ? row.path("id") : row;
            if (!id.isString() || id.asText().isBlank() || id.asText().length() > 256 || !ids.add(id.asText()))
                throw new IllegalArgumentException("Unique bounded string discovery IDs required");
        }
        return ids;
    }

    private static void bindEnum(JsonNode node, Set<String> ids) {
        if (!(node instanceof ObjectNode object) || !"string".equals(node.path("type").asText()))
            throw new IllegalArgumentException("Discovery ID definition missing");
        var values = object.putArray("enum");
        ids.forEach(values::add);
    }
}
