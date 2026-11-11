package io.github.violateer.doctemplate.starter.autoconfigure;

import io.github.violateer.doctemplate.core.convert.DocTemplateValueConverter;
import io.github.violateer.doctemplate.core.datasource.DocumentDataProvider;
import io.github.violateer.doctemplate.starter.controller.DocTemplateDictController;
import io.github.violateer.doctemplate.starter.controller.DocTemplateSystemController;
import io.github.violateer.doctemplate.starter.controller.DocTemplateTemplateController;
import io.github.violateer.doctemplate.starter.controller.DocTemplateDataBlockController;
import io.github.violateer.doctemplate.starter.controller.DocTemplateGenerateRecordController;
import io.github.violateer.doctemplate.starter.controller.DocTemplatePageController;
import io.github.violateer.doctemplate.starter.controller.DocTemplateExceptionHandler;
import io.github.violateer.doctemplate.starter.controller.DocTemplateAnnotateController;
import io.github.violateer.doctemplate.starter.controller.DocTemplateAiController;
import io.github.violateer.doctemplate.starter.ai.DocTemplateAiClient;
import io.github.violateer.doctemplate.starter.ai.DocTemplateAiPlanner;
import io.github.violateer.doctemplate.starter.ai.OpenAiCompatibleClient;
import io.github.violateer.doctemplate.starter.convert.BuiltinConverters;
import io.github.violateer.doctemplate.starter.convert.DictConverter;
import io.github.violateer.doctemplate.starter.convert.DocTemplateConverterRegistry;
import io.github.violateer.doctemplate.starter.db.DocTemplateDialectProvider;
import io.github.violateer.doctemplate.starter.service.DocTemplateAnnotateService;
import io.github.violateer.doctemplate.starter.service.DocTemplateDictService;
import io.github.violateer.doctemplate.starter.service.DocTemplateService;
import io.github.violateer.doctemplate.starter.service.DocTemplateFileStorage;
import io.github.violateer.doctemplate.starter.service.DocTemplateRenderer;
import io.github.violateer.doctemplate.starter.service.DocTemplateImageLoader;
import io.github.violateer.doctemplate.starter.service.DocTemplatePdfRenderer;
import io.github.violateer.doctemplate.starter.service.DocTemplateDataBlockExecutor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import io.github.violateer.doctemplate.starter.repository.DocTemplateDataBlockRepository;
import io.github.violateer.doctemplate.starter.repository.DocTemplateDictItemRepository;
import io.github.violateer.doctemplate.starter.repository.DocTemplateDictRepository;
import io.github.violateer.doctemplate.starter.repository.DocTemplateGenerateRecordRepository;
import io.github.violateer.doctemplate.starter.repository.DocTemplateRepository;
import io.github.violateer.doctemplate.starter.repository.DocTemplateVersionRepository;
import io.github.violateer.doctemplate.starter.auth.DocTemplateGuard;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Qualifier;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * 文档模板模块的自动装配。
 *
 * <p>{@code @AutoConfigureAfter} 用类名字符串同时列出 Spring Boot 3 与 4 的
 * {@code DataSourceAutoConfiguration}：这个注解只按名字做排序，不加载类，
 * 所以不存在的那个名字会被忽略，一份 jar 可以同时跑在 Boot 3 和 Boot 4 上。</p>
 */
@AutoConfiguration
@AutoConfigureAfter(name = {
        "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
        "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration"})
@EnableConfigurationProperties(DocTemplateProperties.class)
@Import({DocTemplatePageController.class, DocTemplateSystemController.class, DocTemplateTemplateController.class, DocTemplateDataBlockController.class, DocTemplateGenerateRecordController.class, DocTemplateDictController.class, DocTemplateAnnotateController.class, DocTemplateAiController.class, DocTemplateExceptionHandler.class})
@ConditionalOnProperty(prefix = "doc-template", name = "enabled", havingValue = "true", matchIfMissing = true)
public class DocTemplateAutoConfiguration {

    @Bean
    public DocumentProviderRegistry documentProviderRegistry(ApplicationContext context) {
        Map<String, DocumentDataProvider> providers = new TreeMap<>();
        context.getBeansOfType(DocumentDataProvider.class).values().forEach(p -> {
            if (p.getCode() == null || p.getCode().isBlank()) throw new IllegalStateException("DocumentDataProvider 的编码不能为空");
            if (providers.putIfAbsent(p.getCode(), p) != null) throw new IllegalStateException("DocumentDataProvider 编码重复：" + p.getCode());
        });
        return new DocumentProviderRegistry(providers);
    }
    @Bean
    public DocTemplateFileStorage docTemplateFileStorage(DocTemplateProperties properties) {
        return new DocTemplateFileStorage(properties);
    }
    @Bean
    public DocTemplateImageLoader docTemplateImageLoader() {
        return new DocTemplateImageLoader();
    }

