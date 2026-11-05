package io.github.violateer.doctemplate.starter.service;

import cn.hutool.core.convert.Convert;
import cn.hutool.core.exceptions.ExceptionUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import io.github.violateer.doctemplate.starter.autoconfigure.DocTemplateProperties;
import io.github.violateer.doctemplate.starter.entity.DocTemplateDataBlockEntity;
import io.github.violateer.doctemplate.starter.entity.DocTemplateEntity;
import io.github.violateer.doctemplate.starter.entity.DocTemplateGenerateRecordEntity;
import io.github.violateer.doctemplate.starter.entity.DocTemplateVersionEntity;
import io.github.violateer.doctemplate.starter.repository.DocTemplateDataBlockRepository;
import io.github.violateer.doctemplate.starter.repository.DocTemplateGenerateRecordRepository;
import io.github.violateer.doctemplate.starter.repository.DocTemplateRepository;
import io.github.violateer.doctemplate.starter.repository.DocTemplateVersionRepository;
import io.github.violateer.doctemplate.starter.support.DocTemplateImageSpec;
import io.github.violateer.doctemplate.starter.support.DocTemplateMergeAlign;
import io.github.violateer.doctemplate.starter.support.DocTemplateJson;
import io.github.violateer.doctemplate.starter.support.DocTemplateQrCodeSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

public class DocTemplateService {
    private static final Logger log = LoggerFactory.getLogger(DocTemplateService.class);
    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private final DocTemplateRepository templateRepository;
    private final DocTemplateVersionRepository versionRepository;
    private final DocTemplateDataBlockRepository blockRepository;
    private final DocTemplateGenerateRecordRepository recordRepository;
    private final DocTemplateProperties props;
    private final DocTemplateFileStorage fileStorage;
    private final DocTemplateRenderer renderer;
    private final DocTemplatePdfRenderer pdfRenderer;
    private final DocTemplateDataBlockExecutor dataBlockExecutor;
    private final DocTemplateDictService dictService;

    public DocTemplateService(DocTemplateRepository templateRepository, DocTemplateVersionRepository versionRepository,
                              DocTemplateDataBlockRepository blockRepository, DocTemplateGenerateRecordRepository recordRepository,
                              DocTemplateProperties props, DocTemplateFileStorage fileStorage,
                              DocTemplateRenderer renderer, DocTemplatePdfRenderer pdfRenderer,
                              DocTemplateDataBlockExecutor dataBlockExecutor, DocTemplateDictService dictService) {
        this.templateRepository = templateRepository;
        this.versionRepository = versionRepository;
        this.blockRepository = blockRepository;
        this.recordRepository = recordRepository;
        this.props = props;
        this.fileStorage = fileStorage;
        this.renderer = renderer;
        this.pdfRenderer = pdfRenderer;
        this.dataBlockExecutor = dataBlockExecutor;
        this.dictService = dictService;
    }

    private static String contentType(String fileType) {
        if ("XLSX".equalsIgnoreCase(fileType))
            return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    }

    /** 分页总页数：0 条数据时返回 0，与前端分页器保持一致。 */
    private static int pages(long total, int pageSize) {
        return (int) ((total + pageSize - 1) / pageSize);
    }

    /**
     * 取异常消息用于展示/入库。SQL 数据块的异常消息已脱敏，此时要取「外层包装消息」而不是根因，
     * 否则 {@code rootCauseMessage} 会把数据库原文（表名、列名、SQL 片段）又捞回来。
     */
    private static String rootMessage(Throwable error) {
        for (Throwable current = error; current != null && current != current.getCause(); current = current.getCause()) {
            if (current instanceof DocTemplateSqlException) {
                return StrUtil.blankToDefault(error.getMessage(), "SQL 执行失败");
            }
        }
        return StrUtil.blankToDefault(ExceptionUtil.getRootCauseMessage(error), error.getClass().getSimpleName());
    }

    /** SQL 数据源被宿主关闭后，保存与执行都要拒绝。 */
    private void requireSqlEnabled(String type) {
        if ("SQL".equals(type) && !props.getDataBlock().isSqlEnabled()) {
            throw new IllegalArgumentException(
                    "SQL 数据源已被禁用（doc-template.data-block.sql-enabled=false），请改用 JAVA 或 API 数据源");
        }
    }

    /**
     * 数据块编码在同一个模板内必须唯一（{@code template_id + block_code} 上有唯一索引）。
     *
     * <p>先查一次是为了给出可读的中文提示；真撞上了也有唯一索引兜底，不会写出重复数据。
     * {@code selfId} 非空时把自己排除掉，改编码不会跟自己冲突。</p>
     */
    private void requireBlockCodeFree(long templateId, String code, Long selfId) {
        DocTemplateDataBlockEntity same = blockRepository.findByTemplateIdAndCode(templateId, code);
        if (same != null && !Objects.equals(same.getId(), selfId))
            throw new IllegalArgumentException("该模板下已存在编码为 " + code + " 的数据块");
    }

