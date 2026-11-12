package io.github.violateer.doctemplate.starter.annotate;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTcPr;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 把 docx / xlsx 读成 {@link DocTemplateSnapshot}。
 *
 * <p>只做「读」：不改文件、不猜语义。哪些格子是变量由调用方（模型或人）判断，
 * 这里只保证坐标、文本、样式信号和落笔时完全一致。</p>
 */
public class DocTemplateSnapshotReader {

    public DocTemplateSnapshot read(byte[] bytes, String fileType) throws Exception {
        if (bytes == null || bytes.length == 0) throw new IllegalArgumentException("模板文件内容为空");
        return "XLSX".equalsIgnoreCase(fileType) ? readXlsx(bytes) : readDocx(bytes);
    }

    // ------------------------------------------------------------------ DOCX

    private DocTemplateSnapshot readDocx(byte[] bytes) throws Exception {
        List<DocTemplateSnapshot.Paragraph> paragraphs = new ArrayList<>();
        List<DocTemplateSnapshot.Table> tables = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            List<XWPFParagraph> bodyParagraphs = document.getParagraphs();
            for (int i = 0; i < bodyParagraphs.size(); i++) {
                XWPFParagraph paragraph = bodyParagraphs.get(i);
                String text = paragraph.getText();
                // 空段落不列：真实报告里大量空行，列出只会把 token 消耗在不可见内容上
                if (text == null || text.isBlank()) continue;
                int runs = paragraph.getRuns() == null ? 0 : paragraph.getRuns().size();
                paragraphs.add(new DocTemplateSnapshot.Paragraph(i, text, paragraphStyle(paragraph), runs));
            }
            List<XWPFTable> bodyTables = document.getTables();
            for (int i = 0; i < bodyTables.size(); i++) {
                tables.add(readTable(bodyTables.get(i), i, warnings));
            }
            if (bodyTables.isEmpty() && paragraphs.isEmpty()) {
                warnings.add("文档里没有读到任何段落或表格，可能是一份空文档");
            }
        }
        return new DocTemplateSnapshot("DOCX", "0-based", paragraphs, tables, null, warnings);
    }

    private DocTemplateSnapshot.Table readTable(XWPFTable table, int index, List<String> warnings) {
        List<DocTemplateSnapshot.Row> rows = new ArrayList<>();
        int cols = 0;
        List<XWPFTableRow> tableRows = table.getRows();
        for (int r = 0; r < tableRows.size(); r++) {
            XWPFTableRow row = tableRows.get(r);
            List<DocTemplateSnapshot.Cell> cells = new ArrayList<>();
            int col = 0;
            for (XWPFTableCell cell : row.getTableCells()) {
                int span = spanOf(cell);
                List<String> cellParagraphs = new ArrayList<>();
                for (XWPFParagraph paragraph : cell.getParagraphs()) cellParagraphs.add(paragraph.getText());
                // 单段落时不重复输出，text 本身就是那一段
                List<String> paragraphs = cellParagraphs.size() > 1 ? cellParagraphs : null;
                cells.add(new DocTemplateSnapshot.Cell(col, span, cell.getText(), paragraphs, cellStyle(cell),
                        isVMergeContinue(cell) ? Boolean.TRUE : null));
                if (!cell.getTables().isEmpty()) {
                    warnings.add("表格 " + index + " 第 " + r + " 行第 " + col
                            + " 列里有嵌套表格，快照没有展开；嵌套表里的内容不会被标注");
                }
                col += span;
            }
            cols = Math.max(cols, col);
            rows.add(new DocTemplateSnapshot.Row(r, cells));
        }
        return new DocTemplateSnapshot.Table(index, cols, rows.size(), rows);
    }

    /** 横向合并的格子在 Word 里占多个网格列；没有声明时算 1 列。 */
    static int spanOf(XWPFTableCell cell) {
        CTTcPr properties = cell.getCTTc().getTcPr();
        if (properties == null || properties.getGridSpan() == null) return 1;
        return Math.max(1, properties.getGridSpan().getVal().intValue());
    }

    /**
     * 这一格是不是纵向合并的续格。
     *
     * <p>{@code <w:vMerge/>} 不带 {@code val} 就是续格，带 {@code val="restart"} 是合并区的第一格。
     * 续格在 Word 里没有自己的文字，标注它会写进空段落，所以快照要标出来、落笔要拦住。</p>
     */
    static boolean isVMergeContinue(XWPFTableCell cell) {
        CTTcPr properties = cell.getCTTc().getTcPr();
        if (properties == null || properties.getVMerge() == null) return false;
        String value = properties.getVMerge().getVal() == null ? null : properties.getVMerge().getVal().toString();
        return value == null || !"restart".equalsIgnoreCase(value);
    }

    private String cellStyle(XWPFTableCell cell) {
        String align = null;
        boolean bold = false;
        for (XWPFParagraph paragraph : cell.getParagraphs()) {
            if (align == null) align = alignment(paragraph.getAlignment());
            for (XWPFRun run : paragraph.getRuns()) {
                if (run.isBold()) {
                    bold = true;
                    break;
                }
            }
        }
        return style(bold, align);
    }

    private String paragraphStyle(XWPFParagraph paragraph) {
        boolean bold = false;
        if (paragraph.getRuns() != null) {
            for (XWPFRun run : paragraph.getRuns()) {
                if (run.isBold()) {
                    bold = true;
                    break;
                }
            }
        }
        return style(bold, alignment(paragraph.getAlignment()));
    }

    private static String alignment(ParagraphAlignment alignment) {
        if (alignment == ParagraphAlignment.CENTER) return "center";
        if (alignment == ParagraphAlignment.RIGHT || alignment == ParagraphAlignment.END) return "right";
        return null;
    }

    private static String style(boolean bold, String align) {
        if (bold && align != null) return "bold," + align;
        if (bold) return "bold";
        return align;
    }

    // ------------------------------------------------------------------ XLSX

    private DocTemplateSnapshot readXlsx(byte[] bytes) throws Exception {
        List<DocTemplateSnapshot.Sheet> sheets = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        DataFormatter formatter = new DataFormatter();
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            for (int s = 0; s < workbook.getNumberOfSheets(); s++) {
                Sheet sheet = workbook.getSheetAt(s);
                Map<Long, CellRangeAddress> merged = mergedIndex(sheet);
                List<DocTemplateSnapshot.SheetRow> rows = new ArrayList<>();
                for (int r = 0; r <= sheet.getLastRowNum(); r++) {
                    Row row = sheet.getRow(r);
                    if (row == null) continue;
                    List<DocTemplateSnapshot.SheetCell> cells = new ArrayList<>();
                    for (int c = 0; c < row.getLastCellNum(); c++) {
                        Cell cell = row.getCell(c);
                        if (cell == null) continue;
                        CellType type = cell.getCellType();
                        if (type == CellType.BLANK) continue;
                        String text = formatter.formatCellValue(cell);
                        if (text == null || text.isEmpty()) continue;
                        CellRangeAddress region = merged.get(key(r, c));
                        // 合并区只在左上角那一格上记一次，其余格子跳过，避免同一段文字出现多遍
                        if (region != null && (region.getFirstRow() != r || region.getFirstColumn() != c)) continue;
                        int span = region == null ? 1 : region.getLastColumn() - region.getFirstColumn() + 1;
                        cells.add(new DocTemplateSnapshot.SheetCell(c, text, typeOf(type), span));
                    }
                    if (!cells.isEmpty()) rows.add(new DocTemplateSnapshot.SheetRow(r, cells));
                }
                if (sheet.getNumMergedRegions() > 0) {
                    warnings.add("工作表「" + sheet.getSheetName() + "」有 " + sheet.getNumMergedRegions()
                            + " 处合并区，快照只在左上角记录内容");
                }
                sheets.add(new DocTemplateSnapshot.Sheet(s, sheet.getSheetName(), rows.size(), rows));
            }
        }
        return new DocTemplateSnapshot("XLSX", "0-based", null, null, sheets, warnings);
    }

    private static Map<Long, CellRangeAddress> mergedIndex(Sheet sheet) {
        Map<Long, CellRangeAddress> index = new HashMap<>();
        for (CellRangeAddress region : sheet.getMergedRegions()) {
            index.put(key(region.getFirstRow(), region.getFirstColumn()), region);
        }
        return index;
    }

    private static long key(int row, int col) {
        return ((long) row << 20) | (col & 0xFFFFF);
    }

    private static String typeOf(CellType type) {
        return switch (type) {
            case STRING -> "s";
            case NUMERIC -> "n";
            case BOOLEAN -> "b";
            case FORMULA -> "f";
            default -> "other";
        };
    }
}
