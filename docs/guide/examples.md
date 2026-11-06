# 示例模板

仓库里放了四套可以直接导入的示例模板，配套的数据块 SQL 也一起给了。两套是**综合示例**（Word / Excel 各一份，把各种写法集中在一份文档里，生成一次就能逐条对照），两套是**接近正式交付的完整报告**（多级标题、目录域、页眉页脚、20 个数据块）。

| 模板 | 模板编码 / 文件 | 格式 | 数据块 | 覆盖的写法 |
| --- | --- | --- | --- | --- |
| 综合示例（Word） | `WordShowcase`<br>`word-showcase.docx` | DOCX / ORIGINAL | 6 个 SQL 块 + `demoProvider`(JAVA) + `demoApi`(API) + 3 本字典 | 占位符与默认值、手填参数、点号取嵌套、三种循环、嵌套与 `where`、竖向合并、**动态列**、`converters` 与字典、宿主自定义转换器、图片与二维码、三种数据源 |
| 综合示例（Excel） | `ExcelShowcase`<br>`excel-showcase.xlsx` | XLSX / ORIGINAL | 6 个 SQL 块 + 3 本字典 | 整格数字与混排文本、行内与区域循环、嵌套与 `where`、`merge` 两种列写法、**动态列**、转换与字典、图片与二维码、循环体逐行出图、多 sheet |
| 正式验收报告（Word） | `WordAcceptanceReport`<br>`word-acceptance-report.docx` | DOCX / ORIGINAL | 20 个 SQL 块 | 多级标题、目录域、三段式页眉页脚、斑马纹表格、KPI 卡片、图片字段 |
| 正式验收报告（Excel） | `ExcelAcceptanceReport`<br>`excel-acceptance-report.xlsx` | XLSX / ORIGINAL | 5 个 SQL 块 | 多 sheet、合并单元格、KPI 卡片、冻结窗格、打印区域、行循环 |

对应的数据块脚本与模板文件放在同一目录（`docs/public/demo/`）：

- `word-showcase.sql`、`excel-showcase.sql`
- `word-acceptance-report.sql`、`excel-acceptance-report.sql`

::: tip 综合示例是「照着抄」用的
两份综合示例里的表格不追求好看，只追求把写法摆清楚：左列写表达式、右列放真实占位符，生成出来就能看到「写什么 → 出什么」。改自己的模板时直接挑对应那一段抄走即可。
:::

## 导入方式

### 一键刷入（推荐）

仓库根目录有两个入口脚本，把上面四套示例一次性刷进**正在运行的系统**——模板元数据、模板文件、数据块、数据字典全带上，不用手工建模板、传文件、跑 SQL：

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
| `--only <codes>` | 只刷指定模板，逗号分隔，如 `WordShowcase,ExcelShowcase` |
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
| `WordShowcase` | 综合示例（Word） | `DOCX` | `ORIGINAL` |
| `ExcelShowcase` | 综合示例（Excel） | `XLSX` | `ORIGINAL` |
| `WordAcceptanceReport` | 正式验收报告（Word） | `DOCX` | `ORIGINAL` |
| `ExcelAcceptanceReport` | 正式验收报告（Excel） | `XLSX` | `ORIGINAL` |

## 综合示例（WordShowcase / ExcelShowcase）

这两套是「一份文档把所有写法摆全」的对照模板：生成一次，就能看到每种标记写出来是什么效果。文档本身样式很朴素（标题 + 带边框的表格），重点全在数据展示和写法对照上。

### 数据块（Word 8 个 / Excel 6 个）

