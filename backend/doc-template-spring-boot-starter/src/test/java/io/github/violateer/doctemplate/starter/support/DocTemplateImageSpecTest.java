package io.github.violateer.doctemplate.starter.support;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 图片 / 二维码字段声明：<b>只写字段名本身</b>，带块编码或路径的写法直接报错。
 *
 * <p>模板那边可以带前缀（{@code ${report.logo}}、循环体里的 {@code ${item.logo}}），
 * 因为前缀是「在哪取的」，渲染时本来就知道；声明这边写路径则是死配置 —— 循环体里根本
 * 没有 {@code items} 这一层，声明成 {@code items.photo} 只会静默退化成一行 URL 文本。</p>
 */
class DocTemplateImageSpecTest {

    private static DocTemplateImageSpec spec(String field) {
        return new DocTemplateImageSpec(field, 120, 80);
    }

    @Test
    void matchesPlainName() {
        assertTrue(spec("logo").matches("logo"));
    }

    /** 模板里带前缀算命中：块编码前缀、循环项前缀都行。 */
    @Test
    void matchesWhenTemplateAddsPrefix() {
        DocTemplateImageSpec spec = spec("logo");
        assertTrue(spec.matches("report.logo"));
        assertTrue(spec.matches("item.logo"));
        assertTrue(spec.matches("items.logo"), "多层路径同样按结尾匹配");
    }

    @Test
    void ignoresUnrelatedKeys() {
        DocTemplateImageSpec spec = spec("logo");
        assertFalse(spec.matches("logo2"));
        assertFalse(spec.matches("my_logo"));
        assertFalse(spec.matches("photo"));
        assertFalse(spec.matches(""));
        assertFalse(spec.matches(null));
    }

    @Test
    void rejectsPathInDeclaration() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> spec("items.photo"));
        assertTrue(error.getMessage().contains("items.photo"), error.getMessage());
        assertTrue(error.getMessage().contains("photo"), "要告诉用户改成什么：" + error.getMessage());
        assertTrue(error.getMessage().contains("${item.photo}"), error.getMessage());
    }

    @Test
    void rejectsPathInQrCodeDeclaration() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new DocTemplateQrCodeSpec("report.verify_url", null, null, null, null,
                        null, null, null, null));
        assertTrue(error.getMessage().contains("二维码字段"), error.getMessage());
        assertTrue(error.getMessage().contains("verify_url"), error.getMessage());
    }

    /** 配置解析走的是同一个构造器，所以带路径的配置在解析阶段就失败，不会等到渲染。 */
    @Test
    void rejectsPathFromConfig() {
        assertThrows(IllegalArgumentException.class,
                () -> DocTemplateImageSpec.fromConfig(Map.of("imageFields", List.of("items.photo"))));
        assertThrows(IllegalArgumentException.class,
                () -> DocTemplateQrCodeSpec.fromConfig(Map.of("qrCodes", "report.verify_url")));
    }

    @Test
    void keepsPlainFieldAsIs() {
        assertEquals("logo", spec("  logo  ").field(), "只去首尾空格");
    }
}
