package io.github.violateer.doctemplate.starter.support;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Jackson based JSON helper used across the starter.
 *
 * <p>Serialization is deliberately kept on Jackson (Spring's default) and no Fastjson dependency is
 * used anywhere in this starter. Entities are converted to snake_case maps for the API responses,
 * and {@link LocalDateTime} is written as {@code yyyy-MM-dd HH:mm:ss} instead of an int array.</p>
 */
public final class DocTemplateJson {

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ObjectMapper MAPPER = createMapper();

    private DocTemplateJson() {
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    private static ObjectMapper createMapper() {
        JavaTimeModule timeModule = new JavaTimeModule();
        timeModule.addSerializer(LocalDateTime.class, new LocalDateTimeSerializer(DATE_TIME));
        return new ObjectMapper()
                .registerModule(timeModule)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    }

    /** Converts any object (entity, record, POJO) into a snake_case map. */
    public static Map<String, Object> toMap(Object value) {
        if (value == null) return new LinkedHashMap<>();
        return MAPPER.convertValue(value, new TypeReference<LinkedHashMap<String, Object>>() {
        });
    }

    public static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalArgumentException("JSON 序列化失败：" + error.getMessage(), error);
        }
    }

    public static Map<String, Object> readMap(String text) {
        if (text == null || text.isBlank()) return new LinkedHashMap<>();
        try {
            return MAPPER.readValue(text, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (Exception error) {
            throw new IllegalArgumentException("JSON 解析失败：" + error.getMessage(), error);
        }
    }

    /** Parses any JSON document (object, array or scalar). Returns null for blank input. */
    public static Object read(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            return MAPPER.readValue(text, Object.class);
        } catch (Exception error) {
            throw new IllegalArgumentException("JSON 解析失败：" + error.getMessage(), error);
        }
    }
}
