package io.github.violateer.doctemplate.starter.support;

import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.Select;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQL 数据块的语句校验。
 *
 * <p>数据块里的 SQL 是「配置驱动的任意查询」，执行前必须确认它只读。这里做三层检查：</p>
 * <ol>
 *   <li><b>词法层</b>：剥离注释与字符串字面量后，首关键字必须是 {@code select} / {@code with}，
 *       且代码区不出现写操作、文件函数、DDL 关键字；字符串里的 {@code 'a;b'} 不再被误判成分号。
 *       <b>例外</b>：MySQL 的可执行注释 {@code /*! ... *}{@code /} 不按注释剥离——服务器会执行里面的内容，
 *       所以它按代码参与关键字与分号检查，否则 {@code select 1 /*!50000 into outfile '...' *}{@code /}
 *       就能把 {@code into outfile} 藏进注释里绕过黑名单。</li>
 *   <li><b>语法层</b>：用 JSqlParser 解析，只有解析结果是 {@code Select} 才放行。这一步能拦住
 *       {@code with x as (select 1) delete from t} 这类 CTE 写操作——词法层只看到 {@code with}，
 *       分不出后面跟的是 SELECT 还是 DELETE。</li>
 *   <li><b>执行层</b>：{@code DocTemplateDataBlockExecutor} 会把连接设为只读。</li>
 * </ol>
 *
 * <p><b>这不是安全边界，只是纵深防御的一层。</b>真正可靠的隔离是给模块配只读数据库账号；
 * 校验器能拦住绝大多数误配置和低级绕过，但不可能覆盖所有数据库方言。</p>
 */
public final class DocTemplateSqlValidator {

    /** 单条 SQL 的长度上限，避免超长语句把解析器拖死。 */
    private static final int MAX_LENGTH = 20000;

    private static final Pattern FIRST_KEYWORD = Pattern.compile("^[\\s(]*(select|with)\\b");

    /**
     * 只读场景下不该出现的关键字。匹配对象是「剥离注释与字面量后的代码区」，
     * 所以列名 {@code create_time}、字面量 {@code 'delete'} 都不会误伤（{@code _} 是单词字符，{@code \b} 不成立）。
     *
     * <p>分成三类：写操作/DDL、读写服务器文件的函数（各数据库写法不同）、会锁行或拖慢响应的写法。
     * 这一层跑在 JSqlParser 解析之前，是「先粗筛再精解析」的第一道网，用来拦住解析器可能放过的
     * 危险写法（{@code for update}、{@code into outfile} 都是合法语法）。</p>
     */
    private static final Pattern FORBIDDEN = Pattern.compile(
            "\\b(insert|update|delete|merge|upsert|drop|alter|truncate|create|grant|revoke|rename"
                    + "|call|exec|execute|copy|runscript|link_schema|load_extension|xp_cmdshell|sys_exec|sys_eval"
                    + "|into\\s+outfile|into\\s+dumpfile|load_file|load\\s+data"
                    + "|pg_read_file|pg_read_binary_file|pg_ls_dir|pg_sleep|lo_import|lo_export|dblink"
                    + "|file_read|file_write|csvread|csvwrite|utl_http|utl_file|utl_inaddr"
                    + "|sleep|benchmark|for\\s+update|for\\s+share)\\b",
            Pattern.CASE_INSENSITIVE);

    private DocTemplateSqlValidator() {
    }

    /**
     * 校验并返回规范化后的 SQL（去掉结尾分号）。
     *
     * @throws IllegalArgumentException 语句不是单条只读查询时抛出，消息可直接展示给使用者
     */
    public static String validate(String rawSql) {
        String sql = rawSql == null ? "" : rawSql.trim();
        while (sql.endsWith(";")) {
            sql = sql.substring(0, sql.length() - 1).trim();
        }
        if (sql.isEmpty()) {
            throw new IllegalArgumentException("SQL 数据源缺少 sql 配置");
        }
        if (sql.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("SQL 过长（超过 " + MAX_LENGTH + " 字符），请简化查询或改用 Java Provider 数据源");
        }

        Masked masked = mask(sql);
        String code = masked.code().toLowerCase(Locale.ROOT).trim();

        // 允许「代码区末尾」有一个分号：`select 1; -- 说明` 这种写法在客户端里很常见，
        // 注释被剥离后分号就落在末尾了。去掉末尾这个分号再查，中间还有分号才算多语句。
        String body = code.endsWith(";") ? code.substring(0, code.length() - 1) : code;
        if (body.indexOf(';') >= 0) {
            throw new IllegalArgumentException("SQL 数据源只允许单条语句，不能包含分号");
        }
        if (!FIRST_KEYWORD.matcher(code).find()) {
            throw new IllegalArgumentException("SQL 数据源只允许查询语句（select / with 开头）");
        }
        Matcher forbidden = FORBIDDEN.matcher(code);
        if (forbidden.find()) {
            throw new IllegalArgumentException("SQL 数据源只允许只读查询，检测到不允许的关键字："
                    + forbidden.group(1).toUpperCase(Locale.ROOT));
        }

        Statement statement;
        try {
            statement = CCJSqlParserUtil.parse(masked.parseable());
        } catch (Exception parseError) {
            throw new IllegalArgumentException("SQL 无法解析为单条查询（" + shorten(parseError.getMessage())
                    + "）。请改写为标准 SQL，或改用 Java Provider 数据源");
        }
        if (!(statement instanceof Select)) {
            throw new IllegalArgumentException("SQL 数据源只允许只读查询，当前语句类型是 "
                    + statement.getClass().getSimpleName() + "；只支持 SELECT 与 WITH ... SELECT");
        }
        return sql;
    }

    /**
     * 一次扫描产出两份文本：
     * <ul>
     *   <li>{@code code} —— 注释与字符串字面量被替换成空格，用于关键字与分号检查。
     *       两个例外：MySQL 可执行注释 {@code /*! ... *}{@code /} 的内容留在 code 里（服务器会执行它），
     *       引号内的内容照常剥离。</li>
     *   <li>{@code parseable} —— 保留原文，只做两处归一：{@code :name} 命名参数换成常量 {@code 1}
     *       （JSqlParser 不认识命名参数，而语句类型判断不依赖参数值）、{@code #} 行注释换成 {@code --}
     *       （解析器不认 MySQL 的 {@code #}）。</li>
     * </ul>
     */
    private static Masked mask(String sql) {
        StringBuilder code = new StringBuilder(sql.length());
        StringBuilder parseable = new StringBuilder(sql.length());
        int index = 0;
        int length = sql.length();
        char quote = 0;
        boolean lineComment = false;
        boolean blockComment = false;

        while (index < length) {
            char current = sql.charAt(index);
            char next = index + 1 < length ? sql.charAt(index + 1) : 0;

            if (lineComment) {
                parseable.append(current);
                if (current == '\n') {
                    lineComment = false;
                    code.append(' ');
                }
                index++;
                continue;
            }
            if (blockComment) {
                parseable.append(current);
                if (current == '*' && next == '/') {
                    parseable.append('/');
                    index += 2;
                    blockComment = false;
                    code.append(' ');
                    continue;
                }
                index++;
                continue;
            }
            if (quote != 0) {
                parseable.append(current);
                if (current == '\\' && quote == '\'' && index + 1 < length) {
                    parseable.append(sql.charAt(index + 1));
                    index += 2;
                    continue;
                }
                if (current == quote) {
                    if (next == quote) {
                        parseable.append(next);
                        index += 2;
                        continue;
                    }
                    quote = 0;
                    code.append(' ');
                }
                index++;
                continue;
            }

            if (current == '\'' || current == '"' || current == '`') {
                quote = current;
                parseable.append(current);
                index++;
                continue;
            }
            if (current == '-' && next == '-') {
                lineComment = true;
                parseable.append("--");
                index += 2;
                continue;
            }
            // MySQL 的 # 注释；PostgreSQL 的 #> / #>> 是 JSON 操作符，不能当注释
            if (current == '#' && next != '>' && next != '-') {
                lineComment = true;
                // 解析器只认 -- 注释，这里换成 -- 再交给它，否则带 # 注释的合法查询会被判成语法错误
                parseable.append("--");
                index++;
                continue;
            }
            if (current == '/' && next == '*') {
                // MySQL 的可执行注释 /*!50000 ... */ 里装的是服务器真正会执行的代码，不能当注释剥掉：
                // 否则 select 1 /*!50000 into outfile '/x' */ 就把关键字藏进「注释」绕过了黑名单。
                // 这里只把 /* 这两个字符当成代码占位，后面的内容照常扫描（结尾的 */ 留在代码区，
                // 匹配不到任何关键字）；parseable 保留原文，JSqlParser 仍按注释处理，不影响语句类型判断。
                if (index + 2 < length && sql.charAt(index + 2) == '!') {
                    code.append("  ");
                    parseable.append("/*");
                    index += 2;
                    continue;
                }
                blockComment = true;
                parseable.append("/*");
                index += 2;
                continue;
            }
            // PostgreSQL 的 :: 类型转换，不能当成命名参数的冒号
            if (current == ':' && next == ':') {
                parseable.append("::");
                code.append("::");
                index += 2;
                continue;
            }
            if (current == ':' && (Character.isLetter(next) || next == '_')) {
                int end = index + 1;
                while (end < length && (Character.isLetterOrDigit(sql.charAt(end)) || sql.charAt(end) == '_')) {
                    end++;
                }
                parseable.append('1');
                code.append('1');
                index = end;
                continue;
            }
            code.append(current);
            parseable.append(current);
            index++;
        }
        return new Masked(code.toString(), parseable.toString());
    }

    private static String shorten(String text) {
        String flat = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        return flat.length() <= 160 ? flat : flat.substring(0, 160) + "…";
    }

    private record Masked(String code, String parseable) {
    }
}
