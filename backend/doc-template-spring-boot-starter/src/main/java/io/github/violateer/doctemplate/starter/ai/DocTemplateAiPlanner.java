package io.github.violateer.doctemplate.starter.ai;

import io.github.violateer.doctemplate.starter.annotate.DocTemplateMarkPlan;
import io.github.violateer.doctemplate.starter.annotate.DocTemplateSnapshot;
import io.github.violateer.doctemplate.starter.autoconfigure.DocTemplateProperties;
import io.github.violateer.doctemplate.starter.service.DocTemplateAnnotateService;
import io.github.violateer.doctemplate.starter.support.DocTemplateJson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 让模型出标记计划，并把「模型写错了」这件事变成可重试的循环。
 *
 * <p>一次尝试是四步：拼提示词 → 调模型 → 解析成 {@link DocTemplateMarkPlan} → 落笔自检
 * （{@link DocTemplateAnnotateService#check}，内部会真的渲染一遍）。任何一步失败都把错误原文
 * 回灌给模型重来，最多 {@code doc-template.ai.max-attempts} 次。</p>
 *
 * <p><b>为什么失败要回灌而不是直接报错</b>：模型最常见的错误是坐标差一格、字段名写错、
 * 把表头也标了 —— 这些它看到错误信息就能自己改对。而渲染器的报错带行号/段号，是最有效的信号。
 * 反过来，一次就报错给用户看，用户除了「重试」也没别的选择。</p>
 *
 * <p>全部尝试都失败时返回 {@code ok=false} 而不是抛异常：这是「模型没给出可用计划」，
 * 不是「接口出错了」，页面应该把每次的原因列出来，让人决定是补提示词还是自己手写计划。</p>
 */
public class DocTemplateAiPlanner {
    private static final Logger log = LoggerFactory.getLogger(DocTemplateAiPlanner.class);
    private static final int DEFAULT_MAX_INPUT = 60000;

    private final DocTemplateAnnotateService annotate;
    private final DocTemplateAiClient client;
    private final DocTemplateProperties.Ai config;

    /**
     * @param client 可以为 {@code null}：没有可用的模型客户端时，只有 {@code /ai/plan} 不可用，
     *               快照、自检、预览、落库这些不依赖模型的能力照常工作
     */
    public DocTemplateAiPlanner(DocTemplateAnnotateService annotate, DocTemplateAiClient client,
                                DocTemplateProperties.Ai config) {
        this.annotate = annotate;
        this.client = client;
        this.config = config;
    }

    public boolean available() {
        return client != null;
    }

    /** 页面用来决定「显示 AI 导入按钮，还是显示配置说明」。不返回 base-url 和 api-key。 */
    public Map<String, Object> status() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", available());
        result.put("model", available() ? config.getModel() : null);
        result.put("max_attempts", attempts());
        result.put("max_input_chars", maxInputChars());
        return result;
    }

    /**
     * 出计划并自检。
     *
     * @param hint 用户补充要求（例如「日期那栏要拆成开始和结束两个字段」），可为空
     * @return {@code ok=true} 时带 {@code plan} 与自检结果；{@code ok=false} 时带 {@code error}
     *         和每次尝试的失败原因
     */
    public Map<String, Object> plan(long templateId, String hint) throws Exception {
        if (client == null) {
            throw new IllegalArgumentException("AI 标注未启用：设置 doc-template.ai.enabled=true 并配好 base-url、"
                    + "api-key、model，或者自己注册一个 DocTemplateAiClient Bean");
        }
        String snapshotJson = snapshotJson(templateId);
        int attempts = attempts();
        List<Map<String, Object>> failures = new ArrayList<>();
        String feedback = null;
        String lastError = null;
        // 输出上限一次给足：推理模型的思考也计入这里，从小往上涨每涨一档都要先跑满一次超时才失败
        int maxTokens = config.getMaxTokens();
        long started = System.currentTimeMillis();

        for (int attempt = 1; attempt <= attempts; attempt++) {
            long attemptStarted = System.currentTimeMillis();
            DocTemplateMarkPlan plan;
            try {
                String raw = client.complete(new DocTemplateAiClient.Request(
                        DocTemplatePrompts.system(),
                        DocTemplatePrompts.user(snapshotJson, hint, feedback),
                        config.getModel(), config.getTemperature(), maxTokens));
                plan = DocTemplateMarkPlan.parse(raw);
            } catch (DocTemplateAiBudgetException error) {
                // 上限已经给足还被截断，说明这个模型的思考比计划本身长得多。重问是同一个请求：
                // 上限不会再涨，只会再等一次超时，还会把剩下的尝试次数耗掉。
                lastError = message(error);
                failures.add(failure(attempt, "call", lastError, attemptStarted));
                log.warn("第 {} 次输出被截断，停止重试：templateId={}，{}", attempt, templateId, lastError);
                break;
            } catch (Exception error) {
                // 调用失败和解析失败都归到这一步：对模型而言都属于「上次输出不可用」
                lastError = message(error);
                failures.add(failure(attempt, "call", lastError, attemptStarted));
                feedback = "上一次的输出不能用：" + lastError;
                log.warn("第 {} 次出计划失败（调用/解析）：templateId={}，{}", attempt, templateId, lastError);
                continue;
            }

            try {
                plan.validate();
            } catch (IllegalArgumentException error) {
                lastError = error.getMessage();
                failures.add(failure(attempt, "validate", lastError, attemptStarted));
                feedback = "计划校验没通过：" + lastError;
                log.warn("第 {} 次出计划失败（校验）：templateId={}，{}", attempt, templateId, lastError);
                continue;
            }

            Map<String, Object> check = annotate.check(templateId, plan);
            if (!Boolean.TRUE.equals(check.get("ok"))) {
                lastError = String.valueOf(check.get("error"));
                failures.add(failure(attempt, "check", lastError, attemptStarted));
                feedback = "落笔或渲染没通过：" + lastError + loopSummary(plan);
                log.warn("第 {} 次出计划失败（自检）：templateId={}，{}", attempt, templateId, lastError);
                continue;
            }

            log.info("第 {} 次出计划通过：templateId={}，{} 个块，{} 条标记", attempt, templateId,
                    plan.blocks().size(), plan.marks().size());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("ok", true);
            result.put("template_id", templateId);
            result.put("attempt", attempt);
            result.put("attempts", failures.size() + 1);
            result.put("model", config.getModel());
            result.put("elapsed_ms", System.currentTimeMillis() - started);
            result.put("plan", plan.toMap());
            // 自检结果平铺进来：块清单、占位符、没绑上的占位符、告警、产出大小，页面直接照着渲染
            result.putAll(check);
            result.put("ok", true);
            return result;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", false);
        result.put("template_id", templateId);
        result.put("error", "尝试 " + attempts + " 次仍未生成可用计划，最后一次失败原因：" + lastError);
        result.put("attempts", failures.size());
        result.put("failures", failures);
        result.put("elapsed_ms", System.currentTimeMillis() - started);
        return result;
    }

    /**
     * 快照序列化成提示词里那段 JSON。
     *
     * <p>超过 {@code max-input-chars} 时从末尾开始丢内容（先段落、再表格、再工作表），
     * 而不是按字符截断 —— 截断会切出一个不合法的 JSON，模型只会更糊涂。丢过内容会加一条告警。</p>
     */
    private String snapshotJson(long templateId) throws Exception {
        DocTemplateSnapshot snapshot = annotate.readSnapshot(templateId);
        int limit = maxInputChars();
        String json = DocTemplateJson.toJson(snapshot);
        if (json.length() <= limit) return json;

        List<DocTemplateSnapshot.Paragraph> paragraphs = new ArrayList<>(snapshot.paragraphs());
        List<DocTemplateSnapshot.Table> tables = new ArrayList<>(snapshot.tables());
        List<DocTemplateSnapshot.Sheet> sheets = new ArrayList<>(snapshot.sheets());
        List<String> warnings = new ArrayList<>(snapshot.warnings());
        warnings.add("文档结构超过 " + limit + " 字符，末尾内容已省略，只标给到的部分");
        while (json.length() > limit) {
            if (!paragraphs.isEmpty()) {
                paragraphs.remove(paragraphs.size() - 1);
            } else if (tables.size() > 1) {
                tables.remove(tables.size() - 1);
            } else if (sheets.size() > 1) {
                sheets.remove(sheets.size() - 1);
            } else {
                break;
            }
            json = DocTemplateJson.toJson(new DocTemplateSnapshot(snapshot.fileType(), snapshot.coordinateBase(),
                    paragraphs, tables, sheets, warnings));
        }
        log.warn("文档结构超过 {} 字符，已裁到 {} 字符：templateId={}", limit, json.length(), templateId);
        return json;
    }

    private static Map<String, Object> failure(int attempt, String stage, String error, long started) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("attempt", attempt);
        item.put("stage", stage);
        item.put("error", error);
        item.put("elapsed_ms", System.currentTimeMillis() - started);
        return item;
    }

    /**
     * 把上一次写出的循环标记摘出来，跟在失败原因后面一起回灌。
     *
     * <p>重试时模型看不到自己的上一版输出，只拿到一句错误，于是按同一份快照重新推一遍 ——
     * 遇到「外层明细里套着一段子明细」这种表，它会稳定地再写一次嵌套，三次尝试一模一样。
     * 带上它自己写的范围和一行改法，才能让它就地改而不是重写。</p>
     */
    private static String loopSummary(DocTemplateMarkPlan plan) {
        List<String> loops = new ArrayList<>();
        for (DocTemplateMarkPlan.Mark mark : plan.marks()) {
            if (!"loop".equals(mark.kind()) && !"region".equals(mark.kind())) continue;
            DocTemplateMarkPlan.At at = mark.at();
            StringBuilder coords = new StringBuilder("{");
            if (at.paragraph() != null) coords.append("paragraph:").append(at.paragraph()).append(", ");
            if (at.sheet() != null) coords.append("sheet:").append(at.sheet()).append(", ");
            if (at.table() != null) coords.append("table:").append(at.table()).append(", ");
            if (at.row() != null) {
                coords.append("row:").append(at.row());
                if (at.rowTo() != null) coords.append(", rowTo:").append(at.rowTo());
            }
            coords.append('}');
            loops.add("- " + mark.kind() + " " + mark.block() + " " + coords);
        }
        if (loops.isEmpty()) return "";
        return "\n\n你上一次写的循环标记：\n" + String.join("\n", loops)
                + "\n两个范围互相包含时只能留一层，不要重写一份结构相同的计划。";
    }

    private int attempts() {
        return Math.max(1, config.getMaxAttempts());
    }

    private int maxInputChars() {
        return config.getMaxInputChars() <= 0 ? DEFAULT_MAX_INPUT : config.getMaxInputChars();
    }

    private static String message(Throwable error) {
        String text = error.getMessage();
        if (text != null && !text.isBlank()) return text;
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
    }
}
