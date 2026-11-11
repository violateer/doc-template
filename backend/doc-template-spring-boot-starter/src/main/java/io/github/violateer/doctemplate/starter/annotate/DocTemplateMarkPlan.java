package io.github.violateer.doctemplate.starter.annotate;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 标记计划：AI（或人）对一份文档给出的「哪里是变量、叫什么名字」的结构化结论。
 *
 * <p>它是整个功能唯一的交接面 —— 模型只产出这份 JSON，落笔全部由 {@link DocTemplateAnnotator}
 * 按坐标执行，所以模型不可能写到白名单之外的地方，写错了也能 diff、能重试、能人工改。</p>
 *
 * <p>坐标语义见 {@link DocTemplateSnapshot}：一律 0 起始，{@code cell} 是网格列号。
 * {@code merge} 例外，它按模板语法原样透传：1 起始，DOCX 是单元格序号、XLSX 是列号。</p>
 *
 * <pre>
 * {
 *   "blocks": [
 *     {"code":"main","name":"检验报告主信息","shape":"object",
 *      "fields":[{"field":"report_no","label":"报告编号","type":"string","samples":["2026-0011"]}]},
 *     {"code":"items","name":"检验明细","shape":"array",
 *      "fields":[{"field":"sample_no","label":"样品编号","type":"string","samples":["1#","2#"]}]}
 *   ],
 *   "marks": [
 *     {"kind":"value","at":{"table":0,"row":0,"cell":1},"block":"main","field":"report_no","replace":"2026-0011"},
 *     {"kind":"loop","at":{"table":1,"row":2},"block":"items","merge":[1],"by":"sample_no"},
 *     {"kind":"value","at":{"table":1,"row":2,"cell":0},"block":"items","field":"sample_no","replace":"1#"}
 *   ]
 * }
 * </pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
public record DocTemplateMarkPlan(List<Block> blocks, List<Mark> marks, List<Ignored> ignore) {

    /**
     * 计划 JSON 固定用驼峰（{@code rowTo}），不跟模块其余响应一起转下划线。
     *
     * <p>原因是它要原样往返：模型输出 → 人改 → 再提交回来。{@code @JsonNaming} 把这条约定钉在类上，
     * 无论宿主把 Spring 的 ObjectMapper 配成什么命名策略，这份 JSON 都还是同一套写法。</p>
     */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** 字段名与块编码只认这一套字符，避免和占位符语法、SQL 别名打架。 */
    private static final Pattern CODE = Pattern.compile("^[A-Za-z][A-Za-z0-9_]*$");
    private static final Pattern FIELD = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");
    private static final Set<String> SHAPES = Set.of("object", "array");
    private static final Set<String> KINDS = Set.of("value", "loop", "region");
    private static final Set<String> TYPES = Set.of("string", "number", "date", "boolean");
    /**
     * 不能当字段名的 SQL 关键字。
     *
     * <p>模拟 SQL 的列别名是 {@code as 字段名}，而别名加引号各库写法不同（MySQL 反引号、
     * 其余双引号，MySQL 双引号还得开 ANSI_QUOTES），所以只能避开，不能靠引号兜。
     * 这里只收「确实会炸、且不像业务字段名」的那些；{@code status} / {@code amount} 这类照常能用。</p>
     */
    private static final Set<String> RESERVED = Set.of(
            "all", "and", "as", "asc", "by", "case", "check", "column", "date", "default", "desc",
            "else", "end", "false", "for", "from", "group", "having", "in", "index", "is", "join",
            "key", "level", "like", "limit", "not", "null", "number", "offset", "on", "or", "order",
            "primary", "references", "row", "rows", "select", "session", "table", "then", "true",
            "union", "user", "values", "view", "when", "where", "with");

    public DocTemplateMarkPlan {
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
        marks = marks == null ? List.of() : List.copyOf(marks);
        ignore = ignore == null ? List.of() : List.copyOf(ignore);
    }

