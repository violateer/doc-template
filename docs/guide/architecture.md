# 工程架构

## 模块划分

```text
doc-template-engine/
├── backend/
│   ├── doc-template-core                  ✅ 发布物：SPI（零依赖契约层）
│   ├── doc-template-spring-boot-starter   ✅ 发布物：全部能力
│   └── doc-template-example               ❌ 示例宿主（真实数据库 + 本地磁盘 + 极简登录）
├── frontend/
│   └── doc-template-ui                    ✅ 内嵌管理页源码（build 产物随 Starter 发布）
├── integration/                            ❌ 集成说明（iframe / 代理 / 上线检查）
└── docs/                                  本 VitePress 站点
```

**发布物只有 `doc-template-core` 和 `doc-template-spring-boot-starter`。**
示例宿主只用于验证，不承诺兼容，也不属于对外 API；`frontend/doc-template-ui` 不单独发包，它的构建产物被打进 Starter，属于发布物的一部分。

## Starter 内部结构

| 包 | 职责 |
| --- | --- |
| `auth` | `AuthMode`、`DocTemplateAuthorizer`（SPI）、`DocTemplateGuard`（门面）、拦截器、401 异常 |
| `autoconfigure` | `DocTemplateProperties`、`DocTemplateAutoConfiguration`、数据源解析、`JdbcTemplate` |
| `controller` | 模板、数据块、生成记录、系统、页面路由、全局异常处理 |
| `entity` / `repository` | 六张表的实体，以及基于 `JdbcTemplate` 的读写（手写 SQL、手写行映射） |
| `service` | `DocTemplateService`（编排）、`DocTemplateRenderer`（渲染）、`DocTemplateDataBlockExecutor`（取数）、`DocTemplateFileStorage`、`DocTemplatePdfRenderer`、`DocTemplateImageLoader` |
| `support` | `DocTemplateJson`、`DocTemplateSqlValidator`、`DocTemplateImageSpec`、`DocTemplateSqlException` |

## 一次生成请求的链路

```text
POST /api/doc-template/templates/{id}/generate
  │
  ├─ DocTemplateAuthInterceptor   识别调用方（none → anonymous / custom → SPI）
  ├─ DocTemplateService.generate
  │    ├─ 解析 templateVersionId（缺省取 current_version_id）
  │    ├─ DocTemplateFileStorage.read(fileUrl)          读模板字节
  │    ├─ DocTemplateDataBlockExecutor.collect(blocks)  按 sort_order 依次执行
  │    │     ├─ JAVA → DocumentDataProvider#execute
  │    │     ├─ API  → HTTP（allowed-hosts / timeout 校验）
  │    │     └─ SQL  → 校验 → 只读连接 → 查询（失败脱敏）
  │    ├─ DocTemplateRenderer.render(字节, 类型, 参数, 图片字段)
  │    ├─ DocTemplatePdfRenderer.render  （exportType=PDF 时）
  │    ├─ DocTemplateFileStorage.storeGenerated
  │    └─ 写 doc_template_generate_record（SUCCESS / FAILED + 原因）
  └─ 返回记录（含 resultFileUrl）
```

## 数据表

启动时检测六张表，缺失则执行对应库的建表脚本（MySQL / MariaDB / PostgreSQL / SQL Server / Oracle），脚本可重复执行，**不会删除已有数据**。

| 表 | 关键字段 |
| --- | --- |
| `doc_template` | `template_code`(唯一)、`template_name`、`file_type`、`export_type`、`current_version_id`、`status` |
| `doc_template_version` | `template_id`、`version_no`、`file_name`、`file_url`、`file_sha256`，唯一键 `(template_id, version_no)` |
| `doc_template_data_block` | `template_id`、`block_code`、`data_source_type`、`data_source_config`、`param_config`、`sort_order`、`status`，唯一键 `(template_id, block_code)` |
| `doc_template_generate_record` | `template_id`、`template_version_id`、`export_type`、`request_params`、`result_file_url`、`status`、`error_message`、`create_by` |
| `doc_template_dict` | `dict_code`(唯一)、`dict_name`、`remark`，字典主表 |
| `doc_template_dict_item` | `dict_id`、`item_value`、`item_label`、`sort_order`，唯一键 `(dict_id, item_value)`，字典子表 |

`doc-template.initialize-schema: false` 可关闭自动建表，改由宿主的迁移流程管理。

## 技术选型

| 关注点 | 选择 | 说明 |
| --- | --- | --- |
| 持久层 | Spring JDBC | `JdbcTemplate` + 手写 SQL，分页用 `COUNT` + `LIMIT/OFFSET`；不依赖任何 ORM，宿主用什么都行 |
| JSON | Jackson | 输出 `SNAKE_CASE`，`LocalDateTime` 固定 `yyyy-MM-dd HH:mm:ss`；**不引入 Fastjson** |
| 工具库 | Hutool | 判空、文件名、摘要、类型转换 |
| SQL 解析 | JSqlParser 4.9 | 数据块只放行 `Select`；打包时重定位到 `io.github.violateer.doctemplate.internal.jsqlparser`，与宿主自带的 jsqlparser 互不干扰 |
| 文档渲染 | Apache POI | DOCX / XLSX 读写 |
| PDF | MiniPdf | 纯 Java，无命令行依赖 |
| 对象存储 | AWS SDK v2 S3 | 兼容 MinIO / RustFS / OSS 的 S3 接口 |

## 依赖边界（有意为之）

Starter 的 `resources` 里**没有任何 `application.yml`**，启动时也不会创建连接池：

1. 从宿主容器里拿现成的 `DataSource`，用它建 Mapper、建表、跑 SQL 数据块；
2. 存储不预设外部服务 —— 不配 `doc-template.storage` 就落本地磁盘。

因此打包出去的 jar 天然「零外部依赖配置」，宿主配什么它就用什么。同理，pom 里**没有任何认证框架依赖**，认证靠宿主实现 `DocTemplateAuthorizer`。
