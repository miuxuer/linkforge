package com.miuxuer.linkforge.filter;

import com.miuxuer.linkforge.constant.RedisKeyConstant;
import com.miuxuer.linkforge.result.Result;
import com.miuxuer.linkforge.result.ResultCode;
import com.miuxuer.linkforge.utils.WebUtils;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.List;

/**
 * 基于 IP 的滑动窗口限流过滤器 —— 挡刷量。
 *
 * <p>每个 IP 在 {@code windowSeconds} 秒内最多放行 {@code maxRequests} 次跳转请求，
 * 超了直接返回 429，不往下走。
 *
 * <p><b>为什么用滑动窗口而不是固定窗口</b>：固定窗口按整点切分（比如 0-60 秒、
 * 60-120 秒各算一个窗口），攻击者卡在 59 秒和 61 秒各打满一次，两个窗口的额度
 * 就叠在一起放过去了 —— 边界处实际放行量翻倍。滑动窗口按"当前时刻往前推 60 秒"
 * 算，边界效应基本消失。
 *
 * <p><b>为什么必须用 Lua</b>：INCR 和 EXPIRE 是两条命令，分开发两次网络请求，
 * 中间一旦断开（或第一个 key 是新键但 EXPIRE 没执行成功），这个 key 就永不过期，
 * 该 IP 会被永久限流。Lua 脚本在 Redis 服务端一次性执行完，天然原子。
 *
 * <p><b>为什么继承 OncePerRequestFilter</b>：保证一个请求只过滤一次。用原生 Filter
 * 的话，转发（forward）和 include 会让同一个请求重复进过滤器，限流计数被凭空放大。
 *
 * <p>本类只拦 GET /{shortCode} 这类公开跳转；{@code /api/**} 是登录后的业务接口，
 * 用户维度限流是另一套逻辑，不在这里做。
 */
@Slf4j
@Component
@Order(1)
public class RateLimitFilter extends OncePerRequestFilter {

    /**
     * Lua 脚本：原子完成 "计数 +1 → 首次计数时设过期 → 判断是否超阈值"。
     *
     * <p>KEYS[1] = 限流 key，ARGV[1] = 窗口秒数，ARGV[2] = 阈值。
     * 返回 1 放行，0 限流。
     *
     * <p>{@code if current == 1 then EXPIRE} 这个判断是关键：只在第一次计数时设过期，
     * 后续每次 INCR 都刷新过期时间的话，窗口会一直往后滑、永远不过期，
     * 就变成了"每 60 秒允许 100 次"的固定窗口。
     */
    private static final String LUA_SCRIPT = """
            local current = redis.call('INCR', KEYS[1])
            if current == 1 then
                redis.call('EXPIRE', KEYS[1], ARGV[1])
            end
            if current > tonumber(ARGV[2]) then
                return 0
            end
            return 1
            """;

    private final DefaultRedisScript<Long> redisScript =
            new DefaultRedisScript<>(LUA_SCRIPT, Long.class);

    private final ObjectMapper objectMapper;

    /** 可选依赖：Redis 不可用时直接跳过限流，不影响正常服务。 */
    @Autowired(required = false)
    private StringRedisTemplate stringRedisTemplate;

    @Value("${linkforge.ratelimit.window-seconds:60}")
    private int windowSeconds;

    @Value("${linkforge.ratelimit.max-requests:100}")
    private int maxRequests;

    /**
     * 注入 Spring 容器里的 ObjectMapper，而不是自己 {@code new} 一个。
     * 容器里那个已经按 application.yml 配好了命名策略、日期格式等；
     * 自己 new 出来的是一份默认配置，两边序列化同一份数据可能结果不一致。
     */
    public RateLimitFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!isShortLinkRedirect(request) || stringRedisTemplate == null) {
            filterChain.doFilter(request, response);
            return;
        }

        // 取真实 IP 的逻辑和访问明细共用，抽在 WebUtils 里。
        // 两处各写一遍的话，哪天发现了 X-Forwarded-For 的解析 bug，很容易只改一处
        String clientIp = WebUtils.getClientIp(request);
        String key = RedisKeyConstant.RATE_LIMIT + clientIp;

        Long allowed = stringRedisTemplate.execute(
                redisScript,
                List.of(key),
                String.valueOf(windowSeconds),
                String.valueOf(maxRequests));

        if (allowed != null && allowed == 0L) {
            log.warn("限流触发: IP={}, 窗口={}s, 阈值={}", clientIp, windowSeconds, maxRequests);
            writeTooManyRequests(response);
            return;
        }

        filterChain.doFilter(request, response);
    }

    /**
     * 429 响应直接写回，不抛异常。
     *
     * <p>过滤器在 DispatcherServlet 之前执行，异常抛出去到不了
     * {@code GlobalExceptionHandler}（那是 Spring MVC 层的组件），
     * 只会变成容器的默认错误页。所以这里必须自己把响应体写好。
     *
     * <p>状态码用 429 而不是 200：只有 429 才能让网关、CDN 和监控区分出
     * "这是被限流了"，也才方便前端做针对性提示。
     */
    private void writeTooManyRequests(HttpServletResponse response) throws IOException {
        response.setStatus(ResultCode.TOO_MANY_REQUESTS.getHttpStatus());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(
                objectMapper.writeValueAsString(Result.error(ResultCode.TOO_MANY_REQUESTS)));
    }

    /**
     * 判断是否是需要限流的跳转请求：GET 且不是 {@code /api/**}、不是根路径。
     *
     * <p>跳转放在根路径 {@code /{shortCode}} 是为了短链足够短；
     * 身份靠"短码里不含 {@code /}"来区分，所以根路径本身和 API 都要排掉。
     */
    private boolean isShortLinkRedirect(HttpServletRequest request) {
        String path = request.getRequestURI();
        return "GET".equalsIgnoreCase(request.getMethod())
                && !path.startsWith("/api/")
                && path.length() > 1;
    }

}
