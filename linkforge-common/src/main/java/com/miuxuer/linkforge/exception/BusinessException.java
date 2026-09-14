package com.miuxuer.linkforge.exception;

import com.miuxuer.linkforge.result.ResultCode;
import lombok.Getter;

/**
 * 业务异常基类。所有"预期内的失败"都抛这个或它的子类。
 *
 * <p>两个设计点：
 *
 * <p><b>一、继承 RuntimeException 而不是 Exception。</b> Spring 的 {@code @Transactional}
 * 默认只在遇到 {@code RuntimeException} 和 {@code Error} 时回滚，受检异常不回滚。如果这里
 * 继承 Exception，那么"扣减库存到一半抛业务异常"这种场景事务会照常提交，数据就脏了。
 * 要么继承 RuntimeException，要么在每个 {@code @Transactional} 上写
 * {@code rollbackFor = Exception.class} —— 前者省事且不容易漏。
 *
 * <p><b>二、同时带业务码和 HTTP 状态码。</b> 由 {@link ResultCode} 统一决定两者的映射，
 * GlobalExceptionHandler 直接取来用，业务代码里不用关心该返回 400 还是 404。
 */
@Getter
public class BusinessException extends RuntimeException {

    /** 业务码，最终会写进 {@code Result.code}。 */
    private final int code;

    /** 该异常对应的 HTTP 状态码，由 GlobalExceptionHandler 写到响应行上。 */
    private final int httpStatus;

    /** 只给文案，业务码取 {@link ResultCode#BUSINESS_ERROR} 兜底。 */
    public BusinessException(String message) {
        this(ResultCode.BUSINESS_ERROR, message);
    }

    /** 用枚举里的默认文案。 */
    public BusinessException(ResultCode resultCode) {
        this(resultCode, resultCode.getMessage());
    }

    /**
     * 业务码取自枚举，文案自己指定 —— 给需要拼接细节的场景用，
     * 比如 {@code new BusinessException(ResultCode.PARAM_ERROR, "短码 " + code + " 已被占用")}。
     */
    public BusinessException(ResultCode resultCode, String message) {
        super(message);
        this.code = resultCode.getCode();
        this.httpStatus = resultCode.getHttpStatus();
    }
}
