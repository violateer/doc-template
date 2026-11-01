package io.github.violateer.doctemplate.example;

import io.github.violateer.doctemplate.core.convert.DocTemplateValueConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 演示用转换器：物料编码 → 物料名称，模拟「要查库/调接口」的宿主转换器。
 *
 * <p>数据块配置里写 {@code {"field":"material_code","type":"demoSku"}} 即可，模板里也能写
 * {@code ${material_code|demoSku}}。注册成 Spring Bean 后即被发现，
 * {@code GET /api/doc-template/system/converters} 能看到 {@code demoSku}。</p>
 *
 * <p><b>重点在 {@link #convertBatch}</b>：数据块的 {@code converters} 会把整列值一次交进来，
 * 这里只打印一次日志。如果没覆盖它，走默认实现就是逐条 {@code convert}，明细 500 行 = 500 次查询。
 * 真实场景把 {@link #NAMES} 换成 mapper / 远程调用即可，方法签名不用变。</p>
 */
@Component
public class DemoSkuConverter implements DocTemplateValueConverter {

    private static final Logger log = LoggerFactory.getLogger(DemoSkuConverter.class);

    /** 假装是数据库里的物料表。 */
    private static final Map<String, String> NAMES = new LinkedHashMap<>();

    static {
        NAMES.put("M-1001", "不锈钢法兰 DN100");
        NAMES.put("M-1002", "石墨缠绕垫片 DN100");
        NAMES.put("M-1003", "六角螺栓套装 M20×90");
        NAMES.put("M-1004", "球阀 Q41F-16P DN50");
    }

    /** 批量被调用的次数，用来在日志里对比「一次 vs N 次」。 */
    private final AtomicInteger batchCalls = new AtomicInteger();

    @Override
    public String code() {
        return "demoSku";
    }

    @Override
    public Object convert(Object value, Map<String, Object> args) {
        return nameOf(value);
    }

    @Override
    public List<Object> convertBatch(List<Object> values, Map<String, Object> args) {
        int call = batchCalls.incrementAndGet();
        log.info("demoSku 第 {} 次批量转换：一次收到 {} 个值（逐条转换的话就是 {} 次查询）",
                call, values.size(), values.size());
        List<Object> converted = new ArrayList<>(values.size());
        for (Object value : values) {
            converted.add(nameOf(value));
        }
        return converted;
    }

    /** 查不到就返回原值 —— 转换失败不该让整篇文档生成失败。 */
    private static Object nameOf(Object value) {
        if (value == null) return null;
        String name = NAMES.get(String.valueOf(value).trim());
        return name == null ? value : name;
    }
}
