package io.github.violateer.doctemplate.starter.controller;

import io.github.violateer.doctemplate.starter.ai.DocTemplateAiImportService;
import io.github.violateer.doctemplate.starter.annotate.DocTemplateMarkPlan;
import io.github.violateer.doctemplate.starter.auth.DocTemplateGuard;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * AI 导入任务接口：上传 → 后台分析 → 建模板。
 *
 * <p>上传与执行分成两步是刻意的：上传只产生一条任务（{@code PENDING}），
 * 用户在列表里点「开始导入」才真正消耗模型调用。分析要几十秒到几分钟，同步接口只能让页面一直转圈，
 * 失败还会留下一个只有文件的空模板。</p>
 */
@RestController
@RequestMapping("/api/doc-template/ai/imports")
public class DocTemplateAiImportController {

    private final DocTemplateAiImportService imports;
    private final DocTemplateGuard guard;

    public DocTemplateAiImportController(DocTemplateAiImportService imports, DocTemplateGuard guard) {
        this.imports = imports;
        this.guard = guard;
    }

    /** 新建任务：文件 + 模板名 + 编码 + 补充要求。不建模板，只落一条任务。 */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> create(@RequestPart("file") MultipartFile file,
                                      @RequestParam(value = "templateName", required = false) String templateName,
                                      @RequestParam(value = "templateCode", required = false) String templateCode,
                                      @RequestParam(value = "hint", required = false) String hint) throws Exception {
        return imports.create(file, templateName, templateCode, hint, guard.currentUser());
    }

    /** 任务列表，服务端分页。 */
    @GetMapping
    public Map<String, Object> list(@RequestParam(value = "page", required = false, defaultValue = "1") Integer page,
                                    @RequestParam(value = "size", required = false, defaultValue = "10") Integer size,
                                    @RequestParam(value = "status", required = false) String status,
                                    @RequestParam(value = "keyword", required = false) String keyword) {
        return imports.page(page == null ? 1 : page, size == null ? 10 : size, status, keyword);
    }

    /** 详情：阶段、失败原因、思维链、计划原文。 */
    @GetMapping("/{id}")
    public Map<String, Object> detail(@PathVariable("id") long id) {
        return imports.detail(id);
    }

    /** 轮询用的轻量进度：状态、阶段、思维链正文（含流式中间结果），不含提示词。 */
    @GetMapping("/{id}/progress")
    public Map<String, Object> progress(@PathVariable("id") long id) {
        return imports.progress(id);
    }

    /** 开始导入：进队列后立即返回，进度靠轮询详情。 */
    @PostMapping("/{id}/start")
    public Map<String, Object> start(@PathVariable("id") long id) {
        return imports.start(id);
    }

    /** 取消：未开始的直接取消，正在跑的下一次调模型前停下。 */
    @PostMapping("/{id}/cancel")
    public Map<String, Object> cancel(@PathVariable("id") long id) {
        return imports.cancel(id);
    }

    /**
     * 重新分析：清掉上一次的结果，再走一遍完整流程。
     *
     * <p>已经生成过模板的任务也能用，结果写回原来那个模板（新增一个版本、重建数据块），
     * 不会新建一个编码相同、内容重复的模板。</p>
     */
    @PostMapping("/{id}/rerun")
    public Map<String, Object> rerun(@PathVariable("id") long id) {
        return imports.rerun(id);
    }

    /** 删除任务（执行中的不能删），文件与思维链一起清掉；已生成的模板保留。 */
    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable("id") long id) {
        imports.delete(id);
        return Map.of("success", true);
    }

    /** 批量删除：逐条删，单条失败不影响其他条；已生成的模板保留。 */
    @PostMapping("/batch-delete")
    public Map<String, Object> batchDelete(@RequestBody Map<String, Object> body) {
        return imports.deleteBatch(body);
    }

    /**
     * 用改过的计划重新自检。
     *
     * <p>计划写错（编码非法、字段不存在）返回 400；坐标和文档对不上返回 {@code ok=false}，
     * 页面直接把 {@code error} 显示出来。</p>
     */
    @PostMapping("/{id}/check")
    public Map<String, Object> check(@PathVariable("id") long id, @RequestBody DocTemplateMarkPlan plan)
            throws Exception {
        return imports.check(id, plan);
    }

    /**
     * 预览任务文件。
     *
     * @param mode {@code template} 看落笔后的模板（标记可见）；默认看模拟数据的渲染效果
     */
    @PostMapping("/{id}/preview")
    public ResponseEntity<byte[]> preview(@PathVariable("id") long id,
                                          @RequestParam(value = "mode", required = false) String mode,
                                          @RequestBody DocTemplateMarkPlan plan) throws Exception {
        return DocTemplateTemplateController.attachment(imports.preview(id, plan, mode),
                imports.fileName(id), imports.contentType(id));
    }

    /** 手动落库：按改过的计划建模板与数据块（已经生成过模板的任务不再走这里）。 */
    @PostMapping("/{id}/apply")
    public Map<String, Object> apply(@PathVariable("id") long id, @RequestBody DocTemplateMarkPlan plan)
            throws Exception {
        return imports.apply(id, plan);
    }
}
