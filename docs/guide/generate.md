# 生成与记录

## 调用方式

```http
POST /api/doc-template/templates/{id}/generate
Content-Type: application/json

{
  "templateVersionId": 12,
  "exportType": "PDF",
  "requestParams": {
    "reportNo": "QC2026001",
    "inspector": "张三"
  }
}
```

| 字段 | 说明 |
| --- | --- |
| `templateVersionId` | 可选，缺省用模板的 `current_version_id` |
| `exportType` | `ORIGINAL`（默认，输出 DOCX/XLSX）或 `PDF`；缺省取模板上的配置 |
| `requestParams` | 手填参数 + 给数据源传参（键名对应 `$request.xxx`） |

返回的就是一条生成记录，含 `status`、`result_file_url`、`error_message`。

## 执行顺序

```text
读模板文件 → 按 sort_order 执行 ENABLED 数据块 → 合并参数 → 渲染 →（PDF 转换）→ 存文件 → 写记录
```

- 数据块结果以**块编码**为命名空间：块编码 `report` + `single: true`，模板里写 `${report.report_no}`。
- 请求参数覆盖数据块结果，但**空串不覆盖** —— 避免模拟生成弹窗把所有键补空串、把取数结果冲掉。
- 任一数据块失败 → 整次 `FAILED`，不再往下渲染。

## 模拟生成

管理界面「模拟生成」的定位是「**按配置跑一遍**」：

- 右侧按 `sort_order` 列出该模板的数据块（编码、类型、SQL/URL/Provider 摘要、需要哪些参数、是否启用），点「开始生成」时后端就按这个顺序真正执行它们。
- 左侧的「数据源参数（JSON）」只用来**给数据源传参**：键名对应参数映射里 `$request.xxx` 的 `xxx`（例如 SQL 里的 `:reportNo`）。模板占位符由数据块结果自动填充，不需要写在这里。
- 数据块需要的参数没填时，弹窗会提前用黄色提示列出缺哪些键，避免 SQL 拿 null 查出 0 条却看不出原因。
- 模板里不由数据块提供的占位符（普通 `${key}`），仍然可以在同一个 JSON 里直接给值，「补齐参数」按钮会把这两类键一次性补上。

## 生成记录

| 接口 | 说明 |
| --- | --- |
| `GET /generate-records` | 不带 `page` 返回全量；带 `page` 返回 `{records,total,page,size,pages}`，支持 `keyword`（记录号/生成人/失败说明/模板名）、`status`、`size`（默认 20，上限 200） |
| `GET /generate-records/{id}/file` | 下载结果文件 |
| `GET /generate-records/{id}/preview` | 内联预览 |
| `DELETE /generate-records/{id}` | 删除记录，同步删除存储对象 |

- 每次生成在 `doc_template_generate_record` 留 `SUCCESS` / `FAILED` + 失败原因。
- 操作人来自认证层：`none` 模式是 `anonymous-user`（或 `user-header`），`custom` 模式来自 `DocTemplateAuthorizer`。
- 删除模板、删除版本、删除生成记录都会同步删除对象存储中的文件，不会遗留孤儿对象。

## 文件预览

预览走 `/preview` 接口（`Content-Disposition: inline`）：

- 管理界面用 docx-preview / SheetJS 在浏览器内渲染 DOCX、XLSX；
- PDF 直接内嵌。

::: warning iframe 预览不带 header
`/templates/versions/{id}/preview`、`/generate-records/{id}/preview` 会被浏览器/iframe 直接打开，**不会携带 `Authorization` 头**。宿主只认 header 时会 401 —— 开 Cookie 读取，或把前端改成 `fetch` + Blob 拉取后再预览。
:::

## 列表页约定

- 模板管理、生成记录都是**服务端分页**：模板默认每页 10 条、生成记录默认每页 20 条，可选 10/20/50/100。
- 关键字与状态过滤都在后端完成，切换过滤条件回到第 1 页，删空当前页会自动退回上一页。
- 页面整体不滚动：`html/body` 与侧边栏、顶栏都是固定高度，列表卡片用 flex 撑满剩余空间，表格通过 `flex-height` 在卡片内部滚动。
