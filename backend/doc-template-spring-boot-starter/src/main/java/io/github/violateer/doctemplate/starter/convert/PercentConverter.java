package io.github.violateer.doctemplate.starter.convert;

import cn.hutool.core.convert.Convert;
import cn.hutool.core.util.StrUtil;
import io.github.violateer.doctemplate.core.convert.DocTemplateValueConverter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Map;

/**
 * {@code percent}：小数转百分比。
 *
 * <p>参数：{@code scale}（小数位，默认 2）、{@code symbol}（默认 {@code %}）、
 * {@code multiply}（是否乘 100，默认 {@code true}）。数据库里已经存成 {@code 12.5} 这种
 * 「已经是百分数」的值时写 {@code multiply=false}。</p>
 */
final class PercentConverter implements DocTemplateValueConverter {

    @Override
    public String code() {
        return "percent";
    }

    @Override
    public Object convert(Object value, Map<String, Object> args) {
        if (value == null) return null;
        BigDecimal number = decimal(value);
        if (number == null) return value;
        int scale = Math.max(0, Convert.toInt(args.get("scale"), 2));
        String symbol = StrUtil.blankToDefault(Convert.toStr(args.get("symbol")), "%");
        if (Convert.toBool(args.get("multiply"), true)) number = number.multiply(BigDecimal.valueOf(100));
        return number.setScale(scale, RoundingMode.HALF_UP).toPlainString() + symbol;
    }

    private static BigDecimal decimal(Object value) {
        if (value instanceof BigDecimal decimal) return decimal;
        if (value instanceof Number number) {
            double d = number.doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) return null;
            return new BigDecimal(number.toString());
        }
        if (value instanceof Boolean) return null;
        String text = Convert.toStr(value, "").trim().replace(",", "").replace("%", "");
        if (text.isEmpty()) return null;
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException notNumber) {
            return null;
        }
    }
}
