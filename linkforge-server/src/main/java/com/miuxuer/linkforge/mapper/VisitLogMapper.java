package com.miuxuer.linkforge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.miuxuer.linkforge.entity.VisitLog;
import com.miuxuer.linkforge.vo.VisitTrendVO;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 访问明细数据访问接口。
 *
 * <p>看板的聚合查询（按天分组、按用户过滤）条件构造器写不出来，都在下面手写 SQL。
 */
public interface VisitLogMapper extends BaseMapper<VisitLog> {

    /*
     * ★ 几条手写 SQL 的共同点：都必须自己写 l.deleted = 0。
     * 实体上的 @TableLogic 只对 MyBatis-Plus 生成的方法生效，手写 SQL 管不着。
     * 漏了的话，用户删掉的短链还会出现在统计里。
     *
     * 用 JOIN 而不是 IN 子查询：MySQL 8 虽然能优化 IN 子查询，
     * 但 JOIN 的执行计划更直观（先按 user_id 走索引拿到短码，再回访问明细表），
     * 出问题时 EXPLAIN 一眼能看懂。
     */

    /**
     * 某个时间点之后该用户的访问次数。
     *
     * <p>没用 {@code DATE(visit_time) = CURDATE()}：那样会让 visit_time 上的索引失效
     * （对列做函数运算，索引就用不上了），而"今日访问"是看板每次打开都要跑的查询。
     * 改成 {@code >= 今天零点}，就是个范围扫描，索引还能用。
     */
    @Select("SELECT COUNT(*) FROM t_visit_log v "
            + "JOIN t_link l ON v.short_code = l.short_code "
            + "WHERE l.user_id = #{userId} AND l.deleted = 0 AND v.visit_time >= #{start}")
    long countVisitsSince(@Param("userId") Long userId, @Param("start") LocalDateTime start);

    /**
     * 按天的访问趋势。
     *
     * <p>只返回有访问记录的日期。没有访问的那几天不会出现在结果里，
     * 由 Service 补零 —— 直接把这个结果丢给前端画折线图的话，
     * x 轴会缺几天，图看起来是对的但其实是错的。
     */
    @Select("SELECT DATE(v.visit_time) AS visit_date, COUNT(*) AS visit_count "
            + "FROM t_visit_log v "
            + "JOIN t_link l ON v.short_code = l.short_code "
            + "WHERE l.user_id = #{userId} AND l.deleted = 0 AND v.visit_time >= #{start} "
            + "GROUP BY DATE(v.visit_time) "
            + "ORDER BY visit_date")
    List<VisitTrendVO> selectDailyTrend(@Param("userId") Long userId, @Param("start") LocalDateTime start);
}
