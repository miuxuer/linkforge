package com.miuxuer.linkforge.controller;

import com.miuxuer.linkforge.event.VisitEvent;
import com.miuxuer.linkforge.exception.LinkNotFoundException;
import com.miuxuer.linkforge.service.LinkService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/**
 * 短码跳转入口。
 *
 * <p>路径是根下的 {@code /{shortCode}} 而不是 {@code /api/xxx} —— 短链的全部意义
 * 就是短，多一层前缀等于每个短链都变长。代价是根路径下所有 {@code /xxx} 都会被
 * 这个映射接住（包括 {@code /favicon.ico}），拿不到就当作短码不存在。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class RedirectController {

    private final LinkService linkService;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 302 跳转到原始长链接。
     *
     * <p><b>为什么是 302（临时重定向）而不是 301（永久）</b>：301 会被浏览器和各级
     * 缓存永久记住，用户第二次点同一个短链时请求根本不经过后端 —— 跳转是快了，
     * 但访问量统计、限流、封禁违规短链全部失效。302 每次都回后端，才谈得上统计和控制。
     * 这是短链服务的取舍：用一次网络往返换取可观测性和可控性。
     */
    @GetMapping("/{shortCode}")
    public void redirect(@PathVariable String shortCode, HttpServletResponse response) throws IOException {
        String originalUrl = linkService.getOriginalUrl(shortCode);

        // 查不到、或者库里的地址不是 http(s)（有人绕过创建接口直接写库塞了
        // javascript: 之类的伪协议），一律当作短链不存在，不给任何额外信息
        if (originalUrl == null || !isHttpUrl(originalUrl)) {
            throw new LinkNotFoundException(shortCode);
        }

        // 发事件而不是直接调计数方法：计数要访问 Redis，同步做会给每个跳转
        // 多加一次网络往返。事件由 @Async 监听器处理，本方法发完就走。
        eventPublisher.publishEvent(new VisitEvent(shortCode));

        response.sendRedirect(originalUrl);
    }

    private boolean isHttpUrl(String url) {
        return url.startsWith("http://") || url.startsWith("https://");
    }
}
