package com.miuxuer.linkforge.service.impl;

import com.miuxuer.linkforge.constant.JwtClaimsConstant;
import com.miuxuer.linkforge.constant.MessageConstant;
import com.miuxuer.linkforge.constant.UserConstant;
import com.miuxuer.linkforge.dto.UserLoginDTO;
import com.miuxuer.linkforge.dto.UserRegisterDTO;
import com.miuxuer.linkforge.entity.User;
import com.miuxuer.linkforge.exception.BusinessException;
import com.miuxuer.linkforge.mapper.UserMapper;
import com.miuxuer.linkforge.properties.JwtProperties;
import com.miuxuer.linkforge.result.ResultCode;
import com.miuxuer.linkforge.service.IdSegmentManager;
import com.miuxuer.linkforge.utils.JwtUtils;
import com.miuxuer.linkforge.vo.UserLoginVO;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 用户注册单元测试。
 *
 * <p>编码器用的是真的 {@link BCryptPasswordEncoder}，不是 mock —— 这个用例最该验证的
 * 就是"密码到底有没有被哈希"，用 mock 就把它验没了。代价是每个用例要跑一次真实的
 * BCrypt（约 100 毫秒），可以接受。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("用户服务 - 注册")
class UserServiceImplTest {

    private static final String RAW_PASSWORD = "plain-password-123";

    @Mock
    private UserMapper userMapper;

    @Mock
    private IdSegmentManager idSegmentManager;

    private static final String JWT_SECRET = "linkforge-unit-test-secret-key-0123456789";

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    private JwtProperties jwtProperties;

    private UserServiceImpl userService;

    @BeforeEach
    void setUp() {
        jwtProperties = new JwtProperties();
        jwtProperties.setSecretKey(JWT_SECRET);
        jwtProperties.setTtl(3600_000L);
        jwtProperties.setTokenName("token");

        userService = new UserServiceImpl(userMapper, idSegmentManager, passwordEncoder, jwtProperties);
    }

