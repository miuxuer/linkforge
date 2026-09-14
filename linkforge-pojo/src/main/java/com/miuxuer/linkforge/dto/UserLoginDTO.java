package com.miuxuer.linkforge.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 登录入参。
 *
 * <p>这里只校验"非空"，<b>不</b>校验用户名格式和密码长度。原因是注册规则将来可能变
 * （比如放宽到 3 位），而老用户的密码是按旧规则设的 —— 如果登录也按新规则校验，
 * 老用户会被自己的账号锁在门外，且错误提示是"密码长度不符合要求"，
 * 完全看不出真正的问题。登录只该判断"对不对"，不该判断"合不合规"。
 */
@Data
public class UserLoginDTO {

    @NotBlank(message = "用户名不能为空")
    private String username;

    @NotBlank(message = "密码不能为空")
    private String password;
}
