package com.miuxuer.linkforge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户实体，对应 {@code t_user}。
 *
 * <p>主键同样是号段模式分配（{@link IdType#INPUT}），和短链共用一套发号器，
 * 但用不同的 {@code biz_tag} 分开取号 —— 这样用户 id 涨得快也不会把短链 id
 * 顶到很大的数值上，短链 id 转 Base62 后的长度才好控制。
 *
 * <p><b>用户名唯一索引带 deleted</b>：{@code UNIQUE KEY (username, deleted)}。
 * 如果只对 username 建唯一索引，用户注销（逻辑删除）之后这个名字就永远被占着，
 * 本人也不能重新注册。带上 deleted 之后，删除行的组合值是 {@code (name, 1)}，
 * 新建的同名用户是 {@code (name, 0)}，不冲突。
 */
@Data
@TableName("t_user")
public class User {

    /** 主键，号段模式分配。 */
    @TableId(type = IdType.INPUT)
    private Long id;

    /** 登录名。 */
    private String username;

    /**
     * 密码密文。
     *
     * <p>存的是 BCrypt 哈希（形如 {@code $2a$10$...}），不是明文也不是 MD5。
     * BCrypt 自带盐值且计算成本可调，MD5 是快速哈希 —— 拖库之后用彩虹表几秒就能撞出原文。
     */
    private String password;

    private String nickname;

    /** 头像 URL（OSS）。 */
    private String avatar;

    /** 角色：见 {@code UserConstant.ROLE_*}。 */
    private Integer role;

    /** 状态：见 {@code UserConstant.STATUS_*}。禁用后登录直接拒绝。 */
    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Long createUser;

    private Long updateUser;

    /** 逻辑删除标记。 */
    @TableLogic
    private Integer deleted;
}
