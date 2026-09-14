package com.miuxuer.linkforge.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 登录结果。
 *
 * <p><b>为什么不直接返回 {@code User} 实体</b>：实体里有 password 字段（哪怕存的是
 * 密文）。直接把实体丢给前端，等于把密码哈希送出去 —— 拿到哈希就能离线慢慢爆破，
 * 而且用户的密码很可能和别的网站重复。VO 只挑该给前端看的字段，
 * 从结构上杜绝"哪天手滑多加了个字段就泄露了"。
 *
 * <p>实体和 VO 分开还有一层好处：数据库加字段不会自动改变接口返回结构，
 * 接口的稳定性不受表结构影响。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserLoginVO {

    private Long id;

    private String username;

    private String nickname;

    private String avatar;

    /** 角色，前端据此决定要不要显示管理端入口。 */
    private Integer role;

    /** JWT 令牌，之后所有需要登录的接口都要带上它。 */
    private String token;
}
