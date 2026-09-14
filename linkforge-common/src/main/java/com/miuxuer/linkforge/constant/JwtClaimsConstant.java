package com.miuxuer.linkforge.constant;

/**
 * JWT 载荷里自定义字段的名字。
 *
 * <p>这些名字会写进 token、也会在读 token 时按名字取值。签发端和解析端如果
 * 各写各的字面量，改名字时只改一处就会变成"解析出来永远是 null"，
 * 而且不报错 —— 只是当前用户 id 变成了空。集中定义可以避免。
 */
public final class JwtClaimsConstant {

    /** 用户 id。登录拦截器靠它把当前用户塞进 ThreadLocal。 */
    public static final String USER_ID = "userId";

    /** 用户名，方便打日志和排查，业务上不依赖它。 */
    public static final String USERNAME = "username";

    /** 角色，用于区分普通用户和管理员，避免每个管理端接口都回查一次数据库。 */
    public static final String ROLE = "role";

    private JwtClaimsConstant() {
    }
}
