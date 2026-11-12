package io.github.violateer.doctemplate.starter.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 流式分片累加。
 *
 * <p>流式与非流式必须走同一套收尾判定：正文为空时从思考里捞 JSON、{@code finish_reason=length}
 * 算预算不足。这里守住「拼起来之后和一次性返回等价」这件事。</p>
 */
class OpenAiCompatibleClientStreamTest {

    private static String chunk(String deltaJson) {
        return "{\"choices\":[{\"index\":0,\"delta\":" + deltaJson + "}]}";
    }

    @Test
    @DisplayName("正文分片按顺序拼接")
    void appendsContent() {
        OpenAiCompatibleClient.StreamAccumulator acc = new OpenAiCompatibleClient.StreamAccumulator();
        assertTrue(acc.accept(chunk("{\"content\":\"{\\\"blocks\\\"\"}")));
        assertTrue(acc.accept(chunk("{\"content\":\":[],\\\"marks\\\":[]}\"}")));
        assertEquals("{\"blocks\":[],\"marks\":[]}", acc.contentText());
        assertNull(acc.reasoningText());
    }

    @Test
    @DisplayName("思考过程与正文各走各的，互不覆盖")
    void appendsReasoningSeparately() {
        OpenAiCompatibleClient.StreamAccumulator acc = new OpenAiCompatibleClient.StreamAccumulator();
        acc.accept(chunk("{\"reasoning_content\":\"先看\"}"));
        acc.accept(chunk("{\"reasoning_content\":\"表头\"}"));
        acc.accept(chunk("{\"content\":\"{\\\"blocks\\\":[],\\\"marks\\\":[]}\"}"));
        assertEquals("先看表头", acc.reasoningText());
        assertEquals("{\"blocks\":[],\"marks\":[]}", acc.contentText());
        assertEquals("{\"blocks\":[],\"marks\":[]}", acc.toResponse().text());
        assertEquals("先看表头", acc.toResponse().reasoning());
    }

    @Test
    @DisplayName("最后一包只有 usage 时不能当成结束，也不能丢掉用量")
    void keepsUsageFromTailChunk() {
        OpenAiCompatibleClient.StreamAccumulator acc = new OpenAiCompatibleClient.StreamAccumulator();
        acc.accept(chunk("{\"content\":\"{\\\"blocks\\\":[],\\\"marks\\\":[]}\"}"));
        acc.accept("{\"choices\":[],\"usage\":{\"prompt_tokens\":120,\"completion_tokens\":34}}");
        DocTemplateAiClient.Usage usage = acc.toResponse().usage();
        assertEquals(120, usage.promptTokens());
        assertEquals(34, usage.completionTokens());
    }

    @Test
    @DisplayName("[DONE] 结束流，之后的包不再累加")
    void stopsOnDone() {
        OpenAiCompatibleClient.StreamAccumulator acc = new OpenAiCompatibleClient.StreamAccumulator();
        acc.accept(chunk("{\"content\":\"{}\"}"));
        assertFalse(acc.accept("[DONE]"));
    }

    @Test
    @DisplayName("正文为空但思考里写出了完整 JSON 时仍然救回这一次")
    void salvagesPlanFromReasoning() {
        OpenAiCompatibleClient.StreamAccumulator acc = new OpenAiCompatibleClient.StreamAccumulator();
        acc.accept(chunk("{\"reasoning_content\":\"计划：{\\\"blocks\\\":[],\\\"marks\\\":[]}\"}"));
        acc.accept("{\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"length\"}]}");
        assertEquals("{\"blocks\":[],\"marks\":[]}", acc.toResponse().text());
    }

    @Test
    @DisplayName("正文为空且思考里没有 JSON 时按预算不足报错")
    void reportsBudgetWhenNothingUsable() {
        OpenAiCompatibleClient.StreamAccumulator acc = new OpenAiCompatibleClient.StreamAccumulator();
        acc.accept(chunk("{\"reasoning_content\":\"还在想\"}"));
        assertThrows(DocTemplateAiBudgetException.class, acc::toResponse);
    }

    @Test
    @DisplayName("SSE 行只认 data:，注释与事件行跳过")
    void readsSsePayload() {
        assertNull(OpenAiCompatibleClient.ssePayload(": keep-alive"));
        assertNull(OpenAiCompatibleClient.ssePayload("event: message"));
        assertNull(OpenAiCompatibleClient.ssePayload(""));
        assertEquals("{\"a\":1}", OpenAiCompatibleClient.ssePayload("data: {\"a\":1}"));
        assertEquals("[DONE]", OpenAiCompatibleClient.ssePayload("data:[DONE]"));
    }
}
