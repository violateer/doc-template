package io.github.violateer.doctemplate.starter.ai;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import io.github.violateer.doctemplate.starter.annotate.DocTemplateMarkPlan;
import io.github.violateer.doctemplate.starter.autoconfigure.DocTemplateProperties;
import io.github.violateer.doctemplate.starter.entity.DocTemplateAiImportEntity;
import io.github.violateer.doctemplate.starter.entity.DocTemplateAiTraceEntity;
import io.github.violateer.doctemplate.starter.repository.DocTemplateAiImportRepository;
import io.github.violateer.doctemplate.starter.repository.DocTemplateAiTraceRepository;
import io.github.violateer.doctemplate.starter.service.DocTemplateAnnotateService;
import io.github.violateer.doctemplate.starter.service.DocTemplateFileStorage;
import io.github.violateer.doctemplate.starter.service.DocTemplateService;
import io.github.violateer.doctemplate.starter.support.DocTemplateJson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AI 导入任务：上传只产生一条任务，分析在后台线程里跑，模板、版本、数据块在 APPLY 阶段一次建好。
 *
 * <p><b>为什么任务表是唯一状态源</b>：内存里只留 taskId 与正在跑的线程。进程重启后残留的
 * {@code RUNNING} 一律标失败，不尝试续跑 —— 一次导入要重跑几十秒的模型调用，续跑既不省钱也不省时间，
 * 反而容易把状态写乱。</p>
 *
 * <p>取消分两种：{@code PENDING} 直接标取消；{@code RUNNING} 置一个标志并中断线程，
 * 每次调模型前检查一次，所以「点了取消立刻停」并不保证，只是不再发起下一次调用。</p>
 */
public class DocTemplateAiImportService {
    private static final Logger log = LoggerFactory.getLogger(DocTemplateAiImportService.class);

    /** {@code error_message} 在 Oracle 上是 {@code VARCHAR2(2000 CHAR)}，与生成记录同一个上限。 */
    private static final int MAX_ERROR_MESSAGE = 2000;
    /** 流式内容落库的最小间隔（毫秒）：逐包写会把连接池打满，太稀又看不出在生成。 */
    private static final long STREAM_FLUSH_MS = 800;
    /** 批量删除一次最多处理多少条：与其它列表保持一致。 */
    private static final int BATCH_DELETE_LIMIT = 200;
    private static final int MAX_HINT = 1000;
    private static final int MAX_TEMPLATE_NAME = 200;
    private static final int MAX_TEMPLATE_CODE = 100;

    private static final List<String> STAGES = List.of(
            DocTemplateAiStage.SNAPSHOT.name(), DocTemplateAiStage.PROMPT.name(), DocTemplateAiStage.CALL.name(),
            DocTemplateAiStage.PARSE.name(), DocTemplateAiStage.VALIDATE.name(), DocTemplateAiStage.CHECK.name(),
            DocTemplateAiStage.APPLY.name(), DocTemplateAiStage.DONE.name());

    /** 已请求取消、但线程还没停下来的任务。 */
    private final Set<Long> cancelled = ConcurrentHashMap.newKeySet();
    private final Map<Long, Thread> running = new ConcurrentHashMap<>();

    private final DocTemplateAiImportRepository imports;
    private final DocTemplateAiTraceRepository traces;
    private final DocTemplateAnnotateService annotate;
    private final DocTemplateAiPlanner planner;
    private final DocTemplateService templates;
    private final DocTemplateFileStorage storage;
    private final DocTemplateProperties properties;
    private final DocTemplateAiImportExecutor executor;

    public DocTemplateAiImportService(DocTemplateAiImportRepository imports, DocTemplateAiTraceRepository traces,
                                      DocTemplateAnnotateService annotate, DocTemplateAiPlanner planner,
                                      DocTemplateService templates, DocTemplateFileStorage storage,
                                      DocTemplateProperties properties, DocTemplateAiImportExecutor executor) {
        this.imports = imports;
        this.traces = traces;
        this.annotate = annotate;
        this.planner = planner;
        this.templates = templates;
        this.storage = storage;
        this.properties = properties;
        this.executor = executor;
    }

    /* ------------------------------------------------------------ 新建与查询 */

