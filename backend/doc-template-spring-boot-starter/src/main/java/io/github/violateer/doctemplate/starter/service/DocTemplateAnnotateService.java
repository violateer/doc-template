package io.github.violateer.doctemplate.starter.service;

import io.github.violateer.doctemplate.starter.annotate.DocTemplateAnnotator;
import io.github.violateer.doctemplate.starter.annotate.DocTemplateMarkPlan;
import io.github.violateer.doctemplate.starter.annotate.DocTemplateMockData;
import io.github.violateer.doctemplate.starter.annotate.DocTemplateSnapshot;
import io.github.violateer.doctemplate.starter.annotate.DocTemplateSnapshotReader;
import io.github.violateer.doctemplate.starter.autoconfigure.DocTemplateProperties;
import io.github.violateer.doctemplate.starter.db.DocTemplateDialect;
import io.github.violateer.doctemplate.starter.db.DocTemplateDialectProvider;
import io.github.violateer.doctemplate.starter.support.DocTemplateJson;
import io.github.violateer.doctemplate.starter.support.DocTemplateSqlValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 结构化标注：读懂文档 → 按计划落笔 → 用模拟数据跑一次渲染 → 通过后才落库。
 *
 * <p>三步都不依赖大模型：计划可以由模型给，也可以由人写一份 JSON 贴进来。模型那层只是「谁填这份 JSON」，
 * 换模型、换提示词都不影响这里。</p>
 *
 * <p><b>自检为什么是渲染一次</b>：语法校验只能发现「标记写错」，发现不了「标记指向的数组不存在」
 * 「字段名拼错」这类问题——那些只有真跑一遍才会暴露。而且渲染器的报错带行号/段号，
 * 回灌给模型改是最有效的信号。</p>
 */
public class DocTemplateAnnotateService {
    private static final Logger log = LoggerFactory.getLogger(DocTemplateAnnotateService.class);

    private final DocTemplateService templates;
    private final DocTemplateRenderer renderer;
    private final DocTemplateDialectProvider dialects;
    private final DocTemplateProperties properties;
    private final DocTemplateSnapshotReader snapshotReader = new DocTemplateSnapshotReader();
    private final DocTemplateAnnotator annotator = new DocTemplateAnnotator();

    public DocTemplateAnnotateService(DocTemplateService templates, DocTemplateRenderer renderer,
                                      DocTemplateDialectProvider dialects, DocTemplateProperties properties) {
        this.templates = templates;
        this.renderer = renderer;
        this.dialects = dialects;
        this.properties = properties;
    }

