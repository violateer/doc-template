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
 * <p>匹配规则：占位符 key 与声明相等即命中；声明不带命名空间时，{@code ${report.logo}}、
 * 循环体里的 {@code ${logo}} 都会命中 {@code logo}；声明带命名空间（如 {@code items.photo}）时，
 * 循环体里只写 {@code ${photo}} 也能命中。</p>
 *
 * @param field   字段名（可带命名空间，如 {@code report.logo}）
 * @param width   目标宽度（像素），为空表示按图片原始宽度
 * @param height  目标高度（像素），为空表示按原始高度；同时给宽高时按比例缩放到框内
 * @param align   单元格内水平对齐：{@code CENTER}（默认）/ {@code LEFT} / {@code RIGHT}
 * @param padding 图片四周留白（像素），默认 4
 */
public record DocTemplateImageSpec(String field, Integer width, Integer height, String align, Integer padding) {

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
        field = StrUtil.trimToEmpty(field);
        align = normalizeAlign(align);
        padding = padding == null ? DEFAULT_PADDING : Math.min(MAX_PADDING, Math.max(0, padding));
    }

    /** 老调用方式：只关心字段名与尺寸时用这个，对齐居中、留白取默认值。 */
    public DocTemplateImageSpec(String field, Integer width, Integer height) {
        this(field, width, height, null, null);
    }

    /** 对齐方式归一化：不认识的写法一律按居中处理。 */
    public static String normalizeAlign(String align) {
        if (StrUtil.isBlank(align)) return ALIGN_CENTER;
        return switch (align.trim().toUpperCase(Locale.ROOT)) {
            case "LEFT", "START" -> ALIGN_LEFT;
            case "RIGHT", "END" -> ALIGN_RIGHT;
            default -> ALIGN_CENTER;
        };
    }

    public boolean matches(String key) {
        if (StrUtil.isBlank(key) || StrUtil.isBlank(field)) return false;
        String target = key.trim();
        String name = field.trim();
        if (target.equalsIgnoreCase(name)) return true;
        String lowerTarget = target.toLowerCase(Locale.ROOT);
        String lowerName = name.toLowerCase(Locale.ROOT);
        // ${report.logo} 这种带命名空间的取值
        if (lowerTarget.endsWith("." + lowerName)) return true;
        // 声明写 items.photo，循环体里通常只写 ${photo}
        int dot = name.lastIndexOf('.');
        return dot >= 0 && lowerTarget.equals(lowerName.substring(dot + 1));
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