    /**
     * 字段值转换器注册表：内置 date / number / boolean / percent / upper / lower / trim / mask，
     * 再加上容器里的转换器 Bean（含内置的 dict）。宿主注册同名实现会覆盖内置的那个。
     */
    @Bean
    public DocTemplateConverterRegistry docTemplateConverterRegistry(ApplicationContext context) {
        return new DocTemplateConverterRegistry(BuiltinConverters.all(),
                context.getBeansOfType(DocTemplateValueConverter.class).values());
    }

    /** 数据字典：字典（主表）+ 字典项（子表）的 CRUD，以及单次生成内的取数缓存。 */
    @Bean
    public DocTemplateDictService docTemplateDictService(DocTemplateDictRepository dictRepository,
                                                        DocTemplateDictItemRepository dictItemRepository) {
        return new DocTemplateDictService(dictRepository, dictItemRepository);
    }

    /** 字典转换器：要查库，所以做成 Bean 交给注册表收集，而不是塞进 BuiltinConverters。 */
    @Bean
    public DictConverter docTemplateDictConverter(DocTemplateDictService dictService) {
        return new DictConverter(dictService);
    }

    @Bean
    public DocTemplateRenderer docTemplateRenderer(DocTemplateImageLoader imageLoader,
                                                   DocTemplateConverterRegistry converters) {
        return new DocTemplateRenderer(imageLoader, converters);
    }
    @Bean
    public DocTemplatePdfRenderer docTemplatePdfRenderer(DocTemplateProperties properties) {
        return new DocTemplatePdfRenderer(properties);
    }
    @Bean
    public DocTemplateDataBlockExecutor docTemplateDataBlockExecutor(DocumentProviderRegistry registry,
            DocTemplateProperties properties, DocTemplateDataSourceResolver dataSourceResolver,
            DocTemplateConverterRegistry converters) {
        return new DocTemplateDataBlockExecutor(registry, properties, dataSourceResolver, converters);
    }
    /**
     * 数据源解析器：默认完全复用宿主应用的 DataSource，模块自己不建连接池。
     * 只有配置了 doc-template.datasource.url 才会额外创建一个独立连接池，并由这里负责关闭。
     */
    @Bean(destroyMethod = "close")
    public DocTemplateDataSourceResolver docTemplateDataSourceResolver(ApplicationContext context, DocTemplateProperties properties) {
        return new DocTemplateDataSourceResolver(context, properties);
    }
    @Bean
    public DocTemplateJdbcTemplate docTemplateJdbcTemplate(DocTemplateDataSourceResolver dataSourceResolver) {
        return new DocTemplateJdbcTemplate(dataSourceResolver.moduleDataSource());
    }

    /**
     * 数据库方言（MySQL / MariaDB、PostgreSQL、SQL Server）。
     *
     * <p>第一次拼 SQL 时才连库读一次产品名并缓存，所以关掉自动建表、自己执行过 DDL 的宿主
     * 不会因为启动阶段识别数据库而失败。</p>
     */
    @Bean
    public DocTemplateDialectProvider docTemplateDialectProvider(DocTemplateDataSourceResolver dataSourceResolver) {
        return new DocTemplateDialectProvider(dataSourceResolver.moduleDataSource());
    }

    @Bean
    public DocTemplateRepository docTemplateRepository(@Qualifier("docTemplateJdbcTemplate") JdbcTemplate jdbcTemplate,
                                                      DocTemplateDialectProvider dialectProvider) {
        return new DocTemplateRepository(jdbcTemplate, dialectProvider);
    }
    @Bean
    public DocTemplateVersionRepository docTemplateVersionRepository(@Qualifier("docTemplateJdbcTemplate") JdbcTemplate jdbcTemplate,
                                                                     DocTemplateDialectProvider dialectProvider) {
        return new DocTemplateVersionRepository(jdbcTemplate, dialectProvider);
    }
    @Bean
    public DocTemplateDataBlockRepository docTemplateDataBlockRepository(@Qualifier("docTemplateJdbcTemplate") JdbcTemplate jdbcTemplate,
                                                                         DocTemplateDialectProvider dialectProvider) {
        return new DocTemplateDataBlockRepository(jdbcTemplate, dialectProvider);
    }
    @Bean
    public DocTemplateGenerateRecordRepository docTemplateGenerateRecordRepository(@Qualifier("docTemplateJdbcTemplate") JdbcTemplate jdbcTemplate,
                                                                                   DocTemplateDialectProvider dialectProvider) {
        return new DocTemplateGenerateRecordRepository(jdbcTemplate, dialectProvider);
    }
    @Bean
    public DocTemplateDictRepository docTemplateDictRepository(@Qualifier("docTemplateJdbcTemplate") JdbcTemplate jdbcTemplate,
                                                               DocTemplateDialectProvider dialectProvider) {
        return new DocTemplateDictRepository(jdbcTemplate, dialectProvider);
    }
    @Bean
    public DocTemplateDictItemRepository docTemplateDictItemRepository(@Qualifier("docTemplateJdbcTemplate") JdbcTemplate jdbcTemplate,
                                                                       DocTemplateDialectProvider dialectProvider) {
        return new DocTemplateDictItemRepository(jdbcTemplate, dialectProvider);
    }
    @Bean
    public DocTemplateService docTemplateService(
            DocTemplateRepository templateRepository,
            DocTemplateVersionRepository versionRepository,
            DocTemplateDataBlockRepository blockRepository,
            DocTemplateGenerateRecordRepository recordRepository,
            DocTemplateProperties properties, DocTemplateFileStorage fileStorage,
            DocTemplateRenderer renderer, DocTemplatePdfRenderer pdfRenderer,
            DocTemplateDataBlockExecutor dataBlockExecutor, DocTemplateDictService dictService) {
        return new DocTemplateService(templateRepository, versionRepository, blockRepository, recordRepository, properties, fileStorage, renderer, pdfRenderer, dataBlockExecutor, dictService);
    }