    /**
     * 新建导入任务：文件进存储，任务进表，状态 {@code PENDING}。
     *
     * <p>这一步<b>不建模板</b>：分析失败不该留下一个只有文件、没有数据块的草稿。</p>
     */
    public Map<String, Object> create(MultipartFile file, String templateName, String templateCode,
                                      String hint, String user) throws Exception {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("请选择要上传的文件");
        String fileName = StrUtil.blankToDefault(file.getOriginalFilename(), "import");
        String ext = StrUtil.blankToDefault(FileUtil.extName(fileName), "").toLowerCase(Locale.ROOT);
        if (!"docx".equals(ext) && !"xlsx".equals(ext))
            throw new IllegalArgumentException("不支持该文件类型，只支持 .docx 和 .xlsx");
        String name = required(templateName, "模板名称");
        if (name.length() > MAX_TEMPLATE_NAME)
            throw new IllegalArgumentException("模板名称最长 " + MAX_TEMPLATE_NAME + " 个字符");
        String code = StrUtil.isBlank(templateCode) ? suggestCode(name) : templateCode.trim();
        if (code.length() > MAX_TEMPLATE_CODE)
            throw new IllegalArgumentException("模板编码最长 " + MAX_TEMPLATE_CODE + " 个字符");
        // 编码全局唯一：与其等模型跑完两分钟再报「编码已存在」，不如在上传时就说
        for (Map<String, Object> existing : templates.templates()) {
            if (code.equalsIgnoreCase(String.valueOf(existing.get("template_code"))))
                throw new IllegalArgumentException("模板编码已存在：" + code);
        }
        byte[] bytes = file.getBytes();

        DocTemplateAiImportEntity task = new DocTemplateAiImportEntity();
        task.setFileName(fileName);
        task.setFileUrl("");
        task.setFileSha256(DigestUtil.sha256Hex(bytes));
        task.setFileType("XLSX".equalsIgnoreCase(ext) ? "XLSX" : "DOCX");
        task.setTemplateName(name);
        task.setTemplateCode(code);
        task.setHint(StrUtil.isBlank(hint) ? null : truncate(hint.trim(), MAX_HINT));
        task.setStatus("PENDING");
        task.setCreateBy(user);
        task.setCreateTime(LocalDateTime.now());
        imports.insert(task);

        try {
            String location = storage.writeObject(importKey(task.getId(), ext), bytes);
            imports.updateFileUrl(task.getId(), location);
        } catch (Exception error) {
            // 文件没写进去的任务留着也没用，直接清掉
            imports.deleteById(task.getId());
            throw error;
        }
        log.info("新建 AI 导入任务：id={}，file={}，{} 字节", task.getId(), fileName, bytes.length);
        return detail(task.getId());
    }

    /** 分页列表：默认按 id 倒序，支持状态与关键字过滤。 */
    public Map<String, Object> page(int page, int size, String status, String keyword) {
        int current = Math.max(1, page);
        int pageSize = Math.min(200, Math.max(1, size));
        String statusFilter = StrUtil.blankToDefault(status, "").trim();
        String text = StrUtil.blankToDefault(keyword, "").trim();
        long total = imports.count(statusFilter, text);
        List<Map<String, Object>> records = imports.findPage(statusFilter, text, (current - 1) * pageSize, pageSize)
                .stream().map(this::rowMap).toList();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("records", records);
        data.put("total", total);
        data.put("page", current);
        data.put("size", pageSize);
        data.put("pages", (int) ((total + pageSize - 1) / pageSize));
        return data;
    }

    /** 详情：任务本身 + 每次尝试的失败原因 + 思维链 + 阶段顺序。 */
    public Map<String, Object> detail(long id) {
        DocTemplateAiImportEntity task = require(id);
        Map<String, Object> data = rowMap(task);
        data.put("plan_json", task.getPlanJson());
        data.put("stages", STAGES);
        List<Map<String, Object>> failures = new ArrayList<>();
        for (DocTemplateAiTraceRepository.Failure failure : traces.findFailures(id)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("attempt", failure.attempt());
            item.put("stage", failure.stage());
            item.put("error", failure.error());
            item.put("elapsed_ms", failure.elapsedMs());
            failures.add(item);
        }
        data.put("failures", failures);
        data.put("traces", traces.findByImportId(id).stream().map(this::traceMap).toList());
        return data;
    }

