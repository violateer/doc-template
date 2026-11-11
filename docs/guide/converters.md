# 字段值转换与数据字典

数据库里存的是 `0/1`、时间戳、`PAID` 这种机器值，文档上要显示「是」「2026-10-30」「已支付」。
这两项能力**无需修改 SQL、也不在 Provider 里写转换代码**，声明即可得到正确的显示值。

::: tip 两个位置，一个意思
**数据块配置**（`data_source_config.converters`）管这个块取到的所有字段；
**模板占位符**（`${amount|number:2}`）管单个位置，优先级更高 —— 渲染发生在取数之后。
:::

## 数据块里声明

管理界面的数据块弹窗里有「字段值转换」区块：一行一个字段，选转换器、填参数，点「示例」自动填一组能跑的参数。每一行就是 `field`（对哪个字段）、`type`（用哪个转换器）、`args`（该转换器的参数）三项：

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

- `field` 写 `"a,b"` 一次覆盖多个字段；也支持对象写法 `{"status": {"type": "dict", "args": {...}}}`。
- 结果里的**嵌套对象和数组都会递归处理**，`{{#each items}}` 明细行里的字段同样生效。
- 转换在取数之后统一做，`JAVA` / `SQL` / `API` 三种数据源行为一致；预览数据源时看到的就是转换后的值。

## 模板里声明

```text
${amount|number:2}                          → 12,345.00（视 grouping 而定）
${amount|number(scale=2, grouping=true)}    → 具名参数写法
${created_at|date(yyyy年MM月dd日)}           → 2026年10月30日
${status|dict(order_status)}                → 已支付
${name|trim|upper}                          → 串联，按顺序执行
${amount:0|number:2}                        → 默认值也走转换链，输出 0.00
```

- `|` 是转换链分隔符，`:` 仍然是「键:默认值」，所以**默认值写在转换链之前**。
- 位置参数顺序：`date(pattern)`、`number(scale, grouping)`、`boolean(trueText, falseText)`、
  `percent(scale, multiply)`、`mask(keepStart, keepEnd, mask)`、`dict(code)`。宿主自定义转换器请用具名参数。
- 占位符探测（模拟生成弹窗）看到的是去掉转换链后的字段名：`${amount|number:2}` 填的就是 `amount`。
- **默认值里不要写 `|`**：它会被当成转换链分隔符。转换器编码写错会直接报错，不会静默输出原文。

## 内置转换器

| 编码 | 作用 | 参数（都可选） |
| --- | --- | --- |
| `date` | 时间格式化 | `pattern`，默认 `yyyy-MM-dd` |
| `number` | 小数位 / 千分位 / 前后缀 | `scale`、`grouping`（默认 `false`）、`rounding`（默认 `half_up`，写错按默认值并告警）、`prefix`、`suffix` |
| `boolean` | `0/1`、`Y/N` → 文本 | `trueText`（默认「是」）、`falseText`（默认「否」） |
| `percent` | 小数 → 百分比 | `scale`（默认 2）、`symbol`（默认 `%`）、`multiply`（默认 `true`） |
| `upper` / `lower` | 大小写 | 无 |
| `trim` | 去首尾空白 | `collapse=true` 顺带压缩中间空白 |
| `mask` | 手机号 / 身份证脱敏 | `mode`（`phone` / `idcard`，写错按默认位数并告警）、`keepStart`、`keepEnd`、`mask` |
| `dict` | 按字典编码换显示名 | `code`（必填）、`fallback` |

::: warning 转换失败不会中断生成
认不出来的值**原样输出**：`date` 收到「不适用」、`number` 收到「—」都只是不转，不会让整篇文档失败。
但**参数写错**（日期格式非法、`dict` 少了 `code`、转换器编码不存在）会直接报错，错误信息带转换器名，生成记录里能看到。
`mask` 的 `mode` 和 `number` 的 `rounding` 是例外：写错不失败，按默认位数 / `half_up` 处理，只在日志里告警一次（同一个写错的词不会反复刷）。
:::

## 数据字典

枚举值不用再在 SQL 里 `case when`。字典是**全局资源**（不属于某个模板），管理页在左侧菜单「数据字典」。

字典用**主子表**：主表配字段编码，子表配 `值 → 显示名` 的映射。一个 `dict_code` 对应一本字典，一本字典下挂多条映射。

| 表 | 列 | 说明 |
| --- | --- | --- |
| `doc_template_dict`（主表） | `dict_code` | 字典编码，如 `order_status`，全局唯一 |
| | `dict_name` | 字典名称，仅管理页展示 |
| | `remark` | 备注 |
| `doc_template_dict_item`（子表） | `dict_id` | 归属哪本字典 |
| | `item_value` | 映射前的原始值，如 `PAID` |
| | `item_label` | 文档里显示的文字，如 `已支付` |
| | `sort_order` | 列表顺序 |

