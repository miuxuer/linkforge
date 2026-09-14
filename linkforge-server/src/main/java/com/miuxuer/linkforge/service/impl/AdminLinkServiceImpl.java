package com.miuxuer.linkforge.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.miuxuer.linkforge.dto.AdminLinkPageQueryDTO;
import com.miuxuer.linkforge.entity.Link;
import com.miuxuer.linkforge.entity.User;
import com.miuxuer.linkforge.exception.LinkNotFoundException;
import com.miuxuer.linkforge.mapper.LinkMapper;
import com.miuxuer.linkforge.mapper.UserMapper;
import com.miuxuer.linkforge.properties.LinkProperties;
import com.miuxuer.linkforge.service.AdminLinkService;
import com.miuxuer.linkforge.vo.AdminLinkVO;
import com.miuxuer.linkforge.vo.PageResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminLinkServiceImpl implements AdminLinkService {

    private final LinkMapper linkMapper;
    private final UserMapper userMapper;
    private final LinkProperties linkProperties;
    private final LinkCacheEvictor linkCacheEvictor;

    @Override
    public PageResult<AdminLinkVO> page(AdminLinkPageQueryDTO dto) {
        Page<Link> pageParam = new Page<>(dto.getPage(), dto.getPageSize());

        LambdaQueryWrapper<Link> wrapper = new LambdaQueryWrapper<Link>()
                // ★ 这里没有 user_id 隔离条件，是刻意的 —— 管理员要看全部短链。
                // 安全边界换成了 /api/admin/** 上的角色校验
                .eq(dto.getUserId() != null, Link::getUserId, dto.getUserId())
                .eq(dto.getStatus() != null, Link::getStatus, dto.getStatus())
                .and(StringUtils.hasText(dto.getKeyword()), w -> w
                        .like(Link::getTitle, dto.getKeyword())
                        .or()
                        .like(Link::getShortCode, dto.getKeyword()))
                .orderByDesc(Link::getCreateTime);

        Page<Link> result = linkMapper.selectPage(pageParam, wrapper);

        Map<Long, String> usernames = loadUsernames(result.getRecords());

        List<AdminLinkVO> records = result.getRecords().stream()
                .map(link -> AdminLinkVO.from(
                        link,
                        usernames.get(link.getUserId()),
                        linkProperties.getDomain()))
                .toList();

        return PageResult.of(result.getTotal(), result.getCurrent(), result.getSize(), records);
    }

    /**
     * 批量查出这页短链的归属用户名。
     *
     * <p><b>用"查完再补"而不是 SQL JOIN</b>：JOIN 会让分页查询变成自定义 SQL
     * （{@code @Select} 里写动态条件要么拼字符串、要么上 {@code <script>} 标签，
     * 都比条件构造器难维护）。而这里一次 {@code selectBatchIds} 就能把整页的用户名
     * 拿齐，总共两次查询 —— 不是"每条短链查一次用户"那种 N+1。
     */
    private Map<Long, String> loadUsernames(List<Link> links) {
        Set<Long> userIds = links.stream()
                .map(Link::getUserId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        if (userIds.isEmpty()) {
            return Map.of();
        }

        return userMapper.selectBatchIds(userIds).stream()
                .collect(Collectors.toMap(User::getId, User::getUsername, (a, b) -> a));
    }

    @Override
    public void forceDelete(Long linkId) {
        Link link = linkMapper.selectById(linkId);
        if (link == null) {
            throw new LinkNotFoundException(linkId);
        }

        // 管理员删除不受归属限制，所以没有 user_id 条件。
        // 逻辑删除仍由 @TableLogic 接管（deleteById 会被改写成 UPDATE ... deleted = 1）
        linkMapper.deleteById(linkId);

        // 必须清缓存，否则这条"已删除"的短链还会继续跳 ——
        // 管理员以为处理完了，实际上违规链接还在服务
        linkCacheEvictor.evict(link.getShortCode());

        log.info("管理员强制删除短链: linkId={}, shortCode={}, 归属用户={}",
                linkId, link.getShortCode(), link.getUserId());
    }
}
