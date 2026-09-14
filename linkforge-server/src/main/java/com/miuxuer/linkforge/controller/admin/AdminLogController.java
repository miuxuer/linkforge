package com.miuxuer.linkforge.controller.admin;

import com.miuxuer.linkforge.dto.OperateLogPageQueryDTO;
import com.miuxuer.linkforge.entity.OperateLog;
import com.miuxuer.linkforge.result.Result;
import com.miuxuer.linkforge.service.AdminLogService;
import com.miuxuer.linkforge.vo.PageResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端 - 操作日志。
 *
 * <p>只提供查询，没有删除接口：日志是审计材料，能被"管理"就不叫审计了。
 * 清理历史日志应该走定时的归档任务（把 N 个月前的挪到冷表或对象存储），
 * 而不是留一个谁都能点的删除按钮。
 */
@RestController
@RequestMapping("/api/admin/log")
@RequiredArgsConstructor
@Validated
public class AdminLogController {

    private final AdminLogService adminLogService;

    @GetMapping("/page")
    public Result<PageResult<OperateLog>> page(@Valid OperateLogPageQueryDTO dto) {
        return Result.success(adminLogService.page(dto));
    }
}
