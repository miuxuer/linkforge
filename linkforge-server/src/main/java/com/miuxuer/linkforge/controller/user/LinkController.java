package com.miuxuer.linkforge.controller.user;

import com.miuxuer.linkforge.annotation.OperateLog;
import com.miuxuer.linkforge.dto.LinkCreateDTO;
import com.miuxuer.linkforge.dto.LinkPageQueryDTO;
import com.miuxuer.linkforge.dto.LinkUpdateDTO;
import com.miuxuer.linkforge.result.Result;
import com.miuxuer.linkforge.service.LinkService;
import com.miuxuer.linkforge.vo.LinkVO;
import com.miuxuer.linkforge.vo.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
// 方法参数上的 @Min/@Max（如二维码 size）要靠它才生效
@Validated
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

    /**
     * 修改短链（标题、备注、启停、过期时间）。
     *
     * <p>路径里的 {@code id} 是短链主键，Service 会校验它属于当前登录用户 ——
     * 换成别人的 id 只会得到 403 / 404，改不动任何东西。
     */
    @PutMapping("/{id}")
    @OperateLog
    public Result<Void> update(@PathVariable Long id, @RequestBody @Valid LinkUpdateDTO dto) {
        linkService.updateLink(id, dto);
        return Result.success();
    }

    /**
     * 删除短链（逻辑删除）。
     *
     * <p>用 DELETE 而不是 GET，也不是 POST /delete：HTTP 方法本身带语义，
     * 网关、监控、灰度系统都能据此做区分（比如对 DELETE 单独告警）。
     */
    @DeleteMapping("/{id}")
    @OperateLog
    public Result<Void> delete(@PathVariable Long id) {
        linkService.deleteLink(id);
        return Result.success();
    }

    /**
     * 生成二维码图片（PNG），可直接在浏览器打开或下载。
     *
     * <p><b>这个接口不返回 {@code Result<T>}，直接返回图片字节流。</b>
     * {@code <img src="...">} 没法解析 JSON 包装，前端要的是原始图片。
     * 这也是为什么它的返回类型是 {@code ResponseEntity<byte[]>} 而不是 {@code Result}。
     *
     * <p>{@code Content-Disposition} 用 {@code inline} 而不是 {@code attachment}：
     * 前端在详情页要直接显示这张图。想下载的话，给 {@code <a>} 加个 download 属性即可，
     * 不需要为"下载"单独开一个接口。
     *
     * <p>需要 {@code @Validated}（打在类上）才能校验 {@code size} 上的
     * {@code @Min/@Max} —— 散装参数上的校验注解默认不生效，这点和 {@code @RequestBody}
     * 不一样，很容易漏。
     */
    @GetMapping("/{id}/qrcode")
    public ResponseEntity<byte[]> qrcode(@PathVariable Long id,
                                         @RequestParam(defaultValue = "300")
                                         @Min(value = 100, message = "二维码尺寸不能小于 100")
                                         @Max(value = 1000, message = "二维码尺寸不能大于 1000")
                                         int size) {
        byte[] png = linkService.generateQrCode(id, size);

        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"qrcode.png\"")
                .body(png);
    }
}
