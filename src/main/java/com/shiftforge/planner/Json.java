package com.shiftforge.planner;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.cfg.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.type.LogicalType;
import java.io.IOException;

public final class Json {
    public static final ObjectMapper MAPPER = JsonMapper.builder()
        .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
        .build();
    static {
        for (CoercionInputShape shape : new CoercionInputShape[]{CoercionInputShape.String, CoercionInputShape.Float, CoercionInputShape.Boolean})
            MAPPER.coercionConfigFor(LogicalType.Integer).setCoercion(shape, CoercionAction.Fail);
    }
    private Json() {}
    public static JsonNode tree(String json) {
        try { return MAPPER.readTree(json); }
        catch (IOException e) { throw new IllegalArgumentException("Invalid JSON: " + e.getMessage(), e); }
    }
    public static <T> T convert(JsonNode tree, Class<T> type) {
        try { return MAPPER.treeToValue(tree, type); }
        catch (IOException | IllegalArgumentException e) { throw new IllegalArgumentException("Invalid " + type.getSimpleName() + " JSON", e); }
    }
    public static String write(Object value) {
        try { return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n"; }
        catch (IOException e) { throw new IllegalStateException("Could not encode response", e); }
    }
}
