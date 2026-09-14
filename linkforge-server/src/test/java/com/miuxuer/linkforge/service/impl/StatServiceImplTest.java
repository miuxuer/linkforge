package com.miuxuer.linkforge.service.impl;

import com.miuxuer.linkforge.constant.RedisKeyConstant;
import com.miuxuer.linkforge.context.CurrentHolder;
import com.miuxuer.linkforge.entity.Link;
import com.miuxuer.linkforge.exception.BusinessException;
import com.miuxuer.linkforge.mapper.LinkMapper;
import com.miuxuer.linkforge.mapper.VisitLogMapper;
import com.miuxuer.linkforge.result.ResultCode;
import com.miuxuer.linkforge.vo.LinkTopVO;
import com.miuxuer.linkforge.vo.StatOverviewVO;
import com.miuxuer.linkforge.vo.VisitTrendVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("看板统计")
class StatServiceImplTest {

    private static final Long USER_ID = 1001L;

    @Mock
    private LinkMapper linkMapper;

    @Mock
    private VisitLogMapper visitLogMapper;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private StatServiceImpl statService;

    @BeforeEach
    void setUp() {
        statService = new StatServiceImpl(linkMapper, visitLogMapper);
        ReflectionTestUtils.setField(statService, "stringRedisTemplate", stringRedisTemplate);
        CurrentHolder.setCurrentId(USER_ID);
        // 默认没有"待同步增量"，个别用例再覆盖
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(linkMapper.selectShortCodesByUser(anyLong())).thenReturn(List.of());
    }

    @AfterEach
    void tearDown() {
        CurrentHolder.remove();
    }

    // ==================== 总览 ====================

    @Test
    @DisplayName("总览 → 三个数据分别来自两次短链表查询和一次明细表查询")
    void overview_shouldAggregateThreeNumbers() {
        when(linkMapper.countByUser(USER_ID)).thenReturn(8L);
        when(linkMapper.sumVisitCountByUser(USER_ID)).thenReturn(1234L);
        when(visitLogMapper.countVisitsSince(anyLong(), any())).thenReturn(56L);

        StatOverviewVO overview = statService.overview();

        assertThat(overview.getTotalLinks()).isEqualTo(8L);
        assertThat(overview.getTotalVisits()).isEqualTo(1234L);
        assertThat(overview.getTodayVisits()).isEqualTo(56L);
    }

    @Test
    @DisplayName("★ 今日访问的时间起点是今天零点，不是「最近 24 小时」")
    void overview_todayShouldStartAtMidnight() {
        when(visitLogMapper.countVisitsSince(anyLong(), any())).thenReturn(0L);

        statService.overview();

        ArgumentCaptor<LocalDateTime> captor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(visitLogMapper).countVisitsSince(anyLong(), captor.capture());

        // 用户看"今日访问"期待的是自然日。用"最近 24 小时"的话，
        // 早上 9 点看到的数字里混着昨天下午的访问，和用户的直觉对不上。
        // 而且 SQL 里必须用 >= 而不是 DATE(visit_time) = CURDATE() ——
        // 后者对列做函数运算，visit_time 上的索引就失效了
        assertThat(captor.getValue()).isEqualTo(LocalDate.now().atStartOfDay());
    }

    @Test
    @DisplayName("总览全部按当前登录用户过滤")
    void overview_shouldScopeToCurrentUser() {
        statService.overview();

        verify(linkMapper).countByUser(USER_ID);
        verify(linkMapper).sumVisitCountByUser(USER_ID);
        verify(linkMapper).selectShortCodesByUser(USER_ID);
        verify(visitLogMapper).countVisitsSince(eq(USER_ID), any());
    }

    // ==================== 未同步增量 ====================

    @Test
    @DisplayName("★ 总访问量 = 数据库已同步的 + Redis 里还没同步的")
    void overview_shouldIncludeUnsyncedRedisDelta() {
        when(linkMapper.sumVisitCountByUser(USER_ID)).thenReturn(100L);
        when(linkMapper.selectShortCodesByUser(USER_ID)).thenReturn(List.of("a", "b"));
        // 这两条短链各自还有 3 次和 2 次访问压在 Redis 里没同步
        when(valueOperations.multiGet(any())).thenReturn(List.of("3", "2"));

        StatOverviewVO overview = statService.overview();

        // 只读数据库的话这里是 100 —— 用户刚访问完自己的短链就打开看板，
        // 会看到数字没变，以为统计坏了。实际上数据没错，只是还没到同步周期
        assertThat(overview.getTotalVisits()).isEqualTo(105L);
    }

