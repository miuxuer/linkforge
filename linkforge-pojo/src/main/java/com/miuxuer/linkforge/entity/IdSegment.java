package com.miuxuer.linkforge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 号段发号表实体，对应 {@code t_id_segment}。
 *
 * <p>一行代表一个业务方的发号状态：{@code max_id} 是"已经发给应用的最大 id"，
 * 应用每次取号时让 {@code max_id += step}，就把这一整段独占下来了。
 * 主键是业务标识而不是自增 id —— 这张表就是靠 {@code biz_tag} 分行的。
 */
@Data
@TableName("t_id_segment")
public class IdSegment {

    /**
     * 业务标识，如 {@code user} / {@code link}。
     *
     * <p>必须写成 {@link IdType#INPUT}（由应用自己赋值）。MyBatis-Plus 的全局默认
     * 是 {@code ASSIGN_ID}（雪花算法），不显式指定的话，insert 时 MP 可能会用自己
     * 生成的雪花字符串覆盖掉我们传进来的 biz_tag。
     */
    @TableId(value = "biz_tag", type = IdType.INPUT)
    private String bizTag;

    /** 当前已分配出去的最大 id。 */
    private Long maxId;

    /** 每次分配的号段长度。 */
    private Integer step;

    private LocalDateTime updateTime;
}
