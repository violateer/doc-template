package io.github.violateer.doctemplate.starter.controller;

import io.github.violateer.doctemplate.starter.ai.DocTemplateAiPlanner;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * AI 出计划接口。
 *
 * <p>只做两件事：报告「能不能用」、让模型给出计划并立即自检。计划本身仍然是一份 JSON，
 * 落库前人可以改 —— 所以模型出错不会直接写坏模板，最坏情况是这份 JSON 不能用。</p>
 *
 * <p>这两个接口在 {@code doc-template.ai.enabled=false}（默认）时也存在：{@code status} 返回
 * {@code enabled=false}，页面据此隐藏入口；{@code plan} 直接报「AI 未启用」。</p>
 */
@RestController
@RequestMapping("/api/doc-template")
public class DocTemplateAiController {

    private final DocTemplateAiPlanner planner;

    public DocTemplateAiController(DocTemplateAiPlanner planner) {
        this.planner = planner;
    }

    /** 是否可用、用的哪个模型、最多试几次。不含地址和密钥。 */
    @GetMapping("/ai/status")
    public Map<String, Object> status() {
        return planner.status();
    }

    /**
     * 让模型读当前版本的结构快照，给出标记计划并自检。
     *
     * @param request 可选的 {@code hint}：用一句话补充要求，比如此文档哪张表是明细、日期要拆开
     */
    @PostMapping("/templates/{id}/ai/plan")
    public Map<String, Object> plan(@PathVariable("id") long id,
                                    @RequestBody(required = false) PlanRequest request) throws Exception {
        return planner.plan(id, request == null ? null : request.hint());
    }

    public record PlanRequest(String hint) {
    }
}
