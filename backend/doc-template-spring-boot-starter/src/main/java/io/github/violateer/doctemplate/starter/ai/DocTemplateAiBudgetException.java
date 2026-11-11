package io.github.violateer.doctemplate.starter.ai;

/**
 * 输出上限被思考过程耗尽：模型只产出了推理内容，正文为空或被截断。
 *
 * <p>单独一个类型，是为了让编排层分清两件事：模型「写错了」（坐标、字段名不对，回灌错误有用）和
 * 「没写完」（上限不够，模型根本没看到自己的输出，回灌没有意义）。上限在请求里已经给足，
 * 重问一遍是同一个请求，所以后者直接停下并说明原因，不占用剩下的尝试次数。</p>
 */
public class DocTemplateAiBudgetException extends IllegalStateException {

    public DocTemplateAiBudgetException(String message) {
        super(message);
    }
}
