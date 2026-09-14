package com.miuxuer.linkforge.constant;

/**
 * 用户相关的枚举值。
 *
 * <p>角色和状态在数据库里是 TINYINT。代码里如果到处写 {@code if (user.getStatus() == 1)}
 * 这种字面量，过两周就没人记得 1 是启用还是禁用了（尤其是有些表 0=启用、有些表 0=禁用
 * 的时候）。集中定义成常量，让调用处自带说明。
 *
 * <p>没做成 enum 是因为这几组值要和数据库的 TINYINT 直接对应，
 * 中间再套一层转换反而啰嗦。
 */
public final class UserConstant {

    /** 普通用户。 */
    public static final int ROLE_USER = 0;

    /** 管理员，可以访问 {@code /api/admin/**}。 */
    public static final int ROLE_ADMIN = 1;

    /** 账号已禁用，登录会被拒绝。 */
    public static final int STATUS_DISABLED = 0;

    /** 账号正常。 */
    public static final int STATUS_ENABLED = 1;

    private UserConstant() {
    }
}
