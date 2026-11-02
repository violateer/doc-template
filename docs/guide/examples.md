# 示例模板

仓库里放了十套可以直接导入的示例模板，配套的数据块 SQL 也一起给了。前两套是**写法演示**（单值占位符、默认值、明细行循环、图片字段、PDF 导出），中间两套专讲**递归渲染**（循环行下面再挂一个列表），再两套专讲**竖向合并**，再两套是**接近正式交付的完整报告**（多级标题、目录域、页眉页脚、20 个数据块），最后两套专讲**字段值转换与数据字典**（`0/1` → 「是」、`PAID` → 「已付款」）。

| 模板 | 模板编码 / 文件 | 格式 | 数据块 | 覆盖的写法 |
| --- | --- | --- | --- | --- |
| 普通Word | `WordStandard`<br>`word-render-test.docx` | DOCX / ORIGINAL | `form`(SQL)、`demoProvider`(JAVA)、`demoApi`(API) | 标题占位符与默认值、表格行循环、三种数据源 |
| Excel循环渲染 | `ExcelDataRows`<br>`excel-render-test.xlsx` | XLSX / PDF | `user`(SQL) | 整行循环、图片字段、空 sheet 跳过 |
| 嵌套循环（Word） | `WordNestedLoop`<br>`word-nested-loop.docx` | DOCX / ORIGINAL | 3 个 SQL 块 | 区域循环、递归嵌套、行内单行循环、段落块嵌套 |
| 嵌套循环（Excel） | `ExcelNestedLoop`<br>`excel-nested-loop.xlsx` | XLSX / ORIGINAL | 3 个 SQL 块 | 嵌套字段、where 关联过滤、行内单行循环 |
| 竖向合并（Word） | `WordMergeCells`<br>`word-merge-cells.docx` | DOCX / ORIGINAL | 4 个 SQL 块 | `merge=1,2 by=物料主键`、不写 by 按文本比较、空值不合并 |
| 竖向合并（Excel） | `ExcelMergeCells`<br>`excel-merge-cells.xlsx` | XLSX / ORIGINAL | 4 个 SQL 块 | 列字母写法 `merge=A,B`、列号写法 `merge=1`、嵌套循环里合并外层列 |
| 正式验收报告（Word） | `WordAcceptanceReport`<br>`word-acceptance-report.docx` | DOCX / ORIGINAL | 20 个 SQL 块 | 多级标题、目录域、三段式页眉页脚、斑马纹表格、KPI 卡片、图片字段 |
| 正式验收报告（Excel） | `ExcelAcceptanceReport`<br>`excel-acceptance-report.xlsx` | XLSX / ORIGINAL | 5 个 SQL 块 | 多 sheet、合并单元格、KPI 卡片、冻结窗格、打印区域、行循环 |
| 转换与字典（Word） | `WordDictConvert`<br>`word-dict-convert.docx` | DOCX / ORIGINAL | 2 个 SQL 块 + 3 本字典 | 数据块 `converters` 与模板 `${字段\|转换器}` 两条路径、字典转换、日期 / 金额 / 布尔 / 脱敏 |
| 转换与字典（Excel） | `ExcelDictConvert`<br>`excel-dict-convert.xlsx` | XLSX / ORIGINAL | 2 个 SQL 块 + 3 本字典 | 同上，多一个「转换对照」工作表逐条列出原始值 → 显示值 |

对应的数据块脚本与模板文件放在同一目录（`docs/public/demo/`）：

- `word-render-test.sql`、`excel-render-test.sql`
- `word-nested-loop.sql`、`excel-nested-loop.sql`
- `word-merge-cells.sql`、`excel-merge-cells.sql`
- `word-acceptance-report.sql`、`excel-acceptance-report.sql`
- `word-dict-convert.sql`、`excel-dict-convert.sql`

::: tip 前两个文件就是 example 模块里在用的模板
文件是从运行中的示例应用下载的当前版本（与库里的 `file_sha256` 一致），SQL 也是照着库里 `doc_template_data_block` 的实际配置整理的，所以「导入后能直接跑通」这件事本身是被验证过的。
:::

## 导入方式

### 一键刷入（推荐）

