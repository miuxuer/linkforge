package com.miuxuer.linkforge.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.google.common.hash.BloomFilter;
import com.miuxuer.linkforge.constant.MessageConstant;
import com.miuxuer.linkforge.constant.RedisKeyConstant;
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
import com.miuxuer.linkforge.service.LinkService;
import com.miuxuer.linkforge.utils.Base62;
import com.miuxuer.linkforge.utils.QrCodeUtils;
import com.miuxuer.linkforge.vo.LinkVO;
import com.miuxuer.linkforge.vo.PageResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
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
    private final LinkProperties linkProperties;
    private final OssImageLoader ossImageLoader;
    private final LinkCacheEvictor linkCacheEvictor;

    /** 可选依赖：Redis 不可用时自动降级为纯 DB 模式。 */
    @Autowired(required = false)
    private StringRedisTemplate stringRedisTemplate;

    /** 可选依赖：布隆过滤器不可用时跳过第一层拦截，不影响可用性。 */
    @Autowired(required = false)
    private BloomFilter<String> bloomFilter;

    public LinkServiceImpl(LinkMapper linkMapper,
                           IdSegmentManager idSegmentManager,
                           LinkProperties linkProperties,
                           OssImageLoader ossImageLoader,
                           LinkCacheEvictor linkCacheEvictor) {
        this.linkMapper = linkMapper;
        this.idSegmentManager = idSegmentManager;
        this.linkProperties = linkProperties;
        this.ossImageLoader = ossImageLoader;
        this.linkCacheEvictor = linkCacheEvictor;
    }

    @Override
    public LinkVO createLink(LinkCreateDTO dto) {
        Long userId = CurrentHolder.requireCurrentId();

        // 号段模式：先从内存取一个 id，Base62 转成短码，一次 INSERT 落地。
        // 一条语句就写完，没有"先插后改"的中间状态。
        long id = idSegmentManager.getNextId(IdSegmentManager.BIZ_TAG_LINK);
        String shortCode = Base62.encode(id);

        Link link = new Link();
        link.setId(id);
        link.setShortCode(shortCode);
        link.setOriginalUrl(dto.getOriginalUrl());
        link.setTitle(dto.getTitle());
        link.setRemark(dto.getRemark());
        // 归属用户来自登录态，不是请求体 —— 这条链路是多租户隔离的起点
        link.setUserId(userId);
        link.setVisitCount(0L);
        link.setStatus(StatusConstant.ENABLED);
        link.setExpireTime(dto.getExpireTime());
        // insertWithFill 会把 create_time / create_user 等自动填上（切面负责）
        linkMapper.insertWithFill(link);

        // 必须同步进布隆过滤器。漏了这一步，新建的短链在缓存未命中时
        // 会被第一层直接拦掉，表现为"刚生成的短链一点就 404"。
        if (bloomFilter != null) {
            bloomFilter.put(shortCode);
        }

        log.info("短链创建成功: id={}, shortCode={}, userId={}", id, shortCode, userId);
        return LinkVO.from(link, linkProperties.getDomain());
    }

    @Override
    public PageResult<LinkVO> pageMyLinks(LinkPageQueryDTO dto) {
        Long userId = CurrentHolder.requireCurrentId();

        Page<Link> pageParam = new Page<>(dto.getPage(), dto.getPageSize());

        LambdaQueryWrapper<Link> wrapper = new LambdaQueryWrapper<Link>()
                // ★ 多租户隔离的那一行。它在最外层，且不带任何条件判断 ——
                // 永远存在，不可能被某个分支绕过。
                .eq(Link::getUserId, userId)
                .eq(dto.getStatus() != null, Link::getStatus, dto.getStatus())
                // ★ 关键词的 OR 必须包在 and(...) 里，不能平铺。
                // 平铺的话生成的 SQL 是：
                //   WHERE user_id = ? AND title LIKE ? OR short_code LIKE ?
                // 而 AND 的优先级高于 OR，实际等价于：
                //   WHERE (user_id = ? AND title LIKE ?) OR short_code LIKE ?
                // 于是"短码匹配上的记录"会绕过 user_id 限制 ——
                // 别人拿一串短码前缀搜索，就能翻出全站的短链。
                // 这类越权不需要任何攻击技巧，纯粹是括号少写了一个。
                .and(StringUtils.hasText(dto.getKeyword()), w -> w
                        .like(Link::getTitle, dto.getKeyword())
                        .or()
                        .like(Link::getShortCode, dto.getKeyword()))
                .orderByDesc(Link::getCreateTime);

        // 注意 keyword 里的 % 和 _ 会被当成 LIKE 的通配符（比如搜 "50%" 会匹配到所有记录）。
        // 影响只限于"搜出来的结果比预期多"，不涉及越权，暂时不做转义处理。

        Page<Link> result = linkMapper.selectPage(pageParam, wrapper);

        List<LinkVO> records = result.getRecords().stream()
                .map(link -> LinkVO.from(link, linkProperties.getDomain()))
                .toList();

        return PageResult.of(result.getTotal(), result.getCurrent(), result.getSize(), records);
    }

    @Override
    public LinkVO getLink(Long id) {
        Long userId = CurrentHolder.requireCurrentId();
        return LinkVO.from(requireOwnedLink(id, userId), linkProperties.getDomain());
    }

    @Override
    public void updateLink(Long id, LinkUpdateDTO dto) {
        Long userId = CurrentHolder.requireCurrentId();
        Link existing = requireOwnedLink(id, userId);

        // 用 wrapper 显式 .set 而不是 updateById：updateById 会把实体里为 null 的字段
        // 当成"不修改"，于是"取消过期时间"这个操作就表达不出来了。
        // 这里每个字段都显式 set，null 就是"设成 null"。
        LambdaUpdateWrapper<Link> wrapper = new LambdaUpdateWrapper<Link>()
                .eq(Link::getId, id)
                // ★ 写操作同样要带 owner 条件。只靠上面那次查出来判断是不够的 ——
                // 查询和更新之间隔了一段时间，中间可能发生变化（TOCTOU）。
                // 把条件写进 UPDATE 的 WHERE 里，隔离才是原子的。
                .eq(Link::getUserId, userId)
                .set(Link::getTitle, dto.getTitle())
                .set(Link::getRemark, dto.getRemark())
                .set(Link::getStatus, dto.getStatus())
                .set(Link::getExpireTime, dto.getExpireTime())
                .set(Link::getQrLogo, dto.getQrLogo());

        // 空实体只用来承接切面填的 update_time / update_user，自己不提供 SET 字段
        int rows = linkMapper.updateWithFill(new Link(), wrapper);
        if (rows == 0) {
            // 查出来时还在、更新时已经不是自己的了（或者被并发删掉）
            throw new LinkNotFoundException(id);
        }

        log.info("短链已更新: id={}, userId={}, 状态={}", id, userId, dto.getStatus());

        // 改了状态或过期时间之后，缓存里那条"能跳转"的旧结果就过期了。
        // 不删的话，被停用的短链在被缓存命中的情况下还能继续跳 ——
        // 用户以为停用生效了，实际没有。
        linkCacheEvictor.evict(existing.getShortCode());
    }

    @Override
    public void deleteLink(Long id) {
        Long userId = CurrentHolder.requireCurrentId();
        Link existing = requireOwnedLink(id, userId);

        // 逻辑删除：@TableLogic 会把 delete 改写成 UPDATE ... SET deleted = 1。
        // 条件里同样带上 user_id。
        int rows = linkMapper.delete(new LambdaQueryWrapper<Link>()
                .eq(Link::getId, id)
                .eq(Link::getUserId, userId));
        if (rows == 0) {
            throw new LinkNotFoundException(id);
        }

        log.info("短链已删除: id={}, userId={}", id, userId);
        linkCacheEvictor.evict(existing.getShortCode());
    }

    @Override
    public byte[] generateQrCode(Long id, int size) {
        Long userId = CurrentHolder.requireCurrentId();
        Link link = requireOwnedLink(id, userId);

        // 二维码里放完整短链接，不是短码 —— 扫出来要能直接打开
        String shortUrl = linkProperties.getDomain() + "/" + link.getShortCode();

        try {
            return QrCodeUtils.toPngBytesWithLogo(shortUrl, size, loadLogoQuietly(link.getQrLogo()));
        } catch (IOException e) {
            log.error("二维码生成失败: linkId={}, shortCode={}", id, link.getShortCode(), e);
            throw new BusinessException(ResultCode.SYSTEM_ERROR, "二维码生成失败，请稍后重试");
        }
    }

    /**
     * 加载二维码的 logo，失败就返回 null（退化成不带 logo 的二维码）。
     *
     * <p><b>为什么这里吞掉异常</b>：logo 只是装饰，拿不到不该让整个功能失败。
     * 用户要的是一个能扫的二维码；因为 OSS 抖了一下就返回 500，
     * 用户拿不到任何东西，这比"少个 logo"糟糕得多。
     *
     * <p>但日志必须打 —— 否则 logo 配置一直失效，谁也发现不了。
     */
    private byte[] loadLogoQuietly(String logoUrl) {
        if (!StringUtils.hasText(logoUrl)) {
            return null;
        }
        try {
            return ossImageLoader.load(logoUrl);
        } catch (InterruptedException e) {
            // 恢复中断标记再退出，不要吞掉 —— 上层可能靠它判断"该停了"
            Thread.currentThread().interrupt();
            log.warn("加载二维码 logo 被中断: {}", logoUrl);
            return null;
        } catch (Exception e) {
            log.warn("加载二维码 logo 失败，退化为不带 logo: {}", logoUrl, e);
            return null;
        }
    }

    /**
     * 校验短链存在且属于当前用户，返回库里的记录。
     *
     * <p>"不存在"和"不是你的"分开报错：前者 404，后者 403。
     * 都报 404 的话用户会以为是自己删过，反复刷新；但也不能报得太细 ——
     * 见 {@code MessageConstant.LINK_NOT_OWNED} 里对这个取舍的说明。
     */
    private Link requireOwnedLink(Long id, Long userId) {
        Link existing = linkMapper.selectById(id);
        if (existing == null) {
            throw new LinkNotFoundException(id);
        }
        if (!userId.equals(existing.getUserId())) {
            log.warn("越权操作短链: linkId={}, 归属={}, 当前={}", id, existing.getUserId(), userId);
            throw new BusinessException(ResultCode.FORBIDDEN, MessageConstant.LINK_NOT_OWNED);
        }
        return existing;
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
        if (!isAvailable(link)) {
            return null;
        }
        stringRedisTemplate.opsForValue().set(
                cacheKey, link.getOriginalUrl(), cacheTtlFor(link));
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
        if (!isAvailable(link)) {
            return null;
        }
        if (stringRedisTemplate != null) {
            stringRedisTemplate.opsForValue().set(
                    RedisKeyConstant.LINK_CACHE + shortCode,
                    link.getOriginalUrl(),
                    cacheTtlFor(link));
        }
        return link.getOriginalUrl();
    }

    /**
     * 短链当前是否可跳转：存在、已启用、且未过期。
     *
     * <p>判断放在<b>回填缓存之前</b>很关键：不可用的短链压根不进缓存。
     * 否则一条已停用的短链会被缓存下来，接下来 25~35 分钟里每次命中缓存都照跳不误 ——
     * 用户以为停用生效了，实际没有。
     */
    private boolean isAvailable(Link link) {
        if (link == null) {
            return false;
        }
        if (link.getStatus() == null || link.getStatus() != StatusConstant.ENABLED) {
            return false;
        }
        // expireTime 为 null 表示永不过期
        return link.getExpireTime() == null
                || link.getExpireTime().isAfter(LocalDateTime.now());
    }

    /**
     * 算出这条短链该缓存多久。
     *
     * <p><b>不能无脑用固定的 25~35 分钟。</b> 假设一条短链 2 分钟后过期，
     * 缓存却按 30 分钟存 —— 过期之后缓存还在，这 28 分钟里它一直在正常跳转，
     * 过期时间形同虚设。所以取"基础 TTL"和"距离过期还剩多久"里的较小值，
     * 让过期时间是秒级生效的。
     */
    private Duration cacheTtlFor(Link link) {
        Duration baseTtl = randomCacheTtl();
        if (link.getExpireTime() == null) {
            return baseTtl;
        }
        Duration remaining = Duration.between(LocalDateTime.now(), link.getExpireTime());
        if (remaining.compareTo(baseTtl) >= 0) {
            return baseTtl;
        }
        // 剩余时间可能刚好在这几行之间变成 0 或负数（判断 isAvailable 时的 now
        // 和这里的 now 不是同一个时刻）。Redis 不接受非正的过期时间，会直接报错，
        // 所以兜一个最小值。
        return remaining.isZero() || remaining.isNegative()
                ? Duration.ofSeconds(1)
                : remaining;
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