| 块编码 | 类型 | Word | Excel | 内容 |
| --- | --- | --- | --- | --- |
| `report` | SQL `single=true` | ✓ | ✓ | 主信息：9 个字段配了 `converters`；`logo` 是图片字段、`verify_url` 是二维码字段 |
| `items` | SQL `single=false` | ✓ | ✓ | 5 行订单明细，转换写在模板占位符上；Excel 版多一个 `pic` 图片字段 |
| `orders` | SQL `single=false` | ✓ | ✓ | 3 个订单，`items` 列是 `json_array(json_object(...))` 拼出的嵌套数组 |
| `order_items` | SQL `single=false` | ✓ | ✓ | 6 行扁平明细，模板里用 `where` 关联过滤 |
| `stock_items` | SQL `single=false` | ✓ | ✓ | 7 行入库明细，用于竖向合并；配了 `mergeCellAlign=center` |
| `inspection` | SQL `single=false` + `columns` | ✓ | ✓ | 4 行检验项目；列定义由 `columns` 里的另一个 SQL 给（3 列样品），用于动态列 |
| `demoProvider` | JAVA | ✓ | — | 调 example 的 `DemoOrderProvider`（编码 `demoOrder`） |
| `demoApi` | API | ✓ | — | 调 example 的 `POST /demo/api/orders/{orderNo}`，同时演示路径、查询串、body 三处传参 |

脚本还会建三本字典（Word / Excel 两份共用，脚本幂等）：

| 字典编码 | 名称 | 条目 |
| --- | --- | --- |
| `order_status` | 订单状态 | `CREATED`→待确认、`PAID`→已付款、`SHIPPED`→已发货、`DONE`→已完成、`CANCELLED`→已取消 |
| `pay_channel` | 支付方式 | `ALIPAY`→支付宝、`WECHAT`→微信支付、`BANK`→银行转账 |
| `item_status` | 明细状态 | `OK`→正常、`SHORT`→缺货、`REPLACED`→已换货 |

模拟生成时要填的参数：`inspector`（模板里手填的审核人，不填显示「未填写」）、`orderNo`（`demoProvider` 用）、`channel`（`demoApi` 用）。

### Word 版：八个章节对应八类写法

| 章节 | 演示的写法 | 用到的块 |
| --- | --- | --- |
| 一、占位符写法 | 美元花括号 / 双层花括号 / `:默认值` / 键不存在也走默认值 / 手填参数 / 点号取嵌套 | `report`、`demoApi` |
| 二、明细行循环 | 行内标记、区域循环、段落块循环（同一份数据三种排版） | `items` |
| 三、嵌套循环 | 外层项自带 `items` 字段；两个扁平数组用 `where` 关联 | `orders`、`order_items` |
| 四、竖向合并 | `merge=1,2 by=material_code`；只写 `merge=1` 按文本比较 | `stock_items` |
| 五、字段值转换 | 数据块 `converters` 的转换结果对照（含字典与宿主自定义转换器） | `report` |
| 六、图片与二维码 | `imageFields` 出图；`qrCodes` 出码，中间小图取同块的 `qr_logo` | `report` |
| 七、三种数据源 | JAVA Provider / API / SQL 各来一段 | `demoProvider`、`demoApi`、其余 SQL 块 |
| 八、动态列 | 固定列 + 动态列混排；表头 `header` / 数据行 `data` 两种角色；数据行行首行尾标出块名；两行分组表头；列级 `width`；`span` 静态行；`_index` 序号 | `inspection` |

页眉页脚也放了占位符（`${report.title:未命名}` / `${report.report_no}`），顺带验证这两个位置同样生效。

几个值得单独说的点：

- **对照表的左列不能写 `${...}`**。模板里任何 `${}` 都会被当占位符吃掉，所以左列只写花括号**里面**的内容（如 `report.report_no`），右列才放真实占位符 —— 这样「写法」和「结果」才能并排看到。
- **段落块循环**（2.3）不用表格：开始标记、内容段、结束标记各占一段，中间段落整体重复。
- **`demoApi` 的 `request_body` 是对象**，所以 `${demoApi.request_body.channel}` 能逐级取到 body 里传进去的 `channel`，用来演示点号路径。
- **`material_code` 用的是宿主自定义转换器 `demoSku`**（example 里注册的 Bean，物料编码 → 物料名称），演示 `DocTemplateValueConverter` 的接入方式。

### Excel 版：七个工作表