仓库根目录有两个入口脚本，把上面十套示例一次性刷进**正在运行的系统**——模板元数据、模板文件、数据块、数据字典全带上，不用手工建模板、传文件、跑 SQL：

```bash
scripts\seed-demo.bat          # Windows
scripts/seed-demo.sh           # Linux / macOS
```

只需要 Node 18+，不需要 MySQL 客户端：全程走 `/api/doc-template/**` 接口。先把服务跑起来（example 默认 `http://localhost:9150`），再执行脚本。

| 参数 | 说明 |
| --- | --- |
| `-b, --base-url <url>` | 服务地址，默认 `http://localhost:9150` |
| `-t, --token <token>` | 直接用已有 token，跳过 `/demo/login` |
| `--username` / `--password` | 登录账号，默认 `admin` / `Admin@123`（example 的演示账号） |
| `--only <codes>` | 只刷指定模板，逗号分隔，如 `WordStandard,ExcelDataRows` |
| `-n, --dry-run` | 只解析并打印要写什么，不调接口 |
| `--skip-file` / `--skip-blocks` / `--skip-dicts` | 跳过对应环节 |
| `--force` | 文件 sha256 相同也重新上传（会多一个版本） |

几个行为值得知道：

- **`.sql` 是唯一数据源**：脚本直接解析同目录 SQL 里的模板元数据、数据块、字典，模板文件取同名的 `.docx` / `.xlsx`。改 SQL 脚本就跟着变，不用维护第二份清单。
- **可以重复跑**：模板按编码复用（已存在就不动元数据）、数据块按编码增改、字典项先清后插、模板文件比对 sha256 相同就跳过，不会堆版本。
- 宿主的登录接口不在 `/demo/login` 时，用 `--token` 指定 token。

### 手工导入

1. 管理页 → 模板管理 → 新增模板，**模板编码 / 名称 / 文件类型 / 导出类型按下表填**（脚本按 `template_code` 关联，编码填错就挂不上）。
2. 打开该模板的「编辑」→ 上传对应的 `.docx` / `.xlsx`。
3. 执行同目录下的 `.sql`：脚本会补一条模板元数据（已存在则不动）、再插入数据块，可重复执行。
4. 回到列表点「模拟生成」，或在数据块里点「预览」先确认取数是否通。

| 模板编码 | 模板名称 | 文件类型 | 导出类型 |
| --- | --- | --- | --- |
| `WordStandard` | 普通Word | `DOCX` | `ORIGINAL` |
| `ExcelDataRows` | Excel循环渲染 | `XLSX` | `PDF` |
| `WordNestedLoop` | 嵌套循环示例（Word） | `DOCX` | `ORIGINAL` |
| `ExcelNestedLoop` | 嵌套循环示例（Excel） | `XLSX` | `ORIGINAL` |
| `WordMergeCells` | 竖向合并示例（Word） | `DOCX` | `ORIGINAL` |
| `ExcelMergeCells` | 竖向合并示例（Excel） | `XLSX` | `ORIGINAL` |
| `WordAcceptanceReport` | 正式验收报告（Word） | `DOCX` | `ORIGINAL` |
| `ExcelAcceptanceReport` | 正式验收报告（Excel） | `XLSX` | `ORIGINAL` |
| `WordDictConvert` | 转换与字典示例（Word） | `DOCX` | `ORIGINAL` |
| `ExcelDictConvert` | 转换与字典示例（Excel） | `XLSX` | `ORIGINAL` |

## 普通Word（WordStandard）

### 模板结构

```
${form.title:数据块渲染测试}                  ← 居中加粗标题，取不到值就用冒号后的默认值
信息：${form.info:form 数据块没有返回 info}

一、Java Provider 数据块（demoProvider）
订单号：${demoProvider.order_no}
客户：${demoProvider.customer_name}
...（联系人 / 下单日期 / 交货日期 / 销售 / 金额 / 数据来源 / 备注）

序号 | 物料名称 | 规格 | 数量 | 单位 | 单价 | 金额          ← 表头
{{#each demoProvider.items}}${line_no} | ${item_name} | ...   ← 数据行，整行按数组重复

二、API 数据块（demoApi）                     ← 与上面同结构，块编码换成 demoApi
```

