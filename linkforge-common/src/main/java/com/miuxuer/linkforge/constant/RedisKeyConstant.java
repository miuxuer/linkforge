package com.miuxuer.linkforge.constant;

/**
 * Redis key 前缀集中定义。
 *
 * <p><b>为什么要集中放一处</b>：同一个 key 前缀往往被两个地方用 —— 比如访问计数，
 * 写入方是 {@code LinkServiceImpl.incrementVisitCount}，读取方是
 * {@code VisitCountSyncTask}。两边各写一份字符串字面量的话，哪天改前缀只改了一边，
 * 写入和读取就对不上了：计数器照常累加，但同步任务扫不到这些 key，
 * 表现为"访问量永远显示 0"，而且不报任何错，很难查。
 *
 * <p>所有 key 统一带 {@code linkforge:} 前缀做命名空间隔离。
 * Redis 默认只有 16 个库，多个项目共用一个实例时，靠前缀区分比靠库号更清楚
 * （也能避免有人 FLUSHDB 把别人的数据冲掉）。
 */
public final class RedisKeyConstant {

    /** 本项目的命名空间前缀。 */
    private static final String APP = "linkforge:";

    /** 短码 -> 原始链接的缓存。 */
    public static final String LINK_CACHE = APP + "link:cache:";

    /** 重建缓存用的互斥锁。 */
    public static final String LINK_LOCK = APP + "link:lock:";

    /** 短链访问计数，在 Redis 累加，由定时任务同步到 DB。 */
    public static final String LINK_VISITS = APP + "link:visits:";

    /** 基于 IP 的限流计数器。 */
    public static final String RATE_LIMIT = APP + "ratelimit:";

    /** 工具类不允许实例化。 */
    private RedisKeyConstant() {
    }
}
