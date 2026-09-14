package com.miuxuer.linkforge.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.miuxuer.linkforge.entity.Link;
import com.miuxuer.linkforge.entity.VisitLog;
import com.miuxuer.linkforge.event.VisitEvent;
import com.miuxuer.linkforge.mapper.LinkMapper;
import com.miuxuer.linkforge.mapper.VisitLogMapper;
import com.miuxuer.linkforge.service.VisitLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class VisitLogServiceImpl implements VisitLogService {

    private final VisitLogMapper visitLogMapper;
    private final LinkMapper linkMapper;

    @Override
    public void record(VisitEvent event) {
        Long linkId = findLinkId(event.shortCode());
        if (linkId == null) {
            // 短链已经被删了（逻辑删除之后 @TableLogic 会把它过滤掉）。
            // 这种情况下明细记下来也没有归属，而且 link_id 是 NOT NULL 的列，
            // 硬插进去会直接报 DataIntegrityViolationException
            log.debug("短链已不存在，跳过访问明细: shortCode={}", event.shortCode());
            return;
        }

        VisitLog visitLog = new VisitLog();
        visitLog.setLinkId(linkId);
        visitLog.setShortCode(event.shortCode());
        visitLog.setIp(event.ip());
        visitLog.setUserAgent(event.userAgent());
        visitLog.setReferer(event.referer());
        // 用服务端时间而不是客户端时间：客户端时钟可以随便改，
        // 用它做统计的话趋势图上会出现"未来"的数据点
        visitLog.setVisitTime(LocalDateTime.now());

        visitLogMapper.insert(visitLog);
    }

    /**
     * 按短码查短链主键。
     *
     * <p><b>为什么不从跳转链路直接带过来</b>：那条链路的缓存里只有原始链接，
     * 命中的时候拿不到 linkId。为了传这个值就得改缓存结构，
     * 而缓存结构是整条跳转链路的核心，动它的风险远大于收益。
     *
     * <p>所以退一步，在这里多查一次。代价可以接受：走的是
     * {@code t_link.short_code} 上的唯一索引，而且整个过程在异步线程里，
     * 对跳转的响应时间没有任何影响。
     *
     * <p>真要优化的话，可以在回填缓存时把 linkId 一并存进去
     * （缓存值改成 {@code linkId|url}），那样这里就省掉一次查询 ——
     * 但那要改 {@code LinkServiceImpl} 的缓存读写和解析，属于后续优化项。
     */
    private Long findLinkId(String shortCode) {
        Link link = linkMapper.selectOne(new LambdaQueryWrapper<Link>()
                // 只查 id 这一列：明细写库是高频操作，少传输几个字段是几个
                .select(Link::getId)
                .eq(Link::getShortCode, shortCode));
        return link == null ? null : link.getId();
    }
}
