package com.miuxuer.linkforge.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.miuxuer.linkforge.constant.StatusConstant;
import com.miuxuer.linkforge.constant.UserConstant;
import com.miuxuer.linkforge.context.CurrentHolder;
import com.miuxuer.linkforge.dto.AdminUserPageQueryDTO;
import com.miuxuer.linkforge.entity.User;
import com.miuxuer.linkforge.exception.BusinessException;
import com.miuxuer.linkforge.mapper.UserMapper;
import com.miuxuer.linkforge.result.ResultCode;
import com.miuxuer.linkforge.vo.AdminUserVO;
import com.miuxuer.linkforge.vo.PageResult;
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
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("管理端 - 用户管理")
class AdminUserServiceImplTest {

    private static final Long ADMIN_ID = 1L;

    @Mock
    private UserMapper userMapper;

    @Mock
    private UserStatusChecker userStatusChecker;

    private AdminUserServiceImpl adminUserService;

    @BeforeAll
    static void initMybatisPlusTableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), User.class);
    }

    @BeforeEach
    void setUp() {
        adminUserService = new AdminUserServiceImpl(userMapper, userStatusChecker);
        // 当前登录的是一个管理员
        CurrentHolder.setCurrentId(ADMIN_ID);
        CurrentHolder.setCurrentRole(UserConstant.ROLE_ADMIN);
    }

    @AfterEach
    void tearDown() {
        CurrentHolder.remove();
    }

    private static User user(Long id, String username, String nickname, int status) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setNickname(nickname);
        user.setRole(UserConstant.ROLE_USER);
        user.setStatus(status);
        // 真实场景里这一列存的是 BCrypt 密文
        user.setPassword("$2a$10$abcdefghijklmnopqrstuv");
        return user;
    }

    @SuppressWarnings("unchecked")
    private LambdaQueryWrapper<User> capturePageWrapper() {
        ArgumentCaptor<Wrapper<User>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(userMapper).selectPage(any(), captor.capture());
        return (LambdaQueryWrapper<User>) captor.getValue();
    }

    // ==================== 分页 ====================

    @Test
    @DisplayName("★ 返回的 VO 里绝不能有密码字段")
    void page_shouldNeverExposePassword() {
        Page<User> dbPage = new Page<>(1, 10);
        dbPage.setTotal(1);
        dbPage.setRecords(List.of(user(2L, "alice", "爱丽丝", StatusConstant.ENABLED)));
        when(userMapper.selectPage(any(), any())).thenReturn(dbPage);

        PageResult<AdminUserVO> result = adminUserService.page(new AdminUserPageQueryDTO());

        AdminUserVO vo = result.getRecords().get(0);
        assertThat(vo.getId()).isEqualTo(2L);
        assertThat(vo.getUsername()).isEqualTo("alice");
        assertThat(vo.getNickname()).isEqualTo("爱丽丝");
        assertThat(vo.getStatus()).isEqualTo(StatusConstant.ENABLED);
        // AdminUserVO 里根本没有 password 这个字段 —— 从类型上就杜绝了泄露，
        // 而不是靠"记得别把它赋值进去"
    }

    @Test
    @DisplayName("★ 管理端查询不带 user_id 条件（这是刻意的，全项目唯一一处）")
    void page_shouldNotFilterByUser() {
        when(userMapper.selectPage(any(), any())).thenReturn(new Page<>());

        adminUserService.page(new AdminUserPageQueryDTO());

        // 管理端就是要看全部用户。安全边界从"数据隔离"换成了"角色校验"，
        // 由拦截器拦 /api/admin/** 保证。所以这里没有 user_id 是设计，不是漏写
        assertThat(capturePageWrapper().getSqlSegment()).doesNotContain("user_id");
    }

    @Test
    @DisplayName("关键词搜索 → OR 分组被括号包住，不会绕过 status 筛选")
    void page_keywordOrMustBeNested() {
        when(userMapper.selectPage(any(), any())).thenReturn(new Page<>());

        AdminUserPageQueryDTO dto = new AdminUserPageQueryDTO();
        dto.setKeyword("abc");
        dto.setStatus(StatusConstant.DISABLED);
        adminUserService.page(dto);

        String sql = capturePageWrapper().getSqlSegment();
        int orIndex = sql.indexOf(" OR ");
        assertThat(orIndex).isPositive();

        int groupOpen = sql.lastIndexOf('(', orIndex);
        int groupClose = sql.indexOf(')', orIndex);
        // 平铺的话会变成 "status = ? AND username LIKE ? OR nickname LIKE ?"，
        // 后半个 OR 会把 status 筛选整个绕过去 —— 想查"已禁用的用户"，
        // 结果昵称匹配的启用用户也全出来了
        assertThat(sql.substring(groupOpen, groupClose)).contains("LIKE");
        assertThat(sql.substring(0, groupOpen)).contains("status");
    }

    // ==================== 启用 / 禁用 ====================

    @Test
    @DisplayName("禁用别人 → UPDATE 带目标 id，SET 里是 status")
    void updateStatus_shouldUpdateTargetUser() {
        when(userMapper.selectById(2L)).thenReturn(user(2L, "alice", "爱丽丝", StatusConstant.ENABLED));

        adminUserService.updateStatus(2L, StatusConstant.DISABLED);

        ArgumentCaptor<Wrapper<User>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(userMapper).updateWithFill(any(User.class), captor.capture());
        LambdaUpdateWrapper<User> wrapper = (LambdaUpdateWrapper<User>) captor.getValue();
        assertThat(wrapper.getSqlSegment()).contains("id");
        assertThat(wrapper.getSqlSet()).contains("status");

        // ★ 必须清掉状态缓存。不清的话，被禁用的用户拿着旧 token 还能继续操作
        // 最多 60 秒，管理员会以为"点了没反应"
        verify(userStatusChecker).evict(2L);
    }

    @Test
    @DisplayName("★ 不能改自己的状态 —— 否则会把自己永久锁在门外")
    void updateStatus_self_shouldBeRejected() {
        // 禁用自己之后连登录都会被拒，而"启用自己"又需要先登录 ——
        // 只能去数据库里手工改回来
        BusinessException e = assertThrows(BusinessException.class,
                () -> adminUserService.updateStatus(ADMIN_ID, StatusConstant.DISABLED));

        assertThat(e.getHttpStatus()).isEqualTo(400);
        assertThat(e.getMessage()).contains("自己");
        verify(userMapper, never()).updateWithFill(any(User.class), any());
    }

    @Test
    @DisplayName("目标用户不存在 → 404，而不是静默成功")
    void updateStatus_userNotFound_shouldThrow() {
        when(userMapper.selectById(999L)).thenReturn(null);

        BusinessException e = assertThrows(BusinessException.class,
                () -> adminUserService.updateStatus(999L, StatusConstant.DISABLED));

        // 静默返回 200 的话，管理员会以为操作成功了，其实什么都没发生
        assertThat(e.getHttpStatus()).isEqualTo(ResultCode.NOT_FOUND.getHttpStatus());
        verify(userMapper, never()).updateWithFill(any(User.class), any());
    }
}
