package io.github.violateer.doctemplate.starter.support;

import cn.hutool.core.convert.Convert;
import cn.hutool.core.util.StrUtil;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 声明「某个占位符要渲染成图片」。
 *
 * <p>配置写在数据块的 {@code data_source_config} 里，三种数据源（JAVA / SQL / API）通用：</p>
 *
 * <pre>
 * {
 *   "sql": "select report_no, logo, photo from qc_report where report_no = :reportNo",
 *   "single": true,
 *   "imageFields": [
 *     "logo",
 *     { "field": "photo", "width": 160, "height": 120, "align": "center", "padding": 4 }
 *   ]
 * }
 * </pre>
 *
 * <p>也接受 {@code image_fields} / {@code images} 两种写法，以及逗号分隔的字符串
 * （{@code "imageFields": "logo,photo"}）。</p>
 *
 * <p>匹配规则：占位符 key 与声明相等即命中；模板里带了命名空间前缀也命中
 * （声明 {@code logo}，模板写 {@code ${report.logo}}、循环体里写 {@code ${item.logo}} 都算）。
 * 反过来不行 —— <b>声明只写字段名本身</b>，写 {@code items.photo} 这种路径没有任何好处：
 * 循环体里根本取不到 {@code items} 这一层，而声明写短名反而两种写法都命中。</p>
 *
 * @param field   字段名，只写字段名本身（如 {@code logo}），不要带块编码或路径
 * @param width   目标宽度（像素），为空表示按图片原始宽度
 * @param height  目标高度（像素），为空表示按原始高度；同时给宽高时按比例缩放到框内
 * @param align   单元格内水平对齐：{@code CENTER}（默认）/ {@code LEFT} / {@code RIGHT}
 * @param padding 图片四周留白（像素），默认 4
 * @param qr      不为空时，这个字段的值先按二维码编码再作为图片插入（见 {@link DocTemplateQrCodeSpec}）
 */
