package com.miuxuer.linkforge.service;

import com.miuxuer.linkforge.dto.LinkCreateDTO;
import com.miuxuer.linkforge.dto.LinkPageQueryDTO;
import com.miuxuer.linkforge.vo.LinkVO;
import com.miuxuer.linkforge.vo.PageResult;

/**
 * 短链服务接口。实现见 {@code impl/LinkServiceImpl}。
 *
 * <p>接口与实现分离是 Spring 项目的惯例：便于替换实现、也便于测试时 mock 掉依赖。
 */
public interface LinkService {

    /**
     * 创建短链：分配 id、Base62 编码成短码、绑定当前登录用户、入库。
     *
     * <p>归属用户从 {@code CurrentHolder} 取，<b>接口签名里没有 userId 参数</b> ——
     * 从签名上就不给"把短链挂到别人名下"留口子。
     *
     * @param dto 创建入参
     * @return 新建的短链（含 shortCode 和完整 shortUrl）
     * @throws com.miuxuer.linkforge.exception.BusinessException 未登录
     */
    LinkVO createLink(LinkCreateDTO dto);

    /**
     * 分页查询当前登录用户的短链。
     *
     * <p><b>查询范围由登录态决定，不由请求决定。</b> SQL 里永远带着
     * {@code user_id = 当前登录用户}，所以不存在"查到别人短链"这条路径 ——
     * 这是多租户隔离的实现方式：不去检查"你有没有权限看这条"，
     * 而是让你根本查不到它。
     *
     * @param dto 分页与筛选条件
     * @return 当前页的短链
     */
    PageResult<LinkVO> pageMyLinks(LinkPageQueryDTO dto);

    /**
     * 按短码取原始长链接，供 302 跳转用。
     *
     * <p>内部走四层防护：布隆过滤器 → Redis 缓存 → 互斥锁 → 数据库。
     *
     * @param shortCode 短码
     * @return 原始长链接；不存在、已停用或已过期都返回 null
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
