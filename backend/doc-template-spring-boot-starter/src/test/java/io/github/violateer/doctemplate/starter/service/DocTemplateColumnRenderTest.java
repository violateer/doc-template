package io.github.violateer.doctemplate.starter.service;

import io.github.violateer.doctemplate.starter.support.DocTemplateColumnSpec;
import io.github.violateer.doctemplate.starter.support.DocTemplateMergeAlign;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
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
 * 表格动态列（{@code {{#columns 块 角色}}}）的渲染冒烟测试：不连库、不落盘，全在内存里拼模板再渲染。
 */
class DocTemplateColumnRenderTest {

    private static final List<DocTemplateColumnSpec> SPECS = List.of(
            new DocTemplateColumnSpec("tensile", "抗拉强度", 1),
            new DocTemplateColumnSpec("elongation", "伸长率", 2));

    private static List<Map<String, Object>> items() {
        List<Map<String, Object>> items = new ArrayList<>();
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("tensile", "1");
        first.put("elongation", "2");
        first.put("remark", "ok");
        items.add(first);
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("tensile", "3");
        second.put("elongation", "4");
        second.put("remark", "no");
        items.add(second);
        return items;
    }

    private static byte[] docxTemplate() throws Exception {
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XWPFTable table = document.createTable(2, 3);
            table.getRow(0).getCell(0).setText("序号");
            table.getRow(0).getCell(1).setText("{{#columns items header}}");
            table.getRow(0).getCell(2).setText("备注");
            table.getRow(1).getCell(0).setText("${_index}");
            table.getRow(1).getCell(1).setText("{{#columns items data}}");
            table.getRow(1).getCell(2).setText("${remark}");
            document.write(out);
            return out.toByteArray();
        }
    }

    @Test
    void expandsDocxColumnsBetweenFixedColumns() throws Exception {
        byte[] rendered = new DocTemplateRenderer().render(docxTemplate(), "DOCX",
                Map.of("items", items()), List.of(), DocTemplateMergeAlign.DEFAULT, Map.of("items", SPECS));
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(rendered))) {
            XWPFTable table = document.getTables().get(0);
            assertEquals(3, table.getRows().size(), "表头 1 行 + 数据 2 行");
            assertEquals(4, table.getRow(0).getTableCells().size(), "固定 2 列 + 动态 2 列");
            assertEquals("抗拉强度", table.getRow(0).getCell(1).getText());
            assertEquals("伸长率", table.getRow(0).getCell(2).getText());
            assertEquals("1", table.getRow(1).getCell(0).getText(), "${_index} 从 1 开始");
            assertEquals("1", table.getRow(1).getCell(1).getText());
            assertEquals("2", table.getRow(1).getCell(2).getText());
            assertEquals("ok", table.getRow(1).getCell(3).getText());
            assertEquals("2", table.getRow(2).getCell(0).getText());
            assertEquals("4", table.getRow(2).getCell(2).getText());
        }
    }

    @Test
    void reportsMissingColumnSource() throws Exception {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new DocTemplateRenderer().render(docxTemplate(), "DOCX",
                        Map.of("items", items()), List.of(), DocTemplateMergeAlign.DEFAULT, Map.of()));
        assertEquals(true, error.getMessage().contains("没有配置列源"), error.getMessage());
    }

    private static byte[] xlsxTemplate() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("report");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("序号");
            header.createCell(1).setCellValue("{{#columns items header}}");
            header.createCell(2).setCellValue("备注");
            Row data = sheet.createRow(1);
            data.createCell(0).setCellValue("${_index}");
            data.createCell(1).setCellValue("{{#columns items data}}");
            data.createCell(2).setCellValue("${remark}");
            workbook.write(out);
            return out.toByteArray();
        }
    }

    @Test
    void expandsXlsxColumnsBetweenFixedColumns() throws Exception {
        byte[] rendered = new DocTemplateRenderer().render(xlsxTemplate(), "XLSX",
                Map.of("items", items()), List.of(), DocTemplateMergeAlign.DEFAULT, Map.of("items", SPECS));
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(rendered))) {
            Sheet sheet = workbook.getSheetAt(0);
            assertEquals(3, sheet.getLastRowNum() + 1, "表头 1 行 + 数据 2 行");
            assertEquals("抗拉强度", sheet.getRow(0).getCell(1).getStringCellValue());
            assertEquals("伸长率", sheet.getRow(0).getCell(2).getStringCellValue());
            // 整格占位符取到数字时会写成数字单元格，这是 XLSX 的既有规则；字符串值仍是文本
            assertEquals(1d, sheet.getRow(1).getCell(0).getNumericCellValue());
            assertEquals("2", sheet.getRow(1).getCell(2).getStringCellValue());
            assertEquals("ok", sheet.getRow(1).getCell(3).getStringCellValue());
            assertEquals(2d, sheet.getRow(2).getCell(0).getNumericCellValue());
        }
    }

    @Test
    void keepsSpanRowMergedAcrossRegion() throws Exception {
        byte[] template;
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XWPFTable table = document.createTable(3, 2);
            table.getRow(0).getCell(0).setText("{{#columns items span}}合计");
            table.getRow(0).getCell(1).setText("备注");
            table.getRow(1).getCell(0).setText("{{#columns items header}}");
            table.getRow(2).getCell(0).setText("{{#columns items data}}");
            document.write(out);
            template = out.toByteArray();
        }
        byte[] rendered = new DocTemplateRenderer().render(template, "DOCX",
                Map.of("items", items()), List.of(), DocTemplateMergeAlign.DEFAULT, Map.of("items", SPECS));
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(rendered))) {
            XWPFTable table = document.getTables().get(0);
            assertEquals("合计", table.getRow(0).getCell(0).getText(), "span 形式保留格内文字");
            assertEquals(2, table.getRow(0).getTableCells().size(), "span 不拆格，只把那一格拉宽");
            assertEquals("备注", table.getRow(0).getCell(1).getText());
            assertEquals(3, table.getRow(1).getTableCells().size(), "表头行拆成 2 个动态列 + 1 个固定列");
            assertEquals("抗拉强度", table.getRow(1).getCell(0).getText());
        }
    }

    /**
     * 工作表的 span 行合并之后，合并区里的每一格都要带上标记格的样式。
     *
     * <p>Excel 的合并只是把区域盖住，边框仍然一格一格画：只给第一格设边框的话，
     * 右边扩出来的几格上下边框会缺一截。</p>
     */
    @Test
    void spanRowCarriesMarkerStyleAcrossMergedRegion() throws Exception {
        byte[] template;
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("report");
            CellStyle bordered = workbook.createCellStyle();
            bordered.setBorderTop(BorderStyle.THIN);
            bordered.setBorderBottom(BorderStyle.THIN);
            bordered.setBorderLeft(BorderStyle.THIN);
            bordered.setBorderRight(BorderStyle.THIN);

            Row span = sheet.createRow(0);
            Cell spanCell = span.createCell(0);
            spanCell.setCellValue("{{#columns items span}}检验结果");
            spanCell.setCellStyle(bordered);
            span.createCell(1).setCellValue("备注");

            Row header = sheet.createRow(1);
            header.createCell(0).setCellValue("{{#columns items header}}");
            header.createCell(1).setCellValue("备注");

            Row data = sheet.createRow(2);
            data.createCell(0).setCellValue("{{#columns items data}}");
            data.createCell(1).setCellValue("${remark}");

            workbook.write(out);
            template = out.toByteArray();
        }
        byte[] rendered = new DocTemplateRenderer().render(template, "XLSX",
                Map.of("items", items()), List.of(), DocTemplateMergeAlign.DEFAULT, Map.of("items", SPECS));
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(rendered))) {
            Sheet sheet = workbook.getSheetAt(0);
            assertEquals("检验结果", sheet.getRow(0).getCell(0).getStringCellValue(), "span 形式保留格内文字");
            assertTrue(sheet.getMergedRegions().stream().anyMatch(region ->
                            region.getFirstRow() == 0 && region.getLastRow() == 0
                                    && region.getFirstColumn() == 0 && region.getLastColumn() == 1),
                    "span 行整段合并成一个格子：" + sheet.getMergedRegions());
            for (int column = 0; column < SPECS.size(); column++) {
                CellStyle style = sheet.getRow(0).getCell(column).getCellStyle();
                assertEquals(BorderStyle.THIN, style.getBorderTop(), "合并区第 " + (column + 1) + " 格的上边框");
                assertEquals(BorderStyle.THIN, style.getBorderBottom(), "合并区第 " + (column + 1) + " 格的下边框");
            }
            assertEquals("备注", sheet.getRow(0).getCell(SPECS.size()).getStringCellValue(), "列区右侧的固定列照常右移");
        }
    }

    /** 表头行与数据行的标记文字一样，靠角色关键字区分；不写角色直接报错，不猜行号。 */
    @Test
    void reportsMissingColumnsRole() throws Exception {
        byte[] template;
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XWPFTable table = document.createTable(2, 1);
            table.getRow(0).getCell(0).setText("{{#columns items}}");
            table.getRow(1).getCell(0).setText("{{#columns items data}}");
            document.write(out);
            template = out.toByteArray();
        }
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new DocTemplateRenderer().render(template, "DOCX",
                        Map.of("items", items()), List.of(), DocTemplateMergeAlign.DEFAULT, Map.of("items", SPECS)));
        assertEquals(true, error.getMessage().contains("没写角色"), error.getMessage());
    }

    /** 数据行只能有一行，写两行直接报错。 */
    @Test
    void reportsDuplicateDataRow() throws Exception {
        byte[] template;
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XWPFTable table = document.createTable(3, 1);
            table.getRow(0).getCell(0).setText("{{#columns items header}}");
            table.getRow(1).getCell(0).setText("{{#columns items data}}");
            table.getRow(2).getCell(0).setText("{{#columns items data}}");
            document.write(out);
            template = out.toByteArray();
        }
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new DocTemplateRenderer().render(template, "DOCX",
                        Map.of("items", items()), List.of(), DocTemplateMergeAlign.DEFAULT, Map.of("items", SPECS)));
        assertEquals(true, error.getMessage().contains("数据行只能有一行"), error.getMessage());
    }

    /**
     * 数据行行首行尾可以写一对 {@code {{#each 块}}…{{/each}}} 把块名标出来，
     * 中间的格子就不用逐个写块前缀；它只做校验，循环仍由 {@code data} 角色驱动，不会展开两遍。
     */
    @Test
    void acceptsRowLoopAnnotationOnDataRow() throws Exception {
        byte[] template;
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XWPFTable table = document.createTable(2, 3);
            table.getRow(0).getCell(0).setText("序号");
            table.getRow(0).getCell(1).setText("{{#columns items header}}");
            table.getRow(0).getCell(2).setText("备注");
            table.getRow(1).getCell(0).setText("{{#each items}}${_index}");
            table.getRow(1).getCell(1).setText("{{#columns items data}}");
            table.getRow(1).getCell(2).setText("${remark}{{/each}}");
            document.write(out);
            template = out.toByteArray();
        }
        byte[] rendered = new DocTemplateRenderer().render(template, "DOCX",
                Map.of("items", items()), List.of(), DocTemplateMergeAlign.DEFAULT, Map.of("items", SPECS));
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(rendered))) {
            XWPFTable table = document.getTables().get(0);
            assertEquals(3, table.getRows().size(), "数据行只展开 2 份，注解标记不额外驱动循环");
            assertEquals("1", table.getRow(1).getCell(0).getText(), "注解标记被清掉，序号照常渲染");
            assertEquals("ok", table.getRow(1).getCell(3).getText());
            assertEquals("2", table.getRow(2).getCell(0).getText());
        }
    }

    /** 注解标记写的块和列区标记的块对不上时直接报错，避免「看着写了块名其实没用」。 */
    @Test
    void reportsRowLoopAnnotationBlockMismatch() throws Exception {
        byte[] template;
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XWPFTable table = document.createTable(2, 3);
            table.getRow(0).getCell(0).setText("序号");
            table.getRow(0).getCell(1).setText("{{#columns items header}}");
            table.getRow(1).getCell(0).setText("{{#each other}}${_index}");
            table.getRow(1).getCell(1).setText("{{#columns items data}}");
            document.write(out);
            template = out.toByteArray();
        }
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new DocTemplateRenderer().render(template, "DOCX",
                        Map.of("items", items(), "other", items()), List.of(),
                        DocTemplateMergeAlign.DEFAULT, Map.of("items", SPECS)));
        assertEquals(true, error.getMessage().contains("不一致"), error.getMessage());
    }
}
