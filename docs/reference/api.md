# REST API

所有接口前缀 `/api/doc-template`，返回 JSON（字段为 `SNAKE_CASE`）。除 `/system/providers` 外均受认证拦截器保护。

## 模板

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/templates` | 不带 `page` 返回完整数组；带 `page` 返回 `{records,total,page,size,pages}`，支持 `size`（默认 20，上限 200）、`keyword`（名称/编码）、`status` |
| `GET` | `/templates/stats` | `{total,enabled,uploaded}` 总览统计，不受分页影响 |
| `GET` | `/templates/{id}` | 模板详情 |
| `POST` | `/templates` | 新建模板 |
| `PUT` | `/templates/{id}` | 更新模板，body `{templateCode?, templateName, exportType, status, remark?}`；`templateCode` 可改，需非空且全局唯一，不传则保持原值 |
| `DELETE` | `/templates/{id}` | 删除模板（连带删除文件与版本） |
| `GET` | `/templates/{id}/placeholders` | 列出模板文件里的 `${placeholder}` |

## 文件与版本

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/templates/{id}/file` | 上传文件（`multipart/form-data`，字段名 `file`） |
| `GET` | `/templates/{id}/versions` | 版本列表 |
| `PUT` | `/templates/{id}/versions/{versionId}/current` | 把历史版本切换为当前版本 |
| `DELETE` | `/templates/{id}/versions/{versionId}` | 删除版本（连带删存储对象） |
| `GET` | `/templates/versions/{versionId}/download` | 下载（`Content-Disposition: attachment`） |
| `GET` | `/templates/versions/{versionId}/preview` | 内联预览（`inline`，`Cache-Control: no-store`） |

## 生成

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/templates/{id}/generate` | 生成文档，body：`{templateVersionId?, exportType?, requestParams?}` |

## 数据块

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/data-blocks?templateId={id}` | 列表 |
| `POST` | `/data-blocks?templateId={id}` | 新建 |
| `POST` | `/data-blocks/preview` | 按请求里的配置执行一次取数（不落库），返回 `{ok,data|error,elapsed_ms}` |
| `PUT` | `/data-blocks/{id}` | 更新，body 同新建；`blockCode` 可改，需在同一模板内唯一，不传则保持原值 |
| `DELETE` | `/data-blocks/{id}` | 删除 |
| `POST` | `/data-blocks/batch-delete` | 批量删除，body `{ids:[...]}`，返回 `{deleted,failed:[{id,message}]}` |

## 数据字典

字典是全局资源（不属于某个模板），主子表结构：主表配字段编码，子表配 `值 → 显示名` 的映射，供 `dict` 转换器使用。

**字典（主表）**

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/dicts?keyword=` | 字典列表，每行带 `item_count`；`keyword` 匹配编码 / 名称 / 备注 |
| `GET` | `/dicts/{id}` | 字典详情，额外带 `items` 数组 |
| `POST` | `/dicts` | 新增字典，body `{dictCode, dictName, remark?}` |
| `PUT` | `/dicts/{id}` | 修改字典，`dictCode` 需保持全局唯一 |
| `DELETE` | `/dicts/{id}` | 删除字典，**连带删除其下全部映射**，返回 `{success:true}` |
| `POST` | `/dicts/batch-delete` | 批量删除，body `{ids: []}`，上限 200 |

**字典项（子表）**

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/dicts/{id}/items` | 该字典的映射，按 `sort_order`、`id` 排序；不带 `page` 返回全量，带 `page` 返回 `{records,total,page,size,pages}`，支持 `size`（默认 20，上限 200） |
| `POST` | `/dicts/{id}/items` | 新增映射，body `{itemValue, itemLabel, sortOrder?}`，`sortOrder` 不传自动追加到末尾 |
| `PUT` | `/dicts/items/{itemId}` | 修改单条映射 |
| `DELETE` | `/dicts/items/{itemId}` | 删除单条映射 |
| `POST` | `/dicts/items/batch-delete` | 批量删除映射，body `{ids: []}`，上限 200 |

同一本字典内 `item_value` 不能重复（`(dict_id, item_value)` 唯一），不同字典之间互不影响。

## 生成记录

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/generate-records` | 不带 `page` 返回全量；带 `page` 返回分页，支持 `keyword`（记录号/生成人/失败说明/模板名）、`status`、`size` |
| `GET` | `/generate-records/{id}/file` | 下载结果文件 |
| `GET` | `/generate-records/{id}/preview` | 内联预览 |
| `DELETE` | `/generate-records/{id}` | 删除记录（连带删存储对象） |

## 系统

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/system/providers` | 已注册的 `DocumentDataProvider` 列表，数据块 `JAVA` 类型下拉项来源 |
| `GET` | `/system/data-sources` | SQL 数据块可用的数据源（`dataSource` 字段下拉项） |
| `GET` | `/system/converters` | 可用的字段值转换器编码（内置 + 宿主注册），字符串数组 |

## 错误响应

| 场景 | 状态码 | 响应 |
| --- | --- | --- |
| 未认证（`mode: custom`） | `401` | `{"code":401,"msg":"..."}` |
| SQL 数据块失败 | `400` | 固定脱敏文案，数据库原文只进日志 |
| 其他未捕获异常 | `500` | 「服务内部错误，请查看服务端日志」 |

::: tip 时间格式
`LocalDateTime` 统一序列化为 `yyyy-MM-dd HH:mm:ss` 字符串，不会出现 `[2026,10,27,11,23,9]` 这种数组。
:::
