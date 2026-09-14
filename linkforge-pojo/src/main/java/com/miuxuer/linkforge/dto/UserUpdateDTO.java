package com.miuxuer.linkforge.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 修改个人资料入参。
 *
 * <p><b>里面没有 username、password、role、status，也不该有。</b>
 * 用户名是登录凭据、角色决定权限、状态是管理员才能改的 ——
 * 这些一旦让用户自己提交，就是"我自己把自己升级成管理员"。
 * 改密码要走单独的接口（要验证原密码），改角色状态属于管理端。
 *
 * <p>和 {@code LinkUpdateDTO} 一样是"整条覆盖"语义：没传的字段会被写成 null。
 * 所以昵称必填（前端表单本来就预填了），头像可以为 null —— 那正是"删掉头像"。
 */
@Data
public class UserUpdateDTO {

    @NotBlank(message = "昵称不能为空")
    @Size(max = 20, message = "昵称最长 20 个字符")
    private String nickname;

    /** 头像地址，一般来自 {@code /api/upload}。传 null 表示清空头像。 */
    @Size(max = 500, message = "头像地址最长 500 个字符")
    private String avatar;
}
