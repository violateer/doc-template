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
 * 声明「某个占位符要渲染成二维码」。
 *
 * <p>配置写在数据块的 {@code data_source_config} 里，三种数据源（JAVA / SQL / API）通用：</p>
 *
 * <pre>
 * {
 *   "sql": "select verify_url, logo from qc_report where report_no = :reportNo",
 *   "single": true,
 *   "qrCodes": [
 *     { "field": "verify_url", "width": 140, "logoField": "logo" },
 *     { "field": "trace_url", "logoUrl": "https://minio.example.com/logo.png" }
 *   ]
 * }
 * </pre>
 *
 * <p>占位符的值就是二维码内容（按文本编码）。中间的小图两种来源，二选一：</p>
 * <ul>
 *   <li>{@code logoUrl}：固定地址，支持 http(s) / data URI / Base64；</li>
 *   <li>{@code logoField}：同一数据块里的另一个字段，值同样可以是地址或 Base64。
 *       写字段名即可，不需要带块编码；单条数据块里也能取到同块字段。</li>
 * </ul>
 *
 * <p>也接受 {@code qr_codes} / {@code qrcodes} 两种写法，以及字符串简写
 * （{@code "qrCodes": "verify_url,trace_url"}）。</p>
 *
 * @param field     字段名（可带命名空间，如 {@code report.verify_url}）
 * @param width     目标宽度（像素），为空时默认 140
 * @param height    目标高度（像素），为空时按宽度等比（二维码是正方形）
 * @param align     单元格内水平对齐：{@code CENTER}（默认）/ {@code LEFT} / {@code RIGHT}
 * @param padding   图片四周留白（像素），默认 4
 * @param level     纠错级别 {@code L / M / Q / H}；不写时带中间小图用 {@code H}，不带用 {@code M}
 * @param logoUrl   中间小图的固定地址
 * @param logoField 中间小图取自同块的哪个字段
 * @param logoRatio 中间小图占二维码边长的比例，默认 0.22，取值区间 0.1 ~ 0.35
 */
