package com.miuxuer.linkforge.interceptor;

import com.miuxuer.linkforge.constant.JwtClaimsConstant;
import com.miuxuer.linkforge.constant.MessageConstant;
import com.miuxuer.linkforge.constant.UserConstant;
import com.miuxuer.linkforge.context.CurrentHolder;
import com.miuxuer.linkforge.exception.BusinessException;
import com.miuxuer.linkforge.properties.JwtProperties;
import com.miuxuer.linkforge.result.ResultCode;
import com.miuxuer.linkforge.service.impl.UserStatusChecker;
import com.miuxuer.linkforge.utils.JwtUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

import java.util.HashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 登录拦截器单元测试。
 *
 * <p>用 spring-test 的 Mock 请求对象手动驱动拦截器，不启动 Spring 上下文。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("登录拦截器")
class JwtTokenInterceptorTest {

    private static final String SECRET = "linkforge-unit-test-secret-key-0123456789";
    private static final String TOKEN_HEADER = "token";

    @Mock
    private UserStatusChecker userStatusChecker;

    private JwtTokenInterceptor interceptor;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;
    private HandlerMethod handlerMethod;

    @BeforeEach
    void setUp() throws Exception {
        JwtProperties properties = new JwtProperties();
        properties.setSecretKey(SECRET);
        properties.setTtl(3600_000L);
        properties.setTokenName(TOKEN_HEADER);
        interceptor = new JwtTokenInterceptor(properties, userStatusChecker);
        // 默认账号是启用的，个别用例再覆盖
        when(userStatusChecker.isEnabled(any())).thenReturn(true);

        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
        // 拦截器只对 HandlerMethod 做鉴权，这里造一个假的 Controller 方法
        handlerMethod = new HandlerMethod(new Object(), Object.class.getMethod("toString"));
    }

    @AfterEach
    void tearDown() {
        CurrentHolder.remove();
    }

    private String validToken(Long userId, int role) {
        Map<String, Object> claims = new HashMap<>();
        claims.put(JwtClaimsConstant.USER_ID, userId);
        claims.put(JwtClaimsConstant.ROLE, role);
        return JwtUtils.createJwt(SECRET, 3600_000L, claims);
    }

    // ==================== 放行的情况 ====================

    @Test
    @DisplayName("白名单接口 /api/user/login → 不带 token 也放行")
    void whitelistedPath_shouldPass() {
        request.setRequestURI("/api/user/login");

        assertTrue(interceptor.preHandle(request, response, handlerMethod));
    }

    @Test
    @DisplayName("注册接口同样在白名单里")
    void registerPath_shouldPass() {
        request.setRequestURI("/api/user/register");

        assertTrue(interceptor.preHandle(request, response, handlerMethod));
    }

    @Test
    @DisplayName("不是 Controller 方法（静态资源等）→ 放行")
    void nonHandlerMethod_shouldPass() {
        request.setRequestURI("/api/link");

        assertTrue(interceptor.preHandle(request, response, new Object()));
    }

    @Test
    @DisplayName("OPTIONS 预检请求 → 放行，否则跨域直接失败")
    void optionsRequest_shouldPass() {
        request.setMethod("OPTIONS");
        request.setRequestURI("/api/link");

        assertTrue(interceptor.preHandle(request, response, handlerMethod));
    }

    // ==================== 拦截的情况 ====================

    @Test
    @DisplayName("没带 token → 401")
    void missingToken_shouldThrowUnauthorized() {
        request.setRequestURI("/api/link");

        BusinessException e = assertThrows(BusinessException.class,
                () -> interceptor.preHandle(request, response, handlerMethod));

        assertEquals(ResultCode.UNAUTHORIZED.getHttpStatus(), e.getHttpStatus());
        assertEquals(MessageConstant.NOT_LOGGED_IN, e.getMessage());
    }

    @Test
    @DisplayName("token 是伪造的（用别的密钥签的）→ 401")
    void forgedToken_shouldThrowUnauthorized() {
        request.setRequestURI("/api/link");
        // 换一个密钥签发，签名对不上
        String forged = JwtUtils.createJwt("a-completely-different-secret-key-0123456789",
                3600_000L, Map.of(JwtClaimsConstant.USER_ID, 999L));
        request.addHeader(TOKEN_HEADER, forged);

        assertThrows(BusinessException.class,
                () -> interceptor.preHandle(request, response, handlerMethod));
        // 关键：伪造的 token 不能留下任何身份痕迹
        assertNull(CurrentHolder.getCurrentId());
    }

    @Test
    @DisplayName("token 已过期 → 401")
    void expiredToken_shouldThrowUnauthorized() {
        request.setRequestURI("/api/link");
        request.addHeader(TOKEN_HEADER,
                JwtUtils.createJwt(SECRET, -60_000L, Map.of(JwtClaimsConstant.USER_ID, 1L)));

        assertThrows(BusinessException.class,
                () -> interceptor.preHandle(request, response, handlerMethod));
    }

    @Test
    @DisplayName("普通用户访问管理端 → 403，而不是 401")
    void normalUserAccessAdmin_shouldThrowForbidden() {
        request.setRequestURI("/api/admin/user/page");
        request.addHeader(TOKEN_HEADER, validToken(1001L, UserConstant.ROLE_USER));

        BusinessException e = assertThrows(BusinessException.class,
                () -> interceptor.preHandle(request, response, handlerMethod));

        // 已登录但权限不够 —— 必须是 403 不是 401，否则前端会把用户踢去重新登录，
        // 而重新登录一万次也没用
        assertEquals(ResultCode.FORBIDDEN.getHttpStatus(), e.getHttpStatus());
    }

