package com.miuxuer.linkforge.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * 管理端短链分页查询条件。
 */
@Data
public class AdminLinkPageQueryDTO {

    @Min(value = 1, message = "页码从 1 开始")
    private Integer page = 1;

    @Min(value = 1, message = "每页至少 1 条")
    @Max(value = 100, message = "每页最多 100 条")
    private Integer pageSize = 10;

    /** 关键词，模糊匹配标题或短码。 */
    private String keyword;

    /** 只看某个用户的短链。 */
    private Long userId;

    /** 按状态筛选：0=停用 1=启用。 */
    private Integer status;
}
