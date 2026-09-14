package com.miuxuer.linkforge.task;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.miuxuer.linkforge.constant.RedisKeyConstant;
import com.miuxuer.linkforge.entity.Link;
import com.miuxuer.linkforge.mapper.LinkMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

/**
 * 访问计数定时同步任务：把 Redis 里累加的访问量批量写回数据库。
 *
 * <p><b>为什么不在跳转时直接 UPDATE 数据库</b>：跳转是读多写多的场景，
 * 每次访问都发一条 UPDATE，DB 的磁盘 IO 和行锁立刻成为瓶颈，而且同一条短链
 * 被并发访问时会在这行上排队。改成 Redis {@code INCR}（单线程模型，天然原子，
 * 单机可扛十万级 QPS）+ 定时批量回写，写入压力被摊薄到几百分之一。
 *
 * <p>代价是可能丢一点数据：GET 和 DEL 之间进来的新计数会连同被删的 key 一起丢掉；
 * 服务在两次同步之间崩溃也会丢这一段。访问量不是资金数据，这个取舍可以接受。
 * 要更强保证就得上 RabbitMQ（持久化 + ACK）。
 *
 * <p><b>关于 KEYS 和 SCAN</b>：不能用 {@code KEYS} 扫 key —— Redis 是单线程的，
 * {@code KEYS} 会遍历整个键空间并阻塞所有其它命令，键一多整个缓存层都会卡住。
 * {@code SCAN} 是游标式增量遍历，每次只返回一小批，不阻塞。
 * 代价是它只保证"遍历期间一直都在的 key 一定会被返回"，可能重复返回，所以要去重，
 * 而且不适合用来做"精确的一次性快照"——这里只是拿一遍待同步的 key，够用。
 */
@Slf4j
@Component
public class VisitCountSyncTask {

    /** 每批扫描返回的 key 数量上限，给 Redis 一个"每次少干点活"的提示。 */
    private static final int SCAN_BATCH_SIZE = 500;

    /** 同步周期：5 分钟。 */
    private static final long SYNC_INTERVAL_MS = 300_000L;

    private final LinkMapper linkMapper;

    /** 可选依赖：Redis 不可用时跳过同步，不影响服务。 */
    @Autowired(required = false)
    private StringRedisTemplate stringRedisTemplate;

    public VisitCountSyncTask(LinkMapper linkMapper) {
        this.linkMapper = linkMapper;
    }

    @Scheduled(fixedRate = SYNC_INTERVAL_MS)
    public void syncVisitCounts() {
        if (stringRedisTemplate == null) {
            return;
        }

        Set<String> keys;
        try {
            keys = scanVisitKeys();
        } catch (Exception e) {
            // Redis 连不上时跳过这一轮。定时任务每 5 分钟跑一次，
            // 不接住的话每次都会打一整条异常堆栈，把日志刷得没法看
            log.warn("扫描访问计数失败，跳过本轮同步: {}", e.getMessage());
            return;
        }
        if (keys.isEmpty()) {
            return;
        }

        log.info("开始同步访问计数，共 {} 个 key", keys.size());
        int synced = 0;

        for (String key : keys) {
            // key 形如 linkforge:link:visits:abc123，去掉前缀就是短码
            String shortCode = key.substring(RedisKeyConstant.LINK_VISITS.length());

            String countStr = stringRedisTemplate.opsForValue().get(key);
            if (countStr == null) {
                continue;
            }

            long count;
            try {
                count = Long.parseLong(countStr);
            } catch (NumberFormatException e) {
                // key 被人手工改成非数字了，删掉免得每次同步都撞一遍
                log.warn("访问计数不是合法数字，已丢弃: key={}, value={}", key, countStr);
                stringRedisTemplate.delete(key);
                continue;
            }
            if (count <= 0) {
                continue;
            }

            // 先删 Redis 再写库：万一写库失败，丢的是这一段计数（可接受）；
            // 反过来先写库再删，中途崩溃就会重复累加，访问量虚高。
            stringRedisTemplate.delete(key);

            linkMapper.update(null, new LambdaUpdateWrapper<Link>()
                    .eq(Link::getShortCode, shortCode)
                    // 用 {0} 占位符而不是字符串拼接：拼接出来的 SQL 一旦掺进非数字内容
                    // 就是注入口子。这里 count 是 long，风险不大，但习惯要养好。
                    .setSql("visit_count = visit_count + {0}", count));
            synced++;
        }

        log.info("访问计数同步完成: {} 条短链更新", synced);
    }

    /**
     * 用 SCAN 游标扫出所有访问计数 key。
     *
     * <p>用 {@code HashSet} 收集是因为 SCAN 可能重复返回同一个 key
     * （扩缩容时尤其明显），不去重的话同一条短链会被同步两次。
     */
    private Set<String> scanVisitKeys() {
        Set<String> keys = new HashSet<>();
        ScanOptions options = ScanOptions.scanOptions()
                .match(RedisKeyConstant.LINK_VISITS + "*")
                .count(SCAN_BATCH_SIZE)
                .build();

        // Cursor 持有 Redis 连接，必须关掉，否则连接池会被耗干
        try (Cursor<String> cursor = stringRedisTemplate.scan(options)) {
            while (cursor.hasNext()) {
                keys.add(cursor.next());
            }
        }
        return keys;
    }
}
