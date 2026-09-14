package com.miuxuer.linkforge.service;

import com.miuxuer.linkforge.dto.AdminUserPageQueryDTO;
import com.miuxuer.linkforge.vo.AdminUserVO;
import com.miuxuer.linkforge.vo.PageResult;

/**
 * 管理端 - 用户管理。
 *
 * <p><b>这是整个项目里唯一不按 user_id 隔离的一组接口</b>，因为管理员本来就要
 * 看全部用户。所以它的安全边界从"数据隔离"换成了"角色校验"——
 * 由 {@code JwtTokenInterceptor} 拦 {@code /api/admin/**} 时校验 token 里的角色。
 * 接口路径必须严格待在这个前缀下，否则拦截器不管。
 */
public interface AdminUserService {

    /** 用户分页。 */
    PageResult<AdminUserVO> page(AdminUserPageQueryDTO dto);

    /**
     * 启用 / 禁用用户。
     *
     * @param userId 目标用户
     * @param status 见 {@code StatusConstant}
     * @throws com.miuxuer.linkforge.exception.BusinessException 目标不存在、或试图改自己
     */
    void updateStatus(Long userId, Integer status);
}
