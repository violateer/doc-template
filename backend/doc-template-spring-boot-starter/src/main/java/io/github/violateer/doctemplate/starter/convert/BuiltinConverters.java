package io.github.violateer.doctemplate.starter.convert;

import io.github.violateer.doctemplate.core.convert.DocTemplateValueConverter;

import java.util.List;

/**
 * Starter 自带的无状态转换器。
 *
 * <p>这里只列不需要外部依赖的那批；{@code dict} 要查字典表，由容器里的
 * {@code DictConverter} Bean 注册（见自动装配），所以不在这里。</p>
 */
public final class BuiltinConverters {

    private BuiltinConverters() {
    }

    public static List<DocTemplateValueConverter> all() {
        return List.of(
                new DateConverter(),
                new NumberConverter(),
                new BooleanConverter(),
                new PercentConverter(),
                TextConverter.upper(),
                TextConverter.lower(),
                TextConverter.trim(),
                new MaskConverter());
    }
}
