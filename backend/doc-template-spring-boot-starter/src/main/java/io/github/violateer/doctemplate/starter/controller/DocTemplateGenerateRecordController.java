package io.github.violateer.doctemplate.starter.controller;

import io.github.violateer.doctemplate.starter.auth.DocTemplateGuard;
import io.github.violateer.doctemplate.starter.service.DocTemplateService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/doc-template/generate-records")
public class DocTemplateGenerateRecordController {
    private final DocTemplateService service;
    private final DocTemplateGuard guard;
    public DocTemplateGenerateRecordController(DocTemplateService service, DocTemplateGuard guard){this.service=service;this.guard=guard;}
    /**
     * 生成记录列表。
     *
     * <p>带 {@code page} 参数时返回 {@code {records,total,page,size,pages}} 分页结构，
     * 支持 {@code keyword}（记录号 / 生成人 / 失败说明 / 模板名称）与 {@code status} 过滤；
     * 不带 {@code page} 时保持旧行为，返回完整数组。</p>
     */
    @GetMapping public Object list(
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false, defaultValue = "20") Integer size,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "status", required = false) String status) {
        if (page == null) return service.records();
        return service.recordsPage(page, size == null ? 20 : size, keyword, status);
    }
    @PostMapping public Map<String,Object> create(@RequestBody Map<String,Object> body){
        long templateId=Long.parseLong(String.valueOf(body.get("templateId")));
        return service.generate(templateId,body,guard.currentUser());
    }
    @GetMapping("/{id}/file") public ResponseEntity<byte[]> file(@PathVariable("id") long id)throws Exception{
        return DocTemplateTemplateController.attachment(service.recordFileBytes(id),service.recordFileName(id),service.recordContentType(id));
    }
    @GetMapping("/{id}/preview") public ResponseEntity<byte[]> preview(@PathVariable("id") long id)throws Exception{
        return DocTemplateTemplateController.inline(service.recordFileBytes(id),service.recordFileName(id),service.recordContentType(id));
    }
    @DeleteMapping("/{id}") public Map<String,Object> delete(@PathVariable("id") long id){
        service.deleteRecord(id);
        return Map.of("success",true);
    }
    /** 批量删除生成记录，请求体 {@code {"ids":[1,2,3]}}；语义同模板的批量删除。 */
    @PostMapping("/batch-delete") public Map<String,Object> batchDelete(@RequestBody Map<String,Object> body){
        return service.deleteRecords(body);
    }
}
