package io.github.violateer.doctemplate.starter.controller;

import io.github.violateer.doctemplate.starter.auth.DocTemplateGuard;
import io.github.violateer.doctemplate.starter.service.DocTemplateService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/doc-template/templates")
public class DocTemplateTemplateController {
    private final DocTemplateService service;
    private final DocTemplateGuard guard;

    public DocTemplateTemplateController(DocTemplateService service, DocTemplateGuard guard) {
        this.service = service;
        this.guard = guard;
    }

    static ResponseEntity<byte[]> inline(byte[] bytes, String name, String contentType) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(name, StandardCharsets.UTF_8).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(bytes);
    }

    static ResponseEntity<byte[]> attachment(byte[] bytes, String name, String contentType) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name, StandardCharsets.UTF_8).build().toString())
                .body(bytes);
    }

    /**
     * 模板列表。
     *
     * <p>带 {@code page} 参数时返回 {@code {records,total,page,size,pages}} 分页结构，
     * 支持 {@code keyword}（模板名称 / 编码）与 {@code status} 过滤；
     * 不带 {@code page} 时保持旧行为，返回完整数组。</p>
     */
    @GetMapping
    public Object list(
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false, defaultValue = "20") Integer size,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "status", required = false) String status) {
        if (page == null) return service.templates();
        return service.templatesPage(page, size == null ? 20 : size, keyword, status);
    }

    /** 模板总览统计，供列表页顶部的统计卡片使用（与分页无关）。 */
    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return service.templateStats();
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable("id") long id) {
        return service.template(id);
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        return service.createTemplate(body);
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable("id") long id, @RequestBody Map<String, Object> body) {
        return service.updateTemplate(id, body);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable("id") long id) {
        service.deleteTemplate(id);
        return Map.of("success", true);
    }

    /**
     * 批量删除模板，请求体 {@code {"ids":[1,2,3]}}。
     *
     * <p>逐条删除，单条失败不影响其他条；返回 {@code {deleted, failed:[{id, message}]}}，
     * 前端按它提示「成功 n 条 / 失败 m 条」。用 POST 而不是带 body 的 DELETE，
     * 是因为不少网关和客户端会丢掉 DELETE 的请求体。</p>
     */
    @PostMapping("/batch-delete")
    public Map<String, Object> batchDelete(@RequestBody Map<String, Object> body) {
        return service.deleteTemplates(body);
    }

    @PostMapping(value = "/{id}/file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> upload(@PathVariable("id") long id, @RequestPart("file") MultipartFile file) throws Exception {
        return service.upload(id, file);
    }

    @GetMapping("/{id}/versions")
    public List<Map<String, Object>> versions(@PathVariable("id") long id) {
        return service.versions(id);
    }

    @DeleteMapping("/{id}/versions/{versionId}")
    public Map<String, Object> deleteVersion(@PathVariable("id") long id, @PathVariable("versionId") long versionId) {
        service.deleteVersion(id, versionId);
        return Map.of("success", true);
    }

    /**
     * 把历史版本切换为当前版本：后续生成都用这一版模板文件。
     */
    @PutMapping("/{id}/versions/{versionId}/current")
    public Map<String, Object> useVersion(@PathVariable("id") long id, @PathVariable("versionId") long versionId) {
        return service.setCurrentVersion(id, versionId);
    }

    @GetMapping("/versions/{versionId}/download")
    public ResponseEntity<byte[]> download(@PathVariable("versionId") long versionId) throws Exception {
        return attachment(service.versionFileBytes(versionId), service.versionFileName(versionId), service.versionContentType(versionId));
    }

    /**
     * Streams the stored template file inline so the management page can render a preview.
     */
    @GetMapping("/versions/{versionId}/preview")
    public ResponseEntity<byte[]> preview(@PathVariable("versionId") long versionId) throws Exception {
        return inline(service.versionFileBytes(versionId), service.versionFileName(versionId), service.versionContentType(versionId));
    }

    /**
     * Lists the ${placeholder} keys found in the current template file.
     */
    @GetMapping("/{id}/placeholders")
    public Map<String, Object> placeholders(@PathVariable("id") long id) throws Exception {
        return service.placeholders(id);
    }

    /**
     * Simulates generation with hand-written parameters and stores the rendered document.
     */
    @PostMapping("/{id}/generate")
    public Map<String, Object> generate(@PathVariable("id") long id, @RequestBody Map<String, Object> body) {
        return service.generate(id, body, guard.currentUser());
    }
}
