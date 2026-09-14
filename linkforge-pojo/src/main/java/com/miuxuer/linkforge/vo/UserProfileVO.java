package com.miuxuer.linkforge.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 当前登录用户的资料。
 *
 * <p>和 {@code UserLoginVO} 的区别在于没有 token —— 这里查的是"已经登录之后"的信息，
 * 不需要再发一次令牌。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserProfileVO {

    private Long id;

    private String username;

    private String nickname;

    private String avatar;

    private Integer role;
}
