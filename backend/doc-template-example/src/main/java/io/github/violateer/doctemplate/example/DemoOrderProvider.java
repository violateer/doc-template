package io.github.violateer.doctemplate.example;

import io.github.violateer.doctemplate.core.datasource.DocumentDataProvider;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 演示用 Provider：返回一份硬编码的销售订单（主信息 + 明细行），不查任何库。
 *
 * <p>对应数据块配置 {@code {"provider":"demoOrder"}}。注册成 Spring Bean 后即被发现，
 * {@code GET /api/doc-template/system/providers} 能看到 {@code demoOrder}。</p>
 *
 * <p>模板里按块编码取数（示例块编码 {@code demoProvider}）：</p>
 * <pre>
 * ${demoProvider.order_no}                 主信息单值
 * {{#each demoProvider.items}}             明细行（DOCX 表格行 / XLSX 整行）
 *   ${item_name} ${quantity} ${amount}
 * {{/each}}
 * </pre>
 *
 * <p>参数映射里配了 {@code orderNo -> $request.orderNo} 时，生成时传的 {@code orderNo} 会盖掉默认单号；
 * 不传就用 {@code SO-2026-0001}，所以这个数据块永远不会因为缺参数而失败。</p>
 */
@Component
public class DemoOrderProvider implements DocumentDataProvider {

    /** 不传 orderNo 时使用的默认单号。 */
    private static final String DEFAULT_ORDER_NO = "SO-2026-0001";

    @Override
    public String getCode() {
        return "demoOrder";
    }

    @Override
    public Object execute(Map<String, Object> params) {
        String orderNo = text(params == null ? null : params.get("orderNo"));
        if (orderNo.isEmpty()) {
            orderNo = DEFAULT_ORDER_NO;
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("order_no", orderNo);
        data.put("customer_name", "杭州云栖智造有限公司");
        data.put("contact", "王工 138-0000-0000");
        data.put("order_date", "2026-10-28");
        data.put("delivery_date", "2026-11-15");
        data.put("sales_name", "李明");
        data.put("amount", new BigDecimal("19640.00"));
        data.put("remark", "数据来自 DemoOrderProvider（JAVA 数据块），全部为代码内造的假数据");
        data.put("data_source", "JAVA Provider: demoOrder");
        data.put("items", items());
        return data;
    }

    /** 明细行：三个物料，金额 = 数量 × 单价。 */
    private List<Map<String, Object>> items() {
        List<Map<String, Object>> items = new ArrayList<>(3);
        items.add(line(1, "不锈钢法兰", "DN100 / PN16 / 304", 120, "件", "86.50"));
        items.add(line(2, "石墨缠绕垫片", "DN100 / PN16", 240, "片", "12.80"));
        items.add(line(3, "六角螺栓套装", "M20×90 / 8.8级", 480, "套", "3.60"));
        return items;
    }

    private Map<String, Object> line(int no, String name, String spec, int quantity, String unit, String price) {
        BigDecimal unitPrice = new BigDecimal(price);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("line_no", no);
        row.put("item_name", name);
        row.put("spec", spec);
        row.put("quantity", quantity);
        row.put("unit", unit);
        row.put("unit_price", unitPrice);
        row.put("amount", unitPrice.multiply(BigDecimal.valueOf(quantity)));
        return row;
    }

    /** 参数可能是 null / 空串 / 其他类型，统一转成去空白的字符串。 */
    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
