package com.miuxuer.linkforge.event;

import com.miuxuer.linkforge.service.LinkService;
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
 * <p>注意这里测的是"监听器收到事件后干了什么"，不是测 {@code @Async} 本身
 * —— 手动直接调 {@code handleVisitEvent} 走的是原生对象，没经过 Spring 代理，
 * 所以是同步执行的。{@code @Async} 是否生效属于集成测试的范畴。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("访问事件监听器")
class VisitEventListenerTest {

    @Mock
    private LinkService linkService;

    @InjectMocks
    private VisitEventListener listener;

    @Test
    @DisplayName("收到 VisitEvent → 调一次 incrementVisitCount")
    void shouldCallIncrementVisitCount() {
        listener.handleVisitEvent(new VisitEvent("abc123"));

        verify(linkService).incrementVisitCount("abc123");
    }

    @Test
    @DisplayName("计数抛异常 → 吞掉不外抛，避免把异步线程池的线程带崩")
    void incrementFailure_shouldNotPropagate() {
        doThrow(new RuntimeException("Redis 连接超时"))
                .when(linkService).incrementVisitCount("abc123");

        // 计数失败只是少记一次访问量，不能让异常冒到线程池的异常处理器里
        assertDoesNotThrow(() -> listener.handleVisitEvent(new VisitEvent("abc123")));
    }
}
