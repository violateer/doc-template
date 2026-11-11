package io.github.violateer.doctemplate.starter.ai;

import io.github.violateer.doctemplate.starter.autoconfigure.DocTemplateProperties;
import io.github.violateer.doctemplate.starter.support.DocTemplateJson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * OpenAI Chat Completions 兼容实现，只用 JDK 自带的 {@link HttpClient}，不引任何 HTTP 库。
 *
 * <p>一个协议覆盖任何 OpenAI Chat Completions 兼容接口（DeepSeek、通义、文心、Kimi、Azure 等）：
 * 换服务商只改 {@code base-url} 和 {@code model}，不用为每家写适配器 —— 这也是积木报表的做法，事实标准。</p>
 *
 * <p><b>不按厂商做适配</b>。模型能力差异（推理过程、参数支持范围、字段命名）每年都在变，
 * 内置一份厂商对照表注定追不上。这里只做协议层能通用的事：不替服务端决定输出上限、
 * 推理内容不认字段名只认文本、服务端拒绝某个可选字段时去掉重试。厂商私有开关留给
 * {@code doc-template.ai.extra-body} 透传。</p>
 *
 * <p>错误只带状态码和响应体前 300 字：正文里可能有文档内容，日志和对外消息都不该出现全文。</p>
 */
