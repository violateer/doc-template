# 数据块取数

数据块由「数据源配置」+「参数映射」两部分组成，三种类型共用这两个字段。

::: tip 推荐顺序
**`JAVA` > `API` > `SQL`。** `JAVA` 由宿主业务代码决定查什么，配置者只能在类型下拉里选 Provider；`SQL` 等于把一条 SQL 的执行权交给配置者，风险最大。
:::

| 类型 | 配置里有哪些字段 | 参数映射作用 |
| --- | --- | --- |
| `JAVA` | `provider`，取宿主应用里 `DocumentDataProvider#getCode()` 的值 | 原样传给 `execute(params)` |
| `SQL` | `sql` / `single` / `maxRows` / `dataSource`（**不推荐**） | 填充 SQL 里的 `:name` 占位 |
| `API` | `url` / `method` / `resultPath` / `timeoutMs` / `headers` / `query` / `body` | 替换 `{name}`、`${name}` 占位 |

## 在管理界面里配置

数据源配置和参数映射都是**按字段填表**，每个字段一个输入框；字段说明集中在弹窗右侧的「说明」侧栏里（默认收起），不占表单的位置：

- **每个字段一个输入框**，标签旁边带「填入示例」，点一下只填这一项。
- **说明侧栏**：弹窗标题栏右上角、关闭按钮左边的侧边栏图标控制展开 / 收起（默认收起），展开后按分组列出当前类型的字段名、JSON 键名和说明，内容与表单同一份字段定义，改一处两边都变。
- **控件按字段类型自动切换**：文本 / 多行文本 / 数字（带单位）/ 布尔（二选一，用下拉）/ 下拉 / 键值对 / JSON（只有 API 的请求体是 JSON，因为接口本身就要 JSON）。
- **一行放三个**：表单是三列网格，窄字段（布尔、数字、下拉、单行文本）一行排三个，多行文本 / JSON / 键值对占满整行；`合并单元格对齐`（整篇文档一份设置）也排在这张网格里。
- **下拉项来自后端**：`Provider 编码` 列宿主注册的 Provider，`数据源` 列可用的 `DataSource` Bean。
- **切换数据源类型**时配置项整套换掉，参数映射、图片字段、二维码字段与动态列保留。
- **字段名可以选**：图片字段、二维码字段、二维码中间小图、转换器这四处的字段名是「可输入下拉」，既能手填也能从候选里挑。
  候选来自这个数据块最近一次取数的结果：第一次点开字段名下拉会自动取一次（一个弹窗里只自动试一次），
  点「预览数据源」成功后也会填入，之后可用字段名旁边的刷新按钮重取；
  打开弹窗、敲键盘都不会发请求。取不到候选（结果为空、`$request.*` 参数没填、配置还没填完）就退回纯手输，不会卡住保存。
- **动态列**单独一个分组，里面是一个开关；关着时不会往配置里写任何东西，打开后才出现「列源类型」和该类型的配置项。
- **排序不用填**：新建的数据块排在最后，执行顺序就是列表顺序。
- 表单里没有的扩展项不会被丢掉，保存时原样带回，界面提示「还有 N 个扩展项」。

字段定义集中在一处，新增配置项只改那一个文件，界面自动跟着变。

## JAVA 数据源（推荐）

宿主实现 `DocumentDataProvider` 并注册为 Spring Bean，数据块里选它的 `getCode()`：

```java
@Component
public class DemoReportProvider implements DocumentDataProvider {
    @Override public String getCode() { return "demoReport"; }

    @Override public Object execute(Map<String, Object> params) {
        return Map.of(
            "report_no", params.get("reportNo"),
            "product_name", "示例产品"
        );
    }
}
```

- 返回值可以是单对象（主信息）或数组（明细，配合 `single: false` 语义用 `{{#each}}`）。
- 查哪张表、带什么条件全在业务代码里定，租户与数据权限规则全部生效。
- `params` 是模块注入的**唯一**输入（数据块参数映射 + 生成请求参数），Provider 不会额外收到调用方身份。要按当前用户过滤时，两种做法都行：Provider 里直接读宿主自己的登录上下文（它与调用同处一个请求线程），或让调用方把用户标识放进参数。
- 已注册的 Provider 可用 `GET /api/doc-template/system/providers` 查看。

## API 数据源

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
- `resultPath`：从响应 JSON 里取哪个节点（`data`、`data.rows`、`data.rows.0.name`），留空表示用整个响应。
- `timeoutMs`：默认取 `default-timeout-ms`（5000），上限由 `max-timeout-ms`（15000）兜住。
- 非 2xx 直接判定生成失败，错误信息里带状态码和响应体前 300 字符。