    /**
     * 解析一段「计划 JSON」文本。
     *
     * <p>模型输出经常裹一层 markdown 代码块、前后带一两句解释，这里按第一个 {@code {} 到最后一个
     * {@code }} 截取，不要求模型每次都干净。截不到就报错并带上原文开头 —— 报错要能让人自己判断是
     * 提示词的问题还是模型的问题。</p>
     */
    public static DocTemplateMarkPlan parse(String text) {
        String json = extractJsonObject(text);
        try {
            return MAPPER.readValue(json, DocTemplateMarkPlan.class);
        } catch (Exception error) {
            throw new IllegalArgumentException("计划 JSON 解析失败：" + error.getMessage()
                    + "；收到的内容开头是「" + head(json) + "」", error);
        }
    }

    /** 转成驼峰命名的 Map，用于响应回显和日志；与 {@link #parse(String)} 的写法一致。 */
    public Map<String, Object> toMap() {
        return MAPPER.convertValue(this, new TypeReference<LinkedHashMap<String, Object>>() {
        });
    }

    private static String extractJsonObject(String text) {
        if (text == null || text.isBlank())
            throw new IllegalArgumentException("计划内容为空：没有拿到任何 JSON，检查模型是否被拦截或超时");
        int from = text.indexOf('{');
        int to = text.lastIndexOf('}');
        if (from < 0 || to <= from)
            throw new IllegalArgumentException("输出里找不到 JSON 对象：期望以 { 开头、以 } 结尾，"
                    + "实际开头是「" + head(text) + "」");
        return text.substring(from, to + 1);
    }

    private static String head(String text) {
        String oneLine = text.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= 120 ? oneLine : oneLine.substring(0, 120) + "…";
    }

