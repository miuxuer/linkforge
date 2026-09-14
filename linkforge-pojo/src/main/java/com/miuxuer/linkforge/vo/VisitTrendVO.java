package com.miuxuer.linkforge.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * 访问趋势里的一个数据点（一天）。
 *
 * <p>字段名和 SQL 里的别名严格对应（{@code visit_date} / {@code visit_count}），
 * 靠 MyBatis-Plus 的下划线转驼峰自动映射。没用 {@code date} / {@code count}
 * 做字段名是因为它们在 SQL 里都是关键字，拿来做别名得加反引号，
 * 平白多一个容易踩的点。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class VisitTrendVO {

    private LocalDate visitDate;

    private Long visitCount;
}
