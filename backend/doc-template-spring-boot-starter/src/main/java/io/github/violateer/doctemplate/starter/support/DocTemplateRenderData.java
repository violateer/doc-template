package io.github.violateer.doctemplate.starter.support;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次生成要喂给渲染器的全部数据：数据块的取数结果 + 表格动态列的列定义。
 *
 * <p>两者分开存放是有意的：列定义<b>不混进行数据</b>，行循环循环的仍然是行数组，
 * 列定义单独传给渲染器。否则 {@code {{#each 块编码}}} 会把列定义也当成一条记录，
 * 明细行里凭空多出一行。</p>
 *
 * @param data    {@code 块编码 -> 取数结果}
 * @param columns {@code 块编码 -> 列定义}，只有配了列源的数据块才有
 */
public record DocTemplateRenderData(Map<String, Object> data, Map<String, List<DocTemplateColumnSpec>> columns) {

    public static final DocTemplateRenderData EMPTY =
            new DocTemplateRenderData(Map.of(), Map.of());

    public DocTemplateRenderData {
        data = data == null ? Map.of() : data;
        columns = columns == null ? Map.of() : columns;
    }

    /** 取某个数据块的列定义，没配列源返回空列表。 */
    public List<DocTemplateColumnSpec> columnsOf(String blockCode) {
        if (blockCode == null || columns.isEmpty()) return List.of();
        List<DocTemplateColumnSpec> specs = columns.get(blockCode);
        return specs == null ? List.of() : specs;
    }

    /** 渲染器要的「按块编码取列定义」视图，键名大小写不敏感。 */
    public Map<String, List<DocTemplateColumnSpec>> columnIndex() {
        if (columns.isEmpty()) return Map.of();
        Map<String, List<DocTemplateColumnSpec>> index = new LinkedHashMap<>();
        columns.forEach((code, specs) -> {
            if (code == null || specs == null || specs.isEmpty()) return;
            index.put(code.toLowerCase(java.util.Locale.ROOT), specs);
        });
        return index;
    }
}
