package io.github.violateer.doctemplate.starter.service;

/**
 * SQL 数据块执行失败。
 *
 * <p>数据库的原始报错里带着表名、列名和 SQL 片段，直接回给调用方等于把「盲注」变成「显错注」。
 * 所以这个异常对外的消息是固定的脱敏文案，真实原因只放在 {@code cause} 里、只写服务端日志。</p>
 */
public class DocTemplateSqlException extends RuntimeException {

    public DocTemplateSqlException(String message, Throwable cause) {
        super(message, cause);
    }
}
