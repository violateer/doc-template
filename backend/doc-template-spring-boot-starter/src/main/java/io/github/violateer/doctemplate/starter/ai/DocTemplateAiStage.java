package io.github.violateer.doctemplate.starter.ai;

/**
 * 一次导入的阶段。
 *
 * <p>顺序固定：读结构 → 拼提示词 → 调模型 → 解析计划 → 校验计划 → 落笔自检 → 建模板 → 完成。
 * 每进一个阶段写一次库，管理页轮询就能显示「现在在做什么」，而不是一个转不完的圈。</p>
 */
public enum DocTemplateAiStage {
    /** 读文档结构 */
    SNAPSHOT,
    /** 拼提示词 */
    PROMPT,
    /** 调模型 */
    CALL,
    /** 解析模型输出 */
    PARSE,
    /** 校验计划本身 */
    VALIDATE,
    /** 落笔并用模拟数据渲染自检 */
    CHECK,
    /** 建模板、版本与数据块 */
    APPLY,
    /** 结束 */
    DONE
}
