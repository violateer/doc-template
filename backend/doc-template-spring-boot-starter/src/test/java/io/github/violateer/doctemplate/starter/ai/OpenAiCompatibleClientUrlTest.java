package io.github.violateer.doctemplate.starter.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 请求地址拼接。
 *
 * <p>base-url 写重一段就会 404，而 404 从服务端响应里看不出是拼错的 —— 这里把几种常见写法都钉住。</p>
 */
class OpenAiCompatibleClientUrlTest {

    private static final String PATH = "/v1/chat/completions";

    @Test
    @DisplayName("base-url 只写域名")
    void appendsThePath() {
        assertEquals("https://api.deepseek.com/v1/chat/completions",
                OpenAiCompatibleClient.resolveUrl("https://api.deepseek.com", PATH));
    }

    @Test
    @DisplayName("base-url 带尾斜杠")
    void ignoresTrailingSlash() {
        assertEquals("https://api.deepseek.com/v1/chat/completions",
                OpenAiCompatibleClient.resolveUrl("https://api.deepseek.com//", PATH));
    }

    @Test
    @DisplayName("base-url 已经写了 /v1 时不重复")
    void doesNotDuplicateTheVersionSegment() {
        assertEquals("https://api.deepseek.com/v1/chat/completions",
                OpenAiCompatibleClient.resolveUrl("https://api.deepseek.com/v1", PATH));
    }

    @Test
    @DisplayName("base-url 直接给了完整接口地址时原样使用")
    void keepsAFullEndpointAsIs() {
        assertEquals("https://api.deepseek.com/v1/chat/completions",
                OpenAiCompatibleClient.resolveUrl("https://api.deepseek.com/v1/chat/completions", PATH));
    }

    @Test
    @DisplayName("网关前缀原样保留")
    void keepsGatewayPrefix() {
        assertEquals("https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
                OpenAiCompatibleClient.resolveUrl("https://dashscope.aliyuncs.com/compatible-mode", PATH));
    }
}