public record DocTemplateImageSpec(String field, Integer width, Integer height, String align, Integer padding,
                                   DocTemplateQrCodeSpec qr) {

    /** 数据块配置里使用的键名（另外兼容 image_fields / images）。 */
    public static final String CONFIG_KEY = "imageFields";

    public static final String ALIGN_CENTER = "CENTER";
    public static final String ALIGN_LEFT = "LEFT";
    public static final String ALIGN_RIGHT = "RIGHT";
    /** 默认留白（像素）：上下左右各 4px。 */
    public static final int DEFAULT_PADDING = 4;
    private static final int MAX_PADDING = 60;

    private static final List<String> CONFIG_KEYS = List.of("imageFields", "image_fields", "images");
    private static final List<String> FIELD_KEYS = List.of("field", "name", "key", "path");
    private static final List<String> WIDTH_KEYS = List.of("width", "w");
    private static final List<String> HEIGHT_KEYS = List.of("height", "h");
    private static final List<String> ALIGN_KEYS = List.of("align", "alignment", "position");
    private static final List<String> PADDING_KEYS = List.of("padding", "margin", "inset");

    public DocTemplateImageSpec {
        field = requirePlainField(field, "图片字段");
        align = normalizeAlign(align);
        padding = padding == null ? DEFAULT_PADDING : Math.min(MAX_PADDING, Math.max(0, padding));
    }

    /**
     * 图片 / 二维码字段名只认「模板里出现的那个名字」，不接受 {@code items.photo} 这种路径。
     *
     * <p>带路径的声明以前靠「短名回退」还能用，现在只按「模板名 == 声明名」或「模板名以 .声明名结尾」
     * 匹配，写路径就成了一个永远匹配不上的死配置 —— 所以这里直接拦下来，别让它静默变成一行 URL 文本。</p>
     */
    static String requirePlainField(String raw, String what) {
        String name = StrUtil.trimToEmpty(raw);
        int dot = name.lastIndexOf('.');
        if (dot < 0) return name;
        String shortName = name.substring(dot + 1);
        throw new IllegalArgumentException(what + "「" + name + "」不要带块编码或路径，只写字段名本身"
                + "（改成「" + shortName + "」）；模板里写 ${" + shortName + "} 或 ${item." + shortName + "} 都能命中");
    }

    /** 老调用方式：只关心字段名与尺寸时用这个，对齐居中、留白取默认值。 */
    public DocTemplateImageSpec(String field, Integer width, Integer height) {
        this(field, width, height, null, null);
    }

    /** 普通图片字段（非二维码）。 */
    public DocTemplateImageSpec(String field, Integer width, Integer height, String align, Integer padding) {
        this(field, width, height, align, padding, null);
    }

    /**
     * 对齐方式归一化：大小写、同义词都认（{@code Left} / {@code start} / {@code centre}），
     * 但<b>认不出来就报错</b>。
     *
     * <p>以前一律退回居中，于是 {@code align: "lef"} 也能生成，图片居中偏了却没有任何提示 ——
     * 配置写错和「本来就想要居中」在结果上完全一样，查不出来。</p>
     */
    public static String normalizeAlign(String align) {
        if (StrUtil.isBlank(align)) return ALIGN_CENTER;
        return switch (align.trim().toUpperCase(Locale.ROOT)) {
            case "LEFT", "START" -> ALIGN_LEFT;
            case "RIGHT", "END" -> ALIGN_RIGHT;
            case "CENTER", "CENTRE", "MIDDLE" -> ALIGN_CENTER;
            default -> throw new IllegalArgumentException("对齐方式「" + align
                    + "」不认识，可选：center（默认）/ left / right");
        };
    }

    /**
     * 模板里的占位符名是否就是声明的这个图片字段。
     *
     * <p>两种写法算命中：完全相同（{@code logo}）、带命名空间前缀（{@code report.logo} /
     * {@code item.logo} 以 {@code .logo} 结尾）。声明带路径的情况由构造器直接拒掉，所以这里
     * 不需要再处理「声明比模板长」的方向。</p>
     */
    public boolean matches(String key) {
        if (StrUtil.isBlank(key) || StrUtil.isBlank(field)) return false;
        String target = key.trim().toLowerCase(Locale.ROOT);
        String name = field.trim().toLowerCase(Locale.ROOT);
        return target.equals(name) || target.endsWith("." + name);
    }

    /**
     * 从数据块配置里取出图片字段声明，找不到时返回空列表。
     */
    public static List<DocTemplateImageSpec> fromConfig(Map<String, Object> config) {
        if (config == null || config.isEmpty()) return List.of();
        for (String key : CONFIG_KEYS) {
            if (config.containsKey(key)) return parse(config.get(key));
        }
        return List.of();
    }

    /**
     * 解析配置值，支持字符串 / 字符串数组 / 对象 / 对象数组 / 「字段名 → 尺寸」的 Map。
     */
    public static List<DocTemplateImageSpec> parse(Object node) {
        List<DocTemplateImageSpec> specs = new ArrayList<>();
        collect(node, specs);
        return dedupe(specs);
    }

    private static void collect(Object node, List<DocTemplateImageSpec> specs) {
        if (node == null) return;
        if (node instanceof Map<?, ?> map) {
            String field = firstText(map, FIELD_KEYS);
            if (StrUtil.isNotBlank(field)) {
                specs.add(specOf(field, map));
                return;
            }
            // {"logo": 120} 表示只给宽度；{"logo": {"width": 120, "align": "left"}} 表示给尺寸/对齐
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = Convert.toStr(entry.getKey(), "").trim();
                if (key.isEmpty()) continue;
                Object value = entry.getValue();
                if (value instanceof Map<?, ?> sub) {
                    specs.add(specOf(key, sub));
                } else if (value instanceof Number || value instanceof String) {
                    specs.add(new DocTemplateImageSpec(key, Convert.toInt(value, null), null));
                } else {
                    specs.add(new DocTemplateImageSpec(key, null, null));
                }
            }
            return;
        }
        if (node instanceof Iterable<?> items) {
            for (Object item : items) collect(item, specs);
            return;
        }
        // "logo,photo" / "logo:120x60"
        for (String part : Convert.toStr(node, "").split(",")) {
            String text = part.trim();
            if (text.isEmpty()) continue;
            String field = text;
            Integer width = null;
            Integer height = null;
            int at = text.indexOf(':');
            if (at > 0) {
                field = text.substring(0, at).trim();
                String size = text.substring(at + 1).trim().toLowerCase(Locale.ROOT);
                String[] pair = size.split("[x*]");
                width = Convert.toInt(pair[0].trim(), null);
                if (pair.length > 1) height = Convert.toInt(pair[1].trim(), null);
            }
            if (!field.isEmpty()) specs.add(new DocTemplateImageSpec(field, width, height));
        }
    }

    private static DocTemplateImageSpec specOf(String field, Map<?, ?> map) {
        Integer padding = null;
        for (String key : PADDING_KEYS) {
            Integer value = Convert.toInt(map.get(key), null);
            if (value != null) {
                padding = value;
                break;
            }
        }
        return new DocTemplateImageSpec(field, firstInt(map, WIDTH_KEYS), firstInt(map, HEIGHT_KEYS),
                firstText(map, ALIGN_KEYS), padding);
    }

    private static List<DocTemplateImageSpec> dedupe(List<DocTemplateImageSpec> specs) {
        Set<String> seen = new LinkedHashSet<>();
        List<DocTemplateImageSpec> result = new ArrayList<>(specs.size());
        for (DocTemplateImageSpec spec : specs) {
            if (spec == null || StrUtil.isBlank(spec.field())) continue;
            if (seen.add(spec.field().toLowerCase(Locale.ROOT))) result.add(spec);
        }
        return result;
    }

    private static String firstText(Map<?, ?> map, List<String> keys) {
        for (String key : keys) {
            String value = Convert.toStr(map.get(key), "").trim();
            if (!value.isEmpty()) return value;
        }
        return null;
    }

    private static Integer firstInt(Map<?, ?> map, List<String> keys) {
        for (String key : keys) {
            Integer value = Convert.toInt(map.get(key), null);
            if (value != null && value > 0) return value;
        }
        return null;
    }
}