- 两个明细表都是 **7 列 2 行**：第一行表头，第二行是循环行，循环标记 `{{#each ...}}` 和 `${line_no}` 写在同一个单元格里，标记本身不会打印。
- 循环体里 `${item_name}` 直接取当前项的字段；和外层参数重名时写成 `${item.item_name}` 更稳。

### 数据块

| 块编码 | 类型 | 关键配置 | 说明 |
| --- | --- | --- | --- |
| `form` | SQL | `select '数据块渲染测试' as title, '示例数据：这条 SQL 不查任何表' as info`，`single=true` | 标题与信息。**不查任何表**，直接给两列假数据；换成真实查询时保证结果里有 `title` / `info` 即可 |
| `demoProvider` | JAVA | `{"provider":"demoOrder"}`，参数映射 `orderNo → $request.orderNo` | 调 example 的 `DemoOrderProvider`，不传 `orderNo` 时回落到 `SO-2026-0001` |
| `demoApi` | API | `POST http://host.docker.internal:9150/demo/api/orders/{orderNo}?name=${channel}`，`body: {"channel":"${channel}"}`，`resultPath=data`，参数映射 `orderNo` 常量 + `channel → $request.channel` | 调 example 的 `DemoApiController`，同时演示 URL 传参（路径 `{orderNo}` + 查询串 `${channel}`）和 body 传参；响应里的 `request_header` / `request_query` / `request_body` 会回显收到的东西。地址按「服务端在容器里回调宿主机」写的，本机 `mvn spring-boot:run` 时改成 `http://127.0.0.1:9150` |

::: tip 「补齐参数」为什么只列出部分键
模拟生成里的补齐参数，只取参数映射中**以 `$request.` 开头的值**——那才是这次生成要传进来的入参。写死的常量（如 `demoApi` 的 `orderNo`）配置里已经有值了，不会再让你填。
所以这份模板打开模拟生成时会补出两个键：`orderNo`（`demoProvider` 用）、`channel`（`demoApi` 用）。
想让 API 块也用生成时传的单号，把它的 `orderNo` 值改成 `$request.orderNo` 即可。
:::

::: warning 这两个块依赖 example 模块
`demoProvider` / `demoApi` 分别指向 `DemoOrderProvider`（编码 `demoOrder`）和 `POST /demo/api/orders/{orderNo}`，只有 `doc-template-example` 启动着才有数据。换成自己的宿主时，把这两个块删掉或改成自己的 Provider / 接口即可，`form` 块和整份模板结构不受影响。
:::

## Excel循环渲染（ExcelDataRows）

### 模板结构

| 单元格 | 内容 |
| --- | --- |
| A1 / B1 / C1 / D1 | 用户名称 / 用户账号 / 手机号 / 头像（表头） |
| A2 | `{{#each user}}${nick_name}` |
| B2 / C2 / D2 | `${user_name}` / `${phone_number}` / `${pic}` |

- 整行循环：A2 出现 `{{#each user}}`，第 2 行按数组长度整行复制，样式一起复制。
- `${pic}` 由数据块的 `imageFields` 声明成图片，**必须独占单元格**；行高不够会自动撑高，列宽不够只加不减。
- Sheet2 / Sheet3 是空表，导出 PDF 时会被自动跳过（否则会多出空白页）。

### 数据块

| 块编码 | 类型 | 关键配置 |
| --- | --- | --- |
| `user` | SQL | `select '张三' as nick_name, ... as pic union all select ...`（三行假数据），`single=false`，`maxRows=1000`，`imageFields=[{field:pic,width:50,height:50}]` |

::: warning 图片字段会真的发起下载
`pic` 指向一个公网图片地址，生成时服务端会去下载（5 秒超时、单张 5MB）。离线或内网环境请把这一列换成可访问的图片，或者先把 `imageFields` 去掉再跑，否则整次生成会因为「图片下载失败」而 `FAILED`。
:::

## 嵌套循环（WordNestedLoop / ExcelNestedLoop）

这两套专门演示**递归渲染**：一个循环行下面再渲染一层列表。内容很短，重点是四种写法都摆在同一个模板里，可以照着抄。

### 数据块（两边一样，3 个 SQL 块）

