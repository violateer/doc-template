# 变更日志

> 只记录**发布物**的变化：`doc-template-core` 与 `doc-template-spring-boot-starter`。
>
> 写法约定：**按功能写，不写实现**。不出现类名、方法名、表名、SQL、配置项内部结构，
> 只说「使用方能感知到什么变了」。判断标准是：宿主升级后需要做什么、能多用出什么能力。
>
> 不进这里的：`doc-template-example`（示例宿主，不在包范围）、`frontend/doc-template-ui` 源码、
> 文档站、部署脚本——这些随主仓提交即可。
>
> 格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循语义化版本。

## [Unreleased]

暂无。下一个版本发布前，把 core / starter 的功能变化补在这里，发版时整段改成版本号 + 日期。

## [0.1.0] - 2026-11-01

首次发布。Starter 与业务框架、认证框架、ORM 均无耦合，引入依赖即生效，无需 `@EnableXxx`。

### 新增

- **模板管理**：模板元数据增删改查，服务端分页；模板文件上传、多版本管理、版本切换、下载与在线预览；占位符探测。
- **文档渲染**：DOCX / XLSX 占位符替换；明细行循环、嵌套循环、段落块循环；行内单行循环；按主键或按文本的竖向合并；图片字段渲染（支持对齐与内边距）。
- **数据块取数**：JAVA（宿主 Provider）/ API / SQL 三种方式；参数映射与请求级取值；SQL 走语句白名单校验、只读连接与错误脱敏；API 支持 URL 与请求体传参、结果路径提取、主机白名单。
- **生成与导出**：模拟生成、生成记录与结果文件留存；内置 PDF 导出，不依赖 Office / LibreOffice / WPS，中文使用宿主机字体。
- **字段值转换**：数据块整列批量转换与模板占位符内联转换两种入口；内置日期、数字、百分比、脱敏、布尔、文本、字典等常用转换器；支持宿主自定义转换器。
- **数据字典**：主子表结构（编码 → 值 → 显示名），配合字典转换器使用，单次生成内复用查询结果。
- **可插拔认证**：`none`（默认，不校验）与 `custom`（宿主提供当前用户）两种模式；只识别调用方身份，不做权限码校验。
- **运行接入**：复用宿主 `DataSource` 或配置独立库；MySQL 8 / MariaDB 自动建表；文件存储支持本地磁盘与 S3 兼容对象存储；同时兼容 Spring Boot 3.x / 4.x 的 Servlet Web 应用。
- **内嵌管理界面**：模板、数据块、字典、生成记录的管理页随 JAR 发布，可 iframe 嵌入或同源部署。

[Unreleased]: https://github.com/violateer/doc-template/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/violateer/doc-template/releases/tag/v0.1.0