    /**
     * 结构化标注：快照 → 按计划落笔 → 模拟数据渲染自检 → 落库。
     *
     * <p>不依赖任何大模型，也不引任何新依赖（POI 本来就在），所以直接放在 Starter 里：
     * 单独拆一个发布物会让发版脚本、版本号同步、管理页的能力探测都多一份成本，
     * 而它带来的隔离收益在这里是零。</p>
     */
    @Bean
    public DocTemplateAnnotateService docTemplateAnnotateService(DocTemplateService templates,
                                                                DocTemplateRenderer renderer,
                                                                DocTemplateDialectProvider dialectProvider,
                                                                DocTemplateProperties properties) {
        return new DocTemplateAnnotateService(templates, renderer, dialectProvider, properties);
    }

    /**
     * 内置的模型客户端：只有显式开启 {@code doc-template.ai.enabled} 才会创建。
     *
     * <p>默认不建，所以没配地址密钥的宿主启动不受影响；宿主自己注册了 {@code DocTemplateAiClient}
     * 时这里退让（{@code @ConditionalOnMissingBean}），连地址密钥都不用配。</p>
     */
    @Bean
    @ConditionalOnProperty(prefix = "doc-template.ai", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean(DocTemplateAiClient.class)
    public DocTemplateAiClient docTemplateAiClient(DocTemplateProperties properties) {
        return new OpenAiCompatibleClient(properties.getAi());
    }

    /**
     * 出计划的编排：拼提示词 → 调模型 → 解析 → 落笔自检 → 失败回灌重试。
     *
     * <p>客户端用 {@link ObjectProvider} 取，没有就是 {@code null} —— 这样「AI 没启用」只影响
     * {@code /ai/plan} 一个接口，快照、自检、预览、落库这些纯代码路径不跟着一起消失。</p>
     */
    @Bean
    public DocTemplateAiPlanner docTemplateAiPlanner(DocTemplateAnnotateService annotateService,
                                                     ObjectProvider<DocTemplateAiClient> client,
                                                     DocTemplateProperties properties) {
        return new DocTemplateAiPlanner(annotateService, client.getIfAvailable(), properties.getAi());
    }

    /**
     * 启动收尾：打印横幅与配置摘要，并按需初始化元数据表。
     *
     * <p>放在 {@link SmartInitializingSingleton} 里，等数据源、存储、认证这些 Bean 都就绪后只跑一次，
     * 所以摘要里的每一行都是最终生效的值，而不是配置文件的字面值。</p>
     */
    @Bean
    public SmartInitializingSingleton docTemplateStartupReport(DocTemplateProperties properties,
            DocTemplateDataSourceResolver dataSourceResolver,
            DocTemplateFileStorage fileStorage,
            ObjectProvider<DocTemplateGuard> guardProvider,
            ApplicationContext context) {
        return new DocTemplateStartupReport(properties, dataSourceResolver, fileStorage, guardProvider, context);
    }

    public record DocumentProviderRegistry(Map<String, DocumentDataProvider> providers) {
        public Object execute(String code, Map<String, Object> params) {
            DocumentDataProvider provider = providers.get(code);
            if (provider == null) throw new IllegalArgumentException("未知的数据源提供者：" + code);
            // 不能用 Map.copyOf：参数映射里 $request.xxx 取不到时值是 null，copyOf 会直接 NPE
            return provider.execute(params == null ? Map.of() : new LinkedHashMap<>(params));
        }
    }
}
