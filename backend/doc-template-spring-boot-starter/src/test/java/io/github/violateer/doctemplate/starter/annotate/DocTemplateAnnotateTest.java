package io.github.violateer.doctemplate.starter.annotate;

import io.github.violateer.doctemplate.starter.db.DocTemplateDialect;
import io.github.violateer.doctemplate.starter.service.DocTemplateRenderer;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 标注链路：快照 → 落笔 → 用模拟数据渲染。
 *
 * <p>这三步必须一次跑通，否则「AI 出计划」这件事没有意义：计划写得再对，落笔出来的标记
 * 渲染器不认，用户拿到的就是一份坏模板。所以这里不测提示词、不测模型，只测这条确定性链路。</p>
 */
class DocTemplateAnnotateTest {

    private static final String[] HEADER = {"样品编号", "结果", "判定"};

    private static DocTemplateMarkPlan plan() {
        DocTemplateMarkPlan.Block main = new DocTemplateMarkPlan.Block("main", "检验报告主信息", "object",
                List.of(new DocTemplateMarkPlan.Field("report_no", "报告编号", "string", List.of("2026-0011"))));
        DocTemplateMarkPlan.Block items = new DocTemplateMarkPlan.Block("items", "检验明细", "array", List.of(
                new DocTemplateMarkPlan.Field("sample_no", "样品编号", "string", List.of("1#", "2#")),
                new DocTemplateMarkPlan.Field("result", "结果", "string", List.of("合格", "不合格")),
                new DocTemplateMarkPlan.Field("judge", "判定", "string", List.of("合格", "不合格"))));
        List<DocTemplateMarkPlan.Mark> marks = List.of(
                new DocTemplateMarkPlan.Mark("value", new DocTemplateMarkPlan.At(0, null, null, null, null, null, null),
                        "main", "report_no", "2026-0011", List.of(), null, null),
                // 样例行是第 1~2 行，模板只留第 1 行
                new DocTemplateMarkPlan.Mark("loop", new DocTemplateMarkPlan.At(null, null, 0, 1, 2, null, null),
                        "items", null, null, List.of(), null, null),
                new DocTemplateMarkPlan.Mark("value", new DocTemplateMarkPlan.At(null, null, 0, 1, null, 0, null),
                        "items", "sample_no", "1#", List.of(), null, null),
                new DocTemplateMarkPlan.Mark("value", new DocTemplateMarkPlan.At(null, null, 0, 1, null, 1, null),
                        "items", "result", "合格", List.of(), null, null),
                new DocTemplateMarkPlan.Mark("value", new DocTemplateMarkPlan.At(null, null, 0, 1, null, 2, null),
                        "items", "judge", "合格", List.of(), null, null));
        return new DocTemplateMarkPlan(List.of(main, items), marks, List.of());
    }

