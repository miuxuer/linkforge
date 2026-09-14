package com.miuxuer.linkforge.handler;

import com.miuxuer.linkforge.exception.BusinessException;
import com.miuxuer.linkforge.result.Result;
import com.miuxuer.linkforge.result.ResultCode;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.apache.tomcat.util.http.InvalidParameterException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Objects;
import java.util.stream.Collectors;

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
 * <p><b>分支顺序无关紧要</b>：Spring 不按声明顺序匹配，而是挑<b>最具体</b>的那个
 * {@code @ExceptionHandler}。{@code BusinessException} 同时匹配业务分支和最后的
 * {@code Exception} 兜底分支时，一定是前者生效。
 *
 * <p><b>为什么业务异常用 warn、兜底用 error</b>：业务异常是"预期内的失败"
 * （用户传了不存在的短码、用户名重复），不是程序 bug。用 error 级别会让真正的
 * 程序错误淹没在业务异常的噪音里，告警也就失去了意义。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 第一级：业务异常。业务码和 HTTP 状态码都从异常上取。
     *
     * <p>用 {@code ResponseEntity} 而不是在方法上打 {@code @ResponseStatus}：
     * 后者只能写死一个状态码，而业务异常的状态码是每个异常自己决定的
     * （短链不存在是 404，参数不合法是 400）。
     *
     * <p>不打堆栈：这类异常的 message 已经说清了问题在哪，堆栈对这个阶段的排查
     * 没有帮助，反而会把日志刷得看不清。
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> handleBusinessException(BusinessException e) {
        log.warn("业务异常: code={}, message={}", e.getCode(), e.getMessage());
        return ResponseEntity.status(e.getHttpStatus())
                .body(Result.error(e.getCode(), e.getMessage()));
    }

    /**
     * 第二级：参数校验失败。
     *
     * <p>三个入口对应三种触发方式，都得接住，否则会掉进兜底分支变成 500 ——
     * 用户只是少填了个字段，却收到"服务器内部错误"，前端也没法提示具体哪里不对。
     *
     * <ul>
     *   <li>{@code MethodArgumentNotValidException} —— {@code @RequestBody} 上的 {@code @Valid}
     *   <li>{@code ConstraintViolationException} —— {@code @Validated} 加在方法参数上
     *   <li>{@code HttpMessageNotReadableException} —— 请求体根本不是合法 JSON
     * </ul>
     *
     * <p>把所有字段的错误一次性返回，而不是只报第一个：让前端一次就能把表单全标红，
     * 不用用户改一个提交一次。
     */
    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            ConstraintViolationException.class,
            HttpMessageNotReadableException.class
    })
    public ResponseEntity<Result<Void>> handleValidationException(Exception e) {
        String message = extractValidationMessage(e);
        log.warn("参数校验失败: {}", message);
        return ResponseEntity.status(ResultCode.PARAM_ERROR.getHttpStatus())
                .body(Result.error(ResultCode.PARAM_ERROR, message));
    }

    /**
     * 查询参数解码失败。
     *
     * <p>客户端把非 UTF-8 编码的中文（比如用 GBK 编码后塞进 URL）当查询参数发过来时，
     * Tomcat 解码会抛这个异常。它是<b>客户端的问题</b>，必须返回 400 ——
     * 落进兜底分支变成 500 的话，监控会把它统计成"服务端故障"，
     * 排查方向也被带偏（明明是对方编码不对，却去查服务端代码）。
     *
     * <p>直接 import Tomcat 的类确实有点脏，换 Jetty / Undertow 就编译不过。
     * 但换来的是类型安全、编译期就能发现 —— 真换了容器，这行编译失败正好提醒你
     * "这里的异常类型要跟着换"，比运行期静默失效要好。
     *
     * <p>不能图省事接父类 {@code IllegalStateException}：那会把大量真正的程序错误
     * （比如状态机用错、容器初始化失败）也误报成 400，反而掩盖 bug。
     */
    @ExceptionHandler(InvalidParameterException.class)
    public ResponseEntity<Result<Void>> handleInvalidParameter(InvalidParameterException e) {
        log.warn("请求参数解码失败（客户端编码可能不是 UTF-8）: {}", e.getMessage());
        return ResponseEntity.status(ResultCode.PARAM_ERROR.getHttpStatus())
                .body(Result.error(ResultCode.PARAM_ERROR, "请求参数编码不正确，请使用 UTF-8 编码"));
    }

    /**
     * 第三级：兜底。
     *
     * <p><b>绝不能把 {@code e.getMessage()} 返回给前端</b>：异常信息里可能带着
     * SQL 片段、表名、文件路径、内部类名 —— 这些是给攻击者画地图用的。
     * 前端只拿到一句无关痛痒的"系统繁忙"，真正的堆栈写进日志，
     * 排查靠日志和链路追踪，不靠接口返回。
     *
     * <p>这里必须打完整堆栈（error 级别 + 传 e）：这是唯一能定位到"哪一行炸了"的地方。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleUnexpectedException(Exception e) {
        log.error("未预期的异常", e);
        return ResponseEntity.status(ResultCode.SYSTEM_ERROR.getHttpStatus())
                .body(Result.error(ResultCode.SYSTEM_ERROR));
    }

    /**
     * 静态资源找不到 —— 也就是请求的路径压根没有对应的接口。
     *
     * <p>Spring Boot 3.2 起，未匹配的请求会被交给静态资源处理器，抛出
     * {@code NoResourceFoundException} 而不是老的 {@code NoHandlerFoundException}。
     * 不单独接住的话，访问一个不存在的接口会返回 500，而正确答案是 404 ——
     * 500 的意思是"服务端有 bug"，会误导排查方向，也会污染错误率指标。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Result<Void>> handleNoResourceFound(NoResourceFoundException e) {
        log.warn("接口不存在: {}", e.getResourcePath());
        return ResponseEntity.status(ResultCode.NOT_FOUND.getHttpStatus())
                .body(Result.error(ResultCode.NOT_FOUND, "请求的接口不存在"));
    }

    /** 从不同来源的校验异常里抽出提示文案。 */
    private String extractValidationMessage(Exception e) {
        if (e instanceof MethodArgumentNotValidException ex) {
            return ex.getBindingResult().getFieldErrors().stream()
                    .map(FieldError::getDefaultMessage)
                    .filter(Objects::nonNull)
                    .collect(Collectors.joining("；"));
        }
        if (e instanceof ConstraintViolationException ex) {
            return ex.getConstraintViolations().stream()
                    .map(v -> v.getMessage())
                    .collect(Collectors.joining("；"));
        }
        // 请求体不是合法 JSON，没有字段级信息可给，用默认文案
        return "请求参数格式不正确";
    }
}
