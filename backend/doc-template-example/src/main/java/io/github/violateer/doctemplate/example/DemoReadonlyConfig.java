package io.github.violateer.doctemplate.example;

import io.github.violateer.doctemplate.starter.autoconfigure.DocTemplateProperties;
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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * 演示站模式装配：{@code demo.readonly.enabled=true} 时，用一个「内置模板只读」的服务替换默认实现。
 *
 * <p>Starter 的 {@code docTemplateService} 是普通 {@code @Bean}，没有 {@code @ConditionalOnMissingBean}，
 * 所以这里不能「顶掉」它，只能另注册一个同类 Bean 并标 {@code @Primary}——控制器按类型注入，
 * 拿到的是这个带闸门的实现。开关关掉时本类整体不生效，行为和原来完全一样。</p>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "demo.readonly", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(DemoReadonlyProperties.class)
public class DemoReadonlyConfig {

    @Bean
    @Primary
    public DocTemplateService demoReadonlyDocTemplateService(
            DocTemplateRepository templateRepository,
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
        return new DemoReadonlyDocTemplateService(templateRepository, versionRepository, blockRepository,
                recordRepository, properties, fileStorage, renderer, pdfRenderer,
                dataBlockExecutor, dictService, readonly);
    }
}
