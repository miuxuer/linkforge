package com.miuxuer.linkforge.interceptor;

import com.miuxuer.linkforge.constant.JwtClaimsConstant;
import com.miuxuer.linkforge.constant.MessageConstant;
import com.miuxuer.linkforge.constant.UserConstant;
import com.miuxuer.linkforge.context.CurrentHolder;
import com.miuxuer.linkforge.exception.BusinessException;
import com.miuxuer.linkforge.properties.JwtProperties;
import com.miuxuer.linkforge.result.ResultCode;
import com.miuxuer.linkforge.utils.JwtUtils;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.List;

/**
 * 登录校验拦截器。
 *
 * <p><b>为什么用拦截器而不是过滤器</b>：拦截器由 DispatcherServlet 调用，能拿到
 * {@code HandlerMethod}（即将执行的是哪个 Controller 的哪个方法），也能直接利用
 * Spring MVC 的异常处理机制 —— 这里抛出的异常会被 {@code @RestControllerAdvice}
 * 接住，自动转成统一的 Result 响应。过滤器跑在 DispatcherServlet 之前，
 * 那两样都拿不到，只能自己往 response 里写 JSON（限流过滤器就是这么干的）。
 *
 * <p>反过来说，过滤器能拦到静态资源和所有请求，拦截器只作用于注册的路径。
 * 本拦截器只注册在 {@code /api/**} 上，所以公开的短码跳转 {@code /{shortCode}}
 * 不受影响。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtTokenInterceptor implements HandlerInterceptor {

    /** 不需要登录就能访问的接口。 */
    private static final List<String> WHITE_LIST = List.of(
            "/api/user/login",
            "/api/user/register"
    );

    /** 管理端路径前缀，访问这些路径额外要求管理员角色。 */
    private static final String ADMIN_PATH_PREFIX = "/api/admin/";

    private final JwtProperties jwtProperties;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 拦截到的不是 Controller 方法（静态资源、错误页面转发等），没有鉴权可言，放行
        if (!(handler instanceof HandlerMethod)) {
            return true;
        }

        // 浏览器的跨域预检请求不带业务请求头（也就没有 token），拦下来会导致跨域直接失败。
        // 预检请求不会真正执行业务逻辑，放行是安全的。
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String path = request.getRequestURI();
        if (WHITE_LIST.contains(path)) {
            return true;
        }

        String token = request.getHeader(jwtProperties.getTokenName());
        if (!StringUtils.hasText(token)) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, MessageConstant.NOT_LOGGED_IN);
        }

        Claims claims;
        try {
            // parseJwt 内部会验签，签名不对、被篡改、过期都会抛异常
            claims = JwtUtils.parseJwt(jwtProperties.getSecretKey(), token);
        } catch (JwtException | IllegalArgumentException e) {
            // 不把具体原因告诉前端：区分"过期"和"签名错误"对排查没帮助，
            // 反而告诉攻击者他改的那部分是签名段还是载荷段
            log.debug("token 校验失败: {}", e.getMessage());
            throw new BusinessException(ResultCode.UNAUTHORIZED, MessageConstant.NOT_LOGGED_IN);
        }

        Object userIdClaim = claims.get(JwtClaimsConstant.USER_ID);
        if (userIdClaim == null) {
            // token 是我们签的，但里面没有 userId —— 只可能是密钥泄露后被人构造的
            log.warn("token 缺少 userId claim，可能是伪造的 token");
            throw new BusinessException(ResultCode.UNAUTHORIZED, MessageConstant.NOT_LOGGED_IN);
        }

        Long userId = ((Number) userIdClaim).longValue();
        Integer role = claims.get(JwtClaimsConstant.ROLE) == null
                ? UserConstant.ROLE_USER
                : ((Number) claims.get(JwtClaimsConstant.ROLE)).intValue();

        // 校验通过，把身份挂到当前线程，后面 Service / Mapper 都能直接取
        CurrentHolder.setCurrentId(userId);
        CurrentHolder.setCurrentRole(role);

        // 管理端接口额外校验角色。普通用户拿着自己的合法 token 也能通过登录校验，
        // 所以"已登录"和"有权限"必须分开判断 —— 少了这一步就是水平越权。
        if (path.startsWith(ADMIN_PATH_PREFIX) && role != UserConstant.ROLE_ADMIN) {
            log.warn("越权访问管理端: userId={}, role={}, path={}", userId, role, path);
            throw new BusinessException(ResultCode.FORBIDDEN, MessageConstant.NO_PERMISSION);
        }

        return true;
    }

    /**
     * 请求结束后清理 ThreadLocal。
     *
     * <p><b>这个方法不能省。</b> Tomcat 用线程池，处理完这个请求的线程会被回收去处理
     * 下一个请求。ThreadLocal 的值挂在线程上而不是请求上 —— 不清理的话，下一个请求
     * 如果没走到 set 就取身份，会拿到上一个用户的信息，表现为"张三的请求带着李四的身份"。
     * 这种 bug 只在并发下偶发，排查成本极高。
     *
     * <p>放在 {@code afterCompletion} 而不是 {@code postHandle}：postHandle 只在请求
     * 正常返回时执行，Controller 抛异常时不会调用，清理就漏了。afterCompletion
     * 是无论成败都执行的收尾钩子。
     */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        CurrentHolder.remove();
    }
}
