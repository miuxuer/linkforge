package com.miuxuer.oss;

import com.aliyun.sdk.service.oss2.OSSClient;
import com.aliyun.sdk.service.oss2.models.PutObjectRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * OSS 上传工具单元测试。
 *
 * <p>OSSClient 用 Mockito 打桩，不会真的发请求 —— 测的是"对象名怎么生成、
 * URL 怎么拼"，这些是纯逻辑，也是最容易出问题的地方（文件名是客户端可控的）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OSS 上传工具")
class AliOssOperatorTest {

    private static final String ENDPOINT = "https://oss-cn-beijing.aliyuncs.com";
    private static final String BUCKET = "miuxuer";

    @Mock
    private OSSClient ossClient;

    private AliOssOperator operator;

    @BeforeEach
    void setUp() {
        OssProperties properties = new OssProperties();
        properties.setEndpoint(ENDPOINT);
        properties.setBucketName(BUCKET);
        properties.setRegion("cn-beijing");
        operator = new AliOssOperator(ossClient, properties);
    }

    /** 跑一次上传，把发给 OSS 的请求抓出来。 */
    private PutObjectRequest captureRequest(String originalFilename) {
        operator.upload(new byte[]{1, 2, 3}, originalFilename);

        ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(ossClient).putObject(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("上传 → 对象名是 年/月/uuid.扩展名 的格式")
    void upload_shouldBuildDatedObjectName() {
        PutObjectRequest request = captureRequest("cat.png");

        assertThat(request.bucket()).isEqualTo(BUCKET);
        // 形如 2026/09/<uuid>.png
        assertThat(request.key()).matches("\\d{4}/\\d{2}/[0-9a-f-]{36}\\.png");
    }

    @Test
    @DisplayName("上传 → 返回的 URL 是 <bucket>.<endpoint 域名>/<key>")
    void upload_shouldReturnAccessUrl() {
        String url = operator.upload(new byte[]{1}, "cat.png");

        // OSS 的访问地址规则：bucket 名做子域名，不是拼在路径里
        assertThat(url).startsWith("https://" + BUCKET + ".oss-cn-beijing.aliyuncs.com/");
        assertThat(url).endsWith(".png");
    }

    @Test
    @DisplayName("★ 文件名带路径（../../etc/passwd）→ 路径部分被剥掉")
    void upload_shouldStripPathFromFilename() {
        PutObjectRequest request = captureRequest("../../../etc/passwd.png");

        // 文件名是客户端可控的。不剥路径的话，等于让上传方自己决定写到哪个目录下。
        // 这里对象名固定是 年/月/uuid.扩展名，不会出现第二组斜杠之外的结构
        assertThat(request.key()).matches("\\d{4}/\\d{2}/[0-9a-f-]{36}\\.png");
        assertThat(request.key()).doesNotContain("..");
        assertThat(request.key()).doesNotContain("etc");
    }

    @Test
    @DisplayName("★ 文件名带反斜杠（Windows 路径）→ 同样被剥掉")
    void upload_shouldStripBackslashPath() {
        PutObjectRequest request = captureRequest("C:\\Users\\a\\secret.png");

        assertThat(request.key()).matches("\\d{4}/\\d{2}/[0-9a-f-]{36}\\.png");
        assertThat(request.key()).doesNotContain("secret");
    }

    @Test
    @DisplayName("文件名没有扩展名 → 对象名没有后缀，而不是把整个文件名拼上去")
    void upload_withoutExtension_shouldNotAppendWholeName() {
        PutObjectRequest request = captureRequest("README");

        // 参考实现用 lastIndexOf(".") 取后缀，没有点时返回 -1，
        // substring(0) 得到整个文件名，对象名会变成 <uuid>README
        assertThat(request.key()).matches("\\d{4}/\\d{2}/[0-9a-f-]{36}");
        assertThat(request.key()).doesNotContain("README");
    }

    @Test
    @DisplayName("扩展名带奇怪字符 → 直接丢掉，不写进对象名")
    void upload_withWeirdExtension_shouldDropIt() {
        // 扩展名同样是客户端给的，可能带引号、分号这类字符
        PutObjectRequest request = captureRequest("evil.\"onmouseover=alert(1)");

        assertThat(request.key()).matches("\\d{4}/\\d{2}/[0-9a-f-]{36}");
    }

    @Test
    @DisplayName("扩展名统一转小写，避免同一个后缀出现两种写法")
    void upload_shouldLowercaseExtension() {
        PutObjectRequest request = captureRequest("PHOTO.PNG");

        assertThat(request.key()).endsWith(".png");
    }

    @Test
    @DisplayName("文件名为 null → 不抛异常，对象名没有后缀")
    void upload_withNullFilename_shouldNotThrow() {
        PutObjectRequest request = captureRequest(null);

        assertThat(request.key()).matches("\\d{4}/\\d{2}/[0-9a-f-]{36}");
    }

    @Test
    @DisplayName("两次上传同名文件 → 对象名不同，不会互相覆盖")
    void upload_sameNameTwice_shouldNotOverwrite() {
        operator.upload(new byte[]{1}, "avatar.png");
        operator.upload(new byte[]{1}, "avatar.png");

        // 两次调用一次抓出来，不能分两次 verify —— 默认的 verify 只认一次调用
        ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(ossClient, times(2)).putObject(captor.capture());

        String firstKey = captor.getAllValues().get(0).key();
        String secondKey = captor.getAllValues().get(1).key();

        // 直接用原始文件名的话，两个用户都传 avatar.png 就会互相覆盖
        assertThat(firstKey).isNotEqualTo(secondKey);
    }

    @Test
    @DisplayName("endpoint 忘了写协议头 → 自动补 https")
    void upload_endpointWithoutScheme_shouldStillWork() {
        OssProperties properties = new OssProperties();
        properties.setEndpoint("oss-cn-beijing.aliyuncs.com");
        properties.setBucketName(BUCKET);
        AliOssOperator tolerant = new AliOssOperator(ossClient, properties);

        String url = tolerant.upload(new byte[]{1}, "a.png");

        assertThat(url).startsWith("https://" + BUCKET + ".oss-cn-beijing.aliyuncs.com/");
    }
}
