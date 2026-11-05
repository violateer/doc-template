package io.github.violateer.doctemplate.starter.repository;

import io.github.violateer.doctemplate.starter.db.DocTemplateDialectProvider;
import io.github.violateer.doctemplate.starter.entity.DocTemplateGenerateRecordEntity;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.util.StringUtils;

import java.sql.ResultSet;
import java.sql.Types;
import java.util.Collection;
import java.util.List;

/** {@code doc_template_generate_record} 表的读写。 */
public class DocTemplateGenerateRecordRepository {

    private static final String COLUMNS = "id, template_id, template_version_id, export_type, request_params,"
            + " result_file_url, status, error_message, create_by, create_time";

    private static final RowMapper<DocTemplateGenerateRecordEntity> ROW_MAPPER = (ResultSet rs, int rowNum) -> {
        DocTemplateGenerateRecordEntity e = new DocTemplateGenerateRecordEntity();
        e.setId(rs.getLong("id"));
        e.setTemplateId(rs.getLong("template_id"));
        e.setTemplateVersionId(DocTemplateRepository.nullableLong(rs, "template_version_id"));
        e.setExportType(rs.getString("export_type"));
        e.setRequestParams(rs.getString("request_params"));
        e.setResultFileUrl(rs.getString("result_file_url"));
        e.setStatus(rs.getString("status"));
        e.setErrorMessage(rs.getString("error_message"));
        e.setCreateBy(rs.getString("create_by"));
        e.setCreateTime(DocTemplateRepository.nullableTime(rs, "create_time"));
        return e;
    };

    private final NamedParameterJdbcTemplate jdbc;
    private final DocTemplateDialectProvider dialect;

    public DocTemplateGenerateRecordRepository(@Qualifier("docTemplateJdbcTemplate") JdbcTemplate jdbc,
                                               DocTemplateDialectProvider dialect) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbc);
        this.dialect = dialect;
    }

    public List<DocTemplateGenerateRecordEntity> findAll() {
        return jdbc.query("SELECT " + COLUMNS + " FROM doc_template_generate_record ORDER BY id DESC", ROW_MAPPER);
    }

    /**
     * 分页查询，支持状态与关键字过滤。
     *
     * <p>关键字匹配记录号（纯数字时等值比对 {@code id}）、生成人、失败说明，以及 {@code templateIds} 里的模板
     * （模板名称不是记录表的列，由调用方先按名称查出 id 再传进来，避免拼接 SQL）。</p>
     */
    public List<DocTemplateGenerateRecordEntity> findPage(String keyword, String status, Collection<Long> templateIds,
                                                          int offset, int limit) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String sql = "SELECT " + COLUMNS + " FROM doc_template_generate_record"
                + where(keyword, status, templateIds, params) + " ORDER BY id DESC";
        return jdbc.query(dialect.current().page(sql, offset, limit), params, ROW_MAPPER);
    }

    public long count(String keyword, String status, Collection<Long> templateIds) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String where = where(keyword, status, templateIds, params);
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM doc_template_generate_record" + where, params, Long.class);
        return total == null ? 0L : total;
    }

    public List<DocTemplateGenerateRecordEntity> findByTemplateId(long templateId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM doc_template_generate_record WHERE template_id = :templateId",
                new MapSqlParameterSource("templateId", templateId), ROW_MAPPER);
    }

    public DocTemplateGenerateRecordEntity findById(long id) {
        List<DocTemplateGenerateRecordEntity> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM doc_template_generate_record WHERE id = :id",
                new MapSqlParameterSource("id", id), ROW_MAPPER);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void insert(DocTemplateGenerateRecordEntity e) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("templateId", e.getTemplateId())
                .addValue("templateVersionId", e.getTemplateVersionId(), Types.BIGINT)
                .addValue("exportType", e.getExportType())
                .addValue("requestParams", e.getRequestParams(), Types.VARCHAR)
                .addValue("resultFileUrl", e.getResultFileUrl(), Types.VARCHAR)
                .addValue("status", e.getStatus())
                .addValue("errorMessage", e.getErrorMessage(), Types.VARCHAR)
                .addValue("createBy", e.getCreateBy(), Types.VARCHAR)
                .addValue("createTime", e.getCreateTime(), Types.TIMESTAMP);
        KeyHolder keys = new GeneratedKeyHolder();
        dialect.current().insert(jdbc, "INSERT INTO doc_template_generate_record"
                + " (template_id, template_version_id, export_type, request_params, result_file_url, status,"
                + " error_message, create_by, create_time)"
                + " VALUES (:templateId, :templateVersionId, :exportType, :requestParams, :resultFileUrl, :status,"
                + " :errorMessage, :createBy, :createTime)", params, keys);
        Long id = JdbcKeys.generatedId(keys);
        if (id != null) e.setId(id);
    }

    public void deleteById(long id) {
        jdbc.update("DELETE FROM doc_template_generate_record WHERE id = :id", new MapSqlParameterSource("id", id));
    }

    public void deleteByTemplateId(long templateId) {
        jdbc.update("DELETE FROM doc_template_generate_record WHERE template_id = :templateId",
                new MapSqlParameterSource("templateId", templateId));
    }

    private static String where(String keyword, String status, Collection<Long> templateIds,
                                MapSqlParameterSource params) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        if (StringUtils.hasText(status) && !"ALL".equalsIgnoreCase(status.trim())) {
            where.append(" AND status = :status");
            params.addValue("status", status.trim());
        }
        if (StringUtils.hasText(keyword)) {
            String text = keyword.trim();
            where.append(" AND (create_by LIKE :keyword OR error_message LIKE :keyword");
            params.addValue("keyword", "%" + text + "%");
            // 记录号按等值匹配，不把 id 转成字符串再 LIKE：各库的类型转换规则不一样，
            // PostgreSQL 的 CAST(x AS CHAR) 是定长 1 位，会静默截断成首字符，导致按 id 永远搜不到。
            Long recordId = numericId(text);
            if (recordId != null) {
                where.append(" OR id = :recordId");
                params.addValue("recordId", recordId);
            }
            if (templateIds != null && !templateIds.isEmpty()) {
                where.append(" OR template_id IN (:templateIds)");
                params.addValue("templateIds", templateIds);
            }
            where.append(")");
        }
        return where.toString();
    }

    /** 纯数字（且放得进 BIGINT）才当记录号；带字母的关键字不参与 id 匹配。 */
    private static Long numericId(String text) {
        if (text.isEmpty() || text.length() > 18) return null;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < '0' || c > '9') return null;
        }
        return Long.parseLong(text);
    }
}
