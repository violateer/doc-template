package io.github.violateer.doctemplate.starter.repository;

import io.github.violateer.doctemplate.starter.db.DocTemplateDialectProvider;
import io.github.violateer.doctemplate.starter.entity.DocTemplateAiImportEntity;
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
import java.time.LocalDateTime;
import java.util.List;

/**
 * {@code doc_template_ai_import} 表的读写。
 *
 * <p>列表查询刻意不选 {@code plan_json}：这一列在 Oracle 上是 CLOB，既不能 LIKE 也不适合
 * 跟着分页排序走，一页几十条任务没必要把每份计划都读出来。</p>
 */
public class DocTemplateAiImportRepository {

    private static final String LIST_COLUMNS = "id, file_name, file_url, file_sha256, file_type, template_name,"
            + " template_code, hint, status, stage, template_id, error_message, create_by, create_time,"
            + " start_time, finish_time, elapsed_ms";

    private static final RowMapper<DocTemplateAiImportEntity> LIST_MAPPER =
            (ResultSet rs, int rowNum) -> map(rs, false);

    private static final RowMapper<DocTemplateAiImportEntity> FULL_MAPPER =
            (ResultSet rs, int rowNum) -> map(rs, true);

    private final NamedParameterJdbcTemplate jdbc;
    private final DocTemplateDialectProvider dialect;

