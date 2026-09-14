package com.miuxuer.linkforge.utils;

import com.miuxuer.linkforge.constant.JwtClaimsConstant;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.security.WeakKeyException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JWT 签发/解析单元测试。
 *
 * <p>纯逻辑测试，不加载 Spring 上下文，也不依赖任何外部服务。
 *
 * <p>重点覆盖"验签失败"这一支：JWT 的安全性全部建立在签名之上，
 * 如果解析失败还能拿到 claims，那这个 token 机制等于不存在。
 */
@DisplayName("JWT 工具")
class JwtUtilsTest {

    /** 32 字节以上。HS256 要求密钥不短于摘要长度，短了会抛 WeakKeyException。 */
    private static final String SECRET = "linkforge-unit-test-secret-key-0123456789";
    private static final String OTHER_SECRET = "another-totally-different-secret-key-abcdefg";

    private static final long ONE_HOUR = 3600_000L;

    @Test
    @DisplayName("签发再解析 → claims 原样还原")
    void createAndParse_shouldRoundTrip() {
        Map<String, Object> claims = Map.of(
                JwtClaimsConstant.USER_ID, 12345L,
                JwtClaimsConstant.USERNAME, "miuxuer",
                JwtClaimsConstant.ROLE, 1);

        String token = JwtUtils.createJwt(SECRET, ONE_HOUR, claims);
        Claims parsed = JwtUtils.parseJwt(SECRET, token);

        assertEquals(12345L, ((Number) parsed.get(JwtClaimsConstant.USER_ID)).longValue());
        assertEquals("miuxuer", parsed.get(JwtClaimsConstant.USERNAME));
        assertEquals(1, ((Number) parsed.get(JwtClaimsConstant.ROLE)).intValue());
        // 过期时间是 jjwt 按我们给的 TTL 自己填的标准字段
        assertTrue(parsed.getExpiration().getTime() > System.currentTimeMillis());
    }

    @Test
    @DisplayName("换一个密钥解析 → 验签失败")
    void parseWithWrongSecret_shouldThrow() {
        String token = JwtUtils.createJwt(SECRET, ONE_HOUR, Map.of(JwtClaimsConstant.USER_ID, 1L));

        // 这就是 JWT 的全部安全性所在：没有正确密钥，签不出合法 token
        assertThrows(JwtException.class, () -> JwtUtils.parseJwt(OTHER_SECRET, token));
    }

    @Test
    @DisplayName("过期的 token → 抛 ExpiredJwtException")
    void parseExpiredToken_shouldThrow() {
        // 负数 TTL = 有效期在签发时就已过去
        String token = JwtUtils.createJwt(SECRET, -60_000L, Map.of(JwtClaimsConstant.USER_ID, 1L));

        assertThrows(ExpiredJwtException.class, () -> JwtUtils.parseJwt(SECRET, token));
    }

    @Test
    @DisplayName("篡改 payload → 验签失败")
    void parseTamperedToken_shouldThrow() {
        String token = JwtUtils.createJwt(SECRET, ONE_HOUR, Map.of(JwtClaimsConstant.USER_ID, 1L));
        String[] parts = token.split("\\.");

        // 把 payload 换成一个"用户 id 是 999"的内容，但签名段保持原样。
        // payload 是明文可改的，改完签名必然对不上 —— 这正是签名存在的意义。
        String forgedPayload = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"userId\":999}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String tampered = parts[0] + "." + forgedPayload + "." + parts[2];

        assertThrows(JwtException.class, () -> JwtUtils.parseJwt(SECRET, tampered));
    }

    @Test
    @DisplayName("密钥短于 32 字节 → 直接拒绝，不允许用弱密钥签发")
    void weakSecret_shouldThrow() {
        // 短密钥能被暴力枚举，枚举出来就能伪造任意用户的 token。
        // jjwt 宁可抛异常也不让我们签出去。
        assertThrows(WeakKeyException.class,
                () -> JwtUtils.createJwt("too-short", ONE_HOUR, Map.of()));
    }

    @Test
    @DisplayName("完全不是 token 的字符串 → 解析失败而不是当成合法 token")
    void malformedToken_shouldThrow() {
        assertThrows(JwtException.class, () -> JwtUtils.parseJwt(SECRET, "not-a-jwt-at-all"));
    }
}
