package io.github.violateer.doctemplate.starter.autoconfigure;

import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 解析文档模板模块该用哪个数据源。
 *
 * <p><b>默认零数据源</b>：模块自己不建连接池，直接用宿主应用的 {@code DataSource}。
 * 只有显式配置了 {@code doc-template.datasource.url}（模板元数据要落到另一个库）时，
 * 才会额外建一个独立的 Hikari 连接池，并在容器关闭时释放。</p>
 *
 * <p>宿主是单数据源时按 {@code dataSource} 取；多数据源（dynamic-datasource 等）时
 * 依次尝试 {@code dynamicDataSource}、{@code masterDataSource} 等常见命名，
 * 再退到 {@code @Primary}，最后才在唯一候选上兜底。</p>
 */
public class DocTemplateDataSourceResolver implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DocTemplateDataSourceResolver.class);

    /** 宿主主库的候选 Bean 名，按顺序优先匹配。 */
    private static final List<String> HOST_CANDIDATES = List.of(
            "dataSource", "dynamicDataSource", "masterDataSource", "primaryDataSource",
            "master", "defaultDataSource");

    /** dataSource 写这些值表示「宿主主库」，不按 Bean 名解析。 */
    private static final Set<String> HOST_ALIASES = Set.of("primary", "default", "main", "master", "host");

    /** 写这些值表示「模块元数据所在的库」：有独立库用独立库，否则等于宿主主库。 */
    private static final Set<String> MODULE_ALIASES = Set.of("doctemplate", "doc-template", "meta", "own", "module");

    private final ApplicationContext context;
    private final DocTemplateProperties properties;
    private HikariDataSource dedicated;

    public DocTemplateDataSourceResolver(ApplicationContext context, DocTemplateProperties properties) {
        this.context = context;
        this.properties = properties;
    }

    /** 是否显式配置了独立的模板库（{@code doc-template.datasource.url}）。 */
    public boolean hasDedicatedDataSource() {
        return StringUtils.hasText(properties.getDatasource().getUrl());
    }

    /** 模块元数据（doc_template 系列表）所在的库：有独立库用独立库，否则用宿主主库。 */
    public DataSource moduleDataSource() {
        return hasDedicatedDataSource() ? dedicatedDataSource() : hostPrimaryDataSource();
    }

    /** 模块当前使用的数据源描述，用于日志与错误提示。 */
    public String moduleSourceLabel() {
        if (hasDedicatedDataSource()) return "doc-template.datasource(" + properties.getDatasource().getUrl() + ")";
        String bean = hostBeanName();
        return bean == null ? "宿主数据源(未找到)" : "宿主数据源(" + bean + ")";
    }

    /** 宿主应用的主数据源。 */
    public DataSource hostPrimaryDataSource() {
        String bean = hostBeanName();
        if (bean == null) {
            throw new IllegalStateException("没有找到宿主应用的数据源（DataSource Bean）。"
                    + "请给宿主配置 spring.datasource，或显式配置 doc-template.datasource.url 让模块连独立的库。");
        }
        return context.getBean(bean, DataSource.class);
    }

    /** 按数据块里写的 dataSource 值解析：留空/primary → 宿主主库，docTemplate → 模块库，其他按 Bean 名。 */
    public DataSource resolve(String name) {
        String key = name == null ? "" : name.trim();
        String lower = key.toLowerCase(Locale.ROOT);
        if (key.isEmpty() || HOST_ALIASES.contains(lower)) return hostPrimaryDataSource();
        if (MODULE_ALIASES.contains(lower)) return moduleDataSource();
        if (!context.containsBean(key)) {
            throw new IllegalArgumentException("找不到数据源 Bean：" + key + "，可用：" + describe());
        }
        return context.getBean(key, DataSource.class);
    }

    /** 可供数据块选择的数据源清单（含别名），用于接口返回与错误提示。 */
    public List<Entry> entries() {
        List<Entry> items = new ArrayList<>();
        items.add(new Entry("primary", "宿主主数据源" + beanSuffix(hostBeanName()) + "（推荐，留空即可）", true));
        String own = hasDedicatedDataSource() ? "doc-template.datasource 指向的独立库" : "未单独配置，与 primary 相同";
        items.add(new Entry("docTemplate", "模板元数据所在的库：" + own, false));
        for (String name : hostBeanNames()) {
            if (name.equals(hostBeanName())) continue;
            items.add(new Entry(name, "Spring 容器里的 DataSource Bean，直接写 Bean 名即可", true));
        }
        return items;
    }

    private String describe() {
        StringBuilder text = new StringBuilder("primary（宿主主库）、docTemplate（模板库）");
        for (String name : hostBeanNames()) text.append("、").append(name);
        return text.toString();
    }

    private String beanSuffix(String bean) {
        return bean == null ? "" : "（" + bean + "）";
    }

    // ------------------------------------------------------------------ 内部解析

    /** 宿主主库的 Bean 名；找不到返回 null。 */
    private String hostBeanName() {
        List<String> names = hostBeanNames();
        if (names.isEmpty()) return null;
        for (String candidate : HOST_CANDIDATES) {
            if (names.contains(candidate)) return candidate;
        }
        String primary = primaryBean(names);
        if (primary != null) return primary;
        if (names.size() == 1) return names.get(0);
        for (String name : names) {
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.contains("master") || lower.contains("primary") || lower.contains("dynamic")) return name;
        }
        // 多个候选且没有主次线索，取注册顺序第一个，并提示用户显式指定
        log.warn("宿主存在多个 DataSource Bean（{}），模块默认使用 {}；如需指定请配置 doc-template.datasource.url",
                String.join("、", names), names.get(0));
        return names.get(0);
    }

    private String primaryBean(List<String> names) {
        if (!(context.getAutowireCapableBeanFactory() instanceof ConfigurableListableBeanFactory factory)) return null;
        for (String name : names) {
            try {
                if (factory.getBeanDefinition(name).isPrimary()) return name;
            } catch (Exception ignored) {
                // 非 BeanDefinition 注册的 DataSource（如手动注册的单例）跳过
            }
        }
        return null;
    }

    private List<String> hostBeanNames() {
        return new ArrayList<>(new LinkedHashSet<>(List.of(context.getBeanNamesForType(DataSource.class))));
    }

    private synchronized DataSource dedicatedDataSource() {
        if (dedicated != null && !dedicated.isClosed()) return dedicated;
        var cfg = properties.getDatasource();
        HikariDataSource pool = new HikariDataSource();
        pool.setJdbcUrl(cfg.getUrl());
        // 留空时由 Hikari 按 jdbcUrl 推断驱动，避免把 MySQL 驱动写死
        if (StringUtils.hasText(cfg.getDriverClassName())) pool.setDriverClassName(cfg.getDriverClassName());
        pool.setUsername(cfg.getUsername());
        pool.setPassword(cfg.getPassword());
        pool.setMaximumPoolSize(Math.max(1, cfg.getMaximumPoolSize()));
        pool.setConnectionTimeout(Math.max(1000, cfg.getConnectionTimeout()));
        pool.setPoolName("doc-template-pool");
        dedicated = pool;
        return pool;
    }

    @Override
    public synchronized void close() {
        if (dedicated != null && !dedicated.isClosed()) dedicated.close();
    }

    /** 数据源清单条目。 */
    public record Entry(String code, String description, boolean bean) {
    }
}
