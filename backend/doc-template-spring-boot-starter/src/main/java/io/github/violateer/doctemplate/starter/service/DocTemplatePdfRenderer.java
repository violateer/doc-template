package io.github.violateer.doctemplate.starter.service;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.StrUtil;
import io.github.violateer.doctemplate.starter.autoconfigure.DocTemplateProperties;
import io.github.minisoftware.minipdf.ConversionOptions;
import io.github.minisoftware.minipdf.MiniPdf;
import io.github.minisoftware.minipdf.MiniPdfException;
import io.github.minisoftware.minipdf.OfficeFormat;
import io.github.minisoftware.minipdf.PageSize;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSectPr;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.lang.reflect.Method;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 用 <a href="https://github.com/mini-software/MiniPdf">MiniPdf</a> 把渲染好的 DOCX / XLSX 转成 PDF。
 *
 * <p>MiniPdf 是纯 Java、Apache-2.0 协议的转换器：它自己解析 OOXML 模型，再通过 PDFBox 写 PDF，
 * 所以运行期不需要装 Microsoft Office、LibreOffice 或 WPS。PDF 标准字体渲染不了中文，
 * 因此每个进程启动时注册一次中文字体（从操作系统自动探测，或用 {@code doc-template.pdf-font} 指定）。</p>
 */
public class DocTemplatePdfRenderer {

    private static final Logger log = LoggerFactory.getLogger(DocTemplatePdfRenderer.class);

    /** {@code ST_UniversalMeasure}：数值 + 单位，例如 {@code 21cm}、{@code 842pt}。 */
    private static final Pattern UNIVERSAL_MEASURE =
            Pattern.compile("(-?\\d+(?:\\.\\d+)?)\\s*(mm|cm|in|pt|pc|pi)");

    /** 能覆盖中文的字体；按顺序取第一个读得到的注册给 MiniPdf。 */
    private static final String[] FONT_CANDIDATES = {
            "C:/Windows/Fonts/simhei.ttf",
            "C:/Windows/Fonts/Deng.ttf",
            "C:/Windows/Fonts/msyh.ttc",
            "C:/Windows/Fonts/simsun.ttc",
            "C:/Windows/Fonts/NotoSansSC-VF.ttf",
            "/System/Library/Fonts/Supplemental/Songti.ttc",
            "/System/Library/Fonts/PingFang.ttc",
            "/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc",
            "/usr/share/fonts/truetype/wqy/wqy-microhei.ttc",
            "/usr/share/fonts/truetype/arphic/uming.ttc",
            "/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttc",
            "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc"
    };

    private final DocTemplateProperties properties;
    private final Object lock = new Object();
    private volatile boolean fontRegistered;
    private volatile String registeredFont;

    public DocTemplatePdfRenderer(DocTemplateProperties properties) {
        this.properties = properties;
    }

    /**
     * 把 DOCX / XLSX 字节转成 PDF 字节。真实格式按文件内容识别（MiniPdf 也是这么做的），
     * {@code fileType} 只在识别不出来时兜底。
     */
    public byte[] render(byte[] source, String fileType) {
        if (ArrayUtil.isEmpty(source)) {
            throw new IllegalArgumentException("PDF 转换失败：源文件内容为空");
        }
        registerFontIfNeeded();
        // MiniPdf 的字体状态是进程级的，这里串行转换，避免并发请求互相踩。
        // PDF 导出只占整体工作量的一小部分，串行的代价可以接受。
        synchronized (lock) {
            try {
                OfficeFormat format = detectFormat(source, fileType);
                // Excel 里留着的空 sheet 会被 MiniPdf 当成一页渲染，变成 PDF 里的空白页，
                // 转换前先把完全没有内容的 sheet 去掉。
                byte[] prepared = format == OfficeFormat.XLSX ? dropBlankSheets(source) : source;
                if (format == OfficeFormat.DOCX) {
                    byte[] structured = renderStructuredDocx(prepared);
                    if (structured != null) return structured;
                }
                return MiniPdf.convertBytesToPdf(prepared);
            } catch (MiniPdfException error) {
                String detail = StrUtil.blankToDefault(error.getMessage(), error.getClass().getSimpleName());
                throw new IllegalStateException("PDF 转换失败（" + fileType + "）：" + detail, error);
            }
        }
    }

