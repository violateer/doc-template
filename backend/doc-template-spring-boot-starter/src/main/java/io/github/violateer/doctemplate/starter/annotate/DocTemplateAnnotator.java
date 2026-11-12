package io.github.violateer.doctemplate.starter.annotate;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTDecimalNumber;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTcPr;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 按 {@link DocTemplateMarkPlan} 把标记写进 docx / xlsx。
 *
 * <p><b>这是整个功能唯一的写入口</b>：模型只给坐标，二进制由这里改。所以这里对越界一律报错，
 * 不猜、不兜底 —— 写错一格比不写更糟，用户会拿到一份看起来正常、实际取不到数的模板。</p>
 *
 * <p>写入内容均为现有渲染器已支持的语法，不引入新方言：</p>
 *
 * <ul>
 *   <li>值：{@code ${块.字段}}；落在循环行/区域里时写短名 {@code ${字段}}（取当前行）；</li>
 *   <li>行内循环：行首格 {@code {{#each 块}}}、行尾格 {@code {{/each}}}，两个标记同一行；</li>
 *   <li>区域循环：区域前、区域后各插一行，分别放 {@code {{#each 块}}} 与 {@code {{/each}}}；</li>
 *   <li>竖向合并：{@code merge=1,2}、{@code by=字段}，原样透传。</li>
 * </ul>
 *
 * <p>只写单层循环：动态列和嵌套循环（循环套循环、{@code where} 关联过滤）不在标注范围内，
 * 由计划校验直接拒绝，不在这里半支持。</p>
 *
 * <p><b>行级改动全部放在最后</b>：插行和删行会改行号，所以先写文字、最后统一按位置从大到小插/删。</p>
 */
public class DocTemplateAnnotator {

    /** 落笔结果：改好的文件 + 需人工确认的告警（不阻断）。 */
    public record Result(byte[] bytes, List<String> warnings) {
    }

    /**
     * 一段样例数据行（行内循环）或一整块重复区域（区域循环）。
     * {@code sheet} 和 {@code table} 只有一个有值，按文件格式区分。
     */
    private record LoopRange(Integer sheet, Integer table, int from, int to, String block, boolean region) {
        boolean sameArea(LoopRange other) {
            return Objects.equals(sheet, other.sheet) && Objects.equals(table, other.table);
        }

        boolean contains(int row) {
            return row >= from && row <= to;
        }
    }

    /** 一次行级改动：{@code insert} 为真时在 {@code row} 处插一行并写入 {@code text}，否则删掉这一行。 */
    private record RowOp(Integer sheet, Integer table, int row, boolean insert, String text) {
    }

    public Result apply(byte[] source, String fileType, DocTemplateMarkPlan plan) throws Exception {
        if (source == null || source.length == 0) throw new IllegalArgumentException("模板文件内容为空");
        plan.validate();
        List<String> warnings = new ArrayList<>();
        byte[] output = "XLSX".equalsIgnoreCase(fileType)
                ? applyXlsx(source, plan, warnings)
                : applyDocx(source, plan, warnings);
        return new Result(output, warnings);
    }

    // ------------------------------------------------------------------ DOCX

    private byte[] applyDocx(byte[] source, DocTemplateMarkPlan plan, List<String> warnings) throws Exception {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(source))) {
            List<LoopRange> loops = loopRanges(plan, true);
            for (LoopRange range : loops) {
                if (gridCells(row(document, range.table(), range.from())).isEmpty())
                    throw new IllegalArgumentException(where(range.table(), range.from(), null)
                            + " 不含任何单元格，无法作为明细行循环");
                // rowTo 也要真的存在：行内循环写大了会一路删到表尾，把合计行、签字行一起删掉
                row(document, range.table(), range.to());
            }

            writeValuesDocx(document, plan, loops, warnings);
            writeInlineLoopsDocx(document, plan);
            applyRowOpsDocx(document, rowOps(loops));

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.write(out);
            return out.toByteArray();
        }
    }

    private void writeValuesDocx(XWPFDocument document, DocTemplateMarkPlan plan, List<LoopRange> loops,
                                 List<String> warnings) {
        for (DocTemplateMarkPlan.Mark mark : plan.marks()) {
            if (!"value".equals(mark.kind())) continue;
            DocTemplateMarkPlan.At at = mark.at();
            String token = "${" + mark.block() + "." + mark.field() + "}";
            if (at.table() != null) {
                LoopRange range = find(loops, null, at.table(), at.row());
                if (range != null) {
                    if (!range.block().equals(mark.block()))
                        throw new IllegalArgumentException(where(at.table(), at.row(), at.cell()) + " 是数据块 "
                                + range.block() + " 的循环行，值标记不能再指向块 " + mark.block()
                                + "；循环行里的字段只能取当前行的（写短名 ${字段}）");
                    if (!range.region() && at.row() != range.from())
                        throw new IllegalArgumentException(where(at.table(), at.row(), at.cell())
                                + " 是样例数据行（第 " + range.from() + " 到 " + range.to()
                                + " 行），落笔后会整行删掉，只留第 " + range.from() + " 行当模板行；"
                                + "请把这一格的标记改到第 " + range.from() + " 行");
                    token = "${" + mark.field() + "}";
                }
            }
            if (at.paragraph() != null) {
                List<XWPFParagraph> paragraphs = document.getParagraphs();
                int index = at.paragraph();
                if (index < 0 || index >= paragraphs.size())
                    throw new IllegalArgumentException("正文第 " + index + " 段不存在：当前文档共 "
                            + paragraphs.size() + " 段（0 起始）");
                replace(paragraphs.get(index), mark, token, warnings, "正文第 " + index + " 段");
            } else {
                XWPFTableCell target = cell(row(document, at.table(), at.row()), at.cell(), at.table(), at.row());
                // 续格在 Word 里没有自己的文字，写进去等于写到一个空段落上，产出会少一块内容
                if (DocTemplateSnapshotReader.isVMergeContinue(target))
                    throw new IllegalArgumentException(where(at.table(), at.row(), at.cell())
                            + " 是纵向合并的续格（文字显示在合并区的第一格），标注会写进空段落；"
                            + "请把这一格标到合并区的第一行，或先在 Word 里拆开合并");
                replace(paragraph(target, at.p(), at), mark, token, warnings, where(at.table(), at.row(), at.cell()));
            }
        }
    }

    private void writeInlineLoopsDocx(XWPFDocument document, DocTemplateMarkPlan plan) {
        for (DocTemplateMarkPlan.Mark mark : plan.marks()) {
            if (!"loop".equals(mark.kind())) continue;
            List<GridCell> cells = gridCells(row(document, mark.at().table(), mark.at().row()));
            DocxRuns.prepend(firstParagraph(cells.get(0).cell()), start(mark));
            DocxRuns.append(lastParagraph(cells.get(cells.size() - 1).cell()), "{{/each}}");
        }
    }

    /** 把行级改动按位置从大到小执行：先动下面的行，上面的坐标就还都是原来的。 */
    private void applyRowOpsDocx(XWPFDocument document, List<RowOp> ops) {
        for (RowOp op : ops) {
            // ops 里已经按 (table, row) 排好，这里只需要按表格分组取表
            XWPFTable table = document.getTables().get(op.table());
            if (!op.insert()) {
                table.removeRow(op.row());
                continue;
            }
            XWPFTableRow created = table.insertNewTableRow(op.row());
            if (created == null) continue;
            created.createCell();
            setCellText(created.getCell(0), op.text());
        }
    }

    private static XWPFTable table(XWPFDocument document, int tableIndex) {
        List<XWPFTable> tables = document.getTables();
        if (tableIndex < 0 || tableIndex >= tables.size())
            throw new IllegalArgumentException("表格 " + tableIndex + " 不存在：当前文档共 " + tables.size()
                    + " 张表（0 起始，只算正文顶层表格）");
        return tables.get(tableIndex);
    }

    private static XWPFTableRow row(XWPFDocument document, int tableIndex, int rowIndex) {
        List<XWPFTableRow> rows = table(document, tableIndex).getRows();
        if (rowIndex < 0 || rowIndex >= rows.size())
            throw new IllegalArgumentException("表格 " + tableIndex + " 第 " + rowIndex + " 行不存在：这张表共 "
                    + rows.size() + " 行（0 起始）");
        return rows.get(rowIndex);
    }

    private static void requireRow(XWPFTable table, int tableIndex, int rowIndex, String role) {
        if (rowIndex < 0 || rowIndex >= table.getRows().size())
            throw new IllegalArgumentException("表格 " + tableIndex + " 第 " + rowIndex + " 行（列区的" + role
                    + "）不存在：这张表共 " + table.getRows().size() + " 行（0 起始）");
    }

    /** 单元格的网格位置：{@code col} 是起始列，{@code span} 是横向合并占的列数。 */
    private record GridCell(int col, int span, XWPFTableCell cell) {
        boolean covers(int target) {
            return target >= col && target < col + span;
        }
    }

    private static List<GridCell> gridCells(XWPFTableRow row) {
        List<GridCell> cells = new ArrayList<>();
        int col = 0;
        for (XWPFTableCell cell : row.getTableCells()) {
            int span = DocTemplateSnapshotReader.spanOf(cell);
            cells.add(new GridCell(col, span, cell));
            col += span;
        }
        return cells;
    }

    private static XWPFTableCell cell(XWPFTableRow row, int col, int tableIndex, int rowIndex) {
        List<GridCell> cells = gridCells(row);
        for (GridCell cell : cells) {
            if (cell.covers(col)) return cell.cell();
        }
        throw new IllegalArgumentException(where(tableIndex, rowIndex, col) + " 不存在：这一行共 "
                + cells.size() + " 个单元格、合计 " + cells.stream().mapToInt(GridCell::span).sum()
                + " 个网格列（0 起始；横向合并的格子按起始列算）");
    }

    private static XWPFParagraph paragraph(XWPFTableCell cell, Integer index, DocTemplateMarkPlan.At at) {
        List<XWPFParagraph> paragraphs = cell.getParagraphs();
        int position = index == null ? 0 : index;
        if (position < 0 || position >= paragraphs.size())
            throw new IllegalArgumentException(where(at.table(), at.row(), at.cell()) + " 里只有 "
                    + paragraphs.size() + " 个段落，取不到第 " + position + " 段（0 起始）");
        return paragraphs.get(position);
    }

    private static XWPFParagraph firstParagraph(XWPFTableCell cell) {
        List<XWPFParagraph> paragraphs = cell.getParagraphs();
        return paragraphs.isEmpty() ? cell.addParagraph() : paragraphs.get(0);
    }

    private static XWPFParagraph lastParagraph(XWPFTableCell cell) {
        List<XWPFParagraph> paragraphs = cell.getParagraphs();
        return paragraphs.isEmpty() ? cell.addParagraph() : paragraphs.get(paragraphs.size() - 1);
    }

    /** 整格替换成一段文字，保留第一段的字符格式；多出来的段落删掉（标记必须独占一格）。 */
    private static void setCellText(XWPFTableCell cell, String text) {
        while (cell.getParagraphs().size() > 1) cell.removeParagraph(cell.getParagraphs().size() - 1);
        XWPFParagraph paragraph = firstParagraph(cell);
        DocxRuns.replaceRange(paragraph, 0, DocxRuns.text(paragraph).length(), text);
    }

    private static void setGridSpan(XWPFTableCell cell, int span) {
        CTTcPr properties = cell.getCTTc().isSetTcPr() ? cell.getCTTc().getTcPr() : cell.getCTTc().addNewTcPr();
        CTDecimalNumber value = properties.isSetGridSpan() ? properties.getGridSpan() : properties.addNewGridSpan();
        value.setVal(BigInteger.valueOf(span));
    }

    // ------------------------------------------------------------------ XLSX

    private byte[] applyXlsx(byte[] source, DocTemplateMarkPlan plan, List<String> warnings) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(source))) {
            List<LoopRange> loops = loopRanges(plan, false);
            for (LoopRange range : loops) {
                Sheet sheet = sheet(workbook, range.sheet());
                if (sheet.getRow(range.to()) == null)
                    throw new IllegalArgumentException(where(sheet.getSheetName(), range.to(), null)
                            + " 不存在：这一行是空的（at.rowTo 是样例数据或区域的最后一行）");
            }

            // 落在样例数据行上的值标记先归到模板行，再做后面的写入
            plan = normalizeSampleRows(workbook, plan, loops, warnings);
            writeInlineLoopsXlsx(workbook, plan);
            writeValuesXlsx(workbook, plan, loops, warnings);
            applyRowOpsXlsx(workbook, rowOps(loops));

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return out.toByteArray();
        }
    }

    /**
     * 把落在样例数据行上的值标记归到模板行。
     *
     * <p>行内循环落笔后只保留模板行，样例行整行删掉，标记留在样例行等于白写。同一批数据的每一行
     * 结构相同，所以直接挪到模板行同列即可。{@code replace} 要按它在原行里的位置重新取模板行的文字：
     * 样例行的值是 {@code 2#}、模板行是 {@code 1#}，照搬文字会匹配不上。</p>
     *
     * <p>必须在写循环标记之前做：模板行首格会被加上 {@code {{#each 块}}} 前缀，之后再按位置切分
     * 就会把前缀算进去。</p>
     */
    private static DocTemplateMarkPlan normalizeSampleRows(XSSFWorkbook workbook, DocTemplateMarkPlan plan,
                                                           List<LoopRange> loops, List<String> warnings) {
        DataFormatter formatter = new DataFormatter();
        Set<String> notified = new HashSet<>();
        List<DocTemplateMarkPlan.Mark> marks = new ArrayList<>();
        for (DocTemplateMarkPlan.Mark mark : plan.marks()) {
            DocTemplateMarkPlan.At at = mark.at();
            if (!"value".equals(mark.kind()) || at == null || at.row() == null || at.cell() == null) {
                marks.add(mark);
                continue;
            }
            LoopRange range = find(loops, at.sheet(), null, at.row());
            if (range == null || range.region() || at.row() == range.from()) {
                marks.add(mark);
                continue;
            }
            Sheet sheet = sheet(workbook, at.sheet());
            DocTemplateMarkPlan.At target = new DocTemplateMarkPlan.At(at.paragraph(), at.sheet(), at.table(),
                    range.from(), at.rowTo(), at.cell(), at.p());
            String replace = mappedReplace(sheet, at, target, mark.replace(), formatter);
            // 一段循环只提示一次：模型常把样例行的每一行每一列都标一遍，逐格提示会刷出上百条
            String notice = "工作表「" + sheet.getSheetName() + "」第 " + range.from() + " 到 " + range.to()
                    + " 行是样例数据行，标记已归到模板行第 " + range.from() + " 行";
            if (notified.add(notice)) warnings.add(notice);
            marks.add(new DocTemplateMarkPlan.Mark(mark.kind(), target, mark.block(), mark.field(), replace,
                    mark.merge(), mark.by(), mark.where()));
        }
        return new DocTemplateMarkPlan(plan.blocks(), marks, plan.ignore());
    }

    /** 按 {@code replace} 在原行里的位置，取模板行同一位置的文字；定位不到就沿用原值。 */
    private static String mappedReplace(Sheet sheet, DocTemplateMarkPlan.At origin, DocTemplateMarkPlan.At target,
                                        String replace, DataFormatter formatter) {
        if (replace == null || replace.isEmpty()) return replace;
        int offset = textAt(sheet, origin, formatter).indexOf(replace);
        if (offset < 0) return replace;
        String text = textAt(sheet, target, formatter);
        if (offset >= text.length()) return "";
        return text.substring(offset, Math.min(offset + replace.length(), text.length()));
    }

    private static String textAt(Sheet sheet, DocTemplateMarkPlan.At at, DataFormatter formatter) {
        Row row = sheet.getRow(at.row());
        if (row == null) return "";
        Cell cell = row.getCell(at.cell());
        return cell == null ? "" : cellText(cell, formatter);
    }

    private void writeInlineLoopsXlsx(XSSFWorkbook workbook, DocTemplateMarkPlan plan) {
        DataFormatter formatter = new DataFormatter();
        for (DocTemplateMarkPlan.Mark mark : plan.marks()) {
            if (!"loop".equals(mark.kind())) continue;
            DocTemplateMarkPlan.At at = mark.at();
            Sheet sheet = sheet(workbook, at.sheet());
            Row row = row(sheet, at);
            int[] range = contentRange(row, sheet.getSheetName(), at);
            // 先把两格的原文取出来再写：首尾是同一格时不能边写边读
            Cell first = cell(row, range[0]);
            Cell last = range[1] == range[0] ? first : cell(row, range[1]);
            String firstText = stringOf(first, formatter);
            String lastText = range[1] == range[0] ? firstText : stringOf(last, formatter);
            first.setCellValue(start(mark) + firstText);
            last.setCellValue(lastText + "{{/each}}");
        }
    }

    private void writeValuesXlsx(XSSFWorkbook workbook, DocTemplateMarkPlan plan, List<LoopRange> loops,
                                 List<String> warnings) {
        // 每份文档一个：DataFormatter 内部有格式缓存，不能做成共享实例
        DataFormatter formatter = new DataFormatter();
        Map<String, String> markedCells = new HashMap<>();
        for (DocTemplateMarkPlan.Mark mark : plan.marks()) {
            if (!"value".equals(mark.kind())) continue;
            DocTemplateMarkPlan.At at = mark.at();
            Sheet sheet = sheet(workbook, at.sheet());
            String token = "${" + mark.block() + "." + mark.field() + "}";
            LoopRange range = find(loops, at.sheet(), null, at.row());
            if (range != null) {
                if (!range.block().equals(mark.block()))
                    throw new IllegalArgumentException(where(sheet.getSheetName(), at.row(), at.cell())
                            + " 是数据块 " + range.block() + " 的循环行，值标记不能再指向块 " + mark.block());
                if (!range.region() && at.row() != range.from())
                    throw new IllegalArgumentException(where(sheet.getSheetName(), at.row(), at.cell())
                            + " 是样例数据行，标记应当归到模板行第 " + range.from() + " 行");
                token = "${" + mark.field() + "}";
            }
            Row row = row(sheet, at);
            Cell cell = row.getCell(at.cell());
            if (cell == null) cell = row.createCell(at.cell());
            // 同一格只能属于一个字段。模型重复输出同一条标记很常见（直接跳过），
            // 但两条不同的标记就是自相矛盾，必须报出来 —— 否则会静默丢掉一个字段
            String cellKey = at.sheet() + ":" + at.row() + ":" + at.cell();
            String previous = markedCells.putIfAbsent(cellKey, mark.field());
            if (previous != null) {
                if (!previous.equals(mark.field()))
                    throw new IllegalArgumentException(where(sheet.getSheetName(), at.row(), at.cell())
                            + " 同时标了字段 " + previous + " 和 " + mark.field() + "，一个格子只能属于一个字段");
                continue;
            }
            // 公式格不能标：写标记会把公式本身覆盖掉，原本算的是什么也就看不出来了
            if (cell.getCellType() == CellType.FORMULA)
                throw new IllegalArgumentException(where(sheet.getSheetName(), at.row(), at.cell())
                        + " 是公式单元格；写标记会覆盖公式，请先在 Excel 里改成文本");
            // 数字和布尔格照常标：按显示文本读出来再写回去，单元格会自动变成文本
            String text = cellText(cell, formatter);
            String target = mark.replace();
            if (target == null || target.isEmpty()) {
                cell.setCellValue(token);
                continue;
            }
            int at0 = text.indexOf(target);
            if (at0 < 0)
                throw new IllegalArgumentException(where(sheet.getSheetName(), at.row(), at.cell())
                        + " 里找不到要替换的文字「" + target + "」，实际内容是「" + text + "」");
            if (text.indexOf(target, at0 + target.length()) >= 0)
                warnings.add(where(sheet.getSheetName(), at.row(), at.cell()) + " 里「" + target
                        + "」出现了多次，只替换了第一处");
            cell.setCellValue(text.substring(0, at0) + token + text.substring(at0 + target.length()));
        }
    }

    /**
     * 单元格的显示文本，和快照里给出的文本一致。
     *
     * <p>数字、日期都按单元格自己的格式渲染（{@code 1} 就是 {@code "1"}，日期就是 {@code 2026-11-01}），
     * 这样模型从快照里抄下来的 {@code replace} 能原样匹配到，落笔时再写回文本即可。</p>
     */
    private static String cellText(Cell cell, DataFormatter formatter) {
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue();
            case BLANK -> "";
            default -> formatter.formatCellValue(cell);
        };
    }

    /**
     * 工作表行级改动：删行、插行都从下往上做，每次只挪动下面那一段。
     *
     * <p>合并区要自己搬：{@code shiftRows} 不管合并区，删完行之后合并区还停在原坐标上，
     * 轻则错位、重则和别的区重叠让文件打不开。</p>
     */
    private void applyRowOpsXlsx(XSSFWorkbook workbook, List<RowOp> ops) {
        for (RowOp op : ops) {
            Sheet sheet = sheet(workbook, op.sheet());
            if (!op.insert()) {
                Row row = sheet.getRow(op.row());
                if (row == null) continue;
                sheet.removeRow(row);
                int last = sheet.getLastRowNum();
                if (op.row() < last) {
                    shiftMerges(sheet, op.row(), -1);
                    sheet.shiftRows(op.row() + 1, last, -1, true, false);
                }
                continue;
            }
            int last = sheet.getLastRowNum();
            if (op.row() <= last) {
                shiftMerges(sheet, op.row(), 1);
                sheet.shiftRows(op.row(), last, 1, true, false);
            }
            Row row = sheet.createRow(op.row());
            row.createCell(0).setCellValue(op.text());
        }
    }

    /** 在第 {@code at} 行插入（{@code delta=1}）或删掉（{@code delta=-1}）一行后，把合并区搬到位。 */
    static void shiftMerges(Sheet sheet, int at, int delta) {
        List<CellRangeAddress> regions = new ArrayList<>();
        for (int i = 0; i < sheet.getNumMergedRegions(); i++) regions.add(sheet.getMergedRegion(i));
        for (int i = sheet.getNumMergedRegions() - 1; i >= 0; i--) sheet.removeMergedRegion(i);
        for (CellRangeAddress region : regions) {
            CellRangeAddress moved = shiftRegion(region, at, delta);
            if (moved != null) sheet.addMergedRegionUnsafe(moved);
        }
    }

    private static CellRangeAddress shiftRegion(CellRangeAddress region, int at, int delta) {
        int first = region.getFirstRow();
        int last = region.getLastRow();
        if (delta > 0) {
            if (first >= at) return copy(region, first + 1, last + 1);
            if (last >= at) return copy(region, first, last + 1);
            return copy(region, first, last);
        }
        if (first > at) return copy(region, first - 1, last - 1);
        if (last < at) return copy(region, first, last);
        if (first == last) return null;
        return copy(region, first, last - 1);
    }

    /**
     * 搬移后的合并区。
     *
     * <p>删掉合并区里的行会让它退化成单个格子（{@code A8:A9} 删掉第 9 行只剩 {@code A8}），
     * 而单格合并区在 POI 里是非法的，{@code addMergedRegionUnsafe} 会抛
     * 「Merged region A8 must contain 2 or more cells」。这种情况返回 {@code null}，
     * 由调用方跳过 —— 合并区本来就该消失。</p>
     */
    private static CellRangeAddress copy(CellRangeAddress region, int firstRow, int lastRow) {
        if (firstRow == lastRow && region.getFirstColumn() == region.getLastColumn()) return null;
        return new CellRangeAddress(firstRow, lastRow, region.getFirstColumn(), region.getLastColumn());
    }

    private static Sheet sheet(XSSFWorkbook workbook, Integer index) {
        if (index == null)
            throw new IllegalArgumentException("XLSX 的标记必须给 at.sheet（第几个工作表，0 起始）");
        if (index < 0 || index >= workbook.getNumberOfSheets())
            throw new IllegalArgumentException("工作表 " + index + " 不存在：当前工作簿共 "
                    + workbook.getNumberOfSheets() + " 个工作表（0 起始）");
        return workbook.getSheetAt(index);
    }

    private static Sheet sheet(XSSFWorkbook workbook, DocTemplateMarkPlan.At at) {
        return sheet(workbook, at.sheet());
    }

    private static Row row(Sheet sheet, DocTemplateMarkPlan.At at) {
        if (at.row() == null) throw new IllegalArgumentException("XLSX 的标记必须给 at.row（行号，0 起始）");
        Row row = sheet.getRow(at.row());
        if (row == null)
            throw new IllegalArgumentException(where(sheet.getSheetName(), at.row(), at.cell()) + " 不存在：这一行是空的");
        return row;
    }

    /** 一行里第一个和最后一个有内容的列；标记写在这两格上，不会把表的范围撑大。 */
    private static int[] contentRange(Row row, String sheetName, DocTemplateMarkPlan.At at) {
        int first = -1;
        int last = -1;
        for (int c = row.getFirstCellNum(); c < row.getLastCellNum(); c++) {
            if (row.getCell(c) == null) continue;
            if (first < 0) first = c;
            last = c;
        }
        if (first < 0)
            throw new IllegalArgumentException(where(sheetName, at.row(), null) + " 不含任何单元格，无法作为明细行循环");
        return new int[]{first, last};
    }

    private static Cell cell(Row row, int col) {
        Cell cell = row.getCell(col);
        return cell == null ? row.createCell(col) : cell;
    }

    /**
     * 单元格里的文字，按显示文本取。
     *
     * <p>数字格也要读出原文：行内循环的尾格如果是数字，读成空串会把原值覆盖掉，
     * 后面按 {@code replace} 找文字时就再也找不到了。</p>
     */
    private static String stringOf(Cell cell, DataFormatter formatter) {
        return cell == null ? "" : cellText(cell, formatter);
    }

    // ------------------------------------------------------------------ 公共

    /**
     * 收集循环范围并做重叠检查。
     *
     * @param docx {@code true} 按 {@code at.table} 分组，{@code false} 按 {@code at.sheet}
     */
    private static List<LoopRange> loopRanges(DocTemplateMarkPlan plan, boolean docx) {
        List<LoopRange> ranges = new ArrayList<>();
        for (DocTemplateMarkPlan.Mark mark : plan.marks()) {
            boolean region = "region".equals(mark.kind());
            if (!region && !"loop".equals(mark.kind())) continue;
            DocTemplateMarkPlan.At at = mark.at();
            if (docx ? at.table() == null : at.sheet() == null) continue;
            ranges.add(new LoopRange(docx ? null : at.sheet(), docx ? at.table() : null,
                    at.row(), at.lastRow(), mark.block(), region));
        }
        for (int i = 0; i < ranges.size(); i++) {
            for (int j = i + 1; j < ranges.size(); j++) {
                LoopRange a = ranges.get(i);
                LoopRange b = ranges.get(j);
                if (a.sameArea(b) && a.from() <= b.to() && b.from() <= a.to())
                    throw new IllegalArgumentException(overlapMessage(a, b));
            }
        }
        return ranges;
    }

    /**
     * 两个循环范围相交时的说明。
     *
     * <p>「一个范围整个落在另一个里」（块 A 第 4~12 行、块 B 第 5~7 行）和「两段各占几行但有一行重叠」
     * 是两种错：前者说明把嵌套明细拆成了两个循环，落笔后外层会把内层整段当样例行删掉；后者才是范围写多了。
     * 改法完全不同，所以文案分开写 —— 这段文字会原样回灌给模型重试。</p>
     */
    private static String overlapMessage(LoopRange a, LoopRange b) {
        boolean aWrapsB = a.from() <= b.from() && b.to() <= a.to();
        boolean bWrapsA = b.from() <= a.from() && a.to() <= b.to();
        // 同一个块写了两个范围相同的循环不算嵌套，按普通重叠说明，免得提示词里出现「A 落在 A 里」
        if (!a.block().equals(b.block()) && (aWrapsB || bWrapsA)) {
            LoopRange outer = aWrapsB ? a : b;
            LoopRange inner = aWrapsB ? b : a;
            return "两个循环标记是嵌套关系（块 " + inner.block() + " 第 " + inner.from() + "~" + inner.to()
                    + " 行整个落在块 " + outer.block() + " 第 " + outer.from() + "~" + outer.to() + " 行里）；"
                    + "嵌套循环不支持，同一段明细只能留一个循环：只保留外层或只保留内层，另一层写进 ignore "
                    + "并说明原因，不要用两个循环覆盖同一批行";
        }
        return "两个循环标记的范围重叠（块 " + a.block() + " 第 " + a.from() + "~" + a.to() + " 行，块 "
                + b.block() + " 第 " + b.from() + "~" + b.to() + " 行）；同一张表里两个循环不能覆盖同一行。"
                + "一段明细的循环只写到自己那段数据的最后一行，不要跨过中间的标题行、空行、合计行；"
                + "这张表里有多段明细就每段各写一个循环，范围不能重叠";
    }

    private static LoopRange find(List<LoopRange> ranges, Integer sheet, Integer table, int row) {
        for (LoopRange range : ranges) {
            if (!Objects.equals(range.sheet(), sheet) || !Objects.equals(range.table(), table)) continue;
            if (range.contains(row)) return range;
        }
        return null;
    }

    /**
     * 把循环范围翻译成行级改动，并按「表 → 位置从大到小」排好。     *
     * <p>行内循环删掉样例行（只留第一行当模板行）；区域循环在区域前后各插一行放标记。
     * 两类操作混在同一张表里时，从下往上做才不会互相错位。</p>
     */
    private static List<RowOp> rowOps(List<LoopRange> ranges) {
        List<RowOp> ops = new ArrayList<>();
        for (LoopRange range : ranges) {
            if (range.region()) {
                ops.add(new RowOp(range.sheet(), range.table(), range.from(), true,
                        "{{#each " + range.block() + "}}"));
                ops.add(new RowOp(range.sheet(), range.table(), range.to() + 1, true, "{{/each}}"));
            } else {
                for (int r = range.to(); r > range.from(); r--)
                    ops.add(new RowOp(range.sheet(), range.table(), r, false, null));
            }
        }
        // 先按表分组，再在表内从下往上：下面的行动完，上面的坐标还是原来的
        ops.sort(Comparator.comparing(RowOp::table, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(RowOp::sheet, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(RowOp::row, Comparator.reverseOrder()));
        return ops;
    }

    private static void replace(XWPFParagraph paragraph, DocTemplateMarkPlan.Mark mark, String token,
                                List<String> warnings, String where) {
        String text = DocxRuns.text(paragraph);
        String target = mark.replace();
        if (target == null || target.isEmpty()) {
            DocxRuns.replaceRange(paragraph, 0, text.length(), token);
            return;
        }
        int at = text.indexOf(target);
        if (at < 0)
            throw new IllegalArgumentException(where + " 里找不到要替换的文字「" + target
                    + "」，实际内容是「" + text + "」。检查 plan 里的 replace 是否和原文完全一致（含空格）");
        if (text.indexOf(target, at + target.length()) >= 0)
            warnings.add(where + " 里「" + target + "」出现了多次，只替换了第一处");
        DocxRuns.replaceRange(paragraph, at, at + target.length(), token);
    }

    /** {@code {{#each 块 merge=… by=…}}}：参数原样透传，merge 从 1 起始。 */
    private static String start(DocTemplateMarkPlan.Mark mark) {
        StringBuilder sb = new StringBuilder("{{#each ").append(mark.block());
        if (!mark.merge().isEmpty()) {
            sb.append(" merge=");
            for (int i = 0; i < mark.merge().size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(mark.merge().get(i));
            }
        }
        if (mark.by() != null && !mark.by().isBlank()) sb.append(" by=").append(mark.by());
        return sb.append("}}").toString();
    }

    private static String where(int table, int row, Integer col) {
        return "表格 " + table + " 第 " + row + " 行" + (col == null ? "" : "第 " + col + " 列");
    }

    private static String where(String sheet, int row, Integer col) {
        return "工作表「" + sheet + "」第 " + row + " 行" + (col == null ? "" : "第 " + col + " 列")
                + "（0 起始，Excel 里要加 1）";
    }
}
