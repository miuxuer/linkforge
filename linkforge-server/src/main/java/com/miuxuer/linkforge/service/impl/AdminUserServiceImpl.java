package com.miuxuer.linkforge.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.miuxuer.linkforge.context.CurrentHolder;
import com.miuxuer.linkforge.dto.AdminUserPageQueryDTO;
import com.miuxuer.linkforge.entity.User;
import com.miuxuer.linkforge.exception.BusinessException;
import com.miuxuer.linkforge.mapper.UserMapper;
import com.miuxuer.linkforge.result.ResultCode;
import com.miuxuer.linkforge.service.AdminUserService;
import com.miuxuer.linkforge.vo.AdminUserVO;
import com.miuxuer.linkforge.vo.PageResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminUserServiceImpl implements AdminUserService {

    private final UserMapper userMapper;
    private final UserStatusChecker userStatusChecker;

    @Override
    public PageResult<AdminUserVO> page(AdminUserPageQueryDTO dto) {
        Page<User> pageParam = new Page<>(dto.getPage(), dto.getPageSize());

        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<User>()
                // ★ 这里没有 user_id 条件，这是刻意的 ——
                // 管理端就是要看全部用户。接口路径在 /api/admin/** 下，
                // 由拦截器保证只有管理员能进来。这是全项目唯一一处不按 user_id 隔离的查询
                .eq(dto.getStatus() != null, User::getStatus, dto.getStatus())
                // 关键词的 OR 同样必须包在 and(...) 里。这里没有 user_id 要保护，
                // 但平铺的话会变成 "status = ? AND username LIKE ? OR nickname LIKE ?"，
                // 后半个 OR 会把 status 筛选也绕过去
                .and(StringUtils.hasText(dto.getKeyword()), w -> w
                        .like(User::getUsername, dto.getKeyword())
                        .or()
                        .like(User::getNickname, dto.getKeyword()))
                .orderByDesc(User::getCreateTime);

        Page<User> result = userMapper.selectPage(pageParam, wrapper);

        List<AdminUserVO> records = result.getRecords().stream()
                .map(AdminUserVO::from)
                .toList();

        return PageResult.of(result.getTotal(), result.getCurrent(), result.getSize(), records);
    }

    @Override
    public void updateStatus(Long userId, Integer status) {
        Long currentAdminId = CurrentHolder.requireCurrentId();

        // 防止管理员把自己禁用了 —— 这不是理论风险：禁用之后连登录都会被拒，
        // 而"启用自己"这个操作本身又需要先登录，等于把自己永久锁在门外，
        // 只能去数据库里手工改回来
        if (currentAdminId.equals(userId)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "不能修改自己的账号状态");
        }

        User target = userMapper.selectById(userId);
        if (target == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "用户不存在");
        }

        userMapper.updateWithFill(new User(), new LambdaUpdateWrapper<User>()
                .eq(User::getId, userId)
                .set(User::getStatus, status));

        // ★ 必须清掉状态缓存，否则禁用要等缓存自然过期（最多 60 秒）才生效 ——
        // 那 60 秒里被禁用的用户拿着旧 token 还能继续操作，
        // 管理员会以为"点了没反应"
        userStatusChecker.evict(userId);

        log.info("管理员修改用户状态: 操作人={}, 目标={}, 新状态={}", currentAdminId, userId, status);
    }
}
