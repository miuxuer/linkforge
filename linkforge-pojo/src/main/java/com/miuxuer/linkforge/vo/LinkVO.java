package com.miuxuer.linkforge.vo;

import com.miuxuer.linkforge.entity.Link;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 短链信息出参。
 *
 * <p>不直接返回 {@code Link} 实体：实体里有 {@code deleted}、{@code createUser}
 * 这类纯粹的内部字段，返回给前端只是噪音；更要紧的是实体字段会随表结构变化，
 * 接口形状跟着数据库改是件很糟的事 —— 加一列就可能把内部字段泄给前端。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LinkVO {

    private Long id;

    private String shortCode;

    /** 完整短链接（域名 + 短码），前端直接拿去展示或复制。 */
    private String shortUrl;

    private String originalUrl;

    private String title;

    private String remark;

    /** 见 {@code StatusConstant}：0=停用 1=启用。 */
    private Integer status;

    private Long visitCount;

    /** 为 null 表示永不过期。 */
    private LocalDateTime expireTime;

    private LocalDateTime createTime;

    /**
     * 由实体转换。
     *
     * <p>{@code shortUrl} 需要域名，而域名是配置项不是实体字段，所以从外面传进来 ——
     * VO 自己不去读配置，那样它就没法在单元测试里被单独构造了。
     */
    public static LinkVO from(Link link, String domain) {
        return LinkVO.builder()
                .id(link.getId())
                .shortCode(link.getShortCode())
                .shortUrl(domain + "/" + link.getShortCode())
                .originalUrl(link.getOriginalUrl())
                .title(link.getTitle())
                .remark(link.getRemark())
                .status(link.getStatus())
                .visitCount(link.getVisitCount())
                .expireTime(link.getExpireTime())
                .createTime(link.getCreateTime())
                .build();
    }
}
