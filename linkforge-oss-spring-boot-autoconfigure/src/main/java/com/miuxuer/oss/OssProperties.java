package com.miuxuer.oss;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * OSS 配置项，绑定 yaml 里 {@code aliyun.oss.*} 下的值。
 *
 * <p><b>注意这个类没有 {@code @Component}。</b>
 * 它是由 {@link OssAutoConfiguration} 上的
 * {@code @EnableConfigurationProperties} 注册进来的。
 *
 * <p>打成 {@code @Component} 也能跑（因为启动类的组件扫描范围覆盖得到），
 * 但那等于绕开了自动配置机制 —— 哪天这个 starter 的包路径挪到了应用的扫描范围之外，
 * 就会变成"编译通过、启动时配置全是 null"。走 {@code @EnableConfigurationProperties}
 * 才是 starter 里的正确姿势，它不依赖使用者的扫描范围。
 *
 * <p><b>AK/SK 不在这里。</b> 那两个值走环境变量
 * （{@code OSS_ACCESS_KEY_ID} / {@code OSS_ACCESS_KEY_SECRET}），
 * 由 SDK 的 {@code EnvironmentVariableCredentialsProvider} 直接读取。
 * 放进 yaml 就等于把它们一起提交到 GitHub 了。
 */
@Data
@ConfigurationProperties(prefix = "aliyun.oss")
public class OssProperties {

    /** 服务接入点，如 {@code https://oss-cn-beijing.aliyuncs.com}。 */
    private String endpoint;

    /** Bucket 名字。 */
    private String bucketName;

    /** 地域，如 {@code cn-beijing}。V2 SDK 要求显式指定。 */
    private String region;
}
