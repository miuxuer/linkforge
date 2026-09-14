package com.miuxuer.linkforge.result;

import lombok.Getter;

/**
 * 业务返回码字典。
 *
 * <p>每个枚举项同时携带两个码，这是刻意的设计：
 *
 * <ul>
 *   <li>{@code code} —— 业务码，给前端做分支判断用（不关心 HTTP 传输层）
 *   <li>{@code httpStatus} —— HTTP 状态码，给浏览器、网关、监控告警用
 * </ul>
 *
 * <p>为什么不统一返回 HTTP 200 再把错误塞进 code：那样 CDN、Nginx、APM 这些
 * 只看 HTTP 状态码的中间件会把失败当成功，告警永远不响，前端也得自己解析 body
 * 才知道成没成。两套码各司其职。
 *
 * <p>编码规则：框架级错误的 {@code code} 直接复用 HTTP 状态码（401 就是 401），
 * 业务级错误从 4000 起跳 —— 避免"业务码 404"这种看着像 HTTP 404 的歧义。
 */
@Getter
public enum ResultCode {

    /* ---------- 成功：0，沿用 shortlink 的约定 ---------- */
    SUCCESS(0, 200, "成功"),

    /* ---------- 框架级错误：code 与 httpStatus 对齐 ---------- */
    PARAM_ERROR(400, 400, "参数校验失败"),
    UNAUTHORIZED(401, 401, "未登录或登录已过期"),
    FORBIDDEN(403, 403, "没有访问权限"),
    NOT_FOUND(404, 404, "请求的资源不存在"),
    PAYLOAD_TOO_LARGE(413, 413, "上传内容过大"),
    TOO_MANY_REQUESTS(429, 429, "请求过于频繁，请稍后重试"),

    /* ---------- 业务级错误：code 从 4000 起，避免和 HTTP 状态码混淆 ---------- */
    BUSINESS_ERROR(4000, 400, "业务处理失败"),
    SYSTEM_ERROR(5000, 500, "系统繁忙，请稍后重试");

    /** 业务码，前端判断分支用。 */
    private final int code;

    /** 该错误对应的 HTTP 状态码，由 GlobalExceptionHandler 写到响应头上。 */
    private final int httpStatus;

    /** 默认提示文案。抛异常时没给具体文案就用这个兜底。 */
    private final String message;

    ResultCode(int code, int httpStatus, String message) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.message = message;
    }
}
