package com.miuxuer.linkforge.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记需要记录操作日志的方法。
 *
 * <p>打在 Controller 方法上。切面会记录：操作人、时间、类名、方法名、入参、返回值、
 * 耗时、成功/失败、异常信息，落到 {@code t_operate_log} 表。
 *
 * <p>做成只有标记、没有属性的注解：操作是哪一类，看类名和方法名就够了
 * （{@code UserController.register} 比任何自定义描述都准确），
 * 再加个描述字段反而要人去维护一份和代码不同步的文案。
 *
 * <p><b>不要打在查询方法上</b>：操作日志是用来追溯"谁改了什么"的。
 * 查询既不修改数据，量又大，全记下来只会让日志表迅速膨胀、淹没有用的记录。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface OperateLog {
}
