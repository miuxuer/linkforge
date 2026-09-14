package com.miuxuer.linkforge.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.miuxuer.linkforge.constant.StatusConstant;
import com.miuxuer.linkforge.context.CurrentHolder;
import com.miuxuer.linkforge.dto.AdminLinkPageQueryDTO;
import com.miuxuer.linkforge.entity.Link;
import com.miuxuer.linkforge.entity.User;
import com.miuxuer.linkforge.exception.LinkNotFoundException;
import com.miuxuer.linkforge.mapper.LinkMapper;
import com.miuxuer.linkforge.mapper.UserMapper;
import com.miuxuer.linkforge.properties.LinkProperties;
import com.miuxuer.linkforge.vo.AdminLinkVO;
import com.miuxuer.linkforge.vo.PageResult;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("管理端 - 短链管理")
class AdminLinkServiceImplTest {

    @Mock
    private LinkMapper linkMapper;

    @Mock
    private UserMapper userMapper;

    @Mock
    private LinkCacheEvictor linkCacheEvictor;

    @InjectMocks
    private AdminLinkServiceImpl adminLinkService;

    @BeforeAll
    static void initMybatisPlusTableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), Link.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), User.class);
    }

    @BeforeEach
    void setUp() {
        LinkProperties properties = new LinkProperties();
        properties.setDomain("http://localhost:8080");
        adminLinkService = new AdminLinkServiceImpl(linkMapper, userMapper, properties, linkCacheEvictor);
        CurrentHolder.setCurrentId(1L);
    }

    @AfterEach
    void tearDown() {
        CurrentHolder.remove();
    }

    private static Link link(Long id, Long userId, String shortCode) {
        Link link = new Link();
        link.setId(id);
        link.setUserId(userId);
        link.setShortCode(shortCode);
        link.setTitle("标题-" + shortCode);
        link.setOriginalUrl("https://example.com/" + shortCode);
        link.setStatus(StatusConstant.ENABLED);
        link.setVisitCount(0L);
        return link;
    }

    @SuppressWarnings("unchecked")
    private LambdaQueryWrapper<Link> capturePageWrapper() {
        ArgumentCaptor<Wrapper<Link>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(linkMapper).selectPage(any(), captor.capture());
        return (LambdaQueryWrapper<Link>) captor.getValue();
    }

    // ==================== 分页 ====================

    @Test
    @DisplayName("分页 → 补齐归属用户名")
    void page_shouldFillUsername() {
        Page<Link> dbPage = new Page<>(1, 10);
        dbPage.setTotal(1);
        dbPage.setRecords(List.of(link(1L, 1001L, "abc")));
        when(linkMapper.selectPage(any(), any())).thenReturn(dbPage);

        User owner = new User();
        owner.setId(1001L);
        owner.setUsername("alice");
        when(userMapper.selectBatchIds(Set.of(1001L))).thenReturn(List.of(owner));

        PageResult<AdminLinkVO> result = adminLinkService.page(new AdminLinkPageQueryDTO());

        assertThat(result.getRecords().get(0).getUsername()).isEqualTo("alice");
        assertThat(result.getRecords().get(0).getShortUrl()).isEqualTo("http://localhost:8080/abc");
    }

    @Test
    @DisplayName("★ 归属用户名用一次批量查询拿齐，不是每条短链查一次")
    void page_shouldBatchLoadUsernames() {
        Page<Link> dbPage = new Page<>(1, 10);
        dbPage.setTotal(3);
        dbPage.setRecords(List.of(
                link(1L, 1001L, "a"), link(2L, 1001L, "b"), link(3L, 1002L, "c")));
        when(linkMapper.selectPage(any(), any())).thenReturn(dbPage);

        User alice = new User();
        alice.setId(1001L);
        alice.setUsername("alice");
        User bobby = new User();
        bobby.setId(1002L);
        bobby.setUsername("bobby");
        when(userMapper.selectBatchIds(any())).thenReturn(List.of(alice, bobby));

        adminLinkService.page(new AdminLinkPageQueryDTO());

        // 三条短链只查一次用户表。逐条查的话一页 100 条就是 100 次查询 ——
        // 经典的 N+1，页面越翻越慢
        verify(userMapper).selectBatchIds(Set.of(1001L, 1002L));
    }

    @Test
    @DisplayName("★ 管理端分页不带 user_id 条件（刻意如此）")
    void page_shouldNotFilterByUser() {
        when(linkMapper.selectPage(any(), any())).thenReturn(new Page<>());

        adminLinkService.page(new AdminLinkPageQueryDTO());

        // 管理员就是要看全部短链。安全边界是 /api/admin/** 上的角色校验，
        // 不是数据隔离 —— 所以这里没有 user_id 是设计
        assertThat(capturePageWrapper().getSqlSegment()).doesNotContain("user_id");
    }

    @Test
    @DisplayName("按归属用户筛选时才带 user_id 条件")
    void page_filterByUserShouldWork() {
        when(linkMapper.selectPage(any(), any())).thenReturn(new Page<>());

        AdminLinkPageQueryDTO dto = new AdminLinkPageQueryDTO();
        dto.setUserId(1001L);
        adminLinkService.page(dto);

        assertThat(capturePageWrapper().getSqlSegment()).contains("user_id");
    }

    // ==================== 强制删除 ====================

    @Test
    @DisplayName("★ 强制删除 → 不受归属限制，但必须清缓存")
    void forceDelete_shouldDeleteAndEvictCache() {
        when(linkMapper.selectById(1L)).thenReturn(link(1L, 2002L, "abc"));

        adminLinkService.forceDelete(1L);

        verify(linkMapper).deleteById(1L);
        // 不清缓存的话，这条"已删除"的违规短链还会继续跳 ——
        // 管理员以为处理完了，实际上它还在服务
        verify(linkCacheEvictor).evict("abc");
    }

    @Test
    @DisplayName("强制删除不存在的短链 → 404")
    void forceDelete_notFound_shouldThrow() {
        when(linkMapper.selectById(999L)).thenReturn(null);

        assertThrows(LinkNotFoundException.class, () -> adminLinkService.forceDelete(999L));
        verify(linkMapper, never()).deleteById(any(Long.class));
    }
}
