package io.github.violateer.doctemplate.starter.controller;

import io.github.violateer.doctemplate.starter.service.DocTemplateDictService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 数据字典的维护接口：字典（主表）与字典项（子表）分开维护。
 *
 * <p>字典是全局资源（不属于某个模板），所以接口不挂在模板路径下。
 * 列表只返回字典本身（带项数），项由 {@code /dicts/{id}/items} 单独取，字典很大时列表也不会被拖慢。</p>
 */
@RestController
@RequestMapping("/api/doc-template/dicts")
public class DocTemplateDictController {
    private final DocTemplateDictService service;

    public DocTemplateDictController(DocTemplateDictService service) {
        this.service = service;
    }

    // ------------------------------------------------------------------ 字典

    /** 字典列表（含 {@code item_count}），{@code keyword} 匹配编码 / 名称 / 备注。 */
    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(value = "keyword", required = false) String keyword) {
        return service.dicts(keyword);
    }

    /** 字典详情：主表字段 + {@code items}。 */
    @GetMapping("/{id}")
    public Map<String, Object> detail(@PathVariable("id") long id) {
        return service.detail(id);
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        return service.createDict(body);
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable("id") long id, @RequestBody Map<String, Object> body) {
        return service.updateDict(id, body);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable("id") long id) {
        service.deleteDict(id);
        return Map.of("success", true);
    }

    /** 批量删除字典，请求体 {@code {"ids":[1,2,3]}}；语义同模板的批量删除（连带子表）。 */
    @PostMapping("/batch-delete")
    public Map<String, Object> batchDelete(@RequestBody Map<String, Object> body) {
        return service.deleteDicts(body);
    }

    // ------------------------------------------------------------------ 字典项

    @GetMapping("/{id}/items")
    public List<Map<String, Object>> items(@PathVariable("id") long id) {
        return service.items(id);
    }

    @PostMapping("/{id}/items")
    public Map<String, Object> createItem(@PathVariable("id") long id, @RequestBody Map<String, Object> body) {
        return service.createItem(id, body);
    }

    @PutMapping("/items/{itemId}")
    public Map<String, Object> updateItem(@PathVariable("itemId") long itemId, @RequestBody Map<String, Object> body) {
        return service.updateItem(itemId, body);
    }

    @DeleteMapping("/items/{itemId}")
    public Map<String, Object> deleteItem(@PathVariable("itemId") long itemId) {
        service.deleteItem(itemId);
        return Map.of("success", true);
    }

    /** 批量删除字典项，请求体 {@code {"ids":[1,2,3]}}。 */
    @PostMapping("/items/batch-delete")
    public Map<String, Object> batchDeleteItems(@RequestBody Map<String, Object> body) {
        return service.deleteItems(body);
    }
}
