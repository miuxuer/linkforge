package com.miuxuer.linkforge.annotation;

import com.miuxuer.linkforge.enumeration.OperationType;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记需要自动填充公共字段的 Mapper 方法。
 *
 * <p>用法见 {@code AutoFillMapper}。约定：<b>被注解方法的第一个参数必须是实体对象</b>，
 * 切面会给它填 create_time / create_user / update_time / update_user。
 *
 * <p>这个"第一个参数是实体"的隐式约定是本方案最脆弱的地方 —— 参数顺序写反了不会报错，
 * 只是字段默默没填上，而且只在数据库里看得出来。所以调用方统一走
 * {@code AutoFillMapper} 提供的 default 方法，不要自己拼参数顺序。
 *
 * <p>{@code @Retention(RUNTIME)} 是必须的：切面在运行时通过反射读取这个注解，
 * 默认的 CLASS 级别在运行期读不到。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AutoFill {

    /** 本次操作的数据库动作类型。 */
    OperationType value();
}
