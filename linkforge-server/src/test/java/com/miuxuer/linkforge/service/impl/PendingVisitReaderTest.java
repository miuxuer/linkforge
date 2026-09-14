package com.miuxuer.linkforge.service.impl;

import com.miuxuer.linkforge.constant.RedisKeyConstant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("未同步访问增量读取")
class PendingVisitReaderTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private PendingVisitReader reader;

    @BeforeEach
    void setUp() {
        reader = new PendingVisitReader();
        ReflectionTestUtils.setField(reader, "stringRedisTemplate", stringRedisTemplate);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    private static String key(String code) {
        return RedisKeyConstant.LINK_VISITS + code;
    }

    // ==================== 正常情况 ====================

    @Test
    @DisplayName("有增量 → 按短码返回")
    void shouldReturnIncrements() {
        when(valueOperations.multiGet(any())).thenReturn(List.of("3", "2"));

        Map<String, Long> result = reader.read(List.of("a", "b"));

        assertThat(result).containsEntry("a", 3L).containsEntry("b", 2L);
    }

    @Test
    @DisplayName("★ 用 MGET 一次拿回来，不是循环 GET")
    void shouldUseMultiGet() {
        reader.read(List.of("a", "b", "c"));

        // 一页 10 条短链循环发 10 次请求，列表/看板打开就要多等 10 个网络往返。
        // MGET 是 Redis 专门为这个场景提供的命令
        verify(valueOperations).multiGet(List.of(key("a"), key("b"), key("c")));
        verify(valueOperations, never()).get(anyString());
    }

    @Test
    @DisplayName("短码列表为空 → 不发 Redis 请求")
    void emptyList_shouldNotQueryRedis() {
        assertThat(reader.read(List.of())).isEmpty();
        verify(valueOperations, never()).multiGet(any());
    }

    // ==================== 边界 ====================

    @Test
    @DisplayName("某个 key 不存在（MGET 返回 null）→ 当 0 处理，不抛 NPE")
    void nullValue_shouldBeTreatedAsZero() {
        // 定时任务刚把计数取走、key 已删除的那一瞬间就会是这种情况
        when(valueOperations.multiGet(any())).thenReturn(Arrays.asList("5", null));

        Map<String, Long> result = reader.read(List.of("a", "b"));

        assertThat(result).containsEntry("a", 5L).doesNotContainKey("b");
    }

    @Test
    @DisplayName("★ MGET 返回条数对不上 → 整体放弃，而不是按索引错配")
    void sizeMismatch_shouldGiveUpInsteadOfMisaligning() {
        // 条数对不上时按索引配对会把增量配错短码 —— "A 的 5 次算到 B 头上"，
        // 数字看着正常但其实全错了。宁可放弃（总量偏小，方向是安全的）
        when(valueOperations.multiGet(any())).thenReturn(List.of("5"));

        assertThat(reader.read(List.of("a", "b"))).isEmpty();
    }

    @Test
    @DisplayName("Redis 抛异常 → 返回空结果，不让调用方 500")
    void redisFailure_shouldReturnEmpty() {
        when(valueOperations.multiGet(any())).thenThrow(new RuntimeException("Connection refused"));

        // 数字偏小可以接受，列表/看板打不开不能接受
        assertThat(reader.read(List.of("a"))).isEmpty();
    }

    @Test
    @DisplayName("Redis 没配上 → 返回空结果")
    void withoutRedis_shouldReturnEmpty() {
        ReflectionTestUtils.setField(reader, "stringRedisTemplate", null);

        assertThat(reader.read(List.of("a"))).isEmpty();
    }

    // ==================== 合并 ====================

    @Test
    @DisplayName("合并：数据库值 + 增量")
    void merge_shouldAddIncrement() {
        Map<String, Long> increments = Map.of("a", 7L);

        assertThat(reader.merge(3L, "a", increments)).isEqualTo(10L);
        // 没有增量的短码原样返回
        assertThat(reader.merge(3L, "b", increments)).isEqualTo(3L);
    }

    @Test
    @DisplayName("合并：数据库值为 null → 当 0 处理，不抛 NPE")
    void merge_nullDbValue_shouldTreatAsZero() {
        assertThat(reader.merge(null, "a", Map.of("a", 5L))).isEqualTo(5L);
        assertThat(reader.merge(null, "b", Map.of())).isZero();
    }
}
