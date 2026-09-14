package com.miuxuer.linkforge.service;

import com.miuxuer.linkforge.dto.UserLoginDTO;
import com.miuxuer.linkforge.dto.UserRegisterDTO;
import com.miuxuer.linkforge.dto.UserUpdateDTO;
import com.miuxuer.linkforge.vo.UserLoginVO;
import com.miuxuer.linkforge.vo.UserProfileVO;

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

    /**
     * 查当前登录用户的资料。
     *
     * <p><b>没有 userId 参数</b>，身份从 {@code CurrentHolder} 里取。这是刻意的：
     * 一旦把 userId 做成参数，就等于给了调用方"想看谁就看谁"的能力，
     * 而校验参数和当前登录人是否一致这件事，总有一天会有人忘记写。
     * 让接口在签名上就没有越权的可能，比事后靠代码评审把关可靠得多。
     *
     * @throws com.miuxuer.linkforge.exception.BusinessException 未登录、或 token 有效但账号已不存在
     */
    UserProfileVO getProfile();

    /**
     * 修改当前登录用户的资料（昵称、头像）。
     *
     * <p>和 {@link #getProfile()} 一样，签名里没有 userId —— 只能改自己的。
     *
     * @param dto 新资料，整条覆盖语义
     * @return 修改后的资料
     * @throws com.miuxuer.linkforge.exception.BusinessException 未登录
     */
    UserProfileVO updateProfile(UserUpdateDTO dto);
}
