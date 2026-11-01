package io.github.violateer.doctemplate.starter.entity;

import java.time.LocalDateTime;

/**
 * 数据字典主表：一本字典一行。
 *
 * <p>{@code dictCode} 是转换器里引用的名字（全局唯一），{@code dictName} 只用于管理页展示；
 * 具体「值 → 显示名」的映射在子表 {@link DocTemplateDictItemEntity} 里。</p>
 */
public class DocTemplateDictEntity {
    private Long id;
    private String dictCode;
    private String dictName;
    private String remark;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    /** 子表项数，只在列表查询里回填，不落库 */
    private Integer itemCount;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getDictCode() { return dictCode; }
    public void setDictCode(String dictCode) { this.dictCode = dictCode; }
    public String getDictName() { return dictName; }
    public void setDictName(String dictName) { this.dictName = dictName; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
    public Integer getItemCount() { return itemCount; }
    public void setItemCount(Integer itemCount) { this.itemCount = itemCount; }
}
