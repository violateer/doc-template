package io.github.violateer.doctemplate.starter.repository;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 多份建表脚本的列集合比对。
 *
 * <p>每加一个库就多一份 DDL，加字段时漏改一份只有那个库的宿主会炸。这里用
 * {@code DatabaseMetaData.getColumns} 读出各脚本建出来的列名，集合必须完全一致。</p>
 *
 * <p>需要至少两个测试库同时配置（见 {@link TestDatabases}）；只配了一个就跳过。</p>
 */
class DocTemplateSchemaDriftTest {

    @Test
    void columnSetsAreIdenticalAcrossScripts() throws Exception {
        List<TestDatabases.Target> targets = TestDatabases.configured();
        assumeTrue(targets.size() >= 2,
                "至少配置两个测试库（见 TestDatabases 里的 DOC_TEST_* 环境变量）才能比对建表脚本");

        Map<String, Map<String, Set<String>>> byDialect = new LinkedHashMap<>();
        for (TestDatabases.Target target : targets) {
            byDialect.put(target.label(), readColumns(target));
        }

        Map.Entry<String, Map<String, Set<String>>> reference = byDialect.entrySet().iterator().next();
        List<String> differences = new ArrayList<>();
        for (Map.Entry<String, Map<String, Set<String>>> other : byDialect.entrySet()) {
            if (other.getKey().equals(reference.getKey())) continue;
            for (String table : TestDatabases.tables()) {
                Set<String> expected = reference.getValue().get(table);
                Set<String> actual = other.getValue().get(table);
                Set<String> missing = new TreeSet<>(expected);
                missing.removeAll(actual);
                Set<String> extra = new TreeSet<>(actual);
                extra.removeAll(expected);
                if (!missing.isEmpty() || !extra.isEmpty()) {
                    differences.add("表 " + table + "：" + reference.getKey() + " 与 " + other.getKey()
                            + " 不一致，缺 " + missing + "，多 " + extra);
                }
            }
        }
        assertTrue(differences.isEmpty(), "建表脚本的列集合必须一致：\n" + String.join("\n", differences));
    }

    /** 在测试库里按脚本建表，读出六张表的列名，再把表删掉。 */
    private static Map<String, Set<String>> readColumns(TestDatabases.Target target) throws SQLException {
        TestDatabases.dropTables(target);
        try {
            TestDatabases.populator(target).execute(target.dataSource());
            Map<String, Set<String>> columns = new LinkedHashMap<>();
            try (Connection connection = target.dataSource().getConnection()) {
                for (String table : TestDatabases.tables()) {
                    Set<String> names = columnsOf(connection, table);
                    assertFalse(names.isEmpty(), target.label() + " 的 " + target.dialect().schemaScript()
                            + " 没有建出表 " + table);
                    columns.put(table, names);
                }
            }
            return columns;
        } finally {
            TestDatabases.dropTables(target);
        }
    }

    /**
     * 列名统一转小写：MySQL 与 PostgreSQL 对未加引号的标识符大小写处理不同。
     *
     * <p>表名要按原样和全大写各试一次：Oracle 把未加引号的标识符折成大写，
     * 拿小写表名去问 {@code getColumns} 是查不到的。</p>
     */
    private static Set<String> columnsOf(Connection connection, String table) throws SQLException {
        for (String candidate : List.of(table, table.toUpperCase(Locale.ROOT))) {
            String[][] scopes = {{connection.getCatalog(), connection.getSchema()}, {null, null}};
            for (String[] scope : scopes) {
                Set<String> columns = new TreeSet<>();
                try (ResultSet rs = connection.getMetaData().getColumns(scope[0], scope[1], candidate, null)) {
                    while (rs.next()) {
                        columns.add(TestDatabases.normalize(rs.getString("COLUMN_NAME")));
                    }
                }
                if (!columns.isEmpty()) return columns;
            }
        }
        return Set.of();
    }
}
