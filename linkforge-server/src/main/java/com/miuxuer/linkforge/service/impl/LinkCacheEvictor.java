package com.miuxuer.linkforge.service.impl;

import com.miuxuer.linkforge.constant.RedisKeyConstant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 短链跳转缓存的清理器。
 *
 * <p>单独抽出来，是因为"改状态 / 改过期时间 / 删除"这些操作都要清缓存，
 * 而它们分散在用户端（{@code LinkServiceImpl}）和管理端
 * （{@code AdminLinkServiceImpl}）两个服务里。各写一遍的话，
 * 迟早有一处漏掉 —— 而漏掉的后果是"停用/删除没生效"：
 * 用户以为操作成功了，短链其实还在跳，因为缓存里那条记录还在。
 *
 * <p><b>只清缓存，不动布隆过滤器。</b> 布隆是"可能存在"的数据结构，删不掉元素。
 * 短码留在布隆里完全没问题：它只是让请求继续往下走，最终由缓存/数据库判断有效性，
 * 不会导致已停用的短链被放行。
 */
@Slf4j
@Component
public class LinkCacheEvictor {

    /** 可选依赖：Redis 不可用时什么都不用清（本来就没有缓存）。 */
    @Autowired(required = false)
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 清掉某个短码的跳转缓存。
     *
     * @param shortCode 短码，为空时静默返回
     */
    public void evict(String shortCode) {
        if (stringRedisTemplate == null || !StringUtils.hasText(shortCode)) {
            return;
        }
        stringRedisTemplate.delete(RedisKeyConstant.LINK_CACHE + shortCode);
    }
}
