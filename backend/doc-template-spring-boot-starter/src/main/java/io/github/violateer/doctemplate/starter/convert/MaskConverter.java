package io.github.violateer.doctemplate.starter.convert;

import cn.hutool.core.convert.Convert;
import cn.hutool.core.util.StrUtil;
import io.github.violateer.doctemplate.core.convert.DocTemplateValueConverter;

import java.util.Map;

/**
 * {@code mask}：手机号 / 身份证等敏感字段脱敏。
 *
 * <p>参数：</p>
 * <ul>
 *   <li>{@code mode}：预设方案，{@code phone}（前 3 后 4）或 {@code idcard}（前 6 后 4）；</li>
 *   <li>{@code keepStart} / {@code keepEnd}：显式指定保留位数，优先级高于 {@code mode}；</li>
 *   <li>{@code mask}：掩码字符，默认 {@code *}。</li>
 * </ul>
 *
 * <p>默认前 3 后 4，例如 {@code 13800001111 → 138****1111}。字符串短到留不出中间段时只保留首字符，
 * 避免「三位数手机号」这种脏数据被整串打码后看不出任何信息。</p>
 */
final class MaskConverter implements DocTemplateValueConverter {

    private static final int DEFAULT_KEEP_START = 3;
    private static final int DEFAULT_KEEP_END = 4;

    @Override
    public String code() {
        return "mask";
    }

    @Override
    public Object convert(Object value, Map<String, Object> args) {
        if (value == null) return null;
        String text = Convert.toStr(value, "");
        if (text.isEmpty()) return text;

        String mode = StrUtil.blankToDefault(Convert.toStr(args.get("mode")), "").trim().toLowerCase(java.util.Locale.ROOT);
        int keepStart = switch (mode) {
            case "phone", "mobile" -> 3;
            case "idcard", "id" -> 6;
            default -> DEFAULT_KEEP_START;
        };
        int keepEnd = switch (mode) {
            case "phone", "mobile" -> 4;
            case "idcard", "id" -> 4;
            default -> DEFAULT_KEEP_END;
        };
        keepStart = Convert.toInt(args.get("keepStart"), keepStart);
        keepEnd = Convert.toInt(args.get("keepEnd"), keepEnd);
        String mask = StrUtil.blankToDefault(Convert.toStr(args.get("mask")), "*");

        int length = text.length();
        int start = Math.max(0, Math.min(keepStart, length));
        int end = Math.max(0, Math.min(keepEnd, length - start));
        // 全遮掉没有任何意义，至少留首字符
        if (start == 0 && end == 0) start = Math.min(1, length);
        if (start + end >= length) return text;

        StringBuilder sb = new StringBuilder(length);
        sb.append(text, 0, start);
        for (int i = start; i < length - end; i++) sb.append(mask);
        sb.append(text, length - end, length);
        return sb.toString();
    }
}
