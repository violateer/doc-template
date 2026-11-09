package io.github.violateer.doctemplate.starter.controller;

import io.github.violateer.doctemplate.starter.autoconfigure.DocTemplateAutoConfiguration.DocumentProviderRegistry;
import io.github.violateer.doctemplate.starter.autoconfigure.DocTemplateDataSourceResolver;
import io.github.violateer.doctemplate.starter.convert.DocTemplateConverterRegistry;
import io.github.violateer.doctemplate.starter.support.DocTemplateVersion;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/doc-template/system")
public class DocTemplateSystemController {
    private final DocumentProviderRegistry registry;
    private final DocTemplateDataSourceResolver dataSourceResolver;
    private final DocTemplateConverterRegistry converters;

    public DocTemplateSystemController(DocumentProviderRegistry registry, DocTemplateDataSourceResolver dataSourceResolver,
                                       DocTemplateConverterRegistry converters) {
        this.registry = registry;
        this.dataSourceResolver = dataSourceResolver;
        this.converters = converters;
    }

    @GetMapping("/providers")
    public List<ProviderItem> providers() {
        return registry.providers().entrySet().stream().map(e -> new ProviderItem(e.getKey(), e.getValue().getClass().getSimpleName())).toList();
    }

    /**
     * SQL 数据块可用的数据源，供管理界面提示 dataSource 字段可以填什么。
     * 默认值 primary 就是宿主应用的数据源，模块本身不建连接池。
     */
    @GetMapping("/data-sources")
    public List<DataSourceItem> dataSources() {
        return dataSourceResolver.entries().stream()
                .map(e -> new DataSourceItem(e.code(), e.description(), e.bean()))
                .toList();
    }

    /**
     * 可用的字段值转换器编码（内置 + 宿主注册），供管理页做下拉选项。
     * 只给编码，说明文案由前端按编码映射（宿主自定义的显示原始编码）。
     */
    @GetMapping("/converters")
    public List<String> converters() {
        return converters.codes();
    }

    /**
     * 当前运行的 Starter 版本号，管理页左下角用它显示「对接的是哪个版本」。
     * 读不到版本时 {@code version} 为 {@code null}，前端只显示「Starter」。
     */
    @GetMapping("/version")
    public VersionItem version() {
        return new VersionItem(DocTemplateVersion.current());
    }

    public record ProviderItem(String code, String implementation) {
    }

    public record DataSourceItem(String code, String description, boolean bean) {
    }

    public record VersionItem(String version) {
    }
}
