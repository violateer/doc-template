package io.github.violateer.doctemplate.starter.repository;

import io.github.violateer.doctemplate.starter.db.DocTemplateDialectProvider;
import io.github.violateer.doctemplate.starter.entity.DocTemplateDictItemEntity;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** {@code doc_template_dict_item}（字典子表：值 → 显示名）的读写。 */
public class DocTemplateDictItemRepository {

    private static final String COLUMNS = "id, dict_id, item_value, item_label, sort_order, create_time";

    private static final RowMapper<DocTemplateDictItemEntity> ROW_MAPPER = (ResultSet rs, int rowNum) -> {
        DocTemplateDictItemEntity e = new DocTemplateDictItemEntity();
        e.setId(rs.getLong("id"));
        e.setDictId(rs.getLong("dict_id"));
        e.setItemValue(rs.getString("item_value"));
        e.setItemLabel(rs.getString("item_label"));
        e.setSortOrder(rs.getInt("sort_order"));
        Timestamp created = rs.getTimestamp("create_time");
        e.setCreateTime(created == null ? null : created.toLocalDateTime());
        return e;
    };

    private final NamedParameterJdbcTemplate jdbc;
    private final DocTemplateDialectProvider dialect;

    public DocTemplateDictItemRepository(@Qualifier("docTemplateJdbcTemplate") JdbcTemplate jdbc,
                                         DocTemplateDialectProvider dialect) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbc);
        this.dialect = dialect;
    }

    public List<DocTemplateDictItemEntity> findByDictId(long dictId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM doc_template_dict_item WHERE dict_id = :dictId"
                        + " ORDER BY sort_order ASC, id ASC",
                new MapSqlParameterSource("dictId", dictId), ROW_MAPPER);
    }

    /** 字典项分页：字典项上千条时管理页只取当前页，排序与全量查询一致。 */
    public List<DocTemplateDictItemEntity> findPage(long dictId, int offset, int limit) {
        String sql = dialect.current().page("SELECT " + COLUMNS + " FROM doc_template_dict_item"
                + " WHERE dict_id = :dictId ORDER BY sort_order ASC, id ASC", offset, limit);
        return jdbc.query(sql, new MapSqlParameterSource("dictId", dictId), ROW_MAPPER);
    }

    public long countByDictId(long dictId) {
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM doc_template_dict_item WHERE dict_id = :dictId",
                new MapSqlParameterSource("dictId", dictId), Long.class);
        return total == null ? 0L : total;
    }

    public DocTemplateDictItemEntity findById(long id) {
        List<DocTemplateDictItemEntity> rows = jdbc.query("SELECT " + COLUMNS
                + " FROM doc_template_dict_item WHERE id = :id", new MapSqlParameterSource("id", id), ROW_MAPPER);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 同一本字典里 {@code item_value} 不能重复（表上有唯一索引，这里先查一次给出友好提示）。 */
    public DocTemplateDictItemEntity findByDictIdAndValue(long dictId, String itemValue, Long excludeId) {
        List<DocTemplateDictItemEntity> rows = jdbc.query("SELECT " + COLUMNS + " FROM doc_template_dict_item"
                        + " WHERE dict_id = :dictId AND item_value = :itemValue AND id <> :excludeId",
                new MapSqlParameterSource("dictId", dictId)
                        .addValue("itemValue", itemValue)
                        .addValue("excludeId", excludeId == null ? -1L : excludeId), ROW_MAPPER);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 转换器取数：一次 join 出「值 → 显示名」，按 sort_order 有序。
     * 字典不存在时返回空 Map（调用方走 fallback），不抛异常。
     */
    public Map<String, String> labelsByCode(String dictCode) {
        Map<String, String> labels = new LinkedHashMap<>();
        jdbc.query("SELECT i.item_value, i.item_label FROM doc_template_dict_item i"
                        + " JOIN doc_template_dict d ON d.id = i.dict_id"
                        + " WHERE d.dict_code = :dictCode ORDER BY i.sort_order ASC, i.id ASC",
                new MapSqlParameterSource("dictCode", dictCode),
                (ResultSet rs) -> {
                    labels.putIfAbsent(rs.getString("item_value"), rs.getString("item_label"));
                });
        return labels;
    }

    public void insert(DocTemplateDictItemEntity e) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("dictId", e.getDictId())
                .addValue("itemValue", e.getItemValue())
                .addValue("itemLabel", e.getItemLabel())
                .addValue("sortOrder", e.getSortOrder() == null ? 0 : e.getSortOrder());
        KeyHolder keys = new GeneratedKeyHolder();
        dialect.current().insert(jdbc, "INSERT INTO doc_template_dict_item"
                + " (dict_id, item_value, item_label, sort_order)"
                + " VALUES (:dictId, :itemValue, :itemLabel, :sortOrder)", params, keys);
        Long id = JdbcKeys.generatedId(keys);
        if (id != null) e.setId(id);
    }

    public void update(DocTemplateDictItemEntity e) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", e.getId())
                .addValue("itemValue", e.getItemValue())
                .addValue("itemLabel", e.getItemLabel())
                .addValue("sortOrder", e.getSortOrder() == null ? 0 : e.getSortOrder());
        jdbc.update("UPDATE doc_template_dict_item SET item_value = :itemValue, item_label = :itemLabel,"
                + " sort_order = :sortOrder WHERE id = :id", params);
    }

    public void deleteById(long id) {
        jdbc.update("DELETE FROM doc_template_dict_item WHERE id = :id", new MapSqlParameterSource("id", id));
    }

    public int deleteByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) return 0;
        return jdbc.update("DELETE FROM doc_template_dict_item WHERE id IN (:ids)",
                new MapSqlParameterSource("ids", ids));
    }

    /** 删字典时连带清子表（表上没有外键约束，顺序由 Service 保证）。 */
    public int deleteByDictId(long dictId) {
        return jdbc.update("DELETE FROM doc_template_dict_item WHERE dict_id = :dictId",
                new MapSqlParameterSource("dictId", dictId));
    }

    /** 新建项默认排在最后，避免和历史数据撞号插到中间。 */
    public int maxSortOrder(long dictId) {
        Integer max = jdbc.queryForObject("SELECT COALESCE(MAX(sort_order), 0) FROM doc_template_dict_item"
                + " WHERE dict_id = :dictId", new MapSqlParameterSource("dictId", dictId), Integer.class);
        return max == null ? 0 : max;
    }
}
