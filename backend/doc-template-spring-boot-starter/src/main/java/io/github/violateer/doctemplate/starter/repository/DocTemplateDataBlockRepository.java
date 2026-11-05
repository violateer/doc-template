package io.github.violateer.doctemplate.starter.repository;

import io.github.violateer.doctemplate.starter.db.DocTemplateDialectProvider;
import io.github.violateer.doctemplate.starter.entity.DocTemplateDataBlockEntity;
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

/** {@code doc_template_data_block} 表的读写。 */
public class DocTemplateDataBlockRepository {

    private static final String COLUMNS = "id, template_id, block_code, block_name, data_source_type,"
            + " data_source_config, param_config, sort_order, status";

    private static final RowMapper<DocTemplateDataBlockEntity> ROW_MAPPER = (ResultSet rs, int rowNum) -> {
        DocTemplateDataBlockEntity e = new DocTemplateDataBlockEntity();
        e.setId(rs.getLong("id"));
        e.setTemplateId(rs.getLong("template_id"));
        e.setBlockCode(rs.getString("block_code"));
        e.setBlockName(rs.getString("block_name"));
        e.setDataSourceType(rs.getString("data_source_type"));
        e.setDataSourceConfig(rs.getString("data_source_config"));
        e.setParamConfig(rs.getString("param_config"));
        e.setSortOrder(rs.getInt("sort_order"));
        e.setStatus(rs.getString("status"));
        return e;
    };

    private final NamedParameterJdbcTemplate jdbc;
    private final DocTemplateDialectProvider dialect;

    public DocTemplateDataBlockRepository(@Qualifier("docTemplateJdbcTemplate") JdbcTemplate jdbc,
                                          DocTemplateDialectProvider dialect) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbc);
        this.dialect = dialect;
    }

    public List<DocTemplateDataBlockEntity> findByTemplateId(long templateId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM doc_template_data_block WHERE template_id = :templateId"
                        + " ORDER BY sort_order ASC, id ASC",
                new MapSqlParameterSource("templateId", templateId), ROW_MAPPER);
    }

    public DocTemplateDataBlockEntity findByTemplateIdAndCode(long templateId, String blockCode) {
        List<DocTemplateDataBlockEntity> rows = jdbc.query("SELECT " + COLUMNS + " FROM doc_template_data_block"
                        + " WHERE template_id = :templateId AND block_code = :blockCode",
                new MapSqlParameterSource("templateId", templateId).addValue("blockCode", blockCode), ROW_MAPPER);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public DocTemplateDataBlockEntity findById(long id) {
        List<DocTemplateDataBlockEntity> rows = jdbc.query("SELECT " + COLUMNS
                + " FROM doc_template_data_block WHERE id = :id", new MapSqlParameterSource("id", id), ROW_MAPPER);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void insert(DocTemplateDataBlockEntity e) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("templateId", e.getTemplateId())
                .addValue("blockCode", e.getBlockCode())
                .addValue("blockName", e.getBlockName())
                .addValue("dataSourceType", e.getDataSourceType())
                .addValue("dataSourceConfig", e.getDataSourceConfig())
                .addValue("paramConfig", e.getParamConfig(), Types.VARCHAR)
                .addValue("sortOrder", e.getSortOrder() == null ? 0 : e.getSortOrder())
                .addValue("status", e.getStatus());
        KeyHolder keys = new GeneratedKeyHolder();
        dialect.current().insert(jdbc, "INSERT INTO doc_template_data_block"
                + " (template_id, block_code, block_name, data_source_type, data_source_config, param_config, sort_order, status)"
                + " VALUES (:templateId, :blockCode, :blockName, :dataSourceType, :dataSourceConfig, :paramConfig, :sortOrder, :status)",
                params, keys);
        Long id = JdbcKeys.generatedId(keys);
        if (id != null) e.setId(id);
    }

    public void update(DocTemplateDataBlockEntity e) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", e.getId())
                .addValue("blockCode", e.getBlockCode())
                .addValue("blockName", e.getBlockName())
                .addValue("dataSourceType", e.getDataSourceType())
                .addValue("dataSourceConfig", e.getDataSourceConfig())
                .addValue("paramConfig", e.getParamConfig(), Types.VARCHAR)
                .addValue("sortOrder", e.getSortOrder() == null ? 0 : e.getSortOrder())
                .addValue("status", e.getStatus());
        jdbc.update("UPDATE doc_template_data_block SET block_code = :blockCode, block_name = :blockName,"
                + " data_source_type = :dataSourceType, data_source_config = :dataSourceConfig,"
                + " param_config = :paramConfig, sort_order = :sortOrder, status = :status WHERE id = :id", params);
    }

    public void deleteById(long id) {
        jdbc.update("DELETE FROM doc_template_data_block WHERE id = :id", new MapSqlParameterSource("id", id));
    }

    public void deleteByTemplateId(long templateId) {
        jdbc.update("DELETE FROM doc_template_data_block WHERE template_id = :templateId",
                new MapSqlParameterSource("templateId", templateId));
    }
}
