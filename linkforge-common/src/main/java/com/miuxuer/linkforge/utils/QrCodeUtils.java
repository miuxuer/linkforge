package com.miuxuer.linkforge.utils;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

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
}