    /** 文档结构快照：坐标 + 文本 + 轻量样式，供模型（或人）判断哪些是变量。 */
    public Map<String, Object> snapshot(long templateId) throws Exception {
        DocTemplateSnapshot snapshot = readSnapshot(templateId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("template_id", templateId);
        result.put("version_id", templates.currentVersionId(templateId));
        result.put("file_type", snapshot.fileType());
        // 走模块统一的序列化：响应一律下划线命名，别让这一处冒出一堆驼峰
        result.put("snapshot", DocTemplateJson.toMap(snapshot));
        return result;
    }

    /**
     * 按文件字节取快照。
     *
     * <p>AI 导入在落库前还没有模板，文件在导入任务里，所以这里不能只认 {@code templateId}。
     * 返回结构与 {@link #snapshot(long)} 一致，只是没有模板与版本号。</p>
     */
    public Map<String, Object> snapshot(byte[] source, String fileType) throws Exception {
        DocTemplateSnapshot snapshot = readSnapshot(source, fileType);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("file_type", snapshot.fileType());
        result.put("snapshot", DocTemplateJson.toMap(snapshot));
        return result;
    }

    /** 快照对象本身。拼提示词时要用它按上限裁剪，所以单独暴露一份，不再从 Map 里反解析。 */
    public DocTemplateSnapshot readSnapshot(long templateId) throws Exception {
        return readSnapshot(templates.currentVersionBytes(templateId), templates.currentFileType(templateId));
    }

    /** 快照对象本身（按文件字节）。 */
    public DocTemplateSnapshot readSnapshot(byte[] source, String fileType) throws Exception {
        return snapshotReader.read(source, fileType);
    }

    /** 模板当前版本的文件内容与类型。标注的每一步都要用它，所以单独包一层。 */
    public Source source(long templateId) throws Exception {
        return new Source(templates.currentVersionBytes(templateId), templates.currentFileType(templateId));
    }

    /** 一份待标注的文件：字节 + 类型。AI 导入在落库前只有这两样。 */
    public record Source(byte[] bytes, String fileType) {
    }

    /**
     * 只校验不落库：落笔 + 生成模拟 SQL + 渲染一次。
     *
     * <p>计划本身写错（编码非法、字段不存在、坐标缺失）抛 400；坐标越界、渲染失败这类
     * 「文档和计划对不上」的问题放进 {@code ok=false}，由页面直接显示原因。</p>
     */
    public Map<String, Object> check(long templateId, DocTemplateMarkPlan plan) throws Exception {
        plan.validate();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("template_id", templateId);
        try {
            Verified verified = verify(templates.currentVersionBytes(templateId),
                    templates.currentFileType(templateId), plan);
            result.put("ok", true);
            result.put("version_id", templates.currentVersionId(templateId));
            fill(result, verified, plan);
            return result;
        } catch (Exception error) {
            log.warn("模板标注自检未通过：templateId={}", templateId, error);
            return failed(result, error);
        }
    }

    /**
     * 只校验不落库（按文件字节）：AI 导入在 APPLY 之前还没有模板版本，自检只能针对任务里的文件。
     */
    public Map<String, Object> check(byte[] source, String fileType, DocTemplateMarkPlan plan) {
        plan.validate();
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            Verified verified = verify(source, fileType, plan);
            result.put("ok", true);
            fill(result, verified, plan);
            return result;
        } catch (Exception error) {
            log.warn("标注自检未通过（按文件字节，{} 字节）", source == null ? 0 : source.length, error);
            return failed(result, error);
        }
    }

    /** 自检通过后的公共字段：块清单、占位符、没绑上的占位符、告警、产出大小。 */
    private static void fill(Map<String, Object> result, Verified verified, DocTemplateMarkPlan plan) {
        result.put("file_type", verified.fileType());
        result.put("blocks", verified.blocks());
        result.put("placeholders", new ArrayList<>(verified.placeholders()));
        result.put("unbound", verified.unbound());
        result.put("suggestions", suggestions(plan));
        result.put("warnings", verified.warnings());
        result.put("ignored", plan.ignore());
        result.put("output_bytes", verified.outputBytes());
    }

    private static Map<String, Object> failed(Map<String, Object> result, Exception error) {
        result.put("ok", false);
        result.put("error", message(error));
        result.put("warnings", List.of());
        return result;
    }

    /**
     * 预览。
     *
     * @param mode {@code template} 返回落笔后的模板（标记可见，用来看位置对不对）；
     *             其余返回用模拟数据渲染出来的文档（看效果和原文像不像）
     */
    public byte[] preview(long templateId, DocTemplateMarkPlan plan, String mode) throws Exception {
        return preview(templates.currentVersionBytes(templateId), templates.currentFileType(templateId), plan, mode);
    }

    /** 预览（按文件字节）。 */
    public byte[] preview(byte[] source, String fileType, DocTemplateMarkPlan plan, String mode) throws Exception {
        plan.validate();
        DocTemplateAnnotator.Result annotated = annotator.apply(source, fileType, plan);
        if ("template".equalsIgnoreCase(mode)) return annotated.bytes();
        return renderer.render(annotated.bytes(), fileType, DocTemplateMockData.params(plan));
    }

    /**
     * 落库：先自检，通过后写一个新版本 + 按计划建数据块。
     *
     * <p>写之前把能预检的都检掉（SQL 开关、块编码冲突、自检），避免留下「版本有了、块没建」的半截状态。</p>
     */
    public Map<String, Object> apply(long templateId, DocTemplateMarkPlan plan) throws Exception {
        return applyTo(templateId, templates.currentFileName(templateId),
                templates.currentVersionBytes(templateId), templates.currentFileType(templateId), plan);
    }

