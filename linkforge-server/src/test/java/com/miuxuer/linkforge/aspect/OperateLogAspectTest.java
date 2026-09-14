package com.miuxuer.linkforge.aspect;

import com.miuxuer.linkforge.constant.OperateLogConstant;
import com.miuxuer.linkforge.context.CurrentHolder;
import com.miuxuer.linkforge.dto.UserLoginDTO;
import com.miuxuer.linkforge.entity.OperateLog;
import com.miuxuer.linkforge.mapper.OperateLogMapper;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 操作日志切面单元测试。
 *
 * <p>最核心的一条是「方法抛异常时日志依然要落库」—— 参考项目在这里栽过跟头：
 * 它的切面把组装日志的代码写在 {@code joinPoint.proceed()} 后面，
 * 方法一抛异常后面就不执行了，导致「成功的操作有日志、失败的操作反而没有」，
 * 而失败的操作恰恰最需要追溯。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("操作日志切面")
class OperateLogAspectTest {

    @Mock
    private OperateLogMapper operateLogMapper;

    private OperateLogAspect aspect;

    @BeforeEach
    void setUp() {
        aspect = new OperateLogAspect(operateLogMapper, new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        CurrentHolder.remove();
    }

    /** 造一个 proceed() 返回 {@code result} 的切点。 */
    private ProceedingJoinPoint joinPointReturning(Object result) throws Throwable {
        return joinPointReturning(result, "arg-1", 42);
    }

    private ProceedingJoinPoint joinPointReturning(Object result, Object... args) throws Throwable {
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        when(joinPoint.proceed()).thenReturn(result);
        when(joinPoint.getArgs()).thenReturn(args);
        when(joinPoint.getTarget()).thenReturn(new Object());
        Signature signature = mock(Signature.class);
        when(signature.getName()).thenReturn("register");
        when(joinPoint.getSignature()).thenReturn(signature);
        return joinPoint;
    }

    private OperateLog captureSaved() {
        ArgumentCaptor<OperateLog> captor = ArgumentCaptor.forClass(OperateLog.class);
        verify(operateLogMapper).insert(captor.capture());
        return captor.getValue();
    }

    // ==================== 成功 ====================

    @Test
    @DisplayName("方法正常返回 → 落库一条成功日志，且原样返回方法的返回值")
    void success_shouldSaveSuccessLog() throws Throwable {
        CurrentHolder.setCurrentId(1001L);

        Object returned = aspect.recordOperateLog(joinPointReturning("业务返回值"));

        // 环绕通知必须把 proceed() 的结果返回出去，否则调用方拿到 null
        assertEquals("业务返回值", returned);

        OperateLog saved = captureSaved();
        assertEquals(1001L, saved.getOperateUser());
        assertNotNull(saved.getOperateTime());
        assertEquals("register", saved.getMethodName());
        assertNotNull(saved.getClassName());
        assertEquals(OperateLogConstant.STATUS_SUCCESS, saved.getStatus());
        assertNull(saved.getErrorMsg());
        assertNotNull(saved.getCostTime());
        assertTrue(saved.getCostTime() >= 0);
    }

    @Test
    @DisplayName("入参和返回值以 JSON 形式落库，不是对象地址串")
    void success_shouldStoreArgsAsJson() throws Throwable {
        CurrentHolder.setCurrentId(1001L);

        aspect.recordOperateLog(joinPointReturning("ok"));

        OperateLog saved = captureSaved();
        // 参考项目用 Arrays.toString，复杂对象打出来是 [Ljava.lang.Object;@1b6d3586 这种东西
        assertTrue(saved.getMethodParams().contains("arg-1"), saved.getMethodParams());
        assertTrue(saved.getMethodParams().contains("42"), saved.getMethodParams());
        assertTrue(saved.getReturnValue().contains("ok"), saved.getReturnValue());
    }

    // ==================== 脱敏 ====================

    @Test
    @DisplayName("入参里的密码 → 落库前必须脱敏，日志表里不能出现明文密码")
    void sensitiveArgs_shouldBeMasked() throws Throwable {
        UserLoginDTO dto = new UserLoginDTO();
        dto.setUsername("zhangsan");
        dto.setPassword("my-plain-password");

        aspect.recordOperateLog(joinPointReturning("token", dto));

        String params = captureSaved().getMethodParams();
        // 不脱敏的话，等于把密码明文抄了一份进日志表 ——
        // 拖库之后攻击者根本不用破解 BCrypt，直接查日志表就行
        assertFalse(params.contains("my-plain-password"), "日志表里出现了明文密码: " + params);
        assertTrue(params.contains("******"), params);
        // 非敏感字段要保留，否则日志失去排查价值
        assertTrue(params.contains("zhangsan"), params);
    }

    @Test
    @DisplayName("密码字段名大小写不同 / 叫 secret 也要脱敏")
    void sensitiveFieldVariants_shouldBeMasked() throws Throwable {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("Password", "plain-1");
        payload.put("secretKey", "plain-2");
        payload.put("token", "plain-3");
        payload.put("normalField", "keep-me");

        aspect.recordOperateLog(joinPointReturning("ok", payload));

        String params = captureSaved().getMethodParams();
        assertFalse(params.contains("plain-1"), params);
        assertFalse(params.contains("plain-2"), params);
        assertFalse(params.contains("plain-3"), params);
        assertTrue(params.contains("keep-me"), params);
    }

    // ==================== 失败（最关键） ====================

    @Test
    @DisplayName("方法抛异常 → 日志照常落库，status=1，errorMsg 带上异常信息")
    void failure_shouldStillSaveLog() throws Throwable {
        CurrentHolder.setCurrentId(1001L);
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        when(joinPoint.proceed()).thenThrow(new IllegalArgumentException("短码不合法"));
        when(joinPoint.getArgs()).thenReturn(new Object[]{"bad"});
        when(joinPoint.getTarget()).thenReturn(new Object());
        Signature signature = mock(Signature.class);
        when(signature.getName()).thenReturn("create");
        when(joinPoint.getSignature()).thenReturn(signature);

        assertThrows(IllegalArgumentException.class, () -> aspect.recordOperateLog(joinPoint));

        OperateLog saved = captureSaved();
        assertEquals(OperateLogConstant.STATUS_FAILED, saved.getStatus());
        assertNotNull(saved.getErrorMsg());
        assertTrue(saved.getErrorMsg().contains("短码不合法"), saved.getErrorMsg());
        assertTrue(saved.getErrorMsg().contains("IllegalArgumentException"), saved.getErrorMsg());
    }

    @Test
    @DisplayName("方法抛异常 → 异常必须原样往上抛，不能被日志切面吃掉")
    void failure_shouldRethrowOriginalException() throws Throwable {
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        IllegalStateException original = new IllegalStateException("原始异常");
        when(joinPoint.proceed()).thenThrow(original);
        when(joinPoint.getArgs()).thenReturn(new Object[0]);
        when(joinPoint.getTarget()).thenReturn(new Object());
        Signature signature = mock(Signature.class);
        when(signature.getName()).thenReturn("create");
        when(joinPoint.getSignature()).thenReturn(signature);

        // 切面吞掉异常的话，GlobalExceptionHandler 收不到，
        // 前端会拿到"成功"的响应，而操作其实失败了
        Throwable thrown = assertThrows(IllegalStateException.class,
                () -> aspect.recordOperateLog(joinPoint));
        assertEquals("原始异常", thrown.getMessage());
    }

    // ==================== 自我保护的边界 ====================

    @Test
    @DisplayName("日志落库本身失败 → 不能影响业务返回值")
    void saveFailure_shouldNotBreakBusiness() throws Throwable {
        when(operateLogMapper.insert(any(OperateLog.class)))
                .thenThrow(new RuntimeException("数据库连接超时"));

        // 业务已经执行完了，这时候抛异常给用户，用户会以为失败并重试，
        // 于是同一个操作做了两遍
        Object returned = assertDoesNotThrow(
                () -> aspect.recordOperateLog(joinPointReturning("业务数据")));
        assertEquals("业务数据", returned);
    }

    @Test
    @DisplayName("异常信息超长 → 截断到列宽以内，不能把日志表撑爆")
    void longErrorMessage_shouldBeTruncated() throws Throwable {
        String longMessage = "x".repeat(2000);
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        when(joinPoint.proceed()).thenThrow(new RuntimeException(longMessage));
        when(joinPoint.getArgs()).thenReturn(new Object[0]);
        when(joinPoint.getTarget()).thenReturn(new Object());
        Signature signature = mock(Signature.class);
        when(signature.getName()).thenReturn("create");
        when(joinPoint.getSignature()).thenReturn(signature);

        assertThrows(RuntimeException.class, () -> aspect.recordOperateLog(joinPoint));

        String errorMsg = captureSaved().getErrorMsg();
        // error_msg 是 VARCHAR(500)，超一个字符就是 Data too long —— 记日志反而抛异常
        assertTrue(errorMsg.length() <= OperateLogConstant.MAX_ERROR_LENGTH,
                "截断后仍然超长: " + errorMsg.length());
        assertTrue(errorMsg.endsWith(OperateLogConstant.TRUNCATED_SUFFIX));
    }

    @Test
    @DisplayName("入参无法序列化成 JSON → 退化为 toString，而不是丢日志")
    void unserializableArgs_shouldFallBack() throws Throwable {
        ObjectMapper failingMapper = mock(ObjectMapper.class);
        when(failingMapper.writeValueAsString(any())).thenThrow(new RuntimeException("序列化失败"));
        OperateLogAspect fallbackAspect = new OperateLogAspect(operateLogMapper, failingMapper);

        fallbackAspect.recordOperateLog(joinPointReturning("ok"));

        OperateLog saved = captureSaved();
        assertNotNull(saved.getMethodParams());
        // 退化成 toString 也远比"因为序列化失败整条日志丢掉"要好
        assertTrue(saved.getReturnValue().contains("ok"), saved.getReturnValue());
    }
}
