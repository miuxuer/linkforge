package com.miuxuer.linkforge.service.impl;

import com.miuxuer.oss.OssProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * OSS 图片下载器的 SSRF 防护测试。
 *
 * <p><b>这里测的全是"拒绝"路径，而且都不会真的发网络请求</b> ——
 * 校验发生在 {@code httpClient.send()} 之前，参数不合法就直接抛出来了。
 * 所以这个测试跑得飞快，也不需要联网。
 *
 * <p>被下载的 URL 存在 {@code t_link.qr_logo} 里，是用户能改的字段。
 * 服务端拿用户给的地址去发请求，这是教科书式的 SSRF 入口，必须挡住。
 */
@DisplayName("OSS 图片下载器 - 地址校验")
class OssImageLoaderTest {

    private static OssImageLoader loaderWithEndpoint(String endpoint) {
        OssProperties properties = new OssProperties();
        properties.setEndpoint(endpoint);
        properties.setBucketName("miuxuer");
        properties.setRegion("cn-beijing");
        return new OssImageLoader(properties);
    }

    private static OssImageLoader defaultLoader() {
        return loaderWithEndpoint("https://oss-cn-beijing.aliyuncs.com");
    }

    // ==================== 协议限制 ====================

    @Test
    @DisplayName("http 协议 → 拒绝（内网探测大多是无认证的）")
    void httpScheme_shouldBeRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> defaultLoader().load("http://oss-cn-beijing.aliyuncs.com/a.png"));
    }

    @Test
    @DisplayName("file 协议 → 拒绝，不能让它读服务器本地文件")
    void fileScheme_shouldBeRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> defaultLoader().load("file:///etc/passwd"));
    }

    @Test
    @DisplayName("ftp / jar 等其它协议 → 拒绝")
    void otherSchemes_shouldBeRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> defaultLoader().load("ftp://oss-cn-beijing.aliyuncs.com/a.png"));
        assertThrows(IllegalArgumentException.class,
                () -> defaultLoader().load("jar:https://oss-cn-beijing.aliyuncs.com/a.jar!/x"));
    }

    // ==================== 域名白名单 ====================

    @Test
    @DisplayName("云厂商元数据地址 → 拒绝（SSRF 的经典目标，里面可能有临时凭证）")
    void cloudMetadataAddress_shouldBeRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> defaultLoader().load("https://169.254.169.254/latest/meta-data/"));
    }

    @Test
    @DisplayName("localhost / 内网地址 → 拒绝，不能拿它当端口扫描器")
    void internalAddresses_shouldBeRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> defaultLoader().load("https://localhost:3306/"));
        assertThrows(IllegalArgumentException.class,
                () -> defaultLoader().load("https://127.0.0.1:8080/actuator/env"));
        assertThrows(IllegalArgumentException.class,
                () -> defaultLoader().load("https://192.168.1.1/admin"));
    }

    @Test
    @DisplayName("任意外部域名 → 拒绝")
    void arbitraryDomain_shouldBeRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> defaultLoader().load("https://evil.example.com/a.png"));
    }

    // ==================== 后缀欺骗 ====================

    @Test
    @DisplayName("★ 后缀欺骗：目标域名前面挂着受信任域名 → 拒绝")
    void suffixSpoofing_shouldBeRejected() {
        // 用 String.contains() 判断的话，这个地址会被放过 ——
        // 它确实"包含"了 oss-cn-beijing.aliyuncs.com，但真实域名是 evil.com
        assertThrows(IllegalArgumentException.class,
                () -> defaultLoader().load("https://oss-cn-beijing.aliyuncs.com.evil.com/a.png"));
    }

    @Test
    @DisplayName("★ 前缀欺骗：受信任域名作为前缀拼在别的域名上 → 拒绝")
    void prefixSpoofing_shouldBeRejected() {
        // 如果只做 endsWith("oss-cn-beijing.aliyuncs.com") 而不要求前面的点，
        // 这个地址会被放过 —— 但真实域名是 eviloss-cn-beijing.aliyuncs.com
        assertThrows(IllegalArgumentException.class,
                () -> defaultLoader().load("https://eviloss-cn-beijing.aliyuncs.com/a.png"));
    }

    // ==================== 边界 ====================

    @Test
    @DisplayName("地址为空 / null → 拒绝")
    void blankUrl_shouldBeRejected() {
        assertThrows(IllegalArgumentException.class, () -> defaultLoader().load(null));
        assertThrows(IllegalArgumentException.class, () -> defaultLoader().load(""));
        assertThrows(IllegalArgumentException.class, () -> defaultLoader().load("   "));
    }

    @Test
    @DisplayName("畸形地址 → 拒绝，而不是抛底层异常")
    void malformedUrl_shouldBeRejected() {
        assertThrows(IllegalArgumentException.class, () -> defaultLoader().load("这不是一个地址"));
        assertThrows(IllegalArgumentException.class, () -> defaultLoader().load("https://"));
    }

    @Test
    @DisplayName("★ 没配 endpoint → 一律拒绝，不能因为配置缺失就退化成谁都允许")
    void noEndpointConfigured_shouldRejectEverything() {
        OssImageLoader loader = loaderWithEndpoint(null);

        // 配置缺失时如果白名单为空串，任何 endsWith("." + "") 都成立 ——
        // 那就等于把门完全打开了。所以这里必须拒绝
        assertThrows(IllegalArgumentException.class,
                () -> loader.load("https://evil.example.com/a.png"));
    }

    // ==================== 放行的情况（不碰网络） ====================

    @Test
    @DisplayName("自家 bucket 的地址 → 校验通过")
    void ownBucketUrl_shouldPass() {
        // 直接调校验方法而不是 load()：load() 会真的发网络请求，
        // 单元测试不该依赖外部服务（慢，而且断网就红）
        assertThat(defaultLoader().validate("https://miuxuer.oss-cn-beijing.aliyuncs.com/2026/09/a.png"))
                .isNotNull();
    }

    @Test
    @DisplayName("OSS 主域名（不带 bucket 前缀）也放行")
    void bareOssHost_shouldPass() {
        assertThat(defaultLoader().validate("https://oss-cn-beijing.aliyuncs.com/a.png")).isNotNull();
    }

    @Test
    @DisplayName("用户自定义域名（CNAME 到 OSS）只要域名对得上也放行")
    void otherBucketOnSameHost_shouldPass() {
        // 同一地域下的别的 bucket 也允许 —— 它们同样是 OSS 的公开地址，
        // 不是内网，不构成 SSRF 风险
        assertThat(defaultLoader().validate("https://other-bucket.oss-cn-beijing.aliyuncs.com/a.png"))
                .isNotNull();
    }
}
