package com.miuxuer.linkforge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.miuxuer.linkforge.entity.VisitLog;

/**
 * 访问明细数据访问接口。
 *
 * <p>看板需要按时间分组统计（{@code GROUP BY DATE(visit_time)}）和 Top N 聚合，
 * 这些用条件构造器写不出来，得写自定义 SQL —— 见阶段 6 的看板接口。
 */
public interface VisitLogMapper extends BaseMapper<VisitLog> {
}
