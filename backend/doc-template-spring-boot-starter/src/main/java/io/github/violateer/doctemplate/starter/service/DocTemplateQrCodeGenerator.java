package io.github.violateer.doctemplate.starter.service;

import cn.hutool.core.util.StrUtil;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import io.github.violateer.doctemplate.starter.support.DocTemplateQrCodeSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.EnumMap;
import java.util.Map;

/**
 * 把一段文本编码成二维码 PNG，可选在中间叠加一张小图。
 *
 * <p>生成时按显示尺寸的 3 倍出图（上限 1600px），写进文档后再缩到目标尺寸，
 * 这样打印和 PDF 导出都清晰；二维码本身是矢量模块，放大不会糊。</p>
 *
 * <p>中间小图会遮挡一部分模块，所以带小图时默认纠错级别 {@code H}（可纠正 30%），
 * 不带小图默认 {@code M}；配置里写了 {@code level} 时以配置为准。</p>
 */
public class DocTemplateQrCodeGenerator {

    private static final Logger log = LoggerFactory.getLogger(DocTemplateQrCodeGenerator.class);

    /** 生成倍数：显示 140px 时出 420px 的图。 */
    private static final int SCALE = 3;
    private static final int MIN_GENERATED_PX = 300;
    private static final int MAX_GENERATED_PX = 1600;
    /** 二维码四周留白（模块数）。1 个模块足够扫码，留白太大会把图撑得很大。 */
    private static final int QUIET_ZONE_MODULES = 1;

    /**
     * @param text 二维码内容（非空；空值由调用方按「没有值」处理）
     * @param spec 二维码声明（尺寸、纠错级别、中间小图比例）
     * @param logo 中间小图的原始字节，可为 {@code null}
     * @return PNG 字节
     */
    public byte[] encode(String text, DocTemplateQrCodeSpec spec, byte[] logo) {
        if (StrUtil.isBlank(text)) {
            throw new IllegalArgumentException("二维码内容为空");
        }
        DocTemplateQrCodeSpec target = spec == null
                ? new DocTemplateQrCodeSpec(null, null, null, null, null, null, null, null, null) : spec;
        int display = Math.max(1, target.width() == null ? DocTemplateQrCodeSpec.DEFAULT_SIZE : target.width());
        int generated = Math.min(MAX_GENERATED_PX, Math.max(MIN_GENERATED_PX, display * SCALE));

        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        hints.put(EncodeHintType.ERROR_CORRECTION, errorCorrection(target, logo != null));
        hints.put(EncodeHintType.MARGIN, QUIET_ZONE_MODULES);

        BitMatrix matrix;
        try {
            matrix = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, generated, generated, hints);
        } catch (WriterException error) {
            throw new IllegalArgumentException("二维码生成失败（内容过长或包含无法编码的字符）："
                    + StrUtil.maxLength(text, 60), error);
        }

        BufferedImage image = new BufferedImage(matrix.getWidth(), matrix.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
            graphics.setColor(Color.BLACK);
            for (int y = 0; y < matrix.getHeight(); y++) {
                for (int x = 0; x < matrix.getWidth(); x++) {
                    if (matrix.get(x, y)) graphics.fillRect(x, y, 1, 1);
                }
            }
            if (logo != null && logo.length > 0) {
                drawLogo(graphics, image, logo, target.logoRatio());
            }
        } finally {
            graphics.dispose();
        }
        return toPng(image);
    }

    private ErrorCorrectionLevel errorCorrection(DocTemplateQrCodeSpec spec, boolean hasLogo) {
        String level = spec == null ? null : spec.level();
        if (level != null) {
            return switch (level) {
                case "L" -> ErrorCorrectionLevel.L;
                case "Q" -> ErrorCorrectionLevel.Q;
                case "H" -> ErrorCorrectionLevel.H;
                default -> ErrorCorrectionLevel.M;
            };
        }
        return hasLogo ? ErrorCorrectionLevel.H : ErrorCorrectionLevel.M;
    }

    /**
     * 中间小图：先铺一块白色圆角底（把被遮住的模块「擦」成留白，扫码器更容易识别），
     * 再把小图等比缩放居中画上去。小图读不出来时只打日志、不加图，二维码本身照常输出。
     */
    private void drawLogo(Graphics2D graphics, BufferedImage canvas, byte[] logo, Double ratio) {
        BufferedImage logoImage;
        try (ByteArrayInputStream in = new ByteArrayInputStream(logo)) {
            logoImage = ImageIO.read(in);
        } catch (Exception error) {
            log.warn("二维码中间小图读取失败，已忽略：{}", error.getMessage());
            return;
        }
        if (logoImage == null || logoImage.getWidth() <= 0 || logoImage.getHeight() <= 0) {
            log.warn("二维码中间小图不是可识别的图片格式，已忽略");
            return;
        }

        int side = canvas.getWidth();
        double scale = ratio == null ? DocTemplateQrCodeSpec.DEFAULT_LOGO_RATIO : ratio;
        int box = Math.max(8, (int) Math.round(side * scale));
        box = Math.min(box, side / 2);
        int margin = Math.max(1, (int) Math.round(box * 0.12));
        int background = box + margin * 2;
        int left = (side - background) / 2;
        int top = (side - background) / 2;

        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics.setComposite(AlphaComposite.SrcOver);
        graphics.setColor(Color.WHITE);
        graphics.fill(new RoundRectangle2D.Double(left, top, background, background,
                background * 0.18, background * 0.18));
        graphics.setColor(new Color(0xE6E6E6));
        graphics.setStroke(new BasicStroke(1f));
        graphics.draw(new RoundRectangle2D.Double(left + 0.5, top + 0.5, background - 1, background - 1,
                background * 0.18, background * 0.18));

        double fit = Math.min(box / (double) logoImage.getWidth(), box / (double) logoImage.getHeight());
        int width = Math.max(1, (int) Math.round(logoImage.getWidth() * fit));
        int height = Math.max(1, (int) Math.round(logoImage.getHeight() * fit));
        graphics.drawImage(logoImage, (side - width) / 2, (side - height) / 2, width, height, null);
    }

    private byte[] toPng(BufferedImage image) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (Exception error) {
            throw new IllegalStateException("二维码转 PNG 失败：" + error.getMessage(), error);
        }
    }
}