    /**
     * 把一份文件标注后写进指定模板：新建一个版本 + 按计划建数据块。
     *
     * <p>文件由调用方给，是因为 AI 导入的文件在导入任务里、不一定是模板的当前版本。
     * 失败时调用方负责删掉刚建的模板，这里只管「要么全成、要么抛出」。</p>
     */
    public Map<String, Object> applyTo(long templateId, String fileName, byte[] source, String fileType,
                                       DocTemplateMarkPlan plan) throws Exception {
        plan.validate();
        if (!properties.getDataBlock().isSqlEnabled())
            throw new IllegalArgumentException("SQL 数据源已关闭（doc-template.data-block.sql-enabled=false），"
                    + "生成的块为 SQL 类型，无法保存；请打开该开关，或改用 JAVA 数据块");
        // 已经标注过的文档不能再标一遍：标记套标记，渲染出来是一堆字面量
        if (!renderer.placeholders(source, fileType).isEmpty() || !renderer.loopFields(source, fileType).isEmpty())
            throw new IllegalArgumentException("当前文件已包含模板标记，不能重复标注；"
                    + "重复标注会导致标记嵌套。如需重做，请先上传原始文档");
        Set<String> existing = new LinkedHashSet<>();
        for (Map<String, Object> block : templates.blocks(templateId)) {
            existing.add(String.valueOf(block.get("block_code")));
        }
        for (DocTemplateMarkPlan.Block block : plan.blocks()) {
            if (existing.contains(block.code()))
                throw new IllegalArgumentException("数据块编码 " + block.code() + " 在该模板中已存在；"
                        + "改 plan 里的 code，或先删掉旧的数据块");
        }
        Verified verified;
        try {
            verified = verify(source, fileType, plan);
        } catch (Exception error) {
            throw new IllegalArgumentException("模板自检没通过，没有保存：" + message(error), error);
        }

        Map<String, Object> version = templates.addVersion(templateId, fileName, verified.template());
        List<Map<String, Object>> created = new ArrayList<>();
        for (int i = 0; i < plan.blocks().size(); i++) {
            DocTemplateMarkPlan.Block block = plan.blocks().get(i);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("blockCode", block.code());
            body.put("blockName", block.name() == null || block.name().isBlank() ? block.code() : block.name());
            body.put("dataSourceType", "SQL");
            Map<String, Object> config = new LinkedHashMap<>();
            config.put("sql", verified.sqls().get(i));
            config.put("single", "object".equals(block.shape()));
            body.put("dataSourceConfig", config);
            body.put("paramConfig", Map.of());
            body.put("sortOrder", i + 1);
            created.add(templates.createBlock(templateId, body));
        }
        log.info("模板标注落库：templateId={}，{} 个数据块，versionId={}", templateId, created.size(), version.get("id"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("template_id", templateId);
        result.put("version", version);
        result.put("blocks", created);
        result.put("placeholders", new ArrayList<>(verified.placeholders()));
        result.put("unbound", verified.unbound());
        result.put("suggestions", suggestions(plan));
        result.put("warnings", verified.warnings());
        return result;
    }

    /** 落笔 + 建 SQL + 渲染一次；任何一步不通过都抛，调用方决定是回显还是拒绝。 */
    private Verified verify(byte[] source, String fileType, DocTemplateMarkPlan plan) throws Exception {
        requireKnownBlocks(plan);
        DocTemplateAnnotator.Result annotated = annotator.apply(source, fileType, plan);

        DocTemplateDialect dialect = dialects.current();
        List<String> sqls = new ArrayList<>();
        List<Map<String, Object>> blocks = new ArrayList<>();
        for (DocTemplateMarkPlan.Block block : plan.blocks()) {
            // 生成的 SQL 也要过一遍只读校验：防止样例值中出现异常内容，不留到执行阶段
            String sql = DocTemplateSqlValidator.validate(DocTemplateMockData.sql(block, dialect));
            sqls.add(sql);
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("code", block.code());
            info.put("name", block.name());
            info.put("shape", block.shape());
            info.put("rows", DocTemplateMockData.rowCount(block));
            info.put("fields", block.fields());
            info.put("sql", sql);
            blocks.add(info);
        }

        byte[] rendered = renderer.render(annotated.bytes(), fileType, DocTemplateMockData.params(plan));
        Set<String> placeholders = renderer.placeholders(annotated.bytes(), fileType);
        Set<String> loopFields = renderer.loopFields(annotated.bytes(), fileType);
        return new Verified(annotated.bytes(), fileType, annotated.warnings(), blocks, sqls,
                placeholders, unbound(placeholders, loopFields, plan), rendered.length);
    }

    /**
     * 标记指向的块必须真的在 {@code blocks} 里定义过。
     *
     * <p>少了这一步，块编码写错时会一路走到渲染器才报「这个块没有配置列源」，
     * 让人以为是列源配置问题，实际是块编码对不上。</p>
     */
    private static void requireKnownBlocks(DocTemplateMarkPlan plan) {
        Map<String, String> known = new LinkedHashMap<>();
        for (DocTemplateMarkPlan.Block block : plan.blocks()) {
            if (block.code() != null) known.put(block.code().toLowerCase(Locale.ROOT), block.code());
        }
        for (DocTemplateMarkPlan.Mark mark : plan.marks()) {
            if (mark.block() == null || mark.block().isBlank()) continue;
            if (known.containsKey(mark.block().toLowerCase(Locale.ROOT))) continue;
            throw new IllegalArgumentException("标记指向的块 " + mark.block() + " 没有定义；blocks 里现有的块编码是 "
                    + String.join("、", known.values()));
        }
    }

    /**
     * 值转换建议：只提一句，不自动写进数据块。
     *
     * <p>自动写等于把格式强加给用户（{@code number} 转换器会统一小数位），而这里只是从几个样例值猜的；
     * 猜错了改回来比重配一遍还麻烦。所以给建议、给原因，改不改由人定。</p>
     */
    private static List<Map<String, Object>> suggestions(DocTemplateMarkPlan plan) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (DocTemplateMarkPlan.Block block : plan.blocks()) {
            for (DocTemplateMarkPlan.Field field : block.fields()) {
                String converter = suggestedConverter(field);
                if (converter == null) continue;
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("block", block.code());
                item.put("field", field.field());
                item.put("converter", converter);
                item.put("reason", "number".equals(converter)
                        ? "样例值带千分位、货币符号或百分号，建议给这个字段加 number 转换器统一格式"
                        : "样例日期不是 yyyy-MM-dd 写法，建议给这个字段加 date 转换器统一格式");
                result.add(item);
            }
        }
        return result;
    }

    private static String suggestedConverter(DocTemplateMarkPlan.Field field) {
        for (String sample : field.samples()) {
            if (sample == null || sample.isBlank()) continue;
            String text = sample.trim();
            if ("number".equals(field.type())
                    && text.matches(".*[,\\s¥￥$%].*")) return "number";
            if ("date".equals(field.type())
                    && text.matches(".*\\d{4}\\s*[年/.-]\\s*\\d{1,2}\\s*[月/.-].*")) return "date";
        }
        return null;
    }

    /**
     * 不属于任何数据块的占位符：多半是手填参数（签字人、日期）。列出来是提醒，不是错误。
     *
     * <p>循环体里的字段写的是短名（{@code ${sample_no}}），它们由行数据给，不算「没绑上」；
     * {@code _index} 是渲染器内置的行号，也跳过。</p>
     */
    private static List<String> unbound(Set<String> placeholders, Set<String> loopFields, DocTemplateMarkPlan plan) {
        Set<String> codes = new LinkedHashSet<>();
        for (DocTemplateMarkPlan.Block block : plan.blocks()) codes.add(block.code());
        List<String> result = new ArrayList<>();
        for (String key : placeholders) {
            if (key.startsWith("_") || loopFields.contains(key)) continue;
            String head = key.contains(".") ? key.substring(0, key.indexOf('.')) : key;
            if (codes.contains(head)) continue;
            result.add(key);
        }
        return result;
    }

    private static String message(Throwable error) {
        String text = error.getMessage();
        if (text != null && !text.isBlank()) return text;
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
    }

    /** 自检结果。{@code template} 是落笔后的文件，落库时直接存它。 */
    private record Verified(byte[] template, String fileType, List<String> warnings,
                            List<Map<String, Object>> blocks, List<String> sqls,
                            Set<String> placeholders, List<String> unbound, int outputBytes) {
    }
}
