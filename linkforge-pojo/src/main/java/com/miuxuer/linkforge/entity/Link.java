package com.miuxuer.linkforge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 短链映射实体，对应 {@code t_link}。
 *
 * <p><b>为什么主键是 {@link IdType#INPUT}</b>：id 由 {@code IdSegmentManager} 用号段模式
 * 提前分配好，再转成 Base62 当短码用。短码必须和 id 一一对应、且不暴露自增规律，
 * 所以 id 不能交给数据库自增 —— 那样得先 INSERT 拿到自增 id、再 UPDATE 回填短码，
 * 变成两步写库，并发下还要处理"插了但没回填"的中间状态。
 *
 * <p>字段的下划线/驼峰转换由 MyBatis-Plus 按全局配置处理，这里用驼峰即可。
 */
@Data
@TableName("t_link")
public class Link {

    /** 主键，号段模式分配。 */
    @TableId(type = IdType.INPUT)
    private Long id;

    /** 短码，Base62 编码，全局唯一。 */
    private String shortCode;

    /** 原始长链接。 */
    private String originalUrl;

    /** 累计访问量。 */
    private Long visitCount;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    /** 逻辑删除标记，MyBatis-Plus 自动处理，查询时不用手写 where deleted = 0。 */
    @TableLogic
    private Integer deleted;
}