| 工作表 | 演示的写法 |
| --- | --- |
| 占位符 | 整格占位符取到数字 → 写成**数字单元格**（可继续参与计算）；与文字混排 → 按文本替换 |
| 明细循环 | 行内标记（标记写在行尾单元格）/ 区域循环（标记行生成后删掉） |
| 嵌套循环 | 嵌套字段 + `where` 关联过滤 |
| 合并单元格 | 列字母写法 `merge=A,B by=material_code` / 列号写法 `merge=1`（不写 `by`，按文本比较） |
| 动态列 | 列数由列源决定；表头 `header` / 数据行 `data` 两种角色；分组表头用 `span` 盖住列区；列宽按权重分配，字体对齐全部沿用标记格 |
| 转换与字典 | 转换前后的原始值 → 显示值对照 |
| 图片与二维码 | 图片与二维码都必须**独占单元格**；循环体里逐行出图 |

- `report.total_qty` 故意**不配转换器**，就是用来演示「整格占位符取到数字写成数字单元格」——配了 `number` 转换器之后值变成字符串，反而写不进数字格。
- 图片字段 `pic` 声明在 `items` 块上，工作表「图片与二维码」里用区域循环逐行出图，`${pic}` 独占一格。
- 标题行用 `merge_cells` 横向合并（`A1:F1` 那种），生成时会随每份产出保留，和竖向 `merge=` 互不影响。

### 几个容易踩的点

- **图片字段会真的发起下载**。`logo` / `pic` / `qr_logo` 指向公网图片地址（5 秒超时、单张 5MB）。离线或内网环境请换成本地可访问的地址，否则**整次生成会 FAILED**（二维码的小图是例外：取不到只打 warn，二维码照常输出）。
- **`demoProvider` / `demoApi` 依赖 example 模块**。只有 `doc-template-example` 启动着才有数据；`demoApi` 回调的是 `127.0.0.1:9150`（示例宿主自己就是这个端口，容器里自调也成立），接口挂在别的地址时改 `url` 即可。换成自己的宿主时把这两个块删掉，其余块和文档结构不受影响。
- **区域标记必须独占一行 / 一段**。写成 `${order_no}{{#each items}}` 就不是区域了：生成时会报「没有配对的 `{{/each}}`」，因为行内循环要求两个标记写在同一行里。
- **`where` 右边是当前作用域的键**，不是字面量。`where order_no = order_no` 表示「明细的 order_no 等于当前订单的 order_no」；比字符串常量要加引号：`where status = 'open'`。
- **合并格默认靠上**。示例 SQL 给 `stock_items` 配了 `"mergeCellAlign": "center"`，删掉就回到靠上，三种取值见[合并单元格对齐](/guide/data-blocks#合并单元格对齐)。
- **标记格会被清空**。行内循环里写 `{{#each ...}}` 的那一格渲染完是空的，所以 `merge=` 不要指向它（要么指向别的列，要么用 `by=`）。

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

- **表格行循环**（12 处）：`units` / `items` / `metrics` / `issues` / `revisions` / `distribution` / `methods` / `issue_levels` / `scores` / `glossary` / `ref_docs` / `signers`。循环行的**第一格开头**写 `{{#each 块}}`、**最后一格末尾**写 `{{/each}}`，例如 `{{#each revisions}}${item.c1}` … `${item.c4}{{/each}}`。
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

## 依赖与坑

| 项 | 说明 |
| --- | --- |
| 业务表依赖 | 四套模板的数据块**都不依赖任何业务表**：要么是 `select` 常量 + `union all`，要么是 JAVA / API 块。执行完 `.sql` 就能「模拟生成」，不用建表、不用灌数 |
| 表结构 | `doc_template` / `doc_template_data_block` / `doc_template_dict` / `doc_template_dict_item` 由 Starter 自动创建（MySQL 8 / MariaDB / PostgreSQL）；其他库先执行 `db/schema.sql` |
| SQL 数据块开关 | `doc-template.data-block.sql-enabled=false` 时 SQL 块直接报错，示例里的块都跑不了 |
| 执行顺序 | 按 `sort_order` 依次执行，任一块失败整次生成就是 `FAILED`；调模板时可以先停用不相关的块 |
| 占位符取不到值 | 不报错，输出空串或默认值。想暴露问题就用「模拟生成」看实际参数列表 |