## SQL 数据源（不推荐）

::: danger 为什么不推荐
配置一条 SQL 数据块，等于把「这个数据源账号能查到的所有表」交给模板配置者：只要能打开数据块编辑页（`auth.mode: none` 时就是任何能访问到端口的人），就能写一条 `select * from biz_order`，再生成一份文档把结果导出去。

模块能做的是**拦住误配置和低级绕过**，做不到真正的数据隔离。`JAVA` 数据源没有这个问题。
:::

确实需要时：

```json
{
  "sql": "select report_no, product_name, inspect_result from qc_report where report_no = :reportNo",
  "single": true,
  "maxRows": 1,
  "dataSource": "primary"
}
```

- `sql`：单条查询语句，用 `:name` 命名占位，**不要拼字符串**；参数值一律走 JDBC 占位符绑定，不会进入 SQL 文本。
- `single`（界面上是「返回数据格式」下拉，选项 单条 / 多条）：`true` 按单条返回；`false` 按多条返回（结果是一个数组），配合模板里的 `{{#each 块编码}}…{{/each}}` 做明细行循环。
- `maxRows`：最多返回行数，默认取 `doc-template.data-block.default-max-rows`（500）。
- `dataSource`：可选，宿主应用里 `DataSource` 的 Bean 名称，默认用主数据源。

### 只读是怎么保证的

三层，由内到外：

| 层 | 做法 | 拦得住什么 |
| --- | --- | --- |
| 语句检查 | 剥离注释和字符串字面量后，首关键字必须是 `select` / `with`，代码区不得出现写操作、文件函数、锁行关键字，分号只允许出现在末尾 | `delete` / `update` / `drop`、`select file_read(...)`、`load_file`、`into outfile`、`pg_read_file`、`for update`、`select 1; drop table t` 这类多语句 |
| 语法校验 | JSqlParser 解析，**只有解析结果是 `Select` 才放行** | `with x as (select 1) delete from t` 这类 CTE 写操作 |
| 只读连接 | 执行前 `Connection.setReadOnly(true)`，执行完恢复再归还连接池 | 前两层漏掉的写操作（MySQL / PostgreSQL 会置为只读事务） |

MySQL 的 `/*! ... */` 可执行注释按**代码**参与检查——服务器会执行里面的内容，当注释剥掉就等于放行。`#` 行注释按注释处理，不会被判成语法错误。

以下写法都按合法查询放行：值里带分号（`where name = 'a;b'`）、语句前后带注释（`-- 说明` / `# 说明`）、结尾分号后跟注释（`select 1; -- 说明`）。

::: warning 这三层不是安全边界
解析器不认识的方言写法会直接报错（宁可拒也不放过），也有驱动只把 `setReadOnly` 当提示、并不真的生效。真正可靠的隔离是**给模块配只读数据库账号** —— 这是宿主侧唯一能兜住所有情况的措施。
:::

### 错误信息不回传数据库原文

SQL 执行失败时，接口和生成记录里只会看到：

```
SQL 执行失败（dataSource=primary），请检查 SQL 与数据源配置；详细原因见服务端日志
```

数据库原始报错（含表名、列名、SQL 片段）只写服务端日志，避免把盲注变成「显错注」。其他接口的兜底 500 同样只返回「服务内部错误，请查看服务端日志」，不回传异常内部信息。

### 关掉 SQL 数据源

```yaml
doc-template:
  data-block:
    sql-enabled: false
```

关闭后保存 SQL 数据块直接报错、执行已有 SQL 数据块也报错，`JAVA` / `API` 数据块不受影响。

## 参数映射

管理界面里按「参数名 / 值」成对添加，效果就是一组键值对：

```json
{
  "reportNo": "$request.reportNo",
  "lang": "zh-CN"
}
```

- 值以 `$request.` 开头 → 取生成时传入的 `requestParams` 里的同名值（支持 `a.b` 嵌套）。
- 其他值按字面量常量处理；`1`、`true`、`{...}`、`[...]` 这类写法会按对应类型解析。
- 数据源不需要入参时整个参数映射留空即可。

## 字段值转换

取到的值不一定能直接上文档：`0/1` 想显示成「是/否」、时间戳要格式化、金额要千分位、枚举要换中文。
在数据块配置里加 `converters` 即可，不用改 SQL，也不用在 Provider 里手写：

```json
{
  "provider": "demoReport",
  "converters": [
    { "field": "status", "type": "dict", "args": { "code": "order_status" } },
    { "field": "amount", "type": "number", "args": { "scale": 2, "grouping": true } }
  ]
}
```

