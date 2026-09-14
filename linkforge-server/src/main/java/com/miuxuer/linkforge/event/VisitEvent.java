package com.miuxuer.linkforge.event;

/**
 * 访问事件：短链被访问时发布，由监听器异步处理。
 *
 * <p><b>为什么不直接在 Controller 里调 Service</b>：
 *
 * <ol>
 *   <li><b>解耦</b>：跳转接口只负责"发一个通知"，不关心谁来消费、怎么消费
 *   <li><b>削峰</b>：{@code @Async} 让计数和明细写入在独立线程池里跑，
 *       主线程发完事件立刻返回 302。这两件事都要访问外部存储（Redis / MySQL），
 *       同步做的话每个跳转都要多等它们
 *   <li><b>可演进</b>：以后换成 RabbitMQ 或 Kafka，只需要换监听器的实现，
 *       发布方一行都不用改
 * </ol>
 *
 * <p><b>事件里为什么带 IP / User-Agent / Referer</b>：这些信息只能从
 * {@code HttpServletRequest} 上取，而 Service 层不该碰它是本项目的分层铁律。
 * 所以由 Controller 取好、塞进事件里带过来 —— 事件在这里扮演的是"把 Web 层
 * 的信息传递到业务层"的角色。
 *
 * @param shortCode 被访问的短码
 * @param ip        访问者 IP
 * @param userAgent 浏览器信息
 * @param referer   来源页
 */
public record VisitEvent(String shortCode, String ip, String userAgent, String referer) {
}
