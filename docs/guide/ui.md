# 内嵌管理界面

管理界面是 Vue 3 + Naive UI 的 SPA，构建产物随 Starter JAR 发布在 `META-INF/resources/doc-template/`，宿主用 iframe 打开即可。

## 入口与路由

| 地址 | 页面 |
| --- | --- |
| `/doc-template/` | 首页（跳转模板管理） |
| `/doc-template/#/templates` | 模板管理，数据块在模板编辑弹窗的第二个 Tab 中维护 |
| `/doc-template/#/generate-records` | 生成记录 |
| `/doc-template/#/dicts` | 数据字典，维护枚举值 → 显示名 |

端口不是 9150 时替换为实际端口。

## 页面能力

- **模板列表**：服务端分页（默认每页 10 条，可选 10/20/50/100），顶部统计卡片来自 `/templates/stats`。
- **编辑弹窗双 Tab**：主表放模板信息，子表放数据块；数据块配置表单化，每字段一个输入框 + 填入示例。字段说明收在右侧的「说明」侧栏里（默认收起；弹窗标题栏右上角、关闭按钮左边有一个侧边栏图标，点一下展开，再点收起），展开后按分组列出字段名、键名和说明，表单本身只留输入框。
- **编码可改**：模板编码、数据块编码在编辑态也能改，唯一性由后端校验（模板全局唯一，数据块在同一模板内唯一）。数据块改编码不动模板文件，所以编码一被改动，弹窗就会提示同步改模板里的 `${编码.字段}` / `{{#each 编码}}…{{/each}}`，否则生成时取不到这个块的数据。
- **字段值转换**：数据块弹窗里的独立区块，一行一个字段（选转换器 + 参数）。参数名由转换器固定，每个输入框都带中文 label；`dict` 的字典编码是可搜索下拉（选项来自「数据字典」页），不用手敲。宿主自定义的转换器在下拉里统一显示为「自定义转换器」，选中后在参数区用查询下拉挑具体编码。见 [字段值转换与字典](./converters.md)。
- **数据字典页**：主子表布局——左侧是字典列表（编码 / 名称 / 条目数，可搜索、批量删除），点一行选中后右侧维护该字典的 `值 → 显示名` 映射（按页加载，同样支持批量删除）；窄屏回退成上下结构。删字典会连带删掉它的全部映射。
- **模拟生成弹窗**：右侧按执行顺序列出数据块（数据块多时这一栏自己滚动，弹窗不再被撑长），左侧填数据源参数，缺参数提前黄色提示。
- **文件预览**：docx-preview / SheetJS 在浏览器内渲染 DOCX、XLSX，PDF 直接内嵌。
- **布局**：页面整体不滚动，侧边栏/顶栏固定高度，表格在卡片内滚动（表头和分页器保持可见）。
- **窄屏**：宽度 < 860px 时左侧栏隐藏，导航移到顶栏；再窄（< 560px）连标题下的说明一起收起，优先保住菜单。

## iframe 嵌入

```html
<iframe
  src="/doc-template/?tokenStorageKey=Admin-Token&tokenHeader=Authorization&tokenPrefix=Bearer&layout=bare"
  class="h-full w-full border-0">
</iframe>
```

| 查询参数 | 作用 | 默认值 |
| --- | --- | --- |
| `tokenStorageKey` | 从 `localStorage` 的哪个键读 token | `token` |
| `tokenHeader` | 用哪个请求头发送 | `Authorization` |
| `tokenPrefix` | token 前缀 | `Bearer` |
| `layout` | `full` 完整布局 / `bare` 只留内容区（无侧栏、无顶栏） | `full` |

### 关于左侧栏

页面默认带左侧栏（品牌、模板管理 / 生成记录 / 数据字典菜单、页脚、构建时间）。**直接嵌进宿主的内容区会出现两条导航**——宿主的菜单一条、这里的左侧栏一条，所以嵌入时应当传 `layout=bare`：

- `layout=bare`：**左侧栏和顶栏都不渲染**——没有导航，也没有标题，页面上只剩内容区。宿主既然已经嵌在内容区里，标题和导航都该由宿主提供。切页面由宿主的菜单负责——直接改 iframe 的 hash 地址即可：

  ```html
  <!-- 模板管理 -->
  <iframe src="/doc-template/?layout=bare#/templates"></iframe>
  <!-- 生成记录 -->
  <iframe src="/doc-template/?layout=bare#/generate-records"></iframe>
  ```

  也可以在宿主里给 iframe 的 `contentWindow.location.hash` 赋值（同源时）来切。
- 不传或 `layout=full`：完整布局，直接访问 `/doc-template/` 时就是这个样子。
- 值写错（例如 `layout=bar`）按 `full` 处理，不会把界面搞成半残。

::: tip 窄屏
窗口宽度小于 860px 时左侧栏会被隐藏，导航自动移到顶栏（只留文字，去掉图标），页面不会变成「进得去、出不来」。`layout=bare` 是例外——那个模式永远不显示任何导航。
:::

::: danger 不要把 token 放进 URL
URL 会进浏览器历史、日志和 Referer。token 只能通过同源 `localStorage`、Cookie 或严格校验 origin 的 `postMessage` 传递。
:::

地址栏参数优先于 `window.DOC_TEMPLATE_CONFIG`（它只影响这一次嵌入）。`apiBaseUrl` / `loginUrl` **不读**查询参数 —— 它们决定请求发到哪、401 跳到哪，允许被链接改写就等于留下钓鱼入口；这两项只能通过 `window.DOC_TEMPLATE_CONFIG` 配置，且那段脚本要在入口读取配置前执行。

## 构建与更新

源码在 `frontend/doc-template-ui`（内嵌页面的源码，产物随 Starter JAR 发布，源码本身不单独发包），构建产物直接输出到 Starter 的静态资源目录：

```bash
cd frontend
pnpm install
pnpm --filter doc-template-frontend-ui build
cd ../backend
mvn clean package
```

::: tip 排查「改完前端没变化」
完整布局下侧边栏底部会显示「构建 yyyy-MM-dd HH:mm:ss」，这是 `vite.config.ts` 用 `define` 注入的构建时刻。没变就说明浏览器加载的还是旧包 —— 需要重新 `vite build` 并重新打包重启。注意 `mvn spring-boot:run` 用的是 `target/classes`，只重启不重新打包同样不会更新。

`layout=bare` 没有侧栏和顶栏，构建时间戳输出到控制台：`[doc-template] build yyyy-MM-dd HH:mm:ss`。
:::

Starter 里只放构建产物（`index.html` + `assets/`），没有手写的静态兜底页；不跑前端构建就拿不到页面。

## 自己写页面

不想用内嵌界面时，宿主可以完全自建页面，只调 REST API：

- 模板 / 版本 / 数据块 / 生成记录接口见 [REST API](/reference/api)；
- 内嵌页面本身是 Vue 3 + Naive UI 的普通 SPA，直接调 `/api/doc-template/**`，没有额外的前端 SDK 依赖。
