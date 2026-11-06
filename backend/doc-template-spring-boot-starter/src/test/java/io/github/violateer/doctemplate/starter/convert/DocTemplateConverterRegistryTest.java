package io.github.violateer.doctemplate.starter.convert;

import io.github.violateer.doctemplate.core.convert.DocTemplateValueConverter;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 转换器编码**严格区分大小写**：`code()` 声明怎么写，配置和模板里就得怎么写。
 *
 * <p>忽略大小写看着「友好」，实际会掩盖拼写错误：配置里写 `DemoSku` 也能跑通，等到某天
 * 换成真正区分大小写的实现（或换一个宿主）才炸。宁可当场报「未知的转换器」。</p>
 */
class DocTemplateConverterRegistryTest {

    private static DocTemplateValueConverter converter(String code) {
        return new DocTemplateValueConverter() {
            @Override
            public String code() {
                return code;
            }

            @Override
            public Object convert(Object value, Map<String, Object> args) {
                return value;
            }
        };
    }

    @Test
    void codesKeepTheDeclaredCasing() {
        DocTemplateConverterRegistry registry = new DocTemplateConverterRegistry(
                BuiltinConverters.all(), List.of(converter("demoSku")));
        assertTrue(registry.codes().contains("demoSku"), registry.codes().toString());
        assertFalse(registry.codes().contains("demosku"), "不能返回另一种写法：" + registry.codes());
    }

    @Test
    void codesAreUniqueAndSortedAsDeclared() {
        DocTemplateConverterRegistry registry = new DocTemplateConverterRegistry(
                List.of(converter("Beta"), converter("alpha"), converter("Gamma")), List.of());
        // 自然排序：大写字母在前，不按忽略大小写的方式重排
        assertEquals(List.of("Beta", "Gamma", "alpha"), registry.codes());
    }

    /** 查表严格区分大小写：写法不一致直接找不到。 */
    @Test
    void lookupIsCaseSensitive() {
        DocTemplateConverterRegistry registry = new DocTemplateConverterRegistry(
                BuiltinConverters.all(), List.of(converter("demoSku")));
        assertTrue(registry.supports("demoSku"));
        assertFalse(registry.supports("DEMOSKU"));
        assertFalse(registry.supports("demosku"));
    }

    /** 模板里写错大小写时报错信息要指出这一点，而不是只给一句「未知的转换器」。 */
    @Test
    void reportsUnknownConverterWithHint() {
        DocTemplateConverterRegistry registry = new DocTemplateConverterRegistry(
                BuiltinConverters.all(), List.of(converter("demoSku")));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> registry.convert("M-1001", "DEMOSKU", Map.of()));
        assertTrue(error.getMessage().contains("区分大小写"), error.getMessage());
        assertTrue(error.getMessage().contains("demoSku"), error.getMessage());
    }

    /** 数据块 {@code converters[].field} 也严格：配置写 {@code Material_Code} 不会命中 {@code material_code}。 */
    @Test
    void converterFieldIsMatchedCaseSensitively() {
        DocTemplateValueConverter shout = new DocTemplateValueConverter() {
            @Override
            public String code() {
                return "shout";
            }

            @Override
            public Object convert(Object value, Map<String, Object> args) {
                return value == null ? null : String.valueOf(value).toUpperCase(Locale.ROOT);
            }
        };
        DocTemplateConverterRegistry registry = new DocTemplateConverterRegistry(List.of(shout), List.of());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("material_code", "m-1");

        Object exact = registry.apply(result, List.of(Map.of("field", "material_code", "type", "shout")));
        assertEquals("M-1", ((Map<?, ?>) exact).get("material_code"), "写法一致才转换");

        Object wrongCase = registry.apply(result, List.of(Map.of("field", "Material_Code", "type", "shout")));
        assertEquals("m-1", ((Map<?, ?>) wrongCase).get("material_code"), "大小写不一致就不该命中");
    }
}
