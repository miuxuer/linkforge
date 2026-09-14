package com.miuxuer.linkforge.utils;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Web 层的小工具。
 *
 * <p>放在 server 模块而不是 common：common 是"零 Web 依赖"的基础设施，
 * 为了一个取 IP 的方法把 servlet-api 引进依赖链最底层不划算。
 */
public final class WebUtils {

    private WebUtils() {
    }

    /**
     * 取客户端真实 IP。
     *
     * <p>直接拿 {@code getRemoteAddr()} 在有 Nginx 反代时拿到的是 Nginx 的 IP ——
     * 那样所有用户共用一个限流额度，一个人刷就把全站刷限流了；
     * 访问明细里 IP 也全变成同一个，统计完全失真。
     *
     * <p>取 X-Forwarded-For 里的第一个：这个头是逗号分隔的链路
     * （客户端, 代理1, 代理2...），最左边才是原始客户端。
     *
     * <p><b>这个头是客户端可以伪造的</b>，生产环境应该只信任自己那一层代理
     * 追加的值（比如 Nginx 配 {@code proxy_set_header X-Forwarded-For $remote_addr}
     * 直接覆盖而不是追加）。本项目没上 Nginx，这里不做区分。
     */
    public static String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (isBlankOrUnknown(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (isBlankOrUnknown(ip)) {
            ip = request.getRemoteAddr();
        }
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip;
    }

    /**
     * 取 User-Agent，超长就截断。
     *
     * <p>这个头同样是客户端可控的，可以塞很长的内容。数据库那列是 VARCHAR(500)，
     * 不截断的话会直接抛 {@code Data too long} —— 访问明细写不进去，
     * 而且是在异步线程里失败，主流程完全感知不到。
     */
    public static String getUserAgent(HttpServletRequest request) {
        return truncate(request.getHeader("User-Agent"), 500);
    }

    /** 取 Referer，同样截断。 */
    public static String getReferer(HttpServletRequest request) {
        return truncate(request.getHeader("Referer"), 500);
    }

    private static boolean isBlankOrUnknown(String value) {
        return value == null || value.isEmpty() || "unknown".equalsIgnoreCase(value);
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
