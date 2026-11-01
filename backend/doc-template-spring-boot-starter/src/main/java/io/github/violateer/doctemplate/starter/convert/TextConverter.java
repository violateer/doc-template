package io.github.violateer.doctemplate.starter.convert;

import cn.hutool.core.convert.Convert;
import cn.hutool.core.util.StrUtil;
import io.github.violateer.doctemplate.core.convert.DocTemplateValueConverter;

import java.util.Locale;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * {@code upper} / {@code lower} / {@code trim}：纯文本处理，没有必填参数。
 *
 * <p>三个转换器共用这一个实现，只是注册的编码不同；{@code upper} / {@code lower} 固定用
 * {@link Locale#ROOT}，避免土耳其语环境下 {@code i} 变大写变成 {@code İ} 这种地域差异。</p>
 *
 * <p>{@code trim} 额外支持 {@code collapse=true}：去掉首尾空白后，把中间的连续空白压成一个空格
 * （SQL 里带换行的地址、备注字段用得上）。</p>
 */
final class TextConverter implements DocTemplateValueConverter {

    private final String code;
    private final UnaryOperator<String> operation;
    private final boolean trims;

    private TextConverter(String code, UnaryOperator<String> operation, boolean trims) {
        this.code = code;
        this.operation = operation;
        this.trims = trims;
    }

    static TextConverter upper() {
        return new TextConverter("upper", text -> text.toUpperCase(Locale.ROOT), false);
    }

    static TextConverter lower() {
        return new TextConverter("lower", text -> text.toLowerCase(Locale.ROOT), false);
    }

    static TextConverter trim() {
        return new TextConverter("trim", String::trim, true);
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public Object convert(Object value, Map<String, Object> args) {
        if (value == null) return null;
        String text = operation.apply(Convert.toStr(value, ""));
        if (trims && Convert.toBool(args.get("collapse"), false)) return StrUtil.cleanBlank(text);
        return text;
    }
}
