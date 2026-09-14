package com.miuxuer.linkforge.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * JWT 配置项，绑定 application.yml 里 {@code linkforge.jwt.*} 下的值。
 *
 * <p><b>为什么用 {@code @ConfigurationProperties} 而不是在用的地方打 {@code @Value}</b>：
 *
 * <ul>
 *   <li>一组相关的配置收在一个类里，看 yaml 和看代码是对得上的
 *   <li>绑定时做类型转换和校验，写错了启动就报错；{@code @Value} 拼错 key 是静默拿到 null
 *   <li>IDE 能补全 yaml（配了 spring-boot-configuration-processor 的话）
 * </ul>
 *
 * <p><b>密钥绝对不能写死在这个类里</b>。硬编码的密钥一旦随代码进了仓库，
 * 任何人都能拿着它签出一个合法的 token 冒充任意用户 —— 这不是"泄露一个配置"，
 * 而是整套认证直接失效。所以 yaml 里写的是 {@code ${JWT_SECRET}}，值从环境变量取。
 */
@Data
@Component
@ConfigurationProperties(prefix = "linkforge.jwt")
public class JwtProperties {

    /**
     * 签名密钥。
     *
     * <p>长度必须 ≥ 32 字节（256 位）—— HS256 要求密钥不短于摘要长度，
     * 短了 jjwt 会直接抛 {@code WeakKeyException}。这是刻意的保护：
     * 短密钥可以被暴力枚举出来，那样就能伪造 token。
     */
    private String secretKey;

    /** token 有效期，单位毫秒。 */
    private long ttl;

    /** 前端放 token 的请求头名字。 */
    private String tokenName;
}
