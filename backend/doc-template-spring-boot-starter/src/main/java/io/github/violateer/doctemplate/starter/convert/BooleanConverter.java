package io.github.violateer.doctemplate.starter.convert;

import cn.hutool.core.convert.Convert;
import cn.hutool.core.util.StrUtil;
import io.github.violateer.doctemplate.core.convert.DocTemplateValueConverter;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * {@code boolean}：把 {@code 0/1}、{@code true/false}、{@code Y/N} 换成可读文本。
 *
 * <p>参数：{@code trueText}（默认「是」）、{@code falseText}（默认「否」）。
 * 例如 {@code {"type":"boolean","args":{"trueText":"启用","falseText":"停用"}}}。</p>
 *
 * <p>认不出的值原样返回 —— 数据库里存了「待定」这种第三态时，直接显示原文比强行二选一靠谱。</p>
 */
final class BooleanConverter implements DocTemplateValueConverter {

    private static final Set<String> TRUE_WORDS = Set.of(
            "1", "true", "t", "y", "yes", "on", "是", "启用", "有效", "enabled");

    private static final Set<String> FALSE_WORDS = Set.of(
            "0", "false", "f", "n", "no", "off", "否", "停用", "无效", "disabled", "");

    @Override
    public String code() {
        return "boolean";
    }

    @Override
    public Object convert(Object value, Map<String, Object> args) {
        if (value == null) return null;
        String trueText = StrUtil.blankToDefault(Convert.toStr(args.get("trueText")), "是");
        String falseText = StrUtil.blankToDefault(Convert.toStr(args.get("falseText")), "否");
        if (value instanceof Boolean flag) return flag ? trueText : falseText;
        if (value instanceof Number number) {
            // 只认 0 / 1，其他数字当作「不是布尔值」，交给原值输出
            return switch (number.intValue()) {
                case 1 -> trueText;
                case 0 -> falseText;
                default -> value;
            };
        }
        String text = Convert.toStr(value, "").trim().toLowerCase(Locale.ROOT);
        if (TRUE_WORDS.contains(text)) return trueText;
        if (FALSE_WORDS.contains(text)) return falseText;
        return value;
    }
}
