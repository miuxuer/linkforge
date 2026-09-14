package com.miuxuer.oss;

import com.aliyun.sdk.service.oss2.OSSClient;
import com.aliyun.sdk.service.oss2.models.PutObjectRequest;
import com.aliyun.sdk.service.oss2.transport.BinaryData;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

/**
 * OSS 上传工具。
 *
 * <p><b>相对参考实现的第一个改动：client 是注入进来的单例，不是每次调用新建。</b>
 * 原来的写法每次上传都 {@code OSSClient.newBuilder().build()}，
 * 建客户端要初始化 HTTP 连接池、解析凭证、加载配置，这些开销在一次上传里完全被浪费掉。
 * 高并发上传时每次都新建还会把连接数打满、把 GC 压上去。
 * OSSClient 本身是线程安全的，做成单例 Bean 由容器管生命周期即可。
 */
public class AliOssOperator {

    /** 按"年/月"分目录。单目录下对象太多时，控制台和工具都会变慢。 */
    private static final DateTimeFormatter DIRECTORY_FORMAT = DateTimeFormatter.ofPattern("yyyy/MM");

    /** 只接受 1~10 位、纯字母数字的扩展名。 */
    private static final String SAFE_EXTENSION_PATTERN = "\\.[a-z0-9]{1,10}";

    private final OSSClient ossClient;
    private final OssProperties properties;

    public AliOssOperator(OSSClient ossClient, OssProperties properties) {
        this.ossClient = ossClient;
        this.properties = properties;
    }

    /**
     * 上传字节内容，返回可公开访问的 URL。
     *
     * @param content          文件内容
     * @param originalFilename 客户端传来的原始文件名（<b>不可信</b>）
     * @return 访问 URL，形如 {@code https://<bucket>.<endpoint>/2026/09/<uuid>.png}
     */
    public String upload(byte[] content, String originalFilename) {
        String objectName = buildObjectName(originalFilename);

        PutObjectRequest request = PutObjectRequest.newBuilder()
                .bucket(properties.getBucketName())
                .key(objectName)
                .body(BinaryData.fromBytes(content))
                .build();

        ossClient.putObject(request);
        return buildAccessUrl(objectName);
    }

    /**
     * 生成对象名：{@code yyyy/MM/uuid.ext}。
     *
     * <p>不用原始文件名有两点考虑：
     *
     * <ul>
     *   <li><b>安全</b>：文件名是客户端给的，可以包含 {@code ../} 之类的内容。
     *       直接拼进 key 有可能写到别人的目录下（取决于服务端如何规范化）。
     *       用 UUID 就把不可信输入彻底排除在外了
     *   <li><b>去重</b>：不同用户传同名文件（比如都是 {@code avatar.png}）
     *       会互相覆盖，后传的把先传的顶掉
     * </ul>
     *
     * <p>只从原文件名里取扩展名，而且取的过程本身也要防着客户端。
     */
    private String buildObjectName(String originalFilename) {
        return LocalDate.now().format(DIRECTORY_FORMAT) + "/"
                + UUID.randomUUID() + extractSafeExtension(originalFilename);
    }

    /**
     * 从原始文件名里安全地取出扩展名，取不到就返回空串（对象名没有后缀）。
     *
     * <p>参考实现是 {@code originalFilename.substring(originalFilename.lastIndexOf("."))}，
     * 有两个问题：
     *
     * <ul>
     *   <li>没有 "." 时 {@code lastIndexOf} 返回 -1，{@code substring(0)} 得到整个文件名，
     *       于是 key 变成 {@code uuid原始文件名} —— 不算漏洞，但很难看且不可预期
     *   <li>没有剥离路径分隔符，{@code "a/b.png"} 会把斜杠带进对象名，
     *       相当于自己给自己拼出了目录层级
     * </ul>
     */
    private String extractSafeExtension(String originalFilename) {
        if (originalFilename == null || originalFilename.isEmpty()) {
            return "";
        }

        // 统一分隔符后只取最后一段，把客户端可能塞进来的路径砍掉
        String name = originalFilename.replace('\\', '/');
        int lastSlash = name.lastIndexOf('/');
        if (lastSlash >= 0) {
            name = name.substring(lastSlash + 1);
        }

        int lastDot = name.lastIndexOf('.');
        if (lastDot < 0 || lastDot == name.length() - 1) {
            return "";
        }

        // 转小写避免出现 .PNG 和 .png 两个不同的后缀；
        // 再白名单校验一遍，含空格、引号、分号之类的扩展名一律丢掉
        String extension = name.substring(lastDot).toLowerCase(Locale.ROOT);
        return extension.matches(SAFE_EXTENSION_PATTERN) ? extension : "";
    }

    /**
     * 拼出公开访问地址。
     *
     * <p>OSS 的访问地址规则是 {@code <scheme>://<bucket>.<endpoint 的域名>/<key>}，
     * 比如 endpoint 是 {@code https://oss-cn-beijing.aliyuncs.com}、
     * bucket 是 {@code miuxuer}，拼出来就是
     * {@code https://miuxuer.oss-cn-beijing.aliyuncs.com/...}。
     *
     * <p>参考实现用 {@code endpoint.split("//")} 取协议头，能跑但依赖"endpoint 里
     * 一定恰好有一个 //"。这里改成按 {@code ://} 定位，语义更明确，
     * 而且 endpoint 忘了写协议头时也能兜住（默认补 https）。
     */
    private String buildAccessUrl(String objectName) {
        String endpoint = properties.getEndpoint() == null ? "" : properties.getEndpoint();

        int schemeEnd = endpoint.indexOf("://");
        String scheme = schemeEnd >= 0 ? endpoint.substring(0, schemeEnd + 3) : "https://";
        String host = schemeEnd >= 0 ? endpoint.substring(schemeEnd + 3) : endpoint;

        return scheme + properties.getBucketName() + "." + host + "/" + objectName;
    }
}
