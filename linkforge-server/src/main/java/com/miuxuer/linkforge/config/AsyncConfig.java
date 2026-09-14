package com.miuxuer.linkforge.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 异步线程池配置。
 *
 * <p>给 {@code @Async} 用（目前唯一的消费者是访问事件的监听器：
 * 写 Redis 计数 + 落访问明细）。
 *
 * <p><b>为什么必须自己配，不能用默认的</b>：
 *
 * <ul>
 *   <li>Spring Boot 默认给 {@code @Async} 的是一个 core=8、
 *       <b>max=Integer.MAX_VALUE、queue=Integer.MAX_VALUE</b> 的线程池。
 *       队列无界意味着 max 永远不会生效，实际就是 8 个线程配一个无界队列 ——
 *       数据库一旦变慢，任务就在队列里无限堆积，最后 OOM
 *   <li>队列有界之后就必须回答"满了怎么办"。默认的 {@code AbortPolicy} 会抛异常，
 *       而这个异常是在 {@code publishEvent()} 提交任务时抛的，会一路冒到
 *       Controller —— 结果是"日志写不过来"变成了"跳转接口 500"，
 *       一个纯统计功能把核心功能搞挂了
 * </ul>
 *
 * <p>所以这里配了有界队列 + 一个只记日志不抛异常的拒绝策略。
 * 丢的是访问明细（统计数据），而 Redis 里的计数还在，总量不会丢 ——
 * 这个取舍比"跳转失败"好得多。
 */
@Slf4j
@Configuration
public class AsyncConfig {

    /** 核心线程数。异步任务都是 I/O（写 Redis、写 MySQL），不需要按 CPU 核数配。 */
    private static final int CORE_POOL_SIZE = 4;

    /** 最大线程数。 */
    private static final int MAX_POOL_SIZE = 8;

    /**
     * 队列容量。
     *
     * <p>按"能扛多久的抖动"估算：8 个线程 + 500 的队列，
     * 假设单条任务 5 毫秒，攒满需要约 300 毫秒 —— 足够覆盖数据库的短暂抖动。
     * 再大就是在拿内存赌"它马上会好"。
     */
    private static final int QUEUE_CAPACITY = 500;

    /**
     * 容器关闭时等待未完成的任务。
     *
     * <p>不设的话，重启时队列里还没落库的访问明细会直接丢掉。
     * 宽限 10 秒足够了 —— 超过 10 秒说明下游是真的挂了，再等也没用。
     */
    private static final int AWAIT_TERMINATION_SECONDS = 10;

    @Bean("taskExecutor")
    public ThreadPoolTaskExecutor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(CORE_POOL_SIZE);
        executor.setMaxPoolSize(MAX_POOL_SIZE);
        executor.setQueueCapacity(QUEUE_CAPACITY);
        // 线程名带前缀：出问题时 jstack 一眼就能看出哪些线程是我们的异步任务
        executor.setThreadNamePrefix("linkforge-async-");
        executor.setRejectedExecutionHandler(new LoggingDiscardPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(AWAIT_TERMINATION_SECONDS);
        executor.initialize();
        return executor;
    }

    /**
     * 队列满时丢弃任务并记录日志。
     *
     * <p>三种常见策略都不合适：{@code AbortPolicy} 会抛异常把调用方搞挂；
     * {@code CallerRunsPolicy} 会让 Tomcat 的请求线程去跑日志任务，
     * 等于把压力转移到了最不该阻塞的地方；{@code DiscardPolicy} 丢得悄无声息，
     * 出了问题没人知道。所以自己写一个"丢 + 记日志"的。
     */
    @Slf4j
    static class LoggingDiscardPolicy implements RejectedExecutionHandler {

        /** 累计丢弃数。 */
        private final AtomicLong discardedCount = new AtomicLong();

        /** 日志采样间隔：每丢这么多条才打一行。 */
        private static final long LOG_SAMPLE_INTERVAL = 1000L;

        @Override
        public void rejectedExecution(Runnable task, ThreadPoolExecutor executor) {
            long total = discardedCount.incrementAndGet();

            // 队列满的时候正是系统压力最大的时候，每条都打日志会让日志本身成为瓶颈。
            // 按固定间隔采样，既知道"在丢"，又不会把日志刷爆。
            if (total % LOG_SAMPLE_INTERVAL == 1) {
                log.warn("异步任务队列已满，累计丢弃 {} 个任务（访问明细可能缺失，"
                                + "但 Redis 计数不受影响）; 当前排队={}, 活跃线程={}",
                        total, executor.getQueue().size(), executor.getActiveCount());
            }
        }
    }
}
