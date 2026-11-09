package io.github.violateer.doctemplate.starter.db;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 方言识别与三处差异的纯逻辑校验：不连库，所以 CI 上一定跑得到。
 *
 * <p>真库上的建表与读写由 {@code DocTemplateSchemaSmokeTest} 覆盖，但那要配测试库连接串；
 * 这里只保证「名字认得对、分页拼得对、脚本文件确实在 jar 里」。</p>
 */
class DocTemplateDialectTest {

    @Test
    void productNameIsRecognized() {
        assertEquals(Optional.of(DocTemplateDialect.MYSQL), DocTemplateDialect.fromProductName("MySQL"));
        assertEquals(Optional.of(DocTemplateDialect.MYSQL), DocTemplateDialect.fromProductName("MariaDB"));
        assertEquals(Optional.of(DocTemplateDialect.POSTGRESQL), DocTemplateDialect.fromProductName("PostgreSQL"));
        assertEquals(Optional.of(DocTemplateDialect.SQLSERVER),
                DocTemplateDialect.fromProductName("Microsoft SQL Server"));
        assertEquals(Optional.of(DocTemplateDialect.ORACLE), DocTemplateDialect.fromProductName("Oracle"));
        assertEquals(Optional.empty(), DocTemplateDialect.fromProductName("H2"));
        assertEquals(Optional.empty(), DocTemplateDialect.fromProductName(null));
    }

    @Test
    void pagingFollowsDialect() {
        String sql = "SELECT id FROM doc_template ORDER BY id DESC";
        assertEquals(sql + " LIMIT 10 OFFSET 20", DocTemplateDialect.MYSQL.page(sql, 20, 10));
        assertEquals(sql + " LIMIT 10 OFFSET 20", DocTemplateDialect.POSTGRESQL.page(sql, 20, 10));
        String fetch = sql + " OFFSET 20 ROWS FETCH NEXT 10 ROWS ONLY";
        assertEquals(fetch, DocTemplateDialect.SQLSERVER.page(sql, 20, 10));
        assertEquals(fetch, DocTemplateDialect.ORACLE.page(sql, 20, 10));
    }

    /** Oracle 的脚本是 PL/SQL 块，按分号切会被切碎，只能用斜杠。 */
    @Test
    void oracleScriptIsSplitBySlash() {
        for (DocTemplateDialect dialect : DocTemplateDialect.values()) {
            assertEquals(dialect == DocTemplateDialect.ORACLE ? "/" : ";", dialect.scriptSeparator(),
                    dialect.displayName() + " 的脚本分隔符");
        }
    }

    @Test
    void everyDialectHasItsSchemaScript() {
        for (DocTemplateDialect dialect : DocTemplateDialect.values()) {
            assertTrue(new ClassPathResource(dialect.schemaScript()).exists(),
                    dialect.displayName() + " 的建表脚本不在 classpath 上：" + dialect.schemaScript());
        }
    }
}
