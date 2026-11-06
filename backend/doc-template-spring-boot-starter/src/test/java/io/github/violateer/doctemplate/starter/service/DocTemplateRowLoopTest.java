package io.github.violateer.doctemplate.starter.service;

import io.github.violateer.doctemplate.starter.support.DocTemplateMergeAlign;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
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
 * 明细行循环的写法约束：开始标记和结束标记**必须成对**。
 *
 * <p>行内循环把两个标记写在同一行（{@code {{#each items}}…{{/each}}}），区域循环让两个标记各占一行。
 * 只写开始标记不再按单行循环兜底，直接报错 —— 少写一个 {@code {{/each}}} 时静默出一份少了几行的文档，
 * 比报错难查得多。</p>
 */
class DocTemplateRowLoopTest {

    private static List<Map<String, Object>> items() {
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = 1; i <= 2; i++) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("sku", "SKU-" + i);
            item.put("qty", i * 10);
            items.add(item);
        }
        return items;
    }

    /** 表格固定 3 列，{@code rows} 每一项是一行的单元格文字，短的补空。 */
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

    private static final String[] HEADER = {"编码", "数量", "备注"};

    private static XWPFTable render(String[]... rows) throws Exception {
        byte[] rendered = new DocTemplateRenderer().render(template(rows), "DOCX",
                Map.of("items", items()), List.of(), DocTemplateMergeAlign.DEFAULT, Map.of());
        return new XWPFDocument(new ByteArrayInputStream(rendered)).getTables().get(0);
    }

    private static IllegalArgumentException fails(String[]... rows) throws Exception {
        return assertThrows(IllegalArgumentException.class, () -> render(rows));
    }

    @Test
    void repeatsRowWhenBothMarkersAreOnSameRow() throws Exception {
        XWPFTable table = render(HEADER,
                new String[]{"{{#each items}}${sku}", "${qty} 件", "{{/each}}"});
        assertEquals(3, table.getRows().size(), "表头 1 行 + 数据 2 行");
        assertEquals("SKU-1", table.getRow(1).getCell(0).getText());
        assertEquals("10 件", table.getRow(1).getCell(1).getText(), "标记被清掉，不留痕迹");
        assertEquals("SKU-2", table.getRow(2).getCell(0).getText());
        assertEquals("20 件", table.getRow(2).getCell(1).getText());
    }

    /** 区域循环：两个标记各占一行，中间的行整体重复。 */
    @Test
    void repeatsRegionWhenMarkersOwnTheirRows() throws Exception {
        XWPFTable table = render(HEADER,
                new String[]{"{{#each items}}"},
                new String[]{"${sku}", "${qty}"},
                new String[]{"{{/each}}"});
        assertEquals(3, table.getRows().size(), "表头 1 行 + 区域展开 2 行，两个标记行被删掉");
        assertEquals("SKU-1", table.getRow(1).getCell(0).getText());
        assertEquals("20", table.getRow(2).getCell(1).getText());
    }

    @Test
    void reportsInlineLoopWithoutEnd() throws Exception {
        IllegalArgumentException error = fails(HEADER,
                new String[]{"{{#each items}}${sku}", "${qty}", "备注"});
        assertTrue(error.getMessage().contains("没有配对的 {{/each}}"), error.getMessage());
    }

    @Test
    void reportsRegionStartWithoutEnd() throws Exception {
        IllegalArgumentException error = fails(HEADER,
                new String[]{"{{#each items}}"},
                new String[]{"${sku}", "${qty}"});
        assertTrue(error.getMessage().contains("没有配对的 {{/each}}"), error.getMessage());
    }

    @Test
    void reportsStrayLoopEnd() throws Exception {
        IllegalArgumentException error = fails(HEADER,
                new String[]{"${sku}", "${qty}"},
                new String[]{"{{/each}}"});
        assertTrue(error.getMessage().contains("没有配对的 {{#each}}"), error.getMessage());
    }

    /** XLSX 的行循环同样要求成对：只多写一个结束标记也报错，不会把标记原样留在单元格里。 */
    @Test
    void reportsStrayLoopEndInSheet() throws Exception {
        byte[] template;
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("明细");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("编码");
            header.createCell(1).setCellValue("数量");
            Row data = sheet.createRow(1);
            data.createCell(0).setCellValue("${sku}");
            data.createCell(1).setCellValue("${qty}");
            sheet.createRow(2).createCell(0).setCellValue("{{/each}}");
            workbook.write(out);
            template = out.toByteArray();
        }
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new DocTemplateRenderer().render(template, "XLSX", Map.of("items", items()),
                        List.of(), DocTemplateMergeAlign.DEFAULT, Map.of()));
        assertTrue(error.getMessage().contains("没有配对的 {{#each}}"), error.getMessage());
    }

    /** 段落块循环也一样：单独一段 `{{/each}}` 直接报错。 */
    @Test
    void reportsStrayLoopEndInBodyParagraph() throws Exception {
        byte[] template;
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("明细：");
            document.createParagraph().createRun().setText("{{/each}}");
            document.write(out);
            template = out.toByteArray();
        }
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new DocTemplateRenderer().render(template, "DOCX", Map.of("items", items()),
                        List.of(), DocTemplateMergeAlign.DEFAULT, Map.of()));
        assertTrue(error.getMessage().contains("没有配对的 {{#each}}"), error.getMessage());
    }
}
