package io.github.violateer.doctemplate.starter.autoconfigure;

import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * 指向模块所用数据源的 JdbcTemplate。
 *
 * <p>连接池的生命周期由 {@link DocTemplateDataSourceResolver} 负责，这里不再持有所有权，
 * 避免误关宿主应用的连接池。</p>
 */
public class DocTemplateJdbcTemplate extends JdbcTemplate {
    public DocTemplateJdbcTemplate(DataSource dataSource) {
        super(dataSource);
    }
}
