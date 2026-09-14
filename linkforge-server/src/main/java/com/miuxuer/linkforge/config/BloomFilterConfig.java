package com.miuxuer.linkforge.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnels;
import com.miuxuer.linkforge.entity.Link;
import com.miuxuer.linkforge.mapper.LinkMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 布隆过滤器配置 —— 四层防护的第一层，专门挡缓存穿透。
 *
 * <p><b>挡的是什么</b>：有人拿随机短码（比如 {@code /aaaaaa}）疯狂请求。这些短码
 * 在 DB 里根本不存在，所以 Redis 永远不命中，每个请求都穿到 DB —— 这就是缓存穿透。
 *
 * <p><b>为什么不用"缓存空值"</b>：缓存空值要给每个不存在的短码都存一个 key，
 * 攻击者换着花样的短码能把 Redis 内存撑爆。布隆过滤器用固定大小的 bit 数组，
 * 100 万容量 + 1% 假阳性率只要约 1.7 MB，内存不会随攻击增长。代价是不能删除元素。
 *
 * <p><b>读法的关键</b>：说"不存在"就一定不存在（直接拒掉，不查 DB）；说"可能存在"
 * 有 1% 的概率是误判（假阳性）—— 那就放过去走正常链路，多查一次 DB 而已。
 * 方向不能反，反了就会把真实存在的短链误杀成 404。
 */
@Slf4j
@Configuration
@ConditionalOnClass(BloomFilter.class)
public class BloomFilterConfig {

    /** 预期短链总数，决定 bit 数组大小。超了假阳性率会上升。 */
    @Value("${linkforge.bloom.expected-insertions:1000000}")
    private long expectedInsertions;

    /** 可接受的假阳性率。越小越省"误查 DB 的次数"，但内存越大。 */
    @Value("${linkforge.bloom.fpp:0.01}")
    private double fpp;

    /**
     * 构造布隆过滤器，并把库里已有的短码全部灌进去。
     *
     * <p>这一步不能省：布隆过滤器是纯内存结构，重启即清空。不预加载的话，
     * 重启后所有已存在的短码都会被判定为"不存在"，线上表现为全站 404。
     *
     * <p>全量加载只在启动时做一次。短码量大到几百万之后，可以改成只加载热数据，
     * 冷数据靠 Redis/DB 兜底。
     */
    @Bean
    public BloomFilter<String> shortCodeBloomFilter(LinkMapper linkMapper) {
        BloomFilter<String> filter = BloomFilter.create(
                Funnels.stringFunnel(StandardCharsets.UTF_8),
                expectedInsertions,
                fpp);

        List<Link> allLinks = linkMapper.selectList(
                new LambdaQueryWrapper<Link>()
                        .select(Link::getShortCode)
                        .isNotNull(Link::getShortCode));
        for (Link link : allLinks) {
            filter.put(link.getShortCode());
        }

        log.info("布隆过滤器初始化完成，已加载 {} 条短码，预期容量 {}，假阳性率 {}",
                allLinks.size(), expectedInsertions, fpp);
        return filter;
    }
}