    @Test
    @DisplayName("Redis 里有 key 但值已过期/被删 → 当 0 处理，不抛 NPE")
    void overview_nullRedisValue_shouldBeTreatedAsZero() {
        when(linkMapper.sumVisitCountByUser(USER_ID)).thenReturn(50L);
        when(linkMapper.selectShortCodesByUser(USER_ID)).thenReturn(List.of("a", "b"));
        // multiGet 对不存在的 key 返回的列表里会有 null
        when(valueOperations.multiGet(any())).thenReturn(Arrays.asList("5", null));

        assertThat(statService.overview().getTotalVisits()).isEqualTo(55L);
    }

    @Test
    @DisplayName("用 MGET 一次拿回来，不是循环 GET")
    void overview_shouldUseMultiGet() {
        when(linkMapper.selectShortCodesByUser(USER_ID)).thenReturn(List.of("a", "b", "c"));

        statService.overview();

        // 循环 GET 的话，一百条短链就要发一百次请求，看板打开多等一百个网络往返。
        // MGET 一次就够，这是 Redis 专门为这场景提供的命令
        verify(valueOperations).multiGet(List.of(
                RedisKeyConstant.LINK_VISITS + "a",
                RedisKeyConstant.LINK_VISITS + "b",
                RedisKeyConstant.LINK_VISITS + "c"));
        verify(valueOperations, never()).get(anyString());
    }

    @Test
    @DisplayName("Redis 不可用 → 只报已同步的部分，不让整个看板打不开")
    void overview_withoutRedis_shouldDegrade() {
        ReflectionTestUtils.setField(statService, "stringRedisTemplate", null);
        when(linkMapper.sumVisitCountByUser(USER_ID)).thenReturn(77L);

        // 数字偏小是可以接受的，整个看板 500 不能接受
        assertThat(statService.overview().getTotalVisits()).isEqualTo(77L);
    }

    // ==================== 趋势 ====================

    @Test
    @DisplayName("★ 趋势按天补零：没有访问的日期也要有 count=0 的记录")
    void trend_shouldFillMissingDaysWithZero() {
        LocalDate today = LocalDate.now();
        // 数据库只返回了"有访问记录"的两天
        when(visitLogMapper.selectDailyTrend(anyLong(), any())).thenReturn(List.of(
                new VisitTrendVO(today.minusDays(2), 5L),
                new VisitTrendVO(today, 3L)));

        List<VisitTrendVO> trend = statService.trend(7);

        // 直接把这 2 条丢给前端画折线图的话，x 轴会缺 5 个刻度 ——
        // 图看起来是连续的，其实把 3 天的数据挤在了一起，最难发现的那类错误
        assertThat(trend).hasSize(7);
        assertThat(trend).extracting(VisitTrendVO::getVisitDate)
                .containsExactly(
                        today.minusDays(6), today.minusDays(5), today.minusDays(4),
                        today.minusDays(3), today.minusDays(2), today.minusDays(1), today);
        assertThat(trend).extracting(VisitTrendVO::getVisitCount)
                .containsExactly(0L, 0L, 0L, 0L, 5L, 0L, 3L);
    }

    @Test
    @DisplayName("趋势的起点是「今天往前 days-1 天」，正好 days 个点")
    void trend_startDateShouldBeInclusive() {
        when(visitLogMapper.selectDailyTrend(anyLong(), any())).thenReturn(List.of());

        statService.trend(7);

        ArgumentCaptor<LocalDateTime> captor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(visitLogMapper).selectDailyTrend(anyLong(), captor.capture());

        // days=7 表示"含今天在内的 7 天"。写成 minusDays(7) 的话会多出一天，
        // 而且多出来的那天在图上看着毫无异常
        assertThat(captor.getValue()).isEqualTo(LocalDate.now().minusDays(6).atStartOfDay());
    }

    @Test
    @DisplayName("趋势某天完全没有数据 → 全部补零，不返回空列表")
    void trend_noDataAtAll_shouldReturnAllZeros() {
        when(visitLogMapper.selectDailyTrend(anyLong(), any())).thenReturn(List.of());

        List<VisitTrendVO> trend = statService.trend(3);

        assertThat(trend).hasSize(3);
        assertThat(trend).allSatisfy(point -> assertThat(point.getVisitCount()).isZero());
    }

    // ==================== Top N ====================

    /** 造一条带访问量的短链。 */
    private static Link linkOf(Long id, String shortCode, Long visitCount) {
        Link link = new Link();
        link.setId(id);
        link.setShortCode(shortCode);
        link.setTitle("标题-" + shortCode);
        link.setVisitCount(visitCount);
        link.setUserId(USER_ID);
        return link;
    }

