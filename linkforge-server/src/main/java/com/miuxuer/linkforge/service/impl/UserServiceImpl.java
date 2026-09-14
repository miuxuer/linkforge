package com.miuxuer.linkforge.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.miuxuer.linkforge.constant.MessageConstant;
import com.miuxuer.linkforge.constant.UserConstant;
import com.miuxuer.linkforge.dto.UserRegisterDTO;
import com.miuxuer.linkforge.entity.User;
import com.miuxuer.linkforge.exception.BusinessException;
import com.miuxuer.linkforge.mapper.UserMapper;
import com.miuxuer.linkforge.result.ResultCode;
import com.miuxuer.linkforge.service.IdSegmentManager;
import com.miuxuer.linkforge.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 用户服务实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserMapper userMapper;
    private final IdSegmentManager idSegmentManager;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void register(UserRegisterDTO dto) {
        // 先查一次，是为了给"用户名已存在"一个明确的提示。
        // 但这一次查询挡不住并发 —— 两个请求可能同时查到"不存在"，然后一起插入。
        // 真正兜底的是数据库上 (username, deleted) 的唯一索引，见下面的 catch。
        if (existsByUsername(dto.getUsername())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, MessageConstant.USERNAME_ALREADY_EXISTS);
        }

        User user = new User();
        user.setId(idSegmentManager.getNextId(IdSegmentManager.BIZ_TAG_USER));
        user.setUsername(dto.getUsername());
        // 只在这里出现一次明文密码，紧接着就被哈希掉，之后任何地方都拿不到原文
        user.setPassword(passwordEncoder.encode(dto.getPassword()));
        user.setNickname(StringUtils.hasText(dto.getNickname()) ? dto.getNickname() : dto.getUsername());
        user.setRole(UserConstant.ROLE_USER);
        user.setStatus(UserConstant.STATUS_ENABLED);

        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException e) {
            // 并发注册撞上唯一索引。对用户来说这和上面那次查重是同一件事，
            // 所以抛同一个业务异常 —— 不要把数据库异常直接透给前端。
            log.warn("并发注册撞唯一索引: username={}", dto.getUsername());
            throw new BusinessException(ResultCode.PARAM_ERROR, MessageConstant.USERNAME_ALREADY_EXISTS);
        }

        // 日志里只记 id 和用户名，绝不能记密码（哪怕是加密后的）
        log.info("用户注册成功: id={}, username={}", user.getId(), user.getUsername());
    }

    private boolean existsByUsername(String username) {
        return userMapper.selectCount(
                new LambdaQueryWrapper<User>().eq(User::getUsername, username)) > 0;
    }
}
