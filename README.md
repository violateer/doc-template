# Vio Doc Template

面向 Spring Boot 3 / 4、Java 17 与 Vue 3 + Naive UI 的可复用文档模板引擎工程。Starter 的静态管理界面随 JAR 发布，宿主前端可以通过 iframe 打开；REST API 的认证是可插拔的（无认证 / 宿主已有认证体系桥接进来），模块不另建登录系统，也不做权限码校验。

## 文档

`docs/` 是 VitePress 文档站，按模块拆分的指南与参考，本地启动方式见下。

发布物只有 `doc-template-core` 与 `doc-template-spring-boot-starter`。`backend/doc-template-example` 是作者自用的示例宿主，**不在包范围内**；`frontend/doc-template-ui` 是内嵌管理页的源码，**产物随 Starter JAR 发布**，源码本身不单独发包。

```bash
cd docs
npm install
npm run dev      # http://localhost:5174
npm run build    # 产物在 docs/.vitepress/dist
```

## 使用前提

Starter 与任何业务框架、认证框架、权限体系都无关，也不要求宿主使用特定 ORM。只要满足下面四条就能直接用：

| 条件 | 说明 |
| --- | --- |
| JDK 17+ | 编译目标 `release=17` |
| Spring Boot 3.x 或 4.x | Servlet Web 应用；自动装配同时兼容两代 Boot 的包路径，WebFlux 不支持 |
| 一个 `DataSource` Bean | 任何连接池都行；不配 `doc-template.datasource` 时复用宿主的 |
| 数据库 | MySQL 8 / MariaDB 可自动建表；其他库需自行执行建表 DDL |

不需要的东西：不需要任何 ORM（MyBatis / MyBatis-Plus / JPA 用不用、什么版本都不影响）、不需要任何认证框架、不需要权限码或菜单表、不需要前端构建环境、不需要 Office / LibreOffice / WPS。

引入依赖后自动生效，无需 `@EnableXxx`：

```xml
<dependency>
  <groupId>io.github.violateer</groupId>
  <artifactId>doc-template-spring-boot-starter</artifactId>
  <version>0.1.0</version>
</dependency>
```

> Starter 的 classpath 需求只有 Spring Web、Spring JDBC 和一个 `DataSource`：不传递任何 ORM 依赖，也不传递 JSqlParser（SQL 校验用的那份被重定位进自己的包内）。所以宿主用不用 MyBatis-Plus、用哪个版本，都不会和这个模块互相影响。

## 工程结构

- `backend/doc-template-core`：业务 Provider SPI（`DocumentDataProvider`），零依赖。
- `backend/doc-template-spring-boot-starter`：模板、版本、数据块、生成记录 API，文件上传/下载，可插拔认证，Starter 静态页面与 MySQL schema。
- `backend/doc-template-example`：独立示例应用，连真实 MySQL 并自动建表，并提供仅供本地示例使用的 `/demo/login`。
- `frontend/doc-template-ui`：内嵌管理页源码（Vue 3 + Naive UI）。`vite build` 的输出目录直接指向 Starter 的 `META-INF/resources/doc-template/`，产物随 JAR 发布。
- `integration/README.md`：宿主集成、认证接入与代理说明。

## 数据持久层

六张元数据表的读写全部走 Spring JDBC（`JdbcTemplate` + 手写 SQL），不引入任何 ORM：SQL 与行映射都在 `io.github.violateer.doctemplate.starter.repository` 下，分页用 `COUNT` + `LIMIT/OFFSET` 自己算，不依赖任何分页插件。建表、缺表检测同样走 Spring JDBC。

这样做的好处是宿主零要求：只要容器里有一个 `DataSource`，宿主用不用 MyBatis-Plus、版本是 3.5.x 还是别的，都不会和这个模块互相干扰。Starter 始终绑定自己解析出的数据源（宿主主库，或 `doc-template.datasource.url` 指定的独立库），不会碰宿主的数据访问层。

## 序列化与工具库

JSON 序列化统一使用 Jackson（Spring 官方内置）；**项目不引入 Fastjson**，也没有任何 `com.alibaba.fastjson` 依赖。实体转 Map、参数解析都走 `support/DocTemplateJson`：

- 输出使用 `SNAKE_CASE` 命名，与前端字段一致；
- `LocalDateTime` 固定序列化为 `yyyy-MM-dd HH:mm:ss` 字符串，不会退化成 `[2026,10,27,11,23,9]` 这种时间戳数组；
- 需要定制时可通过 `DocTemplateJson.mapper()` 拿到同一个 `ObjectMapper`。

