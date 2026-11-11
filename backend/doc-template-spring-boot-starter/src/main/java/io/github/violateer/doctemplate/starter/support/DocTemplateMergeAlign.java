package io.github.violateer.doctemplate.starter.support;

import cn.hutool.core.convert.Convert;
import cn.hutool.core.util.StrUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 竖向合并出来的那一格，内容**垂直**怎么对齐。
 *
 * <p>配置写在数据块的 {@code data_source_config} 里，三种数据源（JAVA / SQL / API）通用：</p>
 *
 * <pre>
 * {
 *   "sql": "select material_code, batch_no from ...",
 *   "single": false,
 *   "mergeCellAlign": "center"
 * }
 * </pre>
 *
 * <p>只有三种取值，管的是垂直方向：</p>
 *
 * <table border="1">
 *   <caption>三种对齐方式</caption>
 *   <tr><th>取值</th><th>含义</th></tr>
 *   <tr><td>{@code top}（默认）</td><td>靠上</td></tr>
 *   <tr><td>{@code center}</td><td>垂直居中</td></tr>
 *   <tr><td>{@code bottom}</td><td>靠下</td></tr>
 * </table>
 *
 * <p><b>水平方向不动</b>：合并格沿用模板里那一格原本的水平对齐（左 / 中 / 右），
 * 所以表格里靠右的数字列、居中的状态列都不会因为竖向合并被打乱。</p>
 *
 * <p>写法容错：{@code middle} / {@code mid} / {@code centre} 都认作 {@code center}；
 * 老配置里带水平词的写法（{@code middle-center}、{@code top-right}）会只取垂直部分，
 * 水平词直接忽略。认不出来的写法按默认 {@code top} 处理并打一条 warn。</p>
 *
 * @param vertical 垂直对齐：{@code top} / {@code center} / {@code bottom}
 */
public record DocTemplateMergeAlign(String vertical) {

    private static final Logger log = LoggerFactory.getLogger(DocTemplateMergeAlign.class);

    /** 数据块配置里使用的键名（另外兼容 merge_cell_align / mergeAlign）。 */
    public static final String CONFIG_KEY = "mergeCellAlign";

    public static final String TOP = "top";
    public static final String CENTER = "center";
    public static final String BOTTOM = "bottom";

    private static final List<String> CONFIG_KEYS = List.of("mergeCellAlign", "merge_cell_align", "mergeAlign");
    private static final List<String> TOP_WORDS = List.of("top", "upper");
    private static final List<String> CENTER_WORDS = List.of("center", "centre", "middle", "mid");
    private static final List<String> BOTTOM_WORDS = List.of("bottom", "lower");

    /**
     * 默认值：靠上，与没有这项配置时的表现一致。
     *
     * <p>声明放在词表之后只是习惯；{@link #normalize} 特意不依赖词表，
     * 免得静态初始化顺序一变就拿到 {@code null}（踩过一次）。</p>
     */
    public static final DocTemplateMergeAlign DEFAULT = new DocTemplateMergeAlign(TOP);

    public DocTemplateMergeAlign {
        vertical = normalize(vertical);
    }

    /** 没配置时用默认值（靠上）。 */
    public boolean isDefault() {
        return TOP.equals(vertical);
    }

    /** 配置里的写法，例如 {@code center}。 */
    public String value() {
        return vertical;
    }

    @Override
    public String toString() {
        return vertical;
    }

    /**
     * 配置里是否真的写了这一项（用来区分「没配」和「配成了默认值」）。
     */
    public static boolean configured(Map<String, Object> config) {
        if (config == null || config.isEmpty()) return false;
        for (String key : CONFIG_KEYS) {
            if (StrUtil.isNotBlank(Convert.toStr(config.get(key), null))) return true;
        }
        return false;
    }

    /**
     * 从数据块配置里取对齐方式，没配置（或配了空串）时返回默认值。
     */
    public static DocTemplateMergeAlign fromConfig(Map<String, Object> config) {
        if (config == null || config.isEmpty()) return DEFAULT;
        for (String key : CONFIG_KEYS) {
            Object value = config.get(key);
            if (value == null) continue;
            String text = Convert.toStr(value, null);
            if (StrUtil.isNotBlank(text)) return parse(text);
        }
        return DEFAULT;
    }

    /**
     * 解析配置值，认不出来时退回默认值。
     *
     * <p>只挑垂直方向的词，水平词（left / right / start / end）一律忽略——老配置里
     * {@code middle-center} 这种写法同样可用，结果是 {@code center}。</p>
     */
    public static DocTemplateMergeAlign parse(String raw) {
        if (StrUtil.isBlank(raw)) return DEFAULT;
        String text = raw.trim().toLowerCase(Locale.ROOT).replaceAll("[-_\\s,/]+", "-");
        String vertical = null;
        for (String part : text.split("-")) {
            if (part.isEmpty()) continue;
            if (TOP_WORDS.contains(part)) {
                vertical = TOP;
            } else if (BOTTOM_WORDS.contains(part)) {
                vertical = BOTTOM;
            } else if (CENTER_WORDS.contains(part)) {
                vertical = CENTER;
            }
        }
        if (vertical == null) {
            log.warn("mergeCellAlign 的写法「{}」认不出来，已按默认 {} 处理；可选值：top（靠上）/ center（居中）/ bottom（靠下）",
                    raw, TOP);
            return DEFAULT;
        }
        return new DocTemplateMergeAlign(vertical);
    }

    /** 构造期只用到字面量，不碰静态词表（静态初始化顺序不受控，见 {@link #DEFAULT} 的说明）。 */
    private static String normalize(String value) {
        if (StrUtil.isBlank(value)) return TOP;
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "bottom", "lower" -> BOTTOM;
            case "center", "centre", "middle", "mid" -> CENTER;
            default -> TOP;
        };
    }
}
