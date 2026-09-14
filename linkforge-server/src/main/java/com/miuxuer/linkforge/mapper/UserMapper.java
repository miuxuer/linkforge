package com.miuxuer.linkforge.mapper;

import com.miuxuer.linkforge.entity.User;

/**
 * 用户数据访问接口。
 *
 * <p>没有自定义 SQL：查询、按主键更新都能用 {@code BaseMapper} 覆盖，
 * 不需要写 XML。唯一多出来的是继承自 {@link AutoFillMapper} 的两个
 * "写入时自动填公共字段"的方法。
 *
 * <p>查询会自动带上 {@code deleted = 0} —— 实体上的 {@code @TableLogic} 让
 * MyBatis-Plus 自动追加这个条件，业务代码里不用手写。
 */
public interface UserMapper extends AutoFillMapper<User> {
}