模板里也能就地覆盖单个占位符：`${amount|number:2}`、`${status|dict(order_status)}`。
管理页里点「添加转换」即可配置，参数名固定、每个输入框都有中文说明，`dict` 的字典直接下拉选。

数据块这一侧的转换是**整列批量执行**的：同一字段（同一转换器、同一参数）在主表与明细里出现多少次，
转换器都只被调用一次，明细几百行也不会变成几百次查库。内置转换器、数据字典和宿主自定义扩展见
[字段值转换与字典](./converters.md)。

## 图片字段

数据块取到的值如果是一张图片，可以声明它是图片字段，渲染时替换成图片（而不是一串 URL/Base64 文本）：

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
- **字段名只写字段名本身**（如 `logo`），不要带块编码或路径：写 `items.photo` 会被直接拒绝并提示改成 `photo`。模板那边可以带前缀，`${logo}`、`${report.logo}`、明细行里的 `${item.logo}` 都命中同一个声明。
- 取值支持三种：`http(s)://` 地址（服务端下载）、`data:image/png;base64,...`、裸 Base64（可带换行，也支持 `base64:` 前缀）；SQL 查出 BLOB 时直接返回 `byte[]` 也可以。
- 尺寸：宽高都写 → 等比缩放进这个框（不拉伸变形）；只写一边 → 按比例算另一边；都不写 → 用原图尺寸，超过 480px 宽等比缩小。
- 对齐：`align` 支持 `center`（默认）/ `left` / `right`，**图片独占段落/单元格时生效**，和文字混排的段落保持模板原有对齐。大小写和同义词都认（`Left` / `start` / `centre`），**认不出来直接报错**，不会静默按居中处理。
- 留白：`padding` 是图片四周的边距（像素），默认 4；Excel 里会自动把行高撑到「图片高 + 上下留白」，列宽不够时按「图片宽 + 左右留白」加宽（只加不减）。
- 位置：DOCX 里写在段落/表格单元格里即可，和文字混排也支持（`签名：${logo} 完毕`）；XLSX 里需要占位符**独占单元格**。
- 明细行循环里的图片同样生效：模板行里写 `{{#each items}}${photo}{{/each}}`，数组每项的 `photo` 都按图片渲染。
- 值取不到（null / 空串）→ 按文本规则渲染，不会中断生成；值不是图片、下载失败、格式不支持 → 生成失败，错误信息里带字段名和原因。
- 支持的格式：png / jpg 原样写入，gif / bmp / tif 等会先转成 png；svg、webp 不支持。

图片渲染默认就开启，**不需要任何配置**：远程图片 5 秒超时、单张上限 5MB、未声明尺寸时最大 480px 宽。

## 二维码字段

数据块取到的是一个链接或编号、希望文档上直接出一张能扫的二维码时，声明 `qrCodes`：

```json
{
  "sql": "select verify_url, trace_url, logo from qc_report where report_no = :reportNo",
  "single": true,
  "qrCodes": [
    { "field": "verify_url", "width": 140, "logoField": "logo" },
    { "field": "trace_url", "logoUrl": "https://minio.example.com/logo.png", "level": "H" }
  ]
}
```

- 模板里写 `${report.verify_url}`，取到的值按文本编码成二维码图片；明细行里写 `{{#each items}}${qr}{{/each}}` 就逐行出码。
- 字段名和图片字段一样**只写字段名本身**（如 `verify_url`），带路径会被直接拒绝。
- 尺寸、对齐、留白与图片字段同一套：宽高都不写默认 140px；`align` 支持 `center` / `left` / `right`。
- 中间小图两种来源：`logoUrl`（图片地址）或 `logoField`（**同一数据块**里的另一个字段，写字段名即可）。两个都写时字段优先，取不到再回退地址。
- 带小图默认纠错级别 `H`，不带默认 `M`；`level` 可写 `L` / `M` / `Q` / `H`（大小写都行），写别的直接报错。小图占边长默认 22%（`logoRatio` 0.1 ~ 0.35）。
- 同一个字段同时声明成图片和二维码时，按二维码渲染。
- 二维码内容为空 → 按文本规则渲染，不中断；中间小图取不到 → 二维码照常输出，只是不叠加小图。
- 管理页数据块配置的「二维码字段」区块可直接维护，不需要手写 JSON。

## 合并单元格对齐

模板里用 `merge=` 竖向合并出来的那一格，内容默认靠上。想让它居中或靠下，在数据块配置里加一项：

```json
{
  "sql": "select material_code, batch_no from stock_item order by material_code",
  "single": false,
  "mergeCellAlign": "center"
}
```

