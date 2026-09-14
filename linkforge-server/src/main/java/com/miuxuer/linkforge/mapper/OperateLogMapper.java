package com.miuxuer.linkforge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.miuxuer.linkforge.entity.OperateLog;

/**
 * 操作日志数据访问接口。
 *
 * <p>没有继承 {@code AutoFillMapper}：{@code t_operate_log} 是审计表，
 * 没有 create_time / update_time 这类"审计表不该有的"公共字段 ——
 * 它自己记的 operate_time / operate_user 就是那些字段想表达的东西，
 * 再套一层自动填充是重复的。
 */
public interface OperateLogMapper extends BaseMapper<OperateLog> {
}
