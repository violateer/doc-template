package io.github.violateer.doctemplate.starter.controller;

import io.github.violateer.doctemplate.starter.annotate.DocTemplateMarkPlan;
import io.github.violateer.doctemplate.starter.service.DocTemplateAnnotateService;
import io.github.violateer.doctemplate.starter.service.DocTemplateService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 结构化标注接口：把一份已经填好的 docx / xlsx 变成「模板 + 数据块」。
 *
 * <p>四步：拿快照 → 写计划 → 自检/预览 → 落库。计划是一份 JSON，可以由模型生成，
 * 也可以由人照着快照手写 —— 这条路不依赖任何大模型，所以它是整个功能的地基。</p>
 */
@RestController
@RequestMapping("/api/doc-template/templates/{id}")
public class DocTemplateAnnotateController {

    private final DocTemplateAnnotateService annotate;
    private final DocTemplateService templates;

    public DocTemplateAnnotateController(DocTemplateAnnotateService annotate, DocTemplateService templates) {
        this.annotate = annotate;
        this.templates = templates;
    }

    /** 当前版本的结构快照：段落、表格、单元格坐标与文本，坐标一律 0 起始。 */
    @GetMapping("/snapshot")
    public Map<String, Object> snapshot(@PathVariable("id") long id) throws Exception {
        return annotate.snapshot(id);
    }

    /**
     * 自检：落笔 + 生成模拟 SQL + 用模拟数据渲染一次，不落库。
     *
     * <p>计划写错（编码非法、字段不存在）返回 400；坐标和文档对不上返回 {@code ok=false}，
     * 页面直接把 {@code error} 显示出来。</p>
     */
    @PostMapping("/annotate/check")
    public Map<String, Object> check(@PathVariable("id") long id, @RequestBody DocTemplateMarkPlan plan) throws Exception {
        return annotate.check(id, plan);
    }

    /**
     * 预览文件。
     *
     * @param mode {@code template} 看落笔后的模板（标记可见）；默认看模拟数据的渲染效果
     */
    @PostMapping("/annotate/preview")
    public ResponseEntity<byte[]> preview(@PathVariable("id") long id,
                                          @RequestParam(value = "mode", required = false) String mode,
                                          @RequestBody DocTemplateMarkPlan plan) throws Exception {
        byte[] bytes = annotate.preview(id, plan, mode);
        long versionId = templates.currentVersionId(id);
        return DocTemplateTemplateController.attachment(bytes, templates.currentFileName(id),
                templates.versionContentType(versionId));
    }

    /** 落库：自检通过后写一个新版本，并按计划建 SQL 数据块（模拟数据，不依赖任何真实表）。 */
    @PostMapping("/annotate")
    public Map<String, Object> apply(@PathVariable("id") long id, @RequestBody DocTemplateMarkPlan plan) throws Exception {
        return annotate.apply(id, plan);
    }
}
