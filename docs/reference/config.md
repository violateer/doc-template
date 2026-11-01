# 配置项

全部配置挂在 `doc-template` 前缀下，对应 `DocTemplateProperties`。

## 顶层

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `doc-template.enabled` | `true` | 是否启用模块 |
| `doc-template.base-path` | `./data/doc-template` | 本地磁盘存储根目录（`storage.type: local` 时生效） |
| `doc-template.initialize-schema` | `true` | 启动时检测并创建缺失的表 |
| `doc-template.pdf-font` | 空 | PDF 导出用字体文件，留空自动探测 |
| `doc-template.pdf-font-name` | 空 | 字体族名，默认取文件名 |

## `doc-template.auth`

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `mode` | `none` | `none` / `custom`。`custom` 需要宿主注册 `DocTemplateAuthorizer` Bean，否则启动失败 |
| `anonymous-user` | `anonymous` | 识别不到操作人时写入生成记录的名称 |
| `user-header` | 空 | 仅 `none` 模式：从该请求头取操作人（网关已完成鉴权时使用） |

## `doc-template.datasource`

整段不配 = 复用宿主 `DataSource`。只有模板元数据要放独立库时才填。

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `url` | 空 | 独立模板库 JDBC URL |
| `driver-class-name` | 空 | 可省略，按 URL 推断 |
| `username` | 空 | — |
| `password` | 空 | — |
| `maximum-pool-size` | `5` | Hikari 池（Bean 名 `doc-template-pool`） |
| `connection-timeout` | `30000` | 毫秒 |

## `doc-template.storage`

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `type` | `local` | `local` / `s3` |
| `endpoint` | 空 | S3 API endpoint（不是控制台端口） |
| `region` | `us-east-1` | — |
| `bucket` | 空 | 需提前创建 |
| `access-key` | 空 | — |
| `secret-key` | 空 | — |
| `path-style-access` | `true` | MinIO/RustFS 通常需要 `true` |
| `prefix` | `doc-template` | 对象 key 前缀 |

## `doc-template.data-block`

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `enabled` | `true` | 关闭后生成时只用请求里手填的参数 |
| `sql-enabled` | `true` | 是否允许 SQL 数据源，**对外系统建议 `false`** |
| `default-max-rows` | `500` | SQL 数据块未配 `maxRows` 时的默认值 |
| `default-timeout-ms` | `5000` | API 数据块未配 `timeoutMs` 时的默认超时 |
| `max-timeout-ms` | `15000` | API 数据块允许的最大超时 |
| `allowed-hosts` | `[]` | 允许访问的接口主机，为空不限制；支持精确匹配与 `*.example.com` 后缀匹配 |

## 完整示例

```yaml
doc-template:
  enabled: true
  base-path: /app/data/doc-template
  initialize-schema: true
  pdf-font: /usr/share/fonts/simhei.ttf
  pdf-font-name: SimHei

  auth:
    mode: custom
    anonymous-user: anonymous

  # datasource 整段不配 = 复用宿主数据源（推荐）

  storage:
    type: s3
    endpoint: http://127.0.0.1:9000
    region: us-east-1
    bucket: doc-template
    access-key: ${DOC_TEMPLATE_S3_ACCESS_KEY}
    secret-key: ${DOC_TEMPLATE_S3_SECRET_KEY}
    path-style-access: true
    prefix: doc-template

  data-block:
    enabled: true
    sql-enabled: false
    default-max-rows: 500
    default-timeout-ms: 5000
    max-timeout-ms: 15000
    allowed-hosts:
      - api.internal.example.com
      - "*.example.com"
```
