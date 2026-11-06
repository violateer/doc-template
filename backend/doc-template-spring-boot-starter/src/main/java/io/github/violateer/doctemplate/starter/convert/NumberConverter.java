package io.github.violateer.doctemplate.starter.convert;

import cn.hutool.core.convert.Convert;
import cn.hutool.core.util.StrUtil;
import io.github.violateer.doctemplate.core.convert.DocTemplateValueConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code number}：千分位 / 固定小数位 / 前后缀。
 *
 * <p>参数全部可选：</p>
 * <ul>
 *   <li>{@code scale} 小数位，不写保留原精度；</li>
 *   <li>{@code grouping} 是否千分位，默认 {@code false}（默认打开会把年份 {@code 2026} 写成 {@code 2,026}）；</li>
 *   <li>{@code rounding} 舍入方式，默认 {@code half_up}，可选 {@code half_even} / {@code down} / {@code up}；</li>
 *   <li>{@code prefix} / {@code suffix} 前后缀，例如 {@code ¥}。</li>
 * </ul>
 */
final class NumberConverter implements DocTemplateValueConverter {

    private static final Logger log = LoggerFactory.getLogger(NumberConverter.class);
    /** 认识的舍入方式（含同义词）。 */
    private static final Set<String> ROUNDINGS =
            Set.of("half_up", "half-up", "half_even", "half-even", "banker", "down", "floor", "truncate", "up", "ceiling");
    /** 同一个写错的 rounding 只告警一次：明细几百行时逐行 warn 会把日志刷满。 */
    private static final Set<String> WARNED_ROUNDINGS = ConcurrentHashMap.newKeySet();

    @Override
    public String code() {
        return "number";
    }

    @Override
    public Object convert(Object value, Map<String, Object> args) {
        if (value == null) return null;
        BigDecimal number = decimal(value);
        if (number == null) return value;

        int scale = Convert.toInt(args.get("scale"), -1);
        boolean grouping = Convert.toBool(args.get("grouping"), false);
        DecimalFormat format = new DecimalFormat();
        format.setDecimalFormatSymbols(DecimalFormatSymbols.getInstance(Locale.ROOT));
        format.setGroupingUsed(grouping);
        format.setRoundingMode(rounding(args.get("rounding")));
        int digits = scale >= 0 ? scale : Math.max(0, number.stripTrailingZeros().scale());
        format.setMinimumFractionDigits(digits);
        format.setMaximumFractionDigits(digits);

        String prefix = StrUtil.blankToDefault(Convert.toStr(args.get("prefix")), "");
        String suffix = StrUtil.blankToDefault(Convert.toStr(args.get("suffix")), "");
        return prefix + format.format(scale >= 0 ? number.setScale(scale, rounding(args.get("rounding"))) : number) + suffix;
    }

    private static RoundingMode rounding(Object raw) {
        String text = StrUtil.blankToDefault(Convert.toStr(raw), "half_up").trim().toLowerCase(Locale.ROOT);
        // 认不出来不报错（金额格式化写错参数不该让整份文档失败），但告警一次，别静默按 half_up 舍入
        if (!ROUNDINGS.contains(text) && WARNED_ROUNDINGS.add(text)) {
            log.warn("number 的 rounding「{}」不认识，已按 half_up 舍入；可选：half_up / half_even（banker）"
                    + " / down（truncate）/ up（ceiling）", text);
        }
        return switch (text) {
            case "half_even", "half-even", "banker" -> RoundingMode.HALF_EVEN;
            case "down", "floor", "truncate" -> RoundingMode.DOWN;
            case "up", "ceiling" -> RoundingMode.UP;
            default -> RoundingMode.HALF_UP;
        };
    }

    /** 数字或数字字符串转 {@link BigDecimal}；布尔、日期之类返回 {@code null} 表示「不处理」。 */
    private static BigDecimal decimal(Object value) {
        if (value instanceof BigDecimal decimal) return decimal;
        if (value instanceof Number number) {
            double d = number.doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) return null;
            return new BigDecimal(number.toString());
        }
        if (value instanceof Boolean) return null;
        String text = Convert.toStr(value, "").trim().replace(",", "").replace("_", "");
        if (text.isEmpty()) return null;
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException notNumber) {
            return null;
        }
    }
}
