package io.github.violateer.doctemplate.starter.entity;

import java.time.LocalDateTime;

public class DocTemplateDataBlockEntity {
    private Long id;
    private Long templateId;
    private String blockCode;
    private String blockName;
    private String dataSourceType;
    private String dataSourceConfig;
    private String paramConfig;
    private Integer sortOrder;
    private String status;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTemplateId() { return templateId; }
    public void setTemplateId(Long templateId) { this.templateId = templateId; }
    public String getBlockCode() { return blockCode; }
    public void setBlockCode(String blockCode) { this.blockCode = blockCode; }
    public String getBlockName() { return blockName; }
    public void setBlockName(String blockName) { this.blockName = blockName; }
    public String getDataSourceType() { return dataSourceType; }
    public void setDataSourceType(String dataSourceType) { this.dataSourceType = dataSourceType; }
    public String getDataSourceConfig() { return dataSourceConfig; }
    public void setDataSourceConfig(String dataSourceConfig) { this.dataSourceConfig = dataSourceConfig; }
    public String getParamConfig() { return paramConfig; }
    public void setParamConfig(String paramConfig) { this.paramConfig = paramConfig; }
    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
