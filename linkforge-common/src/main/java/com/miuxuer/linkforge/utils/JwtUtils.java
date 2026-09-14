package com.miuxuer.linkforge.utils;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;

/**
 * JWT 签发与解析工具（jjwt 0.12.x API）。
 *
 * <p><b>JWT 是什么</b>：三段用点隔开的 Base64 字符串 —— {@code header.payload.signature}。
 * payload 是明文（只是 Base64，不是加密），任何拿到 token 的人都能解出里面的内容，
 * 所以**绝不能往 claims 里放密码之类的敏感信息**。它的价值不在保密，而在于
 * 第三段签名：改了 payload 里的任何一个字符，签名就对不上，服务端一验就知道被篡改过。
 *
 * <p><b>为什么用 JWT 不用 Session</b>：Session 要求服务端存状态，多实例部署时要么
 * 粘性会话，要么把 session 挪到 Redis 里共享。JWT 是无状态的 —— 服务端不存任何东西，
 * 验签就能确认身份，天然支持横向扩容。代价是签发出去就没法主动作废，
 * 只能等它过期（要做"立即踢下线"就得额外维护一张黑名单，那就又回到有状态了）。
 *
 * <p><b>续签怎么做</b>：这里不做自动续签。常见做法是双 token：access token 短有效期
 * （比如 30 分钟），refresh token 长有效期，前端拿 refresh 去换新的 access。
 */
public final class JwtUtils {

    private JwtUtils() {
    }

    /**
     * 签发 token。
     *
     * @param secretKey 签名密钥，长度必须 ≥ 32 字节
     * @param ttlMillis 有效期（毫秒），从当前时刻起算
     * @param claims    自定义载荷，见 {@code JwtClaimsConstant}
     */
    public static String createJwt(String secretKey, long ttlMillis, Map<String, Object> claims) {
        SecretKey key = hmacKey(secretKey);
        long now = System.currentTimeMillis();
        return Jwts.builder()
                // 0.12 起 setClaims 改名为 claims
                .claims(claims)
                .issuedAt(new Date(now))
                .expiration(new Date(now + ttlMillis))
                // 不指定算法，从 key 的类型反推。显式写 HS256 而 key 实际更短的话，
                // 报错点在签发时，不如让 jjwt 自己按 key 长度选一个匹配的算法。
                .signWith(key)
                .compact();
    }

    /**
     * 解析并验签。
     *
     * <p>会抛 {@code JwtException} 的子类，调用方不用逐一区分：
     * 签名不对是 {@code SignatureException}，过期是 {@code ExpiredJwtException}，
     * 格式不对是 {@code MalformedJwtException}。对业务来说这些都是"token 不可用"，
     * 统一当未登录处理即可。
     *
     * <p>验签失败意味着 token 被篡改或不是我们签的，<b>绝不能</b>把解析失败的 token
     * 当作用户已登录继续往下走。
     *
     * @param secretKey 和签发时用同一个密钥
     * @param token     前端传来的 token
     * @return 载荷
     */
    public static Claims parseJwt(String secretKey, String token) {
        return Jwts.parser()
                // 0.12 起 setSigningKey 改名为 verifyWith
                .verifyWith(hmacKey(secretKey))
                .build()
                // 0.12 起 parseClaimsJws 改名为 parseSignedClaims，getBody 改名为 getPayload
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * 把配置里的字符串密钥转成 HMAC 用的 {@link SecretKey}。
     *
     * <p>用 UTF-8 取字节：同一个配置字符串在不同默认编码的机器上算出不同的 key，
     * 会导致"本机签的 token 到服务器上验不过"这种很难查的问题。
     *
     * <p>{@code hmacShaKeyFor} 会在密钥短于 32 字节时抛 {@code WeakKeyException}，
     * 这是保护而不是麻烦 —— 短密钥可被爆破，爆破出来就能伪造任意用户的 token。
     */
    private static SecretKey hmacKey(String secretKey) {
        return Keys.hmacShaKeyFor(secretKey.getBytes(StandardCharsets.UTF_8));
    }
}
