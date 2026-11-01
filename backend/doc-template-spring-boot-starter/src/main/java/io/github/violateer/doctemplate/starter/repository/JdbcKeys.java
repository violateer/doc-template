package io.github.violateer.doctemplate.starter.repository;

import org.springframework.jdbc.support.KeyHolder;

import java.util.Map;

/**
 * 取回自增主键。
 *
 * <p>不同驱动返回的键不一样：MySQL 只回一列（列名 {@code GENERATED_KEY}），另一些驱动会把整行
 * 都当键返回，此时 {@link KeyHolder#getKey()} 会因为「多个键」直接抛异常。所以这里按列名找
 * {@code id}，找不到再退回第一个数值。</p>
 */
final class JdbcKeys {

    private JdbcKeys() {
    }

    static Long generatedId(KeyHolder keys) {
        Map<String, Object> values = keys.getKeys();
        if (values == null || values.isEmpty()) return null;
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            if ("id".equalsIgnoreCase(entry.getKey()) && entry.getValue() instanceof Number number) {
                return number.longValue();
            }
        }
        for (Object value : values.values()) {
            if (value instanceof Number number) return number.longValue();
        }
        return null;
    }
}
