package com.miuxuer.linkforge.dto;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 创建短链入参。
 *
 * <p><b>注意这里没有 userId 字段，也不该有。</b> 归属用户从
 * {@code CurrentHolder} 取 —— 如果让前端传 userId，任何人都能把短链挂到别人名下，
 * 或者把别人的短链"认领"过来。凡是"当前登录用户"相关的字段，
 * 一律不能由请求体提供。
 */
@Data
public class LinkCreateDTO {

    /**
     * 原始长链接。
     *
     * <p>这里先用正则卡住协议，跳转时还会再校验一次。两处都要：
     * DTO 这层是给用户明确提示的，跳转那层是防"绕过接口直接写库"的脏数据 ——
     * 数据库里可能存着 {@code javascript:} 开头的地址，只信 DTO 校验不够。
     */
    @NotBlank(message = "原始链接不能为空")
    @Size(max = 2048, message = "原始链接最长 2048 个字符")
    @Pattern(regexp = "^https?://.+", message = "只支持 http:// 或 https:// 开头的链接")
    private String originalUrl;

    @Size(max = 100, message = "标题最长 100 个字符")
    private String title;

    @Size(max = 255, message = "备注最长 255 个字符")
    private String remark;

    /**
     * 过期时间。不填表示永不过期。
     *
     * <p>{@code @Future} 只约束"不为 null 时必须是将来"—— 字段为 null 时校验直接通过，
     * 正好符合"可选"的语义，不用额外处理。
     *
     * <p>挡住"创建出来就已经过期"这种没意义的数据：否则用户建完链，点一下就是 404，
     * 还得自己排查是不是系统坏了。
     */
    @Future(message = "过期时间必须晚于当前时间")
    private LocalDateTime expireTime;
}
