package com.miuxuer.linkforge.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.google.common.hash.BloomFilter;
import com.miuxuer.linkforge.constant.MessageConstant;
import com.miuxuer.linkforge.constant.StatusConstant;
import com.miuxuer.linkforge.context.CurrentHolder;
import com.miuxuer.linkforge.dto.LinkCreateDTO;
import com.miuxuer.linkforge.dto.LinkPageQueryDTO;
import com.miuxuer.linkforge.dto.LinkUpdateDTO;
import com.miuxuer.linkforge.entity.Link;
import com.miuxuer.linkforge.exception.BusinessException;
import com.miuxuer.linkforge.exception.LinkNotFoundException;
import com.miuxuer.linkforge.mapper.LinkMapper;
import com.miuxuer.linkforge.properties.LinkProperties;
import com.miuxuer.linkforge.result.ResultCode;
import com.miuxuer.linkforge.service.IdSegmentManager;
import com.miuxuer.linkforge.vo.LinkVO;
import com.miuxuer.linkforge.vo.PageResult;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
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

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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

    private LinkProperties linkProperties;

    private LinkServiceImpl linkService;

    /**
     * 给 MyBatis-Plus 喂一份 Link 的表结构元数据。
     *
     * <p>分页查询那几个用例要检查 {@code LambdaQueryWrapper.getSqlSegment()} ——
     * 把 {@code Link::getUserId} 解析成列名 {@code user_id} 需要一份
     * "实体 → TableInfo" 的缓存。这份缓存平时由 MyBatis 启动时扫描实体填充，
     * 而这里是没有 Spring 上下文的纯单元测试，缓存是空的，直接报
     * {@code can not find lambda cache for this entity}。
     *
     * <p>注意：包装器的构建本身不报错，报错的是"生成 SQL 片段"这一步 ——
     * 也就是说，单元测试里不初始化这份缓存的话，你根本验证不到生成的 SQL 长什么样，
     * 而多租户隔离恰恰只能从 SQL 上验证。
     */
    @BeforeAll
    static void initMybatisPlusTableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), Link.class);
    }

    @BeforeEach
    void setUp() {
        linkProperties = new LinkProperties();
        linkProperties.setDomain("http://localhost:8080");

        linkService = new LinkServiceImpl(linkMapper, idSegmentManager, linkProperties);
        // 这两个是 @Autowired(required = false) 的可选依赖，构造器注不进去，
        // 测试里手动塞进去
        ReflectionTestUtils.setField(linkService, "stringRedisTemplate", stringRedisTemplate);
        ReflectionTestUtils.setField(linkService, "bloomFilter", bloomFilter);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @AfterEach
    void tearDown() {
        // createLink 依赖 ThreadLocal 里的登录态，不清会串到下一个用例
        CurrentHolder.remove();
    }

    private static LinkCreateDTO createDto(String originalUrl) {
        LinkCreateDTO dto = new LinkCreateDTO();
        dto.setOriginalUrl(originalUrl);
        return dto;
    }

    /** 造一条"可正常跳转"的短链：启用 + 永不过期。 */
    private static Link link(String shortCode, String originalUrl) {
        Link link = new Link();
        link.setShortCode(shortCode);
        link.setOriginalUrl(originalUrl);
        link.setStatus(StatusConstant.ENABLED);
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

    // ==================== 跳转有效性校验（状态 / 过期） ====================

    @Test
    @DisplayName("已停用的短链 → 返回 null，且不写进缓存")
    void disabledLink_shouldNotRedirect() {
        String shortCode = "off001";
        Link disabled = link(shortCode, "https://www.example.com");
        disabled.setStatus(StatusConstant.DISABLED);
        when(bloomFilter.mightContain(shortCode)).thenReturn(true);
        when(valueOperations.get(CACHE_KEY + shortCode)).thenReturn(null);
        when(linkMapper.selectOne(any())).thenReturn(disabled);

        assertNull(linkService.getOriginalUrl(shortCode));

        // 关键：不能缓存。缓存了的话，接下来 25~35 分钟命中缓存时照跳不误，
        // 用户会以为"停用"这个操作没生效
        verify(valueOperations, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("已过期的短链 → 返回 null，且不写进缓存")
    void expiredLink_shouldNotRedirect() {
        String shortCode = "exp001";
        Link expired = link(shortCode, "https://www.example.com");
        expired.setExpireTime(LocalDateTime.now().minusMinutes(1));
        when(bloomFilter.mightContain(shortCode)).thenReturn(true);
        when(valueOperations.get(CACHE_KEY + shortCode)).thenReturn(null);
        when(linkMapper.selectOne(any())).thenReturn(expired);

        assertNull(linkService.getOriginalUrl(shortCode));
        verify(valueOperations, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("未过期 → 正常跳转，且缓存 TTL 不超过「距离过期还剩多久」")
    void linkExpiringSoon_shouldCapCacheTtl() {
        String shortCode = "soon01";
        Link soon = link(shortCode, "https://www.example.com");
        // 还有 2 分钟过期，而默认缓存 TTL 是 25~35 分钟
        soon.setExpireTime(LocalDateTime.now().plusMinutes(2));
        when(bloomFilter.mightContain(shortCode)).thenReturn(true);
        when(valueOperations.get(CACHE_KEY + shortCode)).thenReturn(null);
        when(linkMapper.selectOne(any())).thenReturn(soon);

        assertEquals("https://www.example.com", linkService.getOriginalUrl(shortCode));

        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(valueOperations).set(eq(CACHE_KEY + shortCode), anyString(), ttlCaptor.capture());

        // 不封顶的话，这条链在过期后还能靠缓存继续跳将近半小时
        assertTrue(ttlCaptor.getValue().toMinutes() <= 2,
                "缓存 TTL 超过了短链剩余寿命，过期时间会形同虚设: " + ttlCaptor.getValue());
        assertTrue(ttlCaptor.getValue().toSeconds() > 0, "TTL 必须为正数");
    }

    @Test
    @DisplayName("status 为 null 的脏数据 → 按不可用处理，不放行")
    void nullStatus_shouldNotRedirect() {
        String shortCode = "dirty1";
        Link dirty = new Link();
        dirty.setShortCode(shortCode);
        dirty.setOriginalUrl("https://www.example.com");
        // status 没赋值 —— 数据库里如果有这种历史脏数据，不能当成"启用"
        when(bloomFilter.mightContain(shortCode)).thenReturn(true);
        when(valueOperations.get(CACHE_KEY + shortCode)).thenReturn(null);
        when(linkMapper.selectOne(any())).thenReturn(dirty);

        assertNull(linkService.getOriginalUrl(shortCode));
    }

    // ==================== 降级可用性 ====================

    @Test
    @DisplayName("Redis 与布隆过滤器都没配上 → 纯 DB 模式仍能正常返回")
    void withoutRedisAndBloom_shouldDegradeToDb() {
        // 不走 @InjectMocks，自己 new 一个不注入可选依赖的实例
        LinkServiceImpl degraded = new LinkServiceImpl(linkMapper, idSegmentManager, linkProperties);
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
        LinkServiceImpl degraded = new LinkServiceImpl(linkMapper, idSegmentManager, linkProperties);

        degraded.incrementVisitCount("abc123");
    }

    // ==================== 创建短链 ====================

    @Test
    @DisplayName("创建短链 → 号段取 id、Base62 编码、绑定当前用户、一次 INSERT、同步进布隆")
    void createLink_shouldEncodeIdAndSyncBloomFilter() {
        CurrentHolder.setCurrentId(1001L);
        when(idSegmentManager.getNextId(IdSegmentManager.BIZ_TAG_LINK)).thenReturn(1L);

        LinkVO result = linkService.createLink(createDto("https://www.baidu.com"));

        assertEquals(1L, result.getId());
        assertEquals("1", result.getShortCode());   // Base62.encode(1) = "1"
        assertEquals("https://www.baidu.com", result.getOriginalUrl());
        // shortUrl 是域名 + 短码拼出来的，前端拿去直接展示
        assertEquals("http://localhost:8080/1", result.getShortUrl());
        assertNotNull(result.getVisitCount());

        // 新短码必须进布隆，否则刚建的链会被第一层拦成 404
        verify(bloomFilter).put("1");
        // 号段模式一条 INSERT 就够，不需要回填短码
        verify(linkMapper, times(1)).insertWithFill(any(Link.class));
        verify(linkMapper, never()).updateByIdWithFill(any(Link.class));
    }

    @Test
    @DisplayName("创建短链 → 归属用户来自登录态，不是请求体")
    void createLink_shouldBindCurrentUser() {
        CurrentHolder.setCurrentId(1001L);
        when(idSegmentManager.getNextId(any())).thenReturn(2L);

        linkService.createLink(createDto("https://www.baidu.com"));

        ArgumentCaptor<Link> captor = ArgumentCaptor.forClass(Link.class);
        verify(linkMapper).insertWithFill(captor.capture());
        // LinkCreateDTO 里根本没有 userId 字段，想挂到别人名下都没有入口
        assertEquals(1001L, captor.getValue().getUserId());
    }

    @Test
    @DisplayName("创建短链 → 默认启用状态，过期时间原样落库")
    void createLink_shouldSetStatusAndExpireTime() {
        CurrentHolder.setCurrentId(1001L);
        when(idSegmentManager.getNextId(any())).thenReturn(3L);
        LocalDateTime expireAt = LocalDateTime.now().plusDays(7);
        LinkCreateDTO dto = createDto("https://www.baidu.com");
        dto.setExpireTime(expireAt);

        linkService.createLink(dto);

        ArgumentCaptor<Link> captor = ArgumentCaptor.forClass(Link.class);
        verify(linkMapper).insertWithFill(captor.capture());
        assertEquals(StatusConstant.ENABLED, captor.getValue().getStatus());
        assertEquals(expireAt, captor.getValue().getExpireTime());
    }

    @Test
    @DisplayName("未登录（ThreadLocal 为空）→ 401，且不写库")
    void createLink_withoutLogin_shouldThrowUnauthorized() {
        // 拦截器没生效时会出现这种情况，不能让它插一条 userId 为 null 的脏数据
        BusinessException e = assertThrows(BusinessException.class,
                () -> linkService.createLink(createDto("https://www.baidu.com")));

        assertEquals(ResultCode.UNAUTHORIZED.getHttpStatus(), e.getHttpStatus());
        verify(linkMapper, never()).insertWithFill(any(Link.class));
        // 也不能白白浪费一个号段里的 id
        verify(idSegmentManager, never()).getNextId(any());
    }

    // ==================== 分页查询与多租户隔离 ====================

    /** 捕获 selectPage 收到的那条 wrapper，用来检查它生成的 SQL 片段。 */
    @SuppressWarnings("unchecked")
    private LambdaQueryWrapper<Link> capturePageWrapper() {
        ArgumentCaptor<Wrapper<Link>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(linkMapper).selectPage(any(), captor.capture());
        return (LambdaQueryWrapper<Link>) captor.getValue();
    }

    private static LinkPageQueryDTO pageDto(String keyword) {
        LinkPageQueryDTO dto = new LinkPageQueryDTO();
        dto.setKeyword(keyword);
        return dto;
    }

    @Test
    @DisplayName("分页查询 → SQL 永远带 user_id 条件")
    void pageMyLinks_shouldAlwaysFilterByCurrentUser() {
        CurrentHolder.setCurrentId(1001L);
        when(linkMapper.selectPage(any(), any())).thenReturn(new Page<>());

        linkService.pageMyLinks(new LinkPageQueryDTO());

        // 哪怕没有任何筛选条件，user_id 也必须在
        assertTrue(capturePageWrapper().getSqlSegment().contains("user_id"),
                "分页查询漏了 user_id 条件，等于把全站短链都查出来了");
    }

    @Test
    @DisplayName("带关键词搜索 → OR 分组必须被括号包住，且 user_id 在分组之外")
    void pageMyLinks_keywordOrMustBeNested() {
        CurrentHolder.setCurrentId(1001L);
        when(linkMapper.selectPage(any(), any())).thenReturn(new Page<>());

        linkService.pageMyLinks(pageDto("abc"));

        String sql = capturePageWrapper().getSqlSegment();

        // 期望的结构：user_id = ? AND (title LIKE ? OR short_code LIKE ?)
        // 少写那对括号会变成：
        //   (user_id = ? AND title LIKE ?) OR short_code LIKE ?
        // 于是"短码匹配上的记录"绕过了用户隔离 —— 别人搜一串短码前缀
        // 就能翻出全站短链。AND 优先级高于 OR，这个坑不报错、只越权。
        int orIndex = sql.indexOf(" OR ");
        assertTrue(orIndex > 0, "关键词搜索应该生成 OR 条件: " + sql);

        int groupOpen = sql.lastIndexOf('(', orIndex);
        int groupClose = sql.indexOf(')', orIndex);
        assertTrue(groupOpen >= 0 && groupClose > orIndex, "OR 没有被括号包住: " + sql);

        // OR 所在的那个括号组里应该只有两个 LIKE 条件
        assertTrue(sql.substring(groupOpen, groupClose).contains("LIKE"),
                "括号里应该是两个 LIKE: " + sql);
        // 而 user_id 必须在括号组之外，否则就失去隔离作用了
        assertTrue(sql.substring(0, groupOpen).contains("user_id"),
                "user_id 条件被卷进了 OR 分组，隔离失效: " + sql);
    }

    @Test
    @DisplayName("分页查询 → 实体转成 VO，total/page/pageSize 透传")
    void pageMyLinks_shouldMapToVo() {
        CurrentHolder.setCurrentId(1001L);
        Page<Link> dbPage = new Page<>(2, 10);
        dbPage.setTotal(35);
        Link entity = link("abc123", "https://www.baidu.com");
        entity.setId(9L);
        entity.setTitle("百度");
        dbPage.setRecords(List.of(entity));
        when(linkMapper.selectPage(any(), any())).thenReturn(dbPage);

        PageResult<LinkVO> result = linkService.pageMyLinks(new LinkPageQueryDTO());

        assertEquals(35, result.getTotal());
        assertEquals(2, result.getPage());
        assertEquals(10, result.getPageSize());
        assertEquals(1, result.getRecords().size());
        assertEquals("abc123", result.getRecords().get(0).getShortCode());
        assertEquals("http://localhost:8080/abc123", result.getRecords().get(0).getShortUrl());
    }

    @Test
    @DisplayName("分页查询 → 状态筛选条件按需拼接")
    void pageMyLinks_statusFilterShouldBeOptional() {
        CurrentHolder.setCurrentId(1001L);
        when(linkMapper.selectPage(any(), any())).thenReturn(new Page<>());

        LinkPageQueryDTO withStatus = new LinkPageQueryDTO();
        withStatus.setStatus(StatusConstant.DISABLED);
        linkService.pageMyLinks(withStatus);
        linkService.pageMyLinks(new LinkPageQueryDTO());

        // 两次调用，一次带 status 一次不带，一次把两条 wrapper 都抓出来
        ArgumentCaptor<Wrapper<Link>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(linkMapper, times(2)).selectPage(any(), captor.capture());
        String withStatusSql = ((LambdaQueryWrapper<Link>) captor.getAllValues().get(0)).getSqlSegment();
        String withoutStatusSql = ((LambdaQueryWrapper<Link>) captor.getAllValues().get(1)).getSqlSegment();

        assertTrue(withStatusSql.contains("status"), "传了 status 就应该带上筛选条件: " + withStatusSql);
        // 注意别拼成恒真/恒假的表达式：没传筛选条件时条件就不该出现
        assertFalse(withoutStatusSql.contains("status"),
                "没传 status 却拼了筛选条件，会莫名其妙查不到数据: " + withoutStatusSql);
    }

    @Test
    @DisplayName("分页查询 → 未登录直接 401，不查库")
    void pageMyLinks_withoutLogin_shouldThrowUnauthorized() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> linkService.pageMyLinks(new LinkPageQueryDTO()));

        assertEquals(ResultCode.UNAUTHORIZED.getHttpStatus(), e.getHttpStatus());
        verify(linkMapper, never()).selectPage(any(), any());
    }

    // ==================== 修改 / 删除 ====================

    /** 造一条属于 userId 的已存在短链。 */
    private static Link ownedLink(Long id, Long userId, String shortCode) {
        Link link = new Link();
        link.setId(id);
        link.setUserId(userId);
        link.setShortCode(shortCode);
        link.setStatus(StatusConstant.ENABLED);
        return link;
    }

    private static LinkUpdateDTO updateDto() {
        LinkUpdateDTO dto = new LinkUpdateDTO();
        dto.setTitle("新标题");
        dto.setStatus(StatusConstant.DISABLED);
        // expireTime 故意留 null —— 这正是"取消过期时间"的表达方式
        return dto;
    }

    @SuppressWarnings("unchecked")
    private LambdaUpdateWrapper<Link> captureUpdateWrapper() {
        ArgumentCaptor<Wrapper<Link>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(linkMapper).updateWithFill(any(Link.class), captor.capture());
        return (LambdaUpdateWrapper<Link>) captor.getValue();
    }

    @Test
    @DisplayName("修改短链 → UPDATE 的 WHERE 里必须同时有 id 和 user_id")
    void updateLink_shouldIncludeOwnerInWhereClause() {
        CurrentHolder.setCurrentId(1001L);
        when(linkMapper.selectById(1L)).thenReturn(ownedLink(1L, 1001L, "abc"));
        when(linkMapper.updateWithFill(any(Link.class), any())).thenReturn(1);

        linkService.updateLink(1L, updateDto());

        String sql = captureUpdateWrapper().getSqlSegment();
        // 只用 selectById 查一次来判断归属是不够的：查询和更新之间隔了时间，
        // 中间数据可能变（TOCTOU）。条件写进 UPDATE 的 WHERE 才是原子的。
        assertTrue(sql.contains("user_id"), "UPDATE 少了 user_id 条件，能改到别人的短链: " + sql);
        assertTrue(sql.contains("id"), sql);
    }

    @Test
    @DisplayName("修改短链 → 清掉跳转缓存，否则停用后缓存命中还能继续跳")
    void updateLink_shouldEvictCache() {
        CurrentHolder.setCurrentId(1001L);
        when(linkMapper.selectById(1L)).thenReturn(ownedLink(1L, 1001L, "abc"));
        when(linkMapper.updateWithFill(any(Link.class), any())).thenReturn(1);

        linkService.updateLink(1L, updateDto());

        // 用户把短链停用了，但缓存里还存着"能跳"的旧结果 ——
        // 不清掉的话，停用动作要等缓存自然过期才生效
        verify(stringRedisTemplate).delete(CACHE_KEY + "abc");
    }

    @Test
    @DisplayName("修改别人的短链 → 403，且一个字都不写")
    void updateLink_notOwned_shouldThrowForbidden() {
        CurrentHolder.setCurrentId(1001L);
        // 这条短链属于 2002
        when(linkMapper.selectById(1L)).thenReturn(ownedLink(1L, 2002L, "abc"));

        BusinessException e = assertThrows(BusinessException.class,
                () -> linkService.updateLink(1L, updateDto()));

        assertEquals(ResultCode.FORBIDDEN.getHttpStatus(), e.getHttpStatus());
        assertEquals(MessageConstant.LINK_NOT_OWNED, e.getMessage());
        verify(linkMapper, never()).updateWithFill(any(Link.class), any());
    }

    @Test
    @DisplayName("修改不存在的短链 → 404")
    void updateLink_notFound_shouldThrowNotFound() {
        CurrentHolder.setCurrentId(1001L);
        when(linkMapper.selectById(999L)).thenReturn(null);

        LinkNotFoundException e = assertThrows(LinkNotFoundException.class,
                () -> linkService.updateLink(999L, updateDto()));

        assertEquals(ResultCode.NOT_FOUND.getHttpStatus(), e.getHttpStatus());
    }

    @Test
    @DisplayName("删除短链 → 走逻辑删除，且条件带 user_id")
    void deleteLink_shouldLogicallyDelete() {
        CurrentHolder.setCurrentId(1001L);
        when(linkMapper.selectById(1L)).thenReturn(ownedLink(1L, 1001L, "abc"));
        when(linkMapper.delete(any())).thenReturn(1);

        linkService.deleteLink(1L);

        ArgumentCaptor<Wrapper<Link>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(linkMapper).delete(captor.capture());
        String sql = captor.getValue().getSqlSegment();
        assertTrue(sql.contains("user_id"), "DELETE 少了 user_id 条件，能删掉别人的短链: " + sql);
        // 缓存也要清，否则已删除的短链还能被跳转
        verify(stringRedisTemplate).delete(CACHE_KEY + "abc");
    }

    @Test
    @DisplayName("删除别人的短链 → 403，且不执行删除")
    void deleteLink_notOwned_shouldThrowForbidden() {
        CurrentHolder.setCurrentId(1001L);
        when(linkMapper.selectById(1L)).thenReturn(ownedLink(1L, 2002L, "abc"));

        BusinessException e = assertThrows(BusinessException.class,
                () -> linkService.deleteLink(1L));

        assertEquals(ResultCode.FORBIDDEN.getHttpStatus(), e.getHttpStatus());
        verify(linkMapper, never()).delete(any());
    }
}
