package com.miuxuer.linkforge.properties;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * JWT 配置校验测试。
 *
 * <p>这几条锁住的是"启动时就发现问题"，而不是"登录时才报 500"。
 * 这个区别很实际：密钥太短时应用能正常启动、接口能访问、数据库也连得上，
 * 一切看起来都没问题 —— 直到第一个用户点登录，收到一句"系统繁忙"。
 */
@DisplayName("JWT 配置校验")
class JwtPropertiesTest {

    private static JwtProperties withSecret(String secret) {
        JwtProperties properties = new JwtProperties();
        properties.setSecretKey(secret);
        properties.setTtl(3600_000L);
        properties.setTokenName("token");
        return properties;
    }

    @Test
    @DisplayName("32 字节以上的密钥 → 通过")
    void validSecret_shouldPass() {
        assertThatCode(() -> withSecret("linkforge-test-secret-key-at-least-32-bytes").validateSecretKey())
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("正好 32 字节 → 通过（边界值，不能多判一个）")
    void exactly32Bytes_shouldPass() {
        // 32 个字符的 ASCII = 32 字节，刚好卡在 RFC 7518 的下限上
        assertThatCode(() -> withSecret("a".repeat(32)).validateSecretKey())
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("★ 密钥太短 → 启动就失败，并且提示里带上当前长度")
    void shortSecret_shouldFailFast() {
        // 这条最典型：随手敲一句短的，应用能起来但登录必挂
        assertThatThrownBy(() -> withSecret("short-secret").validateSecretKey())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("12 字节")
                .hasMessageContaining("32");
    }

    @Test
    @DisplayName("没配密钥 → 启动就失败")
    void missingSecret_shouldFailFast() {
        assertThatThrownBy(() -> withSecret(null).validateSecretKey())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET 没有配置");

        assertThatThrownBy(() -> withSecret("").validateSecretKey())
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> withSecret("   ").validateSecretKey())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("★ 按字节数校验，不是按字符数")
    void shouldCountBytesNotCharacters() {
        // 一个中文字符在 UTF-8 下占 3 字节。11 个汉字 = 33 字节，够用
        assertThatCode(() -> withSecret("一二三四五六七八九十壹").validateSecretKey())
                .doesNotThrowAnyException();

        // 而 10 个汉字 = 30 字节，不够。
        // 如果按字符数判断（10 < 32），这条会通过校验、然后在登录时炸掉 ——
        // 校验必须和 jjwt 真正看的单位一致
        assertThatThrownBy(() -> withSecret("一二三四五六七八九十").validateSecretKey())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("30 字节");
    }
}