`(dict_id, item_value)` 唯一，同一本字典里值不能重复；不同字典可以有同名值（`OK` 在每本字典里各配各的）。

管理页左侧是字典列表（可搜索、带条目数），点一行选中后，右侧维护这本字典的映射；映射按页加载，字典项再多也不会一次拉全量。两侧各自独立增删改。

**查不到值时**由 `args.fallback` 决定：默认 `original`（原样输出），也可写 `blank`（空串）或任意字面量（如 `未知`）。
字典编码写错不抛异常，只走 fallback —— 少建一本字典不该让整篇文档生成失败。

**缓存**：同一次生成里每本字典只查一次库（明细行几百行也只发一条 `SELECT`），生成结束即清理，
不会把旧字典带到下一次请求。改完字典立即生效，不用重启。

## 宿主自定义转换器

实现 `DocTemplateValueConverter`（在 `doc-template-core` 里，零依赖）并注册成 Spring Bean：

```java
@Component
public class OrderStateConverter implements DocTemplateValueConverter {
    @Override public String code() { return "orderState"; }
    @Override public Object convert(Object value, Map<String, Object> args) {
        return switch (String.valueOf(value)) {
            case "1" -> "待付款";
            case "2" -> "已付款";
            default -> value;   // 认不出来就返回原值
        };
    }
}
```

- `code()` 就是配置里的 `type`，**严格区分大小写**：`code()` 返回 `demoSku` 时，配置和模板里都得写
  `demoSku`，写 `Demosku` 会直接报「未知的转换器」并列出可用编码。**同名会覆盖内置实现**，启动日志会打一条 info。
- `args` 是配置里的 `args`（模板占位符里的具名参数也会并进来），不会为 `null`。
- 注册后 `GET /system/converters` 就能看到，列表返回的即 `code()` 中声明的写法，原样返回、原样匹配。
  管理页的「转换器」下拉不逐个列出各转换器编码，而是统一显示
  一项**「自定义转换器」**；选中后在下面的参数区用查询下拉挑具体是哪一个，参数按「参数名 / 参数值」填
  （宿主转换器没有参数元数据，界面不知道它有哪些参数）。

### 需要查库 / 调接口的转换器：覆盖 convertBatch

数据块的 `converters` **不是逐个调用的**。整列值会先收集起来，一次交给 `convertBatch(values, args)`；
只有模板占位符（`${a|b:1}`）才一个值调一次 `convert`。默认实现就是循环调 `convert`，所以不覆盖也能用 ——
但转换里如果访问外部资源，不覆盖就会变成「明细 500 行 = 500 次查询」：

```java
@Component
public class SkuNameConverter implements DocTemplateValueConverter {
    private final SkuClient client;

    @Override public String code() { return "skuName"; }

    @Override public Object convert(Object value, Map<String, Object> args) {
        return resolve(List.of(value), args).get(0);       // 模板占位符场景
    }

    @Override public List<Object> convertBatch(List<Object> values, Map<String, Object> args) {
        return resolve(values, args);                      // 整列只发一次请求
    }

    private List<Object> resolve(List<Object> values, Map<String, Object> args) {
        List<String> codes = values.stream().map(String::valueOf).distinct().toList();
        Map<String, String> names = client.namesByCodes(codes);   // ← 一次远程调用
        return values.stream().map(v -> names.getOrDefault(String.valueOf(v), v)).toList();
    }
}
```

- 返回的 list 必须**与入参等长同序**，长度对不上会直接报错，不会静默错位。
- 分组按「转换器 + 参数」合并：`{"field":"a,b"}`、两行同参数配置、甚至主表与明细里的同名字段，都在同一批里。
- 入参可能含 `null`；空列表不会被调用。
- 内置的 `dict` 就是这么做的（整列取一次字典，再在内存里查表）。
- 完整可运行的样板在 `doc-template-example` 的 `DemoSkuConverter`（`demoSku`），启动示例应用后它会出现在转换器下拉里，
  日志会打印「一次收到 N 个值」。

::: tip 界面上的参数是固定的
管理页「字段值转换」里，内置转换器的参数名由程序固定，每个输入框都有中文 label（小数位、千分位、脱敏方案…），
`dict` 的字典编码是**可搜索下拉**（选项来自 `/dicts`），不需要记参数名、也不用背编码。
换转换器时参数会整体重置，只带上新转换器自己声明的默认值（日期格式、脱敏方案这类）；
上一个转换器填过的参数不会留在配置里，点到当前已选中的转换器不会清空已填好的值。
:::

::: tip MySQL / MariaDB / PostgreSQL / SQL Server / Oracle 之外的宿主
字典两张表 `doc_template_dict` / `doc_template_dict_item` 跟着 Starter 自动建表；其他数据库需要自己执行建表脚本（`db` 目录下那几份的写法可作参考）。
:::
