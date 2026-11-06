---
layout: home

hero:
  name: Vio Doc Template
  text: 文档模板引擎 Starter
  tagline: 把 .docx / .xlsx 模板变成可配置的生成能力 —— 模板版本、数据块取数、占位符渲染、内置 PDF 导出、可插拔认证，一个依赖全给。
  actions:
    - theme: brand
      text: 快速开始
      link: /guide/quick-start
    - theme: alt
      text: 在线演示
      link: http://www.violateer.top:9150/doc-template
    - theme: alt
      text: 配置项
      link: /reference/config

features:
  - title: 模板即配置
    details: 普通 Word / Excel 文件，直接写 ${占位符} 和 {{#each 明细}}…{{/each}}，无需学习模板 DSL，业务人员自己就能改。
  - title: 三种取数方式
    details: JAVA Provider（最安全）、API 接口、SQL 查询，按推荐顺序选择；执行结果按块编码注入渲染参数。
  - title: 零外部依赖导出
    details: 内置 MiniPdf（纯 Java）导出 PDF，不需要装 Office / LibreOffice / WPS，中文字体自动探测。
  - title: 只认证不授权
    details: 一个 DocTemplateAuthorizer 接口就能接进宿主已有认证体系，Starter 不带任何认证框架依赖。
  - title: 自带管理界面
    details: 静态资源随 JAR 发布，/doc-template/ 即可用；宿主用 iframe 打开，也可以自己写页面调 API。
  - title: 开箱即跑
    details: 不自带 application.yml、不建连接池、不预设对象存储；复用宿主 DataSource，缺失的表自动创建。
---

::: tip 在线演示
不想装环境，直接打开 **[http://www.violateer.top:9150/doc-template](http://www.violateer.top:9150/doc-template)** 就能试：模板管理、数据块配置、模拟生成、PDF 导出都在真跑，四套示例模板也已经刷好。演示账号 `admin / Admin@123`（另有 `demo / Demo@123`）。

内置的四套示例模板在演示站上是**只读**的，想试完整流程请自己新建一个模板。
:::

## 发布物范围

本工程对外发布的只有两个模块：

| 模块 | 作用 |
| --- | --- |
| `doc-template-core` | 业务 SPI（`DocumentDataProvider`），零依赖，只含接口 |
| `doc-template-spring-boot-starter` | REST API、渲染引擎、数据块执行器、PDF 导出、认证 SPI、建表脚本、内嵌管理页 |

`doc-template-example` 是作者自用的示例宿主，用于验证两种认证模式和本地试跑，**不在发布包范围内**，其登录实现、示例配置等都不应照搬进生产宿主。内嵌管理页的源码在 `frontend/doc-template-ui`：源码不单独发包，构建产物打进 Starter 的静态资源，属于发布物的一部分。

## 使用前提

Starter 与任何业务框架、认证框架、权限体系都无关，也不要求宿主使用特定 ORM。只要满足四条就能直接用：

| 条件 | 说明 |
| --- | --- |
| JDK 17+ | 编译目标 `release=17` |
| Spring Boot 3.x / 4.x | Servlet Web 应用；自动装配同时兼容两代 Boot 的包路径，WebFlux 不支持 |
| 一个 `DataSource` Bean | 任何连接池都行，不配 `doc-template.datasource` 时复用宿主的 |
| 数据库 | MySQL 8 / MariaDB / PostgreSQL 12+ 可自动建表；其他库需自行执行建表 DDL |

不需要：任何 ORM、任何认证框架、权限码或菜单表、前端构建环境、Office / LibreOffice / WPS。

## 它解决什么问题

业务系统里总有一批「格式固定、内容变化」的文档：检验报告、对账单、出货单、合同附件。传统做法是每次改模板就发一次版，或者把 Word 排版逻辑硬编码进代码。这个 Starter 的做法是：

1. 把模板文件上传进来，占位符直接写在 Word / Excel 里；
2. 把取数逻辑配成数据块（Java Provider / API / SQL），生成时按顺序执行；
3. 一次调用完成渲染，输出 DOCX / XLSX，或者直接导出 PDF；
4. 每次生成留一条记录，失败原因可追。

```java
// 宿主侧调用（伪代码）
POST /api/doc-template/templates/{id}/generate
{ "requestParams": { "reportNo": "QC2026001" }, "exportType": "PDF" }
```

## 示例模板

四套开箱即用的模板，配套数据块 SQL 一起给，导入后就能验证占位符、明细行循环、递归渲染、竖向合并、字段值转换与字典、图片与二维码、PDF 导出：

| 模板 | 文件 | 数据块 | 覆盖的写法 |
| --- | --- | --- | --- |
| 综合示例（Word） | [word-showcase.docx](/demo/word-showcase.docx) | 6 个 SQL 块 + `demoProvider`(JAVA) + `demoApi`(API) + 3 本字典 | 占位符与默认值、手填参数、三种循环、嵌套与 `where`、竖向合并、动态列、转换与字典、图片与二维码、三种数据源 |
| 综合示例（Excel） | [excel-showcase.xlsx](/demo/excel-showcase.xlsx) | 6 个 SQL 块 + 3 本字典 | 整格数字与混排文本、行内与区域循环、嵌套、`merge` 两种列写法、动态列、转换与字典、图片与二维码、多 sheet |
| 正式验收报告（Word） | [word-acceptance-report.docx](/demo/word-acceptance-report.docx) | 20 个 SQL 块 | 交付级长文档、目录域、页眉页脚 |
| 正式验收报告（Excel） | [excel-acceptance-report.xlsx](/demo/excel-acceptance-report.xlsx) | 5 个 SQL 块 | 多 sheet、合并单元格、KPI 卡片 |

数据块脚本：[word-showcase.sql](/demo/word-showcase.sql)、[excel-showcase.sql](/demo/excel-showcase.sql)、[word-acceptance-report.sql](/demo/word-acceptance-report.sql)、[excel-acceptance-report.sql](/demo/excel-acceptance-report.sql)。导入步骤与逐项说明见[示例模板](/guide/examples)。
