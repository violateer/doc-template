package io.github.violateer.doctemplate.starter.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 表格动态列的列定义：由数据块配置里的 {@code columns} 取数得到，决定列数、列标题、取值字段与相对宽度。
 *
 * <p>元素写法：</p>
 * <pre>
 * { "field": "tensile", "label": "抗拉强度", "width": 1 }
 * </pre>
 *
 * <ul>
 *   <li>元素是字符串时按 {@code field = label = 该值} 处理；</li>
 *   <li>{@code label} 留空用 {@code field} 兜底；</li>
 *   <li>{@code width} 是<b>相对权重</b>（正数，缺省 1），只决定列区内各列的相对宽窄。</li>
 * </ul>
 *
 * <p><b>样式不在这里配</b>：字体、字号、边框、底纹、对齐全部沿用模板里写标记的那一格 ——
 * 表头标记格管表头，数据标记格管数据行。列源是数据，不该兼管排版。</p>
 *
 * <p>列源是数据，不是配置：一条脏数据不该让整份文档生成失败，所以宽度是「修正 + 告警」，
 * 只有结构性错误（不是数组、缺 {@code field}、字段名非法、重复、超过上限）才抛异常。</p>
 */
public record DocTemplateColumnSpec(String field, String label, double width) {

    private static final Logger log = LoggerFactory.getLogger(DocTemplateColumnSpec.class);

    /** 列数上限：XLSX 单表 16384 列、DOCX 单元格过多会明显变慢，这是性能护栏而不是安全边界。 */
    public static final int DEFAULT_MAX_COLUMNS = 200;

    /**
     * {@code field} 会被拼进 {@code ${...}} 占位符，带空格 / 花括号会把模板写坏，
     * 所以只允许字母、数字、下划线和点（点用于嵌套路径）。
     */
    private static final Pattern FIELD = Pattern.compile("[A-Za-z0-9_.]+");

    /** 解析列源返回的原始值；空值返回空列表。 */
    public static List<DocTemplateColumnSpec> parse(Object raw) {
        return parse(raw, DEFAULT_MAX_COLUMNS);
    }

    public static List<DocTemplateColumnSpec> parse(Object raw, int maxColumns) {
        List<?> items = asList(raw);
        int limit = maxColumns > 0 ? maxColumns : DEFAULT_MAX_COLUMNS;
        if (items.size() > limit) {
            throw new IllegalArgumentException("列定义有 " + items.size() + " 列，超过上限 " + limit
                    + " 列。请让列源只返回需要渲染的列，或拆分为多张表");
        }
        List<DocTemplateColumnSpec> specs = new ArrayList<>(items.size());
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < items.size(); i++) {
            DocTemplateColumnSpec spec = parseOne(items.get(i), i + 1);
            if (!seen.add(spec.field().toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("列定义第 " + (i + 1) + " 列的字段「" + spec.field()
                        + "」重复，同一张表中每列只能出现一次（按小写比较）");
            }
            specs.add(spec);
        }
        return List.copyOf(specs);
    }

    /** 权重之和，用来按比例分配列宽；空列表返回 0。 */
    public static double totalWeight(List<DocTemplateColumnSpec> specs) {
        double sum = 0;
        for (DocTemplateColumnSpec spec : specs) sum += spec.width();
        return sum;
    }

    private static List<?> asList(Object raw) {
        if (raw == null) return List.of();
        if (raw instanceof List<?> list) return list;
        if (raw instanceof CharSequence text) {
            String trimmed = text.toString().trim();
            if (trimmed.isEmpty()) return List.of();
            if (trimmed.startsWith("[")) {
                // SQL 用 JSON_ARRAY / JSON_ARRAYAGG 拼出来的列定义，JDBC 给回来是字符串
                Object parsed = DocTemplateJson.read(trimmed);
                if (parsed instanceof List<?> list) return list;
            }
            throw new IllegalArgumentException("列定义必须是数组（元素是 {field,label,width}），"
                    + "实际拿到的是字符串：" + abbreviate(trimmed));
        }
        if (raw instanceof Iterable<?> iterable) {
            List<Object> items = new ArrayList<>();
            for (Object item : iterable) items.add(item);
            return items;
        }
        if (raw.getClass().isArray()) {
            int length = Array.getLength(raw);
            List<Object> items = new ArrayList<>(length);
            for (int i = 0; i < length; i++) items.add(Array.get(raw, i));
            return items;
        }
        throw new IllegalArgumentException("列定义必须是数组（元素是 {field,label,width}），实际是 "
                + raw.getClass().getSimpleName());
    }

    private static DocTemplateColumnSpec parseOne(Object item, int position) {
        String field;
        String label;
        double width = 1;
        if (item instanceof CharSequence text) {
            field = text.toString().trim();
            label = field;
        } else if (item instanceof Map<?, ?> map) {
            field = text(map.get("field"));
            label = text(map.get("label"));
            width = number(map.get("width"));
        } else {
            throw new IllegalArgumentException("列定义第 " + position + " 项既不是字符串也不是对象：" + item);
        }
        if (field.isEmpty()) {
            throw new IllegalArgumentException("列定义第 " + position + " 项缺少 field："
                    + "每列都要写 field（取值字段名），例如 {\"field\":\"tensile\",\"label\":\"抗拉强度\"}");
        }
        if (!FIELD.matcher(field).matches()) {
            throw new IllegalArgumentException("列定义第 " + position + " 项的字段名「" + field
                    + "」不合法：只允许字母、数字、下划线和点（它会拼进 ${...} 占位符，不能带空格或花括号）");
        }
        if (label.isBlank()) label = field;
        if (width <= 0 || Double.isNaN(width) || Double.isInfinite(width)) width = 1;
        return new DocTemplateColumnSpec(field, label.trim(), width);
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static double number(Object value) {
        if (value instanceof Number number) return number.doubleValue();
        if (value instanceof CharSequence text) {
            try {
                return Double.parseDouble(text.toString().trim());
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private static String abbreviate(String text) {
        return text.length() <= 60 ? text : text.substring(0, 60) + "…";
    }
}