    public List<Map<String, Object>> templates() {
        return templateRepository.findAll().stream().map(this::toMap).toList();
    }

    /**
     * 模板分页列表：支持 {@code keyword}（模板名称 / 编码）与 {@code status} 过滤，
     * 返回 {@code {records,total,page,size,pages}} 结构，供管理页做服务端分页。
     */
    public Map<String, Object> templatesPage(int page, int size, String keyword, String status) {
        int current = Math.max(1, page);
        int pageSize = Math.min(200, Math.max(1, size));
        String statusFilter = StrUtil.blankToDefault(status, "").trim();
        String text = StrUtil.blankToDefault(keyword, "").trim();
        long total = templateRepository.count(text, statusFilter);
        List<DocTemplateEntity> rows = templateRepository.findPage(text, statusFilter, (current - 1) * pageSize, pageSize);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("records", rows.stream().map(this::toMap).toList());
        data.put("total", total);
        data.put("page", current);
        data.put("size", pageSize);
        data.put("pages", pages(total, pageSize));
        return data;
    }

    /**
     * 模板总览统计（不受分页影响）：总数 / 启用数 / 已上传文件数。
     */
    public Map<String, Object> templateStats() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", templateRepository.countAll());
        data.put("enabled", templateRepository.countByStatus("ENABLED"));
        data.put("uploaded", templateRepository.countUploaded());
        return data;
    }

    public Map<String, Object> template(long id) {
        return toMap(requireTemplate(id));
    }

    public Map<String, Object> createTemplate(Map<String, Object> body) {
        String code = req(body, "templateCode"), name = req(body, "templateName");
        // 类型在首次上传文件前仅作默认值；上传成功后始终以真实扩展名覆盖。
        String fileType = upper(body.getOrDefault("fileType", "DOCX")), export = upper(body.getOrDefault("exportType", "ORIGINAL"));
        if (!Set.of("DOCX", "XLSX").contains(fileType))
            throw new IllegalArgumentException("fileType must be DOCX or XLSX");
        if (!Set.of("ORIGINAL", "PDF").contains(export))
            throw new IllegalArgumentException("exportType must be ORIGINAL or PDF");
        if (templateRepository.findByCode(code) != null)
            throw new IllegalArgumentException("模板编码已存在：" + code);
        DocTemplateEntity e = new DocTemplateEntity();
        e.setTemplateCode(code);
        e.setTemplateName(name);
        e.setFileType(fileType);
        e.setExportType(export);
        e.setStatus(String.valueOf(body.getOrDefault("status", "ENABLED")));
        e.setRemark(str(body.get("remark")));
        e.setCreateTime(LocalDateTime.now());
        e.setUpdateTime(LocalDateTime.now());
        templateRepository.insert(e);
        return template(e.getId());
    }

    /**
     * 更新模板基本信息。编码可以改（{@code template_code} 只是业务标识，不参与渲染），
     * 但要非空且全局唯一；请求里不带 {@code templateCode} 时保持原值。
     */
    public Map<String, Object> updateTemplate(long id, Map<String, Object> b) {
        DocTemplateEntity e = requireTemplate(id);
        if (b.get("templateCode") != null) {
            String code = str(b.get("templateCode")).trim();
            if (code.isEmpty()) throw new IllegalArgumentException("模板编码不能为空");
            DocTemplateEntity same = templateRepository.findByCode(code);
            if (same != null && !Objects.equals(same.getId(), id))
                throw new IllegalArgumentException("模板编码已存在：" + code);
            e.setTemplateCode(code);
        }
        e.setTemplateName(req(b, "templateName"));
        e.setExportType(upper(b.getOrDefault("exportType", "ORIGINAL")));
        e.setStatus(String.valueOf(b.getOrDefault("status", "ENABLED")));
        e.setRemark(str(b.get("remark")));
        e.setUpdateTime(LocalDateTime.now());
        templateRepository.update(e);
        return template(id);
    }

    /**
     * Removes the template together with every stored object (template files and generated files).
     */
    public void deleteTemplate(long id) {
        List<DocTemplateVersionEntity> versions = versionRepository.findByTemplateId(id);
        List<DocTemplateGenerateRecordEntity> records = recordRepository.findByTemplateId(id);
        for (DocTemplateVersionEntity version : versions) deleteStoredQuietly(version.getFileUrl());
        for (DocTemplateGenerateRecordEntity record : records) deleteStoredQuietly(record.getResultFileUrl());
        blockRepository.deleteByTemplateId(id);
        versionRepository.deleteByTemplateId(id);
        recordRepository.deleteByTemplateId(id);
        templateRepository.deleteById(id);
    }

    /**
     * Deletes a single template version and its stored file, keeping other versions intact.
     */
    public void deleteVersion(long id, long versionId) {
        DocTemplateEntity t = requireTemplate(id);
        DocTemplateVersionEntity v = requireVersion(versionId);
        if (!Objects.equals(v.getTemplateId(), id))
            throw new IllegalArgumentException("Version does not belong to template: " + versionId);
        deleteStoredQuietly(v.getFileUrl());
        versionRepository.deleteById(versionId);
        if (Objects.equals(t.getCurrentVersionId(), versionId)) {
            DocTemplateVersionEntity latest = versionRepository.findLatest(id);
            t.setCurrentVersionId(latest == null ? null : latest.getId());
            t.setUpdateTime(LocalDateTime.now());
            templateRepository.update(t);
        }
    }

    /**
     * 把某个历史版本重新设为当前版本（生成文档时用这一版的文件）。
     *
     * <p>只改 {@code current_version_id}，不复制文件、不动其他版本；模板的 {@code file_type}
     * 跟着该版本文件的扩展名走，避免切到 xlsx 版本后还按 docx 渲染。</p>
     */
    public Map<String, Object> setCurrentVersion(long id, long versionId) {
        DocTemplateEntity t = requireTemplate(id);
        DocTemplateVersionEntity v = requireVersion(versionId);
        if (!Objects.equals(v.getTemplateId(), id))
            throw new IllegalArgumentException("Version does not belong to template: " + versionId);
        String ext = FileUtil.extName(v.getFileName());
        if (StrUtil.equalsIgnoreCase("docx", ext)) t.setFileType("DOCX");
        else if (StrUtil.equalsIgnoreCase("xlsx", ext)) t.setFileType("XLSX");
        t.setCurrentVersionId(versionId);
        t.setUpdateTime(LocalDateTime.now());
        templateRepository.update(t);
        return template(id);
    }

    public Map<String, Object> upload(long id, MultipartFile file) throws Exception {
        DocTemplateEntity t = requireTemplate(id);
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("Please select a file");
        String name = StrUtil.blankToDefault(file.getOriginalFilename(), "template");
        String suffix = FileUtil.extName(name);
        String ext = StrUtil.equalsIgnoreCase("docx", suffix) ? "DOCX" : StrUtil.equalsIgnoreCase("xlsx", suffix) ? "XLSX" : "";
        if (ext.isEmpty())
            throw new IllegalArgumentException("Unsupported file extension. Only .docx and .xlsx are supported");
        byte[] bytes = file.getBytes();
        String storedLocation = fileStorage.store(id, name, bytes);
        String sha = sha256(bytes);
        DocTemplateVersionEntity latest = versionRepository.findLatest(id);
        int max = latest == null || latest.getVersionNo() == null ? 0 : latest.getVersionNo();
        DocTemplateVersionEntity v = new DocTemplateVersionEntity();
        v.setTemplateId(id);
        v.setVersionNo(max + 1);
        v.setFileName(name);
        v.setFileUrl(storedLocation);
        v.setFileSha256(sha);
        v.setCreateTime(LocalDateTime.now());
        versionRepository.insert(v);
        // 用户上传什么格式就记录什么格式，不再要求与创建模板时的默认 fileType 一致。
        t.setFileType(ext);
        t.setCurrentVersionId(v.getId());
        t.setUpdateTime(LocalDateTime.now());
        templateRepository.update(t);
        return toMap(v);
    }

    public List<Map<String, Object>> versions(long id) {
        requireTemplate(id);
        return versionRepository.findByTemplateId(id).stream().map(this::toMap).toList();
    }

    public byte[] versionFileBytes(long versionId) throws Exception {
        return fileStorage.read(requireVersion(versionId).getFileUrl());
    }

    public String versionFileName(long versionId) {
        return requireVersion(versionId).getFileName();
    }

    public String versionContentType(long versionId) {
        DocTemplateEntity t = templateRepository.findById(requireVersion(versionId).getTemplateId());
        return contentType(t == null ? null : t.getFileType());
    }

    /**
     * Placeholders found in the current template file, used to pre-fill the simulation form.
     */
    public Map<String, Object> placeholders(long templateId) throws Exception {
        DocTemplateEntity t = requireTemplate(templateId);
        Long versionId = t.getCurrentVersionId();
        if (versionId == null)
            return Map.of("template_id", templateId, "file_type", String.valueOf(t.getFileType()), "version_id", null, "placeholders", List.of(), "sample_params", Map.of());
        DocTemplateVersionEntity v = requireVersion(versionId);
        byte[] bytes = fileStorage.read(v.getFileUrl());
        Set<String> keys = renderer.placeholders(bytes, t.getFileType());
        Set<String> loopFields = renderer.loopFields(bytes, t.getFileType());
        Map<String, Object> sample = new LinkedHashMap<>();
        for (String key : keys) sample.put(key, "");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("template_id", templateId);
        result.put("version_id", versionId);
        result.put("file_type", t.getFileType());
        result.put("version_no", v.getVersionNo());
        result.put("file_name", v.getFileName());
        result.put("placeholders", new ArrayList<>(keys));
        result.put("sample_params", sample);
        // 循环体里的字段由数组项提供，模拟生成时不需要手填
        result.put("loop_fields", new ArrayList<>(loopFields));
        return result;
    }

    public List<Map<String, Object>> blocks(long templateId) {
        requireTemplate(templateId);
        return blockRepository.findByTemplateId(templateId).stream().map(this::toMap).toList();
    }

    public Map<String, Object> createBlock(long templateId, Map<String, Object> b) {
        requireTemplate(templateId);
        String code = req(b, "blockCode"), name = req(b, "blockName"), type = upper(b.getOrDefault("dataSourceType", "JAVA"));
        if (!Set.of("JAVA", "SQL", "API").contains(type))
            throw new IllegalArgumentException("Unsupported dataSourceType");
        requireSqlEnabled(type);
        requireBlockCodeFree(templateId, code, null);
        DocTemplateDataBlockEntity e = new DocTemplateDataBlockEntity();
        e.setTemplateId(templateId);
        e.setBlockCode(code);
        e.setBlockName(name);
        e.setDataSourceType(type);
        e.setDataSourceConfig(json(b.getOrDefault("dataSourceConfig", Map.of())));
        e.setParamConfig(json(b.getOrDefault("paramConfig", Map.of())));
        e.setSortOrder(intVal(b.get("sortOrder")));
        e.setStatus(String.valueOf(b.getOrDefault("status", "ENABLED")));
        blockRepository.insert(e);
        return toMap(blockRepository.findByTemplateIdAndCode(templateId, code));
    }

    /**
     * 更新数据块。编码可以改，但要非空且在同一模板内唯一；请求里不带 {@code blockCode} 时保持原值。
     *
     * <p>改编码不会动模板文件，改完记得同步模板里的 {@code ${编码.字段}} / {@code {{#each 编码}}}，
     * 否则生成时取不到这个块的数据（页面上有提示）。</p>
     */
    public Map<String, Object> updateBlock(long id, Map<String, Object> b) {
        DocTemplateDataBlockEntity e = blockRepository.findById(id);
        if (e == null) throw new NoSuchElementException("Record not found");
        if (b.get("blockCode") != null) {
            String code = str(b.get("blockCode")).trim();
            if (code.isEmpty()) throw new IllegalArgumentException("数据块编码不能为空");
            requireBlockCodeFree(e.getTemplateId(), code, id);
            e.setBlockCode(code);
        }
        e.setBlockName(req(b, "blockName"));
        String type = upper(b.getOrDefault("dataSourceType", "JAVA"));
        requireSqlEnabled(type);
        e.setDataSourceType(type);
        e.setDataSourceConfig(json(b.getOrDefault("dataSourceConfig", Map.of())));
        e.setParamConfig(json(b.getOrDefault("paramConfig", Map.of())));
        e.setSortOrder(intVal(b.get("sortOrder")));
        e.setStatus(String.valueOf(b.getOrDefault("status", "ENABLED")));
        blockRepository.update(e);
        return toMap(e);
    }

    public void deleteBlock(long id) {
        blockRepository.deleteById(id);
    }

    /**
     * 预览数据块：不落库、不生成文档，只按传进来的配置执行一次取数，配完立刻能验证。
     *
     * <p>请求体与新增/编辑数据块一致（{@code dataSourceType} / {@code dataSourceConfig} /
     * {@code paramConfig}），额外带一个 {@code requestParams}，就是生成时手填的那份参数。
     * 走的是和生成完全相同的参数映射与校验路径，所以预览通过不代表模板占位符写对了。</p>
     *
     * <p>失败不抛 500：把错误消息放进 {@code ok=false} 的响应里，页面直接展示，
     * 省得一次配置错误就变成「服务内部错误」。</p>
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> previewBlock(Map<String, Object> b) {
        String type = upper(b.getOrDefault("dataSourceType", "JAVA"));
        if (!Set.of("JAVA", "SQL", "API").contains(type))
            throw new IllegalArgumentException("Unsupported dataSourceType");
        requireSqlEnabled(type);
        DocTemplateDataBlockEntity e = new DocTemplateDataBlockEntity();
        // 预览不落库，块编码只是占位；执行器不会拿它做命名空间
        e.setBlockCode("preview");
        e.setBlockName(StrUtil.blankToDefault(Convert.toStr(b.get("blockName")), "数据块预览"));
        e.setDataSourceType(type);
        e.setDataSourceConfig(json(b.getOrDefault("dataSourceConfig", Map.of())));
        e.setParamConfig(json(b.getOrDefault("paramConfig", Map.of())));
        Object rawParams = b.get("requestParams");
        Map<String, Object> requestParams = rawParams instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();

        long started = System.currentTimeMillis();
        Map<String, Object> result = new LinkedHashMap<>();
        dictService.beginScope();
        try {
            result.put("ok", true);
            result.put("data", dataBlockExecutor.execute(e, requestParams));
        } catch (Exception error) {
            result.put("ok", false);
            result.put("error", rootMessage(error));
            // 预览失败不抛 500（页面上直接展示原因），但日志要留一条，否则调数据块时全靠猜
            log.warn("数据块预览失败：type={}，block={}", type, e.getBlockName(), error);
        } finally {
            dictService.clearScope();
        }
        result.put("elapsed_ms", System.currentTimeMillis() - started);
        return result;
    }

    public List<Map<String, Object>> records() {
        List<DocTemplateGenerateRecordEntity> list = recordRepository.findAll();
        Map<Long, DocTemplateEntity> templates = templatesOf(list);
        return list.stream().map(e -> recordMap(e, templates.get(e.getTemplateId()))).toList();
    }

    /**
     * 分页查询生成记录，支持关键字与状态过滤。
     *
     * <p>关键字匹配记录号、生成人、失败说明以及模板名称；模板名称不是记录表的列，
     * 先按名称查出模板 id 再 {@code IN} 过滤，避免拼接 SQL。</p>
     */
    public Map<String, Object> recordsPage(int page, int size, String keyword, String status) {
        int current = Math.max(1, page);
        int pageSize = Math.min(200, Math.max(1, size));
        String statusFilter = StrUtil.blankToDefault(status, "").trim();
        String text = StrUtil.blankToDefault(keyword, "").trim();
        List<Long> templateIds = text.isEmpty() ? List.of() : templateRepository.findIdsByNameLike(text);
        long total = recordRepository.count(text, statusFilter, templateIds);
        List<DocTemplateGenerateRecordEntity> rows =
                recordRepository.findPage(text, statusFilter, templateIds, (current - 1) * pageSize, pageSize);
        Map<Long, DocTemplateEntity> templates = templatesOf(rows);
        List<Map<String, Object>> items = rows.stream()
                .map(e -> recordMap(e, templates.get(e.getTemplateId()))).toList();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("records", items);
        data.put("total", total);
        data.put("page", current);
        data.put("size", pageSize);
        data.put("pages", pages(total, pageSize));
        return data;
    }

    /** 一次查出这批记录用到的模板，避免逐条 selectById。 */
    private Map<Long, DocTemplateEntity> templatesOf(List<DocTemplateGenerateRecordEntity> records) {
        Set<Long> ids = records.stream().map(DocTemplateGenerateRecordEntity::getTemplateId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) return Map.of();
        return templateRepository.findByIds(ids).stream()
                .collect(Collectors.toMap(DocTemplateEntity::getId, t -> t, (a, b) -> a));
    }

    /**
     * 模板当前生效的版本：优先用 {@code current_version_id}（每次上传都会指向新版本）。
     * 老数据里该字段可能为空，此时退回版本号最大的那条，保证「总是用最新上传的文件」。
     */
    private Long currentVersionId(DocTemplateEntity template) {
        if (template.getCurrentVersionId() != null) return template.getCurrentVersionId();
        DocTemplateVersionEntity latest = versionRepository.findLatest(template.getId());
        if (latest == null) return null;
        // 顺手把空字段补上，后续请求就不用再查一次
        template.setCurrentVersionId(latest.getId());
        template.setUpdateTime(LocalDateTime.now());
        templateRepository.update(template);
        return latest.getId();
    }

    /**
     * Simulates document generation: renders the current (or given) template version with the
     * supplied parameters and stores the produced file. Failures are persisted as records so the
     * history shows what went wrong instead of silently disappearing.
     */
    public Map<String, Object> generate(long templateId, Map<String, Object> body, String user) {
        DocTemplateEntity t = requireTemplate(templateId);
        // 两个分支都保持 Long：写成 long 会让三元表达式整体变成基本类型，versionId 为 null 时直接 NPE
        Long versionId = body.get("templateVersionId") == null
                ? currentVersionId(t)
                : Long.valueOf(String.valueOf(body.get("templateVersionId")));
        String export = upper(body.getOrDefault("exportType", t.getExportType() == null ? "ORIGINAL" : t.getExportType()));
        Map<String, Object> params = paramsOf(body.get("requestParams") != null ? body.get("requestParams") : body.get("params"));
        DocTemplateGenerateRecordEntity e = new DocTemplateGenerateRecordEntity();
        e.setTemplateId(templateId);
        e.setTemplateVersionId(versionId);
        e.setExportType(export);
        e.setRequestParams(json(params));
        e.setCreateBy(user);
        e.setCreateTime(LocalDateTime.now());
        if (versionId == null) {
            e.setStatus("FAILED");
            e.setErrorMessage("该模板尚未上传文件，请先上传 .docx 或 .xlsx 模板文件");
            recordRepository.insert(e);
            return recordMap(e);
        }
        DocTemplateVersionEntity v = requireVersion(versionId);
        // 同一次生成里字典只查一次：明细行几百行时避免逐行 SELECT，结束后必须清掉
        dictService.beginScope();
        try {
            byte[] source = fileStorage.read(v.getFileUrl());
            String fileType = t.getFileType() == null ? "DOCX" : t.getFileType();
            List<DocTemplateDataBlockEntity> blocks = blocksOf(templateId);
            byte[] rendered = renderer.render(source, fileType, withDataBlocks(blocks, params),
                    imageSpecsOf(blocks), mergeAlignOf(blocks));
            String ext = "XLSX".equalsIgnoreCase(fileType) ? ".xlsx" : ".docx";
            byte[] output = rendered;
            if ("PDF".equals(export)) {
                output = pdfRenderer.render(rendered, fileType);
                ext = ".pdf";
            }
            String location = fileStorage.storeGenerated(templateId, ext, output);
            e.setResultFileUrl(location);
            e.setStatus("SUCCESS");
            e.setErrorMessage(null);
        } catch (Exception ex) {
            e.setStatus("FAILED");
            e.setErrorMessage(rootMessage(ex));
            // 生成失败在页面上只有一行脱敏文字，这里必须留完整堆栈：模板占位符写错、数据块取数失败
            // 都发生在服务端，没有这条日志就只能靠猜（SQL 原文也只在堆栈的 cause 里）
            log.error("生成文档失败：templateId={}（{}），versionId={}，exportType={}，fileType={}",
                    templateId, t.getTemplateName(), versionId, export, t.getFileType(), ex);
        } finally {
            dictService.clearScope();
        }
        recordRepository.insert(e);
        return recordMap(e);
    }

    /**
     * 先按顺序执行模板下启用的数据块（结果按 blockCode 命名空间存放），再让请求里手填的参数覆盖。
     * 手填的空串不覆盖数据块已取到的值——模拟生成弹窗「填充占位符」会给所有键补空串，
     * 若不这样处理，数据块取到的值会被空串冲掉。
     */
    private Map<String, Object> withDataBlocks(List<DocTemplateDataBlockEntity> blocks, Map<String, Object> requestParams) {
        Map<String, Object> merged = new LinkedHashMap<>(dataBlockExecutor.collect(blocks, requestParams));
        for (Map.Entry<String, Object> entry : requestParams.entrySet()) {
            Object value = entry.getValue();
            boolean blank = value == null || (value instanceof String s && s.isBlank());
            if (blank && lookupPath(merged, entry.getKey()) != null) continue;
            merged.put(entry.getKey(), value);
        }
        return merged;
    }

    /** 模板下启用的数据块，按执行顺序排好（生成与图片字段解析共用同一份数据）。 */
    private List<DocTemplateDataBlockEntity> blocksOf(long templateId) {
        return blockRepository.findByTemplateId(templateId);
    }

    /**
     * 收集所有启用数据块里声明的图片字段（{@code data_source_config.imageFields}）
     * 和二维码字段（{@code data_source_config.qrCodes}）。
     *
     * <p>渲染器只认「字段名」，不关心来自哪个数据块，所以这里合并成一份列表并按字段名去重。
     * 同一个字段既声明成图片又声明成二维码时，按二维码处理（更具体的那条配置优先）。</p>
     */
    private List<DocTemplateImageSpec> imageSpecsOf(List<DocTemplateDataBlockEntity> blocks) {
        if (blocks == null || blocks.isEmpty()) return List.of();
        Map<String, DocTemplateImageSpec> merged = new LinkedHashMap<>();
        for (DocTemplateDataBlockEntity block : blocks) {
            if (block == null) continue;
            if (!"ENABLED".equalsIgnoreCase(StrUtil.blankToDefault(block.getStatus(), "ENABLED"))) continue;
            Map<String, Object> config = DocTemplateJson.readMap(block.getDataSourceConfig());
            for (DocTemplateImageSpec spec : DocTemplateImageSpec.fromConfig(config)) {
                merged.putIfAbsent(spec.field().toLowerCase(Locale.ROOT), spec);
            }
            for (DocTemplateQrCodeSpec qr : DocTemplateQrCodeSpec.fromConfig(config)) {
                merged.put(qr.field().toLowerCase(Locale.ROOT), qr.toImageSpec());
            }
        }
        return new ArrayList<>(merged.values());
    }

    /**
     * 取合并单元格的对齐方式（{@code data_source_config.mergeCellAlign}）。
     *
     * <p>对齐是「整篇文档一份」的设置，不按数据块区分，所以多个块都配了时以第一个为准，
     * 配得不一致会打一条 warn —— 否则模板作者会搞不清到底哪个生效。</p>
     */
    private DocTemplateMergeAlign mergeAlignOf(List<DocTemplateDataBlockEntity> blocks) {
        if (blocks == null || blocks.isEmpty()) return DocTemplateMergeAlign.DEFAULT;
        DocTemplateMergeAlign result = null;
        String source = null;
        for (DocTemplateDataBlockEntity block : blocks) {
            if (block == null) continue;
            if (!"ENABLED".equalsIgnoreCase(StrUtil.blankToDefault(block.getStatus(), "ENABLED"))) continue;
            Map<String, Object> config = DocTemplateJson.readMap(block.getDataSourceConfig());
            if (!DocTemplateMergeAlign.configured(config)) continue;
            DocTemplateMergeAlign align = DocTemplateMergeAlign.fromConfig(config);
            if (result == null) {
                result = align;
                source = block.getBlockCode();
            } else if (!result.equals(align)) {
                log.warn("数据块「{}」声明的 mergeCellAlign={} 与「{}」的 {} 不一致，按前者生效（对齐是整篇文档一份设置）",
                        block.getBlockCode(), align, source, result);
            }
        }
        return result == null ? DocTemplateMergeAlign.DEFAULT : result;
    }

    /**
     * 支持 {@code a.b.c} 路径取值，与渲染器的占位符取值规则保持一致。
     */
    private Object lookupPath(Map<String, Object> data, String path) {
        if (data.containsKey(path)) return data.get(path);
        Object current = data;
        for (String part : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) return null;
            current = map.get(part);
            if (current == null) return null;
        }
        return current;
    }

    public byte[] recordFileBytes(long recordId) throws Exception {
        DocTemplateGenerateRecordEntity e = requireRecord(recordId);
        if (StrUtil.isBlank(e.getResultFileUrl())) throw new IllegalStateException("该记录没有生成文件");
        return fileStorage.read(e.getResultFileUrl());
    }

    public String recordContentType(long recordId) {
        DocTemplateGenerateRecordEntity e = requireRecord(recordId);
        String url = StrUtil.blankToDefault(e.getResultFileUrl(), "").toLowerCase(Locale.ROOT);
        if (url.endsWith(".pdf")) return "application/pdf";
        if (url.endsWith(".xlsx")) return contentType("XLSX");
        return contentType("DOCX");
    }

    public String recordFileName(long recordId) {
        DocTemplateGenerateRecordEntity e = requireRecord(recordId);
        return fileNameOf(e, templateRepository.findById(e.getTemplateId()));
    }

    /** 由记录与模板拼出下载文件名，不再额外查库（列表分页时每条都查一次会很慢）。 */
    private String fileNameOf(DocTemplateGenerateRecordEntity e, DocTemplateEntity t) {
        String base = t == null ? "document" : t.getTemplateName();
        String url = StrUtil.blankToDefault(e.getResultFileUrl(), "").toLowerCase(Locale.ROOT);
        String ext = url.endsWith(".pdf") ? ".pdf" : url.endsWith(".xlsx") ? ".xlsx" : ".docx";
        LocalDateTime time = e.getCreateTime() == null ? LocalDateTime.now() : e.getCreateTime();
        return base + "_" + FILE_STAMP.format(time) + ext;
    }

    public void deleteRecord(long recordId) {
        DocTemplateGenerateRecordEntity e = requireRecord(recordId);
        deleteStoredQuietly(e.getResultFileUrl());
        recordRepository.deleteById(recordId);
    }

    /* ------------------------------------------------------------ 批量删除 */

    /**
     * 一次批量删除最多多少条。
     *
     * <p>管理页是服务端分页、每页最多 100 条，正常勾选不会超过这个数；
     * 设上限是为了让「误传一万个 id」的请求在入口就被拒掉，而不是慢慢把库删空。</p>
     */
    private static final int BATCH_DELETE_LIMIT = 200;

    /**
     * 批量删除模板：逐条走 {@link #deleteTemplate(long)}，单条失败不影响其他条。
     *
     * <p>返回 {@code {deleted, failed:[{id, message}]}}。整体不抛异常、不回滚——批量删除里
     * 一条失败通常是那条数据本身有问题（比如被别的事务锁住），把它单独报给前端比整批失败有用。</p>
     */
    public Map<String, Object> deleteTemplates(Map<String, Object> body) {
        return deleteEach(idsOf(body), this::deleteTemplate);
    }

    /** 批量删除生成记录，语义同 {@link #deleteTemplates(Map)}。 */
    public Map<String, Object> deleteRecords(Map<String, Object> body) {
        return deleteEach(idsOf(body), this::deleteRecord);
    }

    /** 批量删除数据块，语义同 {@link #deleteTemplates(Map)}。 */
    public Map<String, Object> deleteBlocks(Map<String, Object> body) {
        return deleteEach(idsOf(body), this::deleteBlock);
    }

    /** 从请求体里取 {@code ids}：去重、保持原顺序，空列表和超限都直接拒绝。 */
    private static List<Long> idsOf(Map<String, Object> body) {
        Object raw = body == null ? null : body.get("ids");
        if (!(raw instanceof Collection<?> list) || list.isEmpty())
            throw new IllegalArgumentException("请先选择要删除的数据（ids 不能为空）");
        Set<Long> ids = new LinkedHashSet<>();
        for (Object item : list) {
            if (item == null || StrUtil.isBlank(String.valueOf(item))) continue;
            try {
                ids.add(Long.parseLong(String.valueOf(item).trim()));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("ids 里有非法的主键：" + item);
            }
        }
        if (ids.isEmpty()) throw new IllegalArgumentException("请先选择要删除的数据（ids 不能为空）");
        if (ids.size() > BATCH_DELETE_LIMIT)
            throw new IllegalArgumentException("一次最多删除 " + BATCH_DELETE_LIMIT + " 条，当前选了 " + ids.size() + " 条");
        return new ArrayList<>(ids);
    }

    /** 逐条执行删除并收集失败项，成功条数放在 {@code deleted} 里。 */
    private Map<String, Object> deleteEach(List<Long> ids, java.util.function.LongConsumer action) {
        List<Map<String, Object>> failed = new ArrayList<>();
        int deleted = 0;
        for (Long id : ids) {
            try {
                action.accept(id);
                deleted++;
            } catch (RuntimeException e) {
                String reason = rootMessage(e);
                log.warn("批量删除失败 id={}：{}", id, reason);
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", id);
                item.put("message", reason);
                failed.add(item);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deleted", deleted);
        result.put("failed", failed);
        return result;
    }

    private Map<String, Object> recordMap(DocTemplateGenerateRecordEntity e) {
        return recordMap(e, templateRepository.findById(e.getTemplateId()));
    }

    private Map<String, Object> recordMap(DocTemplateGenerateRecordEntity e, DocTemplateEntity template) {
        Map<String, Object> map = toMap(e);
        boolean hasFile = StrUtil.isNotBlank(e.getResultFileUrl());
        map.put("has_result_file", hasFile);
        map.put("template_name", template == null ? null : template.getTemplateName());
        if (hasFile) map.put("result_file_name", fileNameOf(e, template));
        return map;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> paramsOf(Object value) {
        if (value == null) return new LinkedHashMap<>();
        if (value instanceof Map<?, ?> map) return new LinkedHashMap<>((Map<String, Object>) map);
        if (value instanceof String text) {
            if (StrUtil.isBlank(text)) return new LinkedHashMap<>();
            try {
                return DocTemplateJson.readMap(text);
            } catch (Exception ex) {
                throw new IllegalArgumentException("参数必须是合法的 JSON 对象：" + rootMessage(ex));
            }
        }
        throw new IllegalArgumentException("requestParams must be a JSON object");
    }

    private void deleteStoredQuietly(String location) {
        try {
            fileStorage.delete(location);
        } catch (Exception ignored) { /* keep DB consistent even if storage cleanup fails */ }
    }

    private DocTemplateEntity requireTemplate(long id) {
        DocTemplateEntity e = templateRepository.findById(id);
        if (e == null) throw new NoSuchElementException("Template not found: " + id);
        return e;
    }

    private DocTemplateVersionEntity requireVersion(long id) {
        DocTemplateVersionEntity e = versionRepository.findById(id);
        if (e == null) throw new NoSuchElementException("Version not found: " + id);
        return e;
    }

    private DocTemplateGenerateRecordEntity requireRecord(long id) {
        DocTemplateGenerateRecordEntity e = recordRepository.findById(id);
        if (e == null) throw new NoSuchElementException("Record not found: " + id);
        return e;
    }

    private Map<String, Object> toMap(Object value) {
        return DocTemplateJson.toMap(value);
    }

    private String req(Map<String, Object> m, String k) {
        String v = str(m.get(k));
        if (StrUtil.isBlank(v)) throw new IllegalArgumentException(k + " is required");
        return v.trim();
    }

    private String str(Object o) {
        return Convert.toStr(o);
    }

    private String upper(Object o) {
        return StrUtil.blankToDefault(str(o), "").trim().toUpperCase(Locale.ROOT);
    }

    private int intVal(Object o) {
        return Convert.toInt(o, 0);
    }

    private String json(Object o) {
        return o instanceof String s ? s : DocTemplateJson.toJson(o);
    }

    private String sha256(byte[] bytes) {
        return DigestUtil.sha256Hex(bytes);
    }
}