public record DocTemplateQrCodeSpec(String field, Integer width, Integer height, String align, Integer padding,
                                    String level, String logoUrl, String logoField, Double logoRatio) {

    /** 数据块配置里使用的键名（另外兼容 qr_codes / qrcodes）。 */
    public static final String CONFIG_KEY = "qrCodes";

    /** 不写尺寸时二维码的默认边长（像素）。 */
    public static final int DEFAULT_SIZE = 140;
    public static final double DEFAULT_LOGO_RATIO = 0.22;
    private static final double MIN_LOGO_RATIO = 0.1;
    private static final double MAX_LOGO_RATIO = 0.35;
    private static final int MAX_SIZE = 4000;

    private static final List<String> CONFIG_KEYS = List.of("qrCodes", "qr_codes", "qrcodes", "qrCode");
    private static final List<String> FIELD_KEYS = List.of("field", "name", "key", "path");
    private static final List<String> WIDTH_KEYS = List.of("width", "w", "size");
    private static final List<String> HEIGHT_KEYS = List.of("height", "h");
    private static final List<String> ALIGN_KEYS = List.of("align", "alignment", "position");
    private static final List<String> PADDING_KEYS = List.of("padding", "margin", "inset");
    private static final List<String> LEVEL_KEYS = List.of("level", "errorCorrection", "error_correction", "ec");
    private static final List<String> LOGO_URL_KEYS = List.of("logoUrl", "logo_url", "logoImage", "logo_image");
    private static final List<String> LOGO_FIELD_KEYS = List.of("logoField", "logo_field", "logoFrom", "logo_from");
    private static final List<String> LOGO_RATIO_KEYS = List.of("logoRatio", "logo_ratio", "logoScale", "logo_scale");

    public DocTemplateQrCodeSpec {
        field = StrUtil.trimToEmpty(field);
        align = DocTemplateImageSpec.normalizeAlign(align);
        padding = padding == null ? DocTemplateImageSpec.DEFAULT_PADDING
                : Math.min(60, Math.max(0, padding));
        width = clampSize(width);
        height = clampSize(height);
        if (width == null && height == null) {
            width = DEFAULT_SIZE;
            height = DEFAULT_SIZE;
        }
        level = normalizeLevel(level);
        logoUrl = StrUtil.trimToEmpty(logoUrl);
        logoField = StrUtil.trimToEmpty(logoField);
        logoRatio = logoRatio == null || logoRatio.isNaN()
                ? DEFAULT_LOGO_RATIO
                : Math.min(MAX_LOGO_RATIO, Math.max(MIN_LOGO_RATIO, logoRatio));
    }

    /** 是否配了中间小图。 */
    public boolean hasLogo() {
        return !logoUrl.isEmpty() || !logoField.isEmpty();
    }

    /** 转成渲染器认识的图片声明：尺寸、对齐、留白走同一套逻辑。 */
    public DocTemplateImageSpec toImageSpec() {
        return new DocTemplateImageSpec(field, width, height, align, padding, this);
    }

    /** 纠错级别归一化：只认 L / M / Q / H，其余返回 {@code null} 表示按是否有小图自动选。 */
    public static String normalizeLevel(String level) {
        if (StrUtil.isBlank(level)) return null;
        String text = level.trim().toUpperCase(Locale.ROOT);
        return switch (text) {
            case "L", "M", "Q", "H" -> text;
            default -> null;
        };
    }

    /**
     * 从数据块配置里取出二维码字段声明，找不到时返回空列表。
     */
    public static List<DocTemplateQrCodeSpec> fromConfig(Map<String, Object> config) {
        if (config == null || config.isEmpty()) return List.of();
        for (String key : CONFIG_KEYS) {
            if (config.containsKey(key)) return parse(config.get(key));
        }
        return List.of();
    }

    /**
     * 解析配置值，支持字符串 / 字符串数组 / 对象 / 对象数组。
     */
    public static List<DocTemplateQrCodeSpec> parse(Object node) {
        List<DocTemplateQrCodeSpec> specs = new ArrayList<>();
        collect(node, specs);
        return dedupe(specs);
    }

    private static void collect(Object node, List<DocTemplateQrCodeSpec> specs) {
        if (node == null) return;
        if (node instanceof Map<?, ?> map) {
            String field = firstText(map, FIELD_KEYS);
            if (StrUtil.isNotBlank(field)) {
                specs.add(specOf(field, map));
                return;
            }
            // {"verify_url": 140} / {"verify_url": {"width": 140, "logoField": "logo"}}
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = Convert.toStr(entry.getKey(), "").trim();
                if (key.isEmpty()) continue;
                Object value = entry.getValue();
                if (value instanceof Map<?, ?> sub) {
                    specs.add(specOf(key, sub));
                } else if (value instanceof Number) {
                    Integer size = Convert.toInt(value, null);
                    specs.add(new DocTemplateQrCodeSpec(key, size, size, null, null, null, null, null, null));
                } else if (value instanceof Boolean enabled) {
                    if (enabled) specs.add(new DocTemplateQrCodeSpec(key, null, null, null, null, null, null, null, null));
                } else {
                    specs.add(new DocTemplateQrCodeSpec(key, null, null, null, null, null, null, null, null));
                }
            }
            return;
        }
        if (node instanceof Iterable<?> items) {
            for (Object item : items) collect(item, specs);
            return;
        }
        // "verify_url,trace_url" / "verify_url:140"
        for (String part : Convert.toStr(node, "").split(",")) {
            String text = part.trim();
            if (text.isEmpty()) continue;
            String field = text;
            Integer size = null;
            int at = text.indexOf(':');
            if (at > 0) {
                field = text.substring(0, at).trim();
                size = Convert.toInt(text.substring(at + 1).trim(), null);
            }
            if (!field.isEmpty()) {
                specs.add(new DocTemplateQrCodeSpec(field, size, size, null, null, null, null, null, null));
            }
        }
    }

    private static DocTemplateQrCodeSpec specOf(String field, Map<?, ?> map) {
        Integer padding = null;
        for (String key : PADDING_KEYS) {
            Integer value = Convert.toInt(map.get(key), null);
            if (value != null) {
                padding = value;
                break;
            }
        }
        Double ratio = null;
        for (String key : LOGO_RATIO_KEYS) {
            Double value = Convert.toDouble(map.get(key), null);
            if (value != null) {
                ratio = value;
                break;
            }
        }
        return new DocTemplateQrCodeSpec(field, firstInt(map, WIDTH_KEYS), firstInt(map, HEIGHT_KEYS),
                firstText(map, ALIGN_KEYS), padding, firstText(map, LEVEL_KEYS),
                firstText(map, LOGO_URL_KEYS), firstText(map, LOGO_FIELD_KEYS), ratio);
    }

    private static List<DocTemplateQrCodeSpec> dedupe(List<DocTemplateQrCodeSpec> specs) {
        Set<String> seen = new LinkedHashSet<>();
        List<DocTemplateQrCodeSpec> result = new ArrayList<>(specs.size());
        for (DocTemplateQrCodeSpec spec : specs) {
            if (spec == null || StrUtil.isBlank(spec.field())) continue;
            if (seen.add(spec.field().toLowerCase(Locale.ROOT))) result.add(spec);
        }
        return result;
    }

    private static Integer clampSize(Integer value) {
        if (value == null || value <= 0) return null;
        return Math.min(MAX_SIZE, value);
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
