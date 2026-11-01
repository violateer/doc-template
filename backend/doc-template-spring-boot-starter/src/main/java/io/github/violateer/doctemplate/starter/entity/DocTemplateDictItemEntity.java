package io.github.violateer.doctemplate.starter.entity;

import java.time.LocalDateTime;

/**
 * 数据字典子表：一行就是「某个字典下的一个取值 → 显示名」。
 *
 * <p>同一个 {@code dictId} 下 {@code itemValue} 唯一；{@code sortOrder} 决定管理页与下拉的顺序。</p>
 */
public class DocTemplateDictItemEntity {
    private Long id;
    private Long dictId;
    private String itemValue;
    private String itemLabel;
    private Integer sortOrder;
    private LocalDateTime createTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getDictId() { return dictId; }
    public void setDictId(Long dictId) { this.dictId = dictId; }
    public String getItemValue() { return itemValue; }
    public void setItemValue(String itemValue) { this.itemValue = itemValue; }
    public String getItemLabel() { return itemLabel; }
    public void setItemLabel(String itemLabel) { this.itemLabel = itemLabel; }
    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
}