    public DocTemplateAiImportRepository(@Qualifier("docTemplateJdbcTemplate") JdbcTemplate jdbc,
                                         DocTemplateDialectProvider dialect) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbc);
        this.dialect = dialect;
    }

    public List<DocTemplateAiImportEntity> findPage(String status, String keyword, int offset, int limit) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String sql = "SELECT " + LIST_COLUMNS + " FROM doc_template_ai_import"
                + where(status, keyword, params) + " ORDER BY id DESC";
        return jdbc.query(dialect.current().page(sql, offset, limit), params, LIST_MAPPER);
    }

    public long count(String status, String keyword) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM doc_template_ai_import"
                + where(status, keyword, params), params, Long.class);
        return total == null ? 0L : total;
    }

    /** 详情：带 {@code plan_json}。 */
    public DocTemplateAiImportEntity findById(long id) {
        List<DocTemplateAiImportEntity> rows = jdbc.query(
                "SELECT " + LIST_COLUMNS + ", plan_json FROM doc_template_ai_import WHERE id = :id",
                new MapSqlParameterSource("id", id), FULL_MAPPER);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void insert(DocTemplateAiImportEntity e) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("fileName", e.getFileName())
                .addValue("fileUrl", e.getFileUrl())
                .addValue("fileSha256", e.getFileSha256(), Types.VARCHAR)
                .addValue("fileType", e.getFileType())
                .addValue("templateName", e.getTemplateName())
                .addValue("templateCode", e.getTemplateCode())
                .addValue("hint", e.getHint(), Types.VARCHAR)
                .addValue("status", e.getStatus())
                .addValue("stage", e.getStage(), Types.VARCHAR)
                .addValue("createBy", e.getCreateBy(), Types.VARCHAR)
                .addValue("createTime", e.getCreateTime(), Types.TIMESTAMP);
        KeyHolder keys = new GeneratedKeyHolder();
        dialect.current().insert(jdbc, "INSERT INTO doc_template_ai_import"
                + " (file_name, file_url, file_sha256, file_type, template_name, template_code, hint, status, stage,"
                + " create_by, create_time)"
                + " VALUES (:fileName, :fileUrl, :fileSha256, :fileType, :templateName, :templateCode, :hint, :status,"
                + " :stage, :createBy, :createTime)", params, keys);
        Long id = JdbcKeys.generatedId(keys);
        if (id != null) e.setId(id);
    }

    /** 任务被线程真正接手：状态转 {@code RUNNING} 并记开始时间。 */
    public void markRunning(long id, String stage, LocalDateTime startTime) {
        jdbc.update("UPDATE doc_template_ai_import SET status = 'RUNNING', stage = :stage, start_time = :startTime"
                        + " WHERE id = :id",
                new MapSqlParameterSource("id", id).addValue("stage", stage)
                        .addValue("startTime", startTime, Types.TIMESTAMP));
    }

    /** 每进入一个阶段写一次库，前端轮询就能看到当前在做什么。 */
    public void updateStage(long id, String stage) {
        jdbc.update("UPDATE doc_template_ai_import SET stage = :stage WHERE id = :id",
                new MapSqlParameterSource("id", id).addValue("stage", stage));
    }

    public void updatePlan(long id, String planJson) {
        jdbc.update("UPDATE doc_template_ai_import SET plan_json = :planJson WHERE id = :id",
                new MapSqlParameterSource("id", id).addValue("planJson", planJson, Types.LONGVARCHAR));
    }

    /** 文件先入库再写存储：对象 key 里带任务主键，所以只能建完行才知道往哪写。 */
    public void updateFileUrl(long id, String fileUrl) {
        jdbc.update("UPDATE doc_template_ai_import SET file_url = :fileUrl WHERE id = :id",
                new MapSqlParameterSource("id", id).addValue("fileUrl", fileUrl));
    }

    /**
     * 结束任务。计划单独用 {@link #updatePlan(long, String)} 写，所以这里不动它 ——
     * 失败时反而要保留上一次自检通过的计划，供人改完重试。
     *
     * <p>{@code templateId} 为 {@code null} 时不动 {@code template_id} 这一列：失败与取消不能抹掉
     * 「这条任务已经产出过哪个模板」，否则再点「重新分析」会被当成新任务去建模板，
     * 撞上模板编码唯一约束。</p>
     */
    public void finish(long id, String status, Long templateId, String errorMessage,
                       LocalDateTime finishTime, Long elapsedMs) {
        MapSqlParameterSource params = new MapSqlParameterSource("id", id)
                .addValue("status", status)
                .addValue("errorMessage", errorMessage, Types.VARCHAR)
                .addValue("finishTime", finishTime, Types.TIMESTAMP)
                .addValue("elapsedMs", elapsedMs, Types.BIGINT);
        String setTemplate = "";
        if (templateId != null) {
            setTemplate = ", template_id = :templateId";
            params.addValue("templateId", templateId, Types.BIGINT);
        }
        jdbc.update("UPDATE doc_template_ai_import SET status = :status" + setTemplate
                        + ", error_message = :errorMessage, finish_time = :finishTime,"
                        + " elapsed_ms = :elapsedMs WHERE id = :id", params);
    }

    /**
     * 抢占任务：受影响行数为 1 才算拿到。
     *
     * <p>单实例部署用不上，多实例时这一句就是最小的互斥 —— 不需要额外的分布式锁。
     * 失败、取消过的任务也允许重跑，所以状态条件是「未在跑」而不是「等于 PENDING」。</p>
     */
    public int claim(long id, LocalDateTime startTime) {
        return jdbc.update("UPDATE doc_template_ai_import SET status = 'RUNNING', stage = 'SNAPSHOT',"
                        + " start_time = :startTime, finish_time = NULL, elapsed_ms = NULL, error_message = NULL"
                        + " WHERE id = :id AND status IN ('PENDING', 'FAILED', 'CANCELED')",
                new MapSqlParameterSource("id", id).addValue("startTime", startTime, Types.TIMESTAMP));
    }

    /** 未开始的任务直接标取消；已经开始的不走这里。 */
    public int cancelPending(long id) {
        return jdbc.update("UPDATE doc_template_ai_import SET status = 'CANCELED', finish_time = :now"
                        + " WHERE id = :id AND status = 'PENDING'",
                new MapSqlParameterSource("id", id).addValue("now", LocalDateTime.now(), Types.TIMESTAMP));
    }

    /**
     * 重新分析前把任务退回待执行：清掉上一次的阶段、计划、失败原因与耗时。
     *
     * <p>{@code template_id} 不动 —— 重新分析要把结果写回同一个模板，不是再建一个。
     * 已生成模板的任务也允许重置，所以状态条件是「不在跑」而不是「等于 PENDING」。</p>
     */
    public int resetForRerun(long id) {
        return jdbc.update("UPDATE doc_template_ai_import SET status = 'PENDING', stage = NULL, plan_json = NULL,"
                        + " error_message = NULL, start_time = NULL, finish_time = NULL, elapsed_ms = NULL"
                        + " WHERE id = :id AND status IN ('PENDING', 'FAILED', 'CANCELED', 'SUCCEEDED')",
                new MapSqlParameterSource("id", id));
    }

    /** 服务重启后残留的 {@code RUNNING} 一律标失败：内存里没有任务，续跑也无从谈起。 */
    public int resetRunning(String reason) {
        return jdbc.update("UPDATE doc_template_ai_import SET status = 'FAILED', error_message = :reason,"
                        + " finish_time = :now WHERE status = 'RUNNING'",
                new MapSqlParameterSource("reason", reason).addValue("now", LocalDateTime.now(), Types.TIMESTAMP));
    }

    public void deleteById(long id) {
        jdbc.update("DELETE FROM doc_template_ai_import WHERE id = :id", new MapSqlParameterSource("id", id));
    }

    private static DocTemplateAiImportEntity map(ResultSet rs, boolean withPlan) throws java.sql.SQLException {
        DocTemplateAiImportEntity e = new DocTemplateAiImportEntity();
        e.setId(rs.getLong("id"));
        e.setFileName(rs.getString("file_name"));
        e.setFileUrl(rs.getString("file_url"));
        e.setFileSha256(rs.getString("file_sha256"));
        e.setFileType(rs.getString("file_type"));
        e.setTemplateName(rs.getString("template_name"));
        e.setTemplateCode(rs.getString("template_code"));
        e.setHint(rs.getString("hint"));
        e.setStatus(rs.getString("status"));
        e.setStage(rs.getString("stage"));
        e.setTemplateId(DocTemplateRepository.nullableLong(rs, "template_id"));
        e.setErrorMessage(rs.getString("error_message"));
        e.setCreateBy(rs.getString("create_by"));
        e.setCreateTime(DocTemplateRepository.nullableTime(rs, "create_time"));
        e.setStartTime(DocTemplateRepository.nullableTime(rs, "start_time"));
        e.setFinishTime(DocTemplateRepository.nullableTime(rs, "finish_time"));
        e.setElapsedMs(DocTemplateRepository.nullableLong(rs, "elapsed_ms"));
        if (withPlan) e.setPlanJson(rs.getString("plan_json"));
        return e;
    }

    private static String where(String status, String keyword, MapSqlParameterSource params) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        if (StringUtils.hasText(status) && !"ALL".equalsIgnoreCase(status.trim())) {
            where.append(" AND status = :status");
            params.addValue("status", status.trim());
        }
        if (StringUtils.hasText(keyword)) {
            where.append(" AND (file_name LIKE :keyword OR template_name LIKE :keyword"
                    + " OR template_code LIKE :keyword OR create_by LIKE :keyword)");
            params.addValue("keyword", "%" + keyword.trim() + "%");
        }
        return where.toString();
    }
}
