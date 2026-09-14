package com.miuxuer.linkforge.properties;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

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
     * HS256 要求的最小密钥长度（字节）。
     *
     * <p>RFC 7518 §3.2 规定：HMAC-SHA 算法的密钥长度不得小于摘要长度，
     * HS256 的摘要就是 256 位 = 32 字节。密钥短于这个长度时可以被暴力枚举，
     * 枚举出来就能伪造任意用户的 token —— 所以 jjwt 会直接拒绝。
     */
    private static final int MIN_SECRET_BYTES = 32;

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

    /**
     * 启动时就把密钥长度校验掉，别等到用户点登录才报错。
     *
     * <p><b>为什么非要在这里校验</b>：不校验的话，密钥太短这件事要到
     * <b>第一个用户尝试登录</b>的时候才暴露 —— jjwt 在签发 token 时才检查密钥，
     * 抛出的 {@code WeakKeyException} 会被全局异常处理器当成"未预期的异常"，
     * 前端只看到一个"系统繁忙，请稍后重试"。
     *
     * <p>而这时候应用已经启动成功、接口也能访问、数据库也连上了，
     * 一切看起来都正常 —— 排查的人不会想到是"启动配置"的问题，
     * 只会去翻登录相关的代码。实际的原因藏在一整屏堆栈的最后一行：
     * {@code The specified key byte array is 104 bits which is not secure enough}。
     *
     * <p>提前到启动时校验，失败信息就直接摆在面前，而且应用压根起不来 ——
     * 不存在"看起来正常但用不了"的中间状态。
     */
    @PostConstruct
    public void validateSecretKey() {
        if (secretKey == null || secretKey.isBlank()) {
            throw new IllegalStateException("""
                    JWT_SECRET 没有配置。

                    IDEA：Run → Edit Configurations → Environment variables 里加
                    JWT_SECRET=<至少 32 字节的随机字符串>
                    命令行：JWT_SECRET=xxx mvn -pl linkforge-server spring-boot:run

                    生成一个：openssl rand -base64 48""");
        }

        // 按字节数算而不是字符数：一个中文字符在 UTF-8 下占 3 字节，
        // 按字符数判断的话，"十一个汉字"看起来只有 5 个字符、实际已经够长了；
        // 反过来 ASCII 场景下两者一样，但校验必须按 jjwt 真正看的那个单位来
        int actualBytes = secretKey.getBytes(StandardCharsets.UTF_8).length;
        if (actualBytes < MIN_SECRET_BYTES) {
            throw new IllegalStateException("""
                    JWT_SECRET 太短：当前 %d 字节，至少要 %d 字节。

                    这是 RFC 7518 的硬性要求（HS256 的密钥不能短于摘要长度）——
                    短密钥可以被爆破出来，爆破出来就能伪造任意用户的 token，
                    所以 jjwt 会直接拒绝，不是可以绕过的限制。

                    换一个更长的，比如：
                      openssl rand -base64 48
                    或者随便敲一句 32 个字符以上的话（但别用能被猜到的）""".formatted(
                    actualBytes, MIN_SECRET_BYTES));
        }
    }
}
