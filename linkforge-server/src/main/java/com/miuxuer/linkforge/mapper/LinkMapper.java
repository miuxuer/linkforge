package com.miuxuer.linkforge.mapper;

import com.miuxuer.linkforge.entity.Link;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 短链数据访问接口。
 *
 * <p>继承 {@code BaseMapper} 后自动拥有 selectById / selectOne / updateById
 * 等常用方法，不用写 SQL。只有 BaseMapper 覆盖不到的场景才需要在这里加自定义方法 ——
 * 比如 {@code IdSegmentMapper.allocateSegment} 那种跑不动的原子更新。
 *
 * <p>写库走 {@link AutoFillMapper} 的 {@code insertWithFill} /
 * {@code updateByIdWithFill}，公共字段由切面自动填。
 *
 * <p>没有打 {@code @Mapper} 注解：启动类上的 {@code @MapperScan} 会统一扫描本包。
 */
public interface LinkMapper extends AutoFillMapper<Link> {

    /*
     * 下面几个是看板的聚合查询，条件构造器写不出 COUNT/SUM/ORDER BY + LIMIT，
     * 所以手写 SQL。
     *
     * ★ 手写 SQL 必须自己写 deleted = 0。
     * 实体上的 @TableLogic 只对 MyBatis-Plus 自己生成的那些方法生效
     * （selectById、selectList、update 之类），手写的 SQL 它管不着。
     * 漏写的话，被逻辑删除的短链会重新出现在统计里 —— 用户明明删了，
     * 看板上还挂着，而且数据对得上、看起来一切正常。
     */

    /** 该用户的短链总数。 */
    @Select("SELECT COUNT(*) FROM t_link WHERE user_id = #{userId} AND deleted = 0")
    long countByUser(@Param("userId") Long userId);

    /**
     * 该用户的累计访问量。
     *
     * <p>用 {@code COALESCE} 兜底：一条短链都没有时 {@code SUM} 返回的是 NULL，
     * 而返回类型是 {@code long}，映射到 NULL 会抛异常。
     */
    @Select("SELECT COALESCE(SUM(visit_count), 0) FROM t_link WHERE user_id = #{userId} AND deleted = 0")
    long sumVisitCountByUser(@Param("userId") Long userId);

    /**
     * 该用户的全部短码。
     *
     * <p>给看板用：要按短码去 Redis 里把这批短链"还没同步到数据库"的访问增量捞出来。
     */
    @Select("SELECT short_code FROM t_link WHERE user_id = #{userId} AND deleted = 0")
    List<String> selectShortCodesByUser(@Param("userId") Long userId);

    /**
     * 该用户的全部短链（只取看板要用的几列）。
     *
     * <p><b>为什么 Top N 不是直接 {@code ORDER BY visit_count DESC LIMIT n}</b>：
     * 数据库里的 {@code visit_count} 是每 5 分钟同步一次的值，而排序必须建立在
     * "加上 Redis 增量之后"的数值上。如果在 SQL 里排好序、再给每条补增量，
     * 补完之后列表就乱序了 —— 用户会看到第 1 条 3 次、第 3 条 10 次这种明显不对的排行。
     *
     * <p>增量只存在于 Redis，SQL 里拿不到，所以只能先把候选集取出来、补完增量再排序。
     * 代价是要加载该用户的全部短链 —— 但"一个用户有多少条短链"天然是有限的，
     * 而且这一列走 {@code idx_user_id} 索引，比全表扫访问明细表便宜得多。
     */
    @Select("SELECT id, short_code, title, visit_count FROM t_link "
            + "WHERE user_id = #{userId} AND deleted = 0")
    List<Link> selectAllByUser(@Param("userId") Long userId);
}
