package com.miuxuer.linkforge.controller.admin;

import com.miuxuer.linkforge.annotation.OperateLog;
import com.miuxuer.linkforge.dto.AdminLinkPageQueryDTO;
import com.miuxuer.linkforge.result.Result;
import com.miuxuer.linkforge.service.AdminLinkService;
import com.miuxuer.linkforge.vo.AdminLinkVO;
import com.miuxuer.linkforge.vo.PageResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端 - 短链管理。
 *
 * <p>路径在 {@code /api/admin/} 下，由拦截器做管理员角色校验。
 */
@RestController
@RequestMapping("/api/admin/link")
@RequiredArgsConstructor
@Validated
public class AdminLinkController {

    private final AdminLinkService adminLinkService;

    /** 全部短链分页，可按关键词 / 归属用户 / 状态筛选。 */
    @GetMapping("/page")
    public Result<PageResult<AdminLinkVO>> page(@Valid AdminLinkPageQueryDTO dto) {
        return Result.success(adminLinkService.page(dto));
    }

    /**
     * 强制删除违规短链。
     *
     * <p>和用户端的 {@code DELETE /api/link/{id}} 是同一个动作，区别只在于
     * 管理端不校验归属。路径分开是为了让拦截器能按前缀施加不同的权限要求。
     */
    @DeleteMapping("/{id}")
    @OperateLog
    public Result<Void> forceDelete(@PathVariable Long id) {
        adminLinkService.forceDelete(id);
        return Result.success();
    }
}
