package com.miuxuer.linkforge.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Web 工具")
class WebUtilsTest {

    private static MockHttpServletRequest request() {
        return new MockHttpServletRequest();
    }

    // ==================== 取客户端 IP ====================

    @Test
    @DisplayName("没有代理头 → 用 RemoteAddr")
    void noProxyHeader_shouldUseRemoteAddr() {
        MockHttpServletRequest request = request();
        request.setRemoteAddr("192.168.1.100");

        assertThat(WebUtils.getClientIp(request)).isEqualTo("192.168.1.100");
    }

    @Test
    @DisplayName("★ 有 X-Forwarded-For → 取第一个（原始客户端），不是代理 IP")
    void forwardedFor_shouldTakeFirstEntry() {
        MockHttpServletRequest request = request();
        request.setRemoteAddr("10.0.0.9");
        // 真实链路：客户端 1.2.3.4 → 代理 10.0.0.1 → 代理 10.0.0.9
        request.addHeader("X-Forwarded-For", "1.2.3.4, 10.0.0.1, 10.0.0.9");

        // 取最后一个的话所有人都算在代理头上，限流会把全站算成一个用户，
        // 访问明细里的 IP 也全是代理地址，统计完全失真
        assertThat(WebUtils.getClientIp(request)).isEqualTo("1.2.3.4");
    }

    @Test
    @DisplayName("X-Forwarded-For 为 unknown → 继续往下找")
    void unknownForwardedFor_shouldFallThrough() {
        MockHttpServletRequest request = request();
        request.setRemoteAddr("192.168.1.100");
        // 有些代理会把无法识别的客户端标成 "unknown"
        request.addHeader("X-Forwarded-For", "unknown");
        request.addHeader("X-Real-IP", "1.2.3.4");

        assertThat(WebUtils.getClientIp(request)).isEqualTo("1.2.3.4");
    }

    @Test
    @DisplayName("只有 X-Real-IP → 用它")
    void realIpHeader_shouldBeUsed() {
        MockHttpServletRequest request = request();
        request.setRemoteAddr("10.0.0.9");
        request.addHeader("X-Real-IP", "1.2.3.4");

        assertThat(WebUtils.getClientIp(request)).isEqualTo("1.2.3.4");
    }

    @Test
    @DisplayName("X-Forwarded-For 里带空格 → 去掉首尾空格")
    void forwardedForWithSpaces_shouldBeTrimmed() {
        MockHttpServletRequest request = request();
        request.addHeader("X-Forwarded-For", "  1.2.3.4  , 10.0.0.1");

        // 不去空格的话，限流的 key 会带上空格，和访问明细里的 IP 对不上
        assertThat(WebUtils.getClientIp(request)).isEqualTo("1.2.3.4");
    }

    // ==================== User-Agent / Referer 截断 ====================

    @Test
    @DisplayName("User-Agent 正常 → 原样返回")
    void normalUserAgent_shouldPassThrough() {
        MockHttpServletRequest request = request();
        request.addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");

        assertThat(WebUtils.getUserAgent(request)).isEqualTo("Mozilla/5.0 (Windows NT 10.0; Win64; x64)");
    }

    @Test
    @DisplayName("★ 超长 User-Agent → 截断到 500，不能撑爆 VARCHAR(500) 的列")
    void oversizedUserAgent_shouldBeTruncated() {
        MockHttpServletRequest request = request();
        // 这个头完全由客户端控制，塞多长都行
        request.addHeader("User-Agent", "X".repeat(5000));

        // 不截断的话写库时抛 Data too long，而且是在异步线程里失败，
        // 主流程完全感知不到，只是访问明细莫名其妙少了一条
        assertThat(WebUtils.getUserAgent(request)).hasSize(500);
    }

    @Test
    @DisplayName("超长 Referer → 同样截断")
    void oversizedReferer_shouldBeTruncated() {
        MockHttpServletRequest request = request();
        request.addHeader("Referer", "https://example.com/" + "a".repeat(5000));

        assertThat(WebUtils.getReferer(request)).hasSize(500);
    }

    @Test
    @DisplayName("没有这些头 → 返回 null 而不是抛异常")
    void missingHeaders_shouldReturnNull() {
        MockHttpServletRequest request = request();

        assertThat(WebUtils.getUserAgent(request)).isNull();
        assertThat(WebUtils.getReferer(request)).isNull();
    }
}
