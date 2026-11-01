package io.github.violateer.doctemplate.example;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 演示用业务接口：给 API 数据块当靶子，返回结构固定的假订单。
 *
 * <p>返回体是 {@code {code, msg, data}} 三段式，所以数据块里把「结果路径」配成 {@code data}
 * 就能只取业务部分。数据全部硬编码，不查库、不需要登录态
 * （{@code /demo/**} 不在 {@code /api/doc-template/**} 的认证拦截范围内）。</p>
 *
 * <p>同时提供 GET 与 POST，用来演示数据块里「URL 传参」和「body 传参」两种方式：</p>
 * <pre>
 * GET  /demo/api/orders/{orderNo}?name={name}      ← 路径与查询串里的占位符
 * POST /demo/api/orders/{orderNo}                  ← 路径占位符 + JSON 请求体
 *      body: {"channel": "doc-template", "remark": "..."}
 * </pre>
 *
 * <p>POST 收到的请求体会原样回显在 {@code request_body} 字段里，方便确认 body 里的
 * {@code ${name}} 到底替换成了什么。</p>
 */
@RestController
@RequestMapping("/demo/api")
public class DemoApiController {

    /** 不传单号时用的默认值，保证接口任何时候都能返回完整数据。 */
    private static final String DEFAULT_ORDER_NO = "DEMO-2026-0001";

    /**
     * 订单详情（GET）。{@code /demo/api/orders} 与 {@code /demo/api/orders/{orderNo}} 都支持，
     * 前者方便直接浏览器打开看返回结构，后者用来演示 URL 里的 {@code {orderNo}} 占位符。
     *
     * <p>{@code name} 只是个可选的查询串参数，用来演示 {@code ?name={name}} 这种写法。</p>
     */
    @GetMapping({"/orders", "/orders/{orderNo}"})
    public Map<String, Object> order(@PathVariable(name = "orderNo", required = false) String orderNo,
                                     @RequestParam(name = "name", required = false) String name,
                                     @RequestHeader(name = "X-Demo-Source", required = false) String source) {
        return envelope(payload(orderNo, source, Map.of(), name));
    }

    /**
     * 订单详情（POST）：路径上的 {@code {orderNo}} 和 JSON 请求体同时生效，
     * 用来演示「URL 传参 + body 传参」并存。
     */
    @PostMapping({"/orders", "/orders/{orderNo}"})
    public Map<String, Object> orderByPost(@PathVariable(name = "orderNo", required = false) String orderNo,
                                           @RequestParam(name = "name", required = false) String name,
                                           @RequestHeader(name = "X-Demo-Source", required = false) String source,
                                           @RequestBody(required = false) Map<String, Object> body) {
        return envelope(payload(orderNo, source, body == null ? Map.of() : body, name));
    }

    /** 两个方法返回同一份结构，只有 request_body / request_query 不同。 */
    private Map<String, Object> payload(String orderNo, String source, Map<String, Object> body, String name) {
        String no = blankToDefault(orderNo, DEFAULT_ORDER_NO);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("order_no", no);
        data.put("customer_name", "苏州恒远精密机械有限公司");
        data.put("contact", "张经理 139-0000-0000");
        data.put("order_date", "2026-10-28");
        data.put("delivery_date", "2026-11-20");
        data.put("sales_name", "陈静");
        data.put("amount", new BigDecimal("8740.00"));
        data.put("remark", "数据来自 DemoApiController（API 数据块），全部为代码内造的假数据");
        data.put("data_source", "API: /demo/api/orders/" + no);
        data.put("request_header", blankToDefault(source, "(未传 X-Demo-Source)"));
        data.put("request_query", blankToDefault(name, "(未传 name)"));
        data.put("request_body", body);
        data.put("items", items());
        return data;
    }

    /** 明细行：和 Provider 同结构，方便同一份模板换数据块就能跑。 */
    private List<Map<String, Object>> items() {
        List<Map<String, Object>> items = new ArrayList<>(3);
        items.add(line(1, "深沟球轴承", "6208-2RS", 60, "套", "52.00"));
        items.add(line(2, "同步带轮", "HTD5M-30T", 30, "个", "96.00"));
        items.add(line(3, "润滑脂", "2# 锂基 / 400g", 100, "支", "35.80"));
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

    private Map<String, Object> envelope(Object data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", 0);
        body.put("msg", "ok");
        body.put("data", data);
        return body;
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
