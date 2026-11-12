package io.github.violateer.doctemplate.starter.autoconfigure;

import io.github.violateer.doctemplate.starter.db.DocTemplateDialect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.util.List;
import java.util.Locale;

/**
 * 元数据表初始化：启动时检测 doc_template 系列表，缺失就按数据库类型执行建表脚本。
 *
 * <p>建表脚本本身是幂等的（表已存在就跳过），所以这段逻辑可以每次启动重复执行，也能修复只建了一半的库。
 * 关闭 {@code doc-template.initialize-schema} 时整段跳过，连数据库连接都不会碰。</p>
 */
final class DocTemplateSchemaInitializer {

    private static final Logger log = LoggerFactory.getLogger(DocTemplateSchemaInitializer.class);

    private static final List<String> REQUIRED_TABLES = List.of(
            "doc_template", "doc_template_version", "doc_template_data_block", "doc_template_generate_record",
            "doc_template_dict", "doc_template_dict_item", "doc_template_ai_import", "doc_template_ai_trace");

    private final DocTemplateProperties properties;
    private final DocTemplateDataSourceResolver dataSourceResolver;

    DocTemplateSchemaInitializer(DocTemplateProperties properties, DocTemplateDataSourceResolver dataSourceResolver) {
        this.properties = properties;
        this.dataSourceResolver = dataSourceResolver;
    }

    /** 初始化结果，供启动摘要展示；{@code product} 为 null 表示没有连过库。 */
    record Status(String product, String version, boolean created, boolean skipped) {
        /** 关闭 initialize-schema 时的状态：没连库，也不需要建表。 */
        static Status notRequested() {
            return new Status(null, null, false, true);
        }
    }

    Status initialize() {
        if (!properties.isInitializeSchema()) return Status.notRequested();
        DataSource dataSource = dataSourceResolver.moduleDataSource();
        if (dataSource == null) throw new IllegalStateException("文档模板的数据源尚未配置");
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            String product = metadata.getDatabaseProductName();
            String version = metadata.getDatabaseProductVersion();
            boolean missing = REQUIRED_TABLES.stream().anyMatch(table -> !tableExists(metadata, connection, table));
            if (!missing) return new Status(product, version, false, false);

            DocTemplateDialect dialect = dialectFor(product);
            log.info("文档模板元数据表缺失，正在自动创建：{}", REQUIRED_TABLES);
            ResourceDatabasePopulator populator =
                    new ResourceDatabasePopulator(new ClassPathResource(dialect.schemaScript()));
            // Oracle 的脚本是 PL/SQL 块，块内部自带分号，只能按斜杠切；其余方言按分号切
            populator.setSeparator(dialect.scriptSeparator());
            populator.setContinueOnError(false);
            populator.execute(dataSource);
            return new Status(product, version, true, false);
        } catch (Exception e) {
            throw new IllegalStateException("文档模板元数据表初始化失败", e);
        }
    }

    /** 建表脚本按数据库类型选；不支持的类型只在真的需要建表时才报错。 */
    private static DocTemplateDialect dialectFor(String product) {
        return DocTemplateDialect.fromProductName(product)
                .orElseThrow(() -> new IllegalStateException("自动建表不支持该数据库：" + product
                        + "。当前支持 " + DocTemplateDialect.supported()
                        + "。请自行执行对应建表脚本（classpath 下的 db/schema*.sql），"
                        + "或把 doc-template.initialize-schema 设为 false。"));
    }

    private static boolean tableExists(DatabaseMetaData metadata, Connection connection, String tableName) {
        try (var result = metadata.getTables(connection.getCatalog(), connection.getSchema(), tableName, new String[] {"TABLE"})) {
            if (result.next()) return true;
        } catch (Exception ignored) {
            // 部分驱动不支持 getSchema()，退到下面的大小写不敏感查找
        }
        for (String candidate : List.of(tableName, tableName.toUpperCase(Locale.ROOT))) {
            try (var result = metadata.getTables(connection.getCatalog(), null, candidate, new String[] {"TABLE"})) {
                if (result.next()) return true;
            } catch (Exception ignored) {
                // 继续尝试下一个候选
            }
        }
        return false;
    }
}
