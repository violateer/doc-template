# 文件存储

存储和数据库一样，**模块不预设任何外部服务**。

| 配置 | 实际存储 | 依赖 |
| --- | --- | --- |
| 完全不配 `doc-template.storage` | 本地磁盘 `doc-template.base-path`（默认 `./data/doc-template`） | 无，开箱即跑 |
| `type: local` | 同上，可自定义 `base-path` | 无 |
| `type: s3` | S3 兼容存储（MinIO / RustFS / 阿里云 OSS 的 S3 接口） | 需要 endpoint、bucket、密钥 |

也就是说，宿主不配 storage 时模块照样能用，文件落在本地目录；要接对象存储，由**宿主自己**配这一小段：

```yaml
doc-template:
  storage:
    type: s3
    endpoint: http://127.0.0.1:9000 # MinIO/RustFS API endpoint，不是控制台端口
    region: us-east-1
    bucket: doc-template
    access-key: ${DOC_TEMPLATE_S3_ACCESS_KEY}
    secret-key: ${DOC_TEMPLATE_S3_SECRET_KEY}
    path-style-access: true
    prefix: doc-template
```

启动摘要里「文件存储」一行就是当前实际使用的存储：

```
  文件存储   本地磁盘 /app/data/doc-template（默认）
  文件存储   S3 兼容存储 http://127.0.0.1:9000 · bucket=doc-template · prefix=doc-template
```

## 使用前请确认

1. 数据库 schema 可用；启动时会自动检测并创建缺失的文档模板表（`doc-template.initialize-schema` 默认 `true`）。
2. 用 S3 时需提前创建 bucket（例如 `doc-template`）并给 access key 读写权限，**当前 Starter 不会自动建 bucket**。
3. 模板文件在 S3 模式下保存为对象 key，下载接口从 S3 拉取后回传，不要求浏览器直连 bucket。

## 切换存储方式之后

文件位置是**按记录落库的标识**：本地存储写相对路径（`doc-template/templates/6/xxx.docx`），S3 写 `s3://bucket/key`。所以改 `doc-template.storage.type` 只影响**之后**的上传，不会搬迁已有文件。

| 记录里的位置 | 当前配置 | 读取结果 |
| --- | --- | --- |
| `s3://...` | `type: local`（或不配） | 400，提示该文件在 S3 上、当前实例未启用 S3 存储 |
| 相对路径 | `type: s3` | 仍按本地磁盘读，找不到时提示文件不存在 |
| `s3://...` | `type: s3` | 正常读取 |

这三种情况都不会返回 500，错误信息会直接说明是缺配置还是缺文件。要让存量文件继续可用，要么补回原来的存储配置，要么重新上传模板文件。

删除不受影响：对象不可达时只记一条 warn 日志并跳过对象删除，数据库记录照常删除，避免配置错配导致记录永远删不掉。

## 对象生命周期

| 操作 | 存储侧行为 |
| --- | --- |
| 上传新文件 | 新建版本对象，旧版本对象保留 |
| 删除版本 | 删除对应对象 |
| 删除模板 | 删除该模板下全部版本对象 |
| 生成成功 | 写入 `generated/...` 前缀对象 |
| 删除生成记录 | 删除对应结果对象 |

不会遗留孤儿对象。

::: warning 存储配置独立
`doc-template.storage` 完全独立于宿主已有的 OSS/MinIO 配置，**不会自动复用宿主的对象存储客户端**。要用同一套存储，需要把宿主的 endpoint/bucket/密钥在这里再配一遍。
:::