| 块编码 | single | 内容 | 谁在用 |
| --- | --- | --- | --- |
| `report` | `true` | 标题 / 合计 / 说明 | 页首标题、页尾合计 |
| `orders` | `false` | 3 个订单，`items` 列是 `json_array(json_object(...))` 拼出的嵌套数组 | 写法 1、写法 4 |
| `order_items` | `false` | 6 行扁平明细，多个订单混在一起 | 写法 2、写法 3 |

`items` 列之所以要拼成 JSON 文本：SQL 结果集本身是扁平的，一个单元格里装不下数组；渲染器取循环变量时会把 JSON 数组字符串自动解析成数组。换成 Provider 直接返回 `List` 效果一样，而且更推荐。

### 四种写法

| 写法 | 标记怎么放 | 内层数组从哪来 |
| --- | --- | --- |
| 1 区域循环 + 嵌套字段 | `{{#each orders}}` / `{{#each items}}` 各独占一行 | 外层项的 `items` 字段 |
| 2 区域循环 + where 过滤 | `{{#each order_items where order_no = order_no}}` 独占一行 | 扁平的 `order_items` 块，按单号过滤 |
| 3 行内单行循环 | 标记写在数据行最后一格（`${amount}{{#each order_items}}`） | 整个 `order_items` 块，一行一条 |
| 4 段落块循环（嵌套） | `{{#each}}` / `{{/each}}` 各独占一段，无表格 | 外层项的 `items` 字段 |

Word 模板里四个写法依次排开（三个表格 + 一段列表），Excel 模板拆成三个工作表：`嵌套字段` / `关联过滤` / `行内单行循环`。

### 几个容易踩的点

- **区域标记必须独占一行 / 一段**。写成 `${order_no}{{#each items}}` 就变成单行循环，配对的 `{{/each}}` 会白写。
- **`where` 右边是当前作用域的键**，不是字面量。写 `where order_no = order_no` 表示「明细的 order_no 等于当前订单的 order_no」；要比字符串常量得加引号：`where status = 'open'`。
- **Excel 里明细行的列比订单行少**时，渲染器会把订单行多出来的列清空，不会残留上一单的订单号（这个行为是 2026-10-30 修的）。
- **竖向合并要用 `merge=` 声明**，见下一节。

## 竖向合并（WordMergeCells / ExcelMergeCells）

明细里同一个值连续出现时（同一物料的多个批次、一个订单的多行明细），用 `merge=` 把它合成一格。这两套模板用**同一套数据**，Word 三张表 + Excel 三个工作表，各演示三种写法。

### 数据块（两边一样，4 个 SQL 块）

| 块编码 | single | 内容 |
| --- | --- | --- |
| `report` | `true` | 标题、入库合计、说明 |
| `stock_items` | `false` | 7 行入库明细：`M-1001` 三个批次、`M-1002` 两个批次、`M-1003`/`M-1004` 各一个；最后两行 `warehouse` 是 `null` |
| `orders` | `false` | 3 个订单 |
| `order_items` | `false` | 6 行扁平明细，写法 3 用 `where` 关联过滤 |

### 三种写法

| 写法 | 标记 | 看到的效果 |
| --- | --- | --- |
| 1 按主键合并 | `merge=1,2 by=material_code` | 物料编码 + 名称两列各合并 3 / 2 / 1 / 1 行；不同物料即使同名也不并 |
| 2 不写 by，按文本 | `merge=1` | 相邻同名的仓库合并（原料库 3 行、半成品库 2 行）；两行空仓库**各占一格**，不会变成一个大空格 |
| 3 嵌套循环里合并外层列 | `merge=1 by=order_no` | 外层订单号跨内层展开的明细行合并：3 行明细的订单占 4 行、2 行明细的占 3 行、1 行的占 2 行 |

Excel 那份刻意用了两种列写法：工作表 1 用列字母 `merge=A,B`，工作表 2 用列号 `merge=1`，两者等价。

### 几个容易踩的点

