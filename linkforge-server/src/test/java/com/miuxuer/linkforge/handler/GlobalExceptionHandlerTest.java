package com.miuxuer.linkforge.handler;

import com.miuxuer.linkforge.exception.BusinessException;
import com.miuxuer.linkforge.exception.LinkNotFoundException;
import com.miuxuer.linkforge.result.Result;
import com.miuxuer.linkforge.result.ResultCode;
import org.apache.tomcat.util.http.InvalidParameterException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 全局异常处理器单元测试。
 *
 * <p>直接 new 出处理器调用方法，不起 Spring 上下文 —— 要验的是"异常翻译成什么响应"，
 * 不需要 MockMvc。
 *
 * <p>其中最关键的一条是：兜底分支<b>不能</b>把异常自身的 message 返回给前端。
 */
@DisplayName("全局异常处理")
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("业务异常 → HTTP 状态码取自异常自身，不是写死的")
    void businessException_shouldUseItsOwnStatus() {
        ResponseEntity<Result<Void>> response =
                handler.handleBusinessException(new LinkNotFoundException("abc123"));

        assertEquals(404, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals(ResultCode.NOT_FOUND.getCode(), response.getBody().getCode());
        assertTrue(response.getBody().getMessage().contains("abc123"));
    }

    @Test
    @DisplayName("业务异常带自定义业务码 → 原样透出")
    void businessException_shouldKeepCustomCode() {
        ResponseEntity<Result<Void>> response = handler.handleBusinessException(
                new BusinessException(ResultCode.FORBIDDEN, "账号已被禁用"));

        assertEquals(403, response.getStatusCode().value());
        assertEquals(ResultCode.FORBIDDEN.getCode(), response.getBody().getCode());
        assertEquals("账号已被禁用", response.getBody().getMessage());
    }

    @Test
    @DisplayName("参数校验失败 → 400，且把所有字段的错误一次性返回")
    void validationException_shouldReturn400WithAllMessages() throws Exception {
        MethodArgumentNotValidException e = validationException(
                new String[]{"用户名不能为空", "密码长度需在 6-32 位之间"});

        ResponseEntity<Result<Void>> response = handler.handleValidationException(e);

        assertEquals(400, response.getStatusCode().value());
        assertEquals(ResultCode.PARAM_ERROR.getCode(), response.getBody().getCode());
        String message = response.getBody().getMessage();
        // 一次把问题说全，前端才能一次标红所有字段，不用用户改一个提交一次
        assertTrue(message.contains("用户名不能为空"), message);
        assertTrue(message.contains("密码长度需在 6-32 位之间"), message);
    }

    @Test
    @DisplayName("兜底：未知异常 → 500，且绝不把异常内部信息返回给前端")
    void unexpectedException_shouldNotLeakInternalDetail() {
        // 模拟一个典型的"不该给用户看"的异常
        RuntimeException leaked = new RuntimeException(
                "PreparedStatementCallback; bad SQL grammar "
                        + "[SELECT * FROM t_link WHERE user_id = ?]; "
                        + "nested exception is java.sql.SQLSyntaxErrorException");

        ResponseEntity<Result<Void>> response = handler.handleUnexpectedException(leaked);

        assertEquals(500, response.getStatusCode().value());
        assertEquals(ResultCode.SYSTEM_ERROR.getCode(), response.getBody().getCode());

        String message = response.getBody().getMessage();
        // 表名、SQL 片段、异常类名都是给攻击者画地图用的，一个字都不能漏出去
        assertFalse(message.contains("t_link"), "响应体泄露了表名");
        assertFalse(message.contains("SELECT"), "响应体泄露了 SQL");
        assertFalse(message.contains("SQLSyntaxErrorException"), "响应体泄露了内部异常类名");
        assertEquals(ResultCode.SYSTEM_ERROR.getMessage(), message);
    }

    @Test
    @DisplayName("查询参数编码错（非 UTF-8）→ 400 而不是 500")
    void invalidParameterEncoding_shouldReturn400() {
        // Tomcat 解码失败时抛的异常。不单独接住的话会掉进兜底分支变成 500，
        // 监控就把它统计成"服务端故障"了 —— 实际上是客户端编码不对
        InvalidParameterException e = new InvalidParameterException(
                "Character decoding failed. Parameter [keyword] has been ignored.");

        ResponseEntity<Result<Void>> response = handler.handleInvalidParameter(e);

        assertEquals(400, response.getStatusCode().value());
        assertTrue(response.getStatusCode().is4xxClientError());
        assertEquals(ResultCode.PARAM_ERROR.getCode(), response.getBody().getCode());
    }

    @Test
    @DisplayName("上传文件超限 → 413，而不是 500")
    void maxUploadSizeExceeded_shouldReturn413() {
        // 用户传了个 20MB 的图片显然不是"服务器内部错误"，
        // 落进兜底分支会污染错误率，前端也拿不到可用的提示
        MaxUploadSizeExceededException e =
                new MaxUploadSizeExceededException(10 * 1024 * 1024L);

        ResponseEntity<Result<Void>> response = handler.handleMaxUploadSize(e);

        assertEquals(413, response.getStatusCode().value());
        assertEquals(ResultCode.PAYLOAD_TOO_LARGE.getCode(), response.getBody().getCode());
    }

    @Test
    @DisplayName("参数校验失败也算「用户输入问题」，不该按系统错误处理")
    void validationException_shouldNotBeTreatedAsSystemError() throws Exception {
        ResponseEntity<Result<Void>> response =
                handler.handleValidationException(validationException(new String[]{"用户名不能为空"}));

        // 400 而不是 500 —— 500 会污染错误率指标，把用户填错也统计成"服务端故障"
        assertEquals(400, response.getStatusCode().value());
        assertTrue(response.getStatusCode().is4xxClientError());
    }

    /** 造一个带若干字段错误的 MethodArgumentNotValidException。 */
    private static MethodArgumentNotValidException validationException(String[] messages) throws Exception {
        MethodParameter parameter = new MethodParameter(
                GlobalExceptionHandlerTest.class.getDeclaredMethod("fakeControllerMethod", Object.class), 0);
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "dto");
        for (int i = 0; i < messages.length; i++) {
            bindingResult.addError(new FieldError("dto", "field" + i, messages[i]));
        }
        return new MethodArgumentNotValidException(parameter, bindingResult);
    }

    /** 只为给上面的 MethodParameter 提供一个方法签名，不会被真的调用。 */
    @SuppressWarnings("unused")
    private void fakeControllerMethod(Object dto) {
    }
}
