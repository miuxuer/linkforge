package com.miuxuer.linkforge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.miuxuer.linkforge.entity.User;

/**
 * 用户数据访问接口。
 *
 * <p>没有任何自定义方法：注册（insert）、按用户名查（selectOne + 条件构造器）、
 * 改资料（updateById）都能用 BaseMapper 覆盖，不需要写 SQL。
 *
 * <p>查询会自动带上 {@code deleted = 0} —— 实体上的 {@code @TableLogic} 让
 * MyBatis-Plus 自动追加这个条件，业务代码里不用手写。
 */
public interface UserMapper extends BaseMapper<User> {
}
