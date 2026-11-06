package io.github.violateer.doctemplate.starter.service;

import io.github.violateer.doctemplate.starter.support.DocTemplateMergeAlign;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 取值和关联条件<b>严格区分大小写</b>：模板里的字段名要和数据里的键名一模一样。
 *
 * <p>忽略大小写看着容错，实际是把拼写错误藏起来 —— 数据库列名是大写、模板写小写也能跑通，
 * 换库或换 Provider 才炸。宁可当场取不到：普通占位符留空，关联条件过滤成 0 行。</p>
 */
class DocTemplateCaseSensitivityTest {

    private static byte[] template(String[]... rows) throws Exception {
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XWPFTable table = document.createTable(rows.length, 3);
            for (int r = 0; r < rows.length; r++) {
                for (int c = 0; c < 3 && c < rows[r].length; c++) {
                    if (!rows[r][c].isEmpty()) table.getRow(r).getCell(c).setText(rows[r][c]);
                }
            }
            document.write(out);
            return out.toByteArray();
        }
    }

    private static XWPFTable render(Map<String, Object> params, String[]... rows) throws Exception {
        byte[] rendered = new DocTemplateRenderer().render(template(rows), "DOCX",
                params, List.of(), DocTemplateMergeAlign.DEFAULT, Map.of());
        return new XWPFDocument(new ByteArrayInputStream(rendered)).getTables().get(0);
    }

    private static List<Map<String, Object>> items() {
        List<Map<String, Object>> items = new ArrayList<>();
        items.add(item("SO-1", "SKU-A"));
        items.add(item("so-1", "SKU-B"));
        return items;
    }

    private static Map<String, Object> item(String orderNo, String sku) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("order_no", orderNo);
        item.put("sku", sku);
        return item;
    }

    private static Map<String, Object> params() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("order_no", "SO-1");
        params.put("items", items());
        return params;
    }

    private static final String[] HEADER = {"编码", "数量", "备注"};

    /** 单元格文字里出现过哪些 SKU，按行从上到下（跳过表头）。 */
    private static List<String> skus(XWPFTable table) {
        List<String> found = new ArrayList<>();
        for (int r = 1; r < table.getRows().size(); r++) {
            String text = table.getRow(r).getCell(0).getText();
            if (text.contains("SKU-A") || text.contains("SKU-B")) found.add(text);
        }
        return found;
    }

    @Test
    void placeholderKeyIsCaseSensitive() throws Exception {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", "张三");
        XWPFTable table = render(params, new String[]{"${name}", "${Name}", "${NAME}"});
        assertEquals("张三", table.getRow(0).getCell(0).getText());
        assertEquals("", table.getRow(0).getCell(1).getText(), "Name 不等于 name，取不到就留空");
        assertEquals("", table.getRow(0).getCell(2).getText());
    }

    /** 明细项里的字段名同样严格：`${item.Sku}` 取不到 `${item.sku}`。 */
    @Test
    void itemFieldIsCaseSensitive() throws Exception {
        XWPFTable table = render(params(), HEADER,
                new String[]{"{{#each items}}${item.sku}", "${item.Sku}", "{{/each}}"});
        assertEquals(List.of("SKU-A", "SKU-B"), skus(table));
        assertEquals("", table.getRow(1).getCell(1).getText());
    }

    /** 关联条件的取值严格等值：'SO-1' 和 'so-1' 是两个值，不做大小写合并。 */
    @Test
    void whereValueIsComparedStrictly() throws Exception {
        XWPFTable table = render(params(), HEADER,
                new String[]{"{{#each items where order_no = order_no}}${sku}", "", "{{/each}}"});
        assertEquals(List.of("SKU-A"), skus(table), "只留 order_no 完全等于 SO-1 的那条");
    }

    /** 关联条件的字段名也严格：`where ORDER_NO = ...` 在数据里找不到，过滤成 0 行。 */
    @Test
    void whereFieldIsCaseSensitive() throws Exception {
        XWPFTable table = render(params(), HEADER,
                new String[]{"{{#each items where ORDER_NO = order_no}}${sku}", "", "{{/each}}"});
        assertTrue(skus(table).isEmpty(), "ORDER_NO 不等于 order_no：" + skus(table));
    }

    /** 引号里的字面量同样严格等值。 */
    @Test
    void whereLiteralIsComparedStrictly() throws Exception {
        XWPFTable exact = render(params(), HEADER,
                new String[]{"{{#each items where order_no = 'SO-1'}}${sku}", "", "{{/each}}"});
        assertEquals(List.of("SKU-A"), skus(exact));

        XWPFTable lower = render(params(), HEADER,
                new String[]{"{{#each items where order_no = 'so-1'}}${sku}", "", "{{/each}}"});
        assertEquals(List.of("SKU-B"), skus(lower), "只匹配小写那条，不把两条都算上");
        assertFalse(skus(lower).contains("SKU-A"));
    }
}
