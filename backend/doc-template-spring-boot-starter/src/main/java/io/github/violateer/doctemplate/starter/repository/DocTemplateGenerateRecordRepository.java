package io.github.violateer.doctemplate.starter.repository;

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

    public DocTemplateGenerateRecordRepository(@Qualifier("docTemplateJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbc);
    }

    public List<DocTemplateGenerateRecordEntity> findAll() {
        return jdbc.query("SELECT " + COLUMNS + " FROM doc_template_generate_record ORDER BY id DESC", ROW_MAPPER);
    }

    /**
     * 分页查询，支持状态与关键字过滤。
     *
     * <p>关键字匹配记录号、生成人、失败说明，以及 {@code templateIds} 里的模板
     * （模板名称不是记录表的列，由调用方先按名称查出 id 再传进来，避免拼接 SQL）。</p>
     */
    public List<DocTemplateGenerateRecordEntity> findPage(String keyword, String status, Collection<Long> templateIds,
                                                          int offset, int limit) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String where = where(keyword, status, templateIds, params);
        params.addValue("limit", limit);
        params.addValue("offset", offset);
        return jdbc.query("SELECT " + COLUMNS + " FROM doc_template_generate_record" + where
                + " ORDER BY id DESC LIMIT :limit OFFSET :offset", params, ROW_MAPPER);
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
        jdbc.update("INSERT INTO doc_template_generate_record (template_id, template_version_id, export_type,"
                + " request_params, result_file_url, status, error_message, create_by, create_time)"
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
            where.append(" AND (create_by LIKE :keyword OR error_message LIKE :keyword OR CAST(id AS CHAR) LIKE :keyword");
            params.addValue("keyword", "%" + keyword.trim() + "%");
            if (templateIds != null && !templateIds.isEmpty()) {
                where.append(" OR template_id IN (:templateIds)");
                params.addValue("templateIds", templateIds);
            }
            where.append(")");
        }
        return where.toString();
    }
}
