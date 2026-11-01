package io.github.violateer.doctemplate.starter.convert;

import cn.hutool.core.convert.Convert;
import cn.hutool.core.util.StrUtil;
import io.github.violateer.doctemplate.core.convert.DocTemplateValueConverter;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAccessor;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * {@code date}：把时间值按 {@code pattern} 格式化。
 *
 * <p>入参很杂：SQL 里 {@code DATE} / {@code DATETIME} 取出来可能是 {@link java.sql.Timestamp}、
 * {@link LocalDateTime}、{@link Date}，Provider 也可能直接给字符串或毫秒数，这里统一收口成
 * {@link LocalDateTime} 再格式化。认不出来的值原样返回，不会让整篇文档生成失败。</p>
 */
final class DateConverter implements DocTemplateValueConverter {

    static final String DEFAULT_PATTERN = "yyyy-MM-dd";

    /** 字符串入参的候选格式，按「信息量大的在前」顺序尝试。 */
    private static final List<DateTimeFormatter> INPUTS = List.of(
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm"),
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss"),
            DateTimeFormatter.ofPattern("yyyyMMdd"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd"));

    @Override
    public String code() {
        return "date";
    }

    @Override
    public Object convert(Object value, Map<String, Object> args) {
        if (value == null) return null;
        String pattern = StrUtil.blankToDefault(Convert.toStr(args.get("pattern")), DEFAULT_PATTERN).trim();
        DateTimeFormatter formatter;
        try {
            formatter = DateTimeFormatter.ofPattern(pattern);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("日期格式不合法：" + pattern, invalid);
        }
        LocalDateTime time = toLocalDateTime(value);
        if (time == null) return value;
        return formatter.format(time);
    }

    private LocalDateTime toLocalDateTime(Object value) {
        if (value instanceof LocalDateTime dateTime) return dateTime;
        if (value instanceof LocalDate date) return date.atStartOfDay();
        if (value instanceof LocalTime time) return LocalDateTime.of(LocalDate.EPOCH, time);
        if (value instanceof ZonedDateTime zoned) return zoned.toLocalDateTime();
        if (value instanceof OffsetDateTime offset) return offset.toLocalDateTime();
        if (value instanceof Instant instant) return LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
        if (value instanceof java.util.Calendar calendar) {
            return LocalDateTime.ofInstant(calendar.toInstant(), ZoneId.systemDefault());
        }
        if (value instanceof Date date) {
            return LocalDateTime.ofInstant(date.toInstant(), ZoneId.systemDefault());
        }
        if (value instanceof Number number) {
            return LocalDateTime.ofInstant(Instant.ofEpochMilli(number.longValue()), ZoneId.systemDefault());
        }
        String text = Convert.toStr(value, "").trim();
        if (text.isEmpty()) return null;
        // 先按 ISO 标准格式（2026-10-30T14:16:35）解析，再退回常见的中式写法
        try {
            return LocalDateTime.parse(text);
        } catch (DateTimeParseException ignored) {
            // 继续尝试下面的候选格式
        }
        for (DateTimeFormatter input : INPUTS) {
            try {
                TemporalAccessor parsed = input.parse(text);
                return LocalDateTime.from(parsed);
            } catch (RuntimeException ignored) {
                // 换下一个格式
            }
        }
        return null;
    }
}
