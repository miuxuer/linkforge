package com.miuxuer.linkforge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.miuxuer.linkforge.entity.Link;

/**
 * 短链数据访问接口。
 *
 * <p>继承 {@code BaseMapper} 后自动拥有 insert / selectById / selectOne / updateById
 * 等常用方法，不用写 SQL。只有 BaseMapper 覆盖不到的场景才需要在这里加方法 ——
 * 比如 {@code IdSegmentMapper.allocateSegment} 那种跑不动的原子更新。
 *
 * <p>没有打 {@code @Mapper} 注解：启动类上的 {@code @MapperScan} 会统一扫描本包。
 */
public interface LinkMapper extends BaseMapper<Link> {
}
