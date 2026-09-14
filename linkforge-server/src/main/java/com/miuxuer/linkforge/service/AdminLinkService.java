package com.miuxuer.linkforge.service;

import com.miuxuer.linkforge.dto.AdminLinkPageQueryDTO;
import com.miuxuer.linkforge.vo.AdminLinkVO;
import com.miuxuer.linkforge.vo.PageResult;

/**
 * 管理端 - 短链管理。
 *
 * <p>和用户端最大的区别：这里的查询<b>不带 user_id 条件</b>（管理员要看全部），
 * 安全边界由 {@code JwtTokenInterceptor} 对 {@code /api/admin/**} 的角色校验来保证。
 */
public interface AdminLinkService {

    /** 全部短链分页，支持按关键词 / 归属用户 / 状态筛选。 */
    PageResult<AdminLinkVO> page(AdminLinkPageQueryDTO dto);

    /**
     * 强制删除短链（逻辑删除），不受归属限制。
     *
     * @throws com.miuxuer.linkforge.exception.LinkNotFoundException 短链不存在
     */
    void forceDelete(Long linkId);
}
