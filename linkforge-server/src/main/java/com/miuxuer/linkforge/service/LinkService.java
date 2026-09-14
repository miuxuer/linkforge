package com.miuxuer.linkforge.service;

import com.miuxuer.linkforge.entity.Link;

/**
 * 短链服务接口。实现见 {@code impl/LinkServiceImpl}。
 *
 * <p>接口与实现分离是 Spring 项目的惯例：便于替换实现、也便于测试时 mock 掉依赖。
 */
public interface LinkService {

    /**
     * 创建短链：分配 id、Base62 编码成短码、入库。
     *
     * <p>本阶段还没有用户概念，绑定归属用户是阶段 3 的事。
     *
     * @param originalUrl 原始长链接
     * @return 已保存的实体，含生成的 shortCode
     */
    Link createLink(String originalUrl);

    /**
     * 按短码取原始长链接，供 302 跳转用。
     *
     * <p>内部走四层防护：布隆过滤器 → Redis 缓存 → 互斥锁 → 数据库。
     *
     * @param shortCode 短码
     * @return 原始长链接；不存在返回 null
     */
    String getOriginalUrl(String shortCode);

    /**
     * 访问计数 +1。
     *
     * <p>只写 Redis，不碰数据库 —— 由 {@code VisitCountSyncTask} 定时批量回写。
     * 调用方是异步的事件监听器，所以本方法不在跳转的响应链路上。
     *
     * @param shortCode 短码
     */
    void incrementVisitCount(String shortCode);
}
