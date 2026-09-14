package com.miuxuer.linkforge.event;

/**
 * 访问事件：短链被访问时发布，由监听器异步处理计数。
 *
 * <p><b>为什么不直接在 Controller 里调 Service 计数</b>：
 *
 * <ol>
 *   <li><b>解耦</b>：跳转接口只负责"发一个通知"，不关心谁来消费、怎么消费
 *   <li><b>削峰</b>：{@code @Async} 让计数在独立线程池里跑，主线程发完事件立刻返回 302。
 *       计数要访问 Redis（一次网络往返），同步做的话每个跳转都要多等这一下
 *   <li><b>可演进</b>：以后换成 RabbitMQ 或 Kafka，只需要换监听器的实现，
 *       发布方一行都不用改
 * </ol>
 *
 * <p>用 {@code record} 而不是普通类：事件是"值"，发布出去之后没人会改它，
 * record 天然不可变，也省掉一堆样板代码。
 *
 * @param shortCode 被访问的短码
 */
public record VisitEvent(String shortCode) {
}