public class OpenAiCompatibleClient implements DocTemplateAiClient {
    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleClient.class);
    private static final int MAX_ERROR_CHARS = 300;

    /**
     * 服务端可能不认的可选字段，按「去掉代价从小到大」排列。
     *
     * <p>{@code response_format} 去掉只是失去 JSON 模式（提示词里已经要求只输出 JSON）；
     * {@code temperature} 去掉用服务端默认值。</p>
     *
     * <p>{@code max_tokens} 不在这里：它被拒是因为超过服务端允许值，处理方式是降低而不是去掉 ——
     * 去掉会退回服务端更小的默认值，反而更容易把正文挤没。</p>
     */
    private static final List<String> OPTIONAL_FIELDS = List.of("response_format", "temperature");

    /** 输出上限降到这个值以下就没有意义了：连计划 JSON 都写不完。 */
    private static final int MIN_MAX_TOKENS = 1024;

    /** 在报错里定位「max_tokens」这个词，随后在它后面一段里找服务端声明的上限。 */
    private static final Pattern MAX_TOKENS_ANCHOR = Pattern.compile("(?i)max_tokens");

    private static final Pattern NUMBER = Pattern.compile("\\d{1,7}");

    /** 各家放推理过程的字段名，从新到旧。不认名字，只当文本处理。 */
    private static final List<String> REASONING_KEYS =
            List.of("reasoning_content", "reasoning", "thinking", "reasoning_text");

    /** 文本块里可能装正文的键：多模态 content、Anthropic 风格的 thinking 块都在这几个键上。 */
    private static final List<String> TEXT_KEYS = List.of("text", "thinking", "content");

    private final String url;
    private final String apiKey;
    private final String model;
    private final double temperature;
    private final int maxTokens;
    private final boolean jsonMode;
    private final long timeoutMs;
    private final Map<String, Object> extraBody;
    private final HttpClient http;

    public OpenAiCompatibleClient(DocTemplateProperties.Ai config) {
        if (isBlank(config.getBaseUrl()))
            throw new IllegalStateException("开启了 doc-template.ai.enabled，但没有配置 doc-template.ai.base-url");
        if (isBlank(config.getApiKey()))
            throw new IllegalStateException("开启了 doc-template.ai.enabled，但没有配置 doc-template.ai.api-key");
        if (isBlank(config.getModel()))
            throw new IllegalStateException("开启了 doc-template.ai.enabled，但没有配置 doc-template.ai.model");
        String path = isBlank(config.getCompletionsPath()) ? "/v1/chat/completions" : config.getCompletionsPath().trim();
        this.url = resolveUrl(config.getBaseUrl().trim(), path.startsWith("/") ? path : "/" + path);
        this.apiKey = config.getApiKey().trim();
        this.model = config.getModel().trim();
        this.temperature = config.getTemperature();
        this.maxTokens = config.getMaxTokens();
        this.jsonMode = config.isJsonMode();
        this.timeoutMs = config.getTimeoutMs() <= 0 ? 120000 : config.getTimeoutMs();
        this.extraBody = Map.copyOf(config.getExtraBody() == null ? Map.of() : config.getExtraBody());
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.min(this.timeoutMs, 10000)))
                .build();
        // 拼出来的地址直接打一行：base-url 写重了会 404，有这一行能立刻看出来
        log.info("AI 标注接口：{}（model={}，输出上限={}{}）", this.url, this.model,
                this.maxTokens > 0 ? this.maxTokens + " tokens" : "由服务端决定",
                extraBody.isEmpty() ? "" : "，额外参数 " + extraBody.keySet());
    }

    @Override
    public String complete(Request request) throws Exception {
        Map<String, Object> body = requestBody(request);
        long started = System.currentTimeMillis();
        HttpResponse<String> response = send(body);
        // 400 说明请求形态和服务端对不上，分两类退让：
        //   输出上限超过服务端允许值 → 降到它能接受的值（去掉反而会退回更小的默认值）；
        //   其余可选字段不认 → 去掉再试。
        // 最多 5 轮，避免异常响应把请求拖成循环。
        List<String> adjusted = new ArrayList<>();
        for (int round = 0; round < 5 && response.statusCode() == 400; round++) {
            String reason = response.body();
            if (body.containsKey("max_tokens") && mentions(reason, "max_tokens")) {
                int current = ((Number) body.get("max_tokens")).intValue();
                int next = lowerLimit(current, reason);
                if (next <= 0) break;
                body.put("max_tokens", next);
                adjusted.add("max_tokens " + current + "→" + next);
                log.warn("服务端不接受输出上限 {}，尚未开始生成，降到 {} 重试：{}", current, next, shorten(reason));
                response = send(body);
                continue;
            }
            String field = rejectedField(reason, body);
            if (field == null) break;
            body.remove(field);
            adjusted.add("去掉 " + field);
            log.warn("服务端不接受 {}，已去掉后重试：{}", field, shorten(reason));
            response = send(body);
        }
        long elapsed = System.currentTimeMillis() - started;

        String text = response.body() == null ? "" : response.body();
        if (response.statusCode() / 100 != 2)
            // 带上实际请求地址：404 基本都是 base-url 和 completions-path 拼重了
            throw new IllegalStateException("AI 接口返回 HTTP " + response.statusCode() + "（" + url + "）"
                    + (adjusted.isEmpty() ? "" : "，已调整 " + adjusted) + "：" + shorten(text));
        String content = contentOf(text);
        log.info("模型返回计划：model={}，{} ms，{} 字符{}", model, elapsed, content.length(),
                adjusted.isEmpty() ? "" : "，已调整 " + adjusted);
        return content;
    }

    /** 按配置拼请求体。{@code 0} 与负数都表示「不发这个字段」。 */
    Map<String, Object> requestBody(Request request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        if (temperature >= 0) body.put("temperature", temperature);
        // 输出上限：编排层给的优先。给足是有意的 —— 推理模型的思考也计入这里，
        // 值小了正文还没开始就被截断；模型想完会自己停，不会真的用满。
        int limit = request.maxTokens() > 0 ? request.maxTokens() : maxTokens;
        if (limit > 0) body.put("max_tokens", limit);
        body.put("messages", List.of(
                Map.of("role", "system", "content", request.systemPrompt()),
                Map.of("role", "user", "content", request.userPrompt())));
        // 不是所有服务商都认这个字段，认不出的直接 400，所以做成开关；被拒时会自动去掉重试
        if (jsonMode) body.put("response_format", Map.of("type", "json_object"));
        mergeExtra(body, extraBody);
        return body;
    }

    /**
     * 服务端拒绝当前输出上限时，算出它允许的值。
     *
     * <p><b>为什么是从大往小退</b>：两种失败根本不是一回事。</p>
     * <ul>
     *   <li>上限<b>太大</b>：服务端参数校验直接回 400，请求压根没有开始生成，模型一个字都没跑，
     *       耗时是几百毫秒；</li>
     *   <li>上限<b>太小</b>：请求成功（200），但 {@code finish_reason=length}，正文被截断或为空。
     *       要跑满一次完整生成（几十秒）才发现白跑。</li>
     * </ul>
     * <p>所以先试大的：被拒只损失一次往返，一旦通过就是一次完整的生成。反过来从小到大，
     * 每涨一档都要先白跑几十秒。降到 8K 之后不是「更不行」，而是「这次才真正开始生成」。</p>
     *
     * <p>优先从错误信息里取服务端声明的上限（{@code max_tokens must be less than or equal to 8192}
     * 这类），一次到位；取不到才减半。</p>
     *
     * @return 下一次要用的上限；已经小到没有意义时返回 {@code -1}
     */
    static int lowerLimit(int current, String reason) {
        Integer allowed = allowedLimit(reason);
        int next = allowed != null && allowed > 0 && allowed < current ? allowed : current / 2;
        return next >= MIN_MAX_TOKENS ? next : -1;
    }

    /**
     * 从错误信息里取出服务端声明的上限。
     *
     * <p>在 {@code max_tokens} 之后的一段文本里找所有数字并取最大的一个，这样
     * {@code must be less than or equal to 8192} 和 {@code the valid range of max_tokens is [1, 8192]}
     * 两种写法都能得到 8192；小于 256 的数字（区间下界、占位符）直接忽略。</p>
     */
    static Integer allowedLimit(String reason) {
        if (reason == null) return null;
        Matcher anchor = MAX_TOKENS_ANCHOR.matcher(reason);
        Integer allowed = null;
        while (anchor.find()) {
            String nearby = reason.substring(anchor.end(), Math.min(reason.length(), anchor.end() + 60));
            Matcher numbers = NUMBER.matcher(nearby);
            while (numbers.find()) {
                int value = Integer.parseInt(numbers.group());
                if (value >= 256 && (allowed == null || value > allowed)) allowed = value;
            }
        }
        return allowed;
    }

    private HttpResponse<String> send(Map<String, Object> body) throws Exception {
        HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(timeoutMs))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(DocTemplateJson.toJson(body), StandardCharsets.UTF_8))
                .build();
        try {
            return http.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpTimeoutException timeout) {
            // 推理模型的思考时间不可控，撞超时和「预算不够」是同一类问题：模型没写完
            throw new IllegalStateException("模型响应超时（" + timeoutMs + "ms）。推理模型的思考时间不可控，"
                    + "可调大 doc-template.ai.timeout-ms；模型支持关闭思考时，也可在 doc-template.ai.extra-body "
                    + "中按服务商文档关闭", timeout);
        }
    }

    /**
     * 从 400 的响应体里找出「服务端明确不认的那个可选字段」。
     *
     * <p>只看当前请求里确实带了、且错误信息里被点名的字段，避免把无关的 400 也当成参数问题
     * 反复重试。找不到就返回 {@code null}，让调用方按普通错误抛出。</p>
     *
     * <p>{@code max_tokens} 不走这里：它被拒时应当降低而不是去掉，见 {@link #lowerLimit(int, String)}。</p>
     */
    static String rejectedField(String text, Map<String, Object> body) {
        if (text == null || text.isBlank()) return null;
        for (String field : OPTIONAL_FIELDS) {
            if (body.containsKey(field) && text.contains(field)) return field;
        }
        return null;
    }

    /**
     * 从 {@code choices[0].message} 取正文。
     *
     * <p>推理模型的思考过程同样计入输出上限，预算不够时正文会直接为空、{@code finish_reason} 是
     * {@code length}。各家放思考内容的字段名不一样（{@code reasoning_content}、{@code reasoning}、
     * {@code thinking}），这里不认名字只认文本，因此换模型不用改代码。分三层处理：</p>
     *
     * <ol>
     *   <li>正文完整 —— 直接用；</li>
     *   <li>正文为空，但思考过程里已经写出了完整 JSON —— 捞出来用，救回这一次；</li>
     *   <li>都拿不到 —— 抛 {@link DocTemplateAiBudgetException}，由编排层决定要不要再问一次。</li>
     * </ol>
     */
    static String contentOf(String text) {
        Object parsed = DocTemplateJson.read(text);
        if (!(parsed instanceof Map<?, ?> root))
            throw new IllegalStateException("AI 接口返回的不是 JSON 对象：" + shorten(text));
        Object choices = root.get("choices");
        if (!(choices instanceof List<?> list) || list.isEmpty())
            throw new IllegalStateException("AI 接口返回里没有 choices：" + shorten(text));
        Map<?, ?> choice = list.get(0) instanceof Map<?, ?> first ? first : Map.of();
        Map<?, ?> message = choice.get("message") instanceof Map<?, ?> value ? value : Map.of();
        Object finishReason = choice.get("finish_reason");
        String finish = finishReason == null ? "未知" : String.valueOf(finishReason);
        String usage = usageOf(root.get("usage"));

        String content = textOf(message.get("content"));
        if (content != null && !content.isBlank()) {
            // 正常收尾，或者虽然标了 length 但 JSON 已经写完整了
            if (!"length".equals(finish) || isJsonObject(content)) return content;
            throw new DocTemplateAiBudgetException("模型输出被截断（finish_reason=length" + usage
                    + "）：可调大 doc-template.ai.max-tokens");
        }

        for (String key : REASONING_KEYS) {
            String thought = textOf(message.get(key));
            if (thought == null || thought.isBlank()) continue;
            String salvaged = jsonInReasoning(thought);
            if (salvaged != null) {
                log.warn("模型正文为空，已从 {} 中取出完整 JSON（finish_reason={}）", key, finish);
                return salvaged;
            }
            throw new DocTemplateAiBudgetException("模型只输出了推理内容，正文为空（finish_reason=" + finish + usage
                    + "）：推理过程占满了输出上限。可调大 doc-template.ai.max-tokens，"
                    + "或调大 doc-template.ai.timeout-ms，或按服务商文档在 doc-template.ai.extra-body 中关闭思考");
        }
        throw new IllegalStateException("AI 接口返回的 content 为空（finish_reason=" + finish + usage + "）");
    }

    /**
     * 把任意形态的文本字段拍平成字符串。
     *
     * <p>同一份协议里正文有三种常见形态：纯字符串、多模态的文本块数组
     * （{@code [{"type":"text","text":"..."}]}）、以及包了一层的对象（{@code {"text":"..."}}）。
     * 推理内容还多一种 {@code {"type":"thinking","thinking":"..."}}。全都收在这里，
     * 调用方不需要知道自己接的是哪家。</p>
     */
    static String textOf(Object value) {
        if (value instanceof String text) return text;
        if (value instanceof List<?> parts) {
            StringBuilder joined = new StringBuilder();
            for (Object part : parts) {
                String text = textOf(part);
                if (text == null || text.isBlank()) continue;
                if (joined.length() > 0) joined.append('\n');
                joined.append(text);
            }
            return joined.toString();
        }
        if (value instanceof Map<?, ?> map) {
            for (String key : TEXT_KEYS) {
                String text = textOf(map.get(key));
                if (text != null && !text.isBlank()) return text;
            }
        }
        return null;
    }

    /**
     * 从推理文本里捞最后一个完整 JSON 对象。
     *
     * <p>部分推理模型会在思考末尾把最终 JSON 写出来，只是没来得及进入正文。这里从后往前找配平的
     * 花括号，能解析成带 {@code blocks} 或 {@code marks} 的对象才认 —— 避免把思考里的示例片段当成计划。</p>
     */
    static String jsonInReasoning(String reasoning) {
        int end = reasoning.lastIndexOf('}');
        while (end >= 0) {
            int start = matchingBrace(reasoning, end);
            if (start >= 0) {
                String candidate = reasoning.substring(start, end + 1);
                if (isJsonObject(candidate)) return candidate;
            }
            end = reasoning.lastIndexOf('}', end - 1);
        }
        return null;
    }

    /** 从 {@code end} 这个右花括号往前找与它配对的那个左花括号。 */
    private static int matchingBrace(String text, int end) {
        int depth = 0;
        for (int i = end; i >= 0; i--) {
            char c = text.charAt(i);
            if (c == '}') depth++;
            else if (c == '{' && --depth == 0) return i;
        }
        return -1;
    }

    /** 文本能否解析成对象，且看起来就是标记计划（有 blocks 或 marks 键）。 */
    private static boolean isJsonObject(String text) {
        try {
            return DocTemplateJson.read(text) instanceof Map<?, ?> map
                    && (map.containsKey("blocks") || map.containsKey("marks"));
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /** 把 {@code usage.completion_tokens} 拼成一句，用来判断是不是撞上了输出上限。 */
    private static String usageOf(Object usage) {
        if (!(usage instanceof Map<?, ?> map)) return "";
        Object completion = map.get("completion_tokens");
        return completion == null ? "" : "，completion_tokens=" + completion;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean mentions(String text, String word) {
        return text != null && text.contains(word);
    }

    /**
     * 把 {@code extra-body} 合并进请求体。
     *
     * <p>用来传各家自己的开关（关思考、调 reasoning effort 之类），原样合并、不做解释 ——
     * 推理模型的关闭方式各厂商都不一样，内置适配注定追不上，透传才是能一直用下去的做法。</p>
     *
     * <p>{@code messages} 和 {@code stream} 例外：覆盖前者等于换掉整个对话，覆盖后者会让响应变成流式，
     * 解析必然失败，所以跳过并记一条告警。</p>
     */
    static void mergeExtra(Map<String, Object> body, Map<String, Object> extra) {
        if (extra == null || extra.isEmpty()) return;
        for (Map.Entry<String, Object> entry : extra.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.isBlank()) continue;
            if ("messages".equals(key) || "stream".equals(key)) {
                log.warn("doc-template.ai.extra-body 里的 {} 不会生效：会破坏请求结构", key);
                continue;
            }
            body.put(key, entry.getValue());
        }
    }

    /**
     * 拼请求地址。
     *
     * <p>base-url 的约定是「不含接口路径」，但实际配置里经常被写成 {@code .../v1}，甚至整条
     * {@code .../v1/chat/completions}。直接拼接会得到 {@code /v1/v1/chat/completions} 这类地址，
     * 服务端只会回 404。这里统一收口：已经以接口路径结尾就直接用，只多写了一段 {@code /v1} 就只留一份。</p>
     */
    static String resolveUrl(String baseUrl, String path) {
        String base = trimSlash(baseUrl);
        if (base.endsWith(path)) return base;
        if (base.endsWith("/v1") && path.startsWith("/v1/")) return base + path.substring("/v1".length());
        return base + path;
    }

    private static String trimSlash(String value) {
        String result = value;
        while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
        return result;
    }

    private static String shorten(String text) {
        String oneLine = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= MAX_ERROR_CHARS ? oneLine : oneLine.substring(0, MAX_ERROR_CHARS) + "…";
    }
}
