package com.miuxuer.linkforge.controller.user;

import com.miuxuer.linkforge.annotation.OperateLog;
import com.miuxuer.linkforge.dto.LinkCreateDTO;
import com.miuxuer.linkforge.dto.LinkPageQueryDTO;
import com.miuxuer.linkforge.result.Result;
import com.miuxuer.linkforge.service.LinkService;
import com.miuxuer.linkforge.vo.LinkVO;
import com.miuxuer.linkforge.vo.PageResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户端短链管理接口。
 *
 * <p>路径统一在 {@code /api/link} 下，被登录拦截器覆盖 —— 这些接口都必须登录才能访问。
 * 公开的短码跳转在 {@code RedirectController}，是另一条完全独立的路径。
 */
@RestController
@RequestMapping("/api/link")
@RequiredArgsConstructor
public class LinkController {

    private final LinkService linkService;

    @PostMapping
    @OperateLog
    public Result<LinkVO> create(@RequestBody @Valid LinkCreateDTO dto) {
        return Result.success(linkService.createLink(dto));
    }

    /**
     * 我的短链分页。
     *
     * <p>查询条件从 URL 参数绑定（{@code ?page=1&pageSize=10&keyword=xxx}），
     * 不是请求体 —— GET 请求带 body 在有些代理和客户端上会被丢掉。
     *
     * <p>不加 {@code @OperateLog}：查询不改数据，量又大，
     * 记进操作日志只会把真正重要的"谁改了什么"淹没掉。
     */
    @GetMapping("/page")
    public Result<PageResult<LinkVO>> page(@Valid LinkPageQueryDTO dto) {
        return Result.success(linkService.pageMyLinks(dto));
    }
}
