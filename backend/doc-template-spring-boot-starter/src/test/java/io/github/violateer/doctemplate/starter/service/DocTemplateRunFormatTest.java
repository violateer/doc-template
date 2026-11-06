package io.github.violateer.doctemplate.starter.service;

import io.github.violateer.doctemplate.starter.support.DocTemplateImageSpec;
import io.github.violateer.doctemplate.starter.support.DocTemplateMergeAlign;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 段落按 run 片段写回：同一段里不同格式的片段各留各的。
 *
 * <p>以前是「整段文本拼起来替换完塞回第一个 run、其余 run 清空」，一段里 {@code 合计：}（加粗）+
 * {@code ${amount}}（常规）+ {@code 元}（斜体）渲染完会整段变成第一个 run 的样式。现在只有占位符
 * 所在的那一小片被改写，其余 run 一个字节都不动；含图片的段落整段重建，但每片带自己来源 run 的格式。</p>
 */
class DocTemplateRunFormatTest {

    /** 1×1 透明 PNG，用 data URI 传给图片字段，测试里不依赖外部资源。 */
    private static final String PIXEL_PNG = "data:image/png;base64,"
            + "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";

    private static Map<String, Object> params() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("amount", 100);
        return params;
    }

    private static XWPFDocument render(Consumer<XWPFDocument> builder) throws Exception {
        return render(builder, params(), List.of());
    }

    private static XWPFDocument render(Consumer<XWPFDocument> builder, Map<String, Object> params,
                                       List<DocTemplateImageSpec> images) throws Exception {
        byte[] template;
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            builder.accept(document);
            document.write(out);
            template = out.toByteArray();
        }
        byte[] rendered = new DocTemplateRenderer().render(template, "DOCX", params,
                images, DocTemplateMergeAlign.DEFAULT, Map.of());
        return new XWPFDocument(new ByteArrayInputStream(rendered));
    }

    /** 一段里三种格式，替换后各自保留。 */
    @Test
    void keepsFormattingOfEachRunInOneParagraph() throws Exception {
        try (XWPFDocument document = render(doc -> {
            XWPFParagraph paragraph = doc.createParagraph();
            XWPFRun label = paragraph.createRun();
            label.setBold(true);
            label.setText("合计：");
            paragraph.createRun().setText("${amount}");
            XWPFRun unit = paragraph.createRun();
            unit.setItalic(true);
            unit.setText(" 元");
        })) {
            XWPFParagraph paragraph = document.getParagraphs().get(0);
            assertEquals("合计：100 元", paragraph.getText());

            List<XWPFRun> runs = paragraph.getRuns();
            assertEquals(3, runs.size(), "三段文本各留一个 run，不该合并成一个");
            assertEquals("合计：", runs.get(0).text());
            assertEquals(Boolean.TRUE, runs.get(0).isBold());
            assertEquals("100", runs.get(1).text());
            assertNotEquals(Boolean.TRUE, runs.get(1).isBold(), "值不该被上一段的加粗带过去");
            assertEquals(" 元", runs.get(2).text());
            assertEquals(Boolean.TRUE, runs.get(2).isItalic());
        }
    }

    /** 占位符被 Word 拆到两个 run 里：值沿用起点 run 的格式，被吃掉的 run 不能留残尾。 */
    @Test
    void placeholderSplitAcrossRunsKeepsFirstRunFormatting() throws Exception {
        try (XWPFDocument document = render(doc -> {
            XWPFParagraph paragraph = doc.createParagraph();
            XWPFRun head = paragraph.createRun();
            head.setBold(true);
            head.setText("${amo");
            paragraph.createRun().setText("unt}");
        })) {
            XWPFParagraph paragraph = document.getParagraphs().get(0);
            assertEquals("100", paragraph.getText(), "「unt}」不能残留在文档上");

            List<XWPFRun> runs = paragraph.getRuns();
            assertEquals("100", runs.get(0).text());
            assertEquals(Boolean.TRUE, runs.get(0).isBold());
            assertEquals("", runs.get(1).text());
        }
    }

    /** 没被占位符覆盖的 run 原样不动：制表符、换行这类不是文本节点的内容不能被重写成字符。 */
    @Test
    void keepsUntouchedRunsIntact() throws Exception {
        try (XWPFDocument document = render(doc -> {
            XWPFParagraph paragraph = doc.createParagraph();
            XWPFRun tabbed = paragraph.createRun();
            tabbed.setText("前");
            tabbed.addTab();
            tabbed.setText("后");
            paragraph.createRun().setText("${amount}");
        })) {
            XWPFRun tabbed = document.getParagraphs().get(0).getRuns().get(0);
            assertEquals("前\t后", tabbed.text());
            assertEquals(2, tabbed.getCTR().sizeOfTArray(), "两个 w:t 都要留着，不能被合成一个");
            assertEquals(1, tabbed.getCTR().sizeOfTabArray(), "制表符还是制表符，不能变成 \\t 字符");
        }
    }

    /** 文字和图片混排：图片独占中间那个 run，前后文字的格式各自保留。 */
    @Test
    void keepsFormattingAroundInlineImage() throws Exception {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("logo", PIXEL_PNG);
        try (XWPFDocument document = render(doc -> {
            XWPFParagraph paragraph = doc.createParagraph();
            XWPFRun label = paragraph.createRun();
            label.setBold(true);
            label.setText("签名：");
            paragraph.createRun().setText("${logo}");
            XWPFRun tail = paragraph.createRun();
            tail.setItalic(true);
            tail.setText(" 完毕");
        }, params, List.of(new DocTemplateImageSpec("logo", null, null)))) {
            List<XWPFRun> runs = document.getParagraphs().get(0).getRuns();
            assertEquals(3, runs.size(), "文字 / 图片 / 文字 各占一个 run");
            assertEquals("签名：", runs.get(0).text());
            assertEquals(Boolean.TRUE, runs.get(0).isBold());
            assertEquals(1, runs.get(1).getEmbeddedPictures().size(), "中间那个 run 应该是图片");
            assertEquals("", runs.get(1).text());
            assertEquals(" 完毕", runs.get(2).text());
            assertEquals(Boolean.TRUE, runs.get(2).isItalic());
            assertTrue(runs.get(0).getEmbeddedPictures().isEmpty());
        }
    }
}
