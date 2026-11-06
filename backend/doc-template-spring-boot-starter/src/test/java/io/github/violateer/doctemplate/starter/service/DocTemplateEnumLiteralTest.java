package io.github.violateer.doctemplate.starter.service;

import io.github.violateer.doctemplate.starter.support.DocTemplateImageSpec;
import io.github.violateer.doctemplate.starter.support.DocTemplateQrCodeSpec;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 枚举类字面量：大小写和常见同义词都认，但**认不出来就报错**，不再静默退回默认值。
 *
 * <p>静默退回的害处是「配置写错」和「本来就想要默认值」在结果上完全一样：
 * {@code align: "lef"} 会让图片居中、{@code exportType: "pdff"} 会给你一份 docx，
 * 都查不出原因。</p>
 */
class DocTemplateEnumLiteralTest {

    @Test
    void alignAcceptsCommonSpellings() {
        assertEquals(DocTemplateImageSpec.ALIGN_LEFT, DocTemplateImageSpec.normalizeAlign("left"));
        assertEquals(DocTemplateImageSpec.ALIGN_LEFT, DocTemplateImageSpec.normalizeAlign("LEFT"));
        assertEquals(DocTemplateImageSpec.ALIGN_LEFT, DocTemplateImageSpec.normalizeAlign("start"));
        assertEquals(DocTemplateImageSpec.ALIGN_RIGHT, DocTemplateImageSpec.normalizeAlign("End"));
        assertEquals(DocTemplateImageSpec.ALIGN_CENTER, DocTemplateImageSpec.normalizeAlign("centre"));
        assertEquals(DocTemplateImageSpec.ALIGN_CENTER, DocTemplateImageSpec.normalizeAlign(null));
        assertEquals(DocTemplateImageSpec.ALIGN_CENTER, DocTemplateImageSpec.normalizeAlign("  "));
    }

    @Test
    void alignRejectsUnknownValue() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> DocTemplateImageSpec.normalizeAlign("lef"));
        assertTrue(error.getMessage().contains("lef"), error.getMessage());
        assertTrue(error.getMessage().contains("center"), "要列出可选值：" + error.getMessage());
    }

    @Test
    void qrLevelAcceptsOnlyLmQh() {
        assertEquals("H", DocTemplateQrCodeSpec.normalizeLevel("h"));
        assertEquals("Q", DocTemplateQrCodeSpec.normalizeLevel(" Q "));
        assertNull(DocTemplateQrCodeSpec.normalizeLevel(""), "不写时返回 null，由渲染器按是否有小图自动选");
        assertNull(DocTemplateQrCodeSpec.normalizeLevel(null));
    }

    @Test
    void qrLevelRejectsUnknownValue() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> DocTemplateQrCodeSpec.normalizeLevel("X"));
        assertTrue(error.getMessage().contains("L / M / Q / H"), error.getMessage());
    }

    @Test
    void fileTypeAcceptsOnlyDocxAndXlsx() {
        assertEquals("DOCX", DocTemplateRenderer.fileTypeOf("docx"));
        assertEquals("XLSX", DocTemplateRenderer.fileTypeOf("Xlsx"));
        assertEquals("DOCX", DocTemplateRenderer.fileTypeOf(null));
    }

    @Test
    void fileTypeRejectsUnknownValue() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> DocTemplateRenderer.fileTypeOf("PDF"));
        assertTrue(error.getMessage().contains("DOCX 或 XLSX"), error.getMessage());
    }

    @Test
    void exportTypeAcceptsOnlyOriginalAndPdf() {
        assertEquals("ORIGINAL", DocTemplateService.exportTypeOf("original"));
        assertEquals("PDF", DocTemplateService.exportTypeOf(" pdf "));
        assertEquals("ORIGINAL", DocTemplateService.exportTypeOf(null));
    }

    @Test
    void exportTypeRejectsUnknownValue() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> DocTemplateService.exportTypeOf("pdff"));
        assertTrue(error.getMessage().contains("ORIGINAL"), error.getMessage());
        assertTrue(error.getMessage().contains("PDF"), error.getMessage());
    }

    /** 图片声明构造期就会校验，配置写错在解析阶段就失败，不会等到渲染。 */
    @Test
    void imageSpecValidatesAlignOnConstruction() {
        assertThrows(IllegalArgumentException.class,
                () -> new DocTemplateImageSpec("logo", 120, 80, "middle-left", 4));
        assertThrows(IllegalArgumentException.class,
                () -> new DocTemplateQrCodeSpec("verify_url", 140, 140, "left", 4, "Z", null, null, null));
    }
}
