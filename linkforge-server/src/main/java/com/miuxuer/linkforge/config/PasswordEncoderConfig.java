package com.miuxuer.linkforge.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 密码编码器。
 *
 * <p><b>为什么用 BCrypt 而不是 MD5/SHA-256</b>：MD5 这类是"快速哈希"，
 * 设计目标就是算得快，一张显卡每秒能算几十亿次 —— 拖库之后用彩虹表或者暴力枚举，
 * 常见密码几秒就出来了。BCrypt 是"慢哈希"：自带随机盐（同样的密码每次哈希结果都不同，
 * 彩虹表失效），而且计算成本可调 —— 默认强度 10 意味着一次哈希大约 100 毫秒，
 * 攻击者想爆破就得付出同样的时间成本，但正常登录只多等这 100 毫秒。
 *
 * <p><b>为什么做成 Bean 而不是在 Service 里 new</b>：一是全局只有一个实例，
 * 二是强度参数将来要调整时只改这一处，三是测试里可以注入一个假的实现
 * （比如永远返回 true 的 encoder）来隔离掉哈希耗时。
 */
@Configuration
public class PasswordEncoderConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        // 默认强度 10（2^10 轮），安全性和耗时的常见折中。
        // 调高到 12 会慢 4 倍，登录接口的延迟会明显起来。
        return new BCryptPasswordEncoder();
    }
}
