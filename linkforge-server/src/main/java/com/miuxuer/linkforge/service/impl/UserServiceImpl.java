package com.miuxuer.linkforge.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.miuxuer.linkforge.constant.JwtClaimsConstant;
import com.miuxuer.linkforge.constant.MessageConstant;
import com.miuxuer.linkforge.constant.StatusConstant;
import com.miuxuer.linkforge.constant.UserConstant;
import com.miuxuer.linkforge.context.CurrentHolder;
import com.miuxuer.linkforge.dto.UserLoginDTO;
import com.miuxuer.linkforge.dto.UserRegisterDTO;
import com.miuxuer.linkforge.entity.User;
import com.miuxuer.linkforge.exception.BusinessException;
import com.miuxuer.linkforge.mapper.UserMapper;
import com.miuxuer.linkforge.properties.JwtProperties;
import com.miuxuer.linkforge.result.ResultCode;
import com.miuxuer.linkforge.service.IdSegmentManager;
import com.miuxuer.linkforge.service.UserService;
import com.miuxuer.linkforge.utils.JwtUtils;
import com.miuxuer.linkforge.vo.UserLoginVO;
import com.miuxuer.linkforge.vo.UserProfileVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.Map;

/**
 * 用户服务实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    /**
     * 用户不存在时用来"陪跑"一次哈希校验的假密文。
     *
     * <p>不这么做的话："用户不存在"分支立刻返回，而"密码错误"分支要跑一次 BCrypt
     * 大约 100 毫秒。攻击者不需要看提示文案，掐一下响应时间就能判断用户名存不存在 ——
     * 上面统一提示文案的努力就白费了。
     */
    private static final String DUMMY_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    private final UserMapper userMapper;
    private final IdSegmentManager idSegmentManager;
    private final PasswordEncoder passwordEncoder;
    private final JwtProperties jwtProperties;

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
        user.setStatus(StatusConstant.ENABLED);

        try {
            // 用 insertWithFill 而不是 insert：前者带 @AutoFill 注解，
            // 切面会在 SQL 执行前把 create_time / update_time 等公共字段填上
            userMapper.insertWithFill(user);
        } catch (DuplicateKeyException e) {
            // 并发注册撞上唯一索引。对用户来说这和上面那次查重是同一件事，
            // 所以抛同一个业务异常 —— 不要把数据库异常直接透给前端。
            log.warn("并发注册撞唯一索引: username={}", dto.getUsername());
            throw new BusinessException(ResultCode.PARAM_ERROR, MessageConstant.USERNAME_ALREADY_EXISTS);
        }

        // 日志里只记 id 和用户名，绝不能记密码（哪怕是加密后的）
        log.info("用户注册成功: id={}, username={}", user.getId(), user.getUsername());
    }

    @Override
    public UserLoginVO login(UserLoginDTO dto) {
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, dto.getUsername()));

        if (user == null) {
            // 陪跑一次哈希，把响应时间拉平，避免用时间差反推用户名是否存在
            passwordEncoder.matches(dto.getPassword(), DUMMY_HASH);
            throw new BusinessException(ResultCode.UNAUTHORIZED, MessageConstant.LOGIN_FAILED);
        }

        // BCrypt 的 matches 是"用密文里的盐重新哈希一遍明文再比对"，
        // 不需要我们自己解密 —— 密文本来就不可逆
        if (!passwordEncoder.matches(dto.getPassword(), user.getPassword())) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, MessageConstant.LOGIN_FAILED);
        }

        // 禁用判断放在密码校验之后：放前面的话，"账号被禁用"这个提示本身
        // 就暴露了"这个用户名存在"，等于绕过上面统一文案的防护
        if (user.getStatus() == null || user.getStatus() != StatusConstant.ENABLED) {
            throw new BusinessException(ResultCode.FORBIDDEN, MessageConstant.ACCOUNT_DISABLED);
        }

        String token = issueToken(user);

        log.info("用户登录成功: id={}, username={}", user.getId(), user.getUsername());

        return UserLoginVO.builder()
                .id(user.getId())
                .username(user.getUsername())
                .nickname(user.getNickname())
                .avatar(user.getAvatar())
                .role(user.getRole())
                .token(token)
                .build();
    }

    /**
     * 签发 JWT。
     *
     * <p>claims 里放的是"后续每个请求都要用到、但又不值得再查一次库"的信息。
     * 放 userId 是为了让拦截器能直接确定身份；放 role 是为了让管理端鉴权
     * 不用每个请求回查数据库 —— 代价是改了角色要等 token 过期才生效
     * （把角色当成"登录那一刻的快照"来理解就对了）。
     *
     * <p><b>绝对不要往 claims 里放密码</b>：JWT 的 payload 只是 Base64 编码，
     * 不是加密，任何人复制那个 token 到 jwt.io 就能看到全部内容。
     */
    private String issueToken(User user) {
        // 用 HashMap 而不是 Map.of：Map.of 不接受 null 值，
        // 用户表里 nickname / role 万一为 null 会直接抛 NPE
        Map<String, Object> claims = new HashMap<>();
        claims.put(JwtClaimsConstant.USER_ID, user.getId());
        claims.put(JwtClaimsConstant.USERNAME, user.getUsername());
        claims.put(JwtClaimsConstant.ROLE, user.getRole());

        return JwtUtils.createJwt(jwtProperties.getSecretKey(), jwtProperties.getTtl(), claims);
    }

    @Override
    public UserProfileVO getProfile() {
        Long userId = CurrentHolder.getCurrentId();
        if (userId == null) {
            // 能走到这里说明拦截器没生效（路径没注册、白名单配错）——
            // 属于配置问题而不是用户操作问题，但对用户来说结果一样，按未登录处理
            throw new BusinessException(ResultCode.UNAUTHORIZED, MessageConstant.NOT_LOGGED_IN);
        }

        User user = userMapper.selectById(userId);
        if (user == null) {
            // token 还在有效期内，但账号已经被删（本人注销或管理员清理）。
            // 不挡住的话，后面用 user.getXxx() 会一路 NPE，最后变成一个莫名其妙的 500
            throw new BusinessException(ResultCode.UNAUTHORIZED, MessageConstant.NOT_LOGGED_IN);
        }

        return UserProfileVO.builder()
                .id(user.getId())
                .username(user.getUsername())
                .nickname(user.getNickname())
                .avatar(user.getAvatar())
                .role(user.getRole())
                .build();
    }

    private boolean existsByUsername(String username) {
        return userMapper.selectCount(
                new LambdaQueryWrapper<User>().eq(User::getUsername, username)) > 0;
    }
}