    @Test
    @DisplayName("Top N → 实体转成 VO，只带该给的字段")
    void top_shouldMapToVo() {
        when(linkMapper.selectAllByUser(USER_ID)).thenReturn(List.of(linkOf(1L, "abc", 99L)));

        List<LinkTopVO> top = statService.top(5);

        assertThat(top).hasSize(1);
        assertThat(top.get(0).getShortCode()).isEqualTo("abc");
        assertThat(top.get(0).getTitle()).isEqualTo("标题-abc");
        assertThat(top.get(0).getVisitCount()).isEqualTo(99L);
    }

    @Test
    @DisplayName("★ Top N 要在补完 Redis 增量之后才排序")
    void top_shouldSortAfterApplyingPendingDelta() {
        // a：库里 10 次、没有待同步；b：库里 0 次、但有 20 次压在 Redis 里
        when(linkMapper.selectAllByUser(USER_ID))
                .thenReturn(List.of(linkOf(1L, "a", 10L), linkOf(2L, "b", 0L)));
        when(valueOperations.multiGet(any())).thenReturn(List.of("0", "20"));

        List<LinkTopVO> top = statService.top(10);

        // 在 SQL 里按旧值排好序、之后每条再补增量的话，补完列表就乱了 ——
        // 用户会看到"第 1 条 10 次、第 2 条 20 次"这种明显没排序的排行。
        // 必须先补增量、再排序
        assertThat(top).extracting(LinkTopVO::getShortCode).containsExactly("b", "a");
        assertThat(top).extracting(LinkTopVO::getVisitCount).containsExactly(20L, 10L);
    }

    @Test
    @DisplayName("Top N 的条数上限生效，取的是访问量最高的几条")
    void top_shouldRespectLimit() {
        when(linkMapper.selectAllByUser(USER_ID)).thenReturn(List.of(
                linkOf(1L, "c1", 1L), linkOf(2L, "c2", 2L), linkOf(3L, "c3", 3L),
                linkOf(4L, "c4", 4L), linkOf(5L, "c5", 5L)));

        List<LinkTopVO> top = statService.top(3);

        assertThat(top).hasSize(3);
        assertThat(top).extracting(LinkTopVO::getShortCode).containsExactly("c5", "c4", "c3");
    }

    @Test
    @DisplayName("访问量相同时按 id 排序，保证两次请求结果一致")
    void top_tiesShouldBeStable() {
        when(linkMapper.selectAllByUser(USER_ID)).thenReturn(List.of(
                linkOf(9L, "later", 5L), linkOf(3L, "earlier", 5L)));

        List<LinkTopVO> top = statService.top(10);

        // 不加这一层排序的话，MySQL 返回的顺序不保证，同样的数据两次请求可能不一样，
        // 用户会看到列表莫名其妙地跳动
        assertThat(top).extracting(LinkTopVO::getShortCode).containsExactly("earlier", "later");
    }

    // ==================== 未登录 ====================

    @Test
    @DisplayName("★ 未登录 → 401，而且一次数据库都不查")
    void notLoggedIn_shouldRejectWithoutQuerying() {
        CurrentHolder.remove();

        assertThrows(BusinessException.class, () -> statService.overview());
        assertThrows(BusinessException.class, () -> statService.trend(7));
        assertThrows(BusinessException.class, () -> statService.top(10));

        // 关键在于"不查库"。如果先查了再判断身份，就会出现
        // "userId 为 null 查出了全站数据"这种事故 —— 哪怕最后返回了 401，
        // 数据也已经从数据库里读出来了，一旦哪条日志或缓存把它带出去就是泄露
        verify(linkMapper, never()).countByUser(any());
        verify(linkMapper, never()).sumVisitCountByUser(any());
        verify(linkMapper, never()).selectShortCodesByUser(any());
        verify(linkMapper, never()).selectAllByUser(any());
        verify(visitLogMapper, never()).countVisitsSince(any(), any());
        verify(visitLogMapper, never()).selectDailyTrend(any(), any());
    }

    @Test
    @DisplayName("未登录时抛的是 401，不是 500")
    void notLoggedIn_shouldBeUnauthorized() {
        CurrentHolder.remove();

        BusinessException e = assertThrows(BusinessException.class, () -> statService.overview());

        assertThat(e.getHttpStatus()).isEqualTo(ResultCode.UNAUTHORIZED.getHttpStatus());
    }
}
