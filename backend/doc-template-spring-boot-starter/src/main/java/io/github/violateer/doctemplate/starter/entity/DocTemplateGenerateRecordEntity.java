package io.github.violateer.doctemplate.starter.entity;

import java.time.LocalDateTime;

public class DocTemplateGenerateRecordEntity {
    private Long id;
    private Long templateId;
    private Long templateVersionId;
    private String exportType;
    private String requestParams;
    private String resultFileUrl;
    private String status;
    private String errorMessage;
    private String createBy;
    private LocalDateTime createTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTemplateId() { return templateId; }
    public void setTemplateId(Long templateId) { this.templateId = templateId; }
    public Long getTemplateVersionId() { return templateVersionId; }
    public void setTemplateVersionId(Long templateVersionId) { this.templateVersionId = templateVersionId; }
    public String getExportType() { return exportType; }
    public void setExportType(String exportType) { this.exportType = exportType; }
    public String getRequestParams() { return requestParams; }
    public void setRequestParams(String requestParams) { this.requestParams = requestParams; }
    public String getResultFileUrl() { return resultFileUrl; }
    public void setResultFileUrl(String resultFileUrl) { this.resultFileUrl = resultFileUrl; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public String getCreateBy() { return createBy; }
    public void setCreateBy(String createBy) { this.createBy = createBy; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
}
