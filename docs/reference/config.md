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
| `login-page` | 空 | 未登录打开管理页时跳转的登录页。留空不跳转；站内路径（`/demo/login.html`）或完整地址（`https://sso.example.com/login`）都支持，跳转时自动追加 `redirect=<原地址>`。仅 `custom` 模式生效 |

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

## `doc-template.ai`

让模型读文档结构、给出标记计划（结构化标注的第 ② 步）。**默认关闭**：开启意味着文档正文会被发到
`base-url` 指向的服务。只支持 OpenAI Chat Completions 兼容协议，换服务商只改地址和模型名。

不针对具体厂商做适配：输出上限一次给足，超过服务商允许值时按它的报错自动降低；推理过程不认字段名
（`reasoning_content`、`reasoning`、`thinking` 都读）、正文是文本块数组时也能拼；服务商不认某个可选字段
（`response_format`、`temperature`）时自动去掉该字段重试一次。厂商私有开关走 `extra-body`。

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `enabled` | `false` | 关闭时 `/ai/plan` 直接说明怎么开，其余标注能力不受影响 |
| `base-url` | —— | 接口地址，通常写域名即可（`https://api.deepseek.com`）；写成 `.../v1` 或整条 `.../v1/chat/completions` 也能识别，不会拼重复 |
| `api-key` | —— | 密钥，建议用环境变量占位符 |
| `model` | `deepseek-chat` | 模型名 |
| `temperature` | `0` | 生成结构化产物建议 0：同样的输入要得到同样的计划。设为负数表示不发送该字段（部分推理模型不接受这个参数） |
| `max-tokens` | `32768` | 单次输出上限。推理模型的思考也计入这里，给小了正文还没开始就被截断（`finish_reason=length`）；它只决定模型能说多久，不按用量计费，模型想完会自己停。服务商自己的上限更小时（8K / 16K / 32K / 64K 不等），会按报错里的数值自动降下来。`0` 表示不发送、完全由服务端默认值决定，通常偏小 |
| `completions-path` | `/v1/chat/completions` | 兼容接口的路径 |
| `timeout-ms` | `120000` | 单次请求超时。推理模型的思考时间不可控，不要设得太小 |
| `json-mode` | `true` | 是否带 `response_format: {"type":"json_object"}`；不认这个字段的服务商会在 400 后被自动去掉，也可直接关掉 |
| `max-input-chars` | `60000` | 快照字符上限，超了从末尾丢内容（先段落、再表格、再工作表）并告警 |
| `max-attempts` | `3` | 含首次在内的最大尝试次数：自检没过就把错误回灌给模型重来 |
| `extra-body` | 空 | 原样并入请求体的厂商私有字段，用于关闭思考、调推理强度、传 `chat_template_kwargs` 这类只有某家认的开关。键名和取值以服务商文档为准，各家互不通用；`messages` 与 `stream` 不允许覆盖 |

宿主注册一个 `DocTemplateAiClient` Bean 就完全接管模型调用，这一整段配置都不参与。

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

  # ai 整段不配 = 不启用「让模型出标记计划」（推荐默认）
  ai:
    enabled: true
    base-url: https://api.deepseek.com
    api-key: ${AI_API_KEY}
    model: deepseek-chat
    max-attempts: 3
```
