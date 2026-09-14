package com.miuxuer.linkforge.utils;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;

/**
 * 二维码生成工具（ZXing）。
 *
 * <p><b>纠错级别（Error Correction Level）是这里唯一需要理解的参数</b>：
 * 二维码有 L/M/Q/H 四档纠错能力，分别能在约 7%/15%/25%/30% 的图案被遮挡或污损时
 * 仍然正确解码。代价是纠错级别越高，同样的内容需要越多的码点，二维码看起来越密。
 *
 * <p>本工具默认用 <b>H（30%）</b>：因为要在中心盖一个 logo。
 * 用 L 级别的话，中间那 20% 一盖，整个码就废了。
 * 这也是"带 logo 的二维码"必须用高纠错级别的根本原因 —— 不是设计偏好，
 * 是纠错容量算出来的硬约束。
 */
public final class QrCodeUtils {

    /**
     * 二维码四周留白的宽度，单位是"码点"而不是像素。
     *
     * <p>这个留白叫安静区（quiet zone），不是装饰：扫码软件靠它把二维码
     * 和周围的图案区分开。ZXing 默认就是 4，也是标准推荐值 ——
     * 改成 0 的话，二维码贴在花花绿绿的背景上会很难扫出来。
     */
    private static final int MARGIN_MODULES = 4;

    private QrCodeUtils() {
    }

    /**
     * 生成二维码 PNG 图片的字节，纠错级别用 H（可在中心盖 logo）。
     *
     * @param content 二维码承载的内容，通常是一个 URL
     * @param size    生成图片的边长（像素），二维码是正方形
     * @return PNG 格式的图片字节
     * @throws IllegalArgumentException 内容为空、边长不合法、或内容超出二维码容量
     * @throws IOException              PNG 编码失败
     */
    public static byte[] toPngBytes(String content, int size) throws IOException {
        return toPngBytes(toBitMatrix(content, size, ErrorCorrectionLevel.H));
    }

    /** 生成二维码 PNG 字节，可指定纠错级别。 */
    public static byte[] toPngBytes(String content, int size,
                                    ErrorCorrectionLevel errorCorrectionLevel) throws IOException {
        return toPngBytes(toBitMatrix(content, size, errorCorrectionLevel));
    }

    /**
     * 算出二维码的位矩阵（黑白点阵）。
     *
     * <p>单独暴露这一步，是因为"带 logo"的版本需要在矩阵上做中心挖空 ——
     * 它能直接复用同一份矩阵，不用把内容重新编码一遍。
     */
    public static BitMatrix toBitMatrix(String content, int size,
                                        ErrorCorrectionLevel errorCorrectionLevel) {
        if (content == null || content.isEmpty()) {
            throw new IllegalArgumentException("二维码内容不能为空");
        }
        if (size <= 0) {
            throw new IllegalArgumentException("二维码尺寸必须为正数: " + size);
        }

        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.ERROR_CORRECTION, errorCorrectionLevel);
        // 指定 UTF-8：不指定的话中文内容可能按 ISO-8859-1 编码，扫出来是乱码。
        // 多数扫码软件能猜对，但不该赌
        hints.put(EncodeHintType.CHARACTER_SET, StandardCharsets.UTF_8.name());
        hints.put(EncodeHintType.MARGIN, MARGIN_MODULES);

