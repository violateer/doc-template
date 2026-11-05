package io.github.violateer.doctemplate.example;

import io.github.violateer.doctemplate.starter.autoconfigure.DocTemplateProperties;
import io.github.violateer.doctemplate.starter.entity.DocTemplateDataBlockEntity;
import io.github.violateer.doctemplate.starter.entity.DocTemplateEntity;
import io.github.violateer.doctemplate.starter.repository.DocTemplateDataBlockRepository;
import io.github.violateer.doctemplate.starter.repository.DocTemplateGenerateRecordRepository;
import io.github.violateer.doctemplate.starter.repository.DocTemplateRepository;
import io.github.violateer.doctemplate.starter.repository.DocTemplateVersionRepository;
import io.github.violateer.doctemplate.starter.service.DocTemplateDataBlockExecutor;
import io.github.violateer.doctemplate.starter.service.DocTemplateDictService;
import io.github.violateer.doctemplate.starter.service.DocTemplateFileStorage;
import io.github.violateer.doctemplate.starter.service.DocTemplatePdfRenderer;
import io.github.violateer.doctemplate.starter.service.DocTemplateRenderer;
import io.github.violateer.doctemplate.starter.service.DocTemplateService;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * 演示站用的模板服务：在 {@link DocTemplateService} 的写操作前面加一道「内置模板只读」的闸门。
 *
 * <p>为什么在服务层拦而不是在控制器或过滤器上拦：写入口有七八个（改元数据、传新版本、删版本、
 * 切当前版本、删模板，加上数据块的增删改），批量删除还是先按 id 逐条调 {@code deleteTemplate}。
 * 拦在这一层只需要重写方法，不用解析 URL、不用处理请求体，批量删除也天然覆盖——
 * 被保护的那几条会出现在返回的 {@code failed} 里，其余照常删除。</p>
 *
 * <p>抛 {@link IllegalStateException}：Starter 的异常处理器会把它翻成 400 + 原文消息，
 * 前端弹窗能直接看到「这是演示站的内置模板」，不用在示例里再写一套异常映射。</p>
 */
public class DemoReadonlyDocTemplateService extends DocTemplateService {

    private final DemoReadonlyProperties readonly;
    private final DocTemplateRepository templateRepository;
    private final DocTemplateDataBlockRepository blockRepository;

    public DemoReadonlyDocTemplateService(DocTemplateRepository templateRepository,
                                          DocTemplateVersionRepository versionRepository,
                                          DocTemplateDataBlockRepository blockRepository,
                                          DocTemplateGenerateRecordRepository recordRepository,
                                          DocTemplateProperties properties,
                                          DocTemplateFileStorage fileStorage,
                                          DocTemplateRenderer renderer,
                                          DocTemplatePdfRenderer pdfRenderer,
                                          DocTemplateDataBlockExecutor dataBlockExecutor,
                                          DocTemplateDictService dictService,
                                          DemoReadonlyProperties readonly) {
        super(templateRepository, versionRepository, blockRepository, recordRepository,
                properties, fileStorage, renderer, pdfRenderer, dataBlockExecutor, dictService);
        this.readonly = readonly;
        this.templateRepository = templateRepository;
        this.blockRepository = blockRepository;
    }

    /* ------------------------------------------------------------ 模板写操作 */

    @Override
    public Map<String, Object> updateTemplate(long id, Map<String, Object> body) {
        assertTemplateWritable(id);
        return super.updateTemplate(id, body);
    }

    @Override
    public void deleteTemplate(long id) {
        assertTemplateWritable(id);
        super.deleteTemplate(id);
    }

    @Override
    public void deleteVersion(long id, long versionId) {
        assertTemplateWritable(id);
        super.deleteVersion(id, versionId);
    }

    @Override
    public Map<String, Object> setCurrentVersion(long id, long versionId) {
        assertTemplateWritable(id);
        return super.setCurrentVersion(id, versionId);
    }

    @Override
    public Map<String, Object> upload(long id, MultipartFile file) throws Exception {
        assertTemplateWritable(id);
        return super.upload(id, file);
    }

    /* ---------------------------------------------------------- 数据块写操作 */

    @Override
    public Map<String, Object> createBlock(long templateId, Map<String, Object> body) {
        assertTemplateWritable(templateId);
        return super.createBlock(templateId, body);
    }

    @Override
    public Map<String, Object> updateBlock(long id, Map<String, Object> body) {
        assertBlockWritable(id);
        return super.updateBlock(id, body);
    }

    @Override
    public void deleteBlock(long id) {
        assertBlockWritable(id);
        super.deleteBlock(id);
    }

    /* ---------------------------------------------------------------- 内部 */

    /** 数据块只给了块主键，先查出它属于哪个模板，再按模板判断。 */
    private void assertBlockWritable(long blockId) {
        if (!readonly.isEnabled()) return;
        DocTemplateDataBlockEntity block = blockRepository.findById(blockId);
        // 查不到就交给上层抛 404，这里不抢答
        if (block == null) return;
        assertTemplateWritable(block.getTemplateId());
    }

    private void assertTemplateWritable(long templateId) {
        if (!readonly.isEnabled()) return;
        DocTemplateEntity template = templateRepository.findById(templateId);
        if (template == null) return;
        if (!readonly.getTemplateCodes().contains(template.getTemplateCode())) return;
        throw new IllegalStateException("「" + template.getTemplateName()
                + "」是演示站的内置模板，不支持修改或删除。");
    }
}
