package io.github.violateer.doctemplate.starter.annotate;

import io.github.violateer.doctemplate.starter.db.DocTemplateDialect;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 从计划中的字段与样例值生成两类产物：渲染自检用的参数，以及数据块的模拟 SQL。
 *
 * <p><b>SQL 不交给模型写</b>：模型只给「字段 + 样例值」，这里按方言拼成
 * {@code select '2026-0011' as report_no union all select '2026-0012'}。好处是必然合法、必然跨库、
 * 必然过 {@code DocTemplateSqlValidator} 的只读校验，也不依赖任何真实表存在。</p>
 *
 * <p>四个方言只有一处不同：Oracle 的 SELECT 必须带 {@code from dual}，而且<b>每一段都要带</b>，
 * 不是整条末尾加一个。其余三家（MySQL / PostgreSQL / SQL Server）可以省略 FROM。</p>
 */
public final class DocTemplateMockData {

    /** 数字字面量的判定：带千分位、货币符号、百分号的样例值一律按文本处理，保留预览中的原始形态。 */
    private static final Pattern NUMERIC = Pattern.compile("^-?\\d+(\\.\\d+)?$");
    private static final int MAX_ROWS = 5;

    private DocTemplateMockData() {
    }

    /** 渲染参数：{@code object} 块给一个 Map，{@code array} 块给一组 Map。 */
    public static Map<String, Object> params(DocTemplateMarkPlan plan) {
        Map<String, Object> data = new LinkedHashMap<>();
        for (DocTemplateMarkPlan.Block block : plan.blocks()) {
            if ("array".equals(block.shape())) {
                List<Map<String, Object>> rows = new ArrayList<>();
                for (int i = 0; i < rowCount(block); i++) rows.add(row(block, i));
                data.put(block.code(), rows);
            } else {
                data.put(block.code(), row(block, 0));
            }
        }
        return data;
    }

    /** 一个块的模拟 SQL，按方言拼 {@code union all}。 */
    public static String sql(DocTemplateMarkPlan.Block block, DocTemplateDialect dialect) {
        int rows = rowCount(block);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rows; i++) {
            if (i > 0) sb.append("\nunion all\n");
            sb.append("select ");
            List<DocTemplateMarkPlan.Field> fields = block.fields();
            for (int f = 0; f < fields.size(); f++) {
                if (f > 0) sb.append(", ");
                sb.append(literal(fields.get(f), i, dialect)).append(" as ").append(fields.get(f).field());
            }
            if (dialect == DocTemplateDialect.ORACLE) sb.append(" from dual");
        }
        return sb.toString();
    }

    /** 样例值最多取 5 行，少则 1 行：预览够看，又不至于把 SQL 撑得没人读。 */
    public static int rowCount(DocTemplateMarkPlan.Block block) {
        int max = 1;
        for (DocTemplateMarkPlan.Field field : block.fields()) max = Math.max(max, field.samples().size());
        return Math.min(max, MAX_ROWS);
    }

    private static Map<String, Object> row(DocTemplateMarkPlan.Block block, int index) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (DocTemplateMarkPlan.Field field : block.fields()) {
            row.put(field.field(), value(field, index));
        }
        return row;
    }

    private static Object value(DocTemplateMarkPlan.Field field, int index) {
        if (index >= field.samples().size()) return null;
        String raw = field.samples().get(index);
        if (raw == null) return null;
        return switch (field.type()) {
            case "number" -> numeric(raw);
            case "boolean" -> bool(raw);
            default -> raw;
        };
    }

    private static Object numeric(String raw) {
        String text = raw.trim().replace(",", "").replace("¥", "").replace("￥", "").replace("$", "").trim();
        if (!NUMERIC.matcher(text).matches()) return raw;
        BigDecimal value = new BigDecimal(text);
        // 整数发成 1 而不是 1.0：模拟 SQL 里看着像人写的，也省得 union all 两侧类型不一致
        return value.stripTrailingZeros().scale() <= 0 ? value.toBigIntegerExact() : value;
    }

    private static Object bool(String raw) {
        String text = raw.trim().toLowerCase(Locale.ROOT);
        return text.equals("1") || text.equals("true") || text.equals("y") || text.equals("yes") || text.equals("是");
    }

    /**
     * 一个样例值的 SQL 字面量。
     *
     * <p>数字不加引号（否则整列变 varchar，数值语义和排序都没了）；布尔发 {@code 1/0}，
     * 因为 {@code TRUE}/{@code FALSE} 在 Oracle 23c 之前不是 SQL 字面量；其余一律当文本。</p>
     */
    private static String literal(DocTemplateMarkPlan.Field field, int index, DocTemplateDialect dialect) {
        Object value = value(field, index);
        if (value == null) return "null";
        if (value instanceof Number number) return number.toString();
        if (value instanceof Boolean flag) return flag ? "1" : "0";
        return quote(String.valueOf(value), dialect);
    }

    private static String quote(String text, DocTemplateDialect dialect) {
        String escaped = text.replace("'", "''");
        // MySQL 默认把反斜杠当转义符，其余三家按标准 SQL 当普通字符
        if (dialect == DocTemplateDialect.MYSQL) escaped = escaped.replace("\\", "\\\\");
        return "'" + escaped + "'";
    }
}
