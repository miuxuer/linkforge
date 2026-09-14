package com.miuxuer.linkforge.filter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 限流过滤器单元测试。
 *
 * <p>不启动 Spring 上下文：手动 new 出 Filter，用 Mockito 假装 Redis，
 * 用 spring-test 的 MockHttpServletRequest / Response 装作一次请求。
 * 这种写法跑得快，也不用本机开着 Redis。
 */
@DisplayName("限流过滤器")
class RateLimitFilterTest {

    private RateLimitFilter filter;
    private StringRedisTemplate stringRedisTemplate;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;
    private MockFilterChain filterChain;

    @BeforeEach
    void setUp() {
        // ObjectMapper 在真实运行时不从这里来，是 Spring 容器注入的；
        // 测试里给一个默认配置的实例就够了
        filter = new RateLimitFilter(new ObjectMapper());
        stringRedisTemplate = mock(StringRedisTemplate.class);
        ReflectionTestUtils.setField(filter, "stringRedisTemplate", stringRedisTemplate);
        ReflectionTestUtils.setField(filter, "windowSeconds", 60);
        ReflectionTestUtils.setField(filter, "maxRequests", 100);

        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
        filterChain = new MockFilterChain();
    }

    @Test
    @DisplayName("非跳转请求（POST /api/link）→ 跳过限流，不碰 Redis")
    void nonRedirectRequest_shouldPass() throws Exception {
        request.setMethod("POST");
        request.setRequestURI("/api/link");

        filter.doFilterInternal(request, response, filterChain);

        verify(stringRedisTemplate, never()).execute(any(), anyList(), any());
        assertEquals(200, response.getStatus());
    }

    @Test
    @DisplayName("未超限（Lua 返回 1）→ 放行")
    void underLimit_shouldPass() throws Exception {
        request.setMethod("GET");
        request.setRequestURI("/abc123");
        request.setRemoteAddr("192.168.1.1");
        when(stringRedisTemplate.execute(any(DefaultRedisScript.class), anyList(), anyString(), anyString()))
                .thenReturn(1L);

        filter.doFilterInternal(request, response, filterChain);

        assertEquals(200, response.getStatus());
    }

    @Test
    @DisplayName("超限（Lua 返回 0）→ 返回 429 和统一格式的响应体")
    void overLimit_shouldReturn429() throws Exception {
        request.setMethod("GET");
        request.setRequestURI("/abc123");
        request.setRemoteAddr("192.168.1.1");
        when(stringRedisTemplate.execute(any(DefaultRedisScript.class), anyList(), anyString(), anyString()))
                .thenReturn(0L);

        filter.doFilterInternal(request, response, filterChain);

        assertEquals(429, response.getStatus());
        assertTrue(response.getContentAsString().contains("请求过于频繁"),
                "响应体应该带上可读的提示，实际是: " + response.getContentAsString());
    }

    @Test
    @DisplayName("Redis 不可用 → 跳过限流正常放行，不因为缓存挂了就把服务卡死")
    void redisUnavailable_shouldPass() throws Exception {
        ReflectionTestUtils.setField(filter, "stringRedisTemplate", null);
        request.setMethod("GET");
        request.setRequestURI("/abc123");

        filter.doFilterInternal(request, response, filterChain);

        assertEquals(200, response.getStatus());
    }

    @Test
    @DisplayName("根路径 / → 跳过限流")
    void rootPath_shouldSkip() throws Exception {
        request.setMethod("GET");
        request.setRequestURI("/");

        filter.doFilterInternal(request, response, filterChain);

        verify(stringRedisTemplate, never()).execute(any(), anyList(), any());
        assertEquals(200, response.getStatus());
    }

    @Test
    @DisplayName("带 X-Forwarded-For → 用真实客户端 IP 做限流 key，而不是代理 IP")
    void proxyIp_shouldBeUsed() throws Exception {
        request.setMethod("GET");
        request.setRequestURI("/abc123");
        request.addHeader("X-Forwarded-For", "10.0.0.1, 192.168.1.1");
        when(stringRedisTemplate.execute(any(DefaultRedisScript.class), anyList(), anyString(), anyString()))
                .thenReturn(1L);

        filter.doFilterInternal(request, response, filterChain);

        // 取逗号分隔里的第一个，那才是原始客户端；拿最后一个的话所有人都算在代理头上
        verify(stringRedisTemplate).execute(
                any(DefaultRedisScript.class),
                eq(List.of("linkforge:ratelimit:10.0.0.1")),
                anyString(), anyString());
    }
}
