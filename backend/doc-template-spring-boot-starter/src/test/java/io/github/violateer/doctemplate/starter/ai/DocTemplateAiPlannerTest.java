package io.github.violateer.doctemplate.starter.ai;

import io.github.violateer.doctemplate.starter.annotate.DocTemplateMarkPlan;
import io.github.violateer.doctemplate.starter.annotate.DocTemplateSnapshot;
import io.github.violateer.doctemplate.starter.autoconfigure.DocTemplateProperties;
import io.github.violateer.doctemplate.starter.service.DocTemplateAnnotateService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 出计划的编排：解析容错、失败回灌重试、彻底失败时的回报。
 *
 * <p>这里不连模型也不碰文件：假客户端返回写死的文本，假自检直接校验计划。
 * 要验证的只有一件事 —— 模型写错了，错误有没有真的回到下一轮提示词里，
 * 以及「试完了还不行」时用户拿到的是不是一份能看懂的原因清单。</p>
 */
class DocTemplateAiPlannerTest {

    private static final String GOOD = """
            {
              "blocks": [{"code": "main", "name": "主信息", "shape": "object",
                "fields": [{"field": "report_no", "label": "报告编号", "type": "string", "samples": ["2026-0011"]}]}],
              "marks": [{"kind": "value", "at": {"paragraph": 0}, "block": "main",
                "field": "report_no", "replace": "2026-0011"}]
            }
            """;

    /** 字段名用了 SQL 关键字，{@code validate()} 会直接拒绝 —— 用来模拟「模型写错了」。 */
    private static final String BAD = """
            {
              "blocks": [{"code": "main", "name": "主信息", "shape": "object",
                "fields": [{"field": "date", "label": "日期", "type": "date", "samples": ["2026-11-01"]}]}],
              "marks": [{"kind": "value", "at": {"paragraph": 0}, "block": "main",
                "field": "date", "replace": "2026-11-01"}]
            }
            """;

    @Test
    void feedsTheFailureBackIntoTheNextPrompt() throws Exception {
        RecordingClient client = new RecordingClient(BAD, GOOD);
        Map<String, Object> result = planner(client, 3).plan(1L, null);

        assertEquals(true, result.get("ok"), String.valueOf(result));
        assertEquals(2, result.get("attempt"), "第一次写错，第二次应该通过");
        assertEquals(2, client.prompts.size());
        assertFalse(client.prompts.get(0).contains("上一次尝试没通过"), "第一次不该带失败反馈");
        assertTrue(client.prompts.get(1).contains("上一次尝试没通过"), "第二次要带上失败反馈");
        assertTrue(client.prompts.get(1).contains("SQL 关键字"), "回灌的必须是校验器的原始错误");
    }

    @Test
    void stripsMarkdownFencesAroundTheJson() {
        DocTemplateMarkPlan plan = DocTemplateMarkPlan.parse("""
                好的，这是计划：
                ```json
                {"blocks": [], "marks": []}
                ```
                以上。
                """);
        assertTrue(plan.blocks().isEmpty());
    }

    @Test
    void reportsEveryAttemptWhenNothingWorks() throws Exception {
        RecordingClient client = new RecordingClient(BAD, BAD, BAD);
        Map<String, Object> result = planner(client, 3).plan(1L, null);

        assertEquals(false, result.get("ok"), String.valueOf(result));
        assertEquals(3, client.prompts.size());
        assertEquals(3, result.get("attempts"));
        assertEquals(3, ((List<?>) result.get("failures")).size(), "每次的失败原因都要保留");
        assertTrue(String.valueOf(result.get("error")).contains("尝试 3 次"), String.valueOf(result.get("error")));
    }

    /** 输出上限已一次给足：撞上限说明这个模型想得比计划长得多，重问同一个请求没有意义。 */
    @Test
    void stopsWhenTheOutputIsTruncated() throws Exception {
        TruncatingClient client = new TruncatingClient(GOOD);
        Map<String, Object> result = planner(client, 3).plan(1L, null);

        assertEquals(false, result.get("ok"), String.valueOf(result));
        assertEquals(1, client.maxTokens.size(), "重问不会改变上限，不该再耗一次超时");
        assertEquals(32768, client.maxTokens.get(0), "默认一次给足，不从 4K 起步翻倍");
    }

