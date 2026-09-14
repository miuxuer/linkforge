package com.miuxuer.linkforge.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.google.common.hash.BloomFilter;
import com.miuxuer.linkforge.constant.RedisKeyConstant;
import com.miuxuer.linkforge.entity.Link;
import com.miuxuer.linkforge.mapper.LinkMapper;
import com.miuxuer.linkforge.service.IdSegmentManager;
import com.miuxuer.linkforge.service.LinkService;
import com.miuxuer.linkforge.utils.Base62;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 短链服务实现 —— 四层缓存防护的跳转链路。
 *
 * <p><b>跳转请求依次过四层</b>：
 *
 * <ol>
 *   <li><b>布隆过滤器</b>挡缓存穿透 —— 说"不存在"就直接拒，连 Redis 都不碰
 *   <li><b>Redis 缓存</b>挡热点 —— 命中就返回，这是 99% 请求走的路
 *   <li><b>互斥锁</b>挡缓存击穿 —— 缓存失效瞬间只放一个线程去查库，其它线程等它回填
 *   <li><b>数据库</b>最终兜底
 * </ol>
 *
 * <p>顺序不能换：布隆在最前面是因为它最便宜（纯内存位运算，无网络往返）；
 * 互斥锁必须包住查库那一段，否则拦不住击穿。
 *
 * <p><b>Redis 和布隆过滤器都是可选依赖</b>（{@code @Autowired(required = false)}）：
 * Redis 挂了就自动降级成纯 DB 模式，布隆没配上就跳过第一层。缓存是性能优化，
 * 不该变成可用性的单点 —— 降级后慢，但服务还能用。
 */
@Slf4j
@Service
public class LinkServiceImpl implements LinkService {

    /** 缓存基础过期时间（分钟）。 */
    private static final int CACHE_TTL_MINUTES = 30;

    /**
     * 过期时间的随机抖动幅度（分钟）。实际 TTL 落在 [25, 35] 分钟之间。
     *
     * <p>这是防缓存雪崩最简单有效的一招：如果所有 key 都写死 30 分钟，
     * 一次批量导入或一次重启后的回填会让大批 key 在同一秒集体过期，
     * 流量瞬间全砸到 DB 上。给每个 key 加一点独立随机，过期时刻就摊开了。
     */
    private static final int CACHE_TTL_JITTER_MINUTES = 5;

    /** 互斥锁的过期时间（秒）。必须设，否则持锁进程崩了就是死锁。 */
    private static final int LOCK_TTL_SECONDS = 5;

    /** 抢锁失败后的最大重试次数。 */
    private static final int MAX_RETRIES = 3;

    /** 每次重试前的等待毫秒数。 */
    private static final long RETRY_DELAY_MS = 100;

    private final LinkMapper linkMapper;
    private final IdSegmentManager idSegmentManager;

    /** 可选依赖：Redis 不可用时自动降级为纯 DB 模式。 */
    @Autowired(required = false)
    private StringRedisTemplate stringRedisTemplate;

    /** 可选依赖：布隆过滤器不可用时跳过第一层拦截，不影响可用性。 */
    @Autowired(required = false)
    private BloomFilter<String> bloomFilter;

    public LinkServiceImpl(LinkMapper linkMapper, IdSegmentManager idSegmentManager) {
        this.linkMapper = linkMapper;
        this.idSegmentManager = idSegmentManager;
    }

    @Override
    public Link createLink(String originalUrl) {
        // 号段模式：先从内存取一个 id，Base62 转成短码，一次 INSERT 落地。
        // 一条语句就写完，没有"先插后改"的中间状态。
        long id = idSegmentManager.getNextId(IdSegmentManager.BIZ_TAG_LINK);
        String shortCode = Base62.encode(id);

        Link link = new Link();
        link.setId(id);
        link.setShortCode(shortCode);
        link.setOriginalUrl(originalUrl);
        link.setVisitCount(0L);
        linkMapper.insert(link);

        // 必须同步进布隆过滤器。漏了这一步，新建的短链在缓存未命中时
        // 会被第一层直接拦掉，表现为"刚生成的短链一点就 404"。
        if (bloomFilter != null) {
            bloomFilter.put(shortCode);
        }

        return link;
    }

