package com.miuxuer.linkforge.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 插件配置。
 *
 * <p>分页不是"查出来再截取"——那样要把全表捞进内存，数据一多就 OOM。
 * 真正的分页必须改写成 {@code LIMIT} 语句下推到数据库。
 * {@link PaginationInnerInterceptor} 干的就是这件事：拦截 SQL、解析、拼上 LIMIT。
 * "解析 SQL"需要 SQL 解析器，也就是 {@code mybatis-plus-jsqlparser} 这个依赖 ——
 * MP 3.5.9 之后它被从主包里拆出去了，只引 starter 会找不到这个类。
 */
@Configuration
public class MybatisPlusConfig {

    /**
     * 单页最大条数。
     *
     * <p>不设限的话，前端传个 {@code pageSize=1000000} 就能让数据库一次性
     * 扫出上百万行、把内存和带宽打满 —— 一个参数就能把服务拖垮。
     * 这种"客户端可控的资源消耗"必须由服务端兜底。
     */
    private static final long MAX_PAGE_SIZE = 100L;

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(paginationInnerInterceptor());
        return interceptor;
    }

    private PaginationInnerInterceptor paginationInnerInterceptor() {
        // 显式指定 MySQL：不指定的话 MP 每次都要去连数据库探测类型，多一次往返
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);
        pagination.setMaxLimit(MAX_PAGE_SIZE);
        // 请求页码超过总页数时，返回空列表而不是回到第一页。
        // 回到第一页会让前端"翻到最后一页继续点"时莫名其妙地看到第一页数据。
        pagination.setOverflow(false);
        return pagination;
    }
}
