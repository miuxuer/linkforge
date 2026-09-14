package com.miuxuer.linkforge.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * 管理端操作日志分页查询条件。
 */
@Data
public class OperateLogPageQueryDTO {

    @Min(value = 1, message = "页码从 1 开始")
    private Integer page = 1;

    @Min(value = 1, message = "每页至少 1 条")
    @Max(value = 100, message = "每页最多 100 条")
    private Integer pageSize = 20;

    /** 只看某个操作人的记录。 */
    private Long operateUser;

    /** 只看成功的（0）或只看失败的（1）。不传表示全部。 */
    private Integer status;

    /** 关键词，模糊匹配类名或方法名。 */
    private String keyword;
}
