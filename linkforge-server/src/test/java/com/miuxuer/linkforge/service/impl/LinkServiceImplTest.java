package com.miuxuer.linkforge.service.impl;

import com.google.common.hash.BloomFilter;
import com.miuxuer.linkforge.entity.Link;
import com.miuxuer.linkforge.mapper.LinkMapper;
import com.miuxuer.linkforge.service.IdSegmentManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 四层防护（布隆 → 缓存 → 互斥锁 → DB）单元测试。
 *
 * <p>Redis 和布隆过滤器都用 Mockito 打桩，不依赖本机 Redis。
 * 重点是覆盖这几种分支：布隆拦截、缓存命中、抢到锁、抢不到锁、重试耗尽降级。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("短链服务四层防护")
class LinkServiceImplTest {

    private static final String CACHE_KEY = "linkforge:link:cache:";
    private static final String LOCK_KEY = "linkforge:link:lock:";
    private static final String VISITS_KEY = "linkforge:link:visits:";

    @Mock
    private LinkMapper linkMapper;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private BloomFilter<String> bloomFilter;

    @Mock
    private IdSegmentManager idSegmentManager;

    @InjectMocks
    private LinkServiceImpl linkService;

    @BeforeEach
    void setUp() {
        // 这两个是 @Autowired(required = false) 的可选依赖，构造器注不进去，
        // 测试里手动塞进去
        ReflectionTestUtils.setField(linkService, "stringRedisTemplate", stringRedisTemplate);
        ReflectionTestUtils.setField(linkService, "bloomFilter", bloomFilter);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    private static Link link(String shortCode, String originalUrl) {
        Link link = new Link();
        link.setShortCode(shortCode);
        link.setOriginalUrl(originalUrl);
        return link;
    }

    // ==================== 第一层：布隆过滤器 ====================

    @Test
    @DisplayName("布隆拦截 → 直接返回 null，既不碰 Redis 也不查库")
    void bloomFilterRejects_shouldReturnNullImmediately() {
        when(bloomFilter.mightContain("fake123")).thenReturn(false);

        assertNull(linkService.getOriginalUrl("fake123"));

        verify(valueOperations, never()).get(anyString());
        verify(linkMapper, never()).selectOne(any());
    }

    // ==================== 第二层：缓存命中 ====================

    @Test
    @DisplayName("缓存命中 → 直接返回，不查库")
    void cacheHit_shouldReturnCachedValue() {
        when(bloomFilter.mightContain("abc123")).thenReturn(true);
        when(valueOperations.get(CACHE_KEY + "abc123")).thenReturn("https://www.baidu.com");

        assertEquals("https://www.baidu.com", linkService.getOriginalUrl("abc123"));
        verify(linkMapper, never()).selectOne(any());
    }

    // ==================== 第三层：互斥锁 ====================

    @Test
    @DisplayName("缓存未命中 + 抢到锁 → 查库重建缓存，并在 finally 里释放锁")
    void lockAcquired_shouldRebuildCacheAndReleaseLock() {
        String shortCode = "hot123";
        String originalUrl = "https://www.example.com";
        when(bloomFilter.mightContain(shortCode)).thenReturn(true);
        when(valueOperations.get(CACHE_KEY + shortCode)).thenReturn(null);
        when(valueOperations.setIfAbsent(eq(LOCK_KEY + shortCode), eq("1"), any(Duration.class)))
                .thenReturn(true);
        when(linkMapper.selectOne(any())).thenReturn(link(shortCode, originalUrl));

        assertEquals(originalUrl, linkService.getOriginalUrl(shortCode));

        verify(valueOperations).set(eq(CACHE_KEY + shortCode), eq(originalUrl), any(Duration.class));
        verify(stringRedisTemplate).delete(LOCK_KEY + shortCode);
    }

    @Test
    @DisplayName("抢到锁 + Double-check 命中 → 不查库，仍要释放锁")
    void lockAcquired_doubleCheckHit_shouldReturnCached() {
        String shortCode = "hot456";
        String cachedUrl = "https://www.double-check.com";
        when(bloomFilter.mightContain(shortCode)).thenReturn(true);
        // 第一次 get 未命中；进入锁后 double-check 命中（说明别的线程已经重建好了）
        when(valueOperations.get(CACHE_KEY + shortCode))
                .thenReturn(null)
                .thenReturn(cachedUrl);
        when(valueOperations.setIfAbsent(eq(LOCK_KEY + shortCode), eq("1"), any(Duration.class)))
                .thenReturn(true);

        assertEquals(cachedUrl, linkService.getOriginalUrl(shortCode));

        verify(linkMapper, never()).selectOne(any());
        verify(stringRedisTemplate).delete(LOCK_KEY + shortCode);
    }

    @Test
    @DisplayName("没抢到锁 + 重试期间缓存被填好 → 返回缓存值，不查库")
    void lockFailed_retryCacheHit_shouldReturnCached() {
        String shortCode = "hot789";
        String cachedUrl = "https://www.retry-hit.com";
        when(bloomFilter.mightContain(shortCode)).thenReturn(true);
        // 第一次未命中；等 100ms 后重试前再查，已经命中了
        when(valueOperations.get(CACHE_KEY + shortCode))
                .thenReturn(null)
                .thenReturn(cachedUrl);
        when(valueOperations.setIfAbsent(eq(LOCK_KEY + shortCode), eq("1"), any(Duration.class)))
                .thenReturn(false);

        assertEquals(cachedUrl, linkService.getOriginalUrl(shortCode));
        verify(linkMapper, never()).selectOne(any());
    }

    @Test
    @DisplayName("始终抢不到锁 + 重试耗尽 → 降级直查库并回填缓存")
    void lockFailed_retriesExhausted_shouldFallbackToDb() {
        String shortCode = "cold999";
        String originalUrl = "https://www.fallback.com";
        when(bloomFilter.mightContain(shortCode)).thenReturn(true);
        when(valueOperations.get(CACHE_KEY + shortCode)).thenReturn(null);
        when(valueOperations.setIfAbsent(eq(LOCK_KEY + shortCode), eq("1"), any(Duration.class)))
                .thenReturn(false);
        when(linkMapper.selectOne(any())).thenReturn(link(shortCode, originalUrl));

        assertEquals(originalUrl, linkService.getOriginalUrl(shortCode));

        // 降级路径虽然不持锁，但依然会回填缓存
        verify(valueOperations).set(eq(CACHE_KEY + shortCode), eq(originalUrl), any(Duration.class));
    }

    // ==================== 降级可用性 ====================

    @Test
    @DisplayName("Redis 与布隆过滤器都没配上 → 纯 DB 模式仍能正常返回")
    void withoutRedisAndBloom_shouldDegradeToDb() {
        // 不走 @InjectMocks，自己 new 一个不注入可选依赖的实例
        LinkServiceImpl degraded = new LinkServiceImpl(linkMapper, idSegmentManager);
        when(linkMapper.selectOne(any())).thenReturn(link("plain1", "https://www.degraded.com"));

        assertEquals("https://www.degraded.com", degraded.getOriginalUrl("plain1"));
    }

    // ==================== 访问计数 ====================

    @Test
    @DisplayName("访问计数 → 只做 Redis INCR，一次数据库都不碰")
    void incrementVisitCount_shouldOnlyTouchRedis() {
        linkService.incrementVisitCount("abc123");

        // 走 Redis 而不是直接 UPDATE，是这套计数方案的全部意义所在
        verify(valueOperations).increment(VISITS_KEY + "abc123");
        verify(linkMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("Redis 不可用 → 计数直接跳过，不抛异常拖垮跳转")
    void incrementVisitCount_withoutRedis_shouldNotThrow() {
        LinkServiceImpl degraded = new LinkServiceImpl(linkMapper, idSegmentManager);

        degraded.incrementVisitCount("abc123");
    }

    // ==================== 创建短链 ====================

    @Test
    @DisplayName("创建短链 → 号段取 id、Base62 编码、一次 INSERT、同步进布隆过滤器")
    void createLink_shouldEncodeIdAndSyncBloomFilter() {
        String originalUrl = "https://www.baidu.com";
        when(idSegmentManager.getNextId(IdSegmentManager.BIZ_TAG_LINK)).thenReturn(1L);

        Link result = linkService.createLink(originalUrl);

        assertEquals(1L, result.getId());
        assertEquals("1", result.getShortCode());   // Base62.encode(1) = "1"
        assertEquals(originalUrl, result.getOriginalUrl());
        assertNotNull(result.getVisitCount());

        // 新短码必须进布隆，否则刚建的链会被第一层拦成 404
        verify(bloomFilter).put("1");
        // 号段模式一条 INSERT 就够，不需要回填短码
        verify(linkMapper, times(1)).insert(any(Link.class));
        verify(linkMapper, never()).updateById(any(Link.class));
    }
}
