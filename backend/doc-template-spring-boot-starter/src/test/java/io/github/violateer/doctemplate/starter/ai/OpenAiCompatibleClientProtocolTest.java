package io.github.violateer.doctemplate.starter.ai;

import io.github.violateer.doctemplate.starter.autoconfigure.DocTemplateProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 协议层的通用处理：请求体该发什么、响应里正文以什么形态出现、服务端拒了字段怎么退让。
 *
 * <p>这里刻意不出现任何具体厂商的模型名 —— 要验证的是「换一家模型也不用改代码」，
 * 所以每条用例都对应一种协议形态，而不是一家服务商的行为。</p>
 */
class OpenAiCompatibleClientProtocolTest {

    private static final String PLAN = "{\"blocks\":[],\"marks\":[]}";

    @Test
    @DisplayName("max-tokens 配成 0 时不发送该字段")
    void omitsMaxTokensWhenZero() {
        DocTemplateProperties.Ai config = config();
        config.setMaxTokens(0);
        Map<String, Object> body = new OpenAiCompatibleClient(config).requestBody(request(0));
        assertFalse(body.containsKey("max_tokens"), String.valueOf(body));
    }

    @Test
    @DisplayName("编排层给了预算就用编排层的值")
    void sendsBudgetFromTheCaller() {
        DocTemplateProperties.Ai config = config();
        config.setMaxTokens(4096);
        Map<String, Object> body = new OpenAiCompatibleClient(config).requestBody(request(2048));
        assertEquals(2048, body.get("max_tokens"));
    }

    @Test
    @DisplayName("temperature 为负数时不发送该字段")
    void omitsTemperatureWhenNegative() {
        DocTemplateProperties.Ai config = config();
        config.setTemperature(-1);
        Map<String, Object> body = new OpenAiCompatibleClient(config).requestBody(request(0));
        assertFalse(body.containsKey("temperature"), String.valueOf(body));
    }

    @Test
    @DisplayName("多模态形态的正文（文本块数组）能拼成一段")
    void joinsContentBlocks() {
        String body = "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":["
                + "{\"type\":\"text\",\"text\":\"{\\\"blocks\\\":[],\"},"
                + "{\"type\":\"text\",\"text\":\"\\\"marks\\\":[]}\"}]}}]}";
        assertEquals("{\"blocks\":[],\n\"marks\":[]}", OpenAiCompatibleClient.contentOf(body));
    }

    @Test
    @DisplayName("推理字段叫 reasoning 时同样能取出 JSON")
    void readsReasoningUnderAnotherName() {
        String body = "{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"\","
                + "\"reasoning\":\"考虑完毕，结果：" + PLAN.replace("\"", "\\\"") + "\"}}]}";
        assertEquals(PLAN, OpenAiCompatibleClient.contentOf(body));
    }

    @Test
    @DisplayName("思考块包在对象里（thinking 字段）也能取出 JSON")
    void readsThinkingBlock() {
        String body = "{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":null,"
                + "\"thinking\":{\"type\":\"thinking\",\"thinking\":\"计划 "
                + PLAN.replace("\"", "\\\"") + "\"}}}]}";
        assertEquals(PLAN, OpenAiCompatibleClient.contentOf(body));
    }

    @Test
    @DisplayName("服务端点名了不认的字段，就找出来去掉重试")
    void findsTheRejectedField() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", "m");
        body.put("response_format", Map.of("type", "json_object"));
        assertEquals("response_format",
                OpenAiCompatibleClient.rejectedField("Invalid parameter: response_format is not supported", body));
    }

    @Test
    @DisplayName("400 里没点名可选字段时不当成参数问题")
    void ignoresUnrelatedBadRequest() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", "m");
        body.put("response_format", Map.of("type", "json_object"));
        assertNull(OpenAiCompatibleClient.rejectedField("Insufficient balance", body));
    }

    @Test
    @DisplayName("请求里本来就没带的字段不参与退让")
    void ignoresFieldsThatWereNotSent() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", "m");
        assertNull(OpenAiCompatibleClient.rejectedField("max_tokens must be positive", body));
    }

    @Test
    @DisplayName("服务端声明上限时按它的数字降低")
    void lowersToTheDeclaredLimit() {
        assertEquals(8192, OpenAiCompatibleClient.lowerLimit(32768,
                "Invalid parameter: max_tokens must be less than or equal to 8192"));
    }

    @Test
    @DisplayName("报错里给的是区间时取上界，不取区间下界")
    void picksTheUpperBoundOfARange() {
        assertEquals(8192, OpenAiCompatibleClient.lowerLimit(32768,
                "the valid range of max_tokens is [1, 8192]"));
    }

    @Test
    @DisplayName("报错里没有数字就减半")
    void halvesWhenNoLimitIsDeclared() {
        assertEquals(16384, OpenAiCompatibleClient.lowerLimit(32768, "max_tokens is invalid"));
    }

    @Test
    @DisplayName("已经降到写不完计划的值时不再往下试")
    void givesUpWhenTooSmall() {
        assertEquals(-1, OpenAiCompatibleClient.lowerLimit(1024, "max_tokens is invalid"));
    }

    @Test
    @DisplayName("额外字段原样合并，但 messages 与 stream 不许覆盖")
    void mergesExtraBodyButKeepsTheRequestShape() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("messages", "原样保留");
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("messages", "被替换");
        extra.put("stream", true);
        extra.put("top_p", 0.9);

        OpenAiCompatibleClient.mergeExtra(body, extra);

        assertEquals("原样保留", body.get("messages"));
        assertFalse(body.containsKey("stream"));
        assertEquals(0.9, body.get("top_p"));
        assertTrue(extra.containsKey("messages"), "合并不应改动入参");
    }

    private static DocTemplateProperties.Ai config() {
        DocTemplateProperties.Ai config = new DocTemplateProperties().getAi();
        config.setBaseUrl("https://api.example.com");
        config.setApiKey("test-key");
        config.setModel("test-model");
        return config;
    }

    private static DocTemplateAiClient.Request request(int maxTokens) {
        return new DocTemplateAiClient.Request("system", "user", "test-model", 0, maxTokens);
    }
}
