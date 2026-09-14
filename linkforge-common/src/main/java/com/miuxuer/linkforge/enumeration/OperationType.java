package com.miuxuer.linkforge.enumeration;

/**
 * 数据库操作类型，供 {@code @AutoFill} 区分该填哪些公共字段。
 *
 * <p>插入时要填四个字段（create_time / create_user / update_time / update_user），
 * 更新时只填两个（update_time / update_user）—— create_* 是"谁在什么时候创建了这条记录"，
 * 记录一旦创建就不该再变，更新时覆盖它等于篡改历史。
 *
 * <p>用枚举而不是 0/1 常量：注解属性写成 {@code @AutoFill(OperationType.INSERT)} 时，
 * IDE 能补全、编译期能校验；写成 {@code @AutoFill(0)} 就只能靠人记住 0 是插入还是更新。
 */
public enum OperationType {

    /** 插入。填四个字段。 */
    INSERT,

    /** 更新。只填 update_time / update_user。 */
    UPDATE
}
