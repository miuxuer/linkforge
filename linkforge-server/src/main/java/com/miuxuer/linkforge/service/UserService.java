package com.miuxuer.linkforge.service;

import com.miuxuer.linkforge.dto.UserRegisterDTO;

/**
 * 用户服务接口。
 */
public interface UserService {

    /**
     * 注册新用户。
     *
     * <p>密码在落库前用 BCrypt 加密，数据库里永远没有明文。
     *
     * @param dto 注册入参，已通过 {@code @Valid} 校验
     * @throws com.miuxuer.linkforge.exception.BusinessException 用户名已被占用
     */
    void register(UserRegisterDTO dto);
}
