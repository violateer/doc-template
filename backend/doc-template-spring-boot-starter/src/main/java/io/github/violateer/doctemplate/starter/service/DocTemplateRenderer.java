package io.github.violateer.doctemplate.starter.service;

import cn.hutool.core.util.StrUtil;
import io.github.violateer.doctemplate.starter.convert.DocTemplateConverterRegistry;
import io.github.violateer.doctemplate.starter.convert.DocTemplateConverterRegistry.ConverterCall;
import io.github.violateer.doctemplate.starter.support.DocTemplateColumnSpec;
import io.github.violateer.doctemplate.starter.support.DocTemplateImageSpec;
import io.github.violateer.doctemplate.starter.support.DocTemplateJson;
import io.github.violateer.doctemplate.starter.support.DocTemplateMergeAlign;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFClientAnchor;
import org.apache.poi.xssf.usermodel.XSSFDrawing;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.*;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTDecimalNumber;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTP;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTR;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTRPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTRow;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTbl;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTblGrid;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTblPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTblWidth;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTc;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTcPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTVMerge;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTVerticalJc;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STMerge;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STTblWidth;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STVerticalJc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders DOCX/XLSX templates by replacing placeholders with supplied parameters.
 *
 * <p>Supported placeholder syntax: {@code ${key}} and {@code {{key}}}. A default value can be
 * provided with {@code ${key:default}}. Nested values are addressable with dots,
 * for example {@code ${customer.name}}.</p>
 *
 * <p>字段值转换：{@code ${key|type:arg}} 或 {@code ${key|type(k=v)}}，多个转换器用 {@code |} 串联，
 * 例如 {@code ${amount|number:2}}、{@code ${status|dict(order_status)}}。转换链写在默认值之后
 * （{@code ${key:默认值|number:2}}），与原有的 {@code 键:默认值} 语法不冲突。</p>
 *
 * <p>明细行循环：{@code {{#each items}}} / {@code ${#each items}}} 标记所在的行（DOCX 表格行、
 * XLSX 行）或段落块会按数组重复，循环体内用 {@code ${字段名}} 或 {@code ${item.字段名}} 取当前项。
 * 行内标记是「单标记隐式闭合」；{@code {{#each}}} 与 {@code {{/each}}} 各独占一行时是「区域循环」，
 * 两者之间的所有行整体重复。</p>
 *
 * <p>递归渲染：区域循环可以嵌套，内层数组既可以是外层项自带的字段（{@code {{#each items}}}，
 * 由 Provider 返回嵌套结构），也可以用关联条件从扁平数组里过滤（{@code {{#each items where
 * order_no = order_no}}}，SQL 返回扁平结果集时用）。层级不限。</p>
 *
 * <p>竖向合并单元格：循环标记后面追加 {@code merge=列} 即可把「相邻且分组键相同」的行合并成一格，
 * 例如 {@code {{#each items merge=1,2 by=material_id}}}。写 {@code by=} 时按该字段的值比较，
 * 不写时按渲染后的文本比较；空值永不合并。详见 {@link #mergeSpans}。</p>
 *
 * <p>表格动态列：表头行写 {@code {{#columns 块 header}}}、数据行写 {@code {{#columns 块 data}}}，
 * 展开成 N 个真实单元格，列数由数据块配置里的列源决定；{@code {{#columns 块 span}}} 不拆格、
 * 整格横向盖住整个列区。角色必须写明，表头行和数据行的标记文字才不会混在一起。</p>
 *
 * <p>图片字段：数据块配置里用 {@code imageFields} 声明某个字段是图片后，该占位符会渲染成图片
 * （值可以是 http(s) 地址、data URI 或裸 Base64，也可以是 SQL 返回的 byte[]）。DOCX 走
 * {@code XWPFRun#addPicture}，XLSX 走 {@code XSSFDrawing} 锚定到单元格。</p>
 *
 * <p>This is deliberately a lightweight, dependency-free renderer: it does not run formulas,
 * and a loop only repeats table rows / paragraph blocks — no conditionals, no expression
 * evaluation beyond {@code 数组名 where 字段 = 取值}.</p>
 */
public class DocTemplateRenderer {

    private static final Logger log = LoggerFactory.getLogger(DocTemplateRenderer.class);

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{\\s*([^{}]+?)\\s*}|\\{\\{\\s*([^{}]+?)\\s*}}");
    private static final Pattern FULL_PLACEHOLDER = Pattern.compile("^\\s*(?:\\$\\{|\\{\\{)\\s*([^{}]+?)\\s*}}?\\s*$");
    /**
     * {@code {{#each items}}} 或 {@code ${#each items}}}；
     * 关联过滤写法 {@code {{#each items where order_no = order_no}}}。
     *
     * <p>等号右边可以是当前作用域里的键（{@code order_no} / {@code item.order_no}），
     * 也可以是带引号的字面量（{@code 'open'}）。不能写 {@code ${...}}：循环标记本身就在
     * 花括号里，再嵌一层会让占位符正则提前截断。</p>
     *
     * <p><b>关键字不区分大小写</b>：标记是写在 Word 里的文字，Word 的「句首字母大写」会把
     * {@code {{#each}} 改成 {@code {{#Each}}，写错了看不出来，所以这里一律宽容；
     * 但「只有标记、却没被识别」会报错（见 {@link #replaceText}），不会静默留在文档里。</p>
     */
    private static final Pattern LOOP_START = Pattern.compile(
            "(?:\\$\\{|\\{\\{)\\s*#each\\s+([A-Za-z0-9_.]+)([^{}]*)\\s*}?}?", Pattern.CASE_INSENSITIVE);
    /** {@code where 字段 = 取值}：取值可以是 {@code 'x'}、{@code "x"} 或单个词。 */
    private static final Pattern LOOP_WHERE = Pattern.compile(
            "\\bwhere\\s+([A-Za-z0-9_.]+)\\s*=\\s*('(?:[^']*)'|\"(?:[^\"]*)\"|[^\\s}]+)", Pattern.CASE_INSENSITIVE);
    /** {@code merge=1,2} / {@code merge=A,C}：要竖向合并的列，逗号分隔，不能带空格。 */
    private static final Pattern LOOP_MERGE = Pattern.compile("\\bmerge\\s*=\\s*([^\\s}]+)", Pattern.CASE_INSENSITIVE);
    /** {@code by=order_no}：合并分组键，不写就按渲染后的文本比较。 */
    private static final Pattern LOOP_BY = Pattern.compile("\\bby\\s*=\\s*([A-Za-z0-9_.]+)", Pattern.CASE_INSENSITIVE);
    /**
     * {@code {{/each}}} 或 {@code ${/each}}}
     */
    private static final Pattern LOOP_END = Pattern.compile("(?:\\$\\{|\\{\\{)\\s*/each\\s*}?}?", Pattern.CASE_INSENSITIVE);
    /**
     * 表格列区标记：{@code {{#columns 块编码 角色}}}，角色是 {@code header} / {@code data} / {@code span}。
     *
     * <p>角色必须写：表头行和数据行的标记文字长得一样，写明角色才不用靠行号去猜。
     * 标记必须独占一格：拆格形式后面不能再写别的文字，想加文字用 {@code span} 形式。</p>
     */
    private static final Pattern COLUMNS_START = Pattern.compile(
            "(?:\\$\\{|\\{\\{)\\s*#columns\\s+([A-Za-z0-9_.]+)([^{}]*)\\s*}?}?", Pattern.CASE_INSENSITIVE);
    /** 列区标记的角色关键字，写在块编码后面、{@code where} / {@code merge} / {@code by} 前面。 */
    private static final Pattern COLUMNS_ROLE = Pattern.compile("\\b(header|data|span)\\b", Pattern.CASE_INSENSITIVE);
    /**
     * 列区标记里的参数关键字：角色只认它前面那一段，否则 {@code where status = 'data'} 会被当成角色。
     */
    private static final Pattern COLUMNS_MODIFIER = Pattern.compile("\\b(where|merge|by)\\b", Pattern.CASE_INSENSITIVE);

    /** 没有 {@code tblGrid}、也读不到 {@code tblW} 时按这个总宽（twips）估算，约 A4 正文宽。 */
    private static final int DEFAULT_TABLE_WIDTH = 9000;
    /** 单列太窄时的告警阈值（twips），约 0.5cm。 */
    private static final int NARROW_COLUMN_TWIPS = 280;

    private final DocTemplateImageLoader imageLoader;
    private final DocTemplateConverterRegistry converters;

    public DocTemplateRenderer() {
        this(new DocTemplateImageLoader());
    }

    public DocTemplateRenderer(DocTemplateImageLoader imageLoader) {
        this(imageLoader, null);
    }

    public DocTemplateRenderer(DocTemplateImageLoader imageLoader, DocTemplateConverterRegistry converters) {
        this.imageLoader = imageLoader == null ? new DocTemplateImageLoader() : imageLoader;
        this.converters = converters == null ? new DocTemplateConverterRegistry() : converters;
    }

    private static String stripLoopMarkers(String text) {
        return LOOP_END.matcher(LOOP_START.matcher(text).replaceAll("")).replaceAll("");
    }

    /**
     * 解析出来的循环标记：数组名 + 可选关联条件 + 可选竖向合并声明。
     *
     * @param field        {@code where} 左边的字段名，没有就是 {@code null}
     * @param value        {@code where} 右边的取值（带引号的字面量保留引号，交给取数时再剥）
     * @param mergeColumns {@code merge=} 声明的列，原样保留：XLSX 认列字母或 1 起始列号，DOCX 只认 1 起始序号
     * @param mergeBy      {@code by=} 声明的分组键字段，没写就按渲染后的文本比较
     */
    public record Loop(String array, String field, String value,
                       List<String> mergeColumns, String mergeBy) {

        public Loop(String array, String field, String value) {
            this(array, field, value, List.of(), null);
        }

        public boolean filtered() {
            return field != null && !field.isBlank();
        }

        /** 是否声明了竖向合并。 */
        public boolean merging() {
            return mergeColumns != null && !mergeColumns.isEmpty();
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder(filtered() ? array + " where " + field + " = " + value : array);
            if (merging()) {
                sb.append(" merge=").append(String.join(",", mergeColumns));
                if (mergeBy != null && !mergeBy.isBlank()) sb.append(" by=").append(mergeBy);
            }
            return sb.toString();
        }
    }

    private static Loop parseLoop(String text) {
        // 快路径用 # 而不是 "each"：关键字大小写不敏感，拿小写字面量去 indexOf 会把 {{#EACH}} 漏掉
        if (text == null || text.indexOf('#') < 0) return null;
        Matcher matcher = LOOP_START.matcher(text);
        if (!matcher.find()) return null;
        String array = matcher.group(1);
        String tail = matcher.group(2) == null ? "" : matcher.group(2);
        String field = null;
        String value = null;
        Matcher where = LOOP_WHERE.matcher(tail);
        if (where.find()) {
            field = where.group(1);
            value = where.group(2).trim();
        }
        List<String> columns = List.of();
        Matcher merge = LOOP_MERGE.matcher(tail);
        if (merge.find()) columns = splitColumns(merge.group(1));
        String by = null;
        Matcher byMatcher = LOOP_BY.matcher(tail);
        if (byMatcher.find()) by = byMatcher.group(1).trim();
        return new Loop(array, field, value, columns, by);
    }

    /** {@code merge=1,2} / {@code merge=A，C}（顺带认全角逗号）。 */
    private static List<String> splitColumns(String raw) {
        List<String> columns = new ArrayList<>();
        for (String part : raw.split("[,，]")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) columns.add(trimmed);
        }
        return List.copyOf(columns);
    }

    /** 列区标记的角色：靠它区分表头行和数据行，不用再按行号猜。 */
    private enum ColumnsRole {
        /** 表头行：展开成 N 格，每格写列标题。 */
        HEADER("header"),
        /** 数据行：展开成 N 格，每格取一个字段，并按该数组循环。 */
        DATA("data"),
        /** 不拆格，整格横向盖住整个列区（合计行、说明行用）。 */
        SPAN("span");

        private final String keyword;

        ColumnsRole(String keyword) {
            this.keyword = keyword;
        }

        static ColumnsRole of(String word) {
            for (ColumnsRole role : values()) {
                if (role.keyword.equalsIgnoreCase(word)) return role;
            }
            return null;
        }
    }

    /**
     * 解析出来的列区标记。
     *
     * @param block     列定义所在的数据块编码
     * @param role      角色；没写时是 {@code null}，由调用方带上行号列号报错
     * @param ambiguous 同一个标记里写了两个不同的角色
     */
    private record ColumnsMarker(String block, ColumnsRole role, boolean ambiguous) {

        boolean span() {
            return role == ColumnsRole.SPAN;
        }
    }

    private static ColumnsMarker parseColumns(String text) {
        // 快路径用 # 而不是 #columns：关键字大小写不敏感，不能拿小写字面量去 indexOf
        if (text == null || text.indexOf('#') < 0) return null;
        Matcher matcher = COLUMNS_START.matcher(text);
        if (!matcher.find()) return null;
        String tail = matcher.group(2) == null ? "" : matcher.group(2);
        Matcher cut = COLUMNS_MODIFIER.matcher(tail);
        String head = cut.find() ? tail.substring(0, cut.start()) : tail;
        ColumnsRole role = null;
        boolean ambiguous = false;
        Matcher roles = COLUMNS_ROLE.matcher(head);
        while (roles.find()) {
            ColumnsRole found = ColumnsRole.of(roles.group(1));
            if (found == null) continue;
            if (role != null && role != found) ambiguous = true;
            if (role == null) role = found;
        }
        return new ColumnsMarker(matcher.group(1), role, ambiguous);
    }

    /** 角色必须写明：只有写清是表头行还是数据行，才不用靠行号去猜。 */
    private static void requireColumnsRole(ColumnsMarker marker, String where) {
        String hint = "表头行写 {{#columns " + marker.block() + " header}}，数据行写 {{#columns "
                + marker.block() + " data}}，整格横向盖住列区写 {{#columns " + marker.block() + " span}}";
        if (marker.ambiguous()) {
            throw new IllegalArgumentException(where + "的 {{#columns}} 里写了多个角色，只能写一个；" + hint);
        }
        if (marker.role() == null) {
            throw new IllegalArgumentException(where + "的 {{#columns " + marker.block()
                    + "}} 没写角色，分不清是表头行还是数据行；" + hint);
        }
    }

    /**
     * 数据行上允许再写一对 {@code {{#each 块}}…{{/each}}} 把块名标在行首行尾，格子里就不用逐个写块前缀。
     *
     * <p>它只用来校验块编码：真正驱动循环的是 {@code data} 角色，展开时这对标记会被清掉，
     * 免得同一行上出现两个循环。{@code where=} / {@code merge=} / {@code by=} 必须写在
     * {@code {{#columns}}} 标记里 —— 写在行上指的是展开前的位置，列展开后就错位了。</p>
     */
    private static void checkRowLoopAnnotation(String rowText, ColumnsMarker marker, String where) {
        Loop loop = parseLoop(rowText);
        if (loop == null) return;
        if (!loop.array().equalsIgnoreCase(marker.block())) {
            throw new IllegalArgumentException(where + " 的 {{#each " + loop.array() + "}} 与列区标记的数据块 "
                    + marker.block() + " 不一致；数据行里的循环标记要和 {{#columns}} 写同一个块");
        }
        if (loop.filtered() || loop.merging() || (loop.mergeBy() != null && !loop.mergeBy().isBlank())) {
            throw new IllegalArgumentException(where + " 的 {{#each " + loop.array()
                    + "}} 上不要写 where= / merge= / by=，这些参数写在 {{#columns "
                    + marker.block() + " data ...}} 里");
        }
    }

    /** 整格只有 {@code {{#columns ...}}} 才算标记格；带别的文字是配置错误，不是「顺便渲染一下」。 */
    private static boolean isColumnsOnly(String text) {
        if (text == null) return false;
        Matcher full = FULL_PLACEHOLDER.matcher(text);
        if (!full.matches() || full.group(1) == null) return false;
        return full.group(1).trim().toLowerCase(Locale.ROOT).startsWith("#columns");
    }

    /**
     * Convenience for building a parameter map from a dotted key path.
     */
    public static List<String> sorted(Set<String> keys) {
        return new ArrayList<>(new LinkedHashSet<>(keys));
    }

    public byte[] render(byte[] template, String fileType, Map<String, Object> params) throws Exception {
        return render(template, fileType, params, List.of(), DocTemplateMergeAlign.DEFAULT, Map.of());
    }

    /**
     * @param images 声明为图片的字段（来自数据块配置），为空时全部按文本渲染
     */
    public byte[] render(byte[] template, String fileType, Map<String, Object> params,
                         List<DocTemplateImageSpec> images) throws Exception {
        return render(template, fileType, params, images, DocTemplateMergeAlign.DEFAULT, Map.of());
    }

    /**
     * @param mergeAlign 竖向合并出来的格子内容怎么对齐（来自数据块配置的 {@code mergeCellAlign}）
     */
    public byte[] render(byte[] template, String fileType, Map<String, Object> params,
                         List<DocTemplateImageSpec> images, DocTemplateMergeAlign mergeAlign) throws Exception {
        return render(template, fileType, params, images, mergeAlign, Map.of());
    }

    /**
     * @param columns {@code 块编码 -> 列定义}（数据块配置里的 {@code columns}），
     *                模板里写 {@code {{#columns 块编码 header/data/span}}} 时用它展开表格列
     */
    public byte[] render(byte[] template, String fileType, Map<String, Object> params,
                         List<DocTemplateImageSpec> images, DocTemplateMergeAlign mergeAlign,
                         Map<String, List<DocTemplateColumnSpec>> columns) throws Exception {
        Map<String, Object> safe = params == null ? Map.of() : params;
        List<DocTemplateImageSpec> specs = images == null ? List.of() : images;
        DocTemplateMergeAlign align = mergeAlign == null ? DocTemplateMergeAlign.DEFAULT : mergeAlign;
        Map<String, List<DocTemplateColumnSpec>> columnIndex = columns == null ? Map.of() : columns;
        if ("XLSX".equals(fileTypeOf(fileType))) return renderXlsx(template, safe, specs, align, columnIndex);
        return renderDocx(template, safe, specs, align, columnIndex);
    }

    /**
     * 文件类型只认 DOCX / XLSX，写错直接报错。
     *
     * <p>以前是「不是 XLSX 就当 DOCX」，于是 {@code fileType=PDF} 会安静地按 DOCX 渲染，
     * 拿到一份和预期完全不同的文件却没有任何提示。</p>
     */
    static String fileTypeOf(String raw) {
        String type = StrUtil.blankToDefault(raw, "DOCX").trim().toUpperCase(Locale.ROOT);
        if (!"DOCX".equals(type) && !"XLSX".equals(type)) {
            throw new IllegalArgumentException("不支持的文件类型「" + raw + "」，只能是 DOCX 或 XLSX");
        }
        return type;
    }

    public Set<String> placeholders(byte[] template, String fileType) throws Exception {
        if ("XLSX".equalsIgnoreCase(fileType)) return xlsxPlaceholders(template);
        return docxPlaceholders(template);
    }

    // ------------------------------------------------------------------ DOCX

    /**
     * 循环体内部出现的占位符。这些字段由数组项提供，模拟生成时不需要手填，
     * 前端据此把它们标成「数据块自动填充」。
     */
    public Set<String> loopFields(byte[] template, String fileType) throws Exception {
        Set<String> keys = new TreeSet<>();
        if ("XLSX".equalsIgnoreCase(fileType)) {
            try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(template))) {
                for (int s = 0; s < workbook.getNumberOfSheets(); s++) {
                    List<String> texts = sheetRowTexts(workbook.getSheetAt(s));
                    for (int index : loopRowIndexes(texts)) collect(texts.get(index), keys);
                }
            }
            return keys;
        }
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(template))) {
            for (XWPFTable table : document.getTables()) collectLoopFields(table, false, keys);
            // 段落块循环：按层数配对，循环体内的段落算循环字段（嵌套时层数会累加）
            int depth = 0;
            for (IBodyElement element : document.getBodyElements()) {
                if (!(element instanceof XWPFParagraph paragraph)) continue;
                String text = paragraph.getText();
                if (isLoopStart(text)) {
                    depth++;
                } else if (isLoopEnd(text)) {
                    if (depth > 0) depth--;
                } else if (depth > 0) {
                    collect(text, keys);
                }
            }
        }
        return keys;
    }

    private void collectLoopFields(XWPFTable table, boolean inLoop, Set<String> keys) {
        List<XWPFTableRow> rows = table.getRows();
        if (rows.isEmpty()) return;
        List<String> texts = new ArrayList<>(rows.size());
        for (XWPFTableRow row : rows) texts.add(rowText(row));
        // 外层已经在循环里时，整张表都属于循环体，不必再挑行
        Set<Integer> loopRows = inLoop ? null : loopRowIndexes(texts);
        for (int i = 0; i < rows.size(); i++) {
            boolean rowInLoop = inLoop || (loopRows != null && loopRows.contains(i));
            for (XWPFTableCell cell : rows.get(i).getTableCells()) {
                for (XWPFTable nested : cell.getTables()) collectLoopFields(nested, rowInLoop, keys);
            }
            if (rowInLoop) collect(texts.get(i), keys);
        }
    }

    public byte[] renderDocx(byte[] template, Map<String, Object> params) throws Exception {
        return renderDocx(template, params, List.of(), DocTemplateMergeAlign.DEFAULT, Map.of());
    }

    public byte[] renderDocx(byte[] template, Map<String, Object> params,
                             List<DocTemplateImageSpec> images) throws Exception {
        return renderDocx(template, params, images, DocTemplateMergeAlign.DEFAULT, Map.of());
    }

    public byte[] renderDocx(byte[] template, Map<String, Object> params,
                             List<DocTemplateImageSpec> images, DocTemplateMergeAlign mergeAlign) throws Exception {
        return renderDocx(template, params, images, mergeAlign, Map.of());
    }

    public byte[] renderDocx(byte[] template, Map<String, Object> params,
                             List<DocTemplateImageSpec> images, DocTemplateMergeAlign mergeAlign,
                             Map<String, List<DocTemplateColumnSpec>> columns) throws Exception {
        log.debug("渲染 DOCX，参数：{}，图片字段：{}，合并对齐：{}，列区：{}",
                describeParams(params), describeImages(images), mergeAlign, describeColumns(columns));
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(template));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            expandDocxLoops(document, params, images, mergeAlign, columns);
            for (XWPFParagraph paragraph : document.getParagraphs()) replaceInParagraph(paragraph, params, images);
            for (XWPFTable table : document.getTables()) replaceInTable(table, params, images);
            for (XWPFHeader header : document.getHeaderList()) {
                for (XWPFParagraph paragraph : header.getParagraphs()) replaceInParagraph(paragraph, params, images);
                for (XWPFTable table : header.getTables()) replaceInTable(table, params, images);
            }
            for (XWPFFooter footer : document.getFooterList()) {
                for (XWPFParagraph paragraph : footer.getParagraphs()) replaceInParagraph(paragraph, params, images);
                for (XWPFTable table : footer.getTables()) replaceInTable(table, params, images);
            }
            document.write(out);
            return out.toByteArray();
        }
    }

    public Set<String> docxPlaceholders(byte[] template) throws Exception {
        Set<String> keys = new TreeSet<>();
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(template))) {
            for (XWPFParagraph paragraph : document.getParagraphs()) collect(paragraph.getText(), keys);
            for (XWPFTable table : document.getTables()) collectTable(table, keys);
            for (XWPFHeader header : document.getHeaderList()) {
                for (XWPFParagraph paragraph : header.getParagraphs()) collect(paragraph.getText(), keys);
                for (XWPFTable table : header.getTables()) collectTable(table, keys);
            }
            for (XWPFFooter footer : document.getFooterList()) {
                for (XWPFParagraph paragraph : footer.getParagraphs()) collect(paragraph.getText(), keys);
                for (XWPFTable table : footer.getTables()) collectTable(table, keys);
            }
        }
        return keys;
    }

    private void replaceInTable(XWPFTable table, Map<String, Object> params, List<DocTemplateImageSpec> images) {
        for (XWPFTableRow row : table.getRows()) {
            for (XWPFTableCell cell : row.getTableCells()) {
                for (XWPFParagraph paragraph : cell.getParagraphs()) replaceInParagraph(paragraph, params, images);
                for (XWPFTable nested : cell.getTables()) replaceInTable(nested, params, images);
            }
        }
    }

    // ------------------------------------------------------------------ loops

    private void collectTable(XWPFTable table, Set<String> keys) {
        for (XWPFTableRow row : table.getRows()) {
            for (XWPFTableCell cell : row.getTableCells()) {
                for (XWPFParagraph paragraph : cell.getParagraphs()) collect(paragraph.getText(), keys);
                for (XWPFTable nested : cell.getTables()) collectTable(nested, keys);
            }
        }
    }

    /**
     * 替换段落里的占位符。
     *
     * <p>Word 经常把一个占位符拆到好几个 run 里（{@code ${amo} + {unt}}），所以先把整段文本拼起来定位，
     * 再<b>按 run 边界切成片段</b>写回去：替换出来的值沿用「占位符起点所在 run」的字符格式，
     * 其余 run 一个字节都不动。这样同一段里「合计：」加粗、{@code ${amount}} 常规、「元」斜体
     * 各留各的格式，不会整段塌成第一个 run 的样式。</p>
     *
     * <p>段落里有声明为图片的占位符时整段重建 run —— POI 只能在 run 末尾追加图片，文字和图片混排
     * 必须重排；重建时每个片段也带自己来源 run 的格式，不再统一套第一个 run 的。</p>
     */
    private void replaceInParagraph(XWPFParagraph paragraph, Map<String, Object> params,
                                    List<DocTemplateImageSpec> images) {
        List<XWPFRun> runs = paragraph.getRuns();
        if (runs == null || runs.isEmpty()) return;
        String original = paragraphText(runs);
        if (original.isEmpty() || original.indexOf('{') < 0) return;
        List<ParagraphSegment> segments = paragraphSegments(original, params, images, runs);
        if (segments == null) return;
        if (segments.stream().anyMatch(segment -> segment.image() != null)) {
            DocTemplateImageSpec layout = null;
            for (ParagraphSegment segment : segments) {
                if (segment.image() != null) {
                    layout = segment.image().spec();
                    break;
                }
            }
            rebuildParagraph(paragraph, segments);
            // 只有「图片独占段落」时才动对齐和留白：混排时改了会把同一段的文字一起挪位置，
            // 那种情况交给模板自己排版（在 Word 里设对齐/缩进即可）。
            if (imageOnly(segments)) applyImageLayout(paragraph, layout);
            return;
        }
        writeBackRuns(runs, segments);
    }

    /**
     * 把整段文本按占位符切成片段，每片带上来源 run 的格式。
     *
     * @return 片段列表；整段没有任何改动时返回 {@code null}，调用方据此保持段落原样
     */
    private List<ParagraphSegment> paragraphSegments(String text, Map<String, Object> params,
                                                     List<DocTemplateImageSpec> images, List<XWPFRun> runs) {
        int[] starts = runStarts(runs);
        List<CTRPr> formats = runFormats(runs);
        List<ParagraphSegment> segments = new ArrayList<>();
        Matcher matcher = PLACEHOLDER.matcher(text);
        int cursor = 0;
        boolean changed = false;
        while (matcher.find()) {
            String token = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            checkToken(token);
            if (isStructuralMarker(token)) {
                // 整段只有这一个结构标记，说明它本该被行展开、列区展开或段落展开消费掉，却没被认出来
                // （缺数组名、关键字拼错、角色写错…）。混在文字里的（正文当例子讲的）原样保留。
                if (FULL_PLACEHOLDER.matcher(text).matches()) {
                    throw new IllegalArgumentException(residueMessage(matcher.group()));
                }
                continue;
            }
            DocTemplateImageSpec spec = specFor(keyOf(token), images);
            if (spec != null) {
                DocTemplateImageLoader.ImagePayload payload =
                        imageLoader.load(resolve(token, params), spec, params, keyOf(token));
                // 值为空：保留占位符文本，交给普通文本替换（走默认值或空串）
                if (payload != null) {
                    appendLiteral(segments, text, cursor, matcher.start(), starts, formats);
                    segments.add(ParagraphSegment.ofImage(new ImageSegment(payload, spec),
                            formattingAt(starts, formats, matcher.start())));
                    cursor = matcher.end();
                    changed = true;
                    continue;
                }
            }
            appendLiteral(segments, text, cursor, matcher.start(), starts, formats);
            String replaced = stringify(resolve(token, params));
            if (!replaced.equals(matcher.group())) changed = true;
            segments.add(ParagraphSegment.ofText(replaced, formattingAt(starts, formats, matcher.start()),
                    runAt(starts, matcher.start())));
            cursor = matcher.end();
        }
        if (!changed) return null;
        appendLiteral(segments, text, cursor, text.length(), starts, formats);
        return segments;
    }

    /** 追加一段字面文本，按 run 边界切开，保证每片只带一个 run 的格式。 */
    private static void appendLiteral(List<ParagraphSegment> segments, String text, int from, int to,
                                      int[] starts, List<CTRPr> formats) {
        int cursor = from;
        while (cursor < to) {
            int run = runAt(starts, cursor);
            int end = Math.min(to, starts[run + 1]);
            segments.add(ParagraphSegment.ofText(text.substring(cursor, end), formattingOf(formats, run), run));
            cursor = end;
        }
    }

    /**
     * 按 run 归并片段后原地写回，只改文本真的变了的 run。
     *
     * <p>没被占位符覆盖的 run（制表符、换行、域代码）一个字节都不动；被整个占位符吃掉的 run
     * 归并结果是空串，会被清空，不会留下「100unt}」这种残尾。</p>
     */
    private static void writeBackRuns(List<XWPFRun> runs, List<ParagraphSegment> segments) {
        String[] merged = new String[runs.size()];
        for (ParagraphSegment segment : segments) {
            if (segment.image() != null) continue;
            merged[segment.run()] = (merged[segment.run()] == null ? "" : merged[segment.run()]) + segment.text();
        }
        for (int i = 0; i < runs.size(); i++) {
            String value = merged[i] == null ? "" : merged[i];
            if (value.equals(runText(runs.get(i)))) continue;
            setRunText(runs.get(i), value);
        }
    }

    /**
     * 把一个 run 的文本整体换成 {@code text}，只留一个 {@code w:t}。
     *
     * <p>POI 的 {@code setText(value, 0)} 只改第 0 个 {@code w:t}，一个 run 里有多段文本
     * （Word 另存、跨版本复制常见）时后面的会残留成重影，所以多出来的手动删掉。</p>
     */
    private static void setRunText(XWPFRun run, String text) {
        run.setText(text, 0);
        CTR ctr = run.getCTR();
        for (int i = ctr.sizeOfTArray() - 1; i >= 1; i--) ctr.removeT(i);
    }

    private static String paragraphText(List<XWPFRun> runs) {
        StringBuilder sb = new StringBuilder();
        for (XWPFRun run : runs) sb.append(runText(run));
        return sb.toString();
    }

    private static String runText(XWPFRun run) {
        String text = run.text();
        return text == null ? "" : text;
    }

    /** 每个 run 的起始字符偏移；末尾多一个元素是整段长度，方便直接取 run 的结束位置。 */
    private static int[] runStarts(List<XWPFRun> runs) {
        int[] starts = new int[runs.size() + 1];
        for (int i = 0; i < runs.size(); i++) starts[i + 1] = starts[i] + runText(runs.get(i)).length();
        return starts;
    }

    /** 字符偏移落在第几个 run。空 run 会被跳过，落到后面第一个有文本的 run。 */
    private static int runAt(int[] starts, int offset) {
        int last = starts.length - 2;
        int run = 0;
        while (run < last && starts[run + 1] <= offset) run++;
        return run;
    }

    /** 预先拷一份每个 run 的字符格式：重建前要先删 run，不能直接引用被删掉的 XML 节点。 */
    private static List<CTRPr> runFormats(List<XWPFRun> runs) {
        List<CTRPr> formats = new ArrayList<>(runs.size());
        for (XWPFRun run : runs) {
            CTRPr formatting = run.getCTR().getRPr();
            formats.add(formatting == null ? null : (CTRPr) formatting.copy());
        }
        return formats;
    }

    private static CTRPr formattingAt(int[] starts, List<CTRPr> formats, int offset) {
        return formattingOf(formats, runAt(starts, offset));
    }

    private static CTRPr formattingOf(List<CTRPr> formats, int run) {
        return run < formats.size() ? formats.get(run) : null;
    }

    // ------------------------------------------------------------------ images

    private static boolean imageOnly(List<ParagraphSegment> segments) {
        for (ParagraphSegment segment : segments) {
            if (segment.image() != null) continue;
            if (segment.text() != null && segment.text().isBlank()) continue;
            return false;
        }
        return true;
    }

    /** 清空原 runs，按片段顺序重建；每片带上自己来源 run 的字符格式。 */
    private void rebuildParagraph(XWPFParagraph paragraph, List<ParagraphSegment> segments) {
        List<XWPFRun> runs = paragraph.getRuns();
        for (int i = runs.size() - 1; i >= 0; i--) paragraph.removeRun(i);
        for (ParagraphSegment segment : segments) {
            XWPFRun run = paragraph.createRun();
            if (segment.formatting() != null) run.getCTR().setRPr((CTRPr) segment.formatting().copy());
            if (segment.image() != null) {
                DocTemplateImageLoader.ImagePayload payload = segment.image().payload();
                try {
                    run.addPicture(new ByteArrayInputStream(payload.bytes()), payload.pictureType(), payload.fileName(),
                            payload.widthPx() * DocTemplateImageLoader.EMU_PER_PIXEL,
                            payload.heightPx() * DocTemplateImageLoader.EMU_PER_PIXEL);
                } catch (Exception error) {
                    throw new IllegalStateException("图片写入 DOCX 失败：" + error.getMessage(), error);
                }
            } else if (segment.text() != null && !segment.text().isEmpty()) {
                run.setText(segment.text(), 0);
            }
        }
    }

    /**
     * 图片在单元格/段落里的排版：水平对齐按配置（默认居中），四周留白用段落缩进 + 段前段后间距实现。
     * 只在「图片独占段落」时调用，混排段落保持模板原格式。
     *
     * <p>之所以不用单元格边距（{@code w:tcMar}）：那会影响同一个单元格里的其他文字，而段落级留白
     * 只作用在图片所在段落，混排时更可控。</p>
     */
    private void applyImageLayout(XWPFParagraph paragraph, DocTemplateImageSpec spec) {
        String align = spec == null ? DocTemplateImageSpec.ALIGN_CENTER : spec.align();
        paragraph.setAlignment(switch (align) {
            case DocTemplateImageSpec.ALIGN_LEFT -> ParagraphAlignment.LEFT;
            case DocTemplateImageSpec.ALIGN_RIGHT -> ParagraphAlignment.RIGHT;
            default -> ParagraphAlignment.CENTER;
        });
        int padding = spec == null ? DocTemplateImageSpec.DEFAULT_PADDING : spec.padding();
        int twips = padding * 15;
        if (twips > 0) {
            paragraph.setIndentationLeft(twips);
            paragraph.setIndentationRight(twips);
            paragraph.setSpacingBefore(twips);
            paragraph.setSpacingAfter(twips);
        }
        // 单元格里只有一个段落（只放图片）时，垂直方向也居中，看起来才是「格子正中」
        if (paragraph.getBody() instanceof XWPFTableCell cell && cell.getParagraphs().size() == 1) {
            cell.setVerticalAlignment(XWPFTableCell.XWPFVertAlign.CENTER);
        }
    }

    /** 段落重建用的图片片段：图片数据 + 该字段的排版声明。 */
    private record ImageSegment(DocTemplateImageLoader.ImagePayload payload, DocTemplateImageSpec spec) {
    }

    /**
     * 段落里的一个片段：文本或图片。
     *
     * <p>{@code formatting} 是来源 run 的字符格式（原地写回时用不上，重建时按片套回去）；
     * {@code run} 是来源 run 的下标，图片片段为 -1。</p>
     */
    private record ParagraphSegment(String text, ImageSegment image, CTRPr formatting, int run) {

        static ParagraphSegment ofText(String text, CTRPr formatting, int run) {
            return new ParagraphSegment(text, null, formatting, run);
        }

        static ParagraphSegment ofImage(ImageSegment image, CTRPr formatting) {
            return new ParagraphSegment(null, image, formatting, -1);
        }
    }

    private DocTemplateImageSpec specFor(String key, List<DocTemplateImageSpec> images) {
        if (images == null || images.isEmpty() || StrUtil.isBlank(key)) return null;
        for (DocTemplateImageSpec spec : images) {
            if (spec != null && spec.matches(key)) return spec;
        }
        return null;
    }

    private String describeImages(List<DocTemplateImageSpec> images) {
        if (images == null || images.isEmpty()) return "（无）";
        List<String> parts = new ArrayList<>();
        for (DocTemplateImageSpec spec : images) {
            if (spec == null) continue;
            String size = spec.width() == null && spec.height() == null ? ""
                    : "(" + (spec.width() == null ? "auto" : spec.width()) + "x"
                    + (spec.height() == null ? "auto" : spec.height()) + ")";
            parts.add(spec.field() + size + "," + spec.align() + ",pad=" + spec.padding());
        }
        return parts.isEmpty() ? "（无）" : String.join("、", parts);
    }

    /**
     * 先展开循环，再做整篇替换：循环体用「当前项 + 外层参数」的上下文替换。
     */
    private void expandDocxLoops(XWPFDocument document, Map<String, Object> params,
                                 List<DocTemplateImageSpec> images, DocTemplateMergeAlign mergeAlign,
                                 Map<String, List<DocTemplateColumnSpec>> columns) {
        for (XWPFTable table : document.getTables()) expandTableLoops(table, params, images, mergeAlign, columns);
        for (XWPFHeader header : document.getHeaderList()) {
            for (XWPFTable table : header.getTables()) expandTableLoops(table, params, images, mergeAlign, columns);
        }
        for (XWPFFooter footer : document.getFooterList()) {
            for (XWPFTable table : footer.getTables()) expandTableLoops(table, params, images, mergeAlign, columns);
        }
        expandBodyParagraphLoops(document, params, images);
    }

    /**
     * 表格行循环，两种写法都支持，且可以任意嵌套（递归渲染）：
     *
     * <ul>
     *   <li><b>行内单行循环</b>：某行任意单元格写 {@code {{#each items}}}，该行按数组重复；</li>
     *   <li><b>行区域循环</b>：{@code {{#each items}}} 与 {@code {{/each}}} 各独占一行，
     *       两者之间的所有行整体重复。区域内再写一对标记就是内层循环，层级不限。</li>
     * </ul>
     *
     * <p>实现上先把整张表规划成「用哪一行做模板 + 用什么作用域」的扁平列表（{@link #planRows}），
     * 再按列表重建表格。嵌套循环因此不需要在表格里做原地插删，也不会出现「复制出来的行
     * 带着上一项的值」。</p>
     */
    private void expandTableLoops(XWPFTable table, Map<String, Object> params,
                                  List<DocTemplateImageSpec> images, DocTemplateMergeAlign mergeAlign,
                                  Map<String, List<DocTemplateColumnSpec>> columns) {
        List<XWPFTableRow> rows = table.getRows();
        if (rows.isEmpty()) return;
        // 列区展开必须发生在 planRows 之前：展开完表格退回成「普通表格 + 普通占位符」，
        // 行循环、占位符替换、竖向合并一行都不用改。
        // 列定义为空也要进来扫一遍：模板写了 {{#columns}} 却没配列源时，这里会给出明确的报错
        boolean columnsExpanded = false;
        if (columns != null) {
            List<CTRow> expanded = expandDocxColumns(table, rows, columns);
            if (expanded != null) {
                rows = replaceRows(table, expanded);
                columnsExpanded = true;
            }
        }
        List<String> texts = new ArrayList<>(rows.size());
        for (XWPFTableRow row : rows) texts.add(rowText(row));
        List<PlannedRow> plan = planRows(texts, 0, texts.size(), params);
        // 展开过列区的表一定要走重建：表格里那批行对象是新建的，只有重建这一遍才会把占位符替换掉
        if (!columnsExpanded && planIsIdentity(plan, texts)) return;
        // 快照模板行：原行会被第一项渲染掉，后续项必须用「未渲染」的副本
        List<CTRow> templates = new ArrayList<>(rows.size());
        for (XWPFTableRow row : rows) templates.add((CTRow) row.getCtRow().copy());
        for (int i = table.getRows().size() - 1; i >= 0; i--) table.removeRow(i);
        // 建出来的行要用「重新包一层」的副本：createRow 那个对象缓存的是空单元格，
        // 直接 table.getRow(p) 拿到的是缓存对象，读不到 set 进去的内容
        List<XWPFTableRow> built = new ArrayList<>(plan.size());
        for (PlannedRow planned : plan) {
            XWPFTableRow created = table.createRow();
            if (created == null) {
                built.add(null);
                continue;
            }
            created.getCtRow().set(templates.get(planned.index()));
            XWPFTableRow row = new XWPFTableRow(created.getCtRow(), table);
            built.add(row);
            renderRowWithScope(row, planned, images, mergeAlign, columns);
        }
        // 行已经按计划建好，位置即计划下标，合并按这个下标算
        applyTableMerges(built, plan, mergeAlign);
    }

    /**
     * 用一批 {@code CTRow} 整体替换表格的行，返回「重新包一层」的行对象。
     *
     * <p>{@code XWPFTableRow} 在构造时就把单元格缓存下来了，直接改它背后的 {@code CTRow} 读不到新内容，
     * 所以每次重建都要新建包装对象；表格自身的行列表里那份缓存对象只用于定位，不再读写内容。</p>
     */
    private List<XWPFTableRow> replaceRows(XWPFTable table, List<CTRow> ctRows) {
        for (int i = table.getRows().size() - 1; i >= 0; i--) table.removeRow(i);
        List<XWPFTableRow> built = new ArrayList<>(ctRows.size());
        for (CTRow ctRow : ctRows) {
            XWPFTableRow created = table.createRow();
            if (created == null) continue;
            created.getCtRow().set(ctRow);
            built.add(new XWPFTableRow(created.getCtRow(), table));
        }
        return built;
    }

    private void renderRowWithScope(XWPFTableRow row, PlannedRow planned,
                                    List<DocTemplateImageSpec> images, DocTemplateMergeAlign mergeAlign,
                                    Map<String, List<DocTemplateColumnSpec>> columns) {
        clearLoopMarkers(row);
        for (XWPFTableCell cell : row.getTableCells()) {
            // 循环复制出来的行不能沿用模板里的 vMerge：restart / continue 是按行的物理顺序解释的，
            // 复制出来的一份份全是 restart，Word 打开就是散开或重叠的合并区
            if (planned.repeated()) clearVerticalMerge(cell);
            for (XWPFParagraph paragraph : cell.getParagraphs()) replaceInParagraph(paragraph, planned.scope(), images);
            for (XWPFTable nested : cell.getTables()) {
                expandTableLoops(nested, planned.scope(), images, mergeAlign, columns);
            }
        }
    }

    // ------------------------------------------------------------------ 列区（DOCX）

    /**
     * 展开计划里的一处列区标记。
     *
     * @param gridColumn 标记格在展开前的起始网格列（前面各格 {@code gridSpan} 累加，不是数组下标）
     * @param gridSpan   标记格自己占几个网格列（模板里预先横向合并过就会大于 1）
     */
    private record ColumnsSlot(int row, int cell, int gridColumn, int gridSpan, ColumnsMarker marker) {
    }

    /**
     * 把表格里的 {@code {{#columns 块编码 角色}}} 展开成 N 个真实单元格。
     *
     * <p>一次处理这张表的<b>所有行</b>：表头行、数据行、静态行一起，因为加宽和右移是跨行的。
     * 展开完表格退回成「普通表格 + 普通占位符」，后面的行循环、占位符替换、竖向合并一行都不用改。</p>
     *
     * <p>行角色由标记里的关键字决定，不靠行号猜：{@code header} 拆成 N 格、每格写 {@code label}；
     * {@code data} 拆成 N 格、每格 {@code ${字段}} 并按该数组循环，一表只能有一行；
     * {@code span} 不拆格，整格横向盖住整个列区。分组表头写几行 {@code header} 都行。</p>
     *
     * @return 展开后的行快照；表格里没有列区标记时返回 {@code null}
     */
    private List<CTRow> expandDocxColumns(XWPFTable table, List<XWPFTableRow> rows,
                                          Map<String, List<DocTemplateColumnSpec>> columns) {
        List<ColumnsSlot> slots = new ArrayList<>();
        for (int r = 0; r < rows.size(); r++) {
            List<XWPFTableCell> cells = rows.get(r).getTableCells();
            int grid = 0;
            for (int c = 0; c < cells.size(); c++) {
                XWPFTableCell cell = cells.get(c);
                String text = cell.getText();
                ColumnsMarker marker = parseColumns(text);
                if (marker != null) {
                    requireColumnsRole(marker, "表格第 " + (r + 1) + " 行第 " + (c + 1) + " 格");
                    // span 形式允许格内另有文字（合计行、说明行）；拆格形式必须整格只有标记
                    if (!marker.span() && !isColumnsOnly(text)) {
                        throw new IllegalArgumentException("表格第 " + (r + 1) + " 行第 " + (c + 1)
                                + " 格的 {{#columns}} 必须独占一格；想在这一格里同时写文字，"
                                + "请改用 {{#columns " + marker.block() + " span}}（整格横向盖住列区）");
                    }
                    slots.add(new ColumnsSlot(r, c, grid, gridSpan(cell), marker));
                }
                grid += gridSpan(cell);
            }
        }
        if (slots.isEmpty()) return null;

        String block = slots.get(0).marker().block();
        Set<Integer> rowsWithSlot = new HashSet<>();
        for (ColumnsSlot slot : slots) {
            if (!slot.marker().block().equalsIgnoreCase(block)) {
                throw new IllegalArgumentException("同一张表里出现了两个列区标记且编码不一致（"
                        + block + " / " + slot.marker().block() + "），一张表只能有一个列区");
            }
            if (!rowsWithSlot.add(slot.row())) {
                throw new IllegalArgumentException("表格第 " + (slot.row() + 1)
                        + " 行里有两个列区标记，一行只能有一个");
            }
            if (slot.gridColumn() != slots.get(0).gridColumn()) {
                throw new IllegalArgumentException("列区标记必须落在同一个网格列：第 "
                        + (slots.get(0).row() + 1) + " 行在第 " + (slots.get(0).gridColumn() + 1)
                        + " 列，第 " + (slot.row() + 1) + " 行在第 " + (slot.gridColumn() + 1) + " 列");
            }
        }
        List<DocTemplateColumnSpec> specs = columnSpecs(columns, block);

        List<String> texts = new ArrayList<>(rows.size());
        for (XWPFTableRow row : rows) texts.add(rowText(row));
        // 嵌套循环里的列区第一版不支持：外层 {{#each}} 会把数据行复制多份，列区展开顺序说不清
        for (int[] region : loopRegions(texts)) {
            for (ColumnsSlot slot : slots) {
                if (slot.row() > region[0] && slot.row() < region[1]) {
                    throw new IllegalArgumentException("表格第 " + (slot.row() + 1)
                            + " 行的 {{#columns}} 落在 {{#each}} 区域里，嵌套循环里的列区暂不支持；"
                            + "请把列区那一行移出 {{#each}} 区域");
                }
            }
        }

        int regionStart = slots.get(0).gridColumn();
        int regionSpan = Math.max(1, slots.get(0).gridSpan());
        int n = specs.size();
        int columnCount = Math.max(regionStart + regionSpan, 1);
        for (XWPFTableRow row : rows) {
            int sum = 0;
            for (XWPFTableCell cell : row.getTableCells()) sum += gridSpan(cell);
            columnCount = Math.max(columnCount, sum);
        }
        List<Integer> oldWidths = docxGridWidths(table, columnCount);
        List<Integer> newWidths = new ArrayList<>(columnCount);
        for (int i = 0; i < regionStart; i++) newWidths.add(oldWidths.get(i));
        if (n > 0) {
            int regionWidth = 0;
            for (int i = 0; i < regionSpan; i++) regionWidth += oldWidths.get(regionStart + i);
            newWidths.addAll(allocateWidths(regionWidth, specs));
        }
        for (int i = regionStart + regionSpan; i < columnCount; i++) newWidths.add(oldWidths.get(i));

        ColumnsSlot dataSlot = null;
        for (ColumnsSlot slot : slots) {
            if (slot.marker().role() != ColumnsRole.DATA) continue;
            if (dataSlot != null) {
                throw new IllegalArgumentException("表格第 " + (dataSlot.row() + 1) + " 行和第 "
                        + (slot.row() + 1) + " 行都写了 {{#columns " + block + " data}}，数据行只能有一行");
            }
            dataSlot = slot;
        }
        if (dataSlot == null) {
            throw new IllegalArgumentException("表格里的列区没有数据行标记：把要按数组重复的那一行写成 {{#columns "
                    + block + " data}}，这一行会展开成 N 列并按数据重复");
        }
        int dataRow = dataSlot.row();
        checkRowLoopAnnotation(rowText(rows.get(dataRow)), dataSlot.marker(), "表格第 " + (dataRow + 1) + " 行");

        List<CTRow> expanded = new ArrayList<>(rows.size());
        for (int r = 0; r < rows.size(); r++) {
            ColumnsSlot slot = null;
            for (ColumnsSlot candidate : slots) {
                if (candidate.row() == r) {
                    slot = candidate;
                    break;
                }
            }
            if (n == 0) {
                CTRow stripped = stripColumnsRow(rows.get(r), slot, regionStart, regionSpan, newWidths);
                if (stripped != null) expanded.add(stripped);
                continue;
            }
            expanded.add(rebuildDocxRow(rows.get(r), slot, specs, regionStart, regionSpan,
                    r == dataRow, newWidths));
        }
        rebuildDocxGrid(table, newWidths);
        log.debug("表格列区展开：块 {}，列区起始网格列 {}，{} 列，数据行 {}", block, regionStart + 1, n, dataRow + 1);
        return expanded;
    }

    /** 按块编码取列定义；模板里写了标记却没配列源时报错，而不是渲染出一张空表。 */
    private List<DocTemplateColumnSpec> columnSpecs(Map<String, List<DocTemplateColumnSpec>> columns, String block) {
        if (columns != null) {
            List<DocTemplateColumnSpec> specs = columns.get(block);
            if (specs == null) {
                for (Map.Entry<String, List<DocTemplateColumnSpec>> entry : columns.entrySet()) {
                    if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(block)) {
                        specs = entry.getValue();
                        break;
                    }
                }
            }
            if (specs != null) return specs;
        }
        throw new IllegalArgumentException("模板里的列区标记指向数据块 " + block
                + "，但这个块没有配置列源。请在数据块配置里打开「动态列」并配好列定义的取数方式");
    }

    /** 模板里 {@code {{#each}}...{{/each}}} 各占一行的区域，返回 {@code [起始行, 结束行]}。 */
    private List<int[]> loopRegions(List<String> texts) {
        List<int[]> regions = new ArrayList<>();
        int i = 0;
        while (i < texts.size()) {
            if (isLoopStart(texts.get(i))) {
                int end = matchLoopEnd(texts, i, texts.size());
                if (end > i) {
                    regions.add(new int[]{i, end});
                    i++;
                    continue;
                }
            }
            i++;
        }
        return regions;
    }

    /** 列区宽度按权重分配：固定列保留原宽，列区总宽不变。 */
    private List<Integer> allocateWidths(int total, List<DocTemplateColumnSpec> specs) {
        List<Integer> widths = new ArrayList<>(specs.size());
        double sum = DocTemplateColumnSpec.totalWeight(specs);
        if (sum <= 0) sum = specs.size();
        int used = 0;
        for (int i = 0; i < specs.size(); i++) {
            int width = i == specs.size() - 1
                    ? Math.max(1, total - used)
                    : Math.max(1, (int) Math.round(total * specs.get(i).width() / sum));
            widths.add(width);
            used += width;
        }
        int narrowest = widths.stream().mapToInt(Integer::intValue).min().orElse(0);
        if (total > 0 && narrowest < NARROW_COLUMN_TWIPS) {
            log.warn("表格列区展开成 {} 列后最窄一列只有 {} twips（约 {} cm），可能挤在一起；"
                            + "可以减少列数，或在模板里把列区起始列调宽",
                    specs.size(), narrowest, String.format(Locale.ROOT, "%.1f", narrowest / 567.0));
        }
        return widths;
    }

    /** 读原始表格网格宽度；{@code tblGrid} 缺失或和实际列数对不上时按总宽均分兜底。 */
    private List<Integer> docxGridWidths(XWPFTable table, int columnCount) {
        List<Integer> widths = new ArrayList<>();
        int total = 0;
        CTTblGrid grid = table.getCTTbl().getTblGrid();
        if (grid != null) {
            for (int i = 0; i < grid.sizeOfGridColArray(); i++) {
                int width = twips(grid.getGridColArray(i).getW());
                widths.add(width);
                total += width;
            }
        }
        if (widths.size() == columnCount && total > 0) return widths;
        int each = Math.max(1, docxTableWidth(table) / Math.max(1, columnCount));
        widths.clear();
        for (int i = 0; i < columnCount; i++) widths.add(each);
        return widths;
    }

    private int docxTableWidth(XWPFTable table) {
        CTTblPr properties = table.getCTTbl().getTblPr();
        if (properties != null && properties.isSetTblW()) {
            CTTblWidth width = properties.getTblW();
            if (width.getType() == STTblWidth.DXA) {
                int twips = twips(width.getW());
                if (twips > 0) return twips;
            }
        }
        return DEFAULT_TABLE_WIDTH;
    }

    /**
     * {@code w:w} 在 schema 里是联合类型（数字 twips 或 {@code "20mm"} 这类通用度量），
     * XmlBeans 给回来是 {@code Object}。这里只认数字，量不出来就返回 0 交给均分兜底。
     */
    private int twips(Object raw) {
        if (raw instanceof Number number) return Math.max(0, number.intValue());
        if (raw instanceof CharSequence text) {
            try {
                return Math.max(0, (int) Double.parseDouble(text.toString().trim()));
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    /** 重建 {@code tblGrid}：网格列数量必须和每格的 {@code tcW} 对齐，否则 Word 按旧网格显示，列宽全乱。 */
    private void rebuildDocxGrid(XWPFTable table, List<Integer> widths) {
        CTTbl ctTbl = table.getCTTbl();
        CTTblGrid grid = ctTbl.getTblGrid();
        if (grid == null) grid = ctTbl.addNewTblGrid();
        while (grid.sizeOfGridColArray() > 0) grid.removeGridCol(0);
        for (Integer width : widths) grid.addNewGridCol().setW(BigInteger.valueOf(Math.max(1, width)));
    }

    /**
     * 重建一行：标记格按列定义拆成 N 格（或合并成 1 格），覆盖列区起始列的格加宽 N−1，
     * 列区右侧的格自然右移（重建后位置由前面各格累加决定）。
     */
    private CTRow rebuildDocxRow(XWPFTableRow row, ColumnsSlot slot, List<DocTemplateColumnSpec> specs,
                                 int regionStart, int regionSpan, boolean dataRow, List<Integer> widths) {
        List<XWPFTableCell> cells = row.getTableCells();
        int n = specs.size();
        List<CTTc> out = new ArrayList<>();
        int grid = 0;
        int newGrid = 0;
        for (int c = 0; c < cells.size(); c++) {
            XWPFTableCell cell = cells.get(c);
            int span = gridSpan(cell);
            boolean marker = slot != null && slot.cell() == c;
            int overlap = overlap(grid, span, regionStart, regionSpan);
            if (marker) {
                if (slot.marker().span()) {
                    CTTc tc = copyCell(cell);
                    setTcGridSpan(tc, n);
                    stripColumnsMarker(new XWPFTableCell(tc, row, row.getTable().getBody()));
                    setTcWidth(tc, sumWidths(widths, newGrid, n));
                    out.add(tc);
                    newGrid += n;
                } else if (dataRow) {
                    int markerCell = c;
                    String loop = loopMarkerOf(slot.marker(), cell.getText(), spec -> {
                        int index = docxColumn(spec);
                        if (index == markerCell) {
                            throw new IllegalArgumentException("merge=" + spec + " 指向了写 {{#columns}} 的那一格："
                                    + "展开后它是 " + n + " 个动态列，纵向合并语义不明；请把 merge= 指向固定列");
                        }
                        return String.valueOf((index > markerCell ? index + n - 1 : index) + 1);
                    });
                    for (int i = 0; i < n; i++) {
                        DocTemplateColumnSpec spec = specs.get(i);
                        CTTc tc = copyCell(cell);
                        XWPFTableCell wrapper = new XWPFTableCell(tc, row, row.getTable().getBody());
                        // 循环标记成对生成：结束标记紧跟在开始标记后面，占的是同一格，不影响其它格的排版
                        setCellText(wrapper, (i == 0 ? loop + "{{/each}}" : "") + "${" + spec.field() + "}");
                        setTcWidth(tc, widths.get(newGrid));
                        out.add(tc);
                        newGrid += 1;
                    }
                } else {
                    for (int i = 0; i < n; i++) {
                        DocTemplateColumnSpec spec = specs.get(i);
                        CTTc tc = copyCell(cell);
                        XWPFTableCell wrapper = new XWPFTableCell(tc, row, row.getTable().getBody());
                        setCellText(wrapper, spec.label());
                        setTcWidth(tc, widths.get(newGrid));
                        out.add(tc);
                        newGrid += 1;
                    }
                }
            } else {
                if (slot != null && overlap > 0) {
                    throw new IllegalArgumentException("表格第 " + (indexOfRow(row) + 1) + " 行第 " + (c + 1)
                            + " 格用横向合并盖住了列区标记格，列区里放不下 N 列；"
                            + "请去掉这一格的横向合并，或把列区标记挪到没被合并的格子里");
                }
                int newSpan = overlap > 0 ? span - overlap + n : span;
                CTTc tc = copyCell(cell);
                // 数据行行首行尾那对 {{#each}}/{{/each}} 只是把块名标出来，循环由 data 角色驱动
                if (dataRow) stripRowLoopMarker(tc, row);
                setTcGridSpan(tc, newSpan);
                setTcWidth(tc, sumWidths(widths, newGrid, newSpan));
                out.add(tc);
                newGrid += newSpan;
            }
            grid += span;
        }
        CTRow ctRow = (CTRow) row.getCtRow().copy();
        while (ctRow.sizeOfTcArray() > 0) ctRow.removeTc(0);
        for (CTTc tc : out) ctRow.addNewTc().set(tc);
        return ctRow;
    }

    /** 列定义为空数组时：表头行、数据行整行删掉，其它行里覆盖列区的格删掉，删空了整行删。 */
    private CTRow stripColumnsRow(XWPFTableRow row, ColumnsSlot slot, int regionStart, int regionSpan,
                                  List<Integer> widths) {
        List<XWPFTableCell> cells = row.getTableCells();
        List<CTTc> keep = new ArrayList<>();
        int grid = 0;
        int newGrid = 0;
        for (int c = 0; c < cells.size(); c++) {
            XWPFTableCell cell = cells.get(c);
            int span = gridSpan(cell);
            boolean marker = slot != null && slot.cell() == c;
            boolean covers = overlap(grid, span, regionStart, regionSpan) > 0;
            if (!marker && !covers) {
                CTTc tc = copyCell(cell);
                setTcGridSpan(tc, span);
                setTcWidth(tc, sumWidths(widths, newGrid, span));
                keep.add(tc);
                newGrid += span;
            }
            grid += span;
        }
        if (keep.isEmpty()) return null;
        CTRow ctRow = (CTRow) row.getCtRow().copy();
        while (ctRow.sizeOfTcArray() > 0) ctRow.removeTc(0);
        for (CTTc tc : keep) ctRow.addNewTc().set(tc);
        return ctRow;
    }

    /**
     * 数据行的循环标记：{@code {{#columns 块 merge=1}} → {{#each 块 merge=<改写后的列>}}。
     *
     * <p>列展开会把列号改掉，而 {@code merge=} 认的是展开前的物理位置，所以序号必须按映射改写，
     * 否则合并会落到相邻的固定列上。</p>
     *
     * @param rewriteColumn 把 {@code merge=} 里的一项改写成展开后的写法；落在列区里时抛异常
     */
    private String loopMarkerOf(ColumnsMarker marker, String cellText, Function<String, String> rewriteColumn) {
        Matcher matcher = COLUMNS_START.matcher(cellText);
        String tail = matcher.find() && matcher.group(2) != null ? matcher.group(2) : "";
        StringBuilder sb = new StringBuilder("{{#each ").append(marker.block());
        Matcher where = LOOP_WHERE.matcher(tail);
        if (where.find()) {
            sb.append(" where ").append(where.group(1)).append(" = ").append(where.group(2).trim());
        }
        Matcher merge = LOOP_MERGE.matcher(tail);
        if (merge.find()) {
            List<String> columns = new ArrayList<>();
            for (String spec : splitColumns(merge.group(1))) columns.add(rewriteColumn.apply(spec));
            if (!columns.isEmpty()) sb.append(" merge=").append(String.join(",", columns));
        }
        Matcher by = LOOP_BY.matcher(tail);
        if (by.find()) sb.append(" by=").append(by.group(1).trim());
        return sb.append("}}").toString();
    }

    /** 一段网格区间与列区 {@code [regionStart, regionStart+regionSpan)} 的重叠格数。 */
    private int overlap(int grid, int span, int regionStart, int regionSpan) {
        int start = Math.max(grid, regionStart);
        int end = Math.min(grid + span, regionStart + regionSpan);
        return Math.max(0, end - start);
    }

    private CTTc copyCell(XWPFTableCell cell) {
        return (CTTc) cell.getCTTc().copy();
    }

    /** 把整格内容换成一段文字，保留首个段落与首个 run 的格式（边框、底色、字体都还在 tcPr / pPr / rPr 上）。 */
    private void setCellText(XWPFTableCell cell, String text) {
        while (cell.getParagraphs().size() > 1) {
            cell.removeParagraph(cell.getParagraphs().size() - 1);
        }
        XWPFParagraph paragraph = cell.getParagraphs().isEmpty() ? cell.addParagraph() : cell.getParagraphs().get(0);
        List<XWPFRun> runs = paragraph.getRuns();
        if (runs.isEmpty()) {
            paragraph.createRun().setText(text, 0);
            return;
        }
        runs.get(0).setText(text, 0);
        for (int i = 1; i < runs.size(); i++) runs.get(i).setText("", 0);
    }

    private void stripColumnsMarker(XWPFTableCell cell) {
        for (XWPFParagraph paragraph : cell.getParagraphs()) {
            rewriteText(paragraph, text -> COLUMNS_START.matcher(text).replaceAll(""));
        }
    }

    /**
     * 清掉数据行上的行循环标记。
     *
     * <p>这一行的循环由 {@code data} 角色驱动（展开时会在第一个动态格里生成
     * {@code {{#each 块 merge=…}}}），行首行尾那对标记只是给人看的，留着会让同一行出现两个循环。</p>
     */
    private void stripRowLoopMarker(CTTc tc, XWPFTableRow row) {
        XWPFTableCell wrapper = new XWPFTableCell(tc, row, row.getTable().getBody());
        for (XWPFParagraph paragraph : wrapper.getParagraphs()) {
            rewriteText(paragraph, DocTemplateRenderer::stripLoopMarkers);
        }
    }

    private int gridSpan(XWPFTableCell cell) {
        CTTcPr properties = cell.getCTTc().getTcPr();
        if (properties == null || !properties.isSetGridSpan() || properties.getGridSpan().getVal() == null) return 1;
        return Math.max(1, properties.getGridSpan().getVal().intValue());
    }

    private void setTcGridSpan(CTTc tc, int span) {
        CTTcPr properties = tc.isSetTcPr() ? tc.getTcPr() : tc.addNewTcPr();
        if (span <= 1) {
            if (properties.isSetGridSpan()) properties.unsetGridSpan();
            return;
        }
        CTDecimalNumber gridSpan = properties.isSetGridSpan() ? properties.getGridSpan() : properties.addNewGridSpan();
        gridSpan.setVal(BigInteger.valueOf(span));
    }

    private void setTcWidth(CTTc tc, int twips) {
        if (twips <= 0) return;
        CTTcPr properties = tc.isSetTcPr() ? tc.getTcPr() : tc.addNewTcPr();
        CTTblWidth width = properties.isSetTcW() ? properties.getTcW() : properties.addNewTcW();
        width.setType(STTblWidth.DXA);
        width.setW(BigInteger.valueOf(twips));
    }

    private int sumWidths(List<Integer> widths, int from, int count) {
        int sum = 0;
        for (int i = 0; i < count; i++) {
            int index = from + i;
            if (index >= 0 && index < widths.size()) sum += widths.get(index);
        }
        return sum;
    }

    /** 行在表里的下标，只用于报错信息。 */
    private int indexOfRow(XWPFTableRow row) {
        List<XWPFTableRow> rows = row.getTable() == null ? List.of() : row.getTable().getRows();
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i) == row || rows.get(i).getCtRow() == row.getCtRow()) return i;
        }
        return -1;
    }

    private String describeColumns(Map<String, List<DocTemplateColumnSpec>> columns) {
        if (columns == null || columns.isEmpty()) return "（无）";
        List<String> parts = new ArrayList<>();
        columns.forEach((code, specs) ->
                parts.add(code + "(" + (specs == null ? 0 : specs.size()) + " 列)"));
        return String.join("、", parts);
    }

    /**
     * 把 {@code merge=} 声明落到 DOCX 上：一段的第一格标 {@code vMerge=restart}，
     * 其余标 {@code vMerge=continue} 并清空内容（否则合并区里会叠着几行字）。
     *
     * <p>合并格的内容默认是左上，想要居中就配数据块的 {@code mergeCellAlign}，
     * 对齐只作用在 {@code restart} 那一格（其余格内容已清空，设了也看不出来）。</p>
     */
    private void applyTableMerges(List<XWPFTableRow> rows, List<PlannedRow> plan,
                                  DocTemplateMergeAlign mergeAlign) {
        if (plan.stream().allMatch(row -> row.merges().isEmpty())) return;
        List<MergeSpan> spans = mergeSpans(plan, this::docxColumn,
                (position, column) -> docxCellText(rows, position, column));
        for (MergeSpan span : spans) {
            for (int position = span.from(); position <= span.to(); position++) {
                if (position >= rows.size()) continue;
                XWPFTableRow row = rows.get(position);
                if (row == null) continue;
                List<XWPFTableCell> cells = row.getTableCells();
                if (span.column() < 0 || span.column() >= cells.size()) continue;
                XWPFTableCell cell = cells.get(span.column());
                if (position == span.from()) {
                    setVerticalMerge(cell, true);
                    alignMergedCell(cell, mergeAlign);
                } else {
                    setVerticalMerge(cell, false);
                    clearCellContent(cell);
                }
            }
            log.debug("竖向合并 DOCX 第 {} 个单元格：第 {} 行到第 {} 行，对齐 {}",
                    span.column() + 1, span.from() + 1, span.to() + 1, mergeAlign);
        }
    }

    /**
     * 合并格内容的垂直对齐（{@code w:vAlign}）。
     *
     * <p>水平方向一个字都不动：合并格沿用模板里那一格原本的段落对齐，靠右的数字列、居中的状态列
     * 不会因为竖向合并被打乱。不传（或配成 {@code top}）时连 {@code w:vAlign} 都不写，
     * 保持模板原样——多写一遍反而会把模板作者设的缩进、行距搅乱。</p>
     */
    private void alignMergedCell(XWPFTableCell cell, DocTemplateMergeAlign align) {
        if (align == null || align.isDefault()) return;
        CTTcPr properties = cell.getCTTc().isSetTcPr()
                ? cell.getCTTc().getTcPr() : cell.getCTTc().addNewTcPr();
        CTVerticalJc vertical = properties.isSetVAlign() ? properties.getVAlign() : properties.addNewVAlign();
        vertical.setVal(switch (align.vertical()) {
            case DocTemplateMergeAlign.BOTTOM -> STVerticalJc.BOTTOM;
            case DocTemplateMergeAlign.CENTER -> STVerticalJc.CENTER;
            default -> STVerticalJc.TOP;
        });
    }

    private String docxCellText(List<XWPFTableRow> rows, int position, int column) {
        if (position >= rows.size()) return "";
        XWPFTableRow row = rows.get(position);
        if (row == null) return "";
        List<XWPFTableCell> cells = row.getTableCells();
        if (column < 0 || column >= cells.size()) return "";
        return cells.get(column).getText();
    }

    private void setVerticalMerge(XWPFTableCell cell, boolean restart) {
        CTTcPr properties = cell.getCTTc().isSetTcPr()
                ? cell.getCTTc().getTcPr() : cell.getCTTc().addNewTcPr();
        CTVMerge merge = properties.isSetVMerge() ? properties.getVMerge() : properties.addNewVMerge();
        merge.setVal(restart ? STMerge.RESTART : STMerge.CONTINUE);
    }

    private void clearVerticalMerge(XWPFTableCell cell) {
        if (cell.getCTTc().isSetTcPr() && cell.getCTTc().getTcPr().isSetVMerge()) {
            cell.getCTTc().getTcPr().unsetVMerge();
        }
    }

    /** 清空单元格内容但保留格式与至少一个段落（Word 要求单元格里必须有段落）。 */
    private void clearCellContent(XWPFTableCell cell) {
        List<XWPFParagraph> paragraphs = cell.getParagraphs();
        for (int i = paragraphs.size() - 1; i >= 1; i--) cell.removeParagraph(i);
        if (paragraphs.isEmpty()) return;
        XWPFParagraph first = paragraphs.get(0);
        List<XWPFRun> runs = first.getRuns();
        for (int i = runs.size() - 1; i >= 0; i--) first.removeRun(i);
    }

    /** 一行里的所有文字（按单元格拼接），用来判断循环标记写在哪一行。 */
    private String rowText(XWPFTableRow row) {
        StringBuilder sb = new StringBuilder();
        for (XWPFTableCell cell : row.getTableCells()) sb.append(cell.getText());
        return sb.toString();
    }

    /**
     * 段落块循环：{@code {{#each items}}} 独占一段、{@code {{/each}}} 独占一段，
     * 两者之间的段落整体重复。区域内可以再套一层，层级不限；跨表格的块循环不支持。
     */
    private void expandBodyParagraphLoops(XWPFDocument document, Map<String, Object> params,
                                          List<DocTemplateImageSpec> images) {
        int guard = 0;
        while (guard++ < 500) {
            List<IBodyElement> elements = document.getBodyElements();
            int start = -1;
            int end = -1;
            for (int i = 0; i < elements.size() && start < 0; i++) {
                if (!(elements.get(i) instanceof XWPFParagraph paragraph)) continue;
                if (!isLoopStart(paragraph.getText())) continue;
                int candidate = paragraphEnd(elements, i);
                if (candidate < 0) {
                    throw new IllegalArgumentException("段落块循环 {{#each " + parseLoop(paragraph.getText())
                            + "}} 没有配对的 {{/each}}；两个标记要各占一段，中间的段落整体重复");
                }
                start = i;
                end = candidate;
            }
            if (start < 0) {
                // 循环段都展开完了，剩下的段落里还单独出现 {{/each}} 就是多写的
                for (int i = 0; i < elements.size(); i++) {
                    if (elements.get(i) instanceof XWPFParagraph paragraph && isLoopEnd(paragraph.getText())) {
                        throw new IllegalArgumentException("第 " + (i + 1) + " 段的 {{/each}} 没有配对的 "
                                + "{{#each}}；两个标记要各占一段");
                    }
                }
                return;
            }
            XWPFParagraph startParagraph = (XWPFParagraph) elements.get(start);
            XWPFParagraph endParagraph = (XWPFParagraph) elements.get(end);

            List<XWPFParagraph> body = new ArrayList<>();
            for (int i = start + 1; i < end; i++) {
                if (elements.get(i) instanceof XWPFParagraph paragraph) body.add(paragraph);
            }
            List<String> texts = new ArrayList<>(body.size());
            for (XWPFParagraph paragraph : body) texts.add(paragraph.getText());
            // 外层循环在这里手动展开（要拿它的当前项做作用域），内层标记交给 planRowsInto 递归，
            // 所以内层数组既可以是外层项自带的字段，也可以是带 where 的扁平数组
            List<PlannedRow> plan = new ArrayList<>();
            int index = 1;
            for (Object item : itemsOf(parseLoop(startParagraph.getText()), params)) {
                planRowsInto(texts, 0, texts.size(), scopeOf(item, params, index++), plan, "段");
            }
            // 写回时只是「按计划复制段落」
            for (PlannedRow planned : plan) {
                CTP copy = (CTP) body.get(planned.index()).getCTP().copy();
                XWPFParagraph created = document.insertNewParagraph(endParagraph.getCTP().newCursor());
                created.getCTP().set(copy);
                // 新插入的段落对象缓存的是插入前的空 runs，重新包一层才能读到 set 进去的内容
                XWPFParagraph fresh = new XWPFParagraph(created.getCTP(), document);
                rewriteText(fresh, DocTemplateRenderer::stripLoopMarkers);
                replaceInParagraph(fresh, planned.scope(), images);
            }
            // 先删结束段与正文，再删开始段，避免下标漂移
            removeBodyElement(document, endParagraph);
            for (int i = body.size() - 1; i >= 0; i--) removeBodyElement(document, body.get(i));
            removeBodyElement(document, startParagraph);
        }
    }

    private void removeBodyElement(XWPFDocument document, IBodyElement target) {
        List<IBodyElement> elements = document.getBodyElements();
        for (int i = 0; i < elements.size(); i++) {
            if (elements.get(i) == target) {
                document.removeBodyElement(i);
                return;
            }
        }
    }

    /** 找与 {@code elements[start]} 配对的 {@code {{/each}}}，按层数配对；找不到返回 -1。 */
    private int paragraphEnd(List<IBodyElement> elements, int start) {
        int depth = 0;
        for (int i = start + 1; i < elements.size(); i++) {
            if (!(elements.get(i) instanceof XWPFParagraph paragraph)) continue;
            String text = paragraph.getText();
            if (isLoopEnd(text)) {
                if (depth == 0) return i;
                depth--;
            } else if (isLoopStart(text) && paragraphEnd(elements, i) > i) {
                depth++;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------ 展开计划

    /**
     * 展开计划里的一行：用第几行做模板 + 该行渲染用的作用域 + 该行上的合并声明。
     *
     * @param repeated 是否由循环复制出来（模板里原样保留的行是 {@code false}）。
     *                 DOCX 靠它决定要不要清掉模板自带的 {@code vMerge}
     */
    private record PlannedRow(int index, Map<String, Object> scope, List<MergeRule> merges, boolean repeated) {

        PlannedRow(int index, Map<String, Object> scope) {
            this(index, scope, List.of(), false);
        }

        PlannedRow withMerges(List<MergeRule> rules) {
            return new PlannedRow(index, scope, rules, repeated);
        }
    }

    /**
     * 一条竖向合并声明。
     *
     * @param block 一次循环展开的唯一标识：不同 {@code #each} 实例产出的行即使键相同也不合并
     *              （否则「两笔订单日期相同」会被合并成一格）
     * @param loop  产生这些行的循环标记（含 {@code merge=} / {@code by=} 原始写法）
     */
    private record MergeRule(Object block, Loop loop) {
    }

    /** 合并游标的定位：某个循环块的某一列（{@code block} 用对象身份比较，不是 equals）。 */
    private record MergeSlot(Object block, int column) {
    }

    /** 合并分组的游标：某个循环块里的某一列，当前正在累积的一段。 */
    private record MergeRun(int from, int column, String key, int to) {
    }

    /** 最终要写进文档的一段竖向合并（计划里的位置区间 + 列号）。 */
    private record MergeSpan(int from, int to, int column) {
    }

    /**
     * 把 {@code texts[from,to)} 这段行按循环展开成一份扁平计划。
     *
     * <p>区域循环与行内单行循环都在这里递归解析，所以嵌套层级不限；写回时只需要照计划复制行。
     * 每项的作用域是「外层参数 + 当前项字段」，内层循环取数组时自然能看到外层项的字段。</p>
     */
    private List<PlannedRow> planRows(List<String> texts, int from, int to, Map<String, Object> params) {
        return planRows(texts, from, to, params, "行");
    }

    private List<PlannedRow> planRows(List<String> texts, int from, int to, Map<String, Object> params,
                                      String unit) {
        List<PlannedRow> plan = new ArrayList<>();
        planRowsInto(texts, from, to, params, plan, unit);
        return plan;
    }

    /**
     * @param unit 报错信息里用的量词（表格里是「行」、段落块里是「段」），只为把行号说清楚
     */
    private void planRowsInto(List<String> texts, int from, int to, Map<String, Object> params,
                              List<PlannedRow> plan, String unit) {
        int i = from;
        while (i < to) {
            String text = texts.get(i);
            Loop loop = parseLoop(text);
            if (loop == null) {
                // 区域循环的结束行在 matchLoopEnd 里就被跳过、走不到这里；行内循环的结束标记
                // 和开始标记在同一行，也不会落到这个分支。所以这里还看到 {{/each}} 就是多写的。
                if (LOOP_END.matcher(text).find()) {
                    throw new IllegalArgumentException("第 " + (i + 1) + " " + unit + "的 {{/each}} 没有配对的 "
                            + "{{#each}}；循环的开始和结束标记必须成对写");
                }
                plan.add(new PlannedRow(i, params));
                i++;
                continue;
            }
            boolean rowStart = isLoopStart(text);
            int end = rowStart ? matchLoopEnd(texts, i, to) : -1;
            if (end > i) {
                // 区域循环：区域内所有行按数组重复，内层标记在递归里继续展开
                List<PlannedRow> body = new ArrayList<>();
                int index = 1;
                for (Object item : itemsOf(loop, params)) {
                    planRowsInto(texts, i + 1, end, scopeOf(item, params, index++), body, unit);
                }
                plan.addAll(tagLoop(body, loop));
                i = end + 1;
                continue;
            }
            if (rowStart) {
                throw new IllegalArgumentException("第 " + (i + 1) + " " + unit + "的 {{#each " + loop
                        + "}} 独占一" + unit + "，但没有配对的 {{/each}}；区域循环要把两个标记各写一" + unit
                        + "，行内循环要把两个标记写在同一" + unit + "里");
            }
            if (!LOOP_END.matcher(text).find()) {
                throw new IllegalArgumentException("第 " + (i + 1) + " " + unit + "的 {{#each " + loop
                        + "}} 没有配对的 {{/each}}；行内循环要写成 {{#each 块}}…{{/each}}，"
                        + "两个标记都写在同一" + unit + "里");
            }
            // 行内循环：两个标记写在同一行，整行按数组重复
            List<PlannedRow> single = new ArrayList<>();
            int index = 1;
            for (Object item : itemsOf(loop, params)) single.add(new PlannedRow(i, scopeOf(item, params, index++)));
            plan.addAll(tagLoop(single, loop));
            i++;
        }
    }

    /**
     * 给一段循环产出的行打上「由循环复制」和合并声明的标记。
     *
     * <p>合并声明挂在<b>行</b>上而不是挂在块上，是因为内层循环的行同样落在外层循环的范围里：
     * 外层声明了 {@code merge=1}、内层又展开成多行时，外层的合并照样要把这些行串起来。</p>
     */
    private List<PlannedRow> tagLoop(List<PlannedRow> rows, Loop loop) {
        if (rows.isEmpty()) return rows;
        if (!loop.merging()) {
            List<PlannedRow> tagged = new ArrayList<>(rows.size());
            for (PlannedRow row : rows) {
                tagged.add(new PlannedRow(row.index(), row.scope(), row.merges(), true));
            }
            return tagged;
        }
        MergeRule rule = new MergeRule(new Object(), loop);
        List<PlannedRow> tagged = new ArrayList<>(rows.size());
        for (PlannedRow row : rows) {
            List<MergeRule> rules = new ArrayList<>(row.merges());
            rules.add(rule);
            tagged.add(row.withMerges(List.copyOf(rules)));
        }
        return tagged;
    }

    /** 计划与原文逐行一致、且没有任何循环标记时，说明这张表不需要重排。 */
    private boolean planIsIdentity(List<PlannedRow> plan, List<String> texts) {
        if (plan.size() != texts.size()) return false;
        for (int i = 0; i < texts.size(); i++) {
            if (parseLoop(texts.get(i)) != null) return false;
            if (plan.get(i).index() != i) return false;
        }
        return true;
    }

    /** 找与 {@code texts[start]} 配对的 {@code {{/each}}}，按嵌套层数配对；找不到返回 -1。 */
    private int matchLoopEnd(List<String> texts, int start, int to) {
        int depth = 0;
        for (int i = start + 1; i < to; i++) {
            String text = texts.get(i);
            if (isLoopEnd(text)) {
                if (depth == 0) return i;
                depth--;
            } else if (isLoopStart(text) && matchLoopEnd(texts, i, to) > i) {
                depth++;
            }
        }
        return -1;
    }

    /** 结构扫描：标出哪些行属于循环体（区域内部的行、带行内标记的行），不解析数据。 */
    private Set<Integer> loopRowIndexes(List<String> texts) {
        Set<Integer> indexes = new TreeSet<>();
        scanLoopRows(texts, 0, texts.size(), false, indexes);
        return indexes;
    }

    private void scanLoopRows(List<String> texts, int from, int to, boolean inLoop, Set<Integer> indexes) {
        int i = from;
        while (i < to) {
            String text = texts.get(i);
            Loop loop = parseLoop(text);
            if (loop != null && isLoopStart(text)) {
                int end = matchLoopEnd(texts, i, to);
                if (end > i) {
                    scanLoopRows(texts, i + 1, end, true, indexes);
                    i = end + 1;
                    continue;
                }
            }
            if (inLoop || loop != null || isColumnsDataRow(text)) indexes.add(i);
            i++;
        }
    }

    private boolean isLoopStart(String text) {
        return text != null && LOOP_START.matcher(text.trim()).matches();
    }

    /** 列区的数据行等价于一行 {@code {{#each 块}}}：里面的字段由数据给，不用手填。 */
    private static boolean isColumnsDataRow(String text) {
        ColumnsMarker marker = parseColumns(text);
        return marker != null && marker.role() == ColumnsRole.DATA;
    }

    private boolean isLoopEnd(String text) {
        return text != null && LOOP_END.matcher(text.trim()).matches();
    }

    private void clearLoopMarkers(XWPFTableRow row) {
        for (XWPFTableCell cell : row.getTableCells()) {
            for (XWPFParagraph paragraph : cell.getParagraphs()) {
                rewriteText(paragraph, DocTemplateRenderer::stripLoopMarkers);
            }
        }
    }

    /**
     * 合并 run 后整段重写，避免标记被 Word 拆到多个 run 里导致匹配不到。
     */
    private void rewriteText(XWPFParagraph paragraph, java.util.function.UnaryOperator<String> rewrite) {
        List<XWPFRun> runs = paragraph.getRuns();
        if (runs == null || runs.isEmpty()) return;
        StringBuilder sb = new StringBuilder();
        for (XWPFRun run : runs) sb.append(run.text());
        String original = sb.toString();
        String replaced = rewrite.apply(original);
        if (replaced.equals(original)) return;
        XWPFRun first = runs.get(0);
        first.setText(replaced, 0);
        for (int i = 1; i < runs.size(); i++) runs.get(i).setText("", 0);
    }

    /**
     * 循环体上下文：当前项的字段可直接取值（{@code ${report_no}}），也可用 {@code ${item.report_no}}。
     *
     * <p>另外塞一个 1 起始的行号 {@code ${_index}}：「序号」是最常见的固定列，没有它宿主只能在取数时
     * 自己补一个字段。数据里本来就有同名键时以数据为准（这里先放，随后被当前项覆盖）。</p>
     */
    private Map<String, Object> scopeOf(Object item, Map<String, Object> params, int index) {
        Map<String, Object> scope = new LinkedHashMap<>(params);
        scope.put("_index", index);
        if (item instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                scope.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        scope.put("item", item);
        return scope;
    }

    // ------------------------------------------------------------------ 竖向合并

    /**
     * 按 {@code merge=} 声明算出「哪几行、哪一列要合并」。
     *
     * <p>判定规则（顺序即优先级）：</p>
     * <ol>
     *   <li>只在<b>声明它的那一次循环展开</b>产出的行里合并：{@link MergeRule#block()} 是分块依据，
     *       所以「两笔订单日期相同」不会被并成一格，跨表头 / 跨块也永远不会误并。</li>
     *   <li>只合并<b>相邻</b>的行；中间隔了一行不同的就断组。</li>
     *   <li>分组键相同才合并。写了 {@code by=} 用该字段的值当键（业务主键，推荐），
     *       不写就用渲染后的单元格文本当键（满足「内容一致就合并」的朴素预期）。</li>
     *   <li>键为空的格子永不合并：两个空格子上下相邻不该变成一个大空格。</li>
     *   <li>一段至少 2 行才真的合并。</li>
     * </ol>
     *
     * @param columnResolver 列写法 → 0 起始列号，返回负数表示忽略这一列
     * @param cellText       (计划位置, 列号) → 渲染后的单元格文本，仅在没写 {@code by=} 时用到
     */
    private List<MergeSpan> mergeSpans(List<PlannedRow> plan, Function<String, Integer> columnResolver,
                                       BiFunction<Integer, Integer, String> cellText) {
        List<MergeSpan> spans = new ArrayList<>();
        Map<MergeSlot, MergeRun> active = new LinkedHashMap<>();
        for (int position = 0; position < plan.size(); position++) {
            PlannedRow row = plan.get(position);
            Set<MergeSlot> touched = new HashSet<>();
            for (MergeRule rule : row.merges()) {
                for (String spec : rule.loop().mergeColumns()) {
                    int column = columnResolver.apply(spec);
                    if (column < 0) continue;
                    MergeSlot slot = new MergeSlot(rule.block(), column);
                    touched.add(slot);
                    String key = mergeKey(row, rule, column, position, cellText);
                    MergeRun run = active.get(slot);
                    if (run != null && !key.isEmpty() && run.key().equals(key)) {
                        active.put(slot, new MergeRun(run.from(), column, key, position));
                    } else {
                        if (run != null) flushRun(run, spans);
                        active.put(slot, new MergeRun(position, column, key, position));
                    }
                }
            }
            // 这一行不再属于某个分组的范围（换块 / 换列 / 非循环行），把游标结掉
            Iterator<Map.Entry<MergeSlot, MergeRun>> iterator = active.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<MergeSlot, MergeRun> entry = iterator.next();
                if (touched.contains(entry.getKey())) continue;
                flushRun(entry.getValue(), spans);
                iterator.remove();
            }
        }
        for (MergeRun run : active.values()) flushRun(run, spans);
        return spans;
    }

    private void flushRun(MergeRun run, List<MergeSpan> spans) {
        if (run.to() > run.from()) spans.add(new MergeSpan(run.from(), run.to(), run.column()));
    }

    private String mergeKey(PlannedRow row, MergeRule rule, int column, int position,
                            BiFunction<Integer, Integer, String> cellText) {
        Loop loop = rule.loop();
        String by = loop.mergeBy();
        String key;
        if (by != null && !by.isBlank()) {
            Found found = lookupLenient(by, row.scope());
            if (!found.present()) {
                throw new IllegalArgumentException("循环「" + loop + "」的合并分组键 by=" + by
                        + " 在参数里找不到，当前参数有：" + describeParams(row.scope())
                        + "。by 要写当前作用域里的字段名（明细行里就是该行的字段，如 by=material_id）");
            }
            key = stringify(found.value());
        } else {
            key = cellText.apply(position, column);
        }
        return key == null ? "" : key.trim();
    }

    /** XLSX 的列写法：列字母（{@code A}/{@code BC}）或 1 起始列号。 */
    private int xlsxColumn(String spec) {
        String raw = spec == null ? "" : spec.trim();
        if (raw.isEmpty()) return -1;
        if (raw.chars().allMatch(Character::isDigit)) {
            return Integer.parseInt(raw) - 1;
        }
        try {
            return CellReference.convertColStringToIndex(raw.toUpperCase(Locale.ROOT));
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("merge=" + raw + " 不是合法的列：XLSX 用列字母（A、C）"
                    + "或 1 起始的列号（1、3），多个列用逗号分隔且中间不要空格");
        }
    }

    /** DOCX 的列写法：1 起始的单元格序号（横向合并过的格子在 Word 里算一个）。 */
    private int docxColumn(String spec) {
        String raw = spec == null ? "" : spec.trim();
        if (raw.isEmpty()) return -1;
        if (!raw.chars().allMatch(Character::isDigit)) {
            throw new IllegalArgumentException("merge=" + raw + " 不是合法的单元格序号：DOCX 模板用 1 起始的序号"
                    + "（merge=1,2 表示第 1、2 个单元格），列字母只在 XLSX 里用");
        }
        return Integer.parseInt(raw) - 1;
    }

    // ------------------------------------------------------------------ XLSX

    private List<Object> loopItems(String expression, Map<String, Object> params) {
        Found found = lookupLenient(expression, params);
        if (!found.present()) {
            // 变量根本不存在时如果按空数组处理，行会被静默删掉，用户只会看到「行是空的」，
            // 排查成本极高。这里直接报错，并把当前可用的参数形状列出来。
            throw new IllegalArgumentException("循环变量「" + expression + "」在参数里找不到，当前参数有："
                    + describeParams(params)
                    + "。请确认模板里的 {{#each " + expression + "}} 与数据块编码（或参数名）一致"
                    + suggestArrayParam(params, expression)
                    + "；如果这是内层循环，还要确认外层 {{#each}} 有配对的 {{/each}}"
                    + "（区域循环要求开始与结束标记各独占一行）");
        }
        Object value = found.value();
        if (value == null) {
            log.warn("循环变量「{}」的值为 null，按空数组处理", expression);
            return List.of();
        }
        // SQL 数据块用 JSON_ARRAY / JSON_ARRAYAGG 拼出来的嵌套数组，JDBC 给回来是字符串，
        // 这里自动解析一次，否则「SQL 也能返回嵌套结构」这条路就走不通
        if (value instanceof CharSequence text) {
            String raw = text.toString().trim();
            if (raw.startsWith("[") && raw.endsWith("]")) {
                try {
                    if (DocTemplateJson.read(raw) instanceof List<?> list) {
                        log.debug("循环变量「{}」是 JSON 数组字符串，解析出 {} 条", expression, list.size());
                        return new ArrayList<>(list);
                    }
                } catch (Exception ignored) {
                    // 不是合法 JSON 就按「不是数组」处理，下面统一报错
                }
            }
        }
        if (value instanceof List<?> list) {
            log.debug("循环变量「{}」匹配到 {} 条", expression, list.size());
            return new ArrayList<>(list);
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> items = new ArrayList<>();
            for (Object item : iterable) items.add(item);
            log.debug("循环变量「{}」匹配到 {} 条", expression, items.size());
            return items;
        }
        if (value.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            List<Object> items = new ArrayList<>(length);
            for (int i = 0; i < length; i++) items.add(java.lang.reflect.Array.get(value, i));
            return items;
        }
        throw new IllegalArgumentException("循环变量「" + expression + "」不是数组（实际是 "
                + value.getClass().getSimpleName() + "）。做明细行时数据块要设 single: false 返回数组");
    }

    /** 循环取数：先按数组名取列表，带 {@code where} 时再按关联字段过滤。 */
    private List<Object> itemsOf(Loop loop, Map<String, Object> scope) {
        List<Object> items = loopItems(loop.array(), scope);
        if (!loop.filtered()) return items;
        Object expected = conditionValue(loop, scope);
        if (expected == null) {
            log.debug("循环「{}」的关联值为 null，按空数组处理", loop);
            return List.of();
        }
        String target = String.valueOf(expected).trim();
        List<Object> matched = new ArrayList<>();
        for (Object item : items) {
            Object actual = item instanceof Map<?, ?> map ? mapValue(map, loop.field()) : null;
            // 值比较也严格：只去首尾空格，不再忽略大小写 —— 'PAID' 和 'paid' 是两个值，
            // 要合并成一个应该在数据源里统一，而不是靠渲染器猜
            if (actual != null && String.valueOf(actual).trim().equals(target)) matched.add(item);
        }
        log.debug("循环「{}」按关联条件过滤：{} 条 → {} 条", loop, items.size(), matched.size());
        return matched;
    }

    /** {@code where} 右边的取值：带引号按字面量，否则按当前作用域里的键路径取。 */
    private Object conditionValue(Loop loop, Map<String, Object> scope) {
        String raw = loop.value() == null ? "" : loop.value().trim();
        if (raw.length() >= 2 && (raw.startsWith("'") && raw.endsWith("'")
                || raw.startsWith("\"") && raw.endsWith("\""))) {
            return raw.substring(1, raw.length() - 1);
        }
        Found found = lookupLenient(raw, scope);
        if (!found.present()) {
            throw new IllegalArgumentException("循环「" + loop + "」的关联条件取不到值：参数里没有「" + raw
                    + "」。等号右边写当前作用域里的字段名（如 " + loop.field()
                    + " / item." + loop.field() + "），字面量要加引号（如 'open'）");
        }
        return found.value();
    }

    /** 取明细项里的字段：键名严格区分大小写，和 {@link #lookupLenient} 一致。 */
    private Object mapValue(Map<?, ?> map, String key) {
        return key != null && map.containsKey(key) ? map.get(key) : null;
    }


    /** 参数里只有一个数组时，多半就是模板写错了名字，直接给出猜测。 */
    private String suggestArrayParam(Map<String, Object> params, String expression) {
        if (params == null || params.isEmpty()) return "";
        List<String> arrays = new ArrayList<>();
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            if (entry.getValue() instanceof Collection<?>) arrays.add(entry.getKey());
        }
        if (arrays.size() != 1) return "";
        String candidate = arrays.get(0);
        return "；参数里唯一的数组是「" + candidate + "」，是不是模板里写成了 " + expression + "？";
    }

    /** 循环变量是否存在于参数里（用于区分「没取到」和「取到了但为空」）。 */
    private record Found(boolean present, Object value) {
        static final Found MISSING = new Found(false, null);
    }

    /** 打印参数结构，报错时告诉用户当前到底有什么键。 */
    private String describeParams(Map<String, Object> params) {
        if (params == null || params.isEmpty()) return "（空）";
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            Object value = entry.getValue();
            String shape;
            if (value == null) {
                shape = "null";
            } else if (value instanceof Collection<?> collection) {
                shape = "数组 " + collection.size() + " 条";
            } else if (value instanceof Map<?, ?>) {
                shape = "对象";
            } else {
                shape = value.getClass().getSimpleName();
            }
            parts.add(entry.getKey() + "(" + shape + ")");
        }
        return String.join("、", parts);
    }

    public byte[] renderXlsx(byte[] template, Map<String, Object> params) throws Exception {
        return renderXlsx(template, params, List.of(), DocTemplateMergeAlign.DEFAULT, Map.of());
    }

    public byte[] renderXlsx(byte[] template, Map<String, Object> params,
                             List<DocTemplateImageSpec> images) throws Exception {
        return renderXlsx(template, params, images, DocTemplateMergeAlign.DEFAULT, Map.of());
    }

    public byte[] renderXlsx(byte[] template, Map<String, Object> params,
                             List<DocTemplateImageSpec> images, DocTemplateMergeAlign mergeAlign) throws Exception {
        return renderXlsx(template, params, images, mergeAlign, Map.of());
    }

    public byte[] renderXlsx(byte[] template, Map<String, Object> params,
                             List<DocTemplateImageSpec> images, DocTemplateMergeAlign mergeAlign,
                             Map<String, List<DocTemplateColumnSpec>> columns) throws Exception {
        log.debug("渲染 XLSX，参数：{}，图片字段：{}，合并对齐：{}，列区：{}",
                describeParams(params), describeImages(images), mergeAlign, describeColumns(columns));
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(template));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (int s = 0; s < workbook.getNumberOfSheets(); s++) {
                Sheet sheet = workbook.getSheetAt(s);
                expandSheetColumns(sheet, columns);
                expandSheetLoops(sheet, params, images, mergeAlign);
                for (Row row : sheet) {
                    for (Cell cell : row) renderCell(cell, params, images);
                }
            }
            workbook.write(out);
            return out.toByteArray();
        }
    }

    private void renderCell(Cell cell, Map<String, Object> params, List<DocTemplateImageSpec> images) {
        if (cell.getCellType() != CellType.STRING) return;
        String text = cell.getStringCellValue();
        if (StrUtil.isEmpty(text)) return;
        Matcher full = FULL_PLACEHOLDER.matcher(text);
        if (full.matches()) {
            String token = full.group(1);
            checkToken(token);
            DocTemplateImageSpec spec = specFor(keyOf(token), images);
            if (spec != null) {
                DocTemplateImageLoader.ImagePayload payload =
                        imageLoader.load(resolve(token, params), spec, params, keyOf(token));
                if (payload != null) {
                    insertPicture(cell, payload, spec);
                    return;
                }
            }
            Object value = resolve(token, params);
            if (value instanceof Number number) {
                prepareWrite(cell);
                cell.setCellValue(number.doubleValue());
                return;
            }
            if (value instanceof Boolean bool) {
                prepareWrite(cell);
                cell.setCellValue(bool);
                return;
            }
        }
        String replaced = replaceText(text, params);
        if (!replaced.equals(text)) {
            prepareWrite(cell);
            cell.setCellValue(replaced);
        }
    }

    /**
     * 写值前先清掉内联字符串（{@code t="inlineStr"}）。
     *
     * <p>openpyxl、部分报表工具导出的 .xlsx 会把字符串写成 {@code <is><t>文本</t></is>}。
     * POI 的 {@code setCellValue(String)} 只改 {@code <v>}，{@code <is>} 原样留在原地，
     * 而 Excel / WPS 读单元格时优先取 {@code <is>}，结果就是「值写进去了，打开还是占位符」。
     * 先 {@code setBlank()} 重建单元格（保留样式），后面的写入才会真的生效。</p>
     */
    private void prepareWrite(Cell cell) {
        if (cell instanceof XSSFCell xssfCell && xssfCell.getCTCell().isSetIs()) {
            xssfCell.setBlank();
        }
    }

    /**
     * 把图片锚定到单元格内部：按配置水平对齐（默认居中）、四周留出 padding，并把行高/列宽撑到能放下图片。
     *
     * <p>锚点两端都落在同一个单元格上，只用 dx/dy 偏移，所以图片始终跟着单元格走；列宽不够时
     * 先按「图片宽 + 左右留白」把列加宽（只加不减），否则居中和右侧对齐没有意义。</p>
     */
    private void insertPicture(Cell cell, DocTemplateImageLoader.ImagePayload payload, DocTemplateImageSpec spec) {
        if (!(cell.getSheet() instanceof XSSFSheet sheet)) {
            throw new IllegalStateException("当前工作簿不是 .xlsx 格式，无法插入图片");
        }
        XSSFWorkbook workbook = sheet.getWorkbook();
        XSSFDrawing drawing = sheet.getDrawingPatriarch();
        if (drawing == null) drawing = sheet.createDrawingPatriarch();

        int column = cell.getColumnIndex();
        int rowIndex = cell.getRowIndex();
        int padding = spec == null ? DocTemplateImageSpec.DEFAULT_PADDING : spec.padding();
        String align = spec == null ? DocTemplateImageSpec.ALIGN_CENTER : spec.align();

        // 列宽不够就加宽（列宽单位是 1/256 字符，1 字符 ≈ 7px）
        int cellWidth = Math.round(sheet.getColumnWidthInPixels(column));
        int neededWidth = payload.widthPx() + padding * 2;
        if (cellWidth < neededWidth) {
            int units = (int) Math.min(255 * 256L, Math.round(neededWidth / 7.0 * 256));
            sheet.setColumnWidth(column, units);
            cellWidth = Math.round(sheet.getColumnWidthInPixels(column));
        }
        int offsetX = switch (align) {
            case DocTemplateImageSpec.ALIGN_LEFT -> padding;
            case DocTemplateImageSpec.ALIGN_RIGHT -> Math.max(padding, cellWidth - payload.widthPx() - padding);
            default -> Math.max(padding, Math.round((cellWidth - payload.widthPx()) / 2f));
        };

        int pictureIndex = workbook.addPicture(payload.bytes(), payload.pictureType());
        XSSFClientAnchor anchor = workbook.getCreationHelper().createClientAnchor();
        anchor.setAnchorType(ClientAnchor.AnchorType.MOVE_AND_RESIZE);
        anchor.setCol1(column);
        anchor.setRow1(rowIndex);
        anchor.setCol2(column);
        anchor.setRow2(rowIndex);
        anchor.setDx1(offsetX * DocTemplateImageLoader.EMU_PER_PIXEL);
        anchor.setDy1(padding * DocTemplateImageLoader.EMU_PER_PIXEL);
        anchor.setDx2((offsetX + payload.widthPx()) * DocTemplateImageLoader.EMU_PER_PIXEL);
        anchor.setDy2((padding + payload.heightPx()) * DocTemplateImageLoader.EMU_PER_PIXEL);
        drawing.createPicture(anchor, pictureIndex);
        // 占位符文本清掉，避免 Excel 里图片下面还压着一行 ${xxx}
        prepareWrite(cell);
        cell.setCellValue("");

        Row row = cell.getRow();
        if (row != null) {
            // 1 像素 = 15 twips（96 dpi），行高上限 409 磅 = 8180 twips
            int needed = Math.min(8180, (payload.heightPx() + padding * 2) * 15);
            if (row.getHeight() < needed) row.setHeight((short) needed);
        }
    }

    // ------------------------------------------------------------------ 列区（XLSX）

    /** 工作表里的一处列区标记。 */
    private record SheetColumnsSlot(int row, int column, ColumnsMarker marker) {
    }

    /**
     * 把工作表里的 {@code {{#columns 块编码 角色}}} 展开成 N 个真实列：整行右移、按权重设列宽、套对齐，
     * 合并区跟着重映射。角色与 DOCX 一致：{@code header} 表头行、{@code data} 数据行、{@code span} 静态行。
     *
     * <p>必须在 {@code expandSheetLoops} 之前跑完：展开完每行退回成「普通行 + 普通占位符」，
     * 行循环只负责复制行。</p>
     */
    private void expandSheetColumns(Sheet sheet, Map<String, List<DocTemplateColumnSpec>> columns) {
        if (columns == null) return;
        int lastRow = sheet.getLastRowNum();
        List<SheetColumnsSlot> slots = new ArrayList<>();
        for (int r = 0; r <= lastRow; r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            for (int c = 0; c < row.getLastCellNum(); c++) {
                Cell cell = row.getCell(c);
                if (cell == null || cell.getCellType() != CellType.STRING) continue;
                String text = cell.getStringCellValue();
                ColumnsMarker marker = parseColumns(text);
                if (marker == null) continue;
                requireColumnsRole(marker, "工作表第 " + (r + 1) + " 行第 " + (c + 1) + " 列");
                if (!marker.span() && !isColumnsOnly(text)) {
                    throw new IllegalArgumentException("工作表第 " + (r + 1) + " 行第 " + (c + 1)
                            + " 列的 {{#columns}} 必须独占一格；想在这一格里同时写文字，"
                            + "请改用 {{#columns " + marker.block() + " span}}（横向盖住列区）");
                }
                slots.add(new SheetColumnsSlot(r, c, marker));
            }
        }
        if (slots.isEmpty()) return;

        String block = slots.get(0).marker().block();
        Set<Integer> rowsWithSlot = new HashSet<>();
        for (SheetColumnsSlot slot : slots) {
            if (!slot.marker().block().equalsIgnoreCase(block)) {
                throw new IllegalArgumentException("同一张工作表里出现了两个列区标记且编码不一致（"
                        + block + " / " + slot.marker().block() + "），一张表只能有一个列区");
            }
            if (!rowsWithSlot.add(slot.row())) {
                throw new IllegalArgumentException("工作表第 " + (slot.row() + 1) + " 行里有两个列区标记");
            }
            if (slot.column() != slots.get(0).column()) {
                throw new IllegalArgumentException("列区标记必须落在同一列：第 " + (slots.get(0).row() + 1)
                        + " 行在第 " + (slots.get(0).column() + 1) + " 列，第 " + (slot.row() + 1)
                        + " 行在第 " + (slot.column() + 1) + " 列");
            }
        }
        List<DocTemplateColumnSpec> specs = columnSpecs(columns, block);
        int regionStart = slots.get(0).column();
        int n = specs.size();

        SheetColumnsSlot dataSlot = null;
        for (SheetColumnsSlot slot : slots) {
            if (slot.marker().role() != ColumnsRole.DATA) continue;
            if (dataSlot != null) {
                throw new IllegalArgumentException("工作表第 " + (dataSlot.row() + 1) + " 行和第 "
                        + (slot.row() + 1) + " 行都写了 {{#columns " + block + " data}}，数据行只能有一行");
            }
            dataSlot = slot;
        }
        if (dataSlot == null) {
            throw new IllegalArgumentException("工作表里的列区没有数据行标记：把要按数组重复的那一行写成 {{#columns "
                    + block + " data}}，这一行会展开成 N 列并按数据重复");
        }
        int dataRow = dataSlot.row();

        List<String> texts = sheetRowTexts(sheet);
        for (int[] region : loopRegions(texts)) {
            for (SheetColumnsSlot slot : slots) {
                if (slot.row() > region[0] && slot.row() < region[1]) {
                    throw new IllegalArgumentException("工作表第 " + (slot.row() + 1)
                            + " 行的 {{#columns}} 落在 {{#each}} 区域里，嵌套循环里的列区暂不支持");
                }
            }
        }
        checkRowLoopAnnotation(texts.get(dataRow), dataSlot.marker(), "工作表第 " + (dataRow + 1) + " 行");

        if (n == 0) {
            removeSheetColumns(sheet, slots, regionStart);
            return;
        }

        // 1. 列区右侧的格整体右移 N-1（从右往左搬，样式一起带走）
        for (int r = 0; r <= lastRow; r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            shiftSheetCells(row, regionStart + 1, n - 1);
        }
        // 2. 合并区跟着重映射
        remapSheetRegions(sheet, regionStart, n, rowsWithSlot);
        // 3. 标记行填内容
        for (SheetColumnsSlot slot : slots) {
            Row row = sheet.getRow(slot.row());
            if (row == null) row = sheet.createRow(slot.row());
            Cell markerCell = row.getCell(regionStart);
            if (markerCell == null) markerCell = row.createCell(regionStart);
            String markerText = markerCell.getCellType() == CellType.STRING ? markerCell.getStringCellValue() : "";
            CellStyle style = markerCell.getCellStyle();
            if (slot.marker().span()) {
                prepareWrite(markerCell);
                markerCell.setCellValue(stripColumnsText(markerText));
                // 合并区里的每一格都要套上标记格的样式：Excel 的合并只是把区域盖住，边框仍然按格画，
                // 只给第一格设边框的话，右边几格的上/下边框会缺一截
                for (int i = 1; i < n; i++) {
                    Cell cell = row.getCell(regionStart + i);
                    if (cell == null) cell = row.createCell(regionStart + i);
                    prepareWrite(cell);
                    cell.setCellStyle(style);
                }
                if (n > 1) {
                    sheet.addMergedRegion(new CellRangeAddress(slot.row(), slot.row(),
                            regionStart, regionStart + n - 1));
                }
                continue;
            }
            boolean data = slot.row() == dataRow;
            String loop = data ? loopMarkerOf(slot.marker(), markerText, spec -> {
                int index = xlsxColumn(spec);
                if (index == regionStart) {
                    throw new IllegalArgumentException("merge=" + spec + " 指向了列区所在的列："
                            + "展开后它是 " + n + " 个动态列，纵向合并语义不明；请把 merge= 指向固定列");
                }
                return String.valueOf((index > regionStart ? index + n - 1 : index) + 1);
            }) : null;
            for (int i = 0; i < n; i++) {
                DocTemplateColumnSpec spec = specs.get(i);
                Cell cell = row.getCell(regionStart + i);
                if (cell == null) cell = row.createCell(regionStart + i);
                prepareWrite(cell);
                // 样式（字体、边框、底纹、对齐）整套沿用模板里写标记的那一格，列源只给字段名和标题
                cell.setCellStyle(style);
                // 循环标记成对生成：结束标记紧跟在开始标记后面，占的是同一格，不影响其它格的排版
                cell.setCellValue(data
                        ? (i == 0 ? loop + "{{/each}}" : "") + "${" + spec.field() + "}"
                        : spec.label());
            }
        }
        // 数据行行首行尾那对 {{#each}}/{{/each}} 只是把块名标出来，循环由 data 角色驱动
        clearSheetRowLoop(sheet.getRow(dataRow), regionStart, regionStart + n);
        // 4. 列宽：以列区起始列的现有宽度为基准，列区总宽 = 基准 × N，再按权重分配
        int base = sheet.getColumnWidth(regionStart);
        if (base <= 0) base = Math.max(1, sheet.getDefaultColumnWidth()) * 256;
        int regionTotal = base * n;
        double sum = DocTemplateColumnSpec.totalWeight(specs);
        if (sum <= 0) sum = n;
        int used = 0;
        for (int i = 0; i < n; i++) {
            int width = i == n - 1
                    ? Math.max(256, regionTotal - used)
                    : Math.max(256, (int) Math.round(regionTotal * specs.get(i).width() / sum));
            sheet.setColumnWidth(regionStart + i, Math.min(255 * 256, width));
            used += width;
        }
        log.debug("工作表列区展开：块 {}，起始列 {}，{} 列，数据行 {}",
                block, CellReference.convertNumToColString(regionStart), n, dataRow + 1);
    }

    /** 列定义为空数组时：标记行整行删掉，其它行把列区那一格清空。 */
    private void removeSheetColumns(Sheet sheet, List<SheetColumnsSlot> slots, int regionStart) {
        Set<Integer> markerRows = new HashSet<>();
        for (SheetColumnsSlot slot : slots) markerRows.add(slot.row());
        for (int r = sheet.getLastRowNum(); r >= 0; r--) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            if (markerRows.contains(r)) {
                sheet.removeRow(row);
                int last = sheet.getLastRowNum();
                if (r < last) sheet.shiftRows(r + 1, last, -1);
                continue;
            }
            Cell cell = row.getCell(regionStart);
            if (cell == null) continue;
            prepareWrite(cell);
            cell.setBlank();
        }
    }

    /** 把 {@code [from, last]} 的单元格整体右移 {@code delta} 格，值、类型、样式一起带走。 */
    private void shiftSheetCells(Row row, int from, int delta) {
        int last = row.getLastCellNum() - 1;
        if (delta <= 0 || last < from) return;
        for (int c = last; c >= from; c--) {
            Cell source = row.getCell(c);
            if (source == null) continue;
            Cell target = row.getCell(c + delta);
            if (target == null) target = row.createCell(c + delta);
            prepareWrite(target);
            target.setCellStyle(source.getCellStyle());
            copySheetValue(source, target);
            row.removeCell(source);
        }
    }

    private void copySheetValue(Cell source, Cell target) {
        switch (source.getCellType()) {
            case STRING -> target.setCellValue(source.getStringCellValue());
            case NUMERIC -> target.setCellValue(source.getNumericCellValue());
            case BOOLEAN -> target.setCellValue(source.getBooleanCellValue());
            case FORMULA -> target.setCellFormula(source.getCellFormula());
            default -> target.setBlank();
        }
    }

    /** 列区右侧的合并区整体右移 N−1；跨列区的合并区右边界 +N−1；标记格自己的合并区丢掉重建。 */
    private void remapSheetRegions(Sheet sheet, int regionStart, int n, Set<Integer> markerRows) {
        List<CellRangeAddress> regions = new ArrayList<>(sheet.getMergedRegions());
        for (int i = regions.size() - 1; i >= 0; i--) {
            CellRangeAddress region = regions.get(i);
            int first = region.getFirstColumn();
            int last = region.getLastColumn();
            if (last < regionStart) continue;
            boolean singleCell = first == last && region.getFirstRow() == region.getLastRow();
            sheet.removeMergedRegion(i);
            if (first > regionStart) {
                sheet.addMergedRegion(new CellRangeAddress(region.getFirstRow(), region.getLastRow(),
                        first + n - 1, last + n - 1));
                continue;
            }
            if (singleCell && markerRows.contains(region.getFirstRow())) {
                // 标记格自己的合并区：拆成 N 格后由新格子替代
                continue;
            }
            sheet.addMergedRegion(new CellRangeAddress(region.getFirstRow(), region.getLastRow(),
                    first, last + n - 1));
        }
    }

    private String stripColumnsText(String text) {
        return text == null ? "" : COLUMNS_START.matcher(text).replaceAll("").trim();
    }

    /**
     * 清掉数据行上的行循环标记（列区里的格子不碰，那里已经写好了生成出来的循环标记）。
     *
     * <p>这一行的循环由 {@code data} 角色驱动，行首行尾那对 {@code {{#each}}/{{/each}}}
     * 只是给人看的，留着会让同一行出现两个循环。</p>
     */
    private void clearSheetRowLoop(Row row, int regionStart, int regionEnd) {
        if (row == null) return;
        for (int c = 0; c < row.getLastCellNum(); c++) {
            if (c >= regionStart && c < regionEnd) continue;
            Cell cell = row.getCell(c);
            if (cell == null || cell.getCellType() != CellType.STRING) continue;
            String text = cell.getStringCellValue();
            String stripped = stripLoopMarkers(text);
            if (stripped.equals(text)) continue;
            prepareWrite(cell);
            cell.setCellValue(stripped);
        }
    }

    /**
     * Excel 行循环，与 DOCX 表格同一套规则：行内标记按单行重复；{@code {{#each}}} 与
     * {@code {{/each}}} 各独占一行时是区域循环，区域内可再嵌套（递归渲染）。
     */
    private void expandSheetLoops(Sheet sheet, Map<String, Object> params,
                                  List<DocTemplateImageSpec> images, DocTemplateMergeAlign mergeAlign) {
        int index = 0;
        while (index <= sheet.getLastRowNum()) {
            List<String> texts = sheetRowTexts(sheet, index);
            if (texts.isEmpty()) return;
            Loop loop = parseLoop(texts.get(0));
            if (loop == null) {
                // 区域结束行在上面的 span 里被一起吃掉，走不到这里；能走到就说明这个 {{/each}} 是多写的
                if (isLoopEnd(texts.get(0))) {
                    throw new IllegalArgumentException("第 " + (index + 1) + " 行的 {{/each}} 没有配对的 "
                            + "{{#each}}；循环的开始和结束标记必须成对写");
                }
                index++;
                continue;
            }
            int span = 1;
            if (isLoopStart(texts.get(0))) {
                int end = matchLoopEnd(texts, 0, texts.size());
                if (end > 0) span = end + 1;
            }
            // 传整份 texts 只取前 span 行：这样报错信息里的行号就是工作表里的真实行号
            List<PlannedRow> plan = planRows(texts, 0, span, params, "行");
            applySheetBlock(sheet, index, span, plan, images, mergeAlign);
            index += plan.size();
        }
    }

    /**
     * 按计划重写工作表的 {@code [start, start+span)} 行：先删掉计划用不到的行、把下面的行整体
     * 挪到计划长度对应的位置，再逐行套模板并渲染。
     *
     * <p>合并区要单独处理：块内「只占一行」的横向合并记下来按每份产出行重建，跨块的多行合并只能丢弃
     * 并告警（那本来就该改用 {@code merge=} 声明）。块下方的合并区由 {@code shiftRows} 自己带走。</p>
     */
    private void applySheetBlock(Sheet sheet, int start, int span, List<PlannedRow> plan,
                                 List<DocTemplateImageSpec> images, DocTemplateMergeAlign mergeAlign) {
        int end = start + span - 1;
        List<RowTemplate> templates = new ArrayList<>(span);
        for (int i = 0; i < span; i++) templates.add(snapshot(sheet.getRow(start + i)));
        int count = plan.size();
        int delta = count - span;
        List<TemplateRegion> regions = detachRegions(sheet, start, end);
        if (delta < 0) {
            // 多出来的尾部行先删掉，避免随后的上移把内容写到还被占用的位置上
            for (int i = end; i >= start + count; i--) {
                Row row = sheet.getRow(i);
                if (row != null) sheet.removeRow(row);
            }
        }
        int last = sheet.getLastRowNum();
        if (delta != 0 && end < last) sheet.shiftRows(end + 1, last, delta, true, false);
        for (int k = 0; k < count; k++) {
            PlannedRow planned = plan.get(k);
            RowTemplate template = templates.get(planned.index());
            Row target = sheet.getRow(start + k);
            if (target == null) target = sheet.createRow(start + k);
            applyRow(target, template.cells());
            // 模板行显式设了行高就跟着复制，否则展开出来的行会掉回默认行高
            if (template.customHeight()) target.setHeight(template.height());
            renderRowWithScope(target, planned.scope(), images);
            restoreRegions(sheet, start + k, planned.index(), regions);
        }
        applySheetMerges(sheet, start, plan, mergeAlign);
    }

    /** 模板块里自带的一段合并区（相对块首的行偏移 + 列范围），用于按每份产出行重建。 */
    private record TemplateRegion(int row, int firstColumn, int lastColumn) {
    }

    /**
     * 把与块相交的合并区从工作表上摘下来。
     *
     * <p>块<b>下方</b>的合并区不用管：POI 5.x 的 {@code shiftRows} 会带着整段落在平移范围内的
     * 合并区一起走（踩过坑，别自己再平移一遍，会移两次）。</p>
     *
     * @return 块内「只占一行」的合并区，交给 {@link #restoreRegions} 逐份重建
     */
    private List<TemplateRegion> detachRegions(Sheet sheet, int start, int end) {
        List<TemplateRegion> template = new ArrayList<>();
        List<CellRangeAddress> regions = new ArrayList<>(sheet.getMergedRegions());
        for (int i = regions.size() - 1; i >= 0; i--) {
            CellRangeAddress region = regions.get(i);
            if (region.getLastRow() < start || region.getFirstRow() > end) continue;
            sheet.removeMergedRegion(i);
            boolean singleRowInside = region.getFirstRow() == region.getLastRow()
                    && region.getFirstRow() >= start && region.getLastRow() <= end;
            if (singleRowInside) {
                template.add(new TemplateRegion(region.getFirstRow() - start,
                        region.getFirstColumn(), region.getLastColumn()));
                continue;
            }
            log.warn("循环块（第 {}~{} 行）里的跨行合并区 {} 已移除：循环行不能自带跨行合并，"
                            + "需要竖向合并请改用 {{{{#each 数组 merge=列}}}} 声明",
                    start + 1, end + 1, region.formatAsString());
        }
        return template;
    }

    /** 按「这份行用了哪个模板行」重建模板自带的横向合并区。 */
    private void restoreRegions(Sheet sheet, int rowIndex, int templateRow, List<TemplateRegion> regions) {
        for (TemplateRegion region : regions) {
            if (region.row() != templateRow) continue;
            sheet.addMergedRegion(new CellRangeAddress(rowIndex, rowIndex, region.firstColumn(), region.lastColumn()));
        }
    }

    /**
     * 按 {@code merge=} 声明把「相邻且分组键相同」的行竖向合并。
     *
     * <p>Excel 的合并区只显示左上角那一格的内容，所以对齐样式也只需要改它；
     * 样式必须 clone 一份再改，直接改 {@code cell.getCellStyle()} 会连累用同一样式的其他格子。</p>
     */
    private void applySheetMerges(Sheet sheet, int start, List<PlannedRow> plan,
                                  DocTemplateMergeAlign mergeAlign) {
        if (plan.stream().allMatch(row -> row.merges().isEmpty())) return;
        List<MergeSpan> spans = mergeSpans(plan, this::xlsxColumn,
                (position, column) -> sheetCellText(sheet.getRow(start + position), column));
        for (MergeSpan span : spans) {
            try {
                sheet.addMergedRegion(new CellRangeAddress(start + span.from(), start + span.to(),
                        span.column(), span.column()));
                alignMergedCell(sheet, start + span.from(), span.column(), mergeAlign);
                log.debug("竖向合并 XLSX 第 {} 列：第 {} 行到第 {} 行，对齐 {}",
                        span.column() + 1, start + span.from() + 1, start + span.to() + 1, mergeAlign);
            } catch (RuntimeException error) {
                log.warn("第 {}~{} 行第 {} 列竖向合并失败（多半和已有合并区重叠）：{}",
                        start + span.from() + 1, start + span.to() + 1, span.column() + 1, error.getMessage());
            }
        }
    }

    /**
     * 合并区左上角那一格的垂直对齐；水平对齐保持模板原样，默认（靠上）时整份样式都不动。
     *
     * <p>样式必须 clone 一份再改，直接改 {@code cell.getCellStyle()} 会连累用同一样式的其他格子。</p>
     */
    private void alignMergedCell(Sheet sheet, int rowIndex, int column, DocTemplateMergeAlign align) {
        if (align == null || align.isDefault()) return;
        Row row = sheet.getRow(rowIndex);
        if (row == null) row = sheet.createRow(rowIndex);
        Cell cell = row.getCell(column);
        if (cell == null) cell = row.createCell(column);
        CellStyle style = sheet.getWorkbook().createCellStyle();
        style.cloneStyleFrom(cell.getCellStyle());
        style.setVerticalAlignment(switch (align.vertical()) {
            case DocTemplateMergeAlign.BOTTOM -> VerticalAlignment.BOTTOM;
            case DocTemplateMergeAlign.CENTER -> VerticalAlignment.CENTER;
            default -> VerticalAlignment.TOP;
        });
        cell.setCellStyle(style);
    }

    /** 合并比较用的单元格文本：文本取原值，数字去掉多余的 0，其他按空串。 */
    private String sheetCellText(Row row, int column) {
        if (row == null || column < 0) return "";
        Cell cell = row.getCell(column);
        if (cell == null) return "";
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> stringify(cell.getNumericCellValue());
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            default -> "";
        };
    }

    private void renderRowWithScope(Row row, Map<String, Object> scope, List<DocTemplateImageSpec> images) {
        clearLoopMarkers(row);
        for (int c = 0; c < row.getLastCellNum(); c++) {
            Cell cell = row.getCell(c);
            if (cell != null) renderCell(cell, scope, images);
        }
    }

    /** 一行里的所有文本单元格内容（拼接），用来判断循环标记写在哪一行。 */
    private String rowText(Row row) {
        if (row == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int c = 0; c < row.getLastCellNum(); c++) {
            Cell cell = row.getCell(c);
            if (cell != null && cell.getCellType() == CellType.STRING) sb.append(cell.getStringCellValue());
        }
        return sb.toString();
    }

    private List<String> sheetRowTexts(Sheet sheet) {
        return sheetRowTexts(sheet, 0);
    }

    private List<String> sheetRowTexts(Sheet sheet, int from) {
        List<String> texts = new ArrayList<>();
        for (int r = from; r <= sheet.getLastRowNum(); r++) texts.add(rowText(sheet.getRow(r)));
        return texts;
    }

    private void clearLoopMarkers(Row row) {
        for (int c = 0; c < row.getLastCellNum(); c++) {
            Cell cell = row.getCell(c);
            if (cell == null || cell.getCellType() != CellType.STRING) continue;
            String text = cell.getStringCellValue();
            String cleaned = stripLoopMarkers(text);
            if (!cleaned.equals(text)) {
                prepareWrite(cell);
                cell.setCellValue(cleaned);
            }
        }
    }

    /** 一行模板：单元格快照 + 行高（模板行显式设过行高才复制，否则沿用默认行高）。 */
    private record RowTemplate(List<CellSnapshot> cells, boolean customHeight, short height) {
        static final RowTemplate EMPTY = new RowTemplate(List.of(), false, (short) 0);
    }

    private RowTemplate snapshot(Row row) {
        if (row == null) return RowTemplate.EMPTY;
        return new RowTemplate(snapshotRow(row), hasCustomHeight(row), row.getHeight());
    }

    /**
     * 复制模板行之前先拍快照：原行会被第一项渲染掉，不能再作为后续行的模板。
     */
    private List<CellSnapshot> snapshotRow(Row row) {
        List<CellSnapshot> cells = new ArrayList<>();
        for (int c = 0; c < row.getLastCellNum(); c++) {
            Cell cell = row.getCell(c);
            if (cell == null) {
                cells.add(null);
                continue;
            }
            Object value = switch (cell.getCellType()) {
                case STRING -> cell.getStringCellValue();
                case NUMERIC -> cell.getNumericCellValue();
                case BOOLEAN -> cell.getBooleanCellValue();
                case FORMULA -> cell.getCellFormula();
                default -> null;
            };
            cells.add(new CellSnapshot(cell.getCellType(), value, cell.getCellStyle()));
        }
        return cells;
    }

    /** 模板行是否显式设过行高（{@code customHeight}），没设过就不该硬塞行高。 */
    private boolean hasCustomHeight(Row row) {
        if (row instanceof XSSFRow xssfRow) {
            return xssfRow.getCTRow().getCustomHeight();
        }
        return row.getHeight() != row.getSheet().getDefaultRowHeight();
    }

    /**
     * 把一行模板套到目标行上。
     *
     * <p>目标行可能比模板行宽（区域里「订单行」被「明细行」覆盖时，明细行只有后几列），
     * 所以模板没内容的列必须显式清空，否则会残留上一行的订单号。</p>
     */
    private void applyRow(Row target, List<CellSnapshot> cells) {
        int last = Math.max(cells.size(), target.getLastCellNum());
        for (int c = 0; c < last; c++) {
            CellSnapshot snapshot = c < cells.size() ? cells.get(c) : null;
            Cell cell = target.getCell(c);
            if (snapshot == null) {
                if (cell != null) {
                    prepareWrite(cell);
                    cell.setCellValue((String) null);
                }
                continue;
            }
            if (cell == null) cell = target.createCell(c);
            prepareWrite(cell);
            cell.setCellStyle(snapshot.style());
            switch (snapshot.type()) {
                case STRING -> cell.setCellValue((String) snapshot.value());
                case NUMERIC -> cell.setCellValue((Double) snapshot.value());
                case BOOLEAN -> cell.setCellValue((Boolean) snapshot.value());
                case FORMULA -> cell.setCellFormula((String) snapshot.value());
                default -> {
                }
            }
        }
    }

    public Set<String> xlsxPlaceholders(byte[] template) throws Exception {
        Set<String> keys = new TreeSet<>();
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(template))) {
            for (int s = 0; s < workbook.getNumberOfSheets(); s++) {
                for (Row row : workbook.getSheetAt(s)) {
                    for (Cell cell : row) {
                        if (cell.getCellType() == CellType.STRING) collect(cell.getStringCellValue(), keys);
                    }
                }
            }
        }
        return keys;
    }

    // ------------------------------------------------------------------ shared

    private String replaceText(String text, Map<String, Object> params) {
        if (text == null || text.indexOf('{') < 0) return text;
        Matcher matcher = PLACEHOLDER.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String token = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            checkToken(token);
            if (isStructuralMarker(token)) {
                // 整格/整段只有这一个结构标记，说明它本该被行展开、列区展开或段落展开消费掉，
                // 却没被认出来（缺数组名、关键字拼错、角色写错…）。原样留着只会印一份带花括号的
                // 文档，且没人知道哪里错了，所以这里直接报错。混在文字里的（正文当例子讲的
                // {{#each items}}）仍按原文保留。
                if (FULL_PLACEHOLDER.matcher(text).matches()) {
                    throw new IllegalArgumentException(residueMessage(matcher.group()));
                }
                // {{#each}} / {{/each}} / {{#columns}} 是结构标记，不是占位符。走到这里说明它是
                // 模板正文里当说明文字写的（该被消费的早被行展开清掉了），原样留着，别抹成空串
                matcher.appendReplacement(sb, Matcher.quoteReplacement(matcher.group()));
                continue;
            }
            Object value = resolve(token, params);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(stringify(value)));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /** 「只有标记却没被识别」的报错文案：按标记种类给对应的写法提示。 */
    private static String residueMessage(String marker) {
        String lower = marker.toLowerCase(Locale.ROOT);
        String hint;
        if (lower.contains("#each")) {
            hint = "#each 后面要写数组名（如 {{#each items}}），并且要和 {{/each}} 成对；"
                    + "只写 {{#each}} 不会被当成循环";
        } else if (lower.contains("#columns")) {
            hint = "要写成 {{#columns 块编码 角色}}，角色是 header（表头）/ data（数据行）/ span（横向盖住列区），"
                    + "且必须独占一格";
        } else if (lower.contains("/each")) {
            hint = "多写了一个 {{/each}}，或者它的 {{#each}} 没写对（要写数组名）";
        } else {
            hint = "明细行循环写 {{#each 数组名}}…{{/each}}，表格动态列写 {{#columns 块编码 header/data/span}}";
        }
        return "这一格（或这一段）只有标记 " + marker + "，但它没有被识别成标记：" + hint;
    }

    /** 结构标记（{@code #each} / {@code /each} / {@code #columns}）不是占位符，取不到值也不该被替换。 */
    private static boolean isStructuralMarker(String token) {
        if (token == null) return false;
        String raw = token.trim();
        return raw.startsWith("#") || raw.startsWith("/");
    }

    /**
     * {@code {{#user}}} 这种写法会被当成名为 {@code #user} 的占位符，取不到值就静默变成空串，
     * 非常难查。这里直接拦掉，提示正确写法。
     */
    private void checkToken(String token) {
        if (token == null) return;
        String raw = token.trim();
        if (!raw.startsWith("#")) return;
        String lower = raw.toLowerCase(Locale.ROOT);
        // #each / #columns 是行级标记，不是占位符：它们由行展开逻辑消费掉，不该在这里报错
        if (lower.startsWith("#each") || lower.startsWith("#columns")) return;
        throw new IllegalArgumentException("不认识的标记 " + raw + "，明细行循环要写成 {{#each 数组名}}"
                + "（例如 {{#each user}}），不要漏掉 each；表格动态列要写成 {{#columns 块编码 header}}"
                + " / {{#columns 块编码 data}} / {{#columns 块编码 span}}");
    }

    private void collect(String text, Set<String> keys) {
        if (text == null || text.indexOf('{') < 0) return;
        Matcher matcher = PLACEHOLDER.matcher(text);
        while (matcher.find()) {
            String token = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            String key = keyOf(token);
            // 循环标记（#each / /each）不是占位符，不要暴露到模拟生成的参数表单里
            if (key.isEmpty() || key.startsWith("#") || key.startsWith("/")) continue;
            keys.add(key);
        }
    }

    /**
     * Splits {@code key:default} / {@code key:default|conv} into its key part.
     *
     * <p>转换链（{@code |number:2}）只影响输出，不影响占位符名，所以取键前先按 {@code |} 截断。</p>
     */
    private String keyOf(String token) {
        if (token == null) return "";
        int pipe = token.indexOf('|');
        String head = pipe >= 0 ? token.substring(0, pipe) : token;
        int idx = head.indexOf(':');
        String key = idx >= 0 ? head.substring(0, idx) : head;
        return key.trim();
    }

    /**
     * 取占位符的值：{@code 键[:默认值][|转换器...]}。
     *
     * <p>{@code |} 后面的每个转换器依次作用在上一步的结果上，例如
     * {@code ${amount|number:2|upper}}。默认值只在键取不到时兜底，兜底值同样会走转换链
     * （{@code ${amount:0|number:2}} 输出 {@code 0.00}）。</p>
     */
    private Object resolve(String token, Map<String, Object> params) {
        if (token == null) return null;
        int pipe = token.indexOf('|');
        String head = pipe >= 0 ? token.substring(0, pipe) : token;
        String chain = pipe >= 0 ? token.substring(pipe + 1) : null;
        int idx = head.indexOf(':');
        String key = (idx >= 0 ? head.substring(0, idx) : head).trim();
        String fallback = idx >= 0 ? head.substring(idx + 1) : null;
        Object value = lookup(key, params);
        if (value == null) value = fallback;
        if (value == null) return null;
        for (ConverterCall call : DocTemplateConverterRegistry.parseChain(chain)) {
            value = converters.convert(value, call.type(), call.args());
            if (value == null) return null;
        }
        return value;
    }

    private Object lookup(String key, Map<String, Object> params) {
        Found found = lookupLenient(key, params);
        return found.present() ? found.value() : null;
    }

    /**
     * 按 {@code a.b.c} 取值，键名<b>严格区分大小写</b>：模板里的写法要和数据里的字段名完全一致。
     *
     * <p>以前会「精确找不到就忽略大小写再找一遍」，看着容错，实际是把拼写错误藏起来了 ——
     * 数据库列名是大写、模板写小写也能跑通，换库或换 Provider 才炸。现在取不到就是取不到：
     * 普通占位符按默认值/空串处理，循环变量和 {@code where} 关联键会直接报错并列出可用的键。</p>
     *
     * <p>返回 {@link Found#present()} 区分「键不存在」与「键存在但值为 null」。</p>
     */
    private Found lookupLenient(String key, Map<String, Object> params) {
        if (key == null || params == null) return Found.MISSING;
        String trimmed = key.trim();
        if (trimmed.isEmpty()) return Found.MISSING;
        Object current = params;
        for (String part : trimmed.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) return Found.MISSING;
            if (!map.containsKey(part)) return Found.MISSING;
            current = map.get(part);
        }
        return new Found(true, current);
    }

    private String stringify(Object value) {
        if (value == null) return "";
        if (value instanceof BigDecimal decimal) return decimal.stripTrailingZeros().toPlainString();
        if (value instanceof Double || value instanceof Float) {
            double d = ((Number) value).doubleValue();
            if (d == Math.rint(d) && !Double.isInfinite(d)) return String.valueOf((long) d);
        }
        if (value instanceof Map<?, ?> || value instanceof Iterable<?>) {
            try {
                return DocTemplateJson.toJson(value);
            } catch (Exception ignored) {
                return String.valueOf(value);
            }
        }
        return String.valueOf(value);
    }

    private record CellSnapshot(CellType type, Object value, org.apache.poi.ss.usermodel.CellStyle style) {
    }
}