只有三种取值，管的是**垂直**方向：

| 取值 | 含义 |
| --- | --- |
| `top`（默认） | 靠上 |
| `center` | 垂直居中 |
| `bottom` | 靠下 |

- **水平方向不动**：合并格沿用模板里那一格原本的水平对齐，靠右的数字列、居中的状态列不会因为竖向合并被打乱。
- 键名也认 `merge_cell_align` / `mergeAlign`；`middle` / `mid` / `centre` 都认作 `center`，大小写不敏感。
- 老配置里带水平词的写法（`middle-center`、`top-right`）只取垂直部分，水平词忽略——`middle-center` 等价于 `center`。
- 不配（或配成 `top`）时**一个字都不改**，保持模板原样 —— 模板作者自己设的缩进、行距不会被搅乱。
- 只影响 `merge=` 合并出来的格子，循环里普通单元格的对齐还是模板说了算。
- **对齐是整篇文档一份设置**，不按数据块区分：多个块都配了时以第一个为准，配得不一致会打一条 warn。
- 认不出来的写法按默认值处理并打一条 warn，不会让生成失败。

## 动态列

表格的列数不固定时（检验项有几条、规格有几档由数据决定），给数据块加一个 `columns` 对象，
列定义就从数据源来，模板里只留位置（写法见[动态列](./template-syntax#动态列)）：

```json
{
  "sql": "select item_name, item_value from inspection_item where report_no = :reportNo",
  "single": false,
  "columns": {
    "type": "SQL",
    "sql": "select field, label, width from item_column order by seq"
  }
}
```

| 键 | 说明 |
| --- | --- |
| `type` | 列源用哪种取数方式：`JAVA` / `API` / `SQL`，**不写就沿用数据块自己的类型** |
| 其余键 | 与主数据源**同名同义**（`provider` / `url` / `sql` / `dataSource` / `maxRows` …） |
| `single` | 列源永远是数组，这一项对它没有意义，写了也忽略 |

- **参数映射共用块级的那一份**（`param_config`），不用给列源再配一遍；`$request.*` 在两边都能用。
- 列源返回的是数组，每一项描述一列：

| 元素键 | 必填 | 说明 |
| --- | --- | --- |
| `field` | ✅ | 取值字段名，只认 `[A-Za-z0-9_.]+`（点号可写嵌套路径） |
| `label` | | 列标题，留空用 `field` |
| `width` | | **相对权重**，缺省 1。DOCX 总表宽不变、按权重分配；XLSX 以起始列宽为基准乘出 N 列 |

- 元素也可以直接写字符串：`["jan","feb"]` 等价于 `[{field:"jan",label:"jan"},{field:"feb",label:"feb"}]`。
- `label` **不走转换器**：列标题是配置不是业务值，不会被 `converters` 加工。
- **样式不在这里配**：字体、字号、边框、底纹、对齐全部沿用模板里写标记的那一格 ——
  表头格的样子由 `{{#columns 块 header}}` 那格决定，数据格的样子由 `{{#columns 块 data}}` 那格决定。
  想让数值靠右，就把模板里数据标记那一格设成右对齐，列源不用管。
- 列定义重复（字段名忽略大小写）会直接报错；超过 `maxColumns`（默认 200）也报错，这是防呆的性能护栏。
- 列定义是空数组时，表头行和数据行按空数组处理（删行 / 删格），和 `{{#each}}` 一致。
- 不配 `columns` 时行为完全不变，只有模板里写了 `{{#columns}}` 才会提示「没有配置列源」。

::: tip 固定列不用配置
列区两侧的固定列直接写在模板里，`${字段}` 照常取数据块的字段，和动态列共用同一行数据。
:::

## 执行规则

- 生成时按 `sort_order`、`id` 依次执行状态为 `ENABLED` 的数据块，结果以**块编码**为命名空间放进渲染参数：块编码 `report` + `single: true` 时，模板里写 `${report.report_no}`。
- 任一数据块执行失败，整次生成记为 `FAILED`，错误信息形如「数据块「检验报告主信息」执行失败：…」，可在「生成记录」里直接看到。
- 请求里手填的参数优先级高于数据块结果；但**空串不覆盖**数据块取到的值 —— 模拟生成弹窗不会给数据块管理的占位符补空串，避免把取数结果冲掉。
- 安全边界：SQL 数据块要过语句白名单 + 只读连接，但**仍不推荐使用**，可用 `sql-enabled: false` 整体关掉；API 只允许 `http`/`https`，可用 `allowed-hosts` 限制主机。

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