    /** 一份「已经填好」的报告：一段正文 + 一张表头 + 两行样例数据的表。 */
    private static byte[] docx() throws Exception {
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("报告编号：2026-0011");
            XWPFTable table = document.createTable(3, 3);
            for (int c = 0; c < HEADER.length; c++) table.getRow(0).getCell(c).setText(HEADER[c]);
            String[][] rows = {{"1#", "合格", "合格"}, {"2#", "不合格", "不合格"}};
            for (int r = 0; r < rows.length; r++) {
                for (int c = 0; c < rows[r].length; c++) table.getRow(r + 1).getCell(c).setText(rows[r][c]);
            }
            document.write(out);
            return out.toByteArray();
        }
    }

    private static byte[] xlsx() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("明细");
            Row header = sheet.createRow(0);
            for (int c = 0; c < HEADER.length; c++) header.createCell(c).setCellValue(HEADER[c]);
            Row data = sheet.createRow(1);
            data.createCell(0).setCellValue("1#");
            data.createCell(1).setCellValue("合格");
            data.createCell(2).setCellValue("合格");
            workbook.write(out);
            return out.toByteArray();
        }
    }

    @Test
    void writesMarkersThatTheRendererAccepts() throws Exception {
        byte[] annotated = new DocTemplateAnnotator().apply(docx(), "DOCX", plan()).bytes();
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(annotated))) {
            // 只替换「值」那一段，前缀和格式留着
            assertEquals("报告编号：${main.report_no}", document.getParagraphs().get(0).getText());
            XWPFTable table = document.getTables().get(0);
            assertEquals("{{#each items}}${sample_no}", table.getRow(1).getCell(0).getText());
            assertEquals("${result}", table.getRow(1).getCell(1).getText());
            assertEquals("${judge}{{/each}}", table.getRow(1).getCell(2).getText());
            // 表头不能被动
            assertEquals("样品编号", table.getRow(0).getCell(0).getText());
        }

        byte[] rendered = new DocTemplateRenderer().render(annotated, "DOCX", DocTemplateMockData.params(plan()));
        XWPFTable table = new XWPFDocument(new ByteArrayInputStream(rendered)).getTables().get(0);
        assertEquals(3, table.getRows().size(), "表头 + 两条样例数据");
        assertEquals("1#", table.getRow(1).getCell(0).getText());
        assertEquals("2#", table.getRow(2).getCell(0).getText());
        assertEquals("不合格", table.getRow(2).getCell(2).getText());
    }

    @Test
    void annotatesXlsxRows() throws Exception {
        DocTemplateMarkPlan plan = new DocTemplateMarkPlan(plan().blocks(), List.of(
                new DocTemplateMarkPlan.Mark("loop", new DocTemplateMarkPlan.At(null, 0, null, 1, null, null, null),
                        "items", null, null, List.of(), null, null),
                new DocTemplateMarkPlan.Mark("value", new DocTemplateMarkPlan.At(null, 0, null, 1, null, 0, null),
                        "items", "sample_no", "1#", List.of(), null, null),
                new DocTemplateMarkPlan.Mark("value", new DocTemplateMarkPlan.At(null, 0, null, 1, null, 1, null),
                        "items", "result", "合格", List.of(), null, null),
                new DocTemplateMarkPlan.Mark("value", new DocTemplateMarkPlan.At(null, 0, null, 1, null, 2, null),
                        "items", "judge", "合格", List.of(), null, null)), List.of());

        byte[] annotated = new DocTemplateAnnotator().apply(xlsx(), "XLSX", plan).bytes();
        byte[] rendered = new DocTemplateRenderer().render(annotated, "XLSX", DocTemplateMockData.params(plan));
        Sheet sheet = new XSSFWorkbook(new ByteArrayInputStream(rendered)).getSheetAt(0);
        assertEquals(2, sheet.getLastRowNum(), "表头 + 两条样例数据");
        assertEquals("1#", sheet.getRow(1).getCell(0).getStringCellValue());
        assertEquals("2#", sheet.getRow(2).getCell(0).getStringCellValue());
    }

    @Test
    @DisplayName("两个循环范围是包含关系时按嵌套报错，并说明只能留一层")
    void reportsNestedLoopsAsOverlap() {
        DocTemplateMarkPlan.Block items = plan().blocks().get(1);
        DocTemplateMarkPlan.Block materials = new DocTemplateMarkPlan.Block("materials", "物料明细", "array",
                List.of(new DocTemplateMarkPlan.Field("material", "物料", "string", List.of("A"))));
        DocTemplateMarkPlan plan = new DocTemplateMarkPlan(List.of(items, materials), List.of(
                new DocTemplateMarkPlan.Mark("loop", new DocTemplateMarkPlan.At(null, 0, null, 1, 4, null, null),
                        "items", null, null, List.of(), null, null),
                new DocTemplateMarkPlan.Mark("loop", new DocTemplateMarkPlan.At(null, 0, null, 2, 3, null, null),
                        "materials", null, null, List.of(), null, null)), List.of());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new DocTemplateAnnotator().apply(xlsx(), "XLSX", plan));
        // 这段文字会原样回灌给模型重试，必须点明是嵌套而不是「范围多写了一行」
        assertTrue(error.getMessage().contains("嵌套"), error.getMessage());
        assertTrue(error.getMessage().contains("materials"), error.getMessage());
    }

    @Test
    @DisplayName("数字单元格可以直接落标记，落笔时转成文本")
    void marksNumericCell() throws Exception {
        byte[] source;
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("明细");
            Row row = sheet.createRow(0);
            row.createCell(0).setCellValue(1);
            row.createCell(1).setCellValue("合格");
            workbook.write(out);
            source = out.toByteArray();
        }
        DocTemplateMarkPlan plan = new DocTemplateMarkPlan(plan().blocks(), List.of(
                new DocTemplateMarkPlan.Mark("value", new DocTemplateMarkPlan.At(null, 0, null, 0, null, 0, null),
                        "items", "sample_no", "1", List.of(), null, null)), List.of());

        byte[] annotated = new DocTemplateAnnotator().apply(source, "XLSX", plan).bytes();

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(annotated))) {
            Cell cell = workbook.getSheetAt(0).getRow(0).getCell(0);
            assertEquals(CellType.STRING, cell.getCellType(), "数字格落笔后应当变成文本格");
            assertEquals("${items.sample_no}", cell.getStringCellValue());
        }
    }

    @Test
    @DisplayName("公式格拒绝落标记，避免把公式覆盖掉")
    void rejectsFormulaCell() throws Exception {
        byte[] source;
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("明细");
            Row row = sheet.createRow(0);
            row.createCell(0).setCellFormula("1+1");
            row.createCell(1).setCellValue("合格");
            workbook.write(out);
            source = out.toByteArray();
        }
        DocTemplateMarkPlan plan = new DocTemplateMarkPlan(plan().blocks(), List.of(
                new DocTemplateMarkPlan.Mark("value", new DocTemplateMarkPlan.At(null, 0, null, 0, null, 0, null),
                        "items", "sample_no", "2", List.of(), null, null)), List.of());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new DocTemplateAnnotator().apply(source, "XLSX", plan));
        assertTrue(error.getMessage().contains("公式"), error.getMessage());
    }

    @Test
    @DisplayName("两条标记归到同一格且字段不同时报错，而不是静默丢掉一个")
    void rejectsTwoDifferentFieldsAfterMapping() throws Exception {
        byte[] source;
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("明细");
            sheet.createRow(0).createCell(0).setCellValue("样品编号");
            sheet.createRow(1).createCell(0).setCellValue("1#");
            sheet.createRow(2).createCell(0).setCellValue("2#");
            workbook.write(out);
            source = out.toByteArray();
        }
        // 一条标模板行、一条标样例行：归位后落到同一格，但字段不同
        DocTemplateMarkPlan plan = new DocTemplateMarkPlan(plan().blocks(), List.of(
                new DocTemplateMarkPlan.Mark("loop", new DocTemplateMarkPlan.At(null, 0, null, 1, 2, null, null),
                        "items", null, null, List.of(), null, null),
                new DocTemplateMarkPlan.Mark("value", new DocTemplateMarkPlan.At(null, 0, null, 1, null, 0, null),
                        "items", "sample_no", "1#", List.of(), null, null),
                new DocTemplateMarkPlan.Mark("value", new DocTemplateMarkPlan.At(null, 0, null, 2, null, 0, null),
                        "items", "result", "2#", List.of(), null, null)), List.of());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new DocTemplateAnnotator().apply(source, "XLSX", plan));
        assertTrue(error.getMessage().contains("只能属于一个字段"), error.getMessage());
    }

    @Test
    @DisplayName("merge 写成单个数字时按单元素数组收下")
    void acceptsSingleMergeValue() {
        DocTemplateMarkPlan plan = DocTemplateMarkPlan.parse("""
                {"blocks": [], "marks": [
                  {"kind": "loop", "at": {"sheet": 0, "row": 1, "rowTo": 2}, "block": "items", "merge": 1}
                ]}
                """);
        assertEquals(List.of(1), plan.marks().get(0).merge(), "模型常写 merge: 1，不能因此整份计划解析失败");
    }

    @Test
    @DisplayName("动态列不在标注范围内，跳过而不是报错")
    void skipsColumnsMark() {
        DocTemplateMarkPlan plan = new DocTemplateMarkPlan(plan().blocks(), List.of(
                new DocTemplateMarkPlan.Mark("columns", new DocTemplateMarkPlan.At(null, 0, null, 0, 1, 1, null),
                        "items", null, null, List.of(), null, null)), List.of());

        assertDoesNotThrow(plan::validate);
    }

    @Test
    @DisplayName("解析计划时丢掉动态列标记，其余标记照常保留")
    void dropsColumnsMarkOnParse() {
        String json = """
                {"blocks": [{"code": "items", "name": "检验明细", "shape": "array",
                             "fields": [{"field": "no", "label": "编号", "type": "string", "samples": ["1#"]}]}],
                 "marks": [{"kind": "columns", "at": {"sheet": 0, "row": 0, "rowTo": 1, "cell": 1},
                            "block": "items"},
                           {"kind": "value", "at": {"sheet": 0, "row": 1, "cell": 0},
                            "block": "items", "field": "no", "replace": "1#"}],
                 "ignore": []}
                """;

        DocTemplateMarkPlan plan = DocTemplateMarkPlan.parse(json);

        assertEquals(1, plan.marks().size());
        assertEquals("value", plan.marks().get(0).kind());
    }

    @Test
    @DisplayName("嵌套循环的 where 不参与落笔，校验也不再拦")
    void ignoresWhereOnNestedLoop() {
        DocTemplateMarkPlan plan = new DocTemplateMarkPlan(plan().blocks(), List.of(
                new DocTemplateMarkPlan.Mark("loop", new DocTemplateMarkPlan.At(null, 0, null, 1, 2, null, null),
                        "items", null, null, List.of(), null, " kind = type ")), List.of());

        assertDoesNotThrow(plan::validate);
    }

    @Test
    void reportsWhenTheTextToReplaceIsNotFound() {
        DocTemplateMarkPlan plan = new DocTemplateMarkPlan(plan().blocks(), List.of(
                new DocTemplateMarkPlan.Mark("value", new DocTemplateMarkPlan.At(0, null, null, null, null, null, null),
                        "main", "report_no", "2026-9999", List.of(), null, null)), List.of());
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new DocTemplateAnnotator().apply(docx(), "DOCX", plan));
        assertTrue(error.getMessage().contains("找不到要替换的文字"), error.getMessage());
        assertTrue(error.getMessage().contains("报告编号：2026-0011"), "报错要带上实际内容，否则没法排查");
    }

    @Test
    void mockSqlIsPortableExceptOracle() {
        DocTemplateMarkPlan.Block items = plan().blocks().get(1);
        String mysql = DocTemplateMockData.sql(items, DocTemplateDialect.MYSQL);
        assertTrue(mysql.startsWith("select '1#' as sample_no"), mysql);
        assertTrue(mysql.contains("union all"), mysql);
        assertFalse(mysql.contains("from dual"), "除 Oracle 外都不需要 FROM");

        String oracle = DocTemplateMockData.sql(items, DocTemplateDialect.ORACLE);
        assertEquals(2, oracle.split("from dual", -1).length - 1, "Oracle 每一段都要 from dual：" + oracle);
    }

    @Test
    void rejectsReservedWordAsFieldName() {
        DocTemplateMarkPlan.Block block = new DocTemplateMarkPlan.Block("main", "主信息", "object",
                List.of(new DocTemplateMarkPlan.Field("date", "日期", "date", List.of("2026-11-01"))));
        DocTemplateMarkPlan plan = new DocTemplateMarkPlan(List.of(block), List.of(
                new DocTemplateMarkPlan.Mark("value", new DocTemplateMarkPlan.At(0, null, null, null, null, null, null),
                        "main", "date", "2026-11-01", List.of(), null, null)), List.of());
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, plan::validate);
        assertTrue(error.getMessage().contains("SQL 关键字"), error.getMessage());
    }

    @Test
    void snapshotCoordinatesMatchWhatTheAnnotatorUses() throws Exception {
        DocTemplateSnapshot snapshot = new DocTemplateSnapshotReader().read(docx(), "DOCX");
        assertEquals(1, snapshot.paragraphs().size());
        assertEquals(0, snapshot.paragraphs().get(0).index());
        assertEquals(1, snapshot.tables().size());
        DocTemplateSnapshot.Table table = snapshot.tables().get(0);
        assertEquals(3, table.cols());
        assertEquals(3, table.rowCount());
        assertEquals("1#", table.rows().get(1).cells().get(0).text());
        assertEquals(0, table.rows().get(1).cells().get(0).col());
    }

    /** 区域循环：区域内的行整体重复，样例行一行都不删，标记由落笔器插在区域前后各一行。 */
    @Test
    void regionLoopRepeatsEveryRowInTheRegion() throws Exception {
        DocTemplateMarkPlan.Block items = new DocTemplateMarkPlan.Block("items", "明细", "array", List.of(
                new DocTemplateMarkPlan.Field("sample_no", "样品编号", "string", List.of("1#", "2#")),
                new DocTemplateMarkPlan.Field("result", "结果", "string", List.of("合格", "合格")),
                new DocTemplateMarkPlan.Field("judge", "判定", "string", List.of("合格", "合格"))));
        DocTemplateMarkPlan plan = new DocTemplateMarkPlan(List.of(items), List.of(
                new DocTemplateMarkPlan.Mark("region", new DocTemplateMarkPlan.At(null, null, 0, 1, 2, null, null),
                        "items", null, null, List.of(), null, null),
                new DocTemplateMarkPlan.Mark("value", new DocTemplateMarkPlan.At(null, null, 0, 1, null, 0, null),
                        "items", "sample_no", "1#", List.of(), null, null),
                new DocTemplateMarkPlan.Mark("value", new DocTemplateMarkPlan.At(null, null, 0, 2, null, 0, null),
                        "items", "sample_no", "2#", List.of(), null, null)), List.of());

        byte[] annotated = new DocTemplateAnnotator().apply(docx(), "DOCX", plan).bytes();
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(annotated))) {
            XWPFTable table = document.getTables().get(0);
            assertEquals(5, table.getRows().size(), "表头 + 起标记行 + 两行区域 + 止标记行");
            assertEquals("{{#each items}}", table.getRow(1).getCell(0).getText());
            assertEquals("{{/each}}", table.getRow(4).getCell(0).getText());
            // 区域里的样例行一行都没删，两行的值标记都写成短名（区域里每一行都属于当前项）
            assertEquals("${sample_no}", table.getRow(2).getCell(0).getText());
            assertEquals("${sample_no}", table.getRow(3).getCell(0).getText());
        }

        byte[] rendered = new DocTemplateRenderer().render(annotated, "DOCX", DocTemplateMockData.params(plan));
        XWPFTable table = new XWPFDocument(new ByteArrayInputStream(rendered)).getTables().get(0);
        assertEquals(5, table.getRows().size(), "表头 + 两条样例 × 每项两行");
        assertEquals("1#", table.getRow(1).getCell(0).getText());
        assertEquals("1#", table.getRow(2).getCell(0).getText(), "同一项的两行用同一份数据");
        assertEquals("2#", table.getRow(3).getCell(0).getText());
    }

    /** Excel 里的区域循环：插两行放标记，样例行不删，渲染时按项数展开。 */
    @Test
    void regionLoopWorksOnSheets() throws Exception {
        DocTemplateMarkPlan.Block items = new DocTemplateMarkPlan.Block("items", "明细", "array", List.of(
                new DocTemplateMarkPlan.Field("sample_no", "样品编号", "string", List.of("1#", "2#")),
                new DocTemplateMarkPlan.Field("result", "结果", "string", List.of("合格", "合格")),
                new DocTemplateMarkPlan.Field("judge", "判定", "string", List.of("合格", "合格"))));
        DocTemplateMarkPlan plan = new DocTemplateMarkPlan(List.of(items), List.of(
                new DocTemplateMarkPlan.Mark("region", new DocTemplateMarkPlan.At(null, 0, null, 1, 2, null, null),
                        "items", null, null, List.of(), null, null),
                new DocTemplateMarkPlan.Mark("value", new DocTemplateMarkPlan.At(null, 0, null, 1, null, 0, null),
                        "items", "sample_no", "1#", List.of(), null, null)), List.of());

        byte[] annotated = new DocTemplateAnnotator().apply(regionXlsx(), "XLSX", plan).bytes();
        Sheet sheet = new XSSFWorkbook(new ByteArrayInputStream(annotated)).getSheetAt(0);
        assertEquals(5, sheet.getLastRowNum() + 1, "表头 + 起标记行 + 两行区域 + 止标记行");
        assertEquals("{{#each items}}", sheet.getRow(1).getCell(0).getStringCellValue());
        assertEquals("{{/each}}", sheet.getRow(4).getCell(0).getStringCellValue());

        byte[] rendered = new DocTemplateRenderer().render(annotated, "XLSX", DocTemplateMockData.params(plan));
        sheet = new XSSFWorkbook(new ByteArrayInputStream(rendered)).getSheetAt(0);
        assertEquals(5, sheet.getLastRowNum() + 1, "表头 + 两条样例 × 每项两行");
        assertEquals("1#", sheet.getRow(1).getCell(0).getStringCellValue());
        assertEquals("2#", sheet.getRow(3).getCell(0).getStringCellValue());
    }

    /** 区域循环的样例：表头 + 两行同结构数据。 */
    private static byte[] regionXlsx() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("明细");
            Row header = sheet.createRow(0);
            for (int c = 0; c < HEADER.length; c++) header.createCell(c).setCellValue(HEADER[c]);
            String[][] rows = {{"1#", "合格", "合格"}, {"2#", "不合格", "不合格"}};
            for (int r = 0; r < rows.length; r++) {
                Row row = sheet.createRow(r + 1);
                for (int c = 0; c < rows[r].length; c++) row.createCell(c).setCellValue(rows[r][c]);
            }
            workbook.write(out);
            return out.toByteArray();
        }
    }
}
