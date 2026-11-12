package io.github.violateer.doctemplate.starter.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 响应正文解析。
 *
 * <p>推理模型会把 {@code max_tokens} 花在 {@code reasoning_content} 上，正文直接为空；
 * 这种情况下报错必须指向「预算不够」，否则只看得到一大段思考过程。</p>
 */
class OpenAiCompatibleClientContentTest {

    @Test
    @DisplayName("正常返回直接取 content")
    void readsContent() {
        String body = """
                {"choices":[{"index":0,"finish_reason":"stop",
                  "message":{"role":"assistant","content":"{\\"blocks\\":[],\\"marks\\":[]}"}}]}
                """;
        assertEquals("{\"blocks\":[],\"marks\":[]}", OpenAiCompatibleClient.contentOf(body).text());
    }

    @Test
    @DisplayName("思考过程与用量一并带回，供思维链记录")
    void keepsReasoningAndUsage() {
        String body = """
                {"choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant",
                  "content":"{\\"blocks\\":[],\\"marks\\":[]}","reasoning_content":"先看表头再定字段"}}],
                 "usage":{"prompt_tokens":1234,"completion_tokens":567}}
                """;
        DocTemplateAiClient.Response response = OpenAiCompatibleClient.contentOf(body);
        assertEquals("{\"blocks\":[],\"marks\":[]}", response.text());
        assertEquals("先看表头再定字段", response.reasoning(), "思考过程要留给思维链表");
        assertEquals(1234, response.usage().promptTokens());
        assertEquals(567, response.usage().completionTokens());
    }

    @Test
    @DisplayName("只有推理内容时报「预算不够」而不是「content 为空」")
    void reportsReasoningOnlyAsBudgetProblem() {
        String body = """
                {"choices":[{"index":0,"finish_reason":"length",
                  "message":{"role":"assistant","reasoning_content":"Let me analyze this workbook..."}}],
                 "usage":{"completion_tokens":8192}}
                """;
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> OpenAiCompatibleClient.contentOf(body));
        assertTrue(error.getMessage().contains("只输出了推理内容"), error.getMessage());
        assertTrue(error.getMessage().contains("max-tokens"), error.getMessage());
        assertTrue(error.getMessage().contains("finish_reason=length"), error.getMessage());
        assertTrue(error.getMessage().contains("completion_tokens=8192"), error.getMessage());
    }

    @Test
    @DisplayName("正文为空但思考里写了完整 JSON 时，捞出来用")
    void salvagesJsonFromReasoning() {
        String body = """
                {"choices":[{"index":0,"finish_reason":"length",
                  "message":{"role":"assistant","reasoning_content":
                    "We need produce JSON plan. Final: {\\"blocks\\":[],\\"marks\\":[]} done."}}],
                 "usage":{"completion_tokens":8192}}
                """;
        assertEquals("{\"blocks\":[],\"marks\":[]}", OpenAiCompatibleClient.contentOf(body).text());
    }

    @Test
    @DisplayName("思考里的 JSON 不是计划结构时不当成结果")
    void ignoresUnrelatedJsonInReasoning() {
        String body = """
                {"choices":[{"index":0,"finish_reason":"length",
                  "message":{"role":"assistant","reasoning_content":"配置长这样：{\\"foo\\":1}"}}]}
                """;
        assertThrows(DocTemplateAiBudgetException.class, () -> OpenAiCompatibleClient.contentOf(body));
    }

    @Test
    @DisplayName("正文被截断且不是完整 JSON 时按预算不足处理")
    void reportsTruncatedContentAsBudgetProblem() {
        String body = """
                {"choices":[{"index":0,"finish_reason":"length",
                  "message":{"role":"assistant","content":"{\\"blocks\\":[{\\"code\\":\\"main\\""}}],
                 "usage":{"completion_tokens":8192}}
                """;
        DocTemplateAiBudgetException error = assertThrows(DocTemplateAiBudgetException.class,
                () -> OpenAiCompatibleClient.contentOf(body));
        assertTrue(error.getMessage().contains("被截断"), error.getMessage());
    }

    @Test
    @DisplayName("既没有正文也没有推理内容时如实报空")
    void reportsEmptyContent() {
        String body = """
                {"choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":""}}]}
                """;
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> OpenAiCompatibleClient.contentOf(body));
        assertTrue(error.getMessage().contains("content 为空"), error.getMessage());
        assertTrue(error.getMessage().contains("finish_reason=stop"), error.getMessage());
    }
}
