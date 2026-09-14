package com.miuxuer.linkforge.context;

/**
 * 当前登录用户的上下文，基于 {@link ThreadLocal}。
 *
 * <p><b>解决的问题</b>：Controller → Service → Mapper 这条链路上，很多方法都需要知道
 * "当前是谁在操作"（记录 create_user、拼多租户查询条件）。把这些信息一路当参数传下去，
 * 每个方法签名都要挂一个 userId，改起来牵一发动全身。ThreadLocal 把"当前请求的上下文"
 * 挂在执行线程上，任何地方都能直接取。
 *
 * <p><b>为什么必须有 {@link #remove()}</b>：Tomcat 用线程池处理请求，一个线程处理完
 * 请求 R1 后会被回收去处理 R2。<b>ThreadLocal 的值挂在线程上，不是挂在请求上</b> ——
 * 不清理的话，R2 如果没走到 set 就取值，取到的是 R1 残留的用户 id。后果是
 * 张三的请求带着李四的身份执行，越权查数据、写错 create_user，而且这种偶发 bug
 * 只在并发下复现，极其难查。除此之外 ThreadLocal 的值跟着线程活，不清理
 * 还会被线程池长期持有造成内存泄漏。
 *
 * <p>所以：拦截器 {@code preHandle} 里 set，{@code afterCompletion} 里 remove，
 * 两者必须成对出现。afterCompletion 无论请求成功还是抛异常都会执行，
 * 这正是清理要放在那里的原因（放 postHandle 的话，请求抛异常就清理不到了）。
 */
public final class CurrentHolder {

    /** 当前登录用户 id。 */
    private static final ThreadLocal<Long> CURRENT_ID = new ThreadLocal<>();

    /**
     * 当前登录用户的角色。
     *
     * <p>放在 ThreadLocal 里而不是每个请求回查数据库：角色信息已经在 token 的 claims 里，
     * 拦截器验签时顺手取出来即可，省掉一次 DB 往返。
     */
    private static final ThreadLocal<Integer> CURRENT_ROLE = new ThreadLocal<>();

    private CurrentHolder() {
    }

    public static void setCurrentId(Long id) {
        CURRENT_ID.set(id);
    }

    /** 取当前用户 id。未登录时返回 null。 */
    public static Long getCurrentId() {
        return CURRENT_ID.get();
    }

    public static void setCurrentRole(Integer role) {
        CURRENT_ROLE.set(role);
    }

    public static Integer getCurrentRole() {
        return CURRENT_ROLE.get();
    }

    /**
     * 清理当前线程上的上下文。
     *
     * <p>必须在拦截器的 {@code afterCompletion} 中调用。不能只调 {@code set(null)} ——
     * 那只是把值改成 null，entry 仍在线程的 ThreadLocalMap 里占着位置；
     * {@code remove()} 才会真正把 entry 摘掉。
     */
    public static void remove() {
        CURRENT_ID.remove();
        CURRENT_ROLE.remove();
    }
}
