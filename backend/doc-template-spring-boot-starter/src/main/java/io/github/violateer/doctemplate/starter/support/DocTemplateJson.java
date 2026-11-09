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
 * 全模块共用的 JSON 工具，基于 Jackson。
 *
 * <p>序列化刻意只用 Jackson（Spring 的默认选择），整个模块不引 Fastjson。实体在 API 响应里
 * 转成下划线命名的 Map，{@link LocalDateTime} 写成 {@code yyyy-MM-dd HH:mm:ss} 而不是整数数组。</p>
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

    /** 把任意对象（实体、record、POJO）转成下划线命名的 Map。 */
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

    /** 解析任意 JSON（对象、数组或标量）；入参为空串时返回 {@code null}。 */
    public static Object read(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            return MAPPER.readValue(text, Object.class);
        } catch (Exception error) {
            throw new IllegalArgumentException("JSON 解析失败：" + error.getMessage(), error);
        }
    }
}
