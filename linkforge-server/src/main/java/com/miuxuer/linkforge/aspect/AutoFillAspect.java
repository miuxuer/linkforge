package com.miuxuer.linkforge.aspect;

import com.miuxuer.linkforge.annotation.AutoFill;
import com.miuxuer.linkforge.constant.AutoFillConstant;
import com.miuxuer.linkforge.context.CurrentHolder;
import com.miuxuer.linkforge.enumeration.OperationType;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.time.LocalDateTime;

/**
 * 公共字段自动填充切面。
 *
 * <p>给 {@code @AutoFill} 标注的 Mapper 方法，在 SQL 执行<b>之前</b>把
 * create_time / create_user / update_time / update_user 填进实体。
 * 用 {@code @Before} 而不是 {@code @Around}：只需要改参数，不需要干预方法的执行和返回值，
 * 前置通知是最轻的选择。
 *
 * <p><b>为什么用 AOP 而不是 MyBatis-Plus 的 {@code MetaObjectHandler}</b>：
 * 后者更简洁、没有"第一个参数必须是实体"这种隐式约定，但只对 MyBatis-Plus 自己的
 * insert/update 生效。用 AOP 的话填充逻辑和 ORM 解耦，能拿到任意参数，
 * 将来换成别的持久化方式也不用重写。代价就是上面那条隐式约定，以及每次写库多一次反射。
 * 两种方案都能用，选 AOP 是因为它不绑定框架。
 */
@Slf4j
@Aspect
@Component
public class AutoFillAspect {

    /**
     * 切点：拦截 mapper 包下所有被 {@code @AutoFill} 标注的方法。
     *
     * <p><b>{@code mapper..*} 里的两个点不能少。</b> 单个 {@code *} 只匹配一层包，
     * 写成 {@code mapper.*.*(..)} 的话，一旦 Mapper 被分到子包（比如
     * {@code mapper.user.UserMapper}），切面会<b>静默失效</b> —— 不报错、不警告，
     * 只是字段永远填不上，等到看数据库发现 create_time 全是 null 才反应过来。
     * {@code ..} 表示递归所有子包。
     */
    @Pointcut("execution(* com.miuxuer.linkforge.mapper..*.*(..)) "
            + "&& @annotation(com.miuxuer.linkforge.annotation.AutoFill)")
    public void autoFillPointCut() {
    }

    @Before("autoFillPointCut()")
    public void autoFill(JoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        AutoFill autoFill = signature.getMethod().getAnnotation(AutoFill.class);
        if (autoFill == null) {
            return;
        }

        Object[] args = joinPoint.getArgs();
        if (args == null || args.length == 0 || args[0] == null) {
            // 没传实体就没什么可填的。静默返回而不是抛异常：
            // 填充失败不应该让业务写库跟着失败
            return;
        }

        Object entity = args[0];
        LocalDateTime now = LocalDateTime.now();
        Long currentUserId = CurrentHolder.getCurrentId();

        if (autoFill.value() == OperationType.INSERT) {
            fill(entity, AutoFillConstant.SET_CREATE_TIME, LocalDateTime.class, now);
            fill(entity, AutoFillConstant.SET_CREATE_USER, Long.class, currentUserId);
            fill(entity, AutoFillConstant.SET_UPDATE_TIME, LocalDateTime.class, now);
            fill(entity, AutoFillConstant.SET_UPDATE_USER, Long.class, currentUserId);
        } else if (autoFill.value() == OperationType.UPDATE) {
            // 更新不动 create_* —— 那是历史事实
            fill(entity, AutoFillConstant.SET_UPDATE_TIME, LocalDateTime.class, now);
            fill(entity, AutoFillConstant.SET_UPDATE_USER, Long.class, currentUserId);
        }
    }

    /**
     * 用反射给实体赋值。四种情况都只跳过、不抛异常：
     *
     * <ul>
     *   <li>值为 null —— 比如注册接口，用户还没登录，没有 create_user 可填
     *   <li>实体没有这个 setter —— 比如 {@code Link} 暂时没有 create_user 字段
     *   <li>方法访问权限不足
     *   <li>反射调用本身失败
     * </ul>
     *
     * <p>参考项目在这里直接 {@code throw new RuntimeException(e)}，后果是：
     * 一个实体少写了个字段，整个写库操作就炸了，而且报错信息只说是反射失败，
     * 看不出是哪个字段。公共字段填充是"锦上添花"的功能，它不该有能力让业务失败。
     *
     * @param setterName setter 方法名，见 {@link AutoFillConstant}
     * @param paramType  setter 的参数类型
     * @param value      要填的值，为 null 时直接跳过
     */
    private void fill(Object entity, String setterName, Class<?> paramType, Object value) {
        if (value == null) {
            return;
        }
        try {
            Method setter = entity.getClass().getMethod(setterName, paramType);
            setter.invoke(entity, value);
        } catch (NoSuchMethodException e) {
            log.debug("实体没有 {}，跳过填充: {}", setterName, entity.getClass().getSimpleName());
        } catch (ReflectiveOperationException e) {
            log.warn("公共字段填充失败: {}.{}", entity.getClass().getSimpleName(), setterName, e);
        }
    }
}
