package com.miuxuer.linkforge.utils;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.Result;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 二维码生成单元测试。
 *
 * <p>核心思路是<b>生成之后再用 ZXing 解回来</b> —— 只断言"字节数不为 0"
 * 是没意义的，那证明不了扫得出来。解码成功且内容和原文一致，才说明整个链路是通的。
 *
 * <p>注意这里是拿图片对象在内存里解码，不涉及摄像头，所以测试可以稳定跑。
 */
@DisplayName("二维码生成")
class QrCodeUtilsTest {

    private static final int SIZE = 300;

    /** 用 ZXing 把生成的图片解回文本，解不出来会抛 NotFoundException。 */
    private static String decode(byte[] pngBytes) throws IOException, NotFoundException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(pngBytes));
        BinaryBitmap bitmap = new BinaryBitmap(
                new HybridBinarizer(new BufferedImageLuminanceSource(image)));
        Result result = new MultiFormatReader().decode(bitmap);
        return result.getText();
    }

    /**
     * 在图片正中间盖一块白，模拟"放 logo 的位置"。
     *
     * @param ratio 遮盖区域占边长的比例
     */
    private static byte[] obscureCenter(byte[] pngBytes, double ratio) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(pngBytes));
        int box = (int) (image.getWidth() * ratio);
        int offset = (image.getWidth() - box) / 2;

        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(offset, offset, box, box);
        graphics.dispose();

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "PNG", output);
        return output.toByteArray();
    }

    private static boolean canDecode(byte[] pngBytes) {
        try {
            decode(pngBytes);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ==================== 基本生成 ====================

    @Test
    @DisplayName("生成的是合法 PNG，尺寸正确")
    void shouldProduceValidPng() throws IOException {
        byte[] png = QrCodeUtils.toPngBytes("https://www.baidu.com", SIZE);

        // PNG 文件头：89 50 4E 47
        assertThat(png[0] & 0xFF).isEqualTo(0x89);
        assertThat(new String(png, 1, 3)).isEqualTo("PNG");

        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        assertThat(image).isNotNull();
        assertThat(image.getWidth()).isEqualTo(SIZE);
        assertThat(image.getHeight()).isEqualTo(SIZE);
    }

    @Test
    @DisplayName("★ 生成的二维码能被解回原内容")
    void shouldBeDecodable() throws Exception {
        String url = "https://linkforge.example.com/abc123";

        byte[] png = QrCodeUtils.toPngBytes(url, SIZE);

        assertThat(decode(png)).isEqualTo(url);
    }

    @Test
    @DisplayName("★ 中文内容也能正确解码（UTF-8 编码没写错）")
    void shouldHandleChineseContent() throws Exception {
        // 不指定 CHARACTER_SET 的话中文可能按 ISO-8859-1 编码，扫出来是乱码
        String content = "短链测试：https://例子.测试/中文";

        byte[] png = QrCodeUtils.toPngBytes(content, SIZE);

        assertThat(decode(png)).isEqualTo(content);
    }

    @Test
    @DisplayName("长 URL 也能放下")
    void shouldHandleLongUrl() throws Exception {
        String longUrl = "https://example.com/very/long/path?a=1&b=2&c=3&d=" + "x".repeat(300);

        byte[] png = QrCodeUtils.toPngBytes(longUrl, 500);

        assertThat(decode(png)).isEqualTo(longUrl);
    }

    // ==================== 纠错级别（带 logo 的关键） ====================

    @Test
    @DisplayName("★ 默认用 H 级：中心盖住 20% 仍然扫得出来")
    void highErrorCorrection_shouldSurviveLogoOverlay() throws Exception {
        String url = "https://linkforge.example.com/abc123";

        byte[] png = QrCodeUtils.toPngBytes(url, SIZE);
        byte[] withLogoArea = obscureCenter(png, 0.20);

        // H 级能容忍约 30% 的遮挡，所以中间盖一块还能解出来 ——
        // 这正是"带 logo 的二维码"可行的原因
        assertThat(canDecode(withLogoArea))
                .as("H 级纠错下中心遮挡 20% 应该仍可解码")
                .isTrue();
    }

    @Test
    @DisplayName("★ 换成 L 级：同样盖住 20% 就扫不出来了")
    void lowErrorCorrection_shouldNotSurviveLogoOverlay() throws IOException {
        String url = "https://linkforge.example.com/abc123";

        // L 级只能容忍约 7% 的遮挡
        byte[] png = QrCodeUtils.toPngBytes(url, SIZE, ErrorCorrectionLevel.L);
        byte[] withLogoArea = obscureCenter(png, 0.20);

        // 这一条是"为什么带 logo 必须用高纠错级别"的直接证据：
        // 不换 H 级，中间一放 logo 整个码就废了，而且是"生成成功、扫不出来"这种
        // 最难排查的失败 —— 代码不报错，用户扫了半天没反应
        assertThat(canDecode(withLogoArea))
                .as("L 级纠错下中心遮挡 20% 应该解不出来")
                .isFalse();
    }

    @Test
    @DisplayName("没有遮挡时，L 级也能正常解码")
    void lowErrorCorrection_shouldStillWorkWithoutOverlay() throws Exception {
        String url = "https://linkforge.example.com/abc123";

        byte[] png = QrCodeUtils.toPngBytes(url, SIZE, ErrorCorrectionLevel.L);

        // 说明上一条的失败确实是遮挡造成的，不是 L 级本身有问题
        assertThat(decode(png)).isEqualTo(url);
    }

    // ==================== 边界 ====================

    @Test
    @DisplayName("内容为空 → IllegalArgumentException")
    void emptyContent_shouldThrow() {
        assertThrows(IllegalArgumentException.class, () -> QrCodeUtils.toPngBytes("", SIZE));
    }

    @Test
    @DisplayName("内容为 null → IllegalArgumentException")
    void nullContent_shouldThrow() {
        assertThrows(IllegalArgumentException.class, () -> QrCodeUtils.toPngBytes(null, SIZE));
    }

    @Test
    @DisplayName("尺寸不合法 → IllegalArgumentException")
    void invalidSize_shouldThrow() {
        assertThrows(IllegalArgumentException.class, () -> QrCodeUtils.toPngBytes("abc", 0));
        assertThrows(IllegalArgumentException.class, () -> QrCodeUtils.toPngBytes("abc", -10));
    }

    @Test
    @DisplayName("内容超长放不下 → IllegalArgumentException，而不是抛底层 WriterException")
    void tooLongContent_shouldThrowIllegalArgument() {
        // 二维码容量有限（H 级下最多约 1200 个字符）。
        // 把 ZXing 的 WriterException 直接抛给上层的话，调用方没法区分
        // "我传的内容不合法"和"系统出故障了"，前者该返回 400，后者才是 500
        String tooLong = "x".repeat(5000);

        assertThrows(IllegalArgumentException.class, () -> QrCodeUtils.toPngBytes(tooLong, SIZE));
    }
}
