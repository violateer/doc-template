package io.github.violateer.doctemplate.starter.annotate;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 文档结构快照：把一份 docx / xlsx 摊成「坐标 + 文本 + 轻量样式」的纯数据，
 * 供模型（或人）判断哪些格子是变量、哪些是固定文案。
 *
 * <p><b>坐标一律 0 起始</b>，并且和 {@link DocTemplateAnnotator} 的落笔坐标完全一致：</p>
 *
 * <ul>
 *   <li>{@code paragraph}：正文第几段（{@code document.getParagraphs()} 的下标，不含表格里的段落）；</li>
 *   <li>{@code table}：正文第几张表（{@code document.getTables()} 的下标，只算顶层表格）；</li>
 *   <li>{@code row}：表内第几行；</li>
 *   <li>{@code cell}：<b>网格列号</b>，不是「第几个 w:tc」——横向合并的格子占多个网格列，
 *       但只记在它的起始列上；</li>
 *   <li>xlsx 的 {@code sheet} / {@code row} / {@code col} 都是 POI 原生下标（{@code col} = Excel 列号 - 1）。</li>
 * </ul>
 *
 * <p>{@code style} 只写非默认项，逗号分隔：{@code bold}、{@code center}、{@code right}。
 * 判断「这一行是表头」靠的就是加粗、居中、列数一致这几个信号，别省。</p>
 *
 * <p>空段落、空行不列出来（省 token），但坐标仍按真实下标算，所以列表里的 {@code index} 可能不连续。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DocTemplateSnapshot(
        String fileType,
        String coordinateBase,
        List<Paragraph> paragraphs,
        List<Table> tables,
        List<Sheet> sheets,
        List<String> warnings) {

    /** 正文段落。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Paragraph(int index, String text, String style, int runs) {
    }

    /** 正文表格。{@code cols} 是这张表最宽一行的网格列数。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Table(int index, int cols, int rowCount, List<Row> rows) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Row(int index, List<Cell> cells) {
    }

    /**
     * 单元格。{@code paragraphs} 只在格内有多个段落时才给（单段落时 {@code text} 就是它），
     * 用来精确定位值标记要替换第几段。
     *
     * <p>{@code vMerge} 为 {@code true} 表示这一格是纵向合并的<b>续格</b>：Word 里它没有自己的内容，
     * 显示的是上面那格。这类格子不能标注 —— 替换到空段落上，产出会少一块内容。</p>
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Cell(int col, int span, String text, List<String> paragraphs, String style, Boolean vMerge) {
    }

    /** 工作表。{@code index} 是 POI 的 sheet 下标。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Sheet(int index, String name, int rowCount, List<SheetRow> rows) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SheetRow(int index, List<SheetCell> cells) {
    }

    /**
     * 工作表单元格。{@code type} 是 {@code s}（文本）/ {@code n}（数字）/ {@code b}（布尔）/ {@code f}（公式）：
     * 渲染器只处理文本格，数字格里写标记无效，所以这个字段是给判断用的。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SheetCell(int col, String text, String type, int span) {
    }
}
