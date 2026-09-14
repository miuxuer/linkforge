package com.miuxuer.linkforge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.miuxuer.linkforge.entity.IdSegment;
import com.miuxuer.linkforge.mapper.IdSegmentMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 号段模式分布式 ID 生成器。
 *
 * <p><b>原理</b>：不从数据库一个号一个号地拿，而是一次拿一整段（{@code step} 个），
 * 在内存里用 {@link AtomicLong} 分发，用完了再去拿下一段。取号段的代价被摊薄到
 * step 分之一，DB 压力跟 QPS 基本脱钩。
 *
 * <p><b>和其它方案的取舍</b>：
 *
 * <ul>
 *   <li>vs 数据库自增：自增每插入一行就要访问一次 DB；号段一批才访问一次，
 *       写入 QPS 高一个数量级
 *   <li>vs 雪花算法：雪花完全不依赖 DB，但依赖机器时钟 —— 时钟回拨会发出重复 id，
 *       得额外做回拨检测；号段不依赖时钟，实现简单，代价是依赖 DB 且重启会浪费
 *       一段没用完的号（短链场景 id 量级不大，可接受）
 * </ul>
 *
 * <p><b>并发模型</b>：号段没耗尽时走 {@link AtomicLong#getAndIncrement()} 无锁分发；
 * 只有号段用尽的那一刻才抢一次 {@code synchronized}。取号段是低频操作，锁竞争可以忽略。
 *
 * <p><b>多业务隔离</b>：每个 {@code biz_tag} 有独立的号段缓冲（见 {@link SegmentBuffer}），
 * 用户表和短链表各取各的号，互不影响 —— 用户表 id 狂涨不会带着短链 id 一起涨，
 * 短链 id 转 Base62 后的长度才好控制。
 */
@Slf4j
@Component
public class IdSegmentManager {

    /** 用户表取号用，对应 {@code t_id_segment} 里 {@code biz_tag = 'user'} 那一行。 */
    public static final String BIZ_TAG_USER = "user";

    /** 短链表取号用。 */
    public static final String BIZ_TAG_LINK = "link";

    private final IdSegmentMapper idSegmentMapper;

    /** biz_tag -> 该业务的号段缓冲。ConcurrentHashMap 保证只在首次访问时初始化一次。 */
    private final Map<String, SegmentBuffer> buffers = new ConcurrentHashMap<>();

    public IdSegmentManager(IdSegmentMapper idSegmentMapper) {
        this.idSegmentMapper = idSegmentMapper;
    }

    /**
     * 取下一个 id。
     *
     * @param bizTag 业务标识，取值见 {@link #BIZ_TAG_USER} / {@link #BIZ_TAG_LINK}
     */
    public long getNextId(String bizTag) {
        SegmentBuffer buffer = buffers.computeIfAbsent(bizTag, tag -> new SegmentBuffer());

        // 先快照号段上限，再递增游标 —— 这两步的顺序不能反。
        // 反过来的话，本线程可能拿的是旧号段的游标值，却读到别线程刚换好的新上限，
        // 于是"旧值 <= 新上限"成立、这个 id 被提前发出去；而新号段的游标稍后推进到
        // 同一个数值时还会再发一次 —— 重复 id。先读上限则上限一定不晚于游标，
        // 比较始终落在同一个号段内。
        long segmentMax = buffer.maxIdInSegment;

        // 快路径：号段内直接递增返回，无锁
        long current = buffer.cursor.getAndIncrement();
        if (current <= segmentMax) {
            return current;
        }

        // 慢路径：号段耗尽。这里可能已经有别的线程抢到锁并换好了新号段，
        // 所以在锁内要再判断一次（Double-check），避免重复取号段造成 id 浪费。
        synchronized (buffer.lock) {
            if (buffer.cursor.get() > buffer.maxIdInSegment) {
                allocateNextSegment(bizTag, buffer);
            }
        }
        return buffer.cursor.getAndIncrement();
    }

    /**
     * 从 DB 取下一段号，并把游标挪到新号段起点。
     *
     * <p>必须在 {@code buffer.lock} 内调用 —— 它做的是"改游标 + 改上限"两步操作，
     * 并发进来会把号段范围搞乱。
     */
    private void allocateNextSegment(String bizTag, SegmentBuffer buffer) {
        // 1. 原子推进 max_id，MySQL 行锁保证并发安全
        int rows = idSegmentMapper.allocateSegment(bizTag);
        if (rows == 0) {
            throw new IllegalStateException(
                    "号段分配失败：biz_tag=" + bizTag + " 的记录不存在，请检查 t_id_segment 表初始化");
        }

        // 2. 读回推进后的 max_id 和 step，算出这段号的范围
        IdSegment segment = idSegmentMapper.selectOne(
                new LambdaQueryWrapper<IdSegment>().eq(IdSegment::getBizTag, bizTag));
        long newMaxId = segment.getMaxId();
        long step = segment.getStep();

        // 3. 号段是闭区间 [newMaxId - step + 1, newMaxId]
        long newMin = newMaxId - step + 1;
        buffer.cursor.set(newMin);
        buffer.maxIdInSegment = newMaxId;
        log.info("号段分配成功: biz_tag={}, [{}, {}], step={}", bizTag, newMin, newMaxId, step);
    }

    /**
     * 单个 biz_tag 的号段状态。
     *
     * <p>{@code cursor} 用 AtomicLong（高频、无锁），{@code maxIdInSegment} 用 volatile
     * （低频写、高频读，只要保证可见性）。{@code lock} 只保护"换号段"这一件事。
     */
    private static final class SegmentBuffer {

        /** 下一个可用的 id。 */
        private final AtomicLong cursor = new AtomicLong(1);

        /** 当前号段的上限（含）。0 表示还没取过号段，保证第一次调用一定走取号分支。 */
        private volatile long maxIdInSegment = 0;

        /** 换号段专用锁，和读游标无关。 */
        private final Object lock = new Object();
    }
}
