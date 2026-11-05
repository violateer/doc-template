package io.github.violateer.doctemplate.starter.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * 懒解析的数据库方言持有者。
 *
 * <p>为什么不在启动时就认死：不支持某个库时，只有「真的要用到这个库」才该报错。
 * 宿主把 {@code doc-template.initialize-schema} 关掉、自己执行过建表脚本的场景下，
 * 启动阶段不该因为识别不出数据库而失败。所以第一次拼 SQL 时才连一次库读产品名，
 * 之后一直用缓存的那份。</p>
 *
 * <p><b>内部实现，不属于对外契约。</b></p>
 */
public final class DocTemplateDialectProvider {

    private static final Logger log = LoggerFactory.getLogger(DocTemplateDialectProvider.class);

    private final DataSource dataSource;
    private volatile DocTemplateDialect dialect;

    public DocTemplateDialectProvider(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** 模块所用数据源的方言；识别不出来直接抛，不做静默降级。 */
    public DocTemplateDialect current() {
        DocTemplateDialect resolved = dialect;
        if (resolved != null) return resolved;
        synchronized (this) {
            if (dialect == null) dialect = resolve();
            return dialect;
        }
    }

    private DocTemplateDialect resolve() {
        String product;
        try (Connection connection = dataSource.getConnection()) {
            product = connection.getMetaData().getDatabaseProductName();
        } catch (SQLException e) {
            throw new IllegalStateException("无法连接数据库，取不到数据库类型", e);
        }
        DocTemplateDialect resolved = DocTemplateDialect.fromProductName(product)
                .orElseThrow(() -> new IllegalStateException("不支持该数据库：" + product
                        + "。当前支持 " + DocTemplateDialect.supported()
                        + "；其他数据库请自行执行建表脚本，并把 doc-template.initialize-schema 设为 false。"));
        log.debug("文档模板模块使用 {} 方言", resolved.displayName());
        return resolved;
    }
}
