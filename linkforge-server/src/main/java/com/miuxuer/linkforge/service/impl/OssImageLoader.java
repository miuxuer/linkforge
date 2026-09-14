package com.miuxuer.linkforge.service.impl;

import com.miuxuer.oss.OssProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 从 OSS 下载图片。
 *
 * <p><b>为什么要单独一个类、而不是随手一个 {@code new URL(url).openStream()}：</b>
 * 被下载的 URL 是<b>用户提供的</b>（存在 {@code t_link.qr_logo} 里），
 * 服务端拿用户给的地址去发请求，这是典型的 <b>SSRF（服务端请求伪造）</b> 入口。
 *
 * <p>攻击者可以填 {@code http://169.254.169.254/latest/meta-data/} 让服务器去读
 * 云厂商的实例元数据（里面可能有临时凭证）；或者填 {@code http://localhost:3306}
 * 拿它当端口扫描器；或者指向一个永远不返回的地址，把 Tomcat 的线程耗光。
 *
 * <p>这里用三层限制把口子收窄：
 *
 * <ol>
 *   <li><b>只允许 https</b> —— 挡掉 {@code file:}、{@code ftp:}、{@code jar:} 之类的协议。
 *       只允许 http 的话，内网探测大部分是无认证的，风险很大
 *   <li><b>域名必须在 OSS 白名单里</b> —— 这是最硬的一层。
 *       本项目的 logo 只能通过 {@code /api/upload} 传到自家 bucket，
 *       所以地址必然落在 OSS 域名下。不在这个域名的一律不发请求
 *   <li><b>超时 + 大小上限</b> —— 防止"慢连接"占着线程不放，
 *       以及把一个几百 MB 的文件读进内存
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OssImageLoader {

    /** 建连超时。内网地址通常是"连不上也不拒绝"，没有超时就会一直挂着。 */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);

    /** 读超时。 */
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    /** 允许下载的最大字节数。logo 只是个缩略图，2MB 已经很宽松了。 */
    private static final int MAX_IMAGE_BYTES = 2 * 1024 * 1024;

    private final OssProperties ossProperties;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            // 不让 HttpClient 自动跟随跳转：跟随的话，一个白名单域名下的地址
            // 可以 302 到内网地址，前面那层域名校验就白做了
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    /**
     * 下载图片字节。
     *
     * @param imageUrl 图片地址，必须落在配置的 OSS 域名下
     * @return 图片字节
     * @throws IllegalArgumentException URL 不合法、协议不支持、或域名不在白名单
     * @throws IOException              网络失败、状态码非 2xx、或超过大小限制
     */
    public byte[] load(String imageUrl) throws IOException, InterruptedException {
        URI uri = validate(imageUrl);

        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(READ_TIMEOUT)
                .GET()
                .build();

        HttpResponse<InputStream> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());

        if (response.statusCode() / 100 != 2) {
            throw new IOException("下载图片失败，HTTP " + response.statusCode());
        }

        try (InputStream body = response.body()) {
            return readAtMost(body, MAX_IMAGE_BYTES);
        }
    }

    /**
     * 校验 URL：协议必须是 https，域名必须落在 OSS 域名下。
     *
     * <p>用"后缀匹配"而不是"包含"：{@code oss-cn-beijing.aliyuncs.com.evil.com}
     * 这种域名包含目标字符串但完全受攻击者控制，用 contains 判断就是送人头。
     * 后缀匹配时还要求前面紧跟一个点（或是完全相等），避免
     * {@code eviloss-cn-beijing.aliyuncs.com} 也被放过。
     *
     * <p>可见性放宽到包级（而不是 private）是为了让测试能直接验证校验规则。
     * 走 {@link #load} 测的话，合法地址会真的发出网络请求 ——
     * 单元测试不该依赖外部服务，那既慢又不稳定。
     */
    URI validate(String imageUrl) {
        if (!StringUtils.hasText(imageUrl)) {
            throw new IllegalArgumentException("图片地址为空");
        }

        URI uri;
        try {
            uri = new URI(imageUrl);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("图片地址格式不正确");
        }

        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("只允许通过 https 下载图片，实际是: " + uri.getScheme());
        }

        String host = uri.getHost();
        String allowedHost = allowedHost();
        if (host == null || !host.equals(allowedHost) && !host.endsWith("." + allowedHost)) {
            throw new IllegalArgumentException(
                    "图片地址不在允许的域名下: " + host + "（只允许 " + allowedHost + " 及其子域名）");
        }

        return uri;
    }

    /** 从 OSS 配置里取出允许的域名，比如 {@code oss-cn-beijing.aliyuncs.com}。 */
    private String allowedHost() {
        String endpoint = ossProperties.getEndpoint() == null ? "" : ossProperties.getEndpoint();
        int schemeEnd = endpoint.indexOf("://");
        String host = schemeEnd >= 0 ? endpoint.substring(schemeEnd + 3) : endpoint;

        if (host.isEmpty()) {
            // 没配 endpoint 时返回一个永远匹配不上的值 —— 宁可拒绝，
            // 也不要因为配置缺失就退化成"谁都允许"
            return "-";
        }
        return host;
    }

    /**
     * 最多读 {@code limit} 个字节，超了就报错。
     *
     * <p>不能先用 {@code Content-Length} 判断就完事：那个头是响应方给的，
     * 可以撒谎（声明 1KB 实际发 1GB）。真正可靠的做法是边读边数。
     */
    private byte[] readAtMost(InputStream input, int limit) throws IOException {
        byte[] data = input.readNBytes(limit + 1);
        if (data.length > limit) {
            throw new IOException("图片超过大小上限 " + limit + " 字节");
        }
        return data;
    }
}
