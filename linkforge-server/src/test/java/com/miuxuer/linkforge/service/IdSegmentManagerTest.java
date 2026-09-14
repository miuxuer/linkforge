package com.miuxuer.linkforge.service;

import com.miuxuer.linkforge.entity.IdSegment;
import com.miuxuer.linkforge.mapper.IdSegmentMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 号段管理器单元测试。
 *
 * <p>Mapper 用 Mockito 打桩，所以不需要 MySQL / Redis，随时可跑。
 * 验证三件事：号段内递增分发、号段耗尽自动取下一批、不同 biz_tag 互不干扰。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("号段管理器")
class IdSegmentManagerTest {

    private static final String BIZ_TAG = IdSegmentManager.BIZ_TAG_LINK;

    @Mock
    private IdSegmentMapper idSegmentMapper;

    @InjectMocks
    private IdSegmentManager idSegmentManager;

    /** 造一个号段记录：max_id 是这段号的上限，step 是长度。 */
    private static IdSegment segment(String bizTag, long maxId, int step) {
        IdSegment segment = new IdSegment();
        segment.setBizTag(bizTag);
        segment.setMaxId(maxId);
        segment.setStep(step);
        return segment;
    }

    @Test
    @DisplayName("首次取号 → 从 DB 取号段 [1, 1000]，然后在内存里递增分发")
    void firstAllocation_shouldFetchSegment() {
        // given: DB 里 max_id=1000、step=1000，取号后这段号是 [1, 1000]
        when(idSegmentMapper.allocateSegment(BIZ_TAG)).thenReturn(1);
        when(idSegmentMapper.selectOne(any())).thenReturn(segment(BIZ_TAG, 1000L, 1000));

        // when
        long id1 = idSegmentManager.getNextId(BIZ_TAG);
        long id2 = idSegmentManager.getNextId(BIZ_TAG);

        // then: 按序分发，且只访问了一次 DB
        assertEquals(1L, id1);
        assertEquals(2L, id2);
        verify(idSegmentMapper, times(1)).allocateSegment(BIZ_TAG);
    }

    @Test
    @DisplayName("号段耗尽 → 自动取下一批 [1001, 2000]")
    void segmentExhausted_shouldFetchNextSegment() {
        // given: 第一批 [1, 3]，step=3 方便测完
        when(idSegmentMapper.allocateSegment(BIZ_TAG)).thenReturn(1);
        when(idSegmentMapper.selectOne(any()))
                .thenReturn(segment(BIZ_TAG, 3L, 3))    // 第一批 [1, 3]
                .thenReturn(segment(BIZ_TAG, 6L, 3));   // 第二批 [4, 6]

        // when: 正好把第一批消费完
        assertEquals(1L, idSegmentManager.getNextId(BIZ_TAG));
        assertEquals(2L, idSegmentManager.getNextId(BIZ_TAG));
        assertEquals(3L, idSegmentManager.getNextId(BIZ_TAG));
        // 第 4 个号会触发换号段
        long id4 = idSegmentManager.getNextId(BIZ_TAG);

        // then: 拿到的是第二批的第一个号，且没有跳号
        assertEquals(4L, id4);
        verify(idSegmentMapper, times(2)).allocateSegment(BIZ_TAG);
    }

    @Test
    @DisplayName("号段记录不存在（UPDATE 影响 0 行）→ 抛异常而不是发脏号")
    void allocationFailed_shouldThrowException() {
        when(idSegmentMapper.allocateSegment(BIZ_TAG)).thenReturn(0);

        assertThrows(IllegalStateException.class, () -> idSegmentManager.getNextId(BIZ_TAG));
    }

    @Test
    @DisplayName("不同 biz_tag 各取各的号，互不干扰")
    void differentBizTags_shouldNotInterfere() {
        when(idSegmentMapper.allocateSegment(IdSegmentManager.BIZ_TAG_USER)).thenReturn(1);
        when(idSegmentMapper.allocateSegment(BIZ_TAG)).thenReturn(1);
        when(idSegmentMapper.selectOne(any()))
                .thenReturn(segment(IdSegmentManager.BIZ_TAG_USER, 100L, 100))
                .thenReturn(segment(BIZ_TAG, 5L, 5));

        // 两边都是从 1 开始，因为各自的号段是独立的
        assertEquals(1L, idSegmentManager.getNextId(IdSegmentManager.BIZ_TAG_USER));
        assertEquals(1L, idSegmentManager.getNextId(BIZ_TAG));
        assertEquals(2L, idSegmentManager.getNextId(IdSegmentManager.BIZ_TAG_USER));
        assertEquals(2L, idSegmentManager.getNextId(BIZ_TAG));
    }

    @Test
    @DisplayName("4 线程并发取 2000 个号，不重号")
    void concurrentGetNextId_shouldNotDuplicate() throws InterruptedException {
        // given: 一段够大的号，保证测试期间只换一次号段
        when(idSegmentMapper.allocateSegment(BIZ_TAG)).thenReturn(1);
        when(idSegmentMapper.selectOne(any())).thenReturn(segment(BIZ_TAG, 100_000L, 100_000));

        int threadCount = 4;
        int perThread = 500;
        Set<Long> ids = ConcurrentHashMap.newKeySet();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            new Thread(() -> {
                try {
                    start.await();  // 让线程尽量同时起跑，把竞争窗口拉开
                    for (int j = 0; j < perThread; j++) {
                        ids.add(idSegmentManager.getNextId(BIZ_TAG));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }).start();
        }

        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS), "取号超时，可能有线程卡死");

        // 唯一性是这个组件唯一不能出错的性质：id 重了就是数据覆盖
        assertEquals(threadCount * perThread, ids.size(), "并发取号出现了重复 id");
        // 而且换号段只在起跑那一下发生，之后全是内存分发
        verify(idSegmentMapper, times(1)).allocateSegment(BIZ_TAG);
    }
}