        try {
            return new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints);
        } catch (WriterException e) {
            // 内容超出二维码容量时会走到这里。这是调用方的输入问题，
            // 包成 IllegalArgumentException 让上层能区分"参数不对"和"系统故障"
            throw new IllegalArgumentException("二维码生成失败: " + e.getMessage(), e);
        }
    }

    /** 把位矩阵编码成 PNG 字节。 */
    public static byte[] toPngBytes(BitMatrix matrix) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        MatrixToImageWriter.writeToStream(matrix, "PNG", output);
        return output.toByteArray();
    }

    // ==================== 带 logo 合成 ====================

    /**
     * logo 最大边长占二维码边长的比例。
     *
     * <p>取 20% 是算出来的，不是拍脑袋：H 级纠错能容忍约 30% 的码点被遮挡，
     * 而 logo 是方块、遮挡的是<b>面积</b>，再加上周围还要留一圈白边，
     * 20% 是"看起来够大"和"扫得出来"之间的常见折中。
     * 再大就有扫不出来的风险，而且这类失败是"生成成功、就是扫不出"，
     * 用户只会以为是自己手机的问题。
     */
    private static final double LOGO_MAX_RATIO = 0.20;

    /**
     * 生成带 logo 的二维码 PNG 字节。
     *
     * <p>logo 会被缩放到中心、等比保持长宽比，并在周围铺一圈白底。
     *
     * <p><b>这一层只做图像处理，不负责去下载 logo。</b> 下载是带网络 I/O 和
     * 安全考量的事（用户提供的 URL 可以被用来探测内网），放在 Service 层做更合适 ——
     * 工具类保持成纯粹的、可以离线测试的函数。
     *
     * @param content   二维码内容
     * @param size      图片边长（像素）
     * @param logoBytes logo 图片的字节，为 null 或空时等同于不带 logo
     * @return PNG 字节
     * @throws IllegalArgumentException logo 不是能识别的图片格式
     */
    public static byte[] toPngBytesWithLogo(String content, int size, byte[] logoBytes) throws IOException {
        // 必须用 H 级：中心盖了 logo，低纠错级别会直接扫不出来
        BufferedImage image = MatrixToImageWriter.toBufferedImage(
                toBitMatrix(content, size, ErrorCorrectionLevel.H));

        overlayLogo(image, logoBytes);
        return toPngBytes(image);
    }

    /**
     * 把 logo 画到二维码正中间。
     *
     * <p>先铺白底再画 logo，这一步不能省：logo 的边缘通常和二维码的黑块紧挨着，
     * 视觉上糊成一片，扫码软件会把 logo 的边框当成码点去解析。
     * 留一圈白边相当于给 logo 划出明确边界。
     *
     * <p>用圆角而不是直角，是因为直角白框和二维码的方形码点在粗细上很像，
     * 圆角能让"这是一块覆盖物"这件事在视觉上更清楚，人眼看着也舒服。
     */
    private static void overlayLogo(BufferedImage qrImage, byte[] logoBytes) throws IOException {
        if (logoBytes == null || logoBytes.length == 0) {
            return;
        }

        BufferedImage logo = ImageIO.read(new ByteArrayInputStream(logoBytes));
        if (logo == null) {
            throw new IllegalArgumentException("logo 不是能识别的图片格式");
        }

        int size = qrImage.getWidth();
        int maxLogoSide = (int) (size * LOGO_MAX_RATIO);

        // 等比缩放：直接拉成正方形会把头像压扁。
        // 用 min 而不是分别算，是为了让长边贴到上限、短边按比例跟着缩
        double scale = Math.min(
                (double) maxLogoSide / logo.getWidth(),
                (double) maxLogoSide / logo.getHeight());
        // 小图放大没意义（会糊），所以取 min(1, scale)：只缩不放
        double finalScale = Math.min(1.0, scale);

        int logoWidth = Math.max(1, (int) (logo.getWidth() * finalScale));
        int logoHeight = Math.max(1, (int) (logo.getHeight() * finalScale));
        int x = (size - logoWidth) / 2;
        int y = (size - logoHeight) / 2;

        // 白边宽度按图片尺寸取，小图上留 2px，大图上按比例
        int padding = Math.max(2, size / 100);
        int arc = Math.max(4, size / 25);

        Graphics2D graphics = qrImage.createGraphics();
        // 缩小时用双线性插值，边缘比默认的最近邻平滑得多
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);

        graphics.setColor(Color.WHITE);
        graphics.fillRoundRect(x - padding, y - padding,
                logoWidth + padding * 2, logoHeight + padding * 2, arc, arc);
        graphics.drawImage(logo, x, y, logoWidth, logoHeight, null);
        graphics.dispose();
    }

    /** 把图片编码成 PNG 字节。 */
    private static byte[] toPngBytes(BufferedImage image) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "PNG", output);
        return output.toByteArray();
    }
}