    /**
     * 轻量进度：轮询用，形状与详情一致但不带计划原文。
     *
     * <p>流式期间页面每秒拉一次，计划原文只在上一次落库时才变，重复传没有意义；
     * 思维链正文要跟着涨，所以保留。</p>
     */
    public Map<String, Object> progress(long id) {
        DocTemplateAiImportEntity task = require(id);
        Map<String, Object> data = rowMap(task);
        data.put("stages", STAGES);
        data.put("traces", traces.findByImportId(id).stream().map(this::traceMap).toList());
        return data;
    }

    /** 任务文件的内容与类型：自检、预览、落笔都从它出发。 */
    public DocTemplateAnnotateService.Source source(long id) throws Exception {
        DocTemplateAiImportEntity task = require(id);
        if (StrUtil.isBlank(task.getFileUrl()))
            throw new IllegalStateException("该导入任务没有文件，请重新上传");
        return new DocTemplateAnnotateService.Source(storage.read(task.getFileUrl()), task.getFileType());
    }

    public String fileName(long id) {
        return require(id).getFileName();
    }

    public String contentType(long id) {
        return "XLSX".equalsIgnoreCase(require(id).getFileType())
                ? "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                : "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    }

    /* ------------------------------------------------------------ 执行 */

    /**
     * 开始导入：把任务丢进队列，立即返回。
     *
     * <p>状态变化由后台线程写，所以这里返回的还是 {@code PENDING}；页面靠轮询看进度。</p>
     */
    public Map<String, Object> start(long id) {
        DocTemplateAiImportEntity task = require(id);
        if (!planner.available())
            throw new IllegalArgumentException("AI 标注未启用：设置 doc-template.ai.enabled=true 并配好 base-url、"
                    + "api-key、model，或者自己注册一个 DocTemplateAiClient Bean");
        if ("RUNNING".equals(task.getStatus()))
            throw new IllegalArgumentException("该任务正在执行，请等待完成或先取消");
        if ("SUCCEEDED".equals(task.getStatus()))
            throw new IllegalArgumentException("该任务已生成模板；重新标注请用「重新分析」，"
                    + "结果会写回同一个模板");
        submit(id);
        return detail(id);
    }

    /**
     * 重新分析：清掉上一次的结果，再走一遍「出计划 → 自检 → 落库」。
     *
     * <p>已生成模板的任务也走这里，此时落库会写到原来那个模板上（新增一个版本、重建数据块），
     * 不会再建一个编码相同的新模板。分析本身失败时模板保持原样 —— 旧版本与旧数据块都没动。</p>
     */
    public Map<String, Object> rerun(long id) {
        DocTemplateAiImportEntity task = require(id);
        if (!planner.available())
            throw new IllegalArgumentException("AI 标注未启用：设置 doc-template.ai.enabled=true 并配好 base-url、"
                    + "api-key、model，或者自己注册一个 DocTemplateAiClient Bean");
        if ("RUNNING".equals(task.getStatus()))
            throw new IllegalArgumentException("该任务正在执行，请等待完成或先取消");
        if (imports.resetForRerun(id) != 1)
            throw new IllegalArgumentException("当前状态不能重新分析：" + task.getStatus());
        // 上一轮的思维链跟着作废：新一轮从第一次尝试重新记，页面也不会把两次的记录混在一起
        traces.deleteByImportId(id);
        submit(id);
        return detail(id);
    }

    /** 丢进线程池。队列满时任务保持原状态，用户稍后再点一次即可。 */
    private void submit(long id) {
        try {
            executor.submit(() -> run(id));
        } catch (RuntimeException rejected) {
            throw new IllegalStateException("导入队列已满，请稍后再试", rejected);
        }
    }

    /** 取消：未开始直接标取消，正在跑的通知线程停手。 */
    public Map<String, Object> cancel(long id) {
        DocTemplateAiImportEntity task = require(id);
        if ("PENDING".equals(task.getStatus())) {
            imports.cancelPending(id);
            return detail(id);
        }
        if ("RUNNING".equals(task.getStatus())) {
            cancelled.add(id);
            Thread thread = running.get(id);
            if (thread != null) thread.interrupt();
            log.info("已请求取消 AI 导入任务：id={}", id);
            return detail(id);
        }
        throw new IllegalArgumentException("当前状态不能取消：" + task.getStatus());
    }