- **标记格会被清空**。行内单行循环时，写 `{{#each ...}}` 的那一格渲染完是空的，`merge=` 指向它（按文本比较）永远合不上。这三张表都用**区域循环**（标记独占一行，渲染时整行删掉），所以不存在这个问题。
- **不写 `by` 就是比文本**。两个不同物料恰好同名、或两笔订单日期相同，会被并成一格。要区分就写 `by=主键`。
- **空值永远不合并**。想合并空格子做不到，这是刻意的（两个空格子并成一个大空格是视觉事故）。
- **模板里自己画的跨行合并区在循环块内会被移除**（渲染时会打一条 warn），竖向合并一律用 `merge=` 声明；只占一行的横向合并不受影响，会随每份产出行重建。
- **合并格默认靠上**。示例 SQL 里给 `stock_items` 块配了 `"mergeCellAlign": "center"`，所以合并出来的格子是垂直居中的；删掉这一项就回到靠上。三种取值见[合并单元格对齐](/guide/data-blocks#合并单元格对齐)。

## 正式验收报告（WordAcceptanceReport / ExcelAcceptanceReport）

这两套是「交付级」示例：正文里除了标题、表题、表头，**几乎不留手写文字**，全部由数据块渲染。Word 那份渲染后约 77% 的可见文字来自数据块。

### Word 版的结构

| 位置 | 内容 | 数据来源 |
| --- | --- | --- |
| 封面 | 项目名、编号、合同号、甲乙双方、报告编号、版本、编制单位与日期 | `report`（`single=true`） |
| 文档控制 | 表 0-1 基本信息 / 表 0-2 修订记录 / 表 0-3 分发范围 | `report`、`revisions`、`distribution` |
| 目录 | Word 域（`TOC \o "1-2"`），带缓存条目，打开时自动更新 | — |
| 第 1~6 章 | 背景、目标、范围、参建单位、交付物、核对、依据、方法、标准、执行、指标、安全、分级、问题、整改、结论、评分、建议 | `report` + 18 个列表/表格块 |
| 附录 A~C | 术语表、相关文档、项目标识与版本 | `glossary`、`ref_docs`、`report` |
| 签署页 | 五方签署表 | `signers` |

两类循环都在这份模板里用到了：

- **表格行循环**（12 处）：`units` / `items` / `metrics` / `issues` / `revisions` / `distribution` / `methods` / `issue_levels` / `scores` / `glossary` / `ref_docs` / `signers`。标记写在循环行**最后一格**的占位符后面，例如 `${item.c4}{{#each revisions}}`。
- **段落块循环**（7 处）：`goals` / `scope` / `checklist` / `basis` / `criteria` / `security` / `suggestions`。`{{#each xxx}}` 与 `{{/each}}` 各占一段，两段之间的段落整体重复；标记段渲染后会被删除。

### 列名约定（c1~c5）

20 个数据块全部是 `select ... union all select ...` 的常量假数据，**不建表、不插业务数据**，执行完脚本就能直接生成。其中 15 个表格/列表类块统一用 `c1~c5` 这套列名，靠块编码区分语义：

| 块编码 | c1 | c2 | c3 | c4 | c5 |
| --- | --- | --- | --- | --- | --- |
| `revisions` | 版本 | 日期 | 修订说明 | 修订人 | — |
| `distribution` | 接收方 | 份数 | 形式 | 用途 | — |
| `methods` | 方法 | 主要内容 | 参与方 | 时间 | — |
| `issue_levels` | 等级 | 定义 | 处理时限 | 跟踪方式 | — |
| `scores` | 维度 | 权重 | 得分 | 加权得分 | — |
| `glossary` | 术语 | 说明 | — | — | — |
| `ref_docs` | 名称 | 编号 | 版本 | 提交日期 | — |
| `signers` | 角色 | 姓名 | 单位部门 | 签字 | 日期 |
| `goals` / `scope` / `basis` / `criteria` / `suggestions` | 列表正文 | — | — | — | — |
| `checklist` / `security` | 检查项 | 结果 | — | — | — |

统一列名是为了让模板里同一套写法复用（`${item.c1}` 起头、最后一格挂循环标记），加内容时只要在 `union all` 后面接一行，模板结构不用动。换成真实表查询时保持列名不变即可，例如把 `goals` 换成 `select content as c1 from project_goal where project_code = :projectCode`。

::: warning 段落块循环的段落里只能有一个 run
渲染器替换占位符时会把整段的所有 run 合并后写回第一个 run。如果列表项是「项目符号 run + 文字 run」两个 run，符号的颜色和字重会覆盖到整行。示例里把符号和文字写在同一个 run 里（`（${seq_no}）　${c1}`），需要彩色项目符号时用段落编号（`w:numPr`）而不是文字符号。
:::

## 字段值转换与数据字典（WordDictConvert / ExcelDictConvert）

这两套专门演示**两条转换路径**，故意不重叠，方便对照：主信息全在数据块里配，明细全在模板里写。

| 路径 | 写在哪 | 这套模板里管哪些字段 |
| --- | --- | --- |
| 数据块 `converters` | `data_source_config.converters` | 下单时间、订单状态、支付方式、订单金额、折扣率、是否加急、联系电话 |
| 模板占位符 | `${字段\|转换器}` | 明细的单价、小计、状态，以及页尾合计 |

### 数据块（两边一样，2 个 SQL 块 + 3 本字典）

| 块编码 | single | 内容 |
| --- | --- | --- |
| `report` | `true` | 一行订单主信息（常量假数据），`converters` 里声明了 7 条转换 |
| `items` | `false` | 5 行明细，字段原样返回，转换写在模板里 |

脚本会顺带把三本字典建好（已存在则覆盖，字典项先清后插，可重复执行）：

| 字典编码 | 名称 | 条目 |
| --- | --- | --- |
| `order_status` | 订单状态 | `CREATED`→待确认、`PAID`→已付款、`SHIPPED`→已发货、`DONE`→已完成、`CANCELLED`→已取消 |
| `pay_channel` | 支付方式 | `ALIPAY`→支付宝、`WECHAT`→微信支付、`BANK`→银行转账 |
| `item_status` | 明细状态 | `OK`→正常、`SHORT`→缺货、`REPLACED`→已换货 |

### 两条路径的区别

- 数据块 `converters` **改的是数据本身**：转换后的值进入渲染参数，所以 `{{#each ... where 字段 = 值}}` 要按转换后的值比较（字典转成中文了，`where` 右边就写中文）。
- 模板占位符上的 `|...` **只影响那一处的输出**，不改数据、不影响循环过滤，同一个字段在不同位置可以写不同的转换器。
- 两处可以叠加：数据块里配了 `dict`，模板里再追加 `|upper` 就在字典结果上继续转。

### 几个容易踩的点

- **模板里的静态示例文字别写成 `${...}`**。模板里任何 `${}` 都会被当占位符吃掉，写「示例：`${unit_price|number:2}`」渲染出来是一片空白。这两套模板的对照表改用「字段名 + 竖线 + 转换器」的写法（`unit_price|number:2`），另加一句说明外层要加美元符号和花括号。
- **默认值写在转换链之前**：`${amount:0|number:2}` → `0.00`；默认值里不能出现 `|`（会被当成转换链分隔符）。
- **`dict` 少了 `code` 会直接报错**，但**字典编码写错不报错**，走 `args.fallback`（默认原样输出）。所以「状态还是英文」先回字典页确认编码拼对了没。
- 字典是全局资源，改完立即生效不用重启；同一次生成里每本字典只查一次库（明细几百行也只发一条 `SELECT`）。

## 依赖与坑

| 项 | 说明 |
| --- | --- |
| 业务表依赖 | 十套模板的数据块**都不依赖任何业务表**：要么是 `select` 常量 + `union all`，要么是 JAVA / API 块。执行完 `.sql` 就能「模拟生成」，不用建表、不用灌数 |
| 表结构 | `doc_template` / `doc_template_data_block` / `doc_template_dict` / `doc_template_dict_item` 由 Starter 自动创建（MySQL 8 / MariaDB）；其他库先执行 `db/schema.sql` |
| SQL 数据块开关 | `doc-template.data-block.sql-enabled=false` 时 SQL 块直接报错，示例里的块都跑不了 |
| 执行顺序 | 按 `sort_order` 依次执行，任一块失败整次生成就是 `FAILED`；调模板时可以先停用不相关的块 |
| 占位符取不到值 | 不报错，输出空串或默认值。想暴露问题就用「模拟生成」看实际参数列表 |
