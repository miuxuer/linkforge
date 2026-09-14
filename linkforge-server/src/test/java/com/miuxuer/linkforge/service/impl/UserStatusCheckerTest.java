package com.miuxuer.linkforge.service.impl;

import com.miuxuer.linkforge.constant.RedisKeyConstant;
import com.miuxuer.linkforge.constant.StatusConstant;
import com.miuxuer.linkforge.entity.User;
import com.miuxuer.linkforge.mapper.UserMapper;
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

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("用户状态检查")
class UserStatusCheckerTest {

    private static final Long USER_ID = 1001L;
    private static final String CACHE_KEY = RedisKeyConstant.USER_STATUS + USER_ID;

    @Mock
    private UserMapper userMapper;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private UserStatusChecker checker;

    @BeforeEach
    void setUp() {
        checker = new UserStatusChecker(userMapper);
        ReflectionTestUtils.setField(checker, "stringRedisTemplate", stringRedisTemplate);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    private static User user(int status) {
        User user = new User();
        user.setId(USER_ID);
        user.setStatus(status);
        return user;
    }

    // ==================== 缓存命中 ====================

    @Test
    @DisplayName("缓存里是「启用」→ 直接放行，不查库")
    void cachedEnabled_shouldNotHitDatabase() {
        when(valueOperations.get(CACHE_KEY)).thenReturn("1");

        assertThat(checker.isEnabled(USER_ID)).isTrue();
        verify(userMapper, never()).selectById(any());
    }

    @Test
    @DisplayName("★ 缓存里是「禁用」→ 直接拒绝，不查库")
    void cachedDisabled_shouldNotHitDatabase() {
        when(valueOperations.get(CACHE_KEY)).thenReturn("0");

        // 这条路径就是"禁用立即生效"的关键：管理员改状态时删了缓存，
        // 下一个请求回查数据库拿到"禁用"并写回缓存，之后所有请求都走这里
        assertThat(checker.isEnabled(USER_ID)).isFalse();
        verify(userMapper, never()).selectById(any());
    }

    // ==================== 缓存未命中 ====================

    @Test
    @DisplayName("缓存未命中 → 查库，并把结果写回缓存")
    void cacheMiss_shouldLoadFromDatabaseAndCache() {
        when(valueOperations.get(CACHE_KEY)).thenReturn(null);
        when(userMapper.selectById(USER_ID)).thenReturn(user(StatusConstant.ENABLED));

        assertThat(checker.isEnabled(USER_ID)).isTrue();

        // 用带 TTL 的 set 一步写入，而不是先 set 再 expire ——
        // 两条命令之间进程挂掉会留下一个永不过期的 key，
        // 那个用户的状态会被永久冻结在这一次的结果上
        verify(valueOperations).set(eq(CACHE_KEY), eq("1"), any(Duration.class));
    }

    @Test
    @DisplayName("库里是禁用 → 返回 false 并缓存")
    void disabledInDatabase_shouldCacheDisabled() {
        when(valueOperations.get(CACHE_KEY)).thenReturn(null);
        when(userMapper.selectById(USER_ID)).thenReturn(user(StatusConstant.DISABLED));

        assertThat(checker.isEnabled(USER_ID)).isFalse();
        verify(valueOperations).set(eq(CACHE_KEY), eq("0"), any(Duration.class));
    }

    // ==================== 边界 ====================

    @Test
    @DisplayName("★ 用户不存在（已被删除）→ 按禁用处理")
    void userNotFound_shouldBeTreatedAsDisabled() {
        when(valueOperations.get(CACHE_KEY)).thenReturn(null);
        when(userMapper.selectById(USER_ID)).thenReturn(null);

        // 一个查不到的账号不该能访问接口。返回 true 的话，
        // 被物理删除的用户手上的 token 会一直有效
        assertThat(checker.isEnabled(USER_ID)).isFalse();
    }

    @Test
    @DisplayName("status 为 null 的脏数据 → 按禁用处理")
    void nullStatus_shouldBeTreatedAsDisabled() {
        when(valueOperations.get(CACHE_KEY)).thenReturn(null);
        User dirty = new User();
        dirty.setId(USER_ID);
        when(userMapper.selectById(USER_ID)).thenReturn(dirty);

        assertThat(checker.isEnabled(USER_ID)).isFalse();
    }

    @Test
    @DisplayName("userId 为 null → 直接拒绝，不查库")
    void nullUserId_shouldBeRejected() {
        assertThat(checker.isEnabled(null)).isFalse();
        verify(userMapper, never()).selectById(any());
    }

    // ==================== Redis 不可用 ====================

    @Test
    @DisplayName("★ Redis 不可用 → 退化成每次查库，而不是放开这道防线")
    void withoutRedis_shouldFallBackToDatabase() {
        ReflectionTestUtils.setField(checker, "stringRedisTemplate", null);
        when(userMapper.selectById(USER_ID)).thenReturn(user(StatusConstant.DISABLED));

        // 缓存是性能优化。"Redis 挂了就跳过状态检查"听起来像是"优雅降级"，
        // 实际上是"缓存一挂，所有被禁用的账号立刻全部复活"
        assertThat(checker.isEnabled(USER_ID)).isFalse();
        verify(userMapper).selectById(USER_ID);
    }

    @Test
    @DisplayName("★ Redis 连不上（抛异常）→ 回退查库，而不是让调用方 500")
    void redisThrows_shouldFallBackToDatabase() {
        // 这一条是集成测试逼出来的：单元测试里的 mock 永远不抛异常，
        // 所以"Redis 进程挂了 / 网络断了"这条路径在单元测试里根本覆盖不到。
        //
        // 而 isEnabled 是在拦截器里调的 —— 一旦让异常冒出去，
        // 结果就是"Redis 一挂，所有需要登录的接口全部 500"
        when(valueOperations.get(CACHE_KEY)).thenThrow(new RuntimeException("Connection refused"));
        when(userMapper.selectById(USER_ID)).thenReturn(user(StatusConstant.ENABLED));

        assertThat(checker.isEnabled(USER_ID)).isTrue();
        verify(userMapper).selectById(USER_ID);
    }

    @Test
    @DisplayName("读缓存失败时写入缓存也会失败 → 不影响判断结果")
    void redisThrowsOnBothReadAndWrite_shouldStillWork() {
        when(valueOperations.get(CACHE_KEY)).thenThrow(new RuntimeException("Connection refused"));
        when(userMapper.selectById(USER_ID)).thenReturn(user(StatusConstant.DISABLED));
        org.mockito.Mockito.doThrow(new RuntimeException("Connection refused"))
                .when(valueOperations).set(anyString(), anyString(), any(Duration.class));

        assertThat(checker.isEnabled(USER_ID)).isFalse();
    }

    // ==================== 清缓存 ====================

    @Test
    @DisplayName("evict → 删掉状态缓存")
    void evict_shouldDeleteCacheKey() {
        checker.evict(USER_ID);

        verify(stringRedisTemplate).delete(CACHE_KEY);
    }

    @Test
    @DisplayName("Redis 不可用时 evict 静默返回，不抛异常")
    void evict_withoutRedis_shouldNotThrow() {
        ReflectionTestUtils.setField(checker, "stringRedisTemplate", null);

        // 改状态这个业务动作不能因为缓存清了失败而回滚 ——
        // 数据库已经改了，缓存过期后自然会刷新
        checker.evict(USER_ID);
        verify(stringRedisTemplate, never()).delete(anyString());
    }

    @Test
    @DisplayName("★ evict 时 Redis 抛异常 → 吞掉，不能让「禁用用户」这个操作失败")
    void evict_redisThrows_shouldNotPropagate() {
        when(stringRedisTemplate.delete(CACHE_KEY)).thenThrow(new RuntimeException("Connection refused"));

        // 数据库里状态已经改好了，清缓存失败只是"禁用晚 60 秒生效"，
        // 不该把整个操作报成失败 —— 那样管理员会反复重试，而每次都改成功、都报失败
        checker.evict(USER_ID);
    }
}
