package com.miuxuer.linkforge.event;

import com.miuxuer.linkforge.service.LinkService;
import com.miuxuer.linkforge.service.VisitLogService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * 访问事件监听器单元测试。
 *
 * <p>测的是"监听器收到事件后干了什么"，不是测 {@code @Async} 本身 ——
 * 手动直接调 {@code handleVisitEvent} 走的是原生对象，没经过 Spring 代理，
 * 所以是同步执行的。{@code @Async} 是否生效属于集成测试的范畴。
 *
 * <p>重点在失败隔离：写 Redis 和写 MySQL 是两套独立存储，
 * 一套挂了不能连累另一套，也不能把异常抛到线程池外面去。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("访问事件监听器")
class VisitEventListenerTest {

    private static final VisitEvent EVENT =
            new VisitEvent("abc123", "192.168.1.1", "Mozilla/5.0", "https://www.google.com");

    @Mock
    private LinkService linkService;

    @Mock
    private VisitLogService visitLogService;

    @InjectMocks
    private VisitEventListener listener;

    @Test
    @DisplayName("收到事件 → 计数和落明细各调一次")
    void shouldDoBothJobs() {
        listener.handleVisitEvent(EVENT);

        verify(linkService).incrementVisitCount("abc123");
        verify(visitLogService).record(EVENT);
    }

    @Test
    @DisplayName("计数抛异常 → 吞掉不外抛，且明细照常落库")
    void countFailure_shouldNotBlockDetailRecording() {
        doThrow(new RuntimeException("Redis 连接超时"))
                .when(linkService).incrementVisitCount("abc123");

        assertDoesNotThrow(() -> listener.handleVisitEvent(EVENT));

        // 两个 try 分开写就是为了这个：Redis 挂了不该连明细一起丢。
        // 包在一个 try 里的话，第一句抛异常，第二句根本不会执行
        verify(visitLogService).record(EVENT);
    }

    @Test
    @DisplayName("落明细抛异常 → 吞掉不外抛，计数已经完成了")
    void detailFailure_shouldNotThrow() {
        doThrow(new RuntimeException("Data too long for column 'user_agent'"))
                .when(visitLogService).record(EVENT);

        assertDoesNotThrow(() -> listener.handleVisitEvent(EVENT));

        verify(linkService).incrementVisitCount("abc123");
    }

    @Test
    @DisplayName("两边都挂 → 也不外抛，异常不该冒到线程池外面")
    void bothFailures_shouldStillNotThrow() {
        doThrow(new RuntimeException("Redis 挂了")).when(linkService).incrementVisitCount("abc123");
        doThrow(new RuntimeException("MySQL 挂了")).when(visitLogService).record(EVENT);

        // 异步线程里抛出去只会进线程池的异常处理器，除了刷日志没有任何作用；
        // 而且如果是 CallerRuns 之类的策略，还可能反过来影响调用方
        assertDoesNotThrow(() -> listener.handleVisitEvent(EVENT));
    }
}
