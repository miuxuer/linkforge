package com.miuxuer.linkforge.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.miuxuer.linkforge.dto.OperateLogPageQueryDTO;
import com.miuxuer.linkforge.entity.OperateLog;
import com.miuxuer.linkforge.mapper.OperateLogMapper;
import com.miuxuer.linkforge.service.AdminLogService;
import com.miuxuer.linkforge.vo.PageResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminLogServiceImpl implements AdminLogService {

    private final OperateLogMapper operateLogMapper;

    @Override
    public PageResult<OperateLog> page(OperateLogPageQueryDTO dto) {
        Page<OperateLog> pageParam = new Page<>(dto.getPage(), dto.getPageSize());

        LambdaQueryWrapper<OperateLog> wrapper = new LambdaQueryWrapper<OperateLog>()
                .eq(dto.getOperateUser() != null, OperateLog::getOperateUser, dto.getOperateUser())
                .eq(dto.getStatus() != null, OperateLog::getStatus, dto.getStatus())
                // 关键词的 OR 同样要包在 and(...) 里，否则会绕过上面两个筛选条件
                .and(StringUtils.hasText(dto.getKeyword()), w -> w
                        .like(OperateLog::getClassName, dto.getKeyword())
                        .or()
                        .like(OperateLog::getMethodName, dto.getKeyword()))
                // 日志按时间倒序：排查问题时要先看最近的
                .orderByDesc(OperateLog::getOperateTime);

        Page<OperateLog> result = operateLogMapper.selectPage(pageParam, wrapper);

        return PageResult.of(result.getTotal(), result.getCurrent(), result.getSize(),
                result.getRecords());
    }
}
