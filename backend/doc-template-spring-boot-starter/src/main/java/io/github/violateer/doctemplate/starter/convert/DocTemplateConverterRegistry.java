package io.github.violateer.doctemplate.starter.convert;

import cn.hutool.core.convert.Convert;
import cn.hutool.core.exceptions.ExceptionUtil;
import cn.hutool.core.util.StrUtil;
import io.github.violateer.doctemplate.core.convert.DocTemplateValueConverter;
import io.github.violateer.doctemplate.starter.support.DocTemplateJson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 转换器注册表：按 {@code code} 找到转换器并执行，同时负责两处接线。
 *
 * <ul>
 *   <li><b>数据块配置</b>：{@code data_source_config.converters} 声明的字段转换，在取数完成后
 *       一次性作用到结果上（见 {@link #apply(Object, Object)}）。走的是
 *       {@link DocTemplateValueConverter#convertBatch(List, Map)}：先把整列值收集齐，再按
 *       「转换器 + 参数」分组各调一次，明细几百行也只调一次；</li>
 *   <li><b>模板占位符</b>：{@code ${amount|number:2}} 里的转换链，在渲染时逐个执行
 *       （见 {@link #parseChain(String)}），一个占位符一个值，走 {@code convert}。</li>
 * </ul>
 *
 * <p>内置转换器由 {@link BuiltinConverters} 提供，宿主注册的同名 Bean 会覆盖内置的那个
 * （启动时打一条 info，避免「我明明注册了却不生效」）。</p>
 */
public class DocTemplateConverterRegistry {

    private static final Logger log = LoggerFactory.getLogger(DocTemplateConverterRegistry.class);

    /**
     * 位置参数名：模板里写 {@code ${createdAt|date(yyyy-MM-dd)}} 时，括号里的第一个值按这张表
     * 映射成具名参数，转换器拿到的永远是 {@code Map}，不用关心调用方写的是位置还是具名。
     */
    private static final Map<String, List<String>> POSITIONAL = Map.of(
            "date", List.of("pattern"),
            "number", List.of("scale", "grouping"),
            "boolean", List.of("trueText", "falseText"),
            "percent", List.of("scale", "multiply"),
            "mask", List.of("keepStart", "keepEnd", "mask"),
            "dict", List.of("code"));

    /** 模板占位符里解析出来的一次转换调用。 */
    public record ConverterCall(String type, Map<String, Object> args) {
    }

    /** 数据块配置里的一条字段转换规则。 */
    private record Rule(String field, String type, Map<String, Object> args) {
    }

    /** 待转换的一个位置：原始值 + 往结果里写回的办法（结构已经复制好，这里只补值）。 */
    private record Target(Rule rule, Object value, Consumer<Object> setter) {
    }

    /** 批量调用的分组键：同一个转换器 + 同一组参数就算一批，字段名不同也能合并成一次调用。 */
    private record CallKey(String type, Map<String, Object> args) {
    }

    private final Map<String, DocTemplateValueConverter> converters;

    /** 没有注册任何转换器（渲染器单独使用时用它兜底）。 */
    public DocTemplateConverterRegistry() {
        this(List.of(), List.of());
    }

    /**
     * @param builtins 内置转换器
     * @param beans    容器里的转换器 Bean（含内置的 dict），同名时覆盖内置
     */
    public DocTemplateConverterRegistry(Collection<DocTemplateValueConverter> builtins,
                                        Collection<DocTemplateValueConverter> beans) {
        Map<String, DocTemplateValueConverter> map = new LinkedHashMap<>();
        register(map, builtins, false);
        register(map, beans, true);
        this.converters = Map.copyOf(map);
    }

    private static void register(Map<String, DocTemplateValueConverter> target,
                                 Collection<DocTemplateValueConverter> source, boolean override) {
        if (source == null) return;
        for (DocTemplateValueConverter converter : source) {
            if (converter == null) continue;
            String code = StrUtil.blankToDefault(converter.code(), "").trim();
            if (code.isEmpty()) {
                throw new IllegalStateException("转换器编码不能为空：" + converter.getClass().getName());
            }
            DocTemplateValueConverter existing = target.get(code);
            if (existing != null && !override) {
                throw new IllegalStateException("转换器编码重复：" + code);
            }
            if (existing != null && existing != converter) {
                log.info("转换器「{}」由 {} 覆盖（原实现 {}）", code,
                        converter.getClass().getSimpleName(), existing.getClass().getSimpleName());
            }
            target.put(code, converter);
        }
    }

    /**
     * 已注册的转换器编码，供管理页做下拉选项。
     *
     * <p>返回<b>注册时声明的写法</b>（宿主 {@code code()} 里写的大小写），并按该写法精确排序：
     * 编码是严格区分大小写的标识符，管理页把它直接当配置值用，不能在这里换一种写法。</p>
     */
    public List<String> codes() {
        return converters.keySet().stream().sorted().toList();
    }

    public boolean supports(String code) {
        return code != null && converters.containsKey(code.trim());
    }

    /**
     * 执行一次转换（模板占位符用）。
     *
     * <p>未知编码直接抛错（这是配置问题，越早暴露越好）；转换器自身抛出的异常会带上编码重新抛出，
     * 渲染失败时能从生成记录里一眼看出是哪个转换器。</p>
     */
    public Object convert(Object value, String type, Map<String, Object> args) {
        if (StrUtil.isBlank(type)) return value;
        DocTemplateValueConverter converter = require(type);
        try {
            return converter.convert(value, args == null ? Map.of() : args);
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("转换器「" + type + "」执行失败："
                    + ExceptionUtil.getRootCauseMessage(error), error);
        }
    }

    /**
     * 批量执行一次转换（数据块 {@code converters} 用）。
     *
     * <p>返回值长度与 {@code values} 对不上时直接报错：宁可失败，也不能让结果整体错位一格。</p>
     */
    public List<Object> convertBatch(List<Object> values, String type, Map<String, Object> args) {
        if (values == null || values.isEmpty()) return List.of();
        if (StrUtil.isBlank(type)) return new ArrayList<>(values);
        DocTemplateValueConverter converter = require(type);
        List<Object> converted;
        try {
            converted = converter.convertBatch(values, args == null ? Map.of() : args);
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("转换器「" + type + "」执行失败："
                    + ExceptionUtil.getRootCauseMessage(error), error);
        }
        if (converted == null || converted.size() != values.size()) {
            throw new IllegalStateException("转换器「" + type + "」的批量转换返回了 "
                    + (converted == null ? 0 : converted.size()) + " 个结果，输入是 " + values.size()
                    + " 个（必须一一对应）");
        }
        return converted;
    }

    /**
     * 按编码取转换器。编码**严格区分大小写**：配置里写 {@code DemoSku} 而 {@code code()} 声明的是
     * {@code demoSku} 时直接报「未知的转换器」，并列出可用编码，而不是猜一个。
     */
    private DocTemplateValueConverter require(String type) {
        String code = type.trim();
        DocTemplateValueConverter converter = converters.get(code);
        if (converter == null) {
            throw new IllegalArgumentException("未知的转换器「" + type + "」，可用："
                    + (converters.isEmpty() ? "（没有注册任何转换器）" : String.join("、", codes()))
                    + "；转换器编码区分大小写");
        }
        return converter;
    }

    // ------------------------------------------------------------------ 数据块配置

    /**
     * 把 {@code converters} 配置作用到取数结果上。
     *
     * <p>支持两种写法：</p>
     * <pre>{@code
     * "converters": [{"field":"status","type":"dict","args":{"code":"order_status"}}]
     * "converters": {"status":{"type":"dict","args":{"code":"order_status"}}}
     * }</pre>
     *
     * <p>{@code field} 可以写成 {@code "a,b"} 一次覆盖多个字段。结果里的嵌套对象和数组都会递归处理，
     * 所以 Provider 返回 {@code {report:{...}, items:[{...}]}} 时内层字段同样能转。</p>
     *
     * <p>分两步走：先复制出结果结构、顺手登记每个待转换的标量位置；再按「转换器 + 参数」分组
     * 批量执行。这样明细几百行、嵌套多层，同一个转换器也只会被调用一次（除非它自己要求逐条）。</p>
     */
    public Object apply(Object result, Object spec) {
        List<Rule> rules = rules(spec);
        if (rules.isEmpty() || result == null) return result;
        List<Target> targets = new ArrayList<>();
        Object shaped = walk(result, rules, targets);
        runBatch(targets);
        return shaped;
    }

    private Object walk(Object node, List<Rule> rules, List<Target> targets) {
        if (node instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                Object value = entry.getValue();
                Rule rule = ruleFor(key, rules);
                if (value instanceof Map<?, ?> || value instanceof Collection<?>) {
                    Object nested = walk(value, rules, targets);
                    if (rule == null) {
                        copy.put(key, nested);
                    } else {
                        // 占位等批量结果回填；LinkedHashMap 对已存在的键 put 不改变顺序
                        copy.put(key, null);
                        targets.add(new Target(rule, nested, converted -> copy.put(key, converted)));
                    }
                } else if (rule == null || value == null) {
                    copy.put(key, value);
                } else {
                    copy.put(key, null);
                    targets.add(new Target(rule, value, converted -> copy.put(key, converted)));
                }
            }
            return copy;
        }
        if (node instanceof Collection<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (Object item : list) copy.add(walk(item, rules, targets));
            return copy;
        }
        return node;
    }

    /** 按「转换器 + 参数」分组，每组只调一次 {@code convertBatch}，再按顺序回填。 */
    private void runBatch(List<Target> targets) {
        if (targets.isEmpty()) return;
        Map<CallKey, List<Target>> groups = new LinkedHashMap<>();
        for (Target target : targets) {
            Rule rule = target.rule();
            CallKey key = new CallKey(rule.type().trim(), rule.args());
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(target);
        }
        for (Map.Entry<CallKey, List<Target>> entry : groups.entrySet()) {
            List<Target> group = entry.getValue();
            List<Object> values = new ArrayList<>(group.size());
            for (Target target : group) values.add(target.value());
            List<Object> converted = convertBatch(values, entry.getKey().type(), entry.getKey().args());
            for (int i = 0; i < group.size(); i++) {
                group.get(i).setter().accept(converted.get(i));
            }
        }
    }

    /** 按字段名找转换规则：严格区分大小写，配置里写 {@code Material_Code} 不等于 {@code material_code}。 */
    private static Rule ruleFor(String key, List<Rule> rules) {
        for (Rule rule : rules) {
            if (rule.field().equals(key)) return rule;
        }
        return null;
    }

    private static List<Rule> rules(Object spec) {
        List<Rule> rules = new ArrayList<>();
        Object parsed = spec;
        if (parsed instanceof String text) {
            if (StrUtil.isBlank(text)) return rules;
            parsed = DocTemplateJson.read(text);
        }
        if (parsed instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getValue() instanceof Map<?, ?> definition) {
                    Rule rule = rule(String.valueOf(entry.getKey()), definition);
                    if (rule != null) rules.add(rule);
                }
            }
        } else if (parsed instanceof Collection<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> definition)) continue;
                String field = Convert.toStr(definition.get("field"), "");
                for (String part : field.split("[,，]")) {
                    Rule rule = rule(part, definition);
                    if (rule != null) rules.add(rule);
                }
            }
        }
        return rules;
    }

    private static Rule rule(String field, Map<?, ?> definition) {
        String name = StrUtil.blankToDefault(field, "").trim();
        if (name.isEmpty()) return null;
        String type = StrUtil.blankToDefault(Convert.toStr(definition.get("type")), "").trim();
        if (type.isEmpty()) return null;
        Object args = definition.get("args");
        Map<String, Object> values = new LinkedHashMap<>();
        if (args instanceof Map<?, ?> map) {
            map.forEach((key, value) -> values.put(String.valueOf(key), value));
        } else if (args instanceof String text && StrUtil.isNotBlank(text)) {
            values.putAll(DocTemplateJson.readMap(text));
        }
        return new Rule(name, type, values);
    }

    // ------------------------------------------------------------------ 占位符转换链

    /**
     * 解析 {@code ${amount|number:2|upper}} 里的 {@code amount|number:2|upper}。
     *
     * <p>每个转换器支持三种写法，效果一样：{@code number}、{@code number:2}（位置参数）、
     * {@code number(scale=2, grouping=true)}（具名参数）。位置参数的顺序见 {@link #POSITIONAL}，
     * 宿主自定义转换器建议用具名参数。</p>
     */
    public static List<ConverterCall> parseChain(String chain) {
        List<ConverterCall> calls = new ArrayList<>();
        if (StrUtil.isBlank(chain)) return calls;
        for (String part : chain.split("\\|")) {
            String text = part.trim();
            if (text.isEmpty()) continue;
            int open = text.indexOf('(');
            String type;
            String inner;
            String separator;
            if (open > 0 && text.endsWith(")")) {
                type = text.substring(0, open).trim();
                inner = text.substring(open + 1, text.length() - 1);
                separator = ",";
            } else {
                int colon = text.indexOf(':');
                type = (colon >= 0 ? text.substring(0, colon) : text).trim();
                inner = colon >= 0 ? text.substring(colon + 1) : "";
                separator = ":";
            }
            if (type.isEmpty()) continue;
            calls.add(new ConverterCall(type, argsOf(type, inner, separator)));
        }
        return calls;
    }

    private static Map<String, Object> argsOf(String type, String inner, String separator) {
        Map<String, Object> args = new LinkedHashMap<>();
        if (StrUtil.isBlank(inner)) return args;
        // 位置参数名按内置编码查：编码严格区分大小写，这里也精确匹配（写错大小写就当没有位置参数名，
        // 值会落到 _1 / _2 上，随后由 require() 报「未知的转换器」）
        List<String> positional = POSITIONAL.getOrDefault(type, List.of());
        String[] parts = inner.split(separator, -1);
        int index = 0;
        for (String raw : parts) {
            String item = raw.trim();
            int equals = item.indexOf('=');
            if (equals > 0) {
                // 具名参数：值里允许出现等号后面的原样内容，例如 date(pattern=yyyy-MM-dd)
                args.put(item.substring(0, equals).trim(), item.substring(equals + 1).trim());
            } else if (!item.isEmpty()) {
                args.put(index < positional.size() ? positional.get(index) : "_" + (index + 1), item);
            }
            index++;
        }
        return args;
    }
}