    @Test
    @DisplayName("★ 账号已被禁用 → 403，哪怕 token 本身完全合法")
    void disabledAccount_shouldBeRejectedEvenWithValidToken() {
        request.setRequestURI("/api/link");
        request.addHeader(TOKEN_HEADER, validToken(1001L, UserConstant.ROLE_USER));
        when(userStatusChecker.isEnabled(1001L)).thenReturn(false);

        BusinessException e = assertThrows(BusinessException.class,
                () -> interceptor.preHandle(request, response, handlerMethod));

        // JWT 是无状态的，token 一签发就收不回来。少了这道状态检查，
        // 管理员禁用用户对"已经登录的人"完全不起作用 ——
        // 他拿着旧 token 照样能调所有接口，直到 token 过期（本项目 7 天）
        assertEquals(ResultCode.FORBIDDEN.getHttpStatus(), e.getHttpStatus());
        assertEquals(MessageConstant.ACCOUNT_DISABLED, e.getMessage());
    }

    @Test
    @DisplayName("★ 校验失败时绝不能留下身份 —— 这是串号的根源")
    void rejectedRequest_shouldNotLeaveIdentityInHolder() {
        request.setRequestURI("/api/link");
        request.addHeader(TOKEN_HEADER, validToken(1001L, UserConstant.ROLE_USER));
        when(userStatusChecker.isEnabled(1001L)).thenReturn(false);

        assertThrows(BusinessException.class,
                () -> interceptor.preHandle(request, response, handlerMethod));

        // 这一条是整个拦截器里最容易被忽略的地方：
        // preHandle 抛异常时，Spring 不会调用它自己的 afterCompletion
        // （HandlerExecutionChain 只回滚"已成功返回 true"的那些拦截器），
        // 所以 ThreadLocal 得不到兜底清理。
        // 唯一的解法是"所有校验都过了再写 ThreadLocal" —— 这里就是在锁住这个顺序。
        assertNull(CurrentHolder.getCurrentId(),
                "校验失败却留下了身份，Tomcat 复用线程时下一个请求会串号");
    }

    @Test
    @DisplayName("★ 越权访问管理端被拒时，同样不能留下身份")
    void forbiddenAdminAccess_shouldNotLeaveIdentityInHolder() {
        request.setRequestURI("/api/admin/user/page");
        request.addHeader(TOKEN_HEADER, validToken(1001L, UserConstant.ROLE_USER));

        assertThrows(BusinessException.class,
                () -> interceptor.preHandle(request, response, handlerMethod));

        assertNull(CurrentHolder.getCurrentId(),
                "越权被拒却留下了身份，线程复用时会串号");
    }

    // ==================== 通过后的状态 ====================

    @Test
    @DisplayName("合法 token → 放行，并把身份写进 ThreadLocal")
    void validToken_shouldPopulateCurrentHolder() {
        request.setRequestURI("/api/link");
        request.addHeader(TOKEN_HEADER, validToken(1001L, UserConstant.ROLE_USER));

        assertTrue(interceptor.preHandle(request, response, handlerMethod));

        assertEquals(1001L, CurrentHolder.getCurrentId());
        assertEquals(UserConstant.ROLE_USER, CurrentHolder.getCurrentRole());
    }

    @Test
    @DisplayName("管理员访问管理端 → 放行")
    void adminAccessAdmin_shouldPass() {
        request.setRequestURI("/api/admin/user/page");
        request.addHeader(TOKEN_HEADER, validToken(1L, UserConstant.ROLE_ADMIN));

        assertTrue(interceptor.preHandle(request, response, handlerMethod));
    }

    // ==================== 最重要的一条 ====================

    @Test
    @DisplayName("请求结束 → ThreadLocal 必须被清理干净")
    void afterCompletion_shouldClearThreadLocal() {
        request.setRequestURI("/api/link");
        request.addHeader(TOKEN_HEADER, validToken(1001L, UserConstant.ROLE_USER));
        interceptor.preHandle(request, response, handlerMethod);
        assertEquals(1001L, CurrentHolder.getCurrentId());

        interceptor.afterCompletion(request, response, handlerMethod, null);

        // 不清的话，Tomcat 把这个线程回收去处理下一个请求时，
        // 下一个用户会带着 1001 这个身份执行 —— 串号 + 越权
        assertNull(CurrentHolder.getCurrentId(), "afterCompletion 没有清理 ThreadLocal");
        assertNull(CurrentHolder.getCurrentRole(), "afterCompletion 没有清理 ThreadLocal");
    }

    @Test
    @DisplayName("请求抛异常结束 → ThreadLocal 同样要清理")
    void afterCompletion_onException_shouldStillClear() {
        request.setRequestURI("/api/link");
        request.addHeader(TOKEN_HEADER, validToken(1001L, UserConstant.ROLE_USER));
        interceptor.preHandle(request, response, handlerMethod);

        // 模拟 Controller 抛异常的场景。这正是清理必须放在 afterCompletion
        // 而不是 postHandle 的原因 —— postHandle 在异常时根本不会执行
        interceptor.afterCompletion(request, response, handlerMethod,
                new RuntimeException("模拟业务异常"));

        assertNull(CurrentHolder.getCurrentId());
    }
}
