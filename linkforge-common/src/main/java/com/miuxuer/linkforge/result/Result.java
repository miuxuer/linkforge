package com.miuxuer.linkforge.result;

import lombok.Data;

/**
 * 统一 API 返回结构。前端拿到的所有响应都是 {@code {code, message, data}} 这个形状。
 *
 * <p>三个字段的取值：
 *
 * <ul>
 *   <li>{@code code} —— 业务码，取值见 {@link ResultCode}，0 表示成功
 *   <li>{@code message} —— 提示文案，失败时给用户看
 *   <li>{@code data} —— 业务数据，失败时为 null
 * </ul>
 *
 * <p>注意 {@code code} 和 HTTP 状态码是两回事：HTTP 状态码由 GlobalExceptionHandler
 * 单独写到响应行上（401 就是 401），不会永远是 200。前端 axios 拦截器按 HTTP 状态码
 * 做粗粒度处理（401 跳登录），按 {@code code} 做细粒度分支。
 *
 * @param <T> 业务数据类型；无返回值的接口用 {@code Result<Void>}
 */
@Data
public class Result<T> {

    private int code;
    private String message;
    private T data;

    /** 成功并携带数据。 */
    public static <T> Result<T> success(T data) {
        return build(ResultCode.SUCCESS.getCode(), ResultCode.SUCCESS.getMessage(), data);
    }

    /**
     * 成功但无数据返回（新增、删除这类接口）。
     *
     * <p>单独提供一个无参重载，是为了让调用方写 {@code return Result.success();}
     * 就能拿到 {@code Result<Void>}。否则得写成 {@code Result.<Void>success(null)}，
     * 因为 null 推不出泛型实参。
     */
    public static Result<Void> success() {
        return success(null);
    }

    /** 失败，用 {@link ResultCode} 里的默认文案。 */
    public static <T> Result<T> error(ResultCode resultCode) {
        return error(resultCode, resultCode.getMessage());
    }

    /** 失败，并覆盖默认文案（比如带上具体是哪个字段不合法）。 */
    public static <T> Result<T> error(ResultCode resultCode, String message) {
        return build(resultCode.getCode(), message, null);
    }

    /**
     * 失败，业务码由调用方直接给出。
     *
     * <p>给"异常自己带着业务码"的场景用：GlobalExceptionHandler 处理
     * {@code BusinessException} 时，码是从异常上取的，不是某个固定的 ResultCode。
     */
    public static <T> Result<T> error(int code, String message) {
        return build(code, message, null);
    }

    private static <T> Result<T> build(int code, String message, T data) {
        Result<T> result = new Result<>();
        result.setCode(code);
        result.setMessage(message);
        result.setData(data);
        return result;
    }
}
