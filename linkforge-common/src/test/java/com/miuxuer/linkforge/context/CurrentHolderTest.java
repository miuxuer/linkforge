package com.miuxuer.linkforge.context;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("当前用户上下文")
class CurrentHolderTest {

    @AfterEach
    void tearDown() {
        // 用例之间互相隔离：ThreadLocal 挂在测试线程上，不清会串到下一个用例
        CurrentHolder.remove();
    }

    @Test
    @DisplayName("set 之后能取到，remove 之后取不到")
    void setGetRemove() {
        CurrentHolder.setCurrentId(1001L);
        CurrentHolder.setCurrentRole(1);

        assertEquals(1001L, CurrentHolder.getCurrentId());
        assertEquals(1, CurrentHolder.getCurrentRole());

        CurrentHolder.remove();

        assertNull(CurrentHolder.getCurrentId());
        assertNull(CurrentHolder.getCurrentRole());
    }

    @Test
    @DisplayName("没 set 过就取 → 返回 null，而不是抛异常")
    void getWithoutSet_shouldReturnNull() {
        assertNull(CurrentHolder.getCurrentId());
    }

    @Test
    @DisplayName("值是线程隔离的 —— 主线程 set 的值，别的线程取不到")
    void valueIsThreadIsolated() throws InterruptedException {
        CurrentHolder.setCurrentId(1001L);

        AtomicReference<Object> seenByOtherThread = new AtomicReference<>("没取到过");
        CountDownLatch latch = new CountDownLatch(1);
        Thread other = new Thread(() -> {
            seenByOtherThread.set(CurrentHolder.getCurrentId());
            latch.countDown();
        });
        other.start();
        assertTrue(latch.await(5, TimeUnit.SECONDS));

        // 这正是 ThreadLocal 的设计目的：每个线程一份副本。
        // 也是为什么"忘记 remove"会出问题 —— 线程池复用线程时，
        // 下一个请求用的是同一个线程，也就看到同一份副本。
        assertNull(seenByOtherThread.get());
        assertEquals(1001L, CurrentHolder.getCurrentId());
    }
}
