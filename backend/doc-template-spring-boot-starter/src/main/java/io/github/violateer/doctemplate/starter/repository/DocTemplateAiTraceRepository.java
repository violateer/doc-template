package io.github.violateer.doctemplate.starter.repository;

import io.github.violateer.doctemplate.starter.db.DocTemplateDialectProvider;
import io.github.violateer.doctemplate.starter.entity.DocTemplateAiTraceEntity;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.sql.ResultSet;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.List;

/** {@code doc_template_ai_trace} 表的读写。 */
public class DocTemplateAiTraceRepository {

    private static final String COLUMNS = "id, import_id, attempt, stage, model, system_prompt, user_prompt,"
            + " reasoning, content, prompt_tokens, completion_tokens, elapsed_ms, error, create_time";

    private static final RowMapper<DocTemplateAiTraceEntity> ROW_MAPPER = (ResultSet rs, int rowNum) -> {
        DocTemplateAiTraceEntity e = new DocTemplateAiTraceEntity();
        e.setId(rs.getLong("id"));
        e.setImportId(rs.getLong("import_id"));
        e.setAttempt(rs.getInt("attempt"));
        e.setStage(rs.getString("stage"));
        e.setModel(rs.getString("model"));
        e.setSystemPrompt(rs.getString("system_prompt"));
        e.setUserPrompt(rs.getString("user_prompt"));
        e.setReasoning(rs.getString("reasoning"));
        e.setContent(rs.getString("content"));
        e.setPromptTokens(DocTemplateRepository.nullableInt(rs, "prompt_tokens"));
        e.setCompletionTokens(DocTemplateRepository.nullableInt(rs, "completion_tokens"));
        e.setElapsedMs(DocTemplateRepository.nullableLong(rs, "elapsed_ms"));
        e.setError(rs.getString("error"));
        e.setCreateTime(DocTemplateRepository.nullableTime(rs, "create_time"));
        return e;
    };

    /** 失败摘要：只取几列，供详情页的「每次尝试」列表使用。 */
    private static final RowMapper<Failure> FAILURE_MAPPER = (ResultSet rs, int rowNum) ->
            new Failure(rs.getInt("attempt"), rs.getString("stage"), rs.getString("error"),
                    rs.getLong("elapsed_ms"));

    private final NamedParameterJdbcTemplate jdbc;
    private final DocTemplateDialectProvider dialect;

    public DocTemplateAiTraceRepository(@Qualifier("docTemplateJdbcTemplate") JdbcTemplate jdbc,
                                        DocTemplateDialectProvider dialect) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbc);
        this.dialect = dialect;
    }

    public void insert(DocTemplateAiTraceEntity e) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("importId", e.getImportId())
                .addValue("attempt", e.getAttempt())
                .addValue("stage", e.getStage(), Types.VARCHAR)
                .addValue("model", e.getModel(), Types.VARCHAR)
                .addValue("systemPrompt", e.getSystemPrompt(), Types.VARCHAR)
                .addValue("userPrompt", e.getUserPrompt(), Types.VARCHAR)
                .addValue("reasoning", e.getReasoning(), Types.VARCHAR)
                .addValue("content", e.getContent(), Types.VARCHAR)
                .addValue("promptTokens", e.getPromptTokens(), Types.INTEGER)
                .addValue("completionTokens", e.getCompletionTokens(), Types.INTEGER)
                .addValue("elapsedMs", e.getElapsedMs(), Types.BIGINT)
                .addValue("error", e.getError(), Types.VARCHAR)
                .addValue("createTime", e.getCreateTime(), Types.TIMESTAMP);
        KeyHolder keys = new GeneratedKeyHolder();
        dialect.current().insert(jdbc, "INSERT INTO doc_template_ai_trace"
                + " (import_id, attempt, stage, model, system_prompt, user_prompt, reasoning, content, prompt_tokens,"
                + " completion_tokens, elapsed_ms, error, create_time)"
                + " VALUES (:importId, :attempt, :stage, :model, :systemPrompt, :userPrompt, :reasoning, :content,"
                + " :promptTokens, :completionTokens, :elapsedMs, :error, :createTime)", params, keys);
        Long id = JdbcKeys.generatedId(keys);
        if (id != null) e.setId(id);
    }

    /**
     * 流式过程写：只覆盖思考与正文两列。
     *
     * <p>调用方按时间节流（每几百毫秒一次），所以这里不做「内容变没变」的判断 ——
     * 逐包写会把连接池打满，写得太稀又看不出在生成。</p>
     */
    public void updateStreaming(long id, String reasoning, String content) {
        jdbc.update("UPDATE doc_template_ai_trace SET reasoning = :reasoning, content = :content WHERE id = :id",
                new MapSqlParameterSource("id", id)
                        .addValue("reasoning", reasoning, Types.VARCHAR)
                        .addValue("content", content, Types.VARCHAR));
    }

    /** 一次尝试结束：把阶段、正文、用量、耗时与失败原因补齐。 */
    public void finish(long id, String stage, String reasoning, String content, Integer promptTokens,
                       Integer completionTokens, Long elapsedMs, String error) {
        jdbc.update("UPDATE doc_template_ai_trace SET stage = :stage, reasoning = :reasoning, content = :content,"
                        + " prompt_tokens = :promptTokens, completion_tokens = :completionTokens,"
                        + " elapsed_ms = :elapsedMs, error = :error WHERE id = :id",
                new MapSqlParameterSource("id", id)
                        .addValue("stage", stage, Types.VARCHAR)
                        .addValue("reasoning", reasoning, Types.VARCHAR)
                        .addValue("content", content, Types.VARCHAR)
                        .addValue("promptTokens", promptTokens, Types.INTEGER)
                        .addValue("completionTokens", completionTokens, Types.INTEGER)
                        .addValue("elapsedMs", elapsedMs, Types.BIGINT)
                        .addValue("error", error, Types.VARCHAR));
    }

    public List<DocTemplateAiTraceEntity> findByImportId(long importId) {        return jdbc.query("SELECT " + COLUMNS + " FROM doc_template_ai_trace WHERE import_id = :importId"
                        + " ORDER BY attempt",
                new MapSqlParameterSource("importId", importId), ROW_MAPPER);
    }

    public List<Failure> findFailures(long importId) {
        return jdbc.query("SELECT attempt, stage, error, elapsed_ms FROM doc_template_ai_trace"
                        + " WHERE import_id = :importId AND error IS NOT NULL ORDER BY attempt",
                new MapSqlParameterSource("importId", importId), FAILURE_MAPPER);
    }

    public void deleteByImportId(long importId) {
        jdbc.update("DELETE FROM doc_template_ai_trace WHERE import_id = :importId",
                new MapSqlParameterSource("importId", importId));
    }

    /** 清理过期记录：启动时删一次，不引定时任务。 */
    public int deleteOlderThan(LocalDateTime before) {
        return jdbc.update("DELETE FROM doc_template_ai_trace WHERE create_time < :before",
                new MapSqlParameterSource("before", before));
    }

    /** 一次尝试的失败摘要。 */
    public record Failure(int attempt, String stage, String error, long elapsedMs) {
    }
}
