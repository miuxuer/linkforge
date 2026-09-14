package com.miuxuer.linkforge.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 修改短链入参。
 *
 * <p><b>这是"整条覆盖"语义，不是"改哪传哪"。</b> 四个字段都会被写进数据库，
 * 没传的字段就变成 null。这样设计是为了让"清空过期时间""清掉备注"这类操作
 * 能够表达出来 —— 如果用"null 表示不修改"，用户会发现设了过期时间之后就再也取消不掉了。
 *
 * <p>代价是前端提交时必须带上完整状态（从详情页拿到的原值回填），不能只传改动的字段。
 * 这也是 HTTP PUT 的标准语义（相对地，PATCH 才是局部更新）。
 */
@Data
public class LinkUpdateDTO {

    @Size(max = 100, message = "标题最长 100 个字符")
    private String title;

    @Size(max = 255, message = "备注最长 255 个字符")
    private String remark;

    /** 0=停用 1=启用。停用后跳转返回 404。 */
    @Min(value = 0, message = "状态取值不合法")
    @Max(value = 1, message = "状态取值不合法")
    private Integer status;

    /** 过期时间，传 null 表示取消过期限制、改为永不过期。 */
    private LocalDateTime expireTime;
}
