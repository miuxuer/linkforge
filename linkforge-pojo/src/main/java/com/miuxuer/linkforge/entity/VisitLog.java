package com.miuxuer.linkforge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 访问明细实体，对应 {@code t_visit_log}。
 *
 * <p><b>这张表只写不读</b>（除了看板的聚合查询），而且写入量等于跳转量 ——
 * 所以它绝不能出现在跳转的同步链路上。写入路径是：
 * 跳转发事件 → {@code @Async} 监听器落库，主流程完全不等它。
 *
 * <p>主键用雪花（{@link IdType#ASSIGN_ID}）而不是号段模式：这张表的写入量
 * 比短链大几个数量级，为它多走一次 {@code t_id_segment} 的 UPDATE 不值得，
 * 而且它的 id 也不需要转成短码。
 */
@Data
@TableName("t_visit_log")
public class VisitLog {

    /** 主键，雪花算法生成。 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 关联的短链主键。
     *
     * <p>数据库里是 {@code NOT NULL}，必须填。但它没法从跳转链路直接带过来 ——
     * 那条链路的缓存里只有原始链接，命中的时候拿不到 linkId。
     * 所以由异步监听器按短码多查一次（走唯一索引），详见
     * {@code VisitLogServiceImpl#findLinkId}。
     */
    private Long linkId;

    /**
     * 短码。和 {@link #linkId} 重复，是刻意的冗余。
     *
     * <p>看板的聚合查询大多直接按用户的一批短码过滤，
     * 有了冗余就不必每次都 join 回 {@code t_link} ——
     * 这张表的量级比短链表大几个数量级，少一次 join 是实打实的。
     */
    private String shortCode;

    /** 访问者 IP。 */
    private String ip;

    /** 浏览器信息。 */
    private String userAgent;

    /** 来源页。 */
    private String referer;

    /** 省份。需要 IP 库解析，本项目不做，留空。 */
    private String province;

    private LocalDateTime visitTime;
}
