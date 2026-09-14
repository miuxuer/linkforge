package com.miuxuer.linkforge.constant;

/**
 * 启用 / 禁用状态的通用值。
 *
 * <p>项目里"状态"这一类字段统一用 {@code 0=禁用 1=启用}：用户、短链都是这个约定。
 *
 * <p>做成公共常量而不是每张表各定义一份：这样只有一处可能出现笔误，
 * 也避免了"用户表 1 是启用、短链表 1 是禁用"这种最阴险的 bug ——
 * 那种情况下代码看起来完全正常，只有行为是反的。
 *
 * <p>{@code 0=禁用} 而不是反过来（0=启用）是有意的：数据库列通常 NOT NULL DEFAULT 0，
 * 万一插入时漏了赋值，落到 0 上得到的是"禁用"—— 一个更安全的默认值。
 * 反过来漏赋值就变成了"默认全部开放"。
 */
public final class StatusConstant {

    /** 禁用。用户被禁用后登录被拒；短链被停用后跳转返回 404。 */
    public static final int DISABLED = 0;

    /** 启用。 */
    public static final int ENABLED = 1;

    private StatusConstant() {
    }
}