    @Test
    void refusesWhenNoClientIsConfigured() {
        DocTemplateAiPlanner planner = new DocTemplateAiPlanner(new FakeAnnotate(), null, config(1));
        assertFalse(planner.available());
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> planner.plan(1L, null));
        assertTrue(error.getMessage().contains("AI 标注未启用"), error.getMessage());
    }

    /** 计划在响应里是驼峰，原样提交回来必须还能解析 —— {@code rowTo} 丢了样例行就删不掉。 */
    @Test
    void planSurvivesARoundTripThroughTheResponse() {
        DocTemplateMarkPlan original = new DocTemplateMarkPlan(
                List.of(new DocTemplateMarkPlan.Block("items", "明细", "array",
                        List.of(new DocTemplateMarkPlan.Field("sample_no", "样品编号", "string", List.of("1#"))))),
                List.of(new DocTemplateMarkPlan.Mark("loop",
                        new DocTemplateMarkPlan.At(null, null, 0, 1, 3, null, null),
                        "items", null, null, List.of(1), "sample_no", null)), List.of());

        DocTemplateMarkPlan parsed = DocTemplateMarkPlan.parse(
                io.github.violateer.doctemplate.starter.support.DocTemplateJson.toJson(original.toMap()));

        assertEquals(original, parsed, "toMap 与 parse 必须是同一套命名，否则回传的计划会丢字段");
        assertEquals(3, parsed.marks().get(0).at().rowTo());
    }

    private static DocTemplateAiPlanner planner(DocTemplateAiClient client, int maxAttempts) {
        return new DocTemplateAiPlanner(new FakeAnnotate(), client, config(maxAttempts));
    }

    private static DocTemplateProperties.Ai config(int maxAttempts) {
        DocTemplateProperties.Ai ai = new DocTemplateProperties().getAi();
        ai.setMaxAttempts(maxAttempts);
        return ai;
    }

    /** 按顺序返回写死的文本，并记下每次收到的 user prompt。 */
    private static final class RecordingClient implements DocTemplateAiClient {
        private final List<String> responses;
        private final List<String> prompts = new ArrayList<>();
        private int cursor;

        private RecordingClient(String... responses) {
            this.responses = List.of(responses);
        }

        @Override
        public Response complete(Request request) {
            prompts.add(request.userPrompt());
            return new Response(responses.get(Math.min(cursor++, responses.size() - 1)));
        }
    }

    /** 第一次报「输出被截断」，之后正常返回；记下每次要的上限和提示词。 */
    private static final class TruncatingClient implements DocTemplateAiClient {
        private final List<Integer> maxTokens = new ArrayList<>();
        private final List<String> prompts = new ArrayList<>();
        private final String answer;
        private int cursor;

        private TruncatingClient(String answer) {
            this.answer = answer;
        }

        @Override
        public Response complete(Request request) {
            maxTokens.add(request.maxTokens());
            prompts.add(request.userPrompt());
            if (cursor++ == 0) throw new DocTemplateAiBudgetException("推理过程占满了输出上限");
            return new Response(answer);
        }
    }

    /** 只覆盖出计划用得到的几个方法：文件给固定内容，快照固定，自检就是校验。 */
    private static final class FakeAnnotate extends DocTemplateAnnotateService {
        private FakeAnnotate() {
            super(null, null, null, null);
        }

        @Override
        public Source source(long templateId) {
            return new Source(new byte[0], "DOCX");
        }

        @Override
        public DocTemplateSnapshot readSnapshot(byte[] source, String fileType) {
            return new DocTemplateSnapshot("DOCX", "0",
                    List.of(new DocTemplateSnapshot.Paragraph(0, "报告编号：2026-0011", null, 1)),
                    List.of(), List.of(), List.of());
        }

        @Override
        public Map<String, Object> check(byte[] source, String fileType, DocTemplateMarkPlan plan) {
            Map<String, Object> result = new LinkedHashMap<>();
            try {
                plan.validate();
                result.put("ok", true);
            } catch (IllegalArgumentException error) {
                result.put("ok", false);
                result.put("error", error.getMessage());
            }
            return result;
        }
    }
}
