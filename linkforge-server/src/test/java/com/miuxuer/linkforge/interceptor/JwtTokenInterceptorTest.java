package com.miuxuer.linkforge.interceptor;

import com.miuxuer.linkforge.constant.JwtClaimsConstant;
import com.miuxuer.linkforge.constant.MessageConstant;
import com.miuxuer.linkforge.constant.UserConstant;
import com.miuxuer.linkforge.context.CurrentHolder;
import com.miuxuer.linkforge.exception.BusinessException;
import com.miuxuer.linkforge.properties.JwtProperties;
import com.miuxuer.linkforge.result.ResultCode;
import com.miuxuer.linkforge.utils.JwtUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 登录拦截器单元测试。
 *
 * <p>用 spring-test 的 Mock 请求对象手动驱动拦截器，不启动 Spring 上下文。
 */
@DisplayName("登录拦截器")
class JwtTokenInterceptorTest {

    private static final String SECRET = "linkforge-unit-test-secret-key-0123456789";
    private static final String TOKEN_HEADER = "token";

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
        interceptor = new JwtTokenInterceptor(properties);

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
