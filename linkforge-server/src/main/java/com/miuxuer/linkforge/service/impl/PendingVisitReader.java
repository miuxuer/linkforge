package com.miuxuer.linkforge.service.impl;

import com.miuxuer.linkforge.constant.RedisKeyConstant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 读取"还没同步到数据库"的访问量增量。
 *
 * <p><b>为什么需要它</b>：访问计数写在 Redis 里，每 5 分钟才批量回写到
 * {@code t_link.visit_count}。所以数据库里那个值最多滞后 5 分钟 ——
 * 用户访问完自己的短链立刻刷新页面，会看到访问量还是 0。
 * 数据没错，但在用户眼里就是"统计坏了"。
 *
 * <p>Redis 里的 {@code linkforge:link:visits:*} 存的正是"自上次同步以来的增量"
 * （定时任务取走之后会删掉 key），所以两边相加就是精确值。
 *
 * <p><b>为什么抽成组件而不是各写各的</b>：访问量在<b>三个地方</b>展示 ——
 * 短链列表、短链详情、数据看板。第一版只在看板里合了增量，
 * 结果就是"看板显示 6 次、列表显示 0 次"，用户一眼就看出对不上。
 * 同一份数据在多处展示时，处理逻辑只留一份，才不会漏。
 */
@Slf4j
@Component
public class PendingVisitReader {

    /** 可选依赖：Redis 不可用时返回空结果，调用方退化成只读数据库值。 */
    @Autowired(required = false)
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 查一批短码各自的未同步增量。
     *
     * @param shortCodes 短码列表
     * @return 短码 → 增量；没有增量的短码不会出现在结果里（调用方用 getOrDefault 兜底）
     */
    public Map<String, Long> read(List<String> shortCodes) {
        if (stringRedisTemplate == null || shortCodes == null || shortCodes.isEmpty()) {
            return Map.of();
        }

        List<String> keys = shortCodes.stream()
                .map(code -> RedisKeyConstant.LINK_VISITS + code)
                .toList();

        List<String> values;
        try {
            // 用 MGET 一次拿回来，不是循环 GET ——
            // 一页 10 条短链循环发 10 次请求，看板/列表打开就要多等 10 个网络往返
            values = stringRedisTemplate.opsForValue().multiGet(keys);
        } catch (Exception e) {
            // Redis 连不上时退化成只显示已同步的部分，而不是让整个列表打不开。
            // 数字偏小可以接受，页面报错不能接受
            log.warn("读取未同步访问增量失败，本次按 0 处理: {}", e.getMessage());
            return Map.of();
        }

        // MGET 正常情况下会按请求顺序返回等长列表。数量对不上的话按索引配对会把
        // 增量配错短码 —— "A 的 5 次算到 B 头上"，数字看着正常但其实全错了。
        // 宁可放弃这部分增量（总量偏小，方向是安全的）
        if (values == null || values.size() != shortCodes.size()) {
            log.warn("MGET 返回的条数和短码数不一致，本次忽略未同步增量: 期望 {}, 实际 {}",
                    shortCodes.size(), values == null ? 0 : values.size());
            return Map.of();
        }

        Map<String, Long> increments = new HashMap<>();
        for (int i = 0; i < shortCodes.size(); i++) {
            // 对不存在的 key，MGET 返回 null —— 定时任务刚把计数取走、
            // key 已删除的那一瞬间就会是这种情况，不能当异常处理
            String value = values.get(i);
            if (value != null) {
                increments.put(shortCodes.get(i), Long.parseLong(value));
            }
        }
        return increments;
    }

    /**
     * 把增量加到数据库里的值上。
     *
     * @param dbVisitCount 数据库里记录的访问量
     * @param shortCode    短码
     * @param increments   {@link #read} 的结果
     */
    public long merge(Long dbVisitCount, String shortCode, Map<String, Long> increments) {
        long base = dbVisitCount == null ? 0L : dbVisitCount;
        return base + increments.getOrDefault(shortCode, 0L);
    }
}
