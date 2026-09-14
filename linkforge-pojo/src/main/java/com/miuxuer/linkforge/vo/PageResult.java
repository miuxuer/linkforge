package com.miuxuer.linkforge.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 分页结果。
 *
 * <p><b>为什么不直接把 MyBatis-Plus 的 {@code Page} 返回给前端</b>：
 *
 * <ul>
 *   <li>{@code Page} 里有 {@code orders}、{@code optimizeCountSql}、{@code searchCount}
 *       这些纯内部字段，序列化出去就是一堆噪音，前端还会误以为能控制它们
 *   <li>它属于 ORM 的类型，接口形状跟着 ORM 走是件很糟的事 ——
 *       哪天换掉 MyBatis-Plus，所有前端代码都得跟着改
 *   <li>pojo 模块现在是零 ORM 依赖的，为了一个分页结果把 MyBatis-Plus 引进来不划算
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PageResult<T> {

    /** 总记录数。前端算总页数用。 */
    private long total;

    /** 当前页码，从 1 开始。 */
    private long page;

    /** 每页条数。 */
    private long pageSize;

    /** 当前页的数据。 */
    private List<T> records;

    public static <T> PageResult<T> of(long total, long page, long pageSize, List<T> records) {
        return PageResult.<T>builder()
                .total(total)
                .page(page)
                .pageSize(pageSize)
                .records(records)
                .build();
    }
}