    /** 按内容判断真实格式，识别不出来时退回扩展名。 */
    private static OfficeFormat detectFormat(byte[] source, String fileType) {
        try {
            OfficeFormat detected = MiniPdf.detectOfficeFormat(source);
            if (detected != null && detected != OfficeFormat.UNKNOWN) return detected;
        } catch (MiniPdfException | RuntimeException ignored) {
            // 识别失败不阻断转换，交给扩展名兜底
        }
        if ("XLSX".equalsIgnoreCase(fileType)) return OfficeFormat.XLSX;
        if ("DOCX".equalsIgnoreCase(fileType)) return OfficeFormat.DOCX;
        return OfficeFormat.UNKNOWN;
    }

    /**
     * 直接调 MiniPdf 的结构化渲染器，绕过 {@code MiniPdf.convertBytesToPdf} 里的一个隐藏捷径。
     *
     * <p>MiniPdf 0.4.1 对 DOCX 有两套差别很大的管线：结构化的一套（POI + PDFBox，保留字号、
     * 加粗、对齐、颜色、表格），以及纯文本兜底的一套（所有段落固定 11 磅、左对齐、不带任何格式）。
     * 两者之间的切换条件只是「表格数量」—— 一个表格都没有的文档一定走纯文本兜底，于是只有标题的
     * 模板导出后成了没有样式的文字。对这类文档直接调结构化渲染器，样式就能保住。</p>
     *
     * @return PDF 字节；内部入口不可用时返回 {@code null}，由调用方退回公开 API
     */
    private byte[] renderStructuredDocx(byte[] source) {
        Method renderer = StructuredDocxRenderer.METHOD;
        if (renderer == null) return null;
        try {
            Object pdf = renderer.invoke(null, source, ConversionOptions.defaults(), pageSizeOf(source));
            if (pdf instanceof byte[] bytes && bytes.length > 0) return bytes;
        } catch (ReflectiveOperationException | RuntimeException error) {
            // 反射入口随 MiniPdf 版本变化时不让导出失败，退回公开 API（样式会丢，但文件能出）
            log.warn("结构化 DOCX 渲染不可用，改用默认转换（可能丢失样式）：{}", error.getMessage());
        }
        return null;
    }

