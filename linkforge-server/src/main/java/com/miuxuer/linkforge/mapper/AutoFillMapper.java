package com.miuxuer.linkforge.mapper;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.miuxuer.linkforge.annotation.AutoFill;
import com.miuxuer.linkforge.enumeration.OperationType;

/**
 * 带公共字段自动填充的 Mapper 基接口。
 *
 * <p><b>为什么要有这个接口</b>：{@code BaseMapper.insert()} 是继承来的方法，
 * 没法在它上面打 {@code @AutoFill} 注解 —— 总不能改 MyBatis-Plus 的源码。
 * 所以包一层 default 方法，注解打在可以修改的方法上。
 *
 * <p>MyBatis 对 mapper 接口里的 default 方法有专门处理：直接走 Java 调用，
 * 不会把它当成一条 SQL 去解析。方法体里的 {@code insert(entity)} 才是走 MyBatis 代理
 * 执行真正的 INSERT。
 *
 * <p>改字段名的实体（比如某些表没有 create_user）不用担心：切面找不到对应的 setter
 * 会跳过而不是抛异常，见 {@code AutoFillAspect#fill}。
 *
 * @param <T> 实体类型
 */
public interface AutoFillMapper<T> extends BaseMapper<T> {

    /**
     * 插入并自动填充公共字段。
     *
     * @param entity 要插入的实体，切面会就地修改它
     * @return 影响行数
     */
    @AutoFill(OperationType.INSERT)
    default int insertWithFill(T entity) {
        return insert(entity);
    }

    /**
     * 按主键更新并自动填充公共字段。
     *
     * <p>只填 update_time / update_user，不动 create_* —— 谁创建的、什么时候创建的
     * 是历史事实，更新时不该被覆盖。
     *
     * @param entity 带主键的实体
     * @return 影响行数
     */
    @AutoFill(OperationType.UPDATE)
    default int updateByIdWithFill(T entity) {
        return updateById(entity);
    }

    /**
     * 按条件更新，同时自动填充公共字段。
     *
     * <p><b>为什么还需要这个方法：{@code updateById} 没法把字段置空。</b>
     * MyBatis-Plus 的默认策略是"实体里为 null 的字段不参与 SET"，
     * 于是"把短链的过期时间清掉"这个操作根本表达不出来 ——
     * 用户会发现自己设了过期时间之后就再也取消不掉了。
     *
     * <p>走 wrapper 的 {@code .set(字段, null)} 是显式声明"我要把它设成 null"，
     * 不受 null 策略影响，所以能表达置空。同时 entity 参数仍然由切面填
     * update_time / update_user，两个目的都达到。
     *
     * <p>调用方通常传一个空实体（{@code new Link()}）作为填充目标 ——
     * 它自己不提供任何 SET 字段，只负责承接切面填的公共字段。
     *
     * @param entity        公共字段的填充目标，可为空实体，也可为 null
     * @param updateWrapper 更新条件与显式 SET，<b>条件里必须带上 owner 字段</b>
     * @return 影响行数
     */
    @AutoFill(OperationType.UPDATE)
    default int updateWithFill(T entity, Wrapper<T> updateWrapper) {
        return update(entity, updateWrapper);
    }
}
