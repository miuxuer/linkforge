package com.miuxuer.linkforge.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 注册入参。
 *
 * <p>校验注解直接写在 DTO 上，Controller 用 {@code @Valid} 触发 —— 这样
 * Controller 方法体里不用写一长串 if 判断，业务 Service 也可以假定拿到的数据是合法的。
 *
 * <p>注意这些校验只是"挡住手滑"，不能替代服务端的业务校验（比如用户名是否已存在）。
 * 前端也能做同样的校验，但前端校验是为了体验，服务端校验才是为了安全 ——
 * 请求可以绕过前端直接构造。
 */
@Data
public class UserRegisterDTO {

    @NotBlank(message = "用户名不能为空")
    @Size(min = 4, max = 20, message = "用户名长度需在 4-20 位之间")
    @Pattern(regexp = "^[a-zA-Z0-9_]+$", message = "用户名只能包含字母、数字和下划线")
    private String username;

    /**
     * 明文密码，只在这一次请求的生命周期内存在。
     *
     * <p>上限设 32 位：不是安全考虑，而是 BCrypt 在极端长输入下会明显变慢，
     * 不限制的话有人拿 1MB 的密码来注册就是一个廉价的 DoS。
     */
    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 32, message = "密码长度需在 6-32 位之间")
    private String password;

    /** 昵称，可不填，不填时用用户名兜底。 */
    @Size(max = 20, message = "昵称最长 20 位")
    private String nickname;
}
