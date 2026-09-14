package com.miuxuer.linkforge.constant;

/**
 * 公共字段的 setter 方法名。
 *
 * <p>切面靠反射调用这些方法给实体赋值，方法名写错的话 {@code getMethod} 会抛
 * {@code NoSuchMethodException}。集中定义成常量，配合 IDE 的重构功能，
 * 改实体字段名时能一起改掉，不会出现"改了实体忘了改切面"。
 */
public final class AutoFillConstant {

    public static final String SET_CREATE_TIME = "setCreateTime";

    public static final String SET_UPDATE_TIME = "setUpdateTime";

    public static final String SET_CREATE_USER = "setCreateUser";

    public static final String SET_UPDATE_USER = "setUpdateUser";

    private AutoFillConstant() {
    }
}
