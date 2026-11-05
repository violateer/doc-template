package io.github.violateer.doctemplate.starter.service;

import cn.hutool.core.io.IoUtil;
import cn.hutool.core.util.StrUtil;
import io.github.violateer.doctemplate.starter.support.DocTemplateImageSpec;
import io.github.violateer.doctemplate.starter.support.DocTemplateJson;
import io.github.violateer.doctemplate.starter.support.DocTemplateQrCodeSpec;
import org.apache.poi.ss.usermodel.Workbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 把占位符的值转成可以写进 DOCX / XLSX 的图片。
 *
 * <p>支持三种取值：</p>
 * <ul>
 *   <li>URL：{@code http://...} / {@code https://...}，由服务端下载（5 秒超时、单张上限 5MB）；</li>
 *   <li>Data URI：{@code data:image/png;base64,...}；</li>
 *   <li>裸 Base64（可以带换行/空格，也支持 {@code base64:} 前缀），以及数据库 BLOB 返回的 {@code byte[]}。</li>
 * </ul>
 *
 * <p>值取不到（null / 空串）时返回 {@code null}，由调用方决定回退成文本还是留空；值明显不是图片、
 * 下载失败、格式不支持时抛异常——图片坏了却静默留白，排查成本极高。</p>
 *
 * <p>非 PNG / JPEG 的图片（gif、bmp、tif 等）会先用 ImageIO 转成 PNG 再写入，
 * 避免 OOXML 对图片类型的兼容差异。</p>
 */
public class DocTemplateImageLoader {

    private static final Logger log = LoggerFactory.getLogger(DocTemplateImageLoader.class);

    /** 1 像素 = 9525 EMU（OOXML 固定换算）。 */
    public static final int EMU_PER_PIXEL = 9525;

    private static final Pattern DATA_URI = Pattern.compile("^data:([^;,]*)(?:;[^,]*)?,(.*)$", Pattern.DOTALL);
    private static final Pattern BASE64_CHARS = Pattern.compile("^[A-Za-z0-9+/=\\s]+$");
    private static final int MAX_SIDE_PX = 4000;
    /** 下载远程图片的超时（毫秒）。 */
    private static final int DOWNLOAD_TIMEOUT_MS = 5000;
    /** 单张图片的大小上限，默认 5MB。 */
    private static final int MAX_BYTES = 5 * 1024 * 1024;
    /** 未声明尺寸时的最大宽度（像素），超过即等比缩小。 */
    private static final int DEFAULT_MAX_WIDTH = 480;

    /** 解码后的图片：字节、POI 图片类型、写进文档的文件名、最终像素尺寸。 */
    public record ImagePayload(byte[] bytes, int pictureType, String fileName, int widthPx, int heightPx) {
    }

    private final DocTemplateQrCodeGenerator qrCodeGenerator;

    public DocTemplateImageLoader() {
        this(new DocTemplateQrCodeGenerator());
    }

    public DocTemplateImageLoader(DocTemplateQrCodeGenerator qrCodeGenerator) {
        this.qrCodeGenerator = qrCodeGenerator == null ? new DocTemplateQrCodeGenerator() : qrCodeGenerator;
    }

    /**
     * @param value 占位符解析出来的值（URL / Data URI / Base64 / byte[]）
     * @param spec  该字段的图片声明（可能带目标尺寸）
     * @return 图片数据；值为空时返回 {@code null}
     */
    public ImagePayload load(Object value, DocTemplateImageSpec spec) {
        return load(value, spec, null, null);
    }

    /**
     * @param key    占位符的原始键（可能带块编码，如 {@code report.verify_url}），
     *               二维码中间小图取同块字段时用它推断命名空间
     * @param params 整篇渲染参数，二维码中间小图取字段值时用
     */
    public ImagePayload load(Object value, DocTemplateImageSpec spec, Map<String, Object> params, String key) {
        if (spec != null && spec.qr() != null) return loadQrCode(value, spec, params, key);
        byte[] bytes = bytesOf(value);
        if (bytes == null || bytes.length == 0) return null;

        BufferedImage image = readImage(bytes);
        byte[] embed;
        int pictureType;
        String extension;
        if (isPng(bytes)) {
            embed = bytes;
            pictureType = Workbook.PICTURE_TYPE_PNG;
            extension = ".png";
        } else if (isJpeg(bytes)) {
            embed = bytes;
            pictureType = Workbook.PICTURE_TYPE_JPEG;
            extension = ".jpg";
        } else if (image != null) {
            // gif / bmp / tif 等统一转 PNG，POI 对这类格式的兼容性最好
            embed = toPng(image);
            pictureType = Workbook.PICTURE_TYPE_PNG;
            extension = ".png";
        } else {
            throw new IllegalArgumentException("无法识别的图片格式（字段「" + fieldOf(spec) + "」），"
                    + "支持 png / jpg / gif / bmp / tif");
        }

        int naturalWidth = image == null ? 0 : image.getWidth();
        int naturalHeight = image == null ? 0 : image.getHeight();
        int[] size = targetSize(spec, naturalWidth, naturalHeight);
        return new ImagePayload(embed, pictureType, "image" + extension, size[0], size[1]);
    }

    // ------------------------------------------------------------------ 二维码

    /**
     * 把字段值按文本编码成二维码；中间的小图可以来自同块字段或固定地址。
     *
     * <p>值为空时返回 {@code null}，交给调用方按「没有值」处理（和普通图片字段一致）。</p>
     */
    private ImagePayload loadQrCode(Object value, DocTemplateImageSpec spec, Map<String, Object> params, String key) {
        String text = qrText(value);
        if (StrUtil.isBlank(text)) return null;
        byte[] logo = logoBytes(spec.qr(), params, key);
        byte[] png = qrCodeGenerator.encode(text, spec.qr(), logo);
        BufferedImage image = readImage(png);
        int naturalWidth = image == null ? 0 : image.getWidth();
        int naturalHeight = image == null ? 0 : image.getHeight();
        int[] size = targetSize(spec, naturalWidth, naturalHeight);
        return new ImagePayload(png, Workbook.PICTURE_TYPE_PNG, "qrcode.png", size[0], size[1]);
    }

    private String qrText(Object value) {
        if (value == null) return null;
        if (value instanceof Map<?, ?> || value instanceof Iterable<?>) {
            try {
                return DocTemplateJson.toJson(value);
            } catch (Exception ignored) {
                return String.valueOf(value);
            }
        }
        if (value instanceof byte[] bytes) return new String(bytes, StandardCharsets.UTF_8);
        if (value instanceof Double || value instanceof Float) {
            double number = ((Number) value).doubleValue();
            if (number == Math.rint(number) && !Double.isInfinite(number)) return String.valueOf((long) number);
        }
        return String.valueOf(value).trim();
    }

    /**
     * 中间小图的原始字节：先按 {@code logoField} 从渲染参数里取，取不到再用 {@code logoUrl}。
     *
     * <p>字段名可以不带块编码：占位符是 {@code ${report.verify_url}} 时会自动到 {@code report}
     * 下面找 {@code logo}；明细行里参数本身就是当前项，直接取即可。取不到或不是可识别的图片时
     * 只打日志、不中断生成——二维码本身仍然可用。</p>
     */
    private byte[] logoBytes(DocTemplateQrCodeSpec qr, Map<String, Object> params, String key) {
        if (qr == null) return null;
        Object raw = null;
        if (StrUtil.isNotBlank(qr.logoField())) {
            raw = logoValue(qr.logoField(), params, key);
        }
        if (raw == null && StrUtil.isNotBlank(qr.logoUrl())) {
            raw = qr.logoUrl();
        }
        if (raw == null) return null;
        try {
            return bytesOf(raw);
        } catch (Exception error) {
            log.warn("二维码中间小图不可用（字段「{}」），本次不叠加小图：{}", qr.field(), error.getMessage());
            return null;
        }
    }

    private Object logoValue(String logoField, Map<String, Object> params, String key) {
        Object value = lookupPath(params, logoField);
        if (value != null) return value;
        // 单条数据块：字段在块编码的命名空间下，用二维码字段自己的前缀去找
        if (StrUtil.isNotBlank(key)) {
            int dot = key.lastIndexOf('.');
            if (dot > 0) {
                value = lookupPath(params, key.substring(0, dot) + "." + logoField);
                if (value != null) return value;
            }
        }
        // 兜底：块结果可能被平铺或包在别的层级里，扫一层子 Map
        if (params != null) {
            for (Object child : params.values()) {
                if (child instanceof Map<?, ?> map) {
                    value = lookupPath(castMap(map), logoField);
                    if (value != null) return value;
                }
            }
        }
        return null;
    }

    private Object lookupPath(Map<String, Object> params, String path) {
        if (params == null || StrUtil.isBlank(path)) return null;
        Object current = params;
        for (String part : path.trim().split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) return null;
            String actual = matchKey(map, part);
            if (actual == null) return null;
            current = map.get(actual);
        }
        return current;
    }

    private String matchKey(Map<?, ?> map, String key) {
        if (map.containsKey(key)) return key;
        String lower = key.toLowerCase(Locale.ROOT);
        for (Object candidate : map.keySet()) {
            if (candidate != null && String.valueOf(candidate).toLowerCase(Locale.ROOT).equals(lower)) {
                return String.valueOf(candidate);
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    // ------------------------------------------------------------------ 取值

    private byte[] bytesOf(Object value) {
        if (value == null) return null;
        if (value instanceof byte[] raw) return raw;
        if (value instanceof Byte[] boxed) {
            byte[] raw = new byte[boxed.length];
            for (int i = 0; i < boxed.length; i++) raw[i] = boxed[i] == null ? 0 : boxed[i];
            return raw;
        }
        String text = StrUtil.trimToEmpty(String.valueOf(value));
        if (text.isEmpty()) return null;
        if (text.startsWith("data:")) return fromDataUri(text);
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://")) return download(text);
        if (lower.startsWith("base64:")) return decodeBase64(text.substring("base64:".length()));
        if (BASE64_CHARS.matcher(text).matches() && text.length() > 32) return decodeBase64(text);
        throw new IllegalArgumentException("图片字段的值既不是 http(s) 地址，也不是 Base64："
                + StrUtil.maxLength(text, 80));
    }

    private byte[] fromDataUri(String text) {
        var matcher = DATA_URI.matcher(text);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("data URI 格式不正确，应为 data:image/png;base64,....");
        }
        return decodeBase64(matcher.group(2));
    }

    private byte[] decodeBase64(String text) {
        String compact = text.replaceAll("\\s+", "");
        // URL-safe 变体也兼容一下
        compact = compact.replace('-', '+').replace('_', '/');
        int pad = compact.length() % 4;
        if (pad > 0) compact = compact + "=".repeat(4 - pad);
        try {
            return Base64.getDecoder().decode(compact);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Base64 图片解码失败：" + StrUtil.maxLength(error.getMessage(), 120), error);
        }
    }

    private byte[] download(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (Exception error) {
            throw new IllegalArgumentException("图片地址不合法：" + StrUtil.maxLength(url, 200), error);
        }
        String scheme = StrUtil.blankToDefault(uri.getScheme(), "").toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IllegalArgumentException("图片地址只支持 http / https：" + StrUtil.maxLength(url, 200));
        }

        HttpURLConnection connection = null;
        try {
            URL target = uri.toURL();
            connection = (HttpURLConnection) target.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(DOWNLOAD_TIMEOUT_MS);
            connection.setReadTimeout(DOWNLOAD_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("Accept", "image/*");
            connection.setRequestProperty("User-Agent", "doc-template/1.0");
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IllegalStateException("图片下载失败 HTTP " + status + "：" + StrUtil.maxLength(url, 200));
            }
            byte[] bytes;
            try (InputStream in = connection.getInputStream()) {
                bytes = IoUtil.readBytes(in, false);
            }
            if (bytes.length > MAX_BYTES) {
                throw new IllegalStateException("图片超过大小上限 " + (MAX_BYTES / 1024) + " KB：" + StrUtil.maxLength(url, 200));
            }
            if (bytes.length == 0) {
                throw new IllegalStateException("图片内容为空：" + StrUtil.maxLength(url, 200));
            }
            log.debug("已下载图片 {}，{} 字节", url, bytes.length);
            return bytes;
        } catch (IllegalStateException | IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalStateException("图片下载失败：" + StrUtil.maxLength(url, 200)
                    + "（" + error.getClass().getSimpleName() + "）", error);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    // ------------------------------------------------------------------ 格式与尺寸

    private boolean isPng(byte[] bytes) {
        return bytes.length > 4 && (bytes[0] & 0xFF) == 0x89 && (bytes[1] & 0xFF) == 0x50
                && (bytes[2] & 0xFF) == 0x4E && (bytes[3] & 0xFF) == 0x47;
    }

    private boolean isJpeg(byte[] bytes) {
        return bytes.length > 3 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8
                && (bytes[2] & 0xFF) == 0xFF;
    }

    private BufferedImage readImage(byte[] bytes) {
        try (ByteArrayInputStream in = new ByteArrayInputStream(bytes)) {
            return ImageIO.read(in);
        } catch (Exception error) {
            return null;
        }
    }

    private byte[] toPng(BufferedImage image) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (Exception error) {
            throw new IllegalStateException("图片转 PNG 失败：" + error.getMessage(), error);
        }
    }

    /**
     * 目标像素尺寸：同时给宽高时按比例缩放到框内（不拉伸变形）；只给一边时按比例算另一边；
     * 都没给时用原始尺寸，超过默认最大宽度再等比缩小。
     */
    private int[] targetSize(DocTemplateImageSpec spec, int naturalWidth, int naturalHeight) {
        Integer width = spec == null ? null : spec.width();
        Integer height = spec == null ? null : spec.height();
        int naturalW = Math.max(0, naturalWidth);
        int naturalH = Math.max(0, naturalHeight);
        int maxWidth = DEFAULT_MAX_WIDTH;

        int targetW;
        int targetH;
        if (width != null && height != null) {
            if (naturalW > 0 && naturalH > 0) {
                double scale = Math.min(width / (double) naturalW, height / (double) naturalH);
                targetW = Math.max(1, (int) Math.round(naturalW * scale));
                targetH = Math.max(1, (int) Math.round(naturalH * scale));
            } else {
                targetW = width;
                targetH = height;
            }
        } else if (width != null) {
            targetW = width;
            targetH = naturalW > 0 && naturalH > 0
                    ? Math.max(1, (int) Math.round(naturalH * (width / (double) naturalW))) : width;
        } else if (height != null) {
            targetH = height;
            targetW = naturalW > 0 && naturalH > 0
                    ? Math.max(1, (int) Math.round(naturalW * (height / (double) naturalH))) : height;
        } else if (naturalW > 0 && naturalH > 0) {
            targetW = naturalW;
            targetH = naturalH;
            if (targetW > maxWidth) {
                double scale = maxWidth / (double) targetW;
                targetW = maxWidth;
                targetH = Math.max(1, (int) Math.round(targetH * scale));
            }
        } else {
            targetW = maxWidth;
            targetH = maxWidth;
        }
        return new int[]{clamp(targetW), clamp(targetH)};
    }

    private int clamp(int value) {
        return Math.min(MAX_SIDE_PX, Math.max(1, value));
    }

    private String fieldOf(DocTemplateImageSpec spec) {
        return spec == null ? "?" : StrUtil.blankToDefault(spec.field(), "?");
    }
}
