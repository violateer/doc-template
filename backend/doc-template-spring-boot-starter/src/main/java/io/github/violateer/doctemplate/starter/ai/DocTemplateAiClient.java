package io.github.violateer.doctemplate.starter.ai;

/**
 * 让模型读文档结构、给出标记计划的调用契约。
 *
 * <p>宿主可以自己实现这个接口接管模型调用（走内网网关、走公司的模型平台、加审计日志都行），
 * 注册成 Bean 即可，内置的 OpenAI 兼容实现会退让（{@code @ConditionalOnMissingBean}）。</p>
 *
 * <p><b>实现只要保证一件事</b>：返回值是一段可以解析成 JSON 对象的文本。带 markdown 代码块围栏、
 * 前后带解释文字都能被容错剥掉，但不要返回自然语言。</p>
 */
public interface DocTemplateAiClient {

    /**
     * 一次对话补全。
     *
     * @return 模型输出的文本，期望是一个 JSON 对象
     */
    String complete(Request request) throws Exception;

    /**
     * @param systemPrompt 角色、输出结构和判断规则（本模块固定）
     * @param userPrompt   文档结构快照 + 上一次失败的反馈（本模块拼）
     * @param model        配置里的模型名，宿主实现可以忽略
     * @param maxTokens    本次调用的输出上限，{@code 0} 表示不限制、由服务端决定。
     *                     实现应优先使用这个值，而不是自己配置里的默认值
     */
    record Request(String systemPrompt, String userPrompt, String model, double temperature, int maxTokens) {
    }
}
