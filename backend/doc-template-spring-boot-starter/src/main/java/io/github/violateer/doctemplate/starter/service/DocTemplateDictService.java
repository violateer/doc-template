package io.github.violateer.doctemplate.starter.service;

import cn.hutool.core.convert.Convert;
import cn.hutool.core.util.StrUtil;
import io.github.violateer.doctemplate.starter.entity.DocTemplateDictEntity;
import io.github.violateer.doctemplate.starter.entity.DocTemplateDictItemEntity;
import io.github.violateer.doctemplate.starter.repository.DocTemplateDictItemRepository;
import io.github.violateer.doctemplate.starter.repository.DocTemplateDictRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 数据字典：字典主表（编码 + 名称）+ 字典项子表（值 → 显示名），供 {@code dict} 转换器使用。
 *
 * <p>两张表跟着 Starter 自动建表，宿主不需要额外配置；其他数据库的宿主按 {@code db/} 下对应库的
 * 建表脚本（{@code schema.sql} / {@code schema-postgresql.sql} / {@code schema-sqlserver.sql}）自行执行 DDL。</p>
 *
 * <h2>为什么要做线程内缓存</h2>
 * <p>一张明细表几百行、每行都要转同一个状态字段，逐行查库就是几百次 {@code SELECT}。
 * {@link #labels(String)} 把「字典编码 → (值 → 显示名)」缓存在当前线程，生成结束时由
 * {@link DocTemplateService} 调 {@link #clearScope()} 清掉：同一次生成里每本字典只查一次，
 * 也不会把旧字典带到下一次请求（线程池会复用线程）。</p>
 */
public class DocTemplateDictService {

    private static final Logger log = LoggerFactory.getLogger(DocTemplateDictService.class);

    /** 字典编码只允许字母、数字、下划线、点和短横线：它会出现在配置里，太自由容易写错。 */
    private static final Pattern CODE_PATTERN = Pattern.compile("[A-Za-z0-9_.-]{1,100}");

    /** 一次批量删除最多多少条，与其他列表保持一致。 */
    private static final int BATCH_DELETE_LIMIT = 200;

    private final DocTemplateDictRepository dictRepository;
    private final DocTemplateDictItemRepository itemRepository;

    /** 当前线程的字典缓存，键是字典编码，值是「item_value → item_label」。 */
    private final ThreadLocal<Map<String, Map<String, String>>> scope =
            ThreadLocal.withInitial(LinkedHashMap::new);

    public DocTemplateDictService(DocTemplateDictRepository dictRepository,
                                  DocTemplateDictItemRepository itemRepository) {
        this.dictRepository = dictRepository;
        this.itemRepository = itemRepository;
    }

    // ------------------------------------------------------------------ 转换器取数

    /**
     * 一本字典的「值 → 显示名」映射，按 {@code sort_order} 有序；同一线程内只查一次库。
     *
     * <p>字典不存在时返回空 Map（调用方按 {@code fallback} 决定输出原值还是空串），
     * 不抛异常 —— 少建一本字典不该让整篇文档生成失败。</p>
     */
    public Map<String, String> labels(String dictCode) {
        String code = StrUtil.blankToDefault(dictCode, "").trim();
        if (code.isEmpty()) return Map.of();
        Map<String, Map<String, String>> cache = scope.get();
        Map<String, String> cached = cache.get(code);
        if (cached != null) return cached;
        Map<String, String> labels = itemRepository.labelsByCode(code);
        cache.put(code, labels);
        return labels;
    }

    /** 开始一次生成：清掉上次残留的缓存（线程池复用线程时可能还留着旧字典）。 */
    public void beginScope() {
        scope.get().clear();
    }

    /** 结束一次生成：连同 ThreadLocal 一起摘掉，不给线程池留引用。 */
    public void clearScope() {
        scope.remove();
    }

    // ------------------------------------------------------------------ 字典（主表）

    /** 字典列表，带 {@code item_count}；{@code keyword} 匹配编码 / 名称 / 备注。 */
    public List<Map<String, Object>> dicts(String keyword) {
        return dictRepository.findAll(keyword).stream().map(this::dictMap).toList();
    }

    /** 字典详情：主表字段 + {@code items}。 */
    public Map<String, Object> detail(long id) {
        Map<String, Object> map = dictMap(requireDict(id));
        map.put("items", items(id));
        return map;
    }

    public Map<String, Object> createDict(Map<String, Object> body) {
        String code = requireCode(body.get("dictCode"));
        String name = require(body, "dictName", "字典名称");
        if (dictRepository.findByCodeExcluding(code, null) != null) {
            throw new IllegalArgumentException("字典编码已存在：" + code);
        }
        DocTemplateDictEntity e = new DocTemplateDictEntity();
        e.setDictCode(code);
        e.setDictName(name);
        e.setRemark(StrUtil.trimToNull(Convert.toStr(body.get("remark"))));
        dictRepository.insert(e);
        DocTemplateDictEntity saved = e.getId() == null ? e : dictRepository.findById(e.getId());
        return dictMap(saved == null ? e : saved);
    }

    public Map<String, Object> updateDict(long id, Map<String, Object> body) {
        DocTemplateDictEntity e = requireDict(id);
        String code = requireCode(body.getOrDefault("dictCode", e.getDictCode()));
        String name = require(body, "dictName", "字典名称");
        if (dictRepository.findByCodeExcluding(code, id) != null) {
            throw new IllegalArgumentException("字典编码已存在：" + code);
        }
        e.setDictCode(code);
        e.setDictName(name);
        e.setRemark(StrUtil.trimToNull(Convert.toStr(body.get("remark"))));
        dictRepository.update(e);
        return dictMap(dictRepository.findById(id));
    }

    /** 删除字典：先清子表再删主表（表上没外键，顺序由这里保证）。 */
    public void deleteDict(long id) {
        DocTemplateDictEntity e = requireDict(id);
        int removed = itemRepository.deleteByDictId(id);
        dictRepository.deleteById(id);
        log.debug("删除字典「{}」及其 {} 个字典项", e.getDictCode(), removed);
    }

    /** 批量删除字典，语义同模板的批量删除：逐条删、单条失败不影响其他条。 */
    public Map<String, Object> deleteDicts(Map<String, Object> body) {
        List<Map<String, Object>> failed = new ArrayList<>();
        int deleted = 0;
        for (Long id : idsOf(body)) {
            try {
                deleteDict(id);
                deleted++;
            } catch (RuntimeException e) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", id);
                item.put("message", StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName()));
                failed.add(item);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deleted", deleted);
        result.put("failed", failed);
        return result;
    }

    // ------------------------------------------------------------------ 字典项（子表）

    public List<Map<String, Object>> items(long dictId) {
        requireDict(dictId);
        return itemRepository.findByDictId(dictId).stream().map(this::itemMap).toList();
    }

    /**
     * 字典项分页列表：返回 {@code {records,total,page,size,pages}}，与模板列表同一套结构，
     * 供管理页做服务端分页；字典项很多时不必一次拉全量。
     */
    public Map<String, Object> itemsPage(long dictId, int page, int size) {
        requireDict(dictId);
        int current = Math.max(1, page);
        int pageSize = Math.min(200, Math.max(1, size));
        long total = itemRepository.countByDictId(dictId);
        List<DocTemplateDictItemEntity> rows = itemRepository.findPage(dictId, (current - 1) * pageSize, pageSize);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("records", rows.stream().map(this::itemMap).toList());
        data.put("total", total);
        data.put("page", current);
        data.put("size", pageSize);
        data.put("pages", pages(total, pageSize));
        return data;
    }

    public Map<String, Object> createItem(long dictId, Map<String, Object> body) {
        requireDict(dictId);
        String value = require(body, "itemValue", "字典值");
        String label = require(body, "itemLabel", "显示名");
        if (itemRepository.findByDictIdAndValue(dictId, value, null) != null) {
            throw new IllegalArgumentException("该字典里已经有值为 " + value + " 的项");
        }
        DocTemplateDictItemEntity e = new DocTemplateDictItemEntity();
        e.setDictId(dictId);
        e.setItemValue(value);
        e.setItemLabel(label);
        e.setSortOrder(Convert.toInt(body.get("sortOrder"), itemRepository.maxSortOrder(dictId) + 1));
        itemRepository.insert(e);
        DocTemplateDictItemEntity saved = e.getId() == null ? e : itemRepository.findById(e.getId());
        return itemMap(saved == null ? e : saved);
    }

    public Map<String, Object> updateItem(long itemId, Map<String, Object> body) {
        DocTemplateDictItemEntity e = requireItem(itemId);
        String value = require(body, "itemValue", "字典值");
        String label = require(body, "itemLabel", "显示名");
        if (itemRepository.findByDictIdAndValue(e.getDictId(), value, itemId) != null) {
            throw new IllegalArgumentException("该字典里已经有值为 " + value + " 的项");
        }
        e.setItemValue(value);
        e.setItemLabel(label);
        e.setSortOrder(Convert.toInt(body.get("sortOrder"), e.getSortOrder() == null ? 0 : e.getSortOrder()));
        itemRepository.update(e);
        return itemMap(itemRepository.findById(itemId));
    }

    public void deleteItem(long itemId) {
        requireItem(itemId);
        itemRepository.deleteById(itemId);
    }

    /** 批量删除字典项，语义同 {@link #deleteDicts(Map)}。 */
    public Map<String, Object> deleteItems(Map<String, Object> body) {
        List<Map<String, Object>> failed = new ArrayList<>();
        int deleted = 0;
        for (Long id : idsOf(body)) {
            try {
                deleteItem(id);
                deleted++;
            } catch (RuntimeException e) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", id);
                item.put("message", StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName()));
                failed.add(item);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deleted", deleted);
        result.put("failed", failed);
        return result;
    }

    // ------------------------------------------------------------------ 内部

    /** 分页总页数：0 条数据时返回 0，与前端分页器保持一致。 */
    private static int pages(long total, int pageSize) {
        return (int) ((total + pageSize - 1) / pageSize);
    }

    private DocTemplateDictEntity requireDict(long id) {
        DocTemplateDictEntity e = dictRepository.findById(id);
        if (e == null) throw new NoSuchElementException("字典不存在：" + id);
        return e;
    }

    private DocTemplateDictItemEntity requireItem(long id) {
        DocTemplateDictItemEntity e = itemRepository.findById(id);
        if (e == null) throw new NoSuchElementException("字典项不存在：" + id);
        return e;
    }

    private String requireCode(Object raw) {
        String code = StrUtil.blankToDefault(Convert.toStr(raw), "").trim();
        if (code.isEmpty()) throw new IllegalArgumentException("请填写字典编码");
        if (!CODE_PATTERN.matcher(code).matches()) {
            throw new IllegalArgumentException("字典编码只能用字母、数字、下划线、点和短横线：" + code);
        }
        return code;
    }

    private String require(Map<String, Object> body, String key, String label) {
        String value = StrUtil.trimToEmpty(Convert.toStr(body.get(key)));
        if (value.isEmpty()) throw new IllegalArgumentException("请填写" + label);
        return value;
    }

    /** 从请求体里取 {@code ids}：去重、保序，空列表和超限都直接拒绝。 */
    private static List<Long> idsOf(Map<String, Object> body) {
        Object raw = body == null ? null : body.get("ids");
        if (!(raw instanceof Collection<?> list) || list.isEmpty()) {
            throw new IllegalArgumentException("请先选择要删除的数据（ids 不能为空）");
        }
        Set<Long> ids = new LinkedHashSet<>();
        for (Object item : list) {
            if (item == null || StrUtil.isBlank(String.valueOf(item))) continue;
            try {
                ids.add(Long.parseLong(String.valueOf(item).trim()));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("ids 里有非法的主键：" + item);
            }
        }
        if (ids.isEmpty()) throw new IllegalArgumentException("请先选择要删除的数据（ids 不能为空）");
        if (ids.size() > BATCH_DELETE_LIMIT) {
            throw new IllegalArgumentException("一次最多删除 " + BATCH_DELETE_LIMIT + " 条，当前选了 " + ids.size() + " 条");
        }
        return new ArrayList<>(ids);
    }

    private Map<String, Object> dictMap(DocTemplateDictEntity e) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", e.getId());
        map.put("dict_code", e.getDictCode());
        map.put("dict_name", e.getDictName());
        map.put("remark", e.getRemark());
        map.put("item_count", e.getItemCount());
        map.put("create_time", e.getCreateTime());
        map.put("update_time", e.getUpdateTime());
        return map;
    }

    private Map<String, Object> itemMap(DocTemplateDictItemEntity e) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", e.getId());
        map.put("dict_id", e.getDictId());
        map.put("item_value", e.getItemValue());
        map.put("item_label", e.getItemLabel());
        map.put("sort_order", e.getSortOrder());
        return map;
    }
}
