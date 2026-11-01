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
 * Converts rendered DOCX/XLSX files to PDF with <a href="https://github.com/mini-software/MiniPdf">MiniPdf</a>.
 *
 * <p>MiniPdf is a pure-Java, Apache-2.0 licensed converter: it renders the OOXML model itself and
 * writes the PDF through PDFBox, so no Microsoft Office, LibreOffice or WPS installation is needed
 * at runtime. A CJK capable font is registered once per process (auto-detected from the host, or
 * supplied through {@code doc-template.pdf-font}) because the PDF standard fonts cannot render
 * Chinese characters.</p>
 */
public class DocTemplatePdfRenderer {

    private static final Logger log = LoggerFactory.getLogger(DocTemplatePdfRenderer.class);

    /** {@code ST_UniversalMeasure}：数值 + 单位，例如 {@code 21cm}、{@code 842pt}。 */
    private static final Pattern UNIVERSAL_MEASURE =
            Pattern.compile("(-?\\d+(?:\\.\\d+)?)\\s*(mm|cm|in|pt|pc|pi)");

    /** Fonts that cover Chinese text; the first readable candidate is registered with MiniPdf. */
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
     * Renders the given DOCX/XLSX bytes into PDF bytes. The real format is detected from the
     * content (MiniPdf does the same); {@code fileType} is only a fallback when detection fails.
     */
    public byte[] render(byte[] source, String fileType) {
        if (ArrayUtil.isEmpty(source)) {
            throw new IllegalArgumentException("PDF 转换失败：源文件内容为空");
        }
        registerFontIfNeeded();
        // MiniPdf keeps process-wide font state, so conversions are serialised to stay safe
        // under concurrent requests. PDF export is a small fraction of the overall workload.
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
     * Renders a DOCX through MiniPdf's structured renderer, bypassing a hidden shortcut in
     * {@code MiniPdf.convertBytesToPdf}.
     *
     * <p>MiniPdf 0.4.1 decides between two very different DOCX pipelines: the structured one
     * (POI + PDFBox, keeps font size / bold / alignment / colours / tables) and a plain-text
     * fallback that prints every paragraph at a fixed 11pt, left aligned, without any formatting.
     * The switch is a table count — a document without a single table always takes the plain
     * fallback, so a heading-only template comes out of the PDF as unstyled text. Calling the
     * structured renderer directly keeps the styling for such documents.</p>
     *
     * @return the PDF bytes, or {@code null} when the internal entry point is unavailable and the
     *         caller should fall back to the public API
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
     * Converts one {@code pgSz} attribute into points.
     *
     * <p>{@code w}/{@code h} are typed as {@code ST_TwipsMeasure}, a union of a plain twips count
     * and a universal measure such as {@code 21cm}; the POI schema therefore exposes them as
     * {@link Object}, not as a number. Integers are twips (1/20 pt), strings carry a unit.</p>
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
     * Removes worksheets that carry no content, so they do not turn into blank PDF pages.
     *
     * <p>A sheet counts as blank when it has no cell value, no merged region and no drawing.
     * At least one sheet is always kept; the rest of the workbook is handed to MiniPdf unchanged.
     * Any failure here falls back to the original bytes — dropping blank pages is cosmetic and
     * must never break the export.</p>
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

    /** Name of the font registered with MiniPdf, {@code null} when none was found. */
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
                // Conversion still runs with the PDF standard fonts; Latin text remains readable.
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
