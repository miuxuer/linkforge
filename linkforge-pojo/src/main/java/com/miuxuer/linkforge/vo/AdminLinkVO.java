package com.miuxuer.linkforge.vo;

import com.miuxuer.linkforge.entity.Link;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 管理端的短链列表项。
 *
 * <p>比用户端的 {@code LinkVO} 多了 {@code userId} 和 {@code username} ——
 * 管理员需要知道每条链归谁，出问题时才找得到人。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminLinkVO {

    private Long id;

    private String shortCode;

    private String shortUrl;

    private String originalUrl;

    private String title;

    /** 归属用户 id。 */
    private Long userId;

    /**
     * 归属用户名。
     *
     * <p>可能是 null：用户被逻辑删除之后查不到，或者短链的 user_id 本身为空
     * （阶段 3 之前创建的历史数据）。前端要能容忍这个字段为空。
     */
    private String username;

    private Integer status;

    private Long visitCount;

    private LocalDateTime expireTime;

    private LocalDateTime createTime;

    /**
     * @param link     短链实体
     * @param username 归属用户名，查不到时传 null
     * @param domain   短链域名，用来拼 shortUrl
     */
    public static AdminLinkVO from(Link link, String username, String domain) {
        return AdminLinkVO.builder()
                .id(link.getId())
                .shortCode(link.getShortCode())
                .shortUrl(domain + "/" + link.getShortCode())
                .originalUrl(link.getOriginalUrl())
                .title(link.getTitle())
                .userId(link.getUserId())
                .username(username)
                .status(link.getStatus())
                .visitCount(link.getVisitCount())
                .expireTime(link.getExpireTime())
                .createTime(link.getCreateTime())
                .build();
    }
}
