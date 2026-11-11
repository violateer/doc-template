package io.github.violateer.doctemplate.starter.service;

import io.github.violateer.doctemplate.starter.support.DocTemplateColumnSpec;
import io.github.violateer.doctemplate.starter.support.DocTemplateMergeAlign;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 标记关键字**不区分大小写**，但「只有标记却没被识别」必须报错。
 *
 * <p>标记是写在 Word 里的文字，Word 的「句首字母大写」会把 {@code {{#each}} 改成 {@code {{#Each}}，
 * 改完肉眼看不出区别，所以关键字一律宽容；真正危险的是**静默残留** —— 标记没被识别就原样印在
 * 文档上，行数少了、列没展开都没人发现。</p>
 */
class DocTemplateMarkerCaseTest {

    private static List<Map<String, Object>> items() {
        List<Map<String, Object>> items = new ArrayList<>();
        items.add(item("SO-1", "SKU-A"));
        items.add(item("so-1", "SKU-B"));
        return items;
    }

    private static Map<String, Object> item(String orderNo, String sku) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("order_no", orderNo);
        item.put("sku", sku);
        return item;
    }

    private static Map<String, Object> params() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("order_no", "SO-1");
        params.put("items", items());
        return params;
    }

    private static byte[] template(String[]... rows) throws Exception {
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XWPFTable table = document.createTable(rows.length, 3);
            for (int r = 0; r < rows.length; r++) {
                for (int c = 0; c < 3 && c < rows[r].length; c++) {
                    if (!rows[r][c].isEmpty()) table.getRow(r).getCell(c).setText(rows[r][c]);
                }
            }
            document.write(out);
            return out.toByteArray();
        }
    }

    private static XWPFTable render(String[]... rows) throws Exception {
        byte[] rendered = new DocTemplateRenderer().render(template(rows), "DOCX", params(),
                List.of(), DocTemplateMergeAlign.DEFAULT, Map.of());
        return new XWPFDocument(new ByteArrayInputStream(rendered)).getTables().get(0);
    }

    private static XWPFTable renderColumns(String headerMarker, String dataMarker) throws Exception {
        byte[] template;
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XWPFTable table = document.createTable(2, 3);
            table.getRow(0).getCell(0).setText(headerMarker);
            table.getRow(1).getCell(0).setText(dataMarker);
            document.write(out);
            template = out.toByteArray();
        }
        Map<String, List<DocTemplateColumnSpec>> columns =
                Map.of("items", List.of(new DocTemplateColumnSpec("sku", "编码", 1)));
        byte[] rendered = new DocTemplateRenderer().render(template, "DOCX", params(),
                List.of(), DocTemplateMergeAlign.DEFAULT, columns);
        return new XWPFDocument(new ByteArrayInputStream(rendered)).getTables().get(0);
    }

    private static final String[] HEADER = {"编码", "数量", "备注"};

    @Test
    void loopKeywordsAreCaseInsensitive() throws Exception {
        for (String[] markers : new String[][]{
                {"{{#each items}}${sku}", "{{/each}}"},
                {"{{#EACH items}}${sku}", "{{/EACH}}"},
                {"{{#Each items}}${sku}", "{{/Each}}"},
                {"${#each items}${sku}", "${/each}"}}) {
            XWPFTable table = render(HEADER, new String[]{markers[0], "", markers[1]});
            assertEquals(3, table.getRows().size(), markers[0] + " 应该照常循环");
            assertEquals("SKU-A", table.getRow(1).getCell(0).getText());
            assertEquals("SKU-B", table.getRow(2).getCell(0).getText());
        }
    }

    @Test
    void loopModifierKeywordsAreCaseInsensitive() throws Exception {
        XWPFTable table = render(HEADER,
                new String[]{"{{#EACH items WHERE order_no = order_no}}${sku}", "", "{{/EACH}}"});
        assertEquals(2, table.getRows().size(), "WHERE 大写也要生效，只留 SO-1 那条");
        assertEquals("SKU-A", table.getRow(1).getCell(0).getText());
    }

    @Test
    void columnsKeywordsAreCaseInsensitive() throws Exception {
        XWPFTable table = renderColumns("{{#COLUMNS items HEADER}}", "{{#Columns items Data}}");
        assertEquals(3, table.getRows().size(), "表头 1 行 + 明细 2 行");
        assertEquals("编码", table.getRow(0).getCell(0).getText());
        assertEquals("SKU-A", table.getRow(1).getCell(0).getText());
    }

    @Test
    void columnsWhereIsCaseInsensitive() throws Exception {
        XWPFTable table = renderColumns("{{#columns items header}}",
                "{{#columns items data WHERE order_no = order_no}}");
        assertEquals(2, table.getRows().size(), "表头 + 1 条匹配明细；WHERE 大写不能失效");
        assertEquals("SKU-A", table.getRow(1).getCell(0).getText());
    }

    /** 缺数组名的开始标记会「认不出来」，这时必须报错，而不是把标记印在文档上。 */
    @Test
    void reportsStartMarkerWithoutArrayName() throws Exception {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> render(HEADER, new String[]{"{{#each}}", "${sku}", ""}));
        assertTrue(error.getMessage().contains("未被识别"), error.getMessage());
        assertTrue(error.getMessage().contains("数组名"), error.getMessage());
    }

    /** 拼错的关键字同样报错（而不是当成普通占位符抹成空串）。 */
    @Test
    void reportsMisspelledStartMarker() throws Exception {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> render(HEADER, new String[]{"{{#eac items}}", "${sku}", ""}));
        assertTrue(error.getMessage().contains("不认识的标记"), error.getMessage());
    }

    /** 正文里当例子讲的标记（混在文字里）原样保留，不参与判定。 */
    @Test
    void keepsMarkerMentionedInsideText() throws Exception {
        XWPFTable table = render(HEADER, new String[]{"循环要写 {{#each}} 再加数组名", "", ""});
        assertEquals("循环要写 {{#each}} 再加数组名", table.getRow(1).getCell(0).getText());
    }

    /** 段落里单独一个 `{{#each}}` 也要报错。 */
    @Test
    void reportsResidueInBodyParagraph() throws Exception {
        byte[] template;
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("明细：");
            document.createParagraph().createRun().setText("{{#each}}");
            document.write(out);
            template = out.toByteArray();
        }
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new DocTemplateRenderer().render(template, "DOCX", params(),
                        List.of(), DocTemplateMergeAlign.DEFAULT, Map.of()));
        assertTrue(error.getMessage().contains("未被识别"), error.getMessage());
    }
}
