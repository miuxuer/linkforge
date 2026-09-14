package com.miuxuer.linkforge.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * 我的短链分页查询条件。
 *
 * <p><b>同样没有 userId 字段。</b> 查询范围永远是"当前登录用户的短链"，
 * 这一点由 Service 从 {@code CurrentHolder} 取身份来保证，不由请求决定。
 */
@Data
public class LinkPageQueryDTO {

    @Min(value = 1, message = "页码从 1 开始")
    private Integer page = 1;

    /**
     * 每页条数。
     *
     * <p>这里限 100 只是"友好提示"，真正的硬上限在
     * {@code MybatisPlusConfig} 的 {@code maxLimit} 里 ——
     * DTO 校验能被绕过（比如别的入口直接调 Service），拦截器那道才是兜底。
     */
    @Min(value = 1, message = "每页至少 1 条")
    @Max(value = 100, message = "每页最多 100 条")
    private Integer pageSize = 10;

    /** 关键词，模糊匹配标题或短码。 */
    private String keyword;

    /** 按状态筛选：0=停用 1=启用。不传表示全部。 */
    private Integer status;
}
