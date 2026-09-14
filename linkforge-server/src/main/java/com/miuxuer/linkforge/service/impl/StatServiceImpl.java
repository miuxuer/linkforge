package com.miuxuer.linkforge.service.impl;

import com.miuxuer.linkforge.context.CurrentHolder;
import com.miuxuer.linkforge.entity.Link;
import com.miuxuer.linkforge.mapper.LinkMapper;
import com.miuxuer.linkforge.mapper.VisitLogMapper;
import com.miuxuer.linkforge.service.StatService;
import com.miuxuer.linkforge.vo.LinkTopVO;
import com.miuxuer.linkforge.vo.StatOverviewVO;
import com.miuxuer.linkforge.vo.VisitTrendVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class StatServiceImpl implements StatService {

    private final LinkMapper linkMapper;
    private final VisitLogMapper visitLogMapper;

    /** 读"还没同步到数据库"的访问增量，让看板数字不滞后。 */
    private final PendingVisitReader pendingVisitReader;


    @Override
    public StatOverviewVO overview() {
        Long userId = CurrentHolder.requireCurrentId();

        return StatOverviewVO.builder()
                .totalLinks(linkMapper.countByUser(userId))
                .totalVisits(totalVisits(userId))
                // 从今天零点算起，而不是"最近 24 小时" —— 用户看"今日访问"
                // 期待的是自然日，不是滚动窗口。用 >= 而不是 DATE(visit_time) = CURDATE()
                // 是为了让 visit_time 上的索引还能用上
                .todayVisits(visitLogMapper.countVisitsSince(userId, LocalDate.now().atStartOfDay()))
                .build();
    }

    /**
     * 累计访问量 = 已同步到数据库的 + 还压在 Redis 里没同步的。
     *
     * <p><b>为什么不能只读数据库</b>：{@code t_link.visit_count} 是定时任务
     * 每 5 分钟从 Redis 批量回写的。只读它的话，用户访问完自己的短链立刻打开看板，
     * 会看到总访问量是 0 —— 数据没错，但在用户眼里就是"这功能坏了"。
     *
     * <p>Redis 里那些 {@code linkforge:link:visits:*} 存的正是"自上次同步以来的增量"
     * （定时任务取走之后会删掉 key），所以两边相加就是精确值，而且没有滞后。
     *
     * <p>用 {@code multiGet}（MGET）一次拿回来，不是循环 GET —— 一百条短链
     * 循环发一百次请求，看板打开就要多等一百个网络往返。
     */
    private long totalVisits(Long userId) {
        long syncedTotal = linkMapper.sumVisitCountByUser(userId);
        List<String> shortCodes = linkMapper.selectShortCodesByUser(userId);

        // Redis 挂了 pendingVisits 会返回空 Map，于是只报已同步的部分。
        // 宁可数字偏小，也不要整个看板打不开
        long pending = pendingVisitReader.read(shortCodes).values().stream()
                .mapToLong(Long::longValue)
                .sum();
        return syncedTotal + pending;
    }

    @Override
    public List<VisitTrendVO> trend(int days) {
        Long userId = CurrentHolder.requireCurrentId();

        LocalDate today = LocalDate.now();
        // days=7 表示"含今天在内的最近 7 天"，所以起点要减 6 天而不是 7 天。
        // 减错的话会变成 8 天的数据，而且多出来的那天在图上看着毫无异常
        LocalDate start = today.minusDays(days - 1L);

        Map<LocalDate, Long> counted = visitLogMapper
                .selectDailyTrend(userId, start.atStartOfDay())
                .stream()
                .collect(Collectors.toMap(VisitTrendVO::getVisitDate, VisitTrendVO::getVisitCount));

        // 补零：SQL 的 GROUP BY 只会返回"有访问记录"的日期。
        // 缺的那几天直接丢给前端画折线图的话，x 轴会缺刻度 ——
        // 图看起来是连续的、其实把 3 天的数据挤在了一起，是最难发现的那类错误
        List<VisitTrendVO> trend = new ArrayList<>(days);
        for (int i = 0; i < days; i++) {
            LocalDate date = start.plusDays(i);
            trend.add(new VisitTrendVO(date, counted.getOrDefault(date, 0L)));
        }
        return trend;
    }

    @Override
    public List<LinkTopVO> top(int limit) {
        Long userId = CurrentHolder.requireCurrentId();

        List<Link> links = linkMapper.selectAllByUser(userId);
        Map<String, Long> pending = pendingVisitReader.read(links.stream().map(Link::getShortCode).toList());

        return links.stream()
                // 补上 Redis 里还没同步的增量，否则用户刚访问完看到的全是 0
                .map(link -> LinkTopVO.builder()
                        .id(link.getId())
                        .shortCode(link.getShortCode())
                        .title(link.getTitle())
                        .visitCount(link.getVisitCount() + pending.getOrDefault(link.getShortCode(), 0L))
                        .build())
                // 必须补完增量再排序。在 SQL 里按旧值排好序、之后再加增量的话，
                // 加完列表就乱了 —— 用户会看到"第 1 条 3 次、第 3 条 10 次"这种排行
                .sorted(Comparator.comparingLong(LinkTopVO::getVisitCount).reversed()
                        // 访问量相同时按 id 排，让结果稳定可复现；
                        // 不加这一层的话，同样数据的两次请求可能返回不同顺序
                        .thenComparing(LinkTopVO::getId))
                .limit(limit)
                .toList();
    }

}
