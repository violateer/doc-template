package io.github.violateer.doctemplate.starter.repository;

import io.github.violateer.doctemplate.starter.db.DocTemplateDialect;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 冒烟测试用的测试库清单，全部从环境变量读。
 *
 * <p>测试会真的建表、写数据、删表，所以必须指向一个**独立的空库**（例如 {@code doc_template_test}），
 * 不要指向日常使用的库。</p>
 *
 * <p>一个变量都不配就一条测试都不跑 —— CI 不需要任何容器或镜像：</p>
 * <pre>
 * DOC_TEST_MYSQL_URL=jdbc:mysql://127.0.0.1:3306/doc_template_test?useSSL=false&amp;serverTimezone=Asia/Shanghai
 * DOC_TEST_MYSQL_USERNAME=root        # 可省略，默认 root
 * DOC_TEST_MYSQL_PASSWORD=            # 可省略，默认空
 * DOC_TEST_PG_URL=jdbc:postgresql://127.0.0.1:5432/doc_template_test
 * DOC_TEST_PG_USERNAME=postgres       # 可省略，默认 postgres
 * DOC_TEST_PG_PASSWORD=               # 可省略，默认空
 * DOC_TEST_SQLSERVER_URL=jdbc:sqlserver://127.0.0.1:1433;databaseName=doc_template_test;encrypt=false
 * DOC_TEST_SQLSERVER_USERNAME=sa      # 可省略，默认 sa
 * DOC_TEST_SQLSERVER_PASSWORD=        # 可省略，默认空
 * DOC_TEST_ORACLE_URL=jdbc:oracle:thin:@127.0.0.1:1521/FREEPDB1
 * DOC_TEST_ORACLE_USERNAME=doc_template   # Oracle 的用户名就是 schema，没有默认值，必须显式配置
 * DOC_TEST_ORACLE_PASSWORD=
 * </pre>
 */
final class TestDatabases {

    /** 删表顺序：先子表后主表（表上没建外键，顺序只是为了读起来顺）。 */
    private static final List<String> TABLES = List.of(
            "doc_template_dict_item", "doc_template_dict", "doc_template_generate_record",
            "doc_template_data_block", "doc_template_version", "doc_template");

    private TestDatabases() {
    }

    /** 一个配置好的测试库。 */
    record Target(DocTemplateDialect dialect, DataSource dataSource) {
        String label() {
            return dialect.displayName();
        }
    }

    /** 当前配置了连接串的测试库；没配的跳过。 */
    static List<Target> configured() {
        List<Target> targets = new ArrayList<>(4);
        target(DocTemplateDialect.MYSQL, "DOC_TEST_MYSQL", "root").ifPresent(targets::add);
        target(DocTemplateDialect.POSTGRESQL, "DOC_TEST_PG", "postgres").ifPresent(targets::add);
        target(DocTemplateDialect.SQLSERVER, "DOC_TEST_SQLSERVER", "sa").ifPresent(targets::add);
        // Oracle 的用户名同时是建表所在的 schema，填错就会建到别人的 schema 里，所以不给默认值
        target(DocTemplateDialect.ORACLE, "DOC_TEST_ORACLE", null).ifPresent(targets::add);
        return targets;
    }

    /** 建表脚本要建的六张表。 */
    static List<String> tables() {
        return TABLES;
    }

    /**
     * 按方言准备好的建表脚本执行器：Oracle 的脚本是 PL/SQL 块，分隔符要用斜杠，其余用分号。
     */
    static ResourceDatabasePopulator populator(Target target) {
        ResourceDatabasePopulator populator =
                new ResourceDatabasePopulator(new ClassPathResource(target.dialect().schemaScript()));
        populator.setSeparator(target.dialect().scriptSeparator());
        return populator;
    }

    /**
     * 删掉六张表；测试开始与结束各调一次，保证可重复执行。
     *
     * <p>MySQL / MariaDB、PostgreSQL、SQL Server 2016+ 都认 {@code DROP TABLE IF EXISTS}；
     * Oracle 没有这个写法，先查 {@code user_tables} 再删。</p>
     */
    static void dropTables(Target target) {
        JdbcTemplate jdbc = new JdbcTemplate(target.dataSource());
        boolean oracle = target.dialect() == DocTemplateDialect.ORACLE;
        for (String table : TABLES) {
            if (!oracle) {
                jdbc.execute("DROP TABLE IF EXISTS " + table);
            } else if (oracleTableExists(jdbc, table)) {
                jdbc.execute("DROP TABLE " + table);
            }
        }
    }

    private static boolean oracleTableExists(JdbcTemplate jdbc, String table) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM user_tables WHERE table_name = ?",
                Integer.class, table.toUpperCase(Locale.ROOT));
        return count != null && count > 0;
    }

    private static Optional<Target> target(DocTemplateDialect dialect, String prefix, String defaultUsername) {
        String url = env(prefix + "_URL");
        if (url == null) return Optional.empty();
        String username = env(prefix + "_USERNAME");
        String password = env(prefix + "_PASSWORD");
        DriverManagerDataSource dataSource = new DriverManagerDataSource(url,
                username == null ? defaultUsername : username,
                password == null ? "" : password);
        return Optional.of(new Target(dialect, dataSource));
    }

    private static String env(String name) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** 表名/列名按数据库返回的大小写归一，比对时不受 MySQL 大小写与 PostgreSQL 折叠影响。 */
    static String normalize(String identifier) {
        return identifier.toLowerCase(Locale.ROOT);
    }
}
