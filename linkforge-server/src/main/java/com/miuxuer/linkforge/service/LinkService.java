package com.miuxuer.linkforge.service;

import com.miuxuer.linkforge.dto.LinkCreateDTO;
import com.miuxuer.linkforge.dto.LinkPageQueryDTO;
import com.miuxuer.linkforge.dto.LinkUpdateDTO;
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
     * 修改自己的短链（标题、备注、状态、过期时间）。
     *
     * <p>整条覆盖语义：没传的字段会被写成 null，见 {@link LinkUpdateDTO}。
     *
     * @throws com.miuxuer.linkforge.exception.LinkNotFoundException 短链不存在
     * @throws com.miuxuer.linkforge.exception.BusinessException     短链不属于当前用户
     */
    void updateLink(Long id, LinkUpdateDTO dto);

    /**
     * 删除自己的短链（逻辑删除）。
     *
     * <p>逻辑删除而不是物理删除：短链一旦发出去就可能被人收藏、被人引用，
     * 记录删掉之后 {@code t_visit_log} 里的访问明细就成了孤儿数据，
     * 数据看板也没法回溯。保留 {@code deleted=1} 的记录既能"删掉"，
     * 又不破坏历史数据的完整性。
     *
     * @throws com.miuxuer.linkforge.exception.LinkNotFoundException 短链不存在
     * @throws com.miuxuer.linkforge.exception.BusinessException     短链不属于当前用户
     */
    void deleteLink(Long id);

    /**
     * 生成这条短链的二维码图片（PNG 字节）。
     *
     * <p>内容是完整短链接（域名 + 短码），如果这条短链配了 logo 就合成到中心。
     *
     * <p>本来可以用 HTTP 重定向把二维码生成交给第三方服务，但那样就把短链地址
     * 发给了外部 —— 自己的数据自己画，也省一次网络往返。
     *
     * @param id   短链主键
     * @param size 图片边长（像素）
     * @return PNG 图片字节
     * @throws com.miuxuer.linkforge.exception.LinkNotFoundException 短链不存在
     * @throws com.miuxuer.linkforge.exception.BusinessException     短链不属于当前用户
     */
    byte[] generateQrCode(Long id, int size);

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
