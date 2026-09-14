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

    /** 标题，方便用户在自己的列表里认出来。 */
    private String title;

    /** 备注。 */
    private String remark;

    /**
     * 归属用户 id。
     *
     * <p><b>多租户隔离的命根子。</b> 所有面向用户的查询都必须带上这个条件，
     * 否则 A 用户就能通过改 URL 里的 id 看到 B 用户的短链（水平越权）。
     * 具体做法见 {@code LinkServiceImpl}：查询条件里的 userId 一律从
     * {@code CurrentHolder} 取，接口签名上不给调用方传 userId 的机会。
     */
    private Long userId;

    /** 累计访问量，由定时任务从 Redis 回写。 */
    private Long visitCount;

    /** 状态：见 {@code StatusConstant}。停用后跳转返回 404。 */
    private Integer status;

    /**
     * 过期时间。{@code null} 表示永不过期。
     *
     * <p>不写 "9999-12-31" 这种哨兵值：那样每次判断都要记得排除这个特殊值，
     * 而且真到了那天就全过期了。null 语义明确，SQL 里也是 {@code expire_time is null} 一行。
     */
    private LocalDateTime expireTime;

    /** 二维码中间的 logo URL。 */
    private String qrLogo;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Long createUser;

    private Long updateUser;

    /** 逻辑删除标记，MyBatis-Plus 自动处理，查询时不用手写 where deleted = 0。 */
    @TableLogic
    private Integer deleted;
}
