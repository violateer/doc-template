package io.github.violateer.doctemplate.starter.service;

import cn.hutool.core.util.StrUtil;
import io.github.violateer.doctemplate.starter.convert.DocTemplateConverterRegistry;
import io.github.violateer.doctemplate.starter.convert.DocTemplateConverterRegistry.ConverterCall;
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
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTP;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTRPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTRow;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTcPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTVMerge;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTVerticalJc;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STMerge;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STVerticalJc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
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
     */
    private static final Pattern LOOP_START = Pattern.compile(
            "(?:\\$\\{|\\{\\{)\\s*#each\\s+([A-Za-z0-9_.]+)([^{}]*)\\s*}?}?");
    /** {@code where 字段 = 取值}：取值可以是 {@code 'x'}、{@code "x"} 或单个词。 */
    private static final Pattern LOOP_WHERE = Pattern.compile(
            "\\bwhere\\s+([A-Za-z0-9_.]+)\\s*=\\s*('(?:[^']*)'|\"(?:[^\"]*)\"|[^\\s]+)");
    /** {@code merge=1,2} / {@code merge=A,C}：要竖向合并的列，逗号分隔，不能带空格。 */
    private static final Pattern LOOP_MERGE = Pattern.compile("\\bmerge\\s*=\\s*([^\\s}]+)");
    /** {@code by=order_no}：合并分组键，不写就按渲染后的文本比较。 */
    private static final Pattern LOOP_BY = Pattern.compile("\\bby\\s*=\\s*([A-Za-z0-9_.]+)");
    /**
     * {@code {{/each}}} 或 {@code ${/each}}}
     */
    private static final Pattern LOOP_END = Pattern.compile("(?:\\$\\{|\\{\\{)\\s*/each\\s*}?}?");

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
        if (text == null || text.indexOf("each") < 0) return null;
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

    /**
     * Convenience for building a parameter map from a dotted key path.
     */
    public static List<String> sorted(Set<String> keys) {
        return new ArrayList<>(new LinkedHashSet<>(keys));
    }

    public byte[] render(byte[] template, String fileType, Map<String, Object> params) throws Exception {
        return render(template, fileType, params, List.of(), DocTemplateMergeAlign.DEFAULT);
    }

    /**
     * @param images 声明为图片的字段（来自数据块配置），为空时全部按文本渲染
     */
    public byte[] render(byte[] template, String fileType, Map<String, Object> params,
                         List<DocTemplateImageSpec> images) throws Exception {
        return render(template, fileType, params, images, DocTemplateMergeAlign.DEFAULT);
    }

    /**
     * @param mergeAlign 竖向合并出来的格子内容怎么对齐（来自数据块配置的 {@code mergeCellAlign}）
     */
    public byte[] render(byte[] template, String fileType, Map<String, Object> params,
                         List<DocTemplateImageSpec> images, DocTemplateMergeAlign mergeAlign) throws Exception {
        Map<String, Object> safe = params == null ? Map.of() : params;
        List<DocTemplateImageSpec> specs = images == null ? List.of() : images;
        DocTemplateMergeAlign align = mergeAlign == null ? DocTemplateMergeAlign.DEFAULT : mergeAlign;
        if ("XLSX".equalsIgnoreCase(fileType)) return renderXlsx(template, safe, specs, align);
        return renderDocx(template, safe, specs, align);
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
        return renderDocx(template, params, List.of(), DocTemplateMergeAlign.DEFAULT);
    }

    public byte[] renderDocx(byte[] template, Map<String, Object> params,
                             List<DocTemplateImageSpec> images) throws Exception {
        return renderDocx(template, params, images, DocTemplateMergeAlign.DEFAULT);
    }

    public byte[] renderDocx(byte[] template, Map<String, Object> params,
                             List<DocTemplateImageSpec> images, DocTemplateMergeAlign mergeAlign) throws Exception {
        log.debug("渲染 DOCX，参数：{}，图片字段：{}，合并对齐：{}",
                describeParams(params), describeImages(images), mergeAlign);
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(template));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            expandDocxLoops(document, params, images, mergeAlign);
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
     * Replaces placeholders inside a paragraph. Word often splits one placeholder across several
     * runs, so the paragraph text is rebuilt first and written back into the leading run to keep
     * that run's character formatting.
     *
     * <p>段落里出现声明为图片的占位符时，整段按「文本片段 + 图片」重建，字体格式取自原来的首个 run。</p>
     */
    private void replaceInParagraph(XWPFParagraph paragraph, Map<String, Object> params,
                                    List<DocTemplateImageSpec> images) {
        List<XWPFRun> runs = paragraph.getRuns();
        if (runs == null || runs.isEmpty()) return;
        StringBuilder sb = new StringBuilder();
        for (XWPFRun run : runs) sb.append(run.text());
        String original = sb.toString();
        if (original.isEmpty()) return;
        if (images != null && !images.isEmpty() && replaceImagesInParagraph(paragraph, original, params, images)) return;
        String replaced = replaceText(original, params);
        if (replaced.equals(original)) return;
        List<XWPFRun> current = paragraph.getRuns();
        if (current.isEmpty()) return;
        XWPFRun first = current.get(0);
        first.setText(replaced, 0);
        for (int i = 1; i < current.size(); i++) current.get(i).setText("", 0);
    }

    // ------------------------------------------------------------------ images

    /**
     * 段落里的图片占位符：把文本拆成「文本 / 图片」片段后整段重建。
     *
     * @return 是否命中过图片字段；没命中时返回 {@code false}，交给普通文本替换
     */
    private boolean replaceImagesInParagraph(XWPFParagraph paragraph, String text, Map<String, Object> params,
                                             List<DocTemplateImageSpec> images) {
        List<Object> segments = new ArrayList<>();
        Matcher matcher = PLACEHOLDER.matcher(text);
        int cursor = 0;
        DocTemplateImageSpec layout = null;
        while (matcher.find()) {
            String token = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            checkToken(token);
            DocTemplateImageSpec spec = specFor(keyOf(token), images);
            if (spec == null) continue;
            DocTemplateImageLoader.ImagePayload payload =
                    imageLoader.load(resolve(token, params), spec, params, keyOf(token));
            // 值为空：保留占位符文本，交给后面的文本替换（走默认值或空串）
            if (payload == null) continue;
            if (matcher.start() > cursor) segments.add(replaceText(text.substring(cursor, matcher.start()), params));
            segments.add(new ImageSegment(payload, spec));
            if (layout == null) layout = spec;
            cursor = matcher.end();
        }
        if (layout == null) return false;
        if (cursor < text.length()) segments.add(replaceText(text.substring(cursor), params));
        rebuildParagraph(paragraph, segments);
        // 只有「图片独占段落」时才动对齐和留白：混排时改了会把同一段的文字一起挪位置，
        // 那种情况交给模板自己排版（在 Word 里设对齐/缩进即可）。
        if (imageOnly(segments)) applyImageLayout(paragraph, layout);
        return true;
    }

    private boolean imageOnly(List<Object> segments) {
        for (Object segment : segments) {
            if (segment instanceof ImageSegment) continue;
            if (segment instanceof String text && text.isBlank()) continue;
            return false;
        }
        return true;
    }

    /** 清空原 runs，按片段顺序重建；文本片段沿用原首个 run 的字符格式。 */
    private void rebuildParagraph(XWPFParagraph paragraph, List<Object> segments) {
        List<XWPFRun> runs = paragraph.getRuns();
        CTRPr formatting = null;
        if (!runs.isEmpty() && runs.get(0).getCTR().getRPr() != null) {
            formatting = (CTRPr) runs.get(0).getCTR().getRPr().copy();
        }
        for (int i = runs.size() - 1; i >= 0; i--) paragraph.removeRun(i);
        for (Object segment : segments) {
            XWPFRun run = paragraph.createRun();
            if (formatting != null) run.getCTR().setRPr((CTRPr) formatting.copy());
            if (segment instanceof ImageSegment image) {
                DocTemplateImageLoader.ImagePayload payload = image.payload();
                try {
                    run.addPicture(new ByteArrayInputStream(payload.bytes()), payload.pictureType(), payload.fileName(),
                            payload.widthPx() * DocTemplateImageLoader.EMU_PER_PIXEL,
                            payload.heightPx() * DocTemplateImageLoader.EMU_PER_PIXEL);
                } catch (Exception error) {
                    throw new IllegalStateException("图片写入 DOCX 失败：" + error.getMessage(), error);
                }
            } else {
                String value = String.valueOf(segment);
                if (!value.isEmpty()) run.setText(value, 0);
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
                                 List<DocTemplateImageSpec> images, DocTemplateMergeAlign mergeAlign) {
        for (XWPFTable table : document.getTables()) expandTableLoops(table, params, images, mergeAlign);
        for (XWPFHeader header : document.getHeaderList()) {
            for (XWPFTable table : header.getTables()) expandTableLoops(table, params, images, mergeAlign);
        }
        for (XWPFFooter footer : document.getFooterList()) {
            for (XWPFTable table : footer.getTables()) expandTableLoops(table, params, images, mergeAlign);
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
                                  List<DocTemplateImageSpec> images, DocTemplateMergeAlign mergeAlign) {
        List<XWPFTableRow> rows = table.getRows();
        if (rows.isEmpty()) return;
        List<String> texts = new ArrayList<>(rows.size());
        for (XWPFTableRow row : rows) texts.add(rowText(row));
        List<PlannedRow> plan = planRows(texts, 0, texts.size(), params);
        if (planIsIdentity(plan, texts)) return;
        // 快照模板行：原行会被第一项渲染掉，后续项必须用「未渲染」的副本
        List<CTRow> templates = new ArrayList<>(rows.size());
        for (XWPFTableRow row : rows) templates.add((CTRow) row.getCtRow().copy());
        for (int i = rows.size() - 1; i >= 0; i--) table.removeRow(i);
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
            renderRowWithScope(row, planned, images, mergeAlign);
        }
        // 行已经按计划建好，位置即计划下标，合并按这个下标算
        applyTableMerges(built, plan, mergeAlign);
    }

    private void renderRowWithScope(XWPFTableRow row, PlannedRow planned,
                                    List<DocTemplateImageSpec> images, DocTemplateMergeAlign mergeAlign) {
        clearLoopMarkers(row);
        for (XWPFTableCell cell : row.getTableCells()) {
            // 循环复制出来的行不能沿用模板里的 vMerge：restart / continue 是按行的物理顺序解释的，
            // 复制出来的一份份全是 restart，Word 打开就是散开或重叠的合并区
            if (planned.repeated()) clearVerticalMerge(cell);
            for (XWPFParagraph paragraph : cell.getParagraphs()) replaceInParagraph(paragraph, planned.scope(), images);
            for (XWPFTable nested : cell.getTables()) {
                expandTableLoops(nested, planned.scope(), images, mergeAlign);
            }
        }
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
                    // 没有配对：清掉标记，避免反复命中同一段造成死循环
                    rewriteText(paragraph, text -> LOOP_START.matcher(text).replaceAll(""));
                    continue;
                }
                start = i;
                end = candidate;
            }
            if (start < 0) return;
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
            for (Object item : itemsOf(parseLoop(startParagraph.getText()), params)) {
                planRowsInto(texts, 0, texts.size(), scopeOf(item, params), plan);
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
        List<PlannedRow> plan = new ArrayList<>();
        planRowsInto(texts, from, to, params, plan);
        return plan;
    }

    private void planRowsInto(List<String> texts, int from, int to, Map<String, Object> params,
                              List<PlannedRow> plan) {
        int i = from;
        while (i < to) {
            String text = texts.get(i);
            Loop loop = parseLoop(text);
            if (loop == null) {
                plan.add(new PlannedRow(i, params));
                i++;
                continue;
            }
            int end = isLoopStart(text) ? matchLoopEnd(texts, i, to) : -1;
            if (end > i) {
                // 区域循环：区域内所有行按数组重复，内层标记在递归里继续展开
                List<PlannedRow> body = new ArrayList<>();
                for (Object item : itemsOf(loop, params)) {
                    planRowsInto(texts, i + 1, end, scopeOf(item, params), body);
                }
                plan.addAll(tagLoop(body, loop));
                i = end + 1;
                continue;
            }
            if (isLoopStart(text)) {
                // 独占一行却没有配对的 {{/each}}：退回单行循环。这种情况最常见的后果是
                // 「内层循环的数组在外层项里取不到」，报错信息很难指向真正原因，这里先留一条日志。
                log.warn("模板里的循环标记 {} 独占一行，但没有配对的结束标记，已按单行循环处理；"
                        + "如果是想做区域循环（循环里再套循环），请补上 {{/each}}", "{{#each " + loop + "}}");
            }
            // 行内标记，或独占一行但没有配对的 {{/each}}：按单行循环处理（保持老模板行为）
            List<PlannedRow> single = new ArrayList<>();
            for (Object item : itemsOf(loop, params)) single.add(new PlannedRow(i, scopeOf(item, params)));
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
            if (inLoop || loop != null) indexes.add(i);
            i++;
        }
    }

    private boolean isLoopStart(String text) {
        return text != null && LOOP_START.matcher(text.trim()).matches();
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
     */
    private Map<String, Object> scopeOf(Object item, Map<String, Object> params) {
        Map<String, Object> scope = new LinkedHashMap<>(params);
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
            if (actual != null && String.valueOf(actual).trim().equalsIgnoreCase(target)) matched.add(item);
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

    private Object mapValue(Map<?, ?> map, String key) {
        String actual = matchKey(map, key);
        return actual == null ? null : map.get(actual);
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
        return renderXlsx(template, params, List.of(), DocTemplateMergeAlign.DEFAULT);
    }

    public byte[] renderXlsx(byte[] template, Map<String, Object> params,
                             List<DocTemplateImageSpec> images) throws Exception {
        return renderXlsx(template, params, images, DocTemplateMergeAlign.DEFAULT);
    }

    public byte[] renderXlsx(byte[] template, Map<String, Object> params,
                             List<DocTemplateImageSpec> images, DocTemplateMergeAlign mergeAlign) throws Exception {
        log.debug("渲染 XLSX，参数：{}，图片字段：{}，合并对齐：{}",
                describeParams(params), describeImages(images), mergeAlign);
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(template));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (int s = 0; s < workbook.getNumberOfSheets(); s++) {
                Sheet sheet = workbook.getSheetAt(s);
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
                index++;
                continue;
            }
            int span = 1;
            if (isLoopStart(texts.get(0))) {
                int end = matchLoopEnd(texts, 0, texts.size());
                if (end > 0) span = end + 1;
            }
            List<PlannedRow> plan = planRows(texts.subList(0, span), 0, span, params);
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
            Object value = resolve(token, params);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(stringify(value)));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * {@code {{#user}}} 这种写法会被当成名为 {@code #user} 的占位符，取不到值就静默变成空串，
     * 非常难查。这里直接拦掉，提示正确写法。
     */
    private void checkToken(String token) {
        if (token == null) return;
        String raw = token.trim();
        if (!raw.startsWith("#")) return;
        if (raw.toLowerCase(Locale.ROOT).startsWith("#each")) return;
        throw new IllegalArgumentException("不认识的标记 " + raw + "，明细行循环要写成 {{#each 数组名}}"
                + "（例如 {{#each user}}），不要漏掉 each");
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
     * 按 {@code a.b.c} 取值，键名大小写不敏感（数据库列名可能是大写，模板里通常小写）。
     * 返回 {@link Found#present()} 区分「键不存在」与「键存在但值为 null」。
     */
    private Found lookupLenient(String key, Map<String, Object> params) {
        if (key == null || params == null) return Found.MISSING;
        String trimmed = key.trim();
        if (trimmed.isEmpty()) return Found.MISSING;
        Object current = params;
        for (String part : trimmed.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) return Found.MISSING;
            String actual = matchKey(map, part);
            if (actual == null) return Found.MISSING;
            current = map.get(actual);
        }
        return new Found(true, current);
    }

    /** 先精确匹配，再忽略大小写匹配，返回 Map 里真实的键名。 */
    private String matchKey(Map<?, ?> map, String key) {
        if (map.containsKey(key)) return key;
        String lower = key.toLowerCase(Locale.ROOT);
        for (Object candidate : map.keySet()) {
            if (candidate != null && String.valueOf(candidate).toLowerCase(Locale.ROOT).equals(lower)) {
                return String.valueOf(candidate);
            }
        }
        return null;
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
