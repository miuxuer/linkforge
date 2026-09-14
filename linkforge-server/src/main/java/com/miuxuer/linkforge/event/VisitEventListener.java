package com.miuxuer.linkforge.event;

import com.miuxuer.linkforge.service.LinkService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 访问事件监听器：把计数写进 Redis。
 *
 * <p>{@code @Async} 让这个方法跑在独立线程池里，发布事件的线程不等它。
 * 跳转链路的响应时间因此不受计数影响。
 *
 * <p><b>注意 @Async 生效的前提</b>：调用方必须是通过 Spring 容器拿到的代理对象，
 * 同类内部直接 {@code this.method()} 调用不会走代理，@Async 静默失效。
 * 所以在 Controller 里用的是 {@code ApplicationEventPublisher.publishEvent(...)}，
 * 由 Spring 转发到本方法。
 *
 * <p>这里只做 Redis INCR（微秒级），不碰数据库。真正的落库由
 * {@code VisitCountSyncTask} 定时批量完成。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VisitEventListener {

    private final LinkService linkService;

    @Async
    @EventListener
    public void handleVisitEvent(VisitEvent event) {
        try {
            linkService.incrementVisitCount(event.shortCode());
        } catch (Exception e) {
            // 计数失败不能影响跳转，也不能让异常把异步线程池里的线程吃掉。
            // 这里吞掉但必须打日志——否则访问量少了你根本不知道。
            log.error("访问计数失败: shortCode={}", event.shortCode(), e);
        }
    }
}
