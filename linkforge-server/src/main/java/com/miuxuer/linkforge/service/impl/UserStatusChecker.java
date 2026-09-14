package com.miuxuer.linkforge.service.impl;

import com.miuxuer.linkforge.constant.RedisKeyConstant;
import com.miuxuer.linkforge.constant.StatusConstant;
import com.miuxuer.linkforge.entity.User;
import com.miuxuer.linkforge.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 判断用户当前是否处于启用状态。
 *
 * <p><b>解决的问题</b>：JWT 是无状态的 —— token 一旦签发，服务端就没有任何办法
 * 让它提前失效，只能等它自己过期。于是"管理员禁用了某个用户"这件事，
 * 对于该用户手上那张已经签发的 token 完全不起作用：他照样能调所有接口，
 * 直到 token 过期为止。
 *
 * <p>本项目 token 有效期是 7 天，等于禁用操作要 7 天才真正生效 ——
 * 这不是理论问题，是实测出来的：禁用之后重新登录确实被拒了，
 * 但拿着旧 token 访问接口仍然返回 200。
 *
 * <p><b>做法</b>：在拦截器里加一道"用户当前是否启用"的检查。
 * 为了不让它变成每个请求都查一次数据库，结果缓存在 Redis 里 60 秒；
 * 管理员改状态时会主动删掉缓存，所以禁用是<b>立即生效</b>的，
 * 60 秒只是"别的途径改了状态"（比如直接改库）时的兜底刷新周期。
 *
 * <p><b>为什么敢在拦截器里加一次 Redis 读</b>：拦截器只作用于 {@code /api/**}，
 * 那是用户管理短链的低频接口；真正高并发的短码跳转走的是根路径
 * {@code /{shortCode}}，压根不经过拦截器。所以这一次 Redis 读不在关键路径上。
 *
 * <p>Redis 不可用时会退化成直接查库 —— 宁可慢一点，也不能因为缓存挂了
 * 就把"禁用"这道防线整个放开。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserStatusChecker {

    /**
     * 缓存有效期。
     *
     * <p>60 秒是"兜底刷新"周期，不是"生效延迟"—— 正常通过管理端禁用时会立即删缓存。
     * 留这个 TTL 是为了防止有人绕过接口直接改库时缓存永远不刷新。
     */
    private static final Duration CACHE_TTL = Duration.ofSeconds(60);

    /** 缓存里存的值：1=启用 0=禁用。 */
    private static final String ENABLED_FLAG = "1";
    private static final String DISABLED_FLAG = "0";

    private final UserMapper userMapper;

    /** 可选依赖：Redis 不可用时退化成每次查库。 */
    @Autowired(required = false)
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 该用户当前是否可以访问接口。
     *
     * @return 用户存在且状态为启用时返回 true
     */
    public boolean isEnabled(Long userId) {
        if (userId == null) {
            return false;
        }

        String cacheKey = RedisKeyConstant.USER_STATUS + userId;

        Boolean cached = readCache(cacheKey);
        if (cached != null) {
            return cached;
        }

        // 缓存没命中（或 Redis 不可用），回查数据库
        return loadFromDatabase(userId, cacheKey);
    }

    /**
     * 清掉某个用户的状态缓存。
     *
     * <p>管理员改完状态之后必须调用它 —— 不调用的话，禁用要等最多 60 秒才生效，
     * 那 60 秒里被禁用的用户还能继续操作。
     */
    public void evict(Long userId) {
        if (stringRedisTemplate == null || userId == null) {
            return;
        }
        try {
            stringRedisTemplate.delete(RedisKeyConstant.USER_STATUS + userId);
        } catch (Exception e) {
            // 数据库里的状态已经改好了，缓存过期后自然会刷新。
            // 不能因为"清缓存失败"就让"禁用用户"这个操作整个失败
            log.warn("清除用户状态缓存失败（状态本身已更新，缓存会自动过期）: userId={}", userId, e);
        }
    }

    /**
     * 读缓存。
     *
     * @return 缓存命中时返回 true/false；未命中<b>或 Redis 不可用</b>时返回 null
     */
    private Boolean readCache(String cacheKey) {
        if (stringRedisTemplate == null) {
            return null;
        }
        try {
            String cached = stringRedisTemplate.opsForValue().get(cacheKey);
            if (DISABLED_FLAG.equals(cached)) {
                return false;
            }
            if (ENABLED_FLAG.equals(cached)) {
                return true;
            }
            return null;
        } catch (Exception e) {
            // ★ 这里必须吞掉异常，绝不能让它冒出去。
            //
            // isEnabled 是在拦截器里调的 —— 一旦抛异常，结果就是
            // "Redis 一挂，所有需要登录的接口全部 500"，包括登录本身之后的任何操作。
            // 缓存是性能优化，不该成为可用性的单点。
            //
            // 这个问题是集成测试发现的：单元测试里 Redis 是打桩好的 mock，
            // 永远不抛异常，根本覆盖不到这条路径。
            log.warn("读取用户状态缓存失败，回退查库: {}", e.getMessage());
            return null;
        }
    }

    private boolean loadFromDatabase(Long userId, String cacheKey) {
        User user = userMapper.selectById(userId);
        // 用户不存在（已被删除）也按禁用处理 —— 一个查不到的账号不该能访问接口
        boolean enabled = user != null && user.getStatus() != null
                && user.getStatus() == StatusConstant.ENABLED;

        writeCache(cacheKey, enabled);
        return enabled;
    }

    private void writeCache(String cacheKey, boolean enabled) {
        if (stringRedisTemplate == null) {
            return;
        }
        try {
            // 用 set(key, value, ttl) 一步写入，而不是先 set 再 expire：
            // 两条命令之间有窗口，进程恰好在这里挂掉就会留下一个永不过期的 key，
            // 那个用户的状态会被永久冻结在这一次的结果上
            stringRedisTemplate.opsForValue().set(
                    cacheKey, enabled ? ENABLED_FLAG : DISABLED_FLAG, CACHE_TTL);
        } catch (Exception e) {
            // 写不进去只是每次都回查数据库，判断结果本身不受影响
            log.warn("写入用户状态缓存失败（不影响本次判断）: {}", e.getMessage());
        }
    }
}
