# REST API

所有接口前缀 `/api/doc-template`，返回 JSON（字段为 `SNAKE_CASE`）。除 `/system/providers` 外均受认证拦截器保护。

## 模板

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/templates` | 不带 `page` 返回完整数组；带 `page` 返回 `{records,total,page,size,pages}`，支持 `size`（默认 20，上限 200）、`keyword`（名称/编码）、`status` |
| `GET` | `/templates/stats` | `{total,enabled,uploaded}` 总览统计，不受分页影响 |
| `GET` | `/templates/{id}` | 模板详情 |
| `POST` | `/templates` | 新建模板 |
| `PUT` | `/templates/{id}` | 更新模板，body `{templateCode?, templateName, exportType, status, remark?}`；`templateCode` 可改，需非空且全局唯一，不传则保持原值；`exportType` 只认 `ORIGINAL` / `PDF`（大小写不敏感），写别的直接报错 |
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
| `POST` | `/templates/{id}/generate` | 生成文档，body：`{templateVersionId?, exportType?, requestParams?}`；`exportType` 只认 `ORIGINAL` / `PDF`（大小写不敏感），写别的直接报错 |

## 结构化标注

把一份已经填好内容的文档变成「模板 + 数据块」。计划是一份 JSON，字段名固定驼峰（`rowTo`，不是 `row_to`），
原样回传给后端即可。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/templates/{id}/snapshot` | 当前版本的结构快照：段落、表格、单元格的坐标与文字，坐标一律 0 起始 |
| `POST` | `/templates/{id}/annotate/check` | 只校验不落库：落笔 + 生成模拟 SQL + 渲染一次，返回 `{ok, blocks, placeholders, unbound, suggestions, warnings, output_bytes}`；计划本身写错返回 400，坐标和文档对不上返回 `ok:false` + `error` |
| `POST` | `/templates/{id}/annotate/preview` | 下载预览；`?mode=template` 看落笔后的标记，默认看模拟数据的渲染效果 |
| `POST` | `/templates/{id}/annotate` | 自检通过后写新版本并按计划建数据块；当前版本已经标注过时直接拒绝 |
| `GET` | `/ai/status` | `{enabled, model, max_attempts, max_input_chars, trace_enabled}`，不含地址与密钥；未开启时 `enabled` 为 `false` |
| `POST` | `/templates/{id}/ai/plan` | 让模型读快照出计划并自检，body `{hint?}`；成功时把自检结果平铺进来并带 `plan`，失败返回 `ok:false` + `failures`（每次尝试的原因） |

::: tip 计划里的三类标记
`value`（取值）、`loop`（行循环）、`region`（区域循环）。`loop` / `region` 可带 `merge` / `by`；
坐标全部 0 起始，`cell` 是网格列号。标注只做单层循环：动态列和嵌套循环（循环套循环、按字段关联过滤）
不在范围内，计划里出现这两类标记会被跳过，不影响其余标记落笔——它们仍然可以在模板里手写，只是不由这条链路生成。
:::

## AI 导入任务

上传只产生一条任务，分析在后台线程里跑，模板、版本、数据块在最后一步一次建好；分析失败不会留下
「只有文件、没有数据块」的草稿。

任务状态：`PENDING`（已上传、未开始）/ `RUNNING` / `SUCCEEDED` / `FAILED` / `CANCELED`。
执行阶段按顺序推进：`SNAPSHOT`（读结构）→ `PROMPT`（拼提示词）→ `CALL`（调模型）→ `PARSE`（解析计划）→
`VALIDATE`（校验计划）→ `CHECK`（落笔自检）→ `APPLY`（建模板与数据块）→ `DONE`，每进一个阶段写一次库。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/ai/imports` | 新建任务（multipart：`file`、`templateName`、`templateCode?`、`hint?`）。`templateCode` 留空按文件名生成；**不建模板** |
| `GET` | `/ai/imports` | 分页列表，`page` / `size` / `status` / `keyword`（文件名、模板名、编码、发起人） |
| `GET` | `/ai/imports/{id}` | 详情：状态、阶段、阶段顺序、失败原因、计划原文、每次尝试的思维链 |
| `GET` | `/ai/imports/{id}/progress` | 轮询用的轻量进度：状态、阶段、思维链正文（含流式中间结果），不含提示词与计划原文 |
| `POST` | `/ai/imports/{id}/start` | 进队列后台执行，立即返回；`PENDING` / `FAILED` / `CANCELED` 可执行 |
| `POST` | `/ai/imports/{id}/cancel` | 未开始的直接取消；执行中的置取消标志并中断，下一次调模型前停下 |
| `POST` | `/ai/imports/{id}/rerun` | 重新分析：清掉上一次的阶段、计划、失败原因、耗时与思维链记录，再走一遍完整流程。已生成模板的任务结果写回原模板（新增版本、按新计划重建数据块）；分析失败时模板不变 |
| `DELETE` | `/ai/imports/{id}` | 删除任务（执行中的不可删），上传的文件与思维链一起清掉；**已生成的模板保留** |
| `POST` | `/ai/imports/batch-delete` | 批量删除，body `{"ids":[1,2]}`；逐条删，返回 `{deleted, failed:[{id, message}]}`。执行中的会出现在 `failed` 里 |
| `POST` | `/ai/imports/{id}/check` | 用改过的计划重新自检，body 与 `/annotate/check` 同一份计划 |
| `POST` | `/ai/imports/{id}/preview` | 下载预览；`?mode=template` 看落笔后的标记，默认看模拟数据的渲染效果 |
| `POST` | `/ai/imports/{id}/apply` | 按改过的计划建模板与数据块；已经生成过模板的任务不再接受 |

::: warning 单实例前提
取消、队列、残留任务恢复都按单实例设计：服务重启时残留的 `RUNNING` 一律标为失败（原因「服务重启，任务中断」），
不自动续跑。多实例部署时同一个任务可能被别的实例领走，需要宿主自己加分布式互斥。
:::

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
| `GET` | `/system/converters` | 可用的字段值转换器编码（内置 + 宿主注册），字符串数组；编码按注册时声明的写法原样返回，使用时代码也按原样匹配（区分大小写） |
| `GET` | `/system/version` | 当前运行的 Starter 版本号，`{"version":"0.3.0.1"}`；读不到版本时 `version` 为 `null` |

## 错误响应

| 场景 | 状态码 | 响应 |
| --- | --- | --- |
| 未认证（`mode: custom`） | `401` | `{"code":401,"msg":"..."}` |
| SQL 数据块失败 | `400` | 固定脱敏文案，数据库原文只进日志 |
| 其他未捕获异常 | `500` | 「服务内部错误，请查看服务端日志」 |

::: tip 时间格式
`LocalDateTime` 统一序列化为 `yyyy-MM-dd HH:mm:ss` 字符串，不会出现 `[2026,10,27,11,23,9]` 这种数组。
:::
