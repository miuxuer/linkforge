package com.miuxuer.linkforge.controller.admin;

import com.miuxuer.linkforge.annotation.OperateLog;
import com.miuxuer.linkforge.dto.AdminUserPageQueryDTO;
import com.miuxuer.linkforge.result.Result;
import com.miuxuer.linkforge.service.AdminUserService;
import com.miuxuer.linkforge.vo.AdminUserVO;
import com.miuxuer.linkforge.vo.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端 - 用户管理。
 *
 * <p><b>路径必须在 {@code /api/admin/} 下</b>：{@code JwtTokenInterceptor} 是靠
 * 路径前缀来判断"这个接口需要管理员角色"的。挪到别的前缀下，就只剩登录校验、
 * 没有角色校验了 —— 任何登录用户都能管理别人。
 */
@RestController
@RequestMapping("/api/admin/user")
@RequiredArgsConstructor
@Validated
public class AdminUserController {

    private final AdminUserService adminUserService;

    /** 用户分页。 */
    @GetMapping("/page")
    public Result<PageResult<AdminUserVO>> page(@Valid AdminUserPageQueryDTO dto) {
        return Result.success(adminUserService.page(dto));
    }

    /**
     * 启用 / 禁用用户。
     *
     * <p>用请求参数而不是请求体：只有一个字段，塞进 JSON body 反而让调用方多一层包装。
     * GET/PUT 配 query 参数在 REST 里是常见做法。
     */
    @PutMapping("/{id}/status")
    @OperateLog
    public Result<Void> updateStatus(@PathVariable Long id,
                                     @RequestParam
                                     @Min(value = 0, message = "状态取值不合法")
                                     @Max(value = 1, message = "状态取值不合法") Integer status) {
        adminUserService.updateStatus(id, status);
        return Result.success();
    }
}