    /** 造一个"库里已经存在"的用户，密码是 {@link #RAW_PASSWORD} 的 BCrypt 密文。 */
    private User existingUser(String username, int status) {
        User user = new User();
        user.setId(1001L);
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(RAW_PASSWORD));
        user.setNickname("苗雪儿");
        user.setRole(UserConstant.ROLE_USER);
        user.setStatus(status);
        return user;
    }

    private static UserLoginDTO loginDto(String username, String password) {
        UserLoginDTO dto = new UserLoginDTO();
        dto.setUsername(username);
        dto.setPassword(password);
        return dto;
    }

    private static UserRegisterDTO dto(String username, String nickname) {
        UserRegisterDTO dto = new UserRegisterDTO();
        dto.setUsername(username);
        dto.setPassword(RAW_PASSWORD);
        dto.setNickname(nickname);
        return dto;
    }

    private User captureInserted() {
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).insert(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("注册成功 → 分配 id、密码哈希后落库、角色状态填充")
    void register_shouldHashPasswordAndFillDefaults() {
        when(userMapper.selectCount(any())).thenReturn(0L);
        when(idSegmentManager.getNextId(IdSegmentManager.BIZ_TAG_USER)).thenReturn(1001L);

        userService.register(dto("miuxuer", null));

        User saved = captureInserted();
        assertEquals(1001L, saved.getId());
        assertEquals("miuxuer", saved.getUsername());
        assertEquals(UserConstant.ROLE_USER, saved.getRole());
        assertEquals(UserConstant.STATUS_ENABLED, saved.getStatus());
        // 昵称没填时用用户名兜底，避免前端显示空白
        assertEquals("miuxuer", saved.getNickname());
    }

    @Test
    @DisplayName("密码落库前必须被哈希 —— 数据库里不能出现明文")
    void register_shouldNeverStorePlaintextPassword() {
        when(userMapper.selectCount(any())).thenReturn(0L);
        when(idSegmentManager.getNextId(any())).thenReturn(1L);

        userService.register(dto("miuxuer", null));

        String stored = captureInserted().getPassword();
        assertNotEquals(RAW_PASSWORD, stored, "数据库里存的居然是明文密码");
        // BCrypt 密文固定以 $2a$/$2b$/$2y$ 开头
        assertTrue(stored.startsWith("$2"), "看起来不是 BCrypt 密文: " + stored);
        // 而且要用原密码能验回来，说明哈希没算错
        assertTrue(passwordEncoder.matches(RAW_PASSWORD, stored));
    }

    @Test
    @DisplayName("同一个密码注册两次 → 密文不同（盐是随机的）")
    void register_samePassword_shouldProduceDifferentHashes() {
        when(userMapper.selectCount(any())).thenReturn(0L);
        when(idSegmentManager.getNextId(any())).thenReturn(1L, 2L);

        userService.register(dto("userA", null));
        userService.register(dto("userB", null));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper, times(2)).insert(captor.capture());

        String first = captor.getAllValues().get(0).getPassword();
        String second = captor.getAllValues().get(1).getPassword();
        // 两人用同样的密码，密文却不一样 —— 这正是"自带随机盐"的意义：
        // 攻击者没法靠"密文相同"推断出"这两个人密码一样"，彩虹表也直接失效
        assertNotEquals(first, second);
    }

    @Test
    @DisplayName("用户名已存在 → 抛业务异常，且不写库")
    void register_duplicateUsername_shouldThrow() {
        when(userMapper.selectCount(any())).thenReturn(1L);

        BusinessException e = assertThrows(BusinessException.class,
                () -> userService.register(dto("miuxuer", null)));

        assertEquals(MessageConstant.USERNAME_ALREADY_EXISTS, e.getMessage());
        verify(userMapper, never()).insert(any(User.class));
    }

    @Test
    @DisplayName("并发注册撞上唯一索引 → 翻译成同样的业务异常，而不是把数据库异常透出去")
    void register_concurrentDuplicate_shouldThrowBusinessException() {
        // 模拟"查重时还没有、插入时已经被别人抢先"的竞态
        when(userMapper.selectCount(any())).thenReturn(0L);
        when(idSegmentManager.getNextId(any())).thenReturn(1L);
        when(userMapper.insert(any(User.class))).thenThrow(new DuplicateKeyException("uk_username"));

        BusinessException e = assertThrows(BusinessException.class,
                () -> userService.register(dto("miuxuer", null)));

        assertEquals(MessageConstant.USERNAME_ALREADY_EXISTS, e.getMessage());
    }

    @Test
    @DisplayName("填了昵称 → 用填的那个，不被用户名覆盖")
    void register_withNickname_shouldKeepIt() {
        when(userMapper.selectCount(any())).thenReturn(0L);
        when(idSegmentManager.getNextId(any())).thenReturn(1L);

        userService.register(dto("miuxuer", "苗雪儿"));

        assertEquals("苗雪儿", captureInserted().getNickname());
    }

    // ==================== 登录 ====================

    @Test
    @DisplayName("登录成功 → 返回用户信息和可验签的 token")
    void login_success_shouldReturnToken() {
        when(userMapper.selectOne(any())).thenReturn(existingUser("miuxuer", UserConstant.STATUS_ENABLED));

        UserLoginVO vo = userService.login(loginDto("miuxuer", RAW_PASSWORD));

        assertEquals(1001L, vo.getId());
        assertEquals("miuxuer", vo.getUsername());
        assertEquals("苗雪儿", vo.getNickname());
        assertEquals(UserConstant.ROLE_USER, vo.getRole());
        assertNotNull(vo.getToken());
    }

    @Test
    @DisplayName("签发的 token 能验签，claims 里的 userId 和角色都对")
    void login_tokenShouldCarryIdentityClaims() {
        when(userMapper.selectOne(any())).thenReturn(existingUser("miuxuer", UserConstant.STATUS_ENABLED));

        UserLoginVO vo = userService.login(loginDto("miuxuer", RAW_PASSWORD));
        Claims claims = JwtUtils.parseJwt(JWT_SECRET, vo.getToken());

        // 拦截器就是靠这个 userId 确定"当前是谁"的
        assertEquals(1001L, ((Number) claims.get(JwtClaimsConstant.USER_ID)).longValue());
        assertEquals("miuxuer", claims.get(JwtClaimsConstant.USERNAME));
        assertEquals(UserConstant.ROLE_USER, ((Number) claims.get(JwtClaimsConstant.ROLE)).intValue());
        // 密码相关的任何东西都不能进 claims —— payload 只是 Base64，谁都能解
        assertFalse(vo.getToken().contains("password"));
        assertNull(claims.get("password"));
    }

    @Test
    @DisplayName("密码错误 → 401，且提示不透露用户是否存在")
    void login_wrongPassword_shouldThrowUnauthorized() {
        when(userMapper.selectOne(any())).thenReturn(existingUser("miuxuer", UserConstant.STATUS_ENABLED));

        BusinessException e = assertThrows(BusinessException.class,
                () -> userService.login(loginDto("miuxuer", "wrong-password")));

        assertEquals(MessageConstant.LOGIN_FAILED, e.getMessage());
        assertEquals(ResultCode.UNAUTHORIZED.getHttpStatus(), e.getHttpStatus());
    }

    @Test
    @DisplayName("用户不存在 → 提示和密码错误完全相同，不给攻击者线索")
    void login_userNotFound_shouldUseSameMessage() {
        when(userMapper.selectOne(any())).thenReturn(null);

        BusinessException e = assertThrows(BusinessException.class,
                () -> userService.login(loginDto("nobody", RAW_PASSWORD)));

        // 两种失败给同一句文案，否则等于白送一份有效用户名列表
        assertEquals(MessageConstant.LOGIN_FAILED, e.getMessage());
    }

    @Test
    @DisplayName("账号被禁用 → 403，且提示在密码校验之后才出现")
    void login_disabledAccount_shouldThrowForbidden() {
        when(userMapper.selectOne(any()))
                .thenReturn(existingUser("miuxuer", UserConstant.STATUS_DISABLED));

        BusinessException e = assertThrows(BusinessException.class,
                () -> userService.login(loginDto("miuxuer", RAW_PASSWORD)));

        assertEquals(MessageConstant.ACCOUNT_DISABLED, e.getMessage());
        assertEquals(ResultCode.FORBIDDEN.getHttpStatus(), e.getHttpStatus());
    }

    @Test
    @DisplayName("禁用账号 + 密码也错 → 先报密码错，不泄露账号状态")
    void login_disabledWithWrongPassword_shouldReportPasswordError() {
        when(userMapper.selectOne(any()))
                .thenReturn(existingUser("miuxuer", UserConstant.STATUS_DISABLED));

        BusinessException e = assertThrows(BusinessException.class,
                () -> userService.login(loginDto("miuxuer", "wrong-password")));

        assertEquals(MessageConstant.LOGIN_FAILED, e.getMessage());
    }
}
