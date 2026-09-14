package com.miuxuer.linkforge.service.impl;

import com.miuxuer.linkforge.exception.BusinessException;
import com.miuxuer.linkforge.result.ResultCode;
import com.miuxuer.oss.AliOssOperator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 图片上传校验单元测试。
 *
 * <p>AliOssOperator 用 Mockito 打桩，不会真的传到 OSS —— 测的是上传前的三层校验，
 * 那才是这里自己写的逻辑。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("图片上传校验")
class UploadServiceImplTest {

    /** 合法的 PNG 文件头：89 50 4E 47。后面补够 12 字节，够魔数判断用。 */
    private static final byte[] PNG_HEADER = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};
    private static final byte[] JPEG_HEADER = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0x10, 'J', 'F', 'I', 'F', 0, 1};
    private static final byte[] GIF_HEADER = {'G', 'I', 'F', '8', '9', 'a', 0, 0, 0, 0, 0, 0};
    private static final byte[] WEBP_HEADER = {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'};

    @Mock
    private AliOssOperator aliOssOperator;

    private UploadServiceImpl uploadService;

    @BeforeEach
    void setUp() {
        uploadService = new UploadServiceImpl(aliOssOperator);
    }

    private static MockMultipartFile file(String name, String contentType, byte[] content) {
        return new MockMultipartFile("file", name, contentType, content);
    }

    // ==================== 正常路径 ====================

    @Test
    @DisplayName("上传合法图片 → 调 OSS 返回 URL")
    void uploadValidImage_shouldReturnUrl() {
        when(aliOssOperator.upload(any(), eq("cat.png")))
                .thenReturn("https://miuxuer.oss-cn-beijing.aliyuncs.com/2026/09/x.png");

        String url = uploadService.uploadImage(file("cat.png", "image/png", PNG_HEADER));

        assertThat(url).isEqualTo("https://miuxuer.oss-cn-beijing.aliyuncs.com/2026/09/x.png");
    }

    @Test
    @DisplayName("四种图片格式的文件头都能通过")
    void uploadVariousImageFormats_shouldPass() {
        when(aliOssOperator.upload(any(), anyString())).thenReturn("https://example.com/x");

        assertThat(uploadService.uploadImage(file("a.jpg", "image/jpeg", JPEG_HEADER))).isNotNull();
        assertThat(uploadService.uploadImage(file("b.png", "image/png", PNG_HEADER))).isNotNull();
        assertThat(uploadService.uploadImage(file("c.gif", "image/gif", GIF_HEADER))).isNotNull();
        assertThat(uploadService.uploadImage(file("d.webp", "image/webp", WEBP_HEADER))).isNotNull();
    }

    // ==================== 三层校验 ====================

    @Test
    @DisplayName("空文件 → 400，不调 OSS")
    void emptyFile_shouldReject() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> uploadService.uploadImage(file("a.png", "image/png", new byte[0])));

        assertThat(e.getHttpStatus()).isEqualTo(400);
        verify(aliOssOperator, never()).upload(any(), anyString());
    }

    @Test
    @DisplayName("null 文件 → 400")
    void nullFile_shouldReject() {
        assertThrows(BusinessException.class, () -> uploadService.uploadImage(null));
    }

    @Test
    @DisplayName("Content-Type 不是图片 → 400，不调 OSS")
    void nonImageContentType_shouldReject() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> uploadService.uploadImage(
                        file("evil.txt", "text/plain", "hello".getBytes(StandardCharsets.UTF_8))));

        assertThat(e.getMessage()).contains("jpg");
        verify(aliOssOperator, never()).upload(any(), anyString());
    }

    @Test
    @DisplayName("★ 把 HTML 改名成 .png 并声明 image/png → 文件头校验拦住")
    void disguisedHtml_shouldBeRejectedByMagicBytes() {
        // 这就是 Content-Type 挡不住的情况：类型和扩展名都是客户端说了算的，
        // 改个名、改个 header 就能骗过去。真正读几个字节才看得穿。
        byte[] html = "<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8);

        BusinessException e = assertThrows(BusinessException.class,
                () -> uploadService.uploadImage(file("evil.png", "image/png", html)));

        assertThat(e.getMessage()).contains("不是有效的图片");
        verify(aliOssOperator, never()).upload(any(), anyString());
    }

    @Test
    @DisplayName("文件太短（不足以判断文件头）→ 拒绝，而不是当成合法图片放行")
    void tooShortContent_shouldReject() {
        assertThrows(BusinessException.class,
                () -> uploadService.uploadImage(file("a.png", "image/png", new byte[]{1, 2})));
    }

    @Test
    @DisplayName("前几个字节像 PNG 但后面不是 → 只要文件头对就放行（魔数的固有局限）")
    void magicBytesOnlyCheckHeader() {
        // 记录一下这个已知边界：魔数只看开头几个字节，挡不住有意伪造的人。
        // 要真防住得在服务端把图片解码再重新编码，本项目不做 —— 但要知道这个口子在哪
        when(aliOssOperator.upload(any(), anyString())).thenReturn("https://example.com/x");
        byte[] pngHeaderThenGarbage = PNG_HEADER.clone();
        pngHeaderThenGarbage[11] = (byte) 0xFF;

        assertThat(uploadService.uploadImage(
                file("a.png", "image/png", pngHeaderThenGarbage))).isNotNull();
    }

    // ==================== 失败处理 ====================

    @Test
    @DisplayName("OSS 抛异常 → 包装成系统错误，不把底层信息透给前端")
    void ossFailure_shouldNotLeakInternalDetail() {
        when(aliOssOperator.upload(any(), anyString())).thenThrow(
                new RuntimeException("AccessDenied: bucket miuxuer, endpoint oss-cn-beijing"));

        BusinessException e = assertThrows(BusinessException.class,
                () -> uploadService.uploadImage(file("a.png", "image/png", PNG_HEADER)));

        assertThat(e.getHttpStatus()).isEqualTo(500);
        // bucket 名、endpoint、错误码都不该出现在给用户看的提示里
        assertThat(e.getMessage()).doesNotContain("miuxuer");
        assertThat(e.getMessage()).doesNotContain("AccessDenied");
        assertThat(e.getMessage()).doesNotContain("oss-cn-beijing");
    }
}
