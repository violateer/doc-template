package io.github.violateer.doctemplate.starter.ai;

/**
 * 外部要求中止时抛出（取消导入任务）。
 *
 * <p>单独一个类型，是为了让编排层能区分「用户取消」和「模型这次没给出可用计划」：
 * 前者直接结束任务，后者要继续重试。</p>
 */
public class DocTemplateAiAbortedException extends RuntimeException {

    public DocTemplateAiAbortedException() {
        super("任务已取消");
    }
}
