package com.miuxuer.linkforge.service.impl;

import com.miuxuer.linkforge.constant.MessageConstant;
import com.miuxuer.linkforge.constant.UserConstant;
import com.miuxuer.linkforge.dto.UserRegisterDTO;
import com.miuxuer.linkforge.entity.User;
import com.miuxuer.linkforge.exception.BusinessException;
import com.miuxuer.linkforge.mapper.UserMapper;
import com.miuxuer.linkforge.service.IdSegmentManager;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    private UserServiceImpl userService;

    @BeforeEach
    void setUp() {
        userService = new UserServiceImpl(userMapper, idSegmentManager, passwordEncoder);
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
}
