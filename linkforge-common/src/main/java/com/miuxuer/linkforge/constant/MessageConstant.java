package com.miuxuer.linkforge.constant;

/**
 * 提示文案。
 *
 * <p>集中放一处的理由：同一句话可能被多处用到（比如"用户名已存在"注册时用、
 * 管理员改资料时也用），散落在各个类里改一处漏一处，前端拿到的提示就不一致了。
 */
public final class MessageConstant {

    // ---------- 注册 ----------

    /** 用户名已被占用。 */
    public static final String USERNAME_ALREADY_EXISTS = "用户名已存在";

    // ---------- 登录与认证 ----------

    /**
     * 登录失败。
     *
     * <p><b>"用户名不存在"和"密码错误"故意共用这一句</b>。如果分开提示，
     * 攻击者可以拿一个字典挨个试用户名：返回"密码错误"说明这个用户名存在，
     * 返回"用户不存在"说明不存在 —— 这样就白送了一份有效用户名列表，
     * 后面只需要专心爆破密码。这个技巧叫用户名枚举（user enumeration）。
     */
    public static final String LOGIN_FAILED = "用户名或密码错误";

    /** 账号被管理员禁用。 */
    public static final String ACCOUNT_DISABLED = "账号已被禁用，请联系管理员";

    /** 未携带 token 或 token 无效/过期。 */
    public static final String NOT_LOGGED_IN = "未登录或登录已过期，请重新登录";

    /** 已登录但角色不够（普通用户访问管理端）。 */
    public static final String NO_PERMISSION = "没有访问权限";

    private MessageConstant() {
    }
}
