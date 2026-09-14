package com.miuxuer.linkforge.handler;

import com.miuxuer.linkforge.exception.BusinessException;
import com.miuxuer.linkforge.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器。
 *
 * <p>把异常统一翻译成 {@code Result} 格式的响应体，同时把 HTTP 状态码设置正确。
 *
 * <p><b>为什么状态码不能永远是 200</b>：有些写法会把所有异常都包成 HTTP 200 +
 * {@code {code: 500}}。这样浏览器、Nginx、CDN、APM 监控这些只看 HTTP 状态码的中间件
 * 会把失败当成成功 —— 线上接口全挂了，监控大盘还是一片绿。前端也得先解析完 body
 * 才知道成没成。状态码归状态码，业务码归业务码。
 *
 * <p><b>为什么日志级别是 warn 不是 error</b>：业务异常是"预期内的失败"
 * （用户传了不存在的短码、用户名重复），不是程序 bug。用 error 级别会让真正的
 * 程序错误淹没在业务异常的噪音里。这类异常连堆栈都不用打 —— 异常信息本身就说明
 * 了问题在哪。
 *
 * <p>目前只处理业务异常这一支。参数校验异常、兜底异常是阶段 1 的任务。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 业务异常：业务码和 HTTP 状态码都从异常上取。
     *
     * <p>用 {@code ResponseEntity} 而不是在方法上打 {@code @ResponseStatus}：
     * 后者只能写死一个状态码，而业务异常的状态码是每个异常自己决定的
     * （短链不存在是 404，参数不合法是 400）。
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> handleBusinessException(BusinessException e) {
        log.warn("业务异常: code={}, message={}", e.getCode(), e.getMessage());
        return ResponseEntity.status(e.getHttpStatus())
                .body(Result.error(e.getCode(), e.getMessage()));
    }
}
