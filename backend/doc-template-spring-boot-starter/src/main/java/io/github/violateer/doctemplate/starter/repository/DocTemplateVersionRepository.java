package io.github.violateer.doctemplate.starter.repository;

import io.github.violateer.doctemplate.starter.entity.DocTemplateVersionEntity;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.sql.ResultSet;
import java.sql.Types;
import java.util.List;

/** {@code doc_template_version} 表的读写。 */
public class DocTemplateVersionRepository {

    private static final String COLUMNS =
            "id, template_id, version_no, file_name, file_url, file_sha256, create_time";

    private static final RowMapper<DocTemplateVersionEntity> ROW_MAPPER = (ResultSet rs, int rowNum) -> {
        DocTemplateVersionEntity e = new DocTemplateVersionEntity();
        e.setId(rs.getLong("id"));
        e.setTemplateId(rs.getLong("template_id"));
        e.setVersionNo(rs.getInt("version_no"));
        e.setFileName(rs.getString("file_name"));
        e.setFileUrl(rs.getString("file_url"));
        e.setFileSha256(rs.getString("file_sha256"));
        e.setCreateTime(DocTemplateRepository.nullableTime(rs, "create_time"));
        return e;
    };

    private final NamedParameterJdbcTemplate jdbc;

    public DocTemplateVersionRepository(@Qualifier("docTemplateJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbc);
    }

    public List<DocTemplateVersionEntity> findByTemplateId(long templateId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM doc_template_version WHERE template_id = :templateId"
                + " ORDER BY version_no DESC", new MapSqlParameterSource("templateId", templateId), ROW_MAPPER);
    }

    /** 版本号最大的那一版；没有版本时返回 {@code null}。 */
    public DocTemplateVersionEntity findLatest(long templateId) {
        List<DocTemplateVersionEntity> rows = jdbc.query("SELECT " + COLUMNS + " FROM doc_template_version"
                + " WHERE template_id = :templateId ORDER BY version_no DESC LIMIT 1",
                new MapSqlParameterSource("templateId", templateId), ROW_MAPPER);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public DocTemplateVersionEntity findById(long id) {
        List<DocTemplateVersionEntity> rows = jdbc.query("SELECT " + COLUMNS + " FROM doc_template_version WHERE id = :id",
                new MapSqlParameterSource("id", id), ROW_MAPPER);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void insert(DocTemplateVersionEntity e) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("templateId", e.getTemplateId())
                .addValue("versionNo", e.getVersionNo())
                .addValue("fileName", e.getFileName())
                .addValue("fileUrl", e.getFileUrl())
                .addValue("fileSha256", e.getFileSha256(), Types.VARCHAR)
                .addValue("createTime", e.getCreateTime(), Types.TIMESTAMP);
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update("INSERT INTO doc_template_version (template_id, version_no, file_name, file_url, file_sha256, create_time)"
                + " VALUES (:templateId, :versionNo, :fileName, :fileUrl, :fileSha256, :createTime)", params, keys);
        Long id = JdbcKeys.generatedId(keys);
        if (id != null) e.setId(id);
    }

    public void deleteById(long id) {
        jdbc.update("DELETE FROM doc_template_version WHERE id = :id", new MapSqlParameterSource("id", id));
    }

    public void deleteByTemplateId(long templateId) {
        jdbc.update("DELETE FROM doc_template_version WHERE template_id = :templateId",
                new MapSqlParameterSource("templateId", templateId));
    }
}