    @Override
    public String getOriginalUrl(String shortCode) {
        // 第一层：布隆过滤器。说"不存在"就一定不存在，直接返回，省掉后面所有开销。
        if (bloomFilter != null && !bloomFilter.mightContain(shortCode)) {
            log.debug("布隆过滤器拦截: {}", shortCode);
            return null;
        }

        // 第二层：Redis 缓存
        if (stringRedisTemplate != null) {
            String cacheKey = RedisKeyConstant.LINK_CACHE + shortCode;
            String cached = stringRedisTemplate.opsForValue().get(cacheKey);
            if (cached != null) {
                return cached;
            }

            // 第三层：互斥锁防击穿。缓存刚失效时可能有大量请求同时到达，
            // 只让抢到锁的那一个去查库，其它线程等它把缓存回填好再读。
            String lockKey = RedisKeyConstant.LINK_LOCK + shortCode;
            for (int i = 0; i < MAX_RETRIES; i++) {
                // SETNX + TTL 一步完成：既保证只有一个线程拿到锁，又保证锁不会永久残留
                Boolean locked = stringRedisTemplate.opsForValue()
                        .setIfAbsent(lockKey, "1", Duration.ofSeconds(LOCK_TTL_SECONDS));
                if (Boolean.TRUE.equals(locked)) {
                    try {
                        // Double-check：抢锁期间可能已经有别的线程重建好了缓存，
                        // 不检查就会白白多查一次库 —— 这正是锁要避免的事情。
                        cached = stringRedisTemplate.opsForValue().get(cacheKey);
                        if (cached != null) {
                            return cached;
                        }
                        return queryDbAndCache(shortCode, cacheKey);
                    } finally {
                        // 放锁必须放在 finally：中途抛异常也要释放，
                        // 否则只能干等 TTL 到期，这期间该短码的所有请求都拿不到锁。
                        stringRedisTemplate.delete(lockKey);
                    }
                }

                // 没抢到锁：等一小会儿再看缓存，多半已经被持锁线程填好了
                try {
                    Thread.sleep(RETRY_DELAY_MS);
                } catch (InterruptedException e) {
                    // 恢复中断标记后退出，不要吞掉 —— 否则上层不知道被中断过
                    Thread.currentThread().interrupt();
                    break;
                }
                cached = stringRedisTemplate.opsForValue().get(cacheKey);
                if (cached != null) {
                    return cached;
                }
            }
            log.warn("互斥锁重试耗尽，降级直查库: {}", shortCode);
        }

        // 第四层：兜底直查库（Redis 不可用或抢锁重试耗尽时走这里）
        return queryDbDirect(shortCode);
    }

    /**
     * 查库并回填缓存。只在持锁时调用，保证同一时刻只有一个线程执行。
     */
    private String queryDbAndCache(String shortCode, String cacheKey) {
        Link link = selectByShortCode(shortCode);
        if (link == null) {
            return null;
        }
        stringRedisTemplate.opsForValue().set(
                cacheKey, link.getOriginalUrl(), randomCacheTtl());
        return link.getOriginalUrl();
    }

    /**
     * 降级路径：不持锁，查库后尝试回填缓存。
     *
     * <p>这里没有互斥保护，多个线程可能同时回填 —— 但写的是同一个值，
     * 覆盖了也无所谓。宁可多写几次 Redis，也不要让请求在这里排队。
     */
    private String queryDbDirect(String shortCode) {
        Link link = selectByShortCode(shortCode);
        if (link == null) {
            return null;
        }
        if (stringRedisTemplate != null) {
            stringRedisTemplate.opsForValue().set(
                    RedisKeyConstant.LINK_CACHE + shortCode,
                    link.getOriginalUrl(),
                    randomCacheTtl());
        }
        return link.getOriginalUrl();
    }

    @Override
    public void incrementVisitCount(String shortCode) {
        if (stringRedisTemplate == null) {
            return;
        }
        // INCR 是原子的：多个线程同时打同一个短链不会丢计数。
        // 这里不设过期时间 —— 计数器要一直累加到定时任务把它取走为止，
        // 中途过期就等于丢访问量。定时任务取走后会 delete 掉这个 key。
        stringRedisTemplate.opsForValue().increment(RedisKeyConstant.LINK_VISITS + shortCode);
    }

    private Link selectByShortCode(String shortCode) {
        return linkMapper.selectOne(
                new LambdaQueryWrapper<Link>().eq(Link::getShortCode, shortCode));
    }

    /**
     * 算一个带随机抖动的 TTL，落在 [base - jitter, base + jitter] 分钟之间。
     *
     * <p>用 {@link ThreadLocalRandom} 而不是 {@code Random}：高并发下多个线程共用一个
     * {@code Random} 实例会争抢内部的 CAS，{@code ThreadLocalRandom} 每个线程一份，
     * 没有竞争。
     */
    private Duration randomCacheTtl() {
        int offset = ThreadLocalRandom.current().nextInt(
                -CACHE_TTL_JITTER_MINUTES, CACHE_TTL_JITTER_MINUTES + 1);
        return Duration.ofMinutes(CACHE_TTL_MINUTES + offset);
    }
}