    /**
     * 删除任务：执行中的不能删，其余状态都可以删。
     *
     * <p><b>已生成的模板不跟着删</b>：导入任务只是「怎么把这份文档变成模板」的过程记录，
     * 模板一旦建出来就是独立资产，删任务不该把业务方正在用的模板一起带走。</p>
     */
    public void delete(long id) {
        deleteTask(require(id));
    }

    /** 批量删除：逐条删，单条失败不影响其他条；返回成功条数与失败明细。 */
    public Map<String, Object> deleteBatch(Map<String, Object> body) {
        List<Long> ids = idsOf(body);
        List<Map<String, Object>> failed = new ArrayList<>();
        int deleted = 0;
        for (Long id : ids) {
            try {
                DocTemplateAiImportEntity task = imports.findById(id);
                // 已经被别处删掉的当作删除成功，不必报错
                if (task != null) deleteTask(task);
                deleted++;
            } catch (RuntimeException error) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", id);
                item.put("message", error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
                failed.add(item);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deleted", deleted);
        result.put("failed", failed);
        return result;
    }

    /** 单条与批量删除共用：执行中的一律拒绝，否则后台线程会往已经删掉的行上写。 */
    private void deleteTask(DocTemplateAiImportEntity task) {
        if ("RUNNING".equals(task.getStatus()))
            throw new IllegalArgumentException("正在执行的任务不能删除，请先取消");
        long id = task.getId();
        traces.deleteByImportId(id);
        imports.deleteById(id);
        try {
            storage.delete(task.getFileUrl());
        } catch (Exception error) {
            log.warn("删除 AI 导入任务的存储对象失败：id={}，{}", id, error.getMessage());
        }
        log.info("已删除 AI 导入任务：id={}，templateId={}（模板保留）", id, task.getTemplateId());
    }

    /* ------------------------------------------------------------ 手动自检 / 预览 / 落库 */

    /** 改完计划后重新自检：落笔 + 生成 SQL + 渲染一次，都在任务文件上做。 */
    public Map<String, Object> check(long id, DocTemplateMarkPlan plan) throws Exception {
        DocTemplateAnnotateService.Source source = source(id);
        Map<String, Object> result = annotate.check(source.bytes(), source.fileType(), plan);
        result.put("import_id", id);
        return result;
    }

    /** 预览：{@code template} 看落笔后的标记，其余看模拟数据的渲染效果。 */
    public byte[] preview(long id, DocTemplateMarkPlan plan, String mode) throws Exception {
        DocTemplateAnnotateService.Source source = source(id);
        return annotate.preview(source.bytes(), source.fileType(), plan, mode);
    }

    /**
     * 手动落库：按人改过的计划建模板、版本与数据块。
     *
     * <p>已经生成过模板的任务不再走这里 —— 那说明用户要的是「在这个模板上重新标注」，
     * 属于模板管理的范围，不该由导入任务再写一版。</p>
     */
    public Map<String, Object> apply(long id, DocTemplateMarkPlan plan) throws Exception {
        DocTemplateAiImportEntity task = require(id);
        Long resolved = resolveTemplateId(task);
        if (resolved != null)
            throw new IllegalArgumentException("该任务已生成模板（ID " + resolved
                    + "），如需调整请在模板里重新标注");
        if ("RUNNING".equals(task.getStatus()))
            throw new IllegalArgumentException("该任务正在执行，请等待完成或先取消");
        long started = System.currentTimeMillis();
        DocTemplateAnnotateService.Source source = source(id);
        Map<String, Object> applied = applyNewTemplate(task, source, plan);
        long templateId = Long.parseLong(String.valueOf(applied.get("template_id")));
        String planJson = DocTemplateJson.toJson(plan.toMap());
        imports.updatePlan(id, planJson);
        imports.finish(id, "SUCCEEDED", templateId, null, LocalDateTime.now(), started);
        log.info("AI 导入任务按修改后的计划落库：id={}，templateId={}", id, templateId);
        return detail(id);
    }

    /**
     * 启动收尾：把残留的 {@code RUNNING} 标失败，并按保留期清理思维链。
     *
     * <p>由启动摘要在建表之后调用；这里自己吞掉异常 —— 恢复失败不该挡住应用启动。</p>
     */
    public void recover() {
        try {
            int reset = imports.resetRunning("服务重启，任务中断");
            if (reset > 0) log.warn("有 {} 个 AI 导入任务因服务重启被标记为失败", reset);
            int days = properties.getAi().getTraceRetentionDays();
            if (days > 0) {
                int removed = traces.deleteOlderThan(LocalDateTime.now().minusDays(days));
                if (removed > 0) log.info("已清理 {} 条超过 {} 天的 AI 导入思维链记录", removed, days);
            }
        } catch (Exception error) {
            log.warn("AI 导入任务恢复失败（不影响启动）：{}", error.getMessage());
        }
    }

    /* ------------------------------------------------------------ 后台执行 */

    /** 后台线程入口：抢任务 → 出计划 → 建模板。任何异常都落到任务状态里，不再向外抛。 */
    private void run(long id) {
        long started = System.currentTimeMillis();
        running.put(id, Thread.currentThread());
        try {
            if (imports.claim(id, LocalDateTime.now()) != 1) {
                log.info("AI 导入任务已被其他线程接手，跳过：id={}", id);
                return;
            }
            DocTemplateAiImportEntity task = imports.findById(id);
            if (task == null) return;
            byte[] bytes = storage.read(task.getFileUrl());
            DocTemplateAnnotateService.Source source =
                    new DocTemplateAnnotateService.Source(bytes, task.getFileType());

            Map<String, Object> result = planner.plan(source, task.getHint(), listener(id));
            if (!Boolean.TRUE.equals(result.get("ok"))) {
                fail(id, String.valueOf(result.get("error")), started);
                return;
            }
            // 计划在结果里是一份 Map，这里按同一套命名反解析回对象（与「计划 JSON 回传」走同一条路）
            DocTemplateMarkPlan plan = DocTemplateMarkPlan.parse(DocTemplateJson.toJson(result.get("plan")));
            imports.updatePlan(id, DocTemplateJson.toJson(plan.toMap()));

            imports.updateStage(id, DocTemplateAiStage.APPLY.name());
            if (cancelled.contains(id)) throw new DocTemplateAiAbortedException();
            Map<String, Object> applied = applyTemplate(task, source, plan);
            long templateId = Long.parseLong(String.valueOf(applied.get("template_id")));
            imports.updateStage(id, DocTemplateAiStage.DONE.name());
            imports.finish(id, "SUCCEEDED", templateId, null, LocalDateTime.now(),
                    System.currentTimeMillis() - started);
            log.info("AI 导入完成：id={}，templateId={}，{} ms", id, templateId, System.currentTimeMillis() - started);
        } catch (DocTemplateAiAbortedException error) {
            imports.finish(id, "CANCELED", null, "任务已取消", LocalDateTime.now(),
                    System.currentTimeMillis() - started);
            log.info("AI 导入任务已取消：id={}", id);
        } catch (InterruptedException error) {
            // 中断来自取消：先清掉中断标志再写库，否则连接池里的 JDBC 调用会被直接打断
            Thread.interrupted();
            imports.finish(id, "CANCELED", null, "任务已取消", LocalDateTime.now(),
                    System.currentTimeMillis() - started);
            log.info("AI 导入任务被中断：id={}", id);
        } catch (Exception error) {
            fail(id, rootMessage(error), started);
        } finally {
            running.remove(id);
            cancelled.remove(id);
        }
    }

    private void fail(long id, String message, long started) {
        log.warn("AI 导入任务失败：id={}，{}", id, message);
        imports.finish(id, "FAILED", null, storable(message), LocalDateTime.now(),
                System.currentTimeMillis() - started);
    }

    /**
     * 阶段、尝试与流式增量的回调：阶段写任务表，尝试与增量写思维链表。
     *
     * <p>思维链关掉时只写阶段 —— 「现在在做什么」是基本功能，不该跟「能不能看思考过程」绑在一起。</p>
     *
     * <p>流式内容在<b>调模型之前</b>先把行建出来，之后按 {@link #STREAM_FLUSH_MS} 节流覆盖思考与正文两列。
     * 不逐包写是因为模型每秒能吐几十包，那样每个导入都会把连接池打满；写得太稀又看不出「在长」，
     * 八百毫秒是这两者之间能兼顾的值。</p>
     */
    private DocTemplateAiPlanner.Listener listener(long id) {
        return new DocTemplateAiPlanner.Listener() {
            /** 本次尝试的思维链行；{@code 0} 表示没建出来（思维链关闭或写库失败） */
            private long traceId;
            private int traceAttempt;
            private long lastFlush;
            private String streamedReasoning;
            private String streamedContent;

            @Override
            public void stage(DocTemplateAiStage stage) {
                imports.updateStage(id, stage.name());
            }

            @Override
            public void attemptStarted(int attempt, String model, String systemPrompt, String userPrompt) {
                traceId = 0;
                traceAttempt = attempt;
                lastFlush = 0;
                streamedReasoning = null;
                streamedContent = null;
                if (!properties.getAi().isTraceEnabled()) return;
                try {
                    DocTemplateAiTraceEntity trace = new DocTemplateAiTraceEntity();
                    trace.setImportId(id);
                    trace.setAttempt(attempt);
                    trace.setStage(DocTemplateAiStage.CALL.name());
                    trace.setModel(model);
                    trace.setSystemPrompt(systemPrompt);
                    trace.setUserPrompt(userPrompt);
                    trace.setCreateTime(LocalDateTime.now());
                    traces.insert(trace);
                    traceId = trace.getId();
                } catch (RuntimeException error) {
                    log.warn("建立 AI 导入思维链行失败：importId={}，attempt={}", id, attempt, error);
                }
            }

            @Override
            public void delta(int attempt, String reasoning, String content) {
                if (traceId == 0 || attempt != traceAttempt) return;
                streamedReasoning = reasoning;
                streamedContent = content;
                long now = System.currentTimeMillis();
                if (now - lastFlush < STREAM_FLUSH_MS) return;
                lastFlush = now;
                try {
                    traces.updateStreaming(traceId, reasoning, content);
                } catch (RuntimeException error) {
                    // 过程写不进去只影响观感，不能打断正在跑的调用
                    log.debug("流式内容写库失败：traceId={}", traceId, error);
                }
            }

            @Override
            public void attempt(DocTemplateAiPlanner.Attempt attempt) {
                if (traceId != 0 && attempt.attempt() == traceAttempt) {
                    // 失败时最终结果里没有正文，保留已经流出来的那部分：排查问题靠的就是它
                    String reasoning = attempt.reasoning() != null ? attempt.reasoning() : streamedReasoning;
                    String content = attempt.content() != null ? attempt.content() : streamedContent;
                    try {
                        traces.finish(traceId, attempt.stage() == null ? null : attempt.stage().name(),
                                reasoning, content, attempt.promptTokens(), attempt.completionTokens(),
                                attempt.elapsedMs(), storable(attempt.error()));
                    } catch (RuntimeException error) {
                        log.warn("写入 AI 导入思维链失败：importId={}，attempt={}", id, attempt.attempt(), error);
                    }
                    return;
                }
                saveTrace(id, attempt);
            }

            @Override
            public boolean aborted() {
                return cancelled.contains(id) || Thread.currentThread().isInterrupted();
            }
        };
    }

    private void saveTrace(long importId, DocTemplateAiPlanner.Attempt attempt) {
        if (!properties.getAi().isTraceEnabled()) return;
        try {
            DocTemplateAiTraceEntity trace = new DocTemplateAiTraceEntity();
            trace.setImportId(importId);
            trace.setAttempt(attempt.attempt());
            trace.setStage(attempt.stage() == null ? null : attempt.stage().name());
            trace.setModel(attempt.model());
            trace.setSystemPrompt(attempt.systemPrompt());
            trace.setUserPrompt(attempt.userPrompt());
            trace.setReasoning(attempt.reasoning());
            trace.setContent(attempt.content());
            trace.setPromptTokens(attempt.promptTokens());
            trace.setCompletionTokens(attempt.completionTokens());
            trace.setElapsedMs(attempt.elapsedMs());
            trace.setError(storable(attempt.error()));
            trace.setCreateTime(LocalDateTime.now());
            traces.insert(trace);
        } catch (RuntimeException error) {
            // 记录失败不能影响出计划
            log.warn("写入 AI 导入思维链失败：importId={}，attempt={}", importId, attempt.attempt(), error);
        }
    }

    /**
     * 建模板 + 落笔 + 建数据块。
     *
     * <p>模板先建（数据块要挂在它下面），一旦后续任一步失败就把它删掉 ——
     * 「失败不留草稿」是这套流程的前提，否则列表里会堆满只有文件的空模板。</p>
     */
    private Map<String, Object> applyNewTemplate(DocTemplateAiImportEntity task,
                                                 DocTemplateAnnotateService.Source source,
                                                 DocTemplateMarkPlan plan) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("templateName", task.getTemplateName());
        body.put("templateCode", task.getTemplateCode());
        body.put("fileType", task.getFileType());
        body.put("exportType", "ORIGINAL");
        body.put("status", "ENABLED");
        body.put("remark", "AI 导入任务 #" + task.getId() + " 创建");
        Map<String, Object> created = templates.createTemplate(body);
        long templateId = Long.parseLong(String.valueOf(created.get("id")));
        try {
            return annotate.applyTo(templateId, task.getFileName(), source.bytes(), source.fileType(), plan);
        } catch (Exception error) {
            try {
                templates.deleteTemplate(templateId);
            } catch (Exception cleanup) {
                log.warn("回滚 AI 导入建出的模板失败：templateId={}，{}", templateId, cleanup.getMessage());
            }
            throw error;
        }
    }

    /**
     * 落库：任务还没有模板就新建一个，已有模板就把这次分析的结果写回那个模板。
     *
     * <p>写回时先删旧数据块再按新计划重建 —— {@code applyTo} 会以「编码已存在」拒绝重复。
     * 删块之前先自检一次：自检不过直接抛，模板保持原样，不会出现「旧块删了、新块没建」的半截状态。
     * 版本按 {@code addVersion} 追加，历史版本仍可在模板详情里回滚。</p>
     */
    private Map<String, Object> applyTemplate(DocTemplateAiImportEntity task,
                                              DocTemplateAnnotateService.Source source,
                                              DocTemplateMarkPlan plan) throws Exception {
        Long resolved = resolveTemplateId(task);
        if (resolved == null) return applyNewTemplate(task, source, plan);
        long templateId = resolved;
        Map<String, Object> precheck = annotate.check(source.bytes(), source.fileType(), plan);
        if (!Boolean.TRUE.equals(precheck.get("ok")))
            throw new IllegalArgumentException("模板自检没通过，没有写回：" + precheck.get("error"));
        for (Map<String, Object> block : templates.blocks(templateId)) {
            templates.deleteBlock(Long.parseLong(String.valueOf(block.get("id"))));
        }
        Map<String, Object> applied = annotate.applyTo(templateId, task.getFileName(), source.bytes(),
                source.fileType(), plan);
        log.info("AI 导入结果写回已有模板：id={}，templateId={}，{} 个数据块",
                task.getId(), templateId, plan.blocks().size());
        return applied;
    }

    /**
     * 这次落库该写回哪个模板。
     *
     * <p>正常情况就是任务上的 {@code template_id}。它为空（失败、取消过的老任务会丢关联）或指向的模板
     * 已经被删掉时，按任务上的模板编码再找一次：编码在创建任务时就校验过全局唯一，同编码的模板
     * 只可能是这条任务自己建出来的，找回后照常写回，不再新建一个同编码模板。</p>
     */
    private Long resolveTemplateId(DocTemplateAiImportEntity task) {
        Long linked = task.getTemplateId();
        if (linked != null && templateExists(linked)) return linked;
        String code = task.getTemplateCode();
        if (StrUtil.isBlank(code)) return null;
        for (Map<String, Object> template : templates.templates()) {
            if (!code.equalsIgnoreCase(String.valueOf(template.get("template_code")))) continue;
            long found = Long.parseLong(String.valueOf(template.get("id")));
            log.warn("AI 导入任务 {} 的模板关联已失效（templateId={}），按模板编码 {} 找回模板 {}",
                    task.getId(), linked, code, found);
            return found;
        }
        return null;
    }

    private boolean templateExists(long templateId) {
        try {
            templates.template(templateId);
            return true;
        } catch (NoSuchElementException gone) {
            return false;
        }
    }

    /* ------------------------------------------------------------ 内部工具 */

    private DocTemplateAiImportEntity require(long id) {
        DocTemplateAiImportEntity task = imports.findById(id);
        if (task == null) throw new NoSuchElementException("导入任务不存在：" + id);
        return task;
    }

    /** 列表行：比实体多几个派生字段，页面不用自己拼。 */
    private Map<String, Object> rowMap(DocTemplateAiImportEntity task) {
        Map<String, Object> map = DocTemplateJson.toMap(task);
        map.remove("plan_json");
        map.put("has_file", StrUtil.isNotBlank(task.getFileUrl()));
        map.put("can_start", !"RUNNING".equals(task.getStatus()) && !"SUCCEEDED".equals(task.getStatus()));
        // 重新分析任何不在跑的任务都能点：已生成模板的会写回原模板，其余等同于重跑一次
        map.put("can_rerun", !"RUNNING".equals(task.getStatus()));
        // 只有执行中的不能删；删任务不影响已经生成的模板
        map.put("can_delete", !"RUNNING".equals(task.getStatus()));
        return map;
    }

    /**
     * 思维链行：提示词只落库，不经接口返回。
     *
     * <p>提示词里是整份文档快照与内部系统说明，属于实现细节：页面不展示，接口也不返回，
     * 需要排查时直接查表。</p>
     */
    private Map<String, Object> traceMap(DocTemplateAiTraceEntity trace) {
        Map<String, Object> map = DocTemplateJson.toMap(trace);
        map.remove("system_prompt");
        map.remove("user_prompt");
        return map;
    }

    private String importKey(long id, String ext) {
        String prefix = StrUtil.strip(
                StrUtil.blankToDefault(properties.getStorage().getPrefix(), "").trim(), "/", "/");
        String base = StrUtil.blankToDefault(prefix, "doc-template");
        return base + "/ai-imports/" + id + "/" + cn.hutool.core.util.IdUtil.fastSimpleUUID() + "." + ext;
    }

    /** 编码只留字母数字下划线，且以字母开头；加时间戳后缀，同名文档重复导入不会撞唯一约束。 */
    private static String suggestCode(String name) {
        String cleaned = name.replaceAll("[^A-Za-z0-9_]+", "_").replaceAll("^_+|_+$", "");
        String head = cleaned.matches("^[A-Za-z].*") ? cleaned : "tpl_" + cleaned;
        return head.substring(0, Math.min(40, head.length())) + "_" + Long.toString(System.currentTimeMillis(), 36);
    }

    private static String required(String value, String label) {
        if (StrUtil.isBlank(value)) throw new IllegalArgumentException(label + "不能为空");
        return value.trim();
    }

    /** 从请求体里取 {@code ids}：去重、保持原顺序，空列表与超限直接拒绝。 */
    private static List<Long> idsOf(Map<String, Object> body) {
        Object raw = body == null ? null : body.get("ids");
        if (!(raw instanceof java.util.Collection<?> list) || list.isEmpty())
            throw new IllegalArgumentException("请先选择要删除的任务（ids 不能为空）");
        Set<Long> ids = new java.util.LinkedHashSet<>();
        for (Object item : list) {
            if (item == null || StrUtil.isBlank(String.valueOf(item))) continue;
            try {
                ids.add(Long.parseLong(String.valueOf(item).trim()));
            } catch (NumberFormatException error) {
                throw new IllegalArgumentException("ids 里有非法的主键：" + item);
            }
        }
        if (ids.isEmpty()) throw new IllegalArgumentException("请先选择要删除的任务（ids 不能为空）");
        if (ids.size() > BATCH_DELETE_LIMIT)
            throw new IllegalArgumentException("一次最多删除 " + BATCH_DELETE_LIMIT + " 条，当前选了 " + ids.size() + " 条");
        return new ArrayList<>(ids);
    }

    private static String storable(String message) {
        if (message == null) return null;
        return message.length() <= MAX_ERROR_MESSAGE ? message : message.substring(0, MAX_ERROR_MESSAGE);
    }

    /** 按字符截断（不追加省略号：这些值会原样显示在表单里）。 */
    private static String truncate(String value, int limit) {
        return value.length() <= limit ? value : value.substring(0, limit);
    }

    /** 取异常消息：SQL 脱敏异常取外层，其余取根因。 */
    private static String rootMessage(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String text = root.getMessage();
        if (text != null && !text.isBlank()) return text;
        text = error.getMessage();
        return text == null || text.isBlank() ? root.getClass().getSimpleName() : text;
    }
}
