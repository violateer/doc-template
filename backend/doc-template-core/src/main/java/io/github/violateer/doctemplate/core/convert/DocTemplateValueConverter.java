package io.github.violateer.doctemplate.core.convert;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 把一个字段的原始值转换成展示值。
 *
 * <p>宿主在容器里注册成 Bean 即可使用，{@link #code()} 就是配置里写的 {@code type}：</p>
 *
 * <pre>{@code
 * {"converters":[{"field":"status","type":"orderState","args":{"lang":"zh"}}]}
 * }</pre>
 *
 * <p>内置转换器（{@code date} / {@code number} / {@code boolean} / {@code percent} /
 * {@code upper} / {@code lower} / {@code trim} / {@code mask} / {@code dict}）由 Starter 提供，
 * 宿主同名实现会覆盖内置的那个。</p>
 *
 * <h2>单值与批量</h2>
 * <p>模板占位符（{@code ${amount|number:2}}）只会调 {@link #convert(Object, Map)}：一个占位符一个值。
 * 数据块的 {@code converters} 走 {@link #convertBatch(List, Map)}：整列（含明细行、嵌套数组）先收集起来
 * 一次交给你，**默认实现只是循环调 {@code convert}**，所以不覆盖它也能用。</p>
 *
 * <p>如果你的转换要访问外部资源（查库、调接口、读缓存），<b>请覆盖 {@code convertBatch}</b>：
 * 一次生成里同一个字段几百行只会调用你一次，而不是几百次。这也是这个接口存在的主要理由。</p>
 *
 * <p>约定：<b>转换失败就返回原值</b>，不要抛异常。渲染一个字段不该让整篇文档生成失败；
 * 参数写错这类配置问题由 Starter 在执行前校验。</p>
 */
public interface DocTemplateValueConverter {

    /** 配置里引用的名字，大小写不敏感，建议全小写。 */
    String code();

    /**
     * 转换单个值（模板占位符用这个）。
     *
     * @param value 原始值，可能为 {@code null}
     * @param args  配置里的 {@code args}（模板占位符里的写法也会并进来），不会为 {@code null}
     * @return 转换后的值；返回 {@code null} 表示「没有值」，渲染时输出空串
     */
    Object convert(Object value, Map<String, Object> args);

    /**
     * 批量转换（数据块 {@code converters} 用这个）。默认实现逐个调 {@link #convert(Object, Map)}，
     * 需要避免 N+1 的宿主覆盖它即可。
     *
     * <p>返回值必须与入参<b>等长且同序</b>（{@code result.get(i)} 对应 {@code values.get(i)}），
     * 长度对不上会直接报错，不会静默错位。入参可能含 {@code null}，也可能为空列表（空列表不会被调用）。</p>
     *
     * @param values 同一字段（同一转换器、同一组参数）在当前数据块里出现的全部原始值
     * @param args   配置里的 {@code args}
     * @return 与 {@code values} 一一对应的转换结果
     */
    default List<Object> convertBatch(List<Object> values, Map<String, Object> args) {
        List<Object> converted = new ArrayList<>(values.size());
        for (Object value : values) {
            converted.add(convert(value, args));
        }
        return converted;
    }
}
