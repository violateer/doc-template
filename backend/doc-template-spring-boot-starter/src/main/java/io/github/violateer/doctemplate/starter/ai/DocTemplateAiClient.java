package io.github.violateer.doctemplate.starter.ai;

/**
 * 让模型读文档结构、给出标记计划的调用契约。
 *
 * <p>宿主可以自己实现这个接口接管模型调用（走内网网关、走公司的模型平台、加审计日志都行），
 * 注册成 Bean 即可，内置的 OpenAI 兼容实现会退让（{@code @ConditionalOnMissingBean}）。</p>
 *
 * <p><b>实现只要保证一件事</b>：{@link Response#text()} 是一段可以解析成 JSON 对象的文本。
 * 带 markdown 代码块围栏、前后带解释文字都能被容错剥掉，但不要返回自然语言。</p>
 *
 * <p>{@code reasoning} 与 {@code usage} 允许为 {@code null}：不是所有服务商都返回思考过程或用量，
 * 缺失只影响「思维链记录」这一项展示，不影响出计划。</p>
 */
public interface DocTemplateAiClient {

    /**
     * 一次对话补全。
     *
     * @return 模型输出。{@code text} 期望是一个 JSON 对象；{@code reasoning} 是推理模型的思考过程原文
     */
    Response complete(Request request) throws Exception;

    /**
     * 流式补全：边收边把「到目前为止的全文」交给 {@code delta}，返回值与一次性调用完全一致。
     *
     * <p>默认实现退化为 {@link #complete(Request)}：宿主自己实现的客户端不写这个方法也能用，
     * 只是没有过程可看。回调里的 {@code reasoning} / {@code content} 是<b>累计全文</b>而不是增量片段 ——
     * 消费方直接覆盖落库即可，不用自己做拼接。</p>
     *
     * @param delta 增量回调，可为 {@code null}
     */
    default Response complete(Request request, Delta delta) throws Exception {
        Response response = complete(request);
        if (delta != null) delta.onDelta(response.reasoning(), response.text());
        return response;
    }

    /** 流式增量回调。 */
    interface Delta {
        Delta NONE = (reasoning, content) -> {
        };

        /**
         * @param reasoning 到当前为止的思考过程全文，模型没有输出思考时为 {@code null}
         * @param content   到当前为止的正文全文，还没开始输出正文时为 {@code null}
         */
        void onDelta(String reasoning, String content);
    }

    /**
     * @param systemPrompt 角色、输出结构和判断规则（本模块固定）
     * @param userPrompt   文档结构快照 + 上一次失败的反馈（本模块拼）
     * @param model        配置里的模型名，宿主实现可以忽略
     * @param maxTokens    本次调用的输出上限，{@code 0} 表示不限制、由服务端决定。
     *                     实现应优先使用这个值，而不是自己配置里的默认值
     */
    record Request(String systemPrompt, String userPrompt, String model, double temperature, int maxTokens) {
    }

    /**
     * 模型返回：正文 + 思考过程 + 用量。
     *
     * <p>思考过程以前只用来兜底取 JSON、随后丢弃；现在留下来落进思维链表，排查「模型为什么这么标」
     * 时这是唯一的一手材料。</p>
     */
    record Response(String text, String reasoning, Usage usage) {

        public Response(String text) {
            this(text, null, null);
        }
    }

    /**
     * 用量。
     *
     * @param promptTokens     输入 token 数，服务商没给时为 {@code null}
     * @param completionTokens 输出 token 数（推理模型的思考也计入），服务商没给时为 {@code null}
     */
    record Usage(Integer promptTokens, Integer completionTokens) {
    }
}
