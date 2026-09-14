package com.miuxuer.linkforge.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 看板总览。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StatOverviewVO {

    /** 短链总数。 */
    private Long totalLinks;

    /**
     * 累计访问量。
     *
     * <p>取自 {@code t_link.visit_count}，也就是 Redis 计数器定时同步回来的值 ——
     * 每一次访问都会让它 +1，是准确的总量。
     *
     * <p>和下面"今日访问"的来源不同（那个来自 {@code t_visit_log} 明细表），
     * 所以两者可能对不上：明细表在异步队列打满时会丢行，而计数器不会。
     * 这不是 bug，是两套机制各自的取舍 —— 总量要准，明细要能按天/按来源拆开看。
     *
     * <p>另外这里有个最多 5 分钟的滞后：计数在 Redis 里累加，由定时任务批量回写。
     */
    private Long totalVisits;

    /** 今日访问量，来自访问明细表。 */
    private Long todayVisits;
}
