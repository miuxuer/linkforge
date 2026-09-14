package com.miuxuer.linkforge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.miuxuer.linkforge.entity.IdSegment;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 号段 Mapper。
 *
 * <p>核心只有 {@link #allocateSegment(String)} 一个方法 —— 用一条 UPDATE 完成"取号"。
 * MyBatis-Plus 的 {@code updateById} 也能改 {@code max_id}，但那样得先查后改、
 * 两步之间可能被别的实例插队。这里显式写 {@code max_id = max_id + step}，
 * 把"读 + 加 + 写"压成一条语句，靠 MySQL 的行锁保证原子性。
 */
public interface IdSegmentMapper extends BaseMapper<IdSegment> {

    /**
     * 原子取号：{@code UPDATE t_id_segment SET max_id = max_id + step WHERE biz_tag = ?}。
     *
     * <p>返回影响行数 —— 0 表示 {@code biz_tag} 那一行不存在（表没初始化），
     * 调用方据此抛异常，而不是傻乎乎地继续往下走拿一个不存在的号段。
     *
     * <p>注意这条 SQL 只把 {@code max_id} 往前推，取到的号段范围由调用方
     * 用 {@code max_id} 和 {@code step} 自己算，所以紧接着还要 SELECT 一次。
     */
    @Update("UPDATE t_id_segment SET max_id = max_id + step, update_time = NOW() WHERE biz_tag = #{bizTag}")
    int allocateSegment(@Param("bizTag") String bizTag);
}
