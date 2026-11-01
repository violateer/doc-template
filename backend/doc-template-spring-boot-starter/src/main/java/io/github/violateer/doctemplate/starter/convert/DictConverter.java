package io.github.violateer.doctemplate.starter.convert;

import cn.hutool.core.convert.Convert;
import cn.hutool.core.util.StrUtil;
import io.github.violateer.doctemplate.core.convert.DocTemplateValueConverter;
import io.github.violateer.doctemplate.starter.service.DocTemplateDictService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code dict}：按字典编码把值换成显示名。
 *
 * <p>配置：{@code {"type":"dict","args":{"code":"order_status"}}}；模板里写
 * {@code ${status|dict(order_status)}} 效果一样。</p>
 *
 * <p>字典里查不到时由 {@code args.fallback} 决定：默认 {@code original}（原样输出），
 * 也可以写 {@code blank}（输出空串）或任意字面量（例如 {@code 未知}）。
 * 字典编码写错不会抛异常，只会走 fallback —— 少建一本字典不该让整篇文档生成失败。</p>
 *
 * <p>批量转换覆盖了 {@link #convertBatch(List, Map)}：整列只取一次字典，再逐值查内存里的映射。
 * 这既是「一次生成每本字典只查一次库」的实现，也是宿主自定义转换器该照着写的样板。</p>
 */
public class DictConverter implements DocTemplateValueConverter {

    private final DocTemplateDictService dictService;

    public DictConverter(DocTemplateDictService dictService) {
        this.dictService = dictService;
    }

    @Override
    public String code() {
        return "dict";
    }

    @Override
    public Object convert(Object value, Map<String, Object> args) {
        if (value == null) return null;
        return resolve(value, labelsOf(args), args);
    }

    @Override
    public List<Object> convertBatch(List<Object> values, Map<String, Object> args) {
        Map<String, String> labels = labelsOf(args);
        List<Object> converted = new ArrayList<>(values.size());
        for (Object value : values) {
            converted.add(value == null ? null : resolve(value, labels, args));
        }
        return converted;
    }

    /** 一本字典的映射；字典编码缺失是配置错误，直接抛（注册表会带上转换器名）。 */
    private Map<String, String> labelsOf(Map<String, Object> args) {
        String dictCode = StrUtil.blankToDefault(Convert.toStr(args.get("code")), "").trim();
        if (dictCode.isEmpty()) {
            throw new IllegalArgumentException("dict 转换器缺少 args.code（字典编码）");
        }
        return dictService.labels(dictCode);
    }

    private Object resolve(Object value, Map<String, String> labels, Map<String, Object> args) {
        String key = Convert.toStr(value, "");
        String label = labels.get(key);
        if (label == null) label = labels.get(key.trim());
        if (label != null) return label;

        String fallback = StrUtil.blankToDefault(Convert.toStr(args.get("fallback")), "original").trim();
        if ("original".equalsIgnoreCase(fallback) || "keep".equalsIgnoreCase(fallback)) return value;
        if ("blank".equalsIgnoreCase(fallback) || "empty".equalsIgnoreCase(fallback)) return "";
        return fallback;
    }
}