通用工具方法使用 [Hutool](https://hutool.cn/)（`hutool-core` + `hutool-crypto`），不再手写判空、摘要、文件名解析等代码：

| 场景 | 使用的方法 |
| --- | --- |
| 字符串判空/默认值/去斜杠 | `StrUtil.isBlank`、`StrUtil.blankToDefault`、`StrUtil.strip` |
| 文件扩展名、文件名、读取 | `FileNameUtil.extName`、`FileUtil.getName`、`FileUtil.readBytes` |
| 对象唯一键 | `IdUtil.fastSimpleUUID` |
| 文件摘要 | `DigestUtil.sha256Hex` |
| 类型转换 | `Convert.toStr`、`Convert.toInt` |
| 异常信息提取 | `ExceptionUtil.getRootCauseMessage` |

## 后端构建

需要 JDK 17+、Maven 3.8+。

```bash
cd backend
mvn clean package
```

本地示例启动（example 不自带内存库，必须带 `dev` 或 `local`；dev 需要先设好 `DOC_DB_*` / `DOC_S3_*` 环境变量）：

```bash
mvn -pl doc-template-example -am spring-boot:run -Dspring-boot.run.profiles=local
```

打开 `http://localhost:8080/doc-template/`。示例默认开启认证，首次访问会跳到 `/demo/login.html`；该演示登录只用于 example 模块，切勿复制到生产宿主。

检查 Provider：`GET /api/doc-template/system/providers`。

## 前端构建并嵌入 Starter

内嵌管理页的源码在 `frontend/doc-template-ui`，构建产物直接写进 Starter。需要 Node.js 20+、pnpm 9+。在仓库根目录 `frontend` 执行：

```bash
pnpm install
pnpm --filter doc-template-frontend-ui build
```

Vite 会把构建产物输出到 Starter 的 `src/main/resources/META-INF/resources/doc-template/`。随后重新执行 `mvn clean package`，静态前端就会打进 Starter JAR。Starter 里只有这一份构建产物，没有手写的静态兜底页。

> 排查提示：侧边栏底部会显示「构建 yyyy-MM-dd HH:mm:ss」，这是 `vite.config.ts` 用 `define` 注入的构建时刻。如果改完前端却看不到变化，先看这个时间戳——没变就说明浏览器加载的还是旧包，需要重新 `vite build` 并重新打包重启（`mvn spring-boot:run` 用的是 `target/classes`，只重启不重新打包同样不会更新）。

## API

- `GET/POST /api/doc-template/templates`
  - `GET` 不带 `page` 时返回完整数组（兼容旧调用）；带 `page` 时返回 `{records,total,page,size,pages}`，支持 `size`（默认 20，上限 200）、`keyword`（模板名称 / 编码）、`status`
- `GET /api/doc-template/templates/stats`：模板总览统计 `{total,enabled,uploaded}`，供列表页统计卡片使用（不受分页影响）
- `GET/PUT/DELETE /api/doc-template/templates/{id}`
- `POST /api/doc-template/templates/batch-delete`：批量删除模板，请求体 `{"ids":[1,2,3]}`（上限 200 条）。逐条删、单条失败不影响其他条，返回 `{deleted, failed:[{id,message}]}`
- `POST /api/doc-template/templates/{id}/file`
- `GET /api/doc-template/templates/{id}/versions`
- `DELETE /api/doc-template/templates/{id}/versions/{versionId}`
- `GET /api/doc-template/templates/versions/{versionId}/download`
- `GET /api/doc-template/templates/versions/{versionId}/preview`
- `GET /api/doc-template/templates/{id}/placeholders`
- `POST /api/doc-template/templates/{id}/generate`
- `GET/POST /api/doc-template/data-blocks?templateId={id}`
- `POST /api/doc-template/data-blocks/preview`：按请求里的配置跑一次取数（不落库），返回 `{ok,data|error,elapsed_ms}`
- `PUT/DELETE /api/doc-template/data-blocks/{id}`
- `POST /api/doc-template/data-blocks/batch-delete`：批量删除数据块，请求体与返回结构同模板的批量删除
- `GET /api/doc-template/dicts?keyword=`：字典主表列表（每行带 `item_count`），`POST` 新增、`PUT/DELETE /{id}` 修改 / 删除（连带子表）
- `GET/POST /api/doc-template/dicts/{id}/items`：字典项（子表）列表 / 新增；`PUT/DELETE /api/doc-template/dicts/items/{itemId}` 改 / 删单条
- `POST /api/doc-template/dicts/batch-delete`、`POST /api/doc-template/dicts/items/batch-delete`：两张表各自的批量删除
- `GET/POST /api/doc-template/generate-records`
  - `GET` 不带 `page` 时返回完整数组（兼容旧调用）；带 `page` 时返回 `{records,total,page,size,pages}`，支持 `size`（默认 20，上限 200）、`keyword`（记录号 / 生成人 / 失败说明 / 模板名称）、`status`
- `GET /api/doc-template/generate-records/{id}/file`
- `GET /api/doc-template/generate-records/{id}/preview`
- `DELETE /api/doc-template/generate-records/{id}`
- `POST /api/doc-template/generate-records/batch-delete`：批量删除生成记录，请求体与返回结构同模板的批量删除
- `GET /api/doc-template/system/providers`
- `GET /api/doc-template/system/converters`：可用的字段值转换器编码（内置 + 宿主注册）

认证接入见 `integration/README.md`。默认启动时会检测文档模板所需的六张表；若存在缺失，则自动执行对应数据库建表脚本（MySQL/MariaDB），不会删除已有数据。

## 模拟生成与文件预览

模板文件里能写哪些标记、怎么写，见 [模板标记写法](#模板标记写法)；这一节只说明生成流程。

- 管理界面「模拟生成」的定位是「**按配置跑一遍**」：右侧按 `sort_order` 列出该模板的数据块（编码、类型、SQL/URL/Provider 摘要、需要哪些参数、是否启用），点「开始生成」时后端就按这个顺序真正执行它们，结果直接填进模板占位符。
- 左侧的「数据源参数（JSON）」只用来**给数据源传参**：键名对应参数映射里 `$request.xxx` 的 `xxx`（例如 SQL 里的 `:reportNo`）。模板占位符由数据块结果自动填充，不需要写在这里。
- 数据块需要的参数没填时，弹窗会提前用黄色提示列出缺哪些键，避免 SQL 拿 null 查出 0 条却看不出原因。
- 模板里不由数据块提供的占位符（普通 `${key}`），仍然可以在同一个 JSON 里直接给值，`补齐参数` 按钮会把这两类键一次性补上。
- 生成结果写入存储（`templates/...` 同级 `generated/...` 前缀），并在 `doc_template_generate_record` 中留下 `SUCCESS`/`FAILED` 记录与失败原因。
- 文件预览走 `/preview` 接口（`Content-Disposition: inline`），管理界面用 docx-preview / SheetJS 在浏览器内渲染 DOCX、XLSX，PDF 直接内嵌。
- 删除模板、删除版本、删除生成记录都会同步删除对象存储中的文件，不会遗留孤儿对象。
- 列表页（模板管理、生成记录）都是**服务端分页**：模板默认每页 10 条、生成记录默认每页 20 条，可选 10/20/50/100，关键字与状态过滤都在后端完成，切换过滤条件回到第 1 页，删空当前页会自动退回上一页。
- 页面整体不滚动：`html/body` 与侧边栏、顶栏都是固定高度，列表卡片用 flex 撑满剩余空间，表格通过 `flex-height` 在卡片内部滚动（表头和分页器保持可见）。

### PDF 导出

PDF 由内置引擎直接生成，**不需要安装 Office、LibreOffice 或 WPS**，也没有额外的命令行依赖。转换使用开源组件 [MiniPdf](https://github.com/mini-software/MiniPdf)（Apache-2.0，纯 Java，Maven 坐标 `io.github.mini-software:minipdf`），它基于 POI + PDFBox 自行排版，支持 DOCX / XLSX / PPTX。

PDF 标准字体不含中文字形，因此 Starter 在首次导出时会自动从系统里挑一个中文字体注册给转换引擎（Windows 依次尝试 `simhei.ttf`、`Deng.ttf`、`msyh.ttc`、`simsun.ttc`；macOS / Linux 依次尝试宋体、苹方、文泉驿、Noto CJK）。如需指定字体：

```yaml
doc-template:
  pdf-font: C:/Windows/Fonts/simhei.ttf   # 可选，留空自动探测
  pdf-font-name: SimHei                   # 可选，字体族名，默认取文件名
```

> 容器化部署时请确保镜像内存在上述任一字体文件（例如把 `simhei.ttf` 一起打进镜像），否则中文会缺字。
>
> MiniPdf 面向实用文档转换，并非完整的 Office 版式兼容实现：常规段落、表格、图片能较好还原，极复杂的版式（文本框叠放、艺术字等）可能与 Word 略有差异。

Starter 在把文件交给 MiniPdf 之前做了两处预处理，让导出的 PDF 尽量和原文件一致：

- **DOCX 保留样式**：MiniPdf 的公开入口对「一个表格都没有」的 DOCX 会退化成纯文本输出（全部 11pt、左对齐、丢粗体和居中）。Starter 改为直接走它内部的结构化渲染通道，字号、加粗、对齐、颜色、图片、页眉页脚都能保留，无论文档里有没有表格；同时按 DOCX 里的 `sectPr` 还原纸张尺寸（A4 / A3 / 自定义 / 横向）。
- **XLSX 跳过空 sheet**：工作簿里留着但完全没有内容的 sheet（无单元格值、无合并区域、无图片）会在转换前被剔除，不会在 PDF 里多出空白页；至少保留一个 sheet，预处理失败时按原文件转换，不影响导出。

## 模板标记写法

模板文件就是普通的 `.docx` / `.xlsx`，标记直接写在文档里，生成时由内置渲染器替换。两种模板共用一套标记语法，区别只在「能写在哪里」。

### 通用占位符

| 写法 | 含义 | 示例 |
| --- | --- | --- |
| `${key}` | 取参数 `key` 的值 | `${report_no}` → `QC2026001` |
| `{{key}}` | 同上，两种写法完全等价 | `{{report_no}}` |
| `${key:默认值}` | 取不到值（键不存在或值为 `null`）时用默认值 | `${inspector:未填写}` |
| `${key\|type:参数}` | 值转换：日期格式化、千分位、字典换名… | `${amount\|number:2}` → `12,345.00` |
| `${a.b.c}` | 按点号逐级取嵌套值 | `${customer.name}` |
| `${块编码.字段}` | 取数据块结果（数据块以块编码做命名空间） | `${report.product_name}` |

- 花括号内的空格会被忽略，`${ report_no }` 与 `${report_no}` 等价。
- 键名**大小写不敏感**：模板写 `${Report_No}` 也能匹配参数里的 `report_no`。
- 默认值只在「键不存在」或「值为 `null`」时生效；值本身是空串时按空串输出，不会用默认值。
- 值的渲染规则：`null` → 默认值或空串；数字去掉多余的 0（`1.50` → `1.5`、`2.0` → `2`）；对象 / 数组输出成 JSON 字符串。
- 转换链写在默认值之后、用 `|` 分隔，可串联：`${amount:0|number:2}`、`${status|dict(order_status)}`。

### 明细行循环

数组通常来自 `single: false` 的数据块，用 `{{#each 数组}}` 标记重复（也接受 `${#each 数组}`，推荐前者）：

| 位置 | 写法 | 行为 |
| --- | --- | --- |
| DOCX 表格行 | 该行任意单元格写 `{{#each items}}` | 整行按数组重复，**单标记隐式闭合**，不需要 `{{/each}}` |
| DOCX 表格区域 | `{{#each items}}` / `{{/each}}` 各独占一行 | 两行之间的**所有行**整体重复 |
| DOCX 段落块 | `{{#each items}}` 独占一段、`{{/each}}` 独占一段 | 两段之间的段落整体重复 |
| XLSX 行 | 该行任意单元格写 `{{#each items}}` | 整行复制（值 + 样式），向下插入 |
| XLSX 区域 | `{{#each items}}` / `{{/each}}` 各独占一行 | 之间的行整体重复 |

数组表达式支持点号路径，例如 `{{#each report.items}}`。

循环体内取当前项：

- `${product_name}` 直接取当前项的字段，**当前项优先于外层参数**；
- `${item.product_name}` 等价写法，字段名与外层参数重名时用它更安全；
- 数组为空（或值为 `null`）时：表格行 / Excel 行整行删除，段落块整块清空；
- 数组变量**根本不存在**时直接判定生成失败，错误信息会列出当前可用的参数，避免「行莫名其妙没了」却查不出原因。

限制：段落块循环只处理段落，跨表格的块循环不支持；Excel 行循环不复制合并单元格。

循环体里的字段也可以声明成图片字段（见[图片字段](#图片字段)），明细行里的图片会逐行渲染。

#### 递归渲染：循环行下面再挂一个列表

区域循环可以**嵌套**，层级不限：

```
{{#each orders}}            ← 外层区域开始（独占一行）
${order_no} | ${customer}
{{#each items}}             ← 内层区域开始（独占一行）
  ↳ ${sku} | ${qty}
{{/each}}                   ← 内层区域结束
{{/each}}                   ← 外层区域结束
```

内层数组有两种来源：

| 写法 | 数据形状 | 适用 |
| --- | --- | --- |
| `{{#each items}}` | 外层项自带 `items` 数组 | Provider / API 返回嵌套结构；SQL 用 `json_array(json_object(...))` 拼成 JSON 文本，渲染器自动解析 |
| `{{#each order_items where order_no = order_no}}` | 两个**扁平**数组 | SQL 最常用：`orders` + `order_items` 两个 `single:false` 块，内层按字段相等过滤 |

`where` 右边是当前作用域里的键（`order_no` / `item.order_no`），字面量要加引号（`'open'`）；比较时去空格、忽略大小写，只支持单个等值条件，取不到值会直接报错。

标记必须**独占一行 / 一段**才是区域；`{{#each}}` 独占一行但没有配对的 `{{/each}}` 时仍按单行循环处理，老模板行为不变。

### DOCX 模板

支持的位置：**正文段落、表格（含嵌套表格）、页眉、页脚**。

```
报告编号：${report_no}          成品检验报告
检验员：${inspector:未填写}      日期：${date}
```

- 占位符可以和文字写在同一个段落里，不要求独占一行。
- Word 经常把一个占位符拆到多个 run（中途改过字体、从别处粘贴过），渲染器会先合并段落文本再替换，并把结果写回首个 run、沿用它的字体格式。**建议一个占位符保持同一字体**，格式最稳。
- 表格行循环：在要重复的那一行里任意单元格写 `{{#each items}}`，该行的边框、底色、字体随行复制。
- 段落块循环：`{{#each items}}` 和 `{{/each}}` 各自独占一段，中间可以有多段，整体按数组重复；段落块同样可以嵌套。
- 页眉页脚里的占位符、图片同样生效。
- 图片：占位符独占段落时（`${logo}` 单独一段）按图片字段的 `align` / `padding` 排版；和文字混排时（`签名：${sign} 完毕`）按行内图片插入，位置交给模板自身的排版。
- 不支持：条件判断、目录 / 域更新、公式。

### XLSX 模板

支持的位置：**任意工作表的单元格**。

| 单元格内容 | 生成结果 |
| --- | --- |
| `${qty}`（整格只有占位符） | 取到数字 → 写成数字单元格；取到布尔 → 写成布尔；其他 → 文本 |
| `数量：${qty} 件`（和文字混排） | 按文本替换，结果仍是字符串 |
| `{{#each items}}` | 该行按数组重复，标记本身会被清掉 |

- 只处理**文本单元格**：数字、布尔、公式单元格原样保留，不会被替换。
- 整格占位符取到数字时写成数字单元格，Excel 里能继续参与计算，导出 PDF 时也按数值排版；混排的则一律按文本。
- 行循环复制的是「单元格值 + 样式」，公式单元格会连公式一起复制；空数组时整行删除并上移。
- 图片必须**独占单元格**（`${logo}`）：图片锚定到该单元格，按 `align` 在格内水平对齐、四周留 `padding`；行高不够会自动撑高，列宽不够会自动加宽（只加不减）。
- 行循环不复制合并单元格：需要合并的区域建议不要放在循环行里，或改用 DOCX 模板。
- 区域循环里的标记行会被整行删掉，所以 `{{#each}}` / `{{/each}}` 必须各自独占一行。
- 每个工作表都会渲染；完全空白的工作表在导出 PDF 时会被跳过，不会产生空白页。
- 不支持：公式重算（模板里的公式不重新计算）、条件判断。

### 两种模板对照

| 能力 | DOCX | XLSX |
| --- | --- | --- |
| 占位符位置 | 正文段落、表格单元格、页眉页脚 | 任意工作表的文本单元格 |
| 与文字混排 | 支持 | 支持（同一单元格内） |
| 明细行循环 | 表格行 / 表格区域 / 段落块 | 整行 / 行区域 |
| 循环闭合 | 行内标记隐式；区域与段落块需 `{{/each}}` | 行内标记隐式；区域需 `{{/each}}` |
| 递归渲染 | 区域嵌套，层级不限 | 区域嵌套，层级不限 |
| 图片 | 段落内，可与文字混排 | 必须独占单元格 |
| 合并单元格 | 行复制保留原有结构 | 行循环不复制合并区域 |
| 数字 / 布尔 | 一律按文本渲染 | 整格占位符会写成数字 / 布尔单元格 |
| 页眉页脚 | 支持 | 无此概念 |

### 常见坑

1. **循环漏写 `each`**：`{{#user}}` 会被当成名为 `#user` 的占位符。渲染器直接拦截并提示正确写法。
2. **循环变量名对不上**：`{{#each report}}` 对应的是块编码 `report` 的数据块结果，不是数据库表名；块编码改了记得同步改模板。
3. **普通占位符取不到值不报错**：会输出空串。想暴露问题就给默认值，或用「模拟生成」看实际参数列表。
4. **Excel 标记写在非文本单元格**：数字格、公式格里写标记无效，先把单元格格式改成文本。
5. **图片字段的值不是图片**：空值按文本处理；URL 下载失败或格式不支持（svg / webp）会直接报错，错误信息里带字段名。
6. **不要指望条件判断**：目前没有 `{{#if}}`，需要按条件显示的内容，用数据块返回不同的数据，或拆成两份模板。

## 示例模板

`docs/public/demo/` 下放了可以直接导入的示例模板，配套的数据块 SQL 一起给了。下表两个是最基础的（默认值、明细行循环、图片字段、三种数据源、PDF 导出）；另外八套分别演示递归嵌套、竖向合并、交付级长报告、字段值转换与数据字典，见 [示例模板](./docs/guide/examples.md)。

| 模板 | 模板编码 | 文件 | 格式 | 数据块 | 覆盖的写法 |
| --- | --- | --- | --- | --- | --- |
| 普通Word | `WordStandard` | [`word-render-test.docx`](./docs/public/demo/word-render-test.docx) | DOCX / 导出 ORIGINAL | `form`(SQL)、`demoProvider`(JAVA)、`demoApi`(API) | 标题占位符与默认值、表格行循环、JAVA / API / SQL 三种数据源 |
| Excel循环渲染 | `ExcelDataRows` | [`excel-render-test.xlsx`](./docs/public/demo/excel-render-test.xlsx) | XLSX / 导出 PDF | `user`(SQL) | 整行循环、图片字段、空 sheet 跳过 |

数据块脚本：[`word-render-test.sql`](./docs/public/demo/word-render-test.sql)、[`excel-render-test.sql`](./docs/public/demo/excel-render-test.sql)。脚本按 `template_code` 关联模板，可重复执行。

### 一键刷入（推荐）

不用手工建模板、传文件、跑 SQL：两个脚本会把 `docs/public/demo` 下的 10 套示例一次性刷进**正在运行的系统**，模板元数据、模板文件、数据块、数据字典全带上。

```bash
scripts\seed-demo.bat                 # Windows
scripts/seed-demo.sh                  # Linux / macOS
```

只需要 Node 18+（脚本只用内置 `fetch`，不用 `npm install`），不需要 MySQL 客户端——全程走 `/api/doc-template/**` 接口。先把服务跑起来（example 默认 `http://localhost:8080`），再执行脚本即可。

```bash
scripts/seed-demo.bat --dry-run       # 只解析并打印要写什么，不调接口
scripts/seed-demo.bat --only WordStandard,ExcelDataRows
scripts/seed-demo.bat -b http://localhost:9000 --token <token>
```

| 参数 | 说明 |
| --- | --- |
| `-b, --base-url <url>` | 服务地址，默认 `http://localhost:8080` |
| `-t, --token <token>` | 直接用已有 token，跳过 `/demo/login` |
| `--username` / `--password` | 登录账号，默认 `admin` / `Admin@123`（example 的演示账号） |
| `--only <codes>` | 只刷指定模板，逗号分隔 |
| `-n, --dry-run` | 只解析不写入 |
| `--skip-file` / `--skip-blocks` / `--skip-dicts` | 跳过对应环节 |
| `--force` | 文件 sha256 相同也重新上传（会多一个版本） |

几个行为值得知道：

- **`*.sql` 是唯一数据源**：脚本直接解析 SQL 里的 `doc_template` / `doc_template_data_block` / `doc_template_dict` 三张表的 INSERT，改 SQL 脚本就跟着变，不用维护第二份清单。模板文件取同名的 `.docx` / `.xlsx`。
- **可重复执行**：模板按 `template_code` 复用（已存在就不动元数据，与 SQL 的 `ON DUPLICATE KEY UPDATE` 语义一致），数据块按 `block_code` 增改，字典按 `dict_code` 增改且字典项「先清后插」，模板文件比对 sha256 后相同就跳过——重跑不会堆版本。
- **SQL 数据源被禁用时会报错**：`doc-template.data-block.sql-enabled=false` 时新增 SQL 块会返回 400，脚本会把这条错误单独标出来，其他模板照常刷。

手工导入仍然可用，顺序：新增模板（编码 / 名称 / 文件类型 / 导出类型按下表填）→ 上传对应文件 → 执行 SQL → 「模拟生成」验证。

| 模板编码 | 模板名称 | 文件类型 | 导出类型 |
| --- | --- | --- | --- |
| `WordStandard` | 普通Word | `DOCX` | `ORIGINAL` |
| `ExcelDataRows` | Excel循环渲染 | `XLSX` | `PDF` |

几点需要知道的：

- **两个 SQL 块都是 `select` 假数据**（`union all` 拼几行常量），不依赖任何业务表，执行完就能生成；换成自己的表时改 `sql` 即可。
- **`form` 块**返回 `title` / `info` 两列（`single=true`），标题和信息直接出值；换成真实查询时保证结果里有这两列即可。
- **`demoProvider` / `demoApi` 依赖 example 模块**（`DemoOrderProvider` 与 `POST /demo/api/orders/{orderNo}`），只有 `doc-template-example` 启动着才有数据；换宿主时删掉或改成自己的 Provider / 接口即可。
- **模拟生成要填 `orderNo` 和 `channel` 两个参数**：`orderNo` 给 `demoProvider`、`channel` 给 `demoApi`。补齐参数只列出参数映射里以 `$request.` 开头的键，写死的常量（如 `demoApi` 的 `orderNo`）不会列出来——想让 API 块也用传进来的单号，把它的值改成 `$request.orderNo` 即可。
- **Excel 的 `${pic}` 会真的去下载图片**（5 秒超时、单张 5MB），离线环境请换成可访问的地址或先去掉 `imageFields`，否则整次生成 `FAILED`。

完整说明（模板结构、数据块逐项解释、常见坑）见 [`docs/guide/examples.md`](./docs/guide/examples.md)。

## 数据块数据源配置

数据块由「数据源配置」+「参数映射」两部分组成，三种类型共用这两个字段：

| 类型 | 配置里有哪些字段 | 参数映射作用 |
| --- | --- | --- |
| `JAVA` | `provider`，取宿主应用里 `DocumentDataProvider#getCode()` 的值 | 原样传给 `execute(params)` |
| `SQL` | `sql` / `single` / `maxRows` / `dataSource`（**不推荐**，见下文「SQL 数据源」） | 填充 SQL 里的 `:name` 占位 |
| `API` | `url` / `method` / `resultPath` / `timeoutMs` / `headers` / `query` / `body` | 替换 `{name}`、`${name}` 占位 |

> **取数方式的推荐顺序：`JAVA` > `API` > `SQL`。** `JAVA` 由宿主业务代码决定查什么，配置者只能在
> 下拉里选 Provider；`SQL` 等于把一条 SQL 的执行权交给配置者，风险最大，详见下文「SQL 数据源」。

### 在管理界面里配置

数据源配置和参数映射都是**按字段填表**，每个字段一个输入框，输入框下方就是这个字段的说明（能填什么、默认值、注意点）：

- **每个字段一个输入框**，标签旁边带「填入示例」，点一下只填这一项，不影响其他已填内容。
- **控件按字段类型自动切换**：文本 / 多行文本 / 数字（带单位）/ 开关 / 下拉 / 键值对 / JSON（只有 API 的请求体是 JSON，因为接口本身就要 JSON）。
- **下拉项来自后端**：`Provider 编码` 列的是宿主应用注册的 Provider，`数据源` 列的是可用的 `DataSource` Bean，不用记名字。
- **切换数据源类型**时配置项整套换掉（重置为新类型的默认值），参数映射和图片字段保留。
- **排序不用填**：新建的数据块排在最后，执行顺序就是列表顺序。
- 表单里没有的扩展项不会被丢掉，保存时原样带回，界面上会提示「还有 N 个扩展项」。

字段定义集中在 `frontend/doc-template-ui/src/dataBlockFields.ts`，新增配置项只改这一个文件，界面自动跟着变。

### SQL 数据源（不推荐）

> **不建议使用，优先选 `JAVA` 数据源。**
>
> 配置一条 SQL 数据块，等于把「这个数据源账号能查到的所有表」交给模板配置者：只要能打开数据块编辑页
> （`auth.mode: none` 时就是任何能访问到端口的人），就能写一条 `select * from biz_order`，再生成一份
> 文档把结果导出去。模块能做的是**拦住误配置和低级绕过**，做不到真正的数据隔离。
>
> `JAVA` 数据源没有这个问题：查哪张表、带什么条件全在宿主业务代码里定，配置者只能从下拉里选一个 Provider。

确实需要时，配置长这样：

```json
{
  "sql": "select report_no, product_name, inspect_result from qc_report where report_no = :reportNo",
  "single": true,
  "maxRows": 1,
  "dataSource": "primary"
}
```

- `sql`：单条查询语句，用 `:name` 命名占位，**不要拼字符串**；参数值一律走 JDBC 占位符绑定，不会进入 SQL 文本。
- `single`：`true` 返回单条对象（主信息）；`false` 返回数组，配合模板里的 `{{#each 块编码}}` 做明细行循环。
- `maxRows`：最多返回行数，默认取 `doc-template.data-block.default-max-rows`（500）。
- `dataSource`：可选，宿主应用里 `DataSource` 的 Bean 名称，默认用主数据源 `dataSource`。

#### 只读是怎么保证的

三层，由内到外：

| 层 | 做法 | 拦得住什么 |
| --- | --- | --- |
| 语句检查 | 剥离注释和字符串字面量后，首关键字必须是 `select` / `with`，代码区不得出现写操作、文件函数、锁行关键字，分号只允许出现在末尾 | `delete` / `update` / `drop`、`load_file`、`into outfile`、`for update`、`select 1; drop table t` 这类多语句 |
| 语法校验 | JSqlParser 解析，**只有解析结果是 `Select` 才放行** | `with x as (select 1) delete from t` 这类 CTE 写操作 |
| 只读连接 | 执行前 `Connection.setReadOnly(true)`，执行完恢复再归还连接池 | 前两层漏掉的写操作（MySQL / PostgreSQL 会置为只读事务） |

MySQL 的 `/*! ... */` 可执行注释按**代码**参与检查（服务器会执行里面的内容，不能当注释剥掉），`#` 注释在交给解析器前换成 `--`。

顺带修掉了几个旧误杀：值里带分号（`where name = 'a;b'`）、SQL 前面带注释、结尾分号后跟注释（`select 1; -- 说明`）、`#` 行注释，现在都能正常保存执行。

**这三层不是安全边界。** 解析器不认识的方言写法会直接报错（宁可拒也不放过），也有驱动只把 `setReadOnly` 当提示、并不真的生效。真正可靠的隔离是**给模块配只读数据库账号**——这是宿主侧唯一能兜住所有情况的措施。

#### 错误信息不回传数据库原文

SQL 执行失败时，接口和生成记录里只会看到：

```
SQL 执行失败（dataSource=primary），请检查 SQL 与数据源配置；详细原因见服务端日志
```

数据库原始报错（含表名、列名、SQL 片段）只写服务端日志，避免把盲注变成「显错注」。其他接口的兜底 500 也统一改成「服务内部错误，请查看服务端日志」，不再回传异常内部信息。

#### 关掉 SQL 数据源

```yaml
doc-template:
  data-block:
    sql-enabled: false
```

关闭后保存 SQL 数据块直接报错、执行已有 SQL 数据块也报错，`JAVA` / `API` 数据块不受影响。

### API 数据源

```json
{
  "url": "https://api.example.com/orders/{id}?name={name}",
  "method": "POST",
  "headers": { "Authorization": "Bearer ${token}", "Content-Type": "application/json" },
  "query": { "lang": "zh-CN" },
  "body": { "orderNo": "${id}", "channel": "${channel}" },
  "resultPath": "data",
  "timeoutMs": 5000
}
```

- **URL 传参**：`url` 里的占位符在路径和查询串里都能用，`{name}` 与 `${name}` 两种写法都认（如 `/orders/{id}?name={name}`）。值会做 URL 编码且只编一次，中文、空格、`&` 这类字符都能原样到达接口。
- **body 传参**：`body` 里的 `${name}` 同样会被替换；某个值整串就是一个 `${name}` 时按原始类型塞进去，对象、数组、数字、布尔都能带。
- `headers` / `query` 里的 `${name}` 也会替换；占位符没有对应参数会直接报错。
- `method` 只支持 `GET / POST`，默认 `GET`；`GET` 不发请求体，`POST` 才会带 `body`。
- `resultPath`：从响应 JSON 里取哪个节点作为数据块（如 `data`、`data.rows`、`data.rows.0.name`），留空表示用整个响应。
- `timeoutMs`：请求超时，默认取 `default-timeout-ms`（5000），上限由 `max-timeout-ms`（15000）兜住。
- 非 2xx 直接判定生成失败，错误信息里带状态码和响应体前 300 字符。

### 参数映射

管理界面里按「参数名 / 值」成对添加，效果就是一组键值对：

```json
{
  "reportNo": "$request.reportNo",
  "lang": "zh-CN"
}
```

- 值以 `$request.` 开头 → 取生成时传入的 `requestParams` 里的同名值（支持 `a.b` 嵌套）。
- 其他值按字面量常量处理；`1`、`true`、`{...}`、`[...]` 这类写法会按对应类型解析，不会被当成字符串。
- 数据源不需要入参时整个参数映射留空即可。

### 图片字段

数据块取到的值如果是一张图片，可以在数据块配置里声明它是图片字段，渲染时这个占位符会被替换成图片（而不是一串 URL/Base64 文本）：

```json
{
  "sql": "select report_no, logo, product_image from qc_report where report_no = :reportNo",
  "single": true,
  "imageFields": [
    "logo",
    { "field": "product_image", "width": 160, "height": 120, "align": "center", "padding": 4 }
  ]
}
```

- 写法：`["logo"]`、`"logo,photo"`、`{ "logo": 120 }`（只给宽度）、`{ "field": "logo", "width": 120, "height": 60 }` 都支持；键名也认 `image_fields` / `images`。
- 取值支持三种：`http(s)://` 地址（服务端下载）、`data:image/png;base64,...`、裸 Base64（可带换行，也支持 `base64:` 前缀）；SQL 查出 BLOB 时直接返回 `byte[]` 也可以。
- 尺寸：宽高都写 → 等比缩放进这个框（不拉伸变形）；只写一边 → 按比例算另一边；都不写 → 用原图尺寸，超过 480px 宽等比缩小。
- 对齐：`align` 支持 `center`（默认）/ `left` / `right`，控制图片在单元格内的水平位置；管理界面「图片字段」里直接选。**图片独占段落/单元格时生效**，和文字混排的段落保持模板原有对齐。
- 留白：`padding` 是图片四周的边距（像素），默认 4；Excel 里会自动把行高撑到「图片高 + 上下留白」，列宽不够时按「图片宽 + 左右留白」加宽（只加不减）。
- 位置：DOCX 里写在段落/表格单元格里即可，和文字混排也支持（`签名：${logo} 完毕`），单元格里只有图片时垂直方向也居中；XLSX 里需要占位符**独占单元格**（`${logo}`），图片锚定到该单元格并自动撑高行高。
- 明细行循环里的图片同样生效：模板行里写 `{{#each items}}${photo}`，数组每项的 `photo` 都按图片渲染。
- 值取不到（null / 空串）→ 按文本规则渲染（走默认值或空串），不会中断生成；值不是图片、下载失败、格式不支持 → 生成失败，错误信息里带字段名和原因，避免「图片位置一片空白」却查不出原因。
- 支持的格式：png / jpg 原样写入，gif / bmp / tif 等会先转成 png；svg、webp 不支持。

图片渲染默认就开启，**不需要任何配置**：远程图片 5 秒超时、单张上限 5MB、未声明尺寸时最大 480px 宽，超过即等比缩小。

### 字段值转换与数据字典

数据库里存的是 `0/1`、时间戳、`PAID`，文档上要显示「是」「2026-10-30」「已支付」——不用改 SQL，声明一下就行：

```json
{
  "provider": "demoReport",
  "converters": [
    { "field": "status", "type": "dict", "args": { "code": "order_status" } },
    { "field": "amount, total", "type": "number", "args": { "scale": 2, "grouping": true } },
    { "field": "created_at", "type": "date", "args": { "pattern": "yyyy-MM-dd" } }
  ]
}
```

- 两个位置：数据块配置 `converters`（管整个块，含嵌套对象与明细行），模板占位符 `${amount|number:2}`（管单个位置，**优先级更高**）。
- 内置 `date` / `number` / `boolean` / `percent` / `upper` / `lower` / `trim` / `mask` / `dict`；宿主实现 `DocTemplateValueConverter` 注册成 Bean 即可扩展，同名覆盖内置。**数据块里的转换是整列批量执行的**（`convertBatch`，明细几百行也只调一次），需要查库/调接口的宿主转换器覆盖它就不会 N+1。
- 转换链写在默认值之后：`${amount:0|number:2}` → `0.00`；可串联 `${name|trim|upper}`。
- **认不出来的值原样输出**（不会中断生成）；参数写错（日期格式非法、`dict` 少了 `code`）会直接报错并带上转换器名。
- 数据字典是全局资源，**主子表结构**：`doc_template_dict`（主表，`dict_code` + `dict_name`）配字段编码，`doc_template_dict_item`（子表，`item_value` → `item_label`）配值到显示名的映射；管理页「数据字典」上半屏选字典、下半屏维护映射。`dict` 转换器按 `dict_code` 换显示名，查不到时由 `args.fallback` 决定原样输出 / 空串 / 字面量。
- **同一次生成里每本字典只查一次库**（明细几百行也只发一条 `SELECT`），生成结束即清理缓存，改完字典立即生效。

### 执行规则

- 生成时按 `sort_order`、`id` 依次执行状态为 `ENABLED` 的数据块（管理界面不再提供排序输入，列表顺序即执行顺序），结果以**块编码**为命名空间放进渲染参数：块编码 `report` + `single: true` 时，模板里写 `${report.report_no}`。
- 任一数据块执行失败，整次生成记为 `FAILED`，错误信息形如「数据块「检验报告主信息」执行失败：…」，可在「生成记录」里直接看到。
- 请求里手填的参数优先级高于数据块结果；但**空串不覆盖**数据块取到的值——模拟生成弹窗不会给数据块管理的占位符补空串，避免把取数结果冲掉。
- 安全边界：SQL 数据块要过语句白名单 + 只读连接，但**仍不推荐使用**（详见上面「SQL 数据源」一节），可用 `sql-enabled: false` 整体关掉；API 只允许 `http`/`https`，可用 `doc-template.data-block.allowed-hosts` 限制主机。

```yaml
doc-template:
  data-block:
    enabled: true            # 关闭后生成时只用请求里手填的参数
    sql-enabled: true        # 对外系统建议 false，改用 JAVA 数据源
    default-max-rows: 500
    default-timeout-ms: 5000
    max-timeout-ms: 15000
    allowed-hosts: []        # 留空不限制，支持 *.example.com
```

管理界面里新增/编辑数据块时，切换「数据源类型」会自动带出该类型的默认配置；每一项都能单独点「填入示例」，已填的其他项不会被覆盖。

## 当前边界

已实现：模板元数据 CRUD、文件上传/版本/下载/预览、删除时同步清理云存储、数据块 CRUD（**表单化配置**）与**执行器（Java Provider / SQL / API 三种数据源）**、DOCX/XLSX 占位符渲染（含明细行循环与**图片字段**）、**字段值转换与数据字典**、模拟生成、内置 PDF 导出、生成记录与结果文件、Provider 列表、可插拔认证（none / custom）、嵌入式管理页面（模板信息与数据块合并为双 Tab 弹窗）、示例 iframe/代理/认证配置。

尚未实现：复杂模板语法（条件判断、图表）、循环区的合并单元格、异步生成任务队列、细粒度业务数据权限适配器。



## 直接访问页面与认证

后端启动后访问 `http://localhost:8080/doc-template/`。页面路由：`/doc-template/#/templates`（模板管理，数据块在模板编辑弹窗的第二个 Tab 中维护）、`/doc-template/#/generate-records`（生成记录）。如果端口不是 8080，请替换为实际端口。

### 认证方式

模块只识别「当前调用方是谁」（用于生成记录的操作人），**不做权限码校验，也不创建用户/角色/登录表**。

Starter **不内置任何认证框架的适配器**，pom 里也不带任何认证框架依赖，只提供一个 SPI 接口 `DocTemplateAuthorizer`。宿主用自己已有的方式识别身份实现它即可——会话、Token、网关透传、自建上下文都是一样的写法。

`doc-template.auth.mode` 只有两个取值：

| mode | 行为 | 宿主需要做 | 未登录时 |
| --- | --- | --- | --- |
| `none` | 不校验登录态，操作人取 `anonymous-user`；配了 `user-header` 则从该请求头取 | 什么都不用做 | 不拦截 |
| `custom` | 使用宿主注册的 `DocTemplateAuthorizer` Bean | 写一个 Bean（几行代码） | 实现返回 `null` → 401 |

```yaml
doc-template:
  auth:
    mode: none            # none | custom
    anonymous-user: anonymous
    # user-header: X-User-Id   # 仅 none 模式：从网关透传的可信请求头取操作人
```

启动时会打印生效模式与身份来源；`none` 模式额外打一条 WARN，提醒接口对外开放。

### 接入宿主已有的身份来源

只有一个方法要写：返回当前调用方标识，取不到返回 `null`。身份从哪来由宿主决定，Starter 不关心。

**从宿主自己的请求上下文取**（最常见，登录态由宿主的过滤器/拦截器写入）：

```java
@Bean
DocTemplateAuthorizer docTemplateAuthorizer() {
    return () -> CurrentUserHolder.getUserId();   // 取不到就返回 null
}
```

**从网关透传的请求头取**（网关已鉴权，模块只读头）：

```java
@Bean
DocTemplateAuthorizer docTemplateAuthorizer() {
    return () -> {
        HttpServletRequest request =
                ((ServletRequestAttributes) RequestContextHolder.getRequestAttributes()).getRequest();
        String userId = request.getHeader("X-User-Id");
        return (userId == null || userId.isBlank()) ? null : userId;
    };
}
```

**从请求自带的 Token 解析**（无框架依赖，自己解析即可）：

```java
@Bean
DocTemplateAuthorizer docTemplateAuthorizer() {
    return () -> {
        HttpServletRequest request =
                ((ServletRequestAttributes) RequestContextHolder.getRequestAttributes()).getRequest();
        return tokenService.resolveUserId(request.getHeader("Authorization"));   // 失败返回 null
    };
}
```

然后打开开关：

```yaml
doc-template:
  auth:
    mode: custom
```

写实现时注意三点：

- **不要抛异常**，未登录就返回 `null`。抛出去会被兜底成 500，而不是 401。
- **实现要轻**：每个 `/api/doc-template/**` 请求都会调一次，不要在里查库。
- **必须在请求线程内取得到身份**。宿主如果用异步线程池处理请求，要自己在实现里把上下文带过去（`RequestContextHolder` 或自建 `ThreadLocal` 透传）。

### `none` 模式：网关已经鉴权的情况

如果网关层做了统一鉴权、模块只想在生成记录里留下真实操作人，不用写 Bean，配一个透传请求头即可（模块只读这个头，不校验）：

```yaml
doc-template:
  auth:
    mode: none
    user-header: X-User-Id   # 值由网关写入，模块直接拿来当操作人
```

> 风险提示：这个模式下 `/api/doc-template/**` 对所有能访问到端口的调用方开放，生产环境不要用。

### 示例：`doc-template-example` 的登录模块与模式切换

> 该模块是作者自用的示例宿主，**不在发布包范围内**，只用来把两种模式都跑通。下面的账号、登录页、Cookie 配置都只服务于演示，生产宿主应当接自己的身份来源。

example 模块内置了一个极简登录（内存用户），切换模式不用改代码、不用重新打包。

**账号**（重启即失效）：`admin / Admin@123`、`demo / Demo@123`

**切换方式**——加启动参数即可：

```bash
# 无认证（默认档）：直接进 /doc-template/
java -jar doc-template-example.jar

# 默认档上单独打开认证：未登录访问 /doc-template/ 会跳到登录页，登录后自动跳回
java -jar doc-template-example.jar --doc-template.auth.mode=custom

# 连真实 MySQL + MinIO：dev / local 两个 profile 里已经配好 auth.mode=custom
java -jar doc-template-example.jar --spring.profiles.active=dev    # 地址全走环境变量
java -jar doc-template-example.jar --spring.profiles.active=local  # 地址写在个人文件里
```

IDEA 里把同样的参数写进 Program arguments 即可。

**example 里的四个文件**：

| 文件 | 作用 |
| --- | --- |
| `DemoLoginController` | `/demo/login`、`/demo/logout`、`/demo/me`，签发一个极简会话 token |
| `DemoDocTemplateAuthorizer` | 唯一需要宿主写的东西：把示例会话里的用户标识桥接成 `DocTemplateAuthorizer` |
| `DemoPageAuthConfig` | 只在 `custom` 模式生效，未登录打开管理页时跳登录页（真实宿主一般由前端路由守卫做） |
| `static/demo/login.html` | 登录页，登录成功后把 token 写进 `localStorage.token` |

**为什么不用改前端**：内嵌管理页默认就从 `localStorage.token` 读 token，并按 `Authorization: Bearer <token>` 发送；登录页和管理页同源，`localStorage` 共享，登录完直接就能用。示例的会话同时写 Cookie，是为了让页面导航和 iframe 预览这类**无法自定义 header** 的请求也能带上会话。

> **登录接口不在根路径时，必须显式配 Cookie 的 `path: /`。** 不配的话浏览器会把 Cookie 默认限定在登录接口所在目录（例如 `/demo`），`/doc-template/` 和 `/api/doc-template/**` 全都收不到会话——症状是「登录成功但页面又被弹回登录页」。这是通用结论，与具体认证方案无关。

> 注意：`mode` 是启动期配置，切模式需要重启。这是有意的——认证开关不应该在运行时被动态改掉。

### 注意事项

- 模块**只做认证（你是谁），不做授权（你能干什么）**，所以不需要给宿主菜单/角色配任何权限码，也不需要任何菜单表记录。
- 模块的拦截器只作用于 `/api/doc-template/**`，静态页面 `/doc-template/**` 永远放行——页面本身不含数据，数据都在接口后面。宿主自己的全局拦截器仍需放行这两个前缀。
- 配置了 `mode: custom` 但容器里没有 `DocTemplateAuthorizer` Bean 时**启动直接失败**，不会悄悄退化成无认证：

  | 配置 | 缺少 | 启动行为 |
  | --- | --- | --- |
  | `mode: custom` | `DocTemplateAuthorizer` Bean | 抛 `IllegalStateException`，提示实现接口 |
  | `mode: none` | — | 正常启动 + WARN 日志 |

- 非 `none` 模式下未认证返回 `401 {"code":401,"msg":"..."}`；前端可用 `DOC_TEMPLATE_CONFIG.loginUrl` 指定登录页，401 时自动跳转并带 `redirect` 参数。
- 前端 token 传递要和宿主对齐：内嵌前端默认从 `localStorage.token` 读值，按 `Authorization: Bearer <token>` 发送。宿主用别的存储键/请求头/前缀时，通过 iframe 查询参数传进去（不要把 token 本身放 URL）：

  ```html
  <iframe src="/doc-template/?tokenStorageKey=Admin-Token&tokenHeader=Authorization&tokenPrefix=Bearer&layout=bare"></iframe>
  ```

- **嵌进内容区时加 `layout=bare`**：页面默认带左侧栏（品牌 + 菜单 + 页脚）和顶栏标题，直接嵌进宿主的内容区会和宿主的菜单叠成两条导航。`layout=bare` 把左侧栏和顶栏都去掉，页面上只剩内容区，切页面靠宿主的菜单改 iframe 的 hash 地址（`#/templates`、`#/generate-records`）；不加这个参数（或直接访问 `/doc-template/`）仍是完整布局。

- **预览/下载要留意请求方式**：`/templates/versions/{id}/preview`、`/generate-records/{id}/preview` 这类地址会被浏览器/iframe 直接打开，**不会携带 `Authorization` 头**。宿主只认 header 时这些地址会 401；解决办法是让会话同时写 Cookie，或把前端改成 `fetch` + Blob 拉取后再预览。


## 数据源与存储接入

### 设计原则：Starter 不自带数据库连接，也不预设存储

`doc-template-spring-boot-starter` 的 `resources` 目录里**没有任何 `application.yml`**，启动时也不会创建连接池。它只做两件事：

1. 从宿主容器里拿一个现成的 `DataSource`，用它建 Mapper、建元数据表、跑 SQL 数据块；
2. 文件存储也不预设外部服务——宿主不配 `doc-template.storage` 时落本地磁盘，配了 `s3` 才连对象存储。

所以打包发布出去的 jar 天然是「零外部依赖配置」的，宿主配什么它就用什么。

### 三种接法

| 场景 | 配置 | 说明 |
| --- | --- | --- |
| 单库（推荐） | 只配宿主的 `spring.datasource`，**不配** `doc-template.datasource` | 元数据表和业务表同库，SQL 数据块 `dataSource` 留空即可 |
| 独立模板库 | 额外配 `doc-template.datasource.url` | Starter 另建一个 Hikari 池（`doc-template-pool`），元数据表落在该库；业务表仍在宿主库，SQL 数据块用 `dataSource: docTemplate` 指过去 |
| 宿主多数据源 | 不配 `doc-template.datasource` | 按 `dataSource` → `dynamicDataSource` → `masterDataSource` → `primaryDataSource` → `@Primary` 的顺序自动挑一个；也可在数据块里直接写 Bean 名 |

```yaml
# 场景一：单库（最常见的用法）
spring:
  datasource:
    url: jdbc:mysql://127.0.0.1:3306/doc_template?useUnicode=true&characterEncoding=utf8&serverTimezone=GMT%2B8
    username: root
    password: ${DB_PASSWORD}

doc-template:
  enabled: true
  auth:
    mode: none
  # datasource 整段不配 = 复用上面的宿主数据源
```

```yaml
# 场景二：模板元数据单独一个库
doc-template:
  datasource:
    url: jdbc:mysql://127.0.0.1:3306/doc_template?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai
    driver-class-name: com.mysql.cj.jdbc.Driver # 可省略，按 URL 自动推断
    username: doc_template
    password: ${DOC_TEMPLATE_DB_PASSWORD}
    maximum-pool-size: 5
    connection-timeout: 30000
```

启动后会打印一块配置摘要，「数据源」一行就是实际选中的库：

```
  数据源     宿主数据源(dataSource) · MySQL 8.0.36
```

### 存储接入

存储和数据库一样，**模块不预设任何外部服务**：

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

使用前请：

1. 数据库 schema 可用；启动时会自动检测并创建缺失的文档模板表（`doc-template.initialize-schema` 默认 `true`）。
2. 用 S3 时需提前创建 bucket（例如 `doc-template`）并给 access key 读写权限，当前 Starter 不会自动建 bucket。
3. 模板文件在 S3 模式下保存为对象 key，下载接口从 S3 拉取后回传，不要求浏览器直连 bucket。

注意：存储配置完全独立于宿主已有的 OSS/MinIO 配置，不会自动复用宿主的对象存储客户端。

文件位置是**按记录落库的标识**：本地存储写相对路径，S3 写 `s3://bucket/key`。改 `doc-template.storage.type` 只影响之后的上传，不会搬迁已有文件：

| 记录里的位置 | 当前配置 | 读取结果 |
| --- | --- | --- |
| `s3://...` | `type: local`（或不配） | 400，提示该文件在 S3 上、当前实例未启用 S3 存储 |
| 相对路径 | `type: s3` | 仍按本地磁盘读，找不到时提示文件不存在 |
| `s3://...` | `type: s3` | 正常读取 |

三种情况都不返回 500。删除不受影响：对象不可达时只记 warn 日志并跳过对象删除，数据库记录照常删除。

### 本地调试：example 就是「示例宿主」

`backend/doc-template-example` 本身就是一个完整的宿主项目：它用 `spring.datasource` 提供数据源、用 `doc-template.storage` 提供存储，Starter 只是被它依赖引入。所以本地调试不需要给 Starter 单独配库或配存储，只改 example 的配置即可。

example **不自带内存库**，必须带 `dev` 或 `local` 启动（不带 profile 会因为缺少 `DataSource` 启动失败）：

| 启动方式 | 数据源 | 文件存储 | 认证 | 用途 |
| --- | --- | --- | --- | --- |
| `--spring.profiles.active=dev` | 环境变量 `DOC_DB_*` 指向的 MySQL | 环境变量 `DOC_S3_*` 指向的对象存储 | `custom` | 团队/CI 共用，地址与凭据全部走参数 |
| `--spring.profiles.active=local` | `application-local.yml` 里的真实 MySQL | 同文件的 MinIO | `custom` | 作者本地，地址写死在个人文件里（不进 git） |

两个档位都不需要改 Starter 的代码或配置——差异全部落在 example（宿主）这一侧；想关掉认证加 `--doc-template.auth.mode=none` 即可。

```bash
# 连真实库本地调试（团队共用档，先设好环境变量）
mvn -pl doc-template-example -am spring-boot:run -Dspring-boot.run.profiles=dev
# 或者
java -jar doc-template-example/target/doc-template-example-0.1.0.jar --spring.profiles.active=dev
# 自己的机器：地址写在 application-local.yml 里，直接跑
java -jar doc-template-example/target/doc-template-example-0.1.0.jar --spring.profiles.active=local
```

`dev` 档的连接串与凭据全部来自环境变量，缺任何一项都会启动失败并提示 `Could not resolve placeholder`：

```bash
export DOC_DB_URL='jdbc:mysql://127.0.0.1:3306/doc_template?...'
export DOC_DB_USERNAME=root
export DOC_DB_PASSWORD=your_password
export DOC_S3_ENDPOINT='http://127.0.0.1:9000'
export DOC_S3_ACCESS_KEY=your_access_key
export DOC_S3_SECRET_KEY=your_secret_key
# 可选：DOC_S3_REGION（默认 us-east-1）、DOC_S3_BUCKET（默认 doc-template）
```

`application-dev.yml` 进 git（不含任何真实地址和凭据），`application-local.yml` 已被 `.gitignore` 忽略、只留在本机。两个文件都只属于 example 模块（示例宿主），不会进入 Starter 的 jar；Starter 发布给宿主使用时，宿主的 `spring.datasource` 是什么就连什么。

### 独立数据库驱动说明

Starter 已将 `com.mysql:mysql-connector-j` 作为运行时依赖引入，`doc-template.datasource.driver-class-name` 可不配置，默认根据 JDBC URL 自动识别；如使用其他数据库，需要自行添加对应 JDBC 驱动。自动建表目前支持 MySQL 和 MariaDB。

## 对外坐标

发布到 Maven Central，命名空间 `io.github.violateer`（GitHub 账号验证）：

| 坐标 | 说明 |
| --- | --- |
| `io.github.violateer:doc-template-spring-boot-starter` | 宿主直接引入这一个 |
| `io.github.violateer:doc-template-core` | 只实现 `DocumentDataProvider` 的业务模块可单独依赖，零传递依赖 |

`doc-template-parent` 也会一并发布（子模块靠它解析父 POM），不需要宿主直接引用。示例宿主 `doc-template-example` **不在发布范围**。

## 开源协议

[Apache License 2.0](./LICENSE)。
