package com.miuxuer.linkforge.vo;

import com.miuxuer.linkforge.entity.User;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 管理端的用户列表项。
 *
 * <p><b>绝对不能直接返回 {@code User} 实体</b>：里面有 password 字段。
 * 哪怕存的是 BCrypt 密文，把哈希发给前端也等于把爆破材料送出去了 ——
 * 而且用户的密码很可能和别的网站重复。这个 VO 从字段上就杜绝了这种可能。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminUserVO {

    private Long id;

    private String username;

    private String nickname;

    private String avatar;

    /** 0=普通用户 1=管理员。 */
    private Integer role;

    /** 0=禁用 1=启用。 */
    private Integer status;

    private LocalDateTime createTime;

    public static AdminUserVO from(User user) {
        return AdminUserVO.builder()
                .id(user.getId())
                .username(user.getUsername())
                .nickname(user.getNickname())
                .avatar(user.getAvatar())
                .role(user.getRole())
                .status(user.getStatus())
                .createTime(user.getCreateTime())
                .build();
    }
}