    /** 一个数据块：{@code object} 对应 {@code single:true}，{@code array} 对应明细行。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public record Block(String code, String name, String shape, List<Field> fields) {
        public Block {
            fields = fields == null ? List.of() : List.copyOf(fields);
            shape = shape == null ? null : shape.trim().toLowerCase(Locale.ROOT);
        }
    }

    /** 一个字段。{@code samples} 直接用文档里识别到的原值，既是模拟数据，也是预览的比对基准。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public record Field(String field, String label, String type, List<String> samples) {
        public Field {
            samples = samples == null ? List.of() : List.copyOf(samples);
            type = type == null || type.isBlank() ? "string" : type.trim().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * 一条标记，{@code kind} 决定语义：
     *
     * <ul>
     *   <li>{@code value}：把 {@code replace} 那段文字换成 {@code ${块.字段}}；落在循环行里时换成
     *       {@code ${字段}}（短名，取当前行）。{@code replace} 留空表示整段替换。</li>
     *   <li>{@code loop}：<b>行内循环</b>，一行按数组重复。标记写在行首格和行尾格、两个标记同一行
     *       —— 这是渲染器的硬要求。</li>
     *   <li>{@code region}：<b>区域循环</b>，{@code at.row} 到 {@code at.rowTo} 之间的所有行整体重复。
     *       落笔时在区域前后各插入一行放 {@code {{#each}}} / {@code {{/each}}}，样例行一行都不删。</li>
     * </ul>
     *
     * <p>标注只做<b>单层</b>循环：动态列和嵌套循环（循环套循环、{@code where} 关联过滤）不在范围内。
     * 这两类仍然可以在模板里手写，只是不由这条链路生成。</p>
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public record Mark(String kind, At at, String block, String field, String replace,
                       // 模型经常把单列写成 merge: 1 而不是 [1]，两种都收
                       @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY) List<Integer> merge,
                       String by,
                       // 只为把「不支持嵌套」这件事说清楚：非空即拒绝，不参与落笔
                       String where) {
        public Mark {
            kind = kind == null ? null : kind.trim().toLowerCase(Locale.ROOT);
            merge = merge == null ? List.of() : List.copyOf(merge);
        }
    }

    /** 明确不动的区域，只用于回显给人看（例如签字盖章区），不影响落笔。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public record Ignored(At at, String reason) {
    }

    /**
     * 坐标。{@code p} 是格内第几段，默认 0；{@code cell} 是网格列号（XLSX 就是列下标）。
     *
     * <p>DOCX 用 {@code paragraph} 或 {@code table + row + cell}，XLSX 用 {@code sheet + row + cell}。
     * 两种格式的坐标不混用，落笔时各自校验。</p>
     *
     * <p>{@code rowTo} 只给循环标记用：样例数据从 {@code row} 到 {@code rowTo}（含），
     * 落笔后只留 {@code row} 这一行当模板行，其余整行删掉 —— 不删的话每份产出都会多出几行静态样例。</p>
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public record At(Integer paragraph, Integer sheet, Integer table, Integer row, Integer rowTo,
                     Integer cell, Integer p) {

        String key() {
            if (paragraph != null) return "p" + paragraph;
            return "s" + sheet + "t" + table + "r" + row + "c" + cell + "p" + (p == null ? 0 : p);
        }

        /** 样例数据的最后一行；没写就是 {@code row} 本身。 */
        int lastRow() {
            return rowTo == null ? row : rowTo;
        }
    }

    /**
     * 自检：块、字段、标记之间的自洽性。坐标是否越界要看文档才知道，由 {@link DocTemplateAnnotator} 负责。
     *
     * <p>全部「报错而不是猜」：写错了直接说清哪一条、错在哪、正确写法是什么。</p>
     */
    public void validate() {
        if (blocks.isEmpty())
            throw new IllegalArgumentException("plan.blocks 不能为空：至少要有一个数据块，否则生成的模板没有任何数据来源");
        if (marks.isEmpty())
            throw new IllegalArgumentException("plan.marks 不能为空：没有任何标记，模板文件会原样保存，起不到模板的作用");

        Map<String, Block> byCode = new LinkedHashMap<>();
        for (Block block : blocks) {
            String code = block.code();
            if (code == null || !CODE.matcher(code).matches())
                throw new IllegalArgumentException("数据块编码「" + code + "」不合法：只能由字母开头，"
                        + "包含字母、数字、下划线（会写进 ${编码.字段} 和 {{#each 编码}}）");
            if (byCode.putIfAbsent(code, block) != null)
                throw new IllegalArgumentException("数据块编码重复：" + code + "；同一模板内编码必须唯一");
            if (block.shape() == null || !SHAPES.contains(block.shape()))
                throw new IllegalArgumentException("数据块 " + code + " 的 shape 只能写 object（单条）或 array（多条），当前是「"
                        + block.shape() + "」");
            if (block.fields().isEmpty())
                throw new IllegalArgumentException("数据块 " + code + " 没有字段：至少要有一个字段可用于模板");

            Set<String> names = new HashSet<>();
            for (Field field : block.fields()) {
                String name = field.field();
                if (name == null || !FIELD.matcher(name).matches())
                    throw new IllegalArgumentException("数据块 " + code + " 的字段名「" + name + "」不合法："
                            + "只能包含字母、数字、下划线，且不能以数字开头");
                if (!names.add(name))
                    throw new IllegalArgumentException("数据块 " + code + " 的字段名重复：" + name);
                if (RESERVED.contains(name.toLowerCase(Locale.ROOT)))
                    throw new IllegalArgumentException("数据块 " + code + " 的字段名「" + name + "」是 SQL 关键字，"
                            + "模拟 SQL 的列别名不能加引号（各库写法不同），请改用其他名称，例如 " + name + "_value");
                if (!TYPES.contains(field.type()))
                    throw new IllegalArgumentException("数据块 " + code + " 的字段 " + name + " 的 type 只能是 "
                            + String.join(" / ", TYPES) + "，当前是「" + field.type() + "」");
            }
        }

        Set<String> targets = new HashSet<>();
        for (Mark mark : marks) {
            if ("columns".equals(mark.kind()))
                throw new IllegalArgumentException("AI 导入不支持动态列：列数随数据变的表格请在管理页手工标注，"
                        + "或把列固定下来再导入。标记的 kind 只能写 " + String.join(" / ", KINDS));
            if (mark.kind() == null || !KINDS.contains(mark.kind()))
                throw new IllegalArgumentException("标记的 kind 只能写 " + String.join(" / ", KINDS)
                        + "，当前是「" + mark.kind() + "」");
            Block block = mark.block() == null ? null : byCode.get(mark.block());
            if (block == null)
                throw new IllegalArgumentException("标记指向的数据块「" + mark.block() + "」不存在，"
                        + "已定义的块有：" + String.join("、", byCode.keySet()));
            if (mark.at() == null) throw new IllegalArgumentException("标记 " + mark.kind() + " 缺少 at 坐标");

            if ("value".equals(mark.kind())) {
                requireValue(mark, block);
            } else {
                requireLoop(mark, block);
            }

            String key = mark.kind() + ":" + mark.at().key();
            if (!targets.add(key))
                throw new IllegalArgumentException("同一个位置被标注了两次：" + describe(mark.at())
                        + "；一格只能有一条 value 标记，一行只能有一条 loop / region 标记");
        }
    }

    private static void requireValue(Mark mark, Block block) {
        if (mark.field() == null || mark.field().isBlank())
            throw new IllegalArgumentException("值标记必须给 field：说明这一格取块 " + block.code() + " 的哪个字段");
        if (block.fields().stream().noneMatch(f -> f.field().equals(mark.field())))
            throw new IllegalArgumentException("值标记的字段「" + mark.field() + "」不在块 " + block.code()
                    + " 里，该块的字段有：" + String.join("、", block.fields().stream().map(Field::field).toList()));
        boolean inParagraph = mark.at().paragraph() != null;
        boolean inTable = mark.at().row() != null && mark.at().cell() != null
                && (mark.at().table() != null || mark.at().sheet() != null);
        if (!inParagraph && !inTable)
            throw new IllegalArgumentException("值标记的 at 要么给 paragraph（正文第几段），"
                    + "要么给 table + row + cell（DOCX）或 sheet + row + cell（XLSX），都是 0 起始");
        if (inParagraph && (mark.at().table() != null || mark.at().sheet() != null))
            throw new IllegalArgumentException("值标记的 at 不能同时写 paragraph 和 table/sheet，二选一");
        if (mark.at().rowTo() != null)
            throw new IllegalArgumentException("值标记的 at 不应包含 rowTo：该字段仅用于循环标记，"
                    + "表示样例数据或区域的最后一行");
    }

    /** 循环（行内）与区域循环共用的规则，差别只在 rowTo 是不是必填、样例行删不删。 */
    private static void requireLoop(Mark mark, Block block) {
        boolean region = "region".equals(mark.kind());
        if (mark.where() != null && !mark.where().isBlank())
            throw new IllegalArgumentException("AI 导入不支持嵌套循环：where 关联过滤只用于循环套循环，"
                    + "这条链路只做单层循环。请把两层数据拆成两张表，或改用固定列");
        if (mark.at().row() == null || (mark.at().table() == null && mark.at().sheet() == null))
            throw new IllegalArgumentException((region ? "区域循环" : "循环") + "标记必须给 at.row 和 at.table（DOCX）"
                    + "或 at.sheet（XLSX），都是 0 起始");
        if (mark.at().cell() != null)
            throw new IllegalArgumentException((region ? "区域循环" : "循环") + "标记作用于整行，不应指定 at.cell；"
                    + "行内循环的起止标记会由工具写在行首格和行尾格，区域循环会各占一行");
        if (!"array".equals(block.shape()))
            throw new IllegalArgumentException("循环标记指向的块 " + block.code() + " 的 shape 是 object，"
                    + "仅 array（多条）可用于明细行循环");
        if (region && mark.at().rowTo() == null)
            throw new IllegalArgumentException("区域循环必须给 at.rowTo（区域最后一行，含）："
                    + "at.row 到 at.rowTo 之间的行整体重复");
        if (mark.at().rowTo() != null && mark.at().rowTo() < mark.at().row())
            throw new IllegalArgumentException("循环标记的 at.rowTo（" + mark.at().rowTo()
                    + "）不能小于 at.row（" + mark.at().row() + "）");
        if (mark.by() != null && block.fields().stream().noneMatch(f -> f.field().equals(mark.by())))
            throw new IllegalArgumentException("循环标记的 by=" + mark.by() + " 不是块 " + block.code()
                    + " 的字段，合并分组键必须是这一行的字段名");
        for (Integer column : mark.merge()) {
            if (column == null || column < 1)
                throw new IllegalArgumentException("merge 里的列号从 1 开始，收到「" + column + "」");
        }
    }

    private static String describe(At at) {
        if (at.paragraph() != null) return "第 " + at.paragraph() + " 段";
        if (at.sheet() != null) return "工作表 " + at.sheet() + " 第 " + at.row() + " 行第 " + at.cell() + " 列";
        return "表格 " + at.table() + " 第 " + at.row() + " 行第 " + at.cell() + " 列";
    }
}
