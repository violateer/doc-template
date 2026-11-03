# PDF 导出

PDF 由内置引擎直接生成，**不需要安装 Office、LibreOffice 或 WPS**，也没有额外的命令行依赖。

- 转换使用开源组件 [MiniPdf](https://github.com/mini-software/MiniPdf)（Apache-2.0，纯 Java，Maven 坐标 `io.github.mini-software:minipdf`）；
- 它基于 POI + PDFBox 自行排版，支持 DOCX / XLSX / PPTX；
- 触发方式：生成时 `exportType: "PDF"`，或模板上把 `exportType` 设为 `PDF`。

## 中文字体

PDF 标准字体不含中文字形，因此 Starter 在首次导出时会自动从系统里挑一个中文字体注册给转换引擎：

| 平台 | 尝试顺序 |
| --- | --- |
| Windows | `simhei.ttf` → `Deng.ttf` → `msyh.ttc` → `simsun.ttc` |
| macOS / Linux | 宋体 → 苹方 → 文泉驿 → Noto CJK |

指定字体：

```yaml
doc-template:
  pdf-font: C:/Windows/Fonts/simhei.ttf   # 可选，留空自动探测
  pdf-font-name: SimHei                   # 可选，字体族名，默认取文件名
```

::: danger 容器化部署
镜像内必须存在上述任一字体文件（例如把 `simhei.ttf` 一起打进镜像），否则中文会缺字。
:::

## 两处预处理

导出前会做两处处理，让 PDF 尽量和原文件一致：

### DOCX 保留样式

字号、加粗、对齐、颜色、图片、页眉页脚都按原文件保留，与文档里有没有表格无关；纸张尺寸按 DOCX 里的 `sectPr` 还原（A4 / A3 / 自定义 / 横向）。

### XLSX 跳过空 sheet

工作簿里留着但完全没有内容的 sheet（无单元格值、无合并区域、无图片）不会出现在 PDF 里，不会多出空白页；至少保留一个 sheet，处理失败时按原文件转换，不影响导出。

## 能力边界

MiniPdf 面向实用文档转换，并非完整的 Office 版式兼容实现：

| 内容 | 还原度 |
| --- | --- |
| 常规段落、表格、图片 | 较好 |
| 文本框叠放、艺术字 | 可能与 Word 有差异 |
| 公式重算 | 不做（模板里的公式不重新计算） |
| 目录 / 域 | 不更新 |
