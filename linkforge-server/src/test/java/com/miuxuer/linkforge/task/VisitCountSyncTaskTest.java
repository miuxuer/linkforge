package com.miuxuer.linkforge.task;

import com.miuxuer.linkforge.constant.RedisKeyConstant;
import com.miuxuer.linkforge.mapper.LinkMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 访问计数定时同步任务单元测试。
 *
 * <p>SCAN 的游标用 Mockito 假装：让 {@code hasNext()} 依次返回 true/true/false，
 * {@code next()} 返回两个待同步的 key，就模拟出了一次扫出两条的效果。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("访问计数同步任务")
class VisitCountSyncTaskTest {

    private static final String KEY_ABC = RedisKeyConstant.LINK_VISITS + "abc123";
    private static final String KEY_XYZ = RedisKeyConstant.LINK_VISITS + "xyz789";

    @Mock
    private LinkMapper linkMapper;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private Cursor<String> cursor;

    @InjectMocks
    private VisitCountSyncTask syncTask;

    private void injectRedis() {
        ReflectionTestUtils.setField(syncTask, "stringRedisTemplate", stringRedisTemplate);
    }

    @Test
    @DisplayName("扫到 2 个计数器 → 分别回写 DB 并删掉 Redis key")
    void sync_shouldUpdateDbAndDeleteKeys() {
        injectRedis();
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(stringRedisTemplate.scan(any(ScanOptions.class))).thenReturn(cursor);
        when(cursor.hasNext()).thenReturn(true, true, false);
        when(cursor.next()).thenReturn(KEY_ABC, KEY_XYZ);
        when(valueOperations.get(KEY_ABC)).thenReturn("5");
        when(valueOperations.get(KEY_XYZ)).thenReturn("3");

        syncTask.syncVisitCounts();

        // 计数取走后必须删 key，否则下一轮会把同一批数字再加一遍，访问量越滚越大
        verify(stringRedisTemplate).delete(KEY_ABC);
        verify(stringRedisTemplate).delete(KEY_XYZ);
        verify(linkMapper, times(2)).update(any(), any());
    }

    @Test
    @DisplayName("一个 key 都没扫到 → 直接返回，不碰数据库")
    void sync_noKeys_shouldSkip() {
        injectRedis();
        when(stringRedisTemplate.scan(any(ScanOptions.class))).thenReturn(cursor);
        when(cursor.hasNext()).thenReturn(false);

        syncTask.syncVisitCounts();

        verify(linkMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("计数为 0 → 跳过，不产生一条 +0 的无用 UPDATE")
    void sync_zeroCount_shouldSkip() {
        injectRedis();
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(stringRedisTemplate.scan(any(ScanOptions.class))).thenReturn(cursor);
        when(cursor.hasNext()).thenReturn(true, false);
        when(cursor.next()).thenReturn(KEY_ABC);
        when(valueOperations.get(KEY_ABC)).thenReturn("0");

        syncTask.syncVisitCounts();

        verify(linkMapper, never()).update(any(), any());
        verify(stringRedisTemplate, never()).delete(anyString());
    }

    @Test
    @DisplayName("Redis 不可用 → 跳过同步，不抛异常")
    void sync_redisUnavailable_shouldSkip() {
        ReflectionTestUtils.setField(syncTask, "stringRedisTemplate", null);

        syncTask.syncVisitCounts();

        verify(linkMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("计数器被人手工改成非数字 → 丢弃该 key，不影响其它短链同步")
    void sync_malformedCount_shouldDiscardKeyOnly() {
        injectRedis();
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(stringRedisTemplate.scan(any(ScanOptions.class))).thenReturn(cursor);
        when(cursor.hasNext()).thenReturn(true, true, false);
        when(cursor.next()).thenReturn(KEY_ABC, KEY_XYZ);
        when(valueOperations.get(KEY_ABC)).thenReturn("not-a-number");
        when(valueOperations.get(KEY_XYZ)).thenReturn("7");

        syncTask.syncVisitCounts();

        // 坏 key 被丢掉
        verify(stringRedisTemplate).delete(KEY_ABC);
        // 正常那条照常同步 —— 一个坏数据不能让整轮同步中断
        verify(stringRedisTemplate).delete(KEY_XYZ);
        verify(linkMapper, times(1)).update(any(), any());
    }
}
