package io.github.violateer.doctemplate.starter.entity;

import java.time.LocalDateTime;

/**
 * 一次导入里某一次尝试的完整记录：提示词、思考过程、回答原文、用量、失败原因。
 *
 * <p>提示词里是文档全文，可能含业务数据，所以整张表可以用
 * {@code doc-template.ai.trace-enabled=false} 整体关掉。</p>
 */
public class DocTemplateAiTraceEntity {
    private Long id;
    private Long importId;
    private Integer attempt;
    private String stage;
    private String model;
    private String systemPrompt;
    private String userPrompt;
    private String reasoning;
    private String content;
    private Integer promptTokens;
    private Integer completionTokens;
    private Long elapsedMs;
    private String error;
    private LocalDateTime createTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getImportId() { return importId; }
    public void setImportId(Long importId) { this.importId = importId; }
    public Integer getAttempt() { return attempt; }
    public void setAttempt(Integer attempt) { this.attempt = attempt; }
    public String getStage() { return stage; }
    public void setStage(String stage) { this.stage = stage; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getSystemPrompt() { return systemPrompt; }
    public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }
    public String getUserPrompt() { return userPrompt; }
    public void setUserPrompt(String userPrompt) { this.userPrompt = userPrompt; }
    public String getReasoning() { return reasoning; }
    public void setReasoning(String reasoning) { this.reasoning = reasoning; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public Integer getPromptTokens() { return promptTokens; }
    public void setPromptTokens(Integer promptTokens) { this.promptTokens = promptTokens; }
    public Integer getCompletionTokens() { return completionTokens; }
    public void setCompletionTokens(Integer completionTokens) { this.completionTokens = completionTokens; }
    public Long getElapsedMs() { return elapsedMs; }
    public void setElapsedMs(Long elapsedMs) { this.elapsedMs = elapsedMs; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
}