    /** 读取 DOCX 的纸张尺寸（pgSz 单位是 twips，1/20 磅），读不到就用 A4。 */
    private static PageSize pageSizeOf(byte[] source) {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(source))) {
            CTSectPr sectPr = document.getDocument().getBody().getSectPr();
            if (sectPr != null && sectPr.getPgSz() != null) {
                float width = pointsOf(sectPr.getPgSz().getW());
                float height = pointsOf(sectPr.getPgSz().getH());
                if (width > 0f && height > 0f) {
                    return PageSize.of(width, height);
                }
            }
        } catch (Exception ignored) {
            // 解析失败不阻断转换
        }
        return PageSize.A4;
    }

    /**
     * 把一个 {@code pgSz} 属性换算成磅。
     *
     * <p>{@code w} / {@code h} 的类型是 {@code ST_TwipsMeasure}：既可以是一个纯 twips 数值，
     * 也可以是带单位的通用度量（例如 {@code 21cm}），所以 POI 的 schema 里它们是
     * {@link Object} 而不是数字。整数按 twips 处理（1/20 磅），字符串自带单位。</p>
     */
    private static float pointsOf(Object measure) {
        if (measure instanceof Number number) {
            return number.floatValue() / 20f;
        }
        if (measure == null) return 0f;
        Matcher matcher = UNIVERSAL_MEASURE.matcher(measure.toString().trim());
        if (!matcher.matches()) return 0f;
        float value = Float.parseFloat(matcher.group(1));
        return switch (matcher.group(2)) {
            case "mm" -> value * 72f / 25.4f;
            case "cm" -> value * 72f / 2.54f;
            case "in" -> value * 72f;
            case "pc", "pi" -> value * 12f;
            default -> value; // pt
        };
    }

    /** MiniPdf 内部的 DOCX 结构化渲染器，首次使用时解析一次。 */
    private static final class StructuredDocxRenderer {
        static final Method METHOD = locate();

        private static Method locate() {
            try {
                Method method = Class.forName("io.github.minisoftware.minipdf.internal.docx.PoiDocxRenderer")
                        .getDeclaredMethod("render", byte[].class, ConversionOptions.class, PageSize.class);
                method.setAccessible(true);
                return method;
            } catch (ReflectiveOperationException | RuntimeException error) {
                log.debug("MiniPdf 未提供结构化 DOCX 渲染入口，使用默认转换：{}", error.getMessage());
                return null;
            }
        }
    }

    /**
     * 去掉完全没有内容的 sheet，免得它们在 PDF 里变成空白页。
     *
     * <p>没有单元格值、没有合并区域、也没有图形的 sheet 才算空。至少保留一个 sheet，
     * 其余内容原样交给 MiniPdf。这里出任何问题都退回原始字节 —— 去空白页只是排版优化，
     * 不能因此让导出失败。</p>
     */
    private static byte[] dropBlankSheets(byte[] source) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(source))) {
            boolean removed = false;
            for (int index = workbook.getNumberOfSheets() - 1; index >= 0; index--) {
                if (workbook.getNumberOfSheets() <= 1) break;
                if (!isBlankSheet(workbook.getSheetAt(index))) continue;
                workbook.removeSheetAt(index);
                removed = true;
            }
            if (!removed) return source;
            try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                workbook.write(out);
                return out.toByteArray();
            }
        } catch (Exception error) {
            log.warn("XLSX 空 sheet 预处理失败，按原文件转换 PDF：{}", error.getMessage());
            return source;
        }
    }

    private static boolean isBlankSheet(XSSFSheet sheet) {
        if (sheet.getDrawingPatriarch() != null) return false;
        if (!sheet.getMergedRegions().isEmpty()) return false;
        for (Row row : sheet) {
            for (Cell cell : row) {
                // 只有样式没有值的单元格也算空
                if (cell.getCellType() != CellType.BLANK) return false;
            }
        }
        return true;
    }

    /** 注册给 MiniPdf 的字体名；一个都没找到时为 {@code null}。 */
    public String registeredFont() {
        return registeredFont;
    }

    private void registerFontIfNeeded() {
        if (fontRegistered) return;
        synchronized (lock) {
            if (fontRegistered) return;
            try {
                String path = firstReadableFont(properties.getPdfFont());
                if (StrUtil.isNotBlank(path)) {
                    String name = StrUtil.blankToDefault(properties.getPdfFontName(), FileUtil.mainName(path));
                    MiniPdf.registerFont(name, FileUtil.readBytes(path));
                    registeredFont = name;
                }
            } catch (Exception ignored) {
                // 注册失败也照常转换，只是退回 PDF 标准字体：中文会缺字，英文仍能看。
            } finally {
                fontRegistered = true;
            }
        }
    }

    private static String firstReadableFont(String configured) {
        if (StrUtil.isNotBlank(configured)) {
            File file = FileUtil.file(configured);
            if (file.isFile() && file.canRead()) return file.getAbsolutePath();
        }
        for (String candidate : FONT_CANDIDATES) {
            if (FileUtil.exist(candidate)) return candidate;
        }
        return null;
    }
}
