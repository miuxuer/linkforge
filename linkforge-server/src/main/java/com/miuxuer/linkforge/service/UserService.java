package com.miuxuer.linkforge.service;

import com.miuxuer.linkforge.dto.UserLoginDTO;
import com.miuxuer.linkforge.dto.UserRegisterDTO;
import com.miuxuer.linkforge.vo.UserLoginVO;

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

    /**
     * 登录，校验通过后签发 JWT。
     *
     * @param dto 登录入参
     * @return 用户信息和 token
     * @throws com.miuxuer.linkforge.exception.BusinessException 用户名或密码错误、账号被禁用
     */
    UserLoginVO login(UserLoginDTO dto);
}
