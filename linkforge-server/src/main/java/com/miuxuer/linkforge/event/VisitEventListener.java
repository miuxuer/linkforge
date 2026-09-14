package com.miuxuer.linkforge.event;

import com.miuxuer.linkforge.service.LinkService;
import com.miuxuer.linkforge.service.VisitLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 访问事件监听器：写 Redis 计数 + 落访问明细。
 *
 * <p>两件事都跑在 {@code AsyncConfig} 配的线程池里，跳转的 302 响应不等它们。
 *
 * <p><b>注意 {@code @Async} 生效的前提</b>：调用方必须是通过 Spring 容器拿到的
 * 代理对象。同类内部直接 {@code this.method()} 调用不走代理，{@code @Async} 静默失效。
 * 所以 Controller 用的是 {@code ApplicationEventPublisher.publishEvent(...)}，
 * 由 Spring 转发到本方法 —— 这样还顺带获得了一个线程池的缓冲。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VisitEventListener {

    private final LinkService linkService;
    private final VisitLogService visitLogService;

    @Async
    @EventListener
    public void handleVisitEvent(VisitEvent event) {
        // 两个 try 分开写，而不是包一个大的：它们访问的是两套存储（Redis 和 MySQL），
        // 一个挂了不该连累另一个。计数失败了明细照记，反之亦然。
        incrementCount(event);
        recordDetail(event);
    }

    private void incrementCount(VisitEvent event) {
        try {
            linkService.incrementVisitCount(event.shortCode());
        } catch (Exception e) {
            // 计数失败会让看板的总访问量偏小，必须留下痕迹；
            // 但不能外抛 —— 异步线程抛异常只会进线程池的异常处理器，
            // 除了刷日志没有任何作用
            log.error("访问计数失败: shortCode={}", event.shortCode(), e);
        }
    }

    private void recordDetail(VisitEvent event) {
        try {
            visitLogService.record(event);
        } catch (Exception e) {
            // 明细写不进去（比如某条 User-Agent 超长触发了 Data too long），
            // 同样不该影响主流程。丢的是一条明细，总量还有 Redis 计数兜着
            log.error("访问明细落库失败: shortCode={}", event.shortCode(), e);
        }
    }
}
