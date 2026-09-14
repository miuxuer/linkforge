package com.miuxuer.linkforge.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.miuxuer.linkforge.constant.JwtClaimsConstant;
import com.miuxuer.linkforge.constant.MessageConstant;
import com.miuxuer.linkforge.constant.StatusConstant;
import com.miuxuer.linkforge.constant.UserConstant;
import com.miuxuer.linkforge.context.CurrentHolder;
import com.miuxuer.linkforge.dto.UserLoginDTO;
import com.miuxuer.linkforge.dto.UserRegisterDTO;
import com.miuxuer.linkforge.dto.UserUpdateDTO;
import com.miuxuer.linkforge.entity.User;
import com.miuxuer.linkforge.exception.BusinessException;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.miuxuer.linkforge.mapper.UserMapper;
import com.miuxuer.linkforge.properties.JwtProperties;
import com.miuxuer.linkforge.result.ResultCode;
import com.miuxuer.linkforge.service.IdSegmentManager;
import com.miuxuer.linkforge.utils.JwtUtils;
import com.miuxuer.linkforge.vo.UserLoginVO;
import com.miuxuer.linkforge.vo.UserProfileVO;
import io.jsonwebtoken.Claims;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
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

    /**
     * 给 MyBatis-Plus 喂一份 User 的表结构元数据。
     *
     * <p>改资料那几个用例要检查 {@code LambdaUpdateWrapper.getSqlSet()} ——
     * 把 {@code User::getNickname} 解析成列名 {@code nickname} 需要一份
     * "实体 → TableInfo" 的缓存，而这份缓存平时由 MyBatis 启动时填充，
     * 纯单元测试里是空的，会报 {@code can not find lambda cache for this entity}。
     */
    @BeforeAll
    static void initMybatisPlusTableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), User.class);
    }

    @BeforeEach
    void setUp() {
        jwtProperties = new JwtProperties();
        jwtProperties.setSecretKey(JWT_SECRET);
        jwtProperties.setTtl(3600_000L);
        jwtProperties.setTokenName("token");

        userService = new UserServiceImpl(userMapper, idSegmentManager, passwordEncoder, jwtProperties);
    }

    @AfterEach
    void tearDown() {
        // getProfile 用到 ThreadLocal，不清会串到下一个用例
        CurrentHolder.remove();
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
        verify(userMapper).insertWithFill(captor.capture());
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
        assertEquals(StatusConstant.ENABLED, saved.getStatus());
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
        verify(userMapper, times(2)).insertWithFill(captor.capture());

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
        verify(userMapper, never()).insertWithFill(any(User.class));
    }

    @Test
    @DisplayName("并发注册撞上唯一索引 → 翻译成同样的业务异常，而不是把数据库异常透出去")
    void register_concurrentDuplicate_shouldThrowBusinessException() {
        // 模拟"查重时还没有、插入时已经被别人抢先"的竞态
        when(userMapper.selectCount(any())).thenReturn(0L);
        when(idSegmentManager.getNextId(any())).thenReturn(1L);
        when(userMapper.insertWithFill(any(User.class))).thenThrow(new DuplicateKeyException("uk_username"));

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
        when(userMapper.selectOne(any())).thenReturn(existingUser("miuxuer", StatusConstant.ENABLED));

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
        when(userMapper.selectOne(any())).thenReturn(existingUser("miuxuer", StatusConstant.ENABLED));

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
        when(userMapper.selectOne(any())).thenReturn(existingUser("miuxuer", StatusConstant.ENABLED));

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
                .thenReturn(existingUser("miuxuer", StatusConstant.DISABLED));

        BusinessException e = assertThrows(BusinessException.class,
                () -> userService.login(loginDto("miuxuer", RAW_PASSWORD)));

        assertEquals(MessageConstant.ACCOUNT_DISABLED, e.getMessage());
        assertEquals(ResultCode.FORBIDDEN.getHttpStatus(), e.getHttpStatus());
    }

    @Test
    @DisplayName("禁用账号 + 密码也错 → 先报密码错，不泄露账号状态")
    void login_disabledWithWrongPassword_shouldReportPasswordError() {
        when(userMapper.selectOne(any()))
                .thenReturn(existingUser("miuxuer", StatusConstant.DISABLED));

        BusinessException e = assertThrows(BusinessException.class,
                () -> userService.login(loginDto("miuxuer", "wrong-password")));

        assertEquals(MessageConstant.LOGIN_FAILED, e.getMessage());
    }

    // ==================== 当前用户资料 ====================

    @Test
    @DisplayName("查资料 → 按 ThreadLocal 里的用户 id 查，返回 VO")
    void getProfile_shouldUseCurrentHolderId() {
        CurrentHolder.setCurrentId(1001L);
        when(userMapper.selectById(1001L)).thenReturn(existingUser("miuxuer", StatusConstant.ENABLED));

        UserProfileVO vo = userService.getProfile();

        assertEquals(1001L, vo.getId());
        assertEquals("miuxuer", vo.getUsername());
        assertEquals("苗雪儿", vo.getNickname());
        // 接口签名里根本没有 userId 参数，想查别人的都没有入口
        verify(userMapper).selectById(1001L);
    }

    @Test
    @DisplayName("ThreadLocal 里没有身份 → 401，而不是查出一个 null 用户")
    void getProfile_withoutCurrentUser_shouldThrowUnauthorized() {
        // 模拟拦截器没生效的情况
        BusinessException e = assertThrows(BusinessException.class, () -> userService.getProfile());

        assertEquals(ResultCode.UNAUTHORIZED.getHttpStatus(), e.getHttpStatus());
        verify(userMapper, never()).selectById(any());
    }

    @Test
    @DisplayName("token 有效但账号已被删 → 401，而不是一路 NPE 变成 500")
    void getProfile_userDeleted_shouldThrowUnauthorized() {
        CurrentHolder.setCurrentId(1001L);
        when(userMapper.selectById(1001L)).thenReturn(null);

        BusinessException e = assertThrows(BusinessException.class, () -> userService.getProfile());

        assertEquals(ResultCode.UNAUTHORIZED.getHttpStatus(), e.getHttpStatus());
    }

    // ==================== 修改资料 ====================

    @SuppressWarnings("unchecked")
    private LambdaUpdateWrapper<User> captureUpdateWrapper() {
        ArgumentCaptor<Wrapper<User>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(userMapper).updateWithFill(any(User.class), captor.capture());
        return (LambdaUpdateWrapper<User>) captor.getValue();
    }

    private static UserUpdateDTO profileDto(String nickname, String avatar) {
        UserUpdateDTO dto = new UserUpdateDTO();
        dto.setNickname(nickname);
        dto.setAvatar(avatar);
        return dto;
    }

    @Test
    @DisplayName("改资料 → UPDATE 的 WHERE 是当前用户，SET 里有昵称和头像")
    void updateProfile_shouldUpdateOwnRow() {
        CurrentHolder.setCurrentId(1001L);
        when(userMapper.selectById(1001L)).thenReturn(existingUser("miuxuer", StatusConstant.ENABLED));

        userService.updateProfile(profileDto("新昵称", "https://oss.example.com/a.png"));

        LambdaUpdateWrapper<User> wrapper = captureUpdateWrapper();
        // WHERE 里是当前登录用户的 id —— 接口签名没有 userId，
        // 想改别人的连入口都没有
        assertTrue(wrapper.getSqlSegment().contains("id"), wrapper.getSqlSegment());
        assertTrue(wrapper.getSqlSet().contains("nickname"), wrapper.getSqlSet());
        assertTrue(wrapper.getSqlSet().contains("avatar"), wrapper.getSqlSet());
    }

    @Test
    @DisplayName("★ 头像传 null → 也要出现在 SET 里，否则清不掉头像")
    void updateProfile_nullAvatar_shouldStillBeSet() {
        CurrentHolder.setCurrentId(1001L);
        when(userMapper.selectById(1001L)).thenReturn(existingUser("miuxuer", StatusConstant.ENABLED));

        userService.updateProfile(profileDto("新昵称", null));

        // 用 updateById 的话，null 会被当成"不修改"，用户就永远删不掉自己的头像。
        // 这里用 wrapper 显式 set，null 就是"设成 null"
        assertTrue(captureUpdateWrapper().getSqlSet().contains("avatar"),
                "头像是 null 时也必须出现在 SET 子句里");
    }

    @Test
    @DisplayName("改完返回数据库里的最新状态，不是拿请求参数拼出来的")
    void updateProfile_shouldReturnReloadedProfile() {
        CurrentHolder.setCurrentId(1001L);
        // 回查时返回的是"库里的"值
        when(userMapper.selectById(1001L)).thenReturn(existingUser("miuxuer", StatusConstant.ENABLED));

        UserProfileVO profile = userService.updateProfile(profileDto("新昵称", null));

        // existingUser 造的昵称是"苗雪儿"，而不是我们提交的"新昵称" ——
        // 说明返回值确实来自回查，不是拿 dto 拼的
        assertEquals("苗雪儿", profile.getNickname());
    }

    @Test
    @DisplayName("未登录 → 401，且不写库")
    void updateProfile_withoutLogin_shouldThrowUnauthorized() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> userService.updateProfile(profileDto("新昵称", null)));

        assertEquals(ResultCode.UNAUTHORIZED.getHttpStatus(), e.getHttpStatus());
        verify(userMapper, never()).updateWithFill(any(User.class), any());
    }
}
