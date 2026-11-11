package io.github.violateer.doctemplate.starter.repository;

import io.github.violateer.doctemplate.starter.db.DocTemplateDialectProvider;
import io.github.violateer.doctemplate.starter.entity.DocTemplateDictEntity;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.Collection;
import java.util.List;

/**
 * {@code doc_template_dict}（字典主表）的读写。
 *
 * <p>列表查询同时带出子表项数（{@code LEFT JOIN + COUNT}），管理页可直接显示「该字典的项数」，
 * 不用逐本再查一次。</p>
 */
public class DocTemplateDictRepository {

    private static final String COLUMNS = "d.id, d.dict_code, d.dict_name, d.remark, d.create_time, d.update_time";

    private static final String LIST_SQL = "SELECT " + COLUMNS + ", COUNT(i.id) AS item_count"
            + " FROM doc_template_dict d LEFT JOIN doc_template_dict_item i ON i.dict_id = d.id";

    private static final RowMapper<DocTemplateDictEntity> ROW_MAPPER = (ResultSet rs, int rowNum) -> {
        DocTemplateDictEntity e = new DocTemplateDictEntity();
        e.setId(rs.getLong("id"));
        e.setDictCode(rs.getString("dict_code"));
        e.setDictName(rs.getString("dict_name"));
        e.setRemark(rs.getString("remark"));
        Timestamp created = rs.getTimestamp("create_time");
        e.setCreateTime(created == null ? null : created.toLocalDateTime());
        Timestamp updated = rs.getTimestamp("update_time");
        e.setUpdateTime(updated == null ? null : updated.toLocalDateTime());
        return e;
    };

    private final NamedParameterJdbcTemplate jdbc;
    private final DocTemplateDialectProvider dialect;

    public DocTemplateDictRepository(@Qualifier("docTemplateJdbcTemplate") JdbcTemplate jdbc,
                                     DocTemplateDialectProvider dialect) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbc);
        this.dialect = dialect;
    }

    /** 全部字典（含项数），按编码排序；{@code keyword} 匹配编码 / 名称 / 备注。 */
    public List<DocTemplateDictEntity> findAll(String keyword) {
        boolean filtered = keyword != null && !keyword.isBlank();
        String sql = LIST_SQL + (filtered
                ? " WHERE d.dict_code LIKE :keyword OR d.dict_name LIKE :keyword OR d.remark LIKE :keyword"
                : "")
                + " GROUP BY d.id, d.dict_code, d.dict_name, d.remark, d.create_time, d.update_time"
                + " ORDER BY d.dict_code ASC, d.id ASC";
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (filtered) params.addValue("keyword", "%" + keyword.trim() + "%");
        return jdbc.query(sql, params, (ResultSet rs, int rowNum) -> {
            DocTemplateDictEntity e = ROW_MAPPER.mapRow(rs, rowNum);
            if (e != null) e.setItemCount(rs.getInt("item_count"));
            return e;
        });
    }

    public DocTemplateDictEntity findById(long id) {
        List<DocTemplateDictEntity> rows = jdbc.query("SELECT " + COLUMNS + " FROM doc_template_dict d"
                + " WHERE d.id = :id", new MapSqlParameterSource("id", id), ROW_MAPPER);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 转换器按编码取字典用。 */
    public DocTemplateDictEntity findByCode(String dictCode) {
        List<DocTemplateDictEntity> rows = jdbc.query("SELECT " + COLUMNS + " FROM doc_template_dict d"
                + " WHERE d.dict_code = :dictCode", new MapSqlParameterSource("dictCode", dictCode), ROW_MAPPER);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 编码唯一，新建/改名时先查一次给出友好提示（表上还有唯一索引兜底）。 */
    public DocTemplateDictEntity findByCodeExcluding(String dictCode, Long excludeId) {
        List<DocTemplateDictEntity> rows = jdbc.query("SELECT " + COLUMNS + " FROM doc_template_dict d"
                        + " WHERE d.dict_code = :dictCode AND d.id <> :excludeId",
                new MapSqlParameterSource("dictCode", dictCode)
                        .addValue("excludeId", excludeId == null ? -1L : excludeId), ROW_MAPPER);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void insert(DocTemplateDictEntity e) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("dictCode", e.getDictCode())
                .addValue("dictName", e.getDictName())
                .addValue("remark", e.getRemark());
        KeyHolder keys = new GeneratedKeyHolder();
        dialect.current().insert(jdbc, "INSERT INTO doc_template_dict (dict_code, dict_name, remark)"
                + " VALUES (:dictCode, :dictName, :remark)", params, keys);
        Long id = JdbcKeys.generatedId(keys);
        if (id != null) e.setId(id);
    }

    public void update(DocTemplateDictEntity e) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", e.getId())
                .addValue("dictCode", e.getDictCode())
                .addValue("dictName", e.getDictName())
                .addValue("remark", e.getRemark());
        jdbc.update("UPDATE doc_template_dict SET dict_code = :dictCode, dict_name = :dictName,"
                + " remark = :remark, update_time = CURRENT_TIMESTAMP WHERE id = :id", params);
    }

    public void deleteById(long id) {
        jdbc.update("DELETE FROM doc_template_dict WHERE id = :id", new MapSqlParameterSource("id", id));
    }

    public int deleteByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) return 0;
        return jdbc.update("DELETE FROM doc_template_dict WHERE id IN (:ids)",
                new MapSqlParameterSource("ids", ids));
    }
}
