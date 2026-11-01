package io.github.violateer.doctemplate.starter.controller;

import io.github.violateer.doctemplate.starter.service.DocTemplateService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/doc-template/data-blocks")
public class DocTemplateDataBlockController {
    private final DocTemplateService service;

    public DocTemplateDataBlockController(DocTemplateService service) {
        this.service = service;
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam("templateId") long templateId) {
        return service.blocks(templateId);
    }

    @PostMapping
    public Map<String, Object> create(@RequestParam("templateId") long templateId, @RequestBody Map<String, Object> body) {
        return service.createBlock(templateId, body);
    }

    /**
     * 预览数据源：按请求里的配置执行一次取数，不落库、不生成文件。
     */
    @PostMapping("/preview")
    public Map<String, Object> preview(@RequestBody Map<String, Object> body) {
        return service.previewBlock(body);
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable("id") long id, @RequestBody Map<String, Object> body) {
        return service.updateBlock(id, body);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable("id") long id) {
        service.deleteBlock(id);
        return Map.of("success", true);
    }

    /** 批量删除数据块，请求体 {@code {"ids":[1,2,3]}}；语义同模板的批量删除。 */
    @PostMapping("/batch-delete")
    public Map<String, Object> batchDelete(@RequestBody Map<String, Object> body) {
        return service.deleteBlocks(body);
    }
}
