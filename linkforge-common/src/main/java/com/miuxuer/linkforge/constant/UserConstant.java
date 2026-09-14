package com.miuxuer.linkforge.constant;

/**
 * 用户相关的枚举值。
 *
 * <p>角色在数据库里是 TINYINT。代码里如果到处写 {@code if (user.getRole() == 1)}
 * 这种字面量，过两周就没人记得 1 是管理员还是普通用户了。
 *
 * <p>状态（启用/禁用）不在这里定义 —— 那是用户和短链共用的约定，
 * 见 {@link StatusConstant}，别在两处各写一份。
 */
public final class UserConstant {

    /** 普通用户。 */
    public static final int ROLE_USER = 0;

    /** 管理员，可以访问 {@code /api/admin/**}。 */
    public static final int ROLE_ADMIN = 1;

    private UserConstant() {
    }
}
