package io.github.violateer.doctemplate.starter.repository;

import io.github.violateer.doctemplate.starter.db.DocTemplateDialectProvider;
import io.github.violateer.doctemplate.starter.entity.DocTemplateEntity;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.util.StringUtils;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * {@code doc_template} 表的读写。
 *
 * <p>整个持久层只用 Spring JDBC，不引入任何 ORM：宿主只要能提供一个 {@code DataSource}，
 * 不论用不用 ORM、ORM 是什么版本，这个模块都能跑起来。</p>
 */
public class DocTemplateRepository {

    private static final String COLUMNS =
            "id, template_code, template_name, file_type, export_type, current_version_id, status, remark, create_time, update_time";

    private static final String INSERT_SQL = "INSERT INTO doc_template"
            + " (template_code, template_name, file_type, export_type, current_version_id, status, remark, create_time, update_time)"
            + " VALUES (:templateCode, :templateName, :fileType, :exportType, :currentVersionId, :status, :remark, :createTime, :updateTime)";

    private static final String UPDATE_SQL = "UPDATE doc_template SET"
            + " template_code = :templateCode, template_name = :templateName, file_type = :fileType,"
            + " export_type = :exportType, current_version_id = :currentVersionId, status = :status,"
            + " remark = :remark, update_time = :updateTime"
            + " WHERE id = :id";

    private static final RowMapper<DocTemplateEntity> ROW_MAPPER = (ResultSet rs, int rowNum) -> {
        DocTemplateEntity e = new DocTemplateEntity();
        e.setId(rs.getLong("id"));
        e.setTemplateCode(rs.getString("template_code"));
        e.setTemplateName(rs.getString("template_name"));
        e.setFileType(rs.getString("file_type"));
        e.setExportType(rs.getString("export_type"));
        e.setCurrentVersionId(nullableLong(rs, "current_version_id"));
        e.setStatus(rs.getString("status"));
        e.setRemark(rs.getString("remark"));
        e.setCreateTime(nullableTime(rs, "create_time"));
        e.setUpdateTime(nullableTime(rs, "update_time"));
        return e;
    };

    private final NamedParameterJdbcTemplate jdbc;
    private final DocTemplateDialectProvider dialect;

    public DocTemplateRepository(@Qualifier("docTemplateJdbcTemplate") JdbcTemplate jdbc,
                                 DocTemplateDialectProvider dialect) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbc);
        this.dialect = dialect;
    }

    public List<DocTemplateEntity> findAll() {
        return jdbc.query("SELECT " + COLUMNS + " FROM doc_template ORDER BY id DESC", ROW_MAPPER);
    }

    public List<DocTemplateEntity> findPage(String keyword, String status, int offset, int limit) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String sql = "SELECT " + COLUMNS + " FROM doc_template" + where(keyword, status, params) + " ORDER BY id DESC";
        return jdbc.query(dialect.current().page(sql, offset, limit), params, ROW_MAPPER);
    }

    public long count(String keyword, String status) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String where = where(keyword, status, params);
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM doc_template" + where, params, Long.class);
        return total == null ? 0L : total;
    }

    public long countAll() {
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM doc_template", new MapSqlParameterSource(), Long.class);
        return total == null ? 0L : total;
    }

    public long countByStatus(String status) {
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM doc_template WHERE status = :status",
                new MapSqlParameterSource("status", status), Long.class);
        return total == null ? 0L : total;
    }

    public long countUploaded() {
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM doc_template WHERE current_version_id IS NOT NULL",
                new MapSqlParameterSource(), Long.class);
        return total == null ? 0L : total;
    }

    public DocTemplateEntity findById(long id) {
        List<DocTemplateEntity> rows = jdbc.query("SELECT " + COLUMNS + " FROM doc_template WHERE id = :id",
                new MapSqlParameterSource("id", id), ROW_MAPPER);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 按编码查模板，用于新增 / 改编码时的唯一性校验。 */
    public DocTemplateEntity findByCode(String code) {
        List<DocTemplateEntity> rows = jdbc.query("SELECT " + COLUMNS + " FROM doc_template WHERE template_code = :code",
                new MapSqlParameterSource("code", code), ROW_MAPPER);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 模板名称模糊匹配的 id 列表，供生成记录的关键字过滤使用。 */
    public List<Long> findIdsByNameLike(String text) {
        return jdbc.query("SELECT id FROM doc_template WHERE template_name LIKE :text ORDER BY id DESC",
                new MapSqlParameterSource("text", "%" + text + "%"), (rs, rowNum) -> rs.getLong("id"));
    }

    public List<DocTemplateEntity> findByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        return jdbc.query("SELECT " + COLUMNS + " FROM doc_template WHERE id IN (:ids)",
                new MapSqlParameterSource("ids", ids), ROW_MAPPER);
    }

    /** 插入模板并回填自增主键。 */
    public void insert(DocTemplateEntity e) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("templateCode", e.getTemplateCode())
                .addValue("templateName", e.getTemplateName())
                .addValue("fileType", e.getFileType())
                .addValue("exportType", e.getExportType())
                .addValue("currentVersionId", e.getCurrentVersionId(), Types.BIGINT)
                .addValue("status", e.getStatus())
                .addValue("remark", e.getRemark(), Types.VARCHAR)
                .addValue("createTime", e.getCreateTime(), Types.TIMESTAMP)
                .addValue("updateTime", e.getUpdateTime(), Types.TIMESTAMP);
        KeyHolder keys = new GeneratedKeyHolder();
        dialect.current().insert(jdbc, INSERT_SQL, params, keys);
        Long id = JdbcKeys.generatedId(keys);
        if (id != null) e.setId(id);
    }

    public void update(DocTemplateEntity e) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", e.getId())
                .addValue("templateCode", e.getTemplateCode())
                .addValue("templateName", e.getTemplateName())
                .addValue("fileType", e.getFileType())
                .addValue("exportType", e.getExportType())
                .addValue("currentVersionId", e.getCurrentVersionId(), Types.BIGINT)
                .addValue("status", e.getStatus())
                .addValue("remark", e.getRemark(), Types.VARCHAR)
                .addValue("updateTime", e.getUpdateTime(), Types.TIMESTAMP);
        jdbc.update(UPDATE_SQL, params);
    }

    public void deleteById(long id) {
        jdbc.update("DELETE FROM doc_template WHERE id = :id", new MapSqlParameterSource("id", id));
    }

    private static String where(String keyword, String status, MapSqlParameterSource params) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        if (StringUtils.hasText(status) && !"ALL".equalsIgnoreCase(status.trim())) {
            where.append(" AND status = :status");
            params.addValue("status", status.trim());
        }
        if (StringUtils.hasText(keyword)) {
            where.append(" AND (template_name LIKE :keyword OR template_code LIKE :keyword)");
            params.addValue("keyword", "%" + keyword.trim() + "%");
        }
        return where.toString();
    }

    static Long nullableLong(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, Long.class);
    }

    static LocalDateTime nullableTime(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, LocalDateTime.class);
    }
}
