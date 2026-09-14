package com.miuxuer.linkforge.controller.user;

import com.miuxuer.linkforge.result.Result;
import com.miuxuer.linkforge.service.StatService;
import com.miuxuer.linkforge.vo.LinkTopVO;
import com.miuxuer.linkforge.vo.StatOverviewVO;
import com.miuxuer.linkforge.vo.VisitTrendVO;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 数据看板接口。
 *
 * <p>单独一个 Controller 而不是塞进 {@code LinkController}：路径上虽然同属
 * {@code /api/link} 之下，但看板是一块独立的功能，分开之后
 * {@code /api/link/{id}/qrcode} 和 {@code /api/link/stat/overview} 也不会
 * 出现"字面量路径和变量路径谁先匹配"的疑问 —— 那种依赖匹配优先级的写法能跑，
 * 但每次有人加新接口都要在脑子里过一遍规则，迟早出错。
 */
@RestController
@RequestMapping("/api/link/stat")
@RequiredArgsConstructor
// 方法参数上的 @Min/@Max 要靠它才生效（和 LinkController 里 size 参数同理）
@Validated
public class StatController {

    private final StatService statService;

    /** 总览：短链总数、累计访问量、今日访问量。 */
    @GetMapping("/overview")
    public Result<StatOverviewVO> overview() {
        return Result.success(statService.overview());
    }

    /**
     * 访问趋势。
     *
     * @param days 天数，默认最近 7 天
     */
    @GetMapping("/trend")
    public Result<List<VisitTrendVO>> trend(
            @RequestParam(defaultValue = "7")
            @Min(value = 1, message = "天数至少为 1")
            @Max(value = 90, message = "最多查询 90 天") int days) {
        return Result.success(statService.trend(days));
    }

    /**
     * 访问量 Top N。
     *
     * @param limit 条数，默认 10 条
     */
    @GetMapping("/top")
    public Result<List<LinkTopVO>> top(
            @RequestParam(defaultValue = "10")
            @Min(value = 1, message = "至少返回 1 条")
            @Max(value = 50, message = "最多返回 50 条") int limit) {
        return Result.success(statService.top(limit));
    }
}
