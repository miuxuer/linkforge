package com.miuxuer.linkforge.exception;

import com.miuxuer.linkforge.result.ResultCode;

/**
 * 短链不存在异常：短码查不到对应记录，或者记录已失效。
 *
 * <p>继承 {@link BusinessException} 并绑定 {@link ResultCode#NOT_FOUND}，
 * 于是 GlobalExceptionHandler 不需要为它写任何专门的分支 —— 异常自己带着
 * "该返回 HTTP 404" 这个信息往上走。这是把业务码和 HTTP 状态码绑进异常的好处。
 *
 * <p>为什么不直接在 Controller 里 {@code response.setStatus(404)}：那样 Service 层
 * 就没法表达"这条短链不存在"了，只能返回 null 让调用方猜。
 */
public class LinkNotFoundException extends BusinessException {

    /** 出问题的短码，方便打日志和排查。 */
    private final String shortCode;

    public LinkNotFoundException(String shortCode) {
        super(ResultCode.NOT_FOUND, "短链不存在或已失效: " + shortCode);
        this.shortCode = shortCode;
    }

    public String getShortCode() {
        return shortCode;
    }
}
