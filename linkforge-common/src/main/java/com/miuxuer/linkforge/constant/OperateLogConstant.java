package com.miuxuer.linkforge.constant;

/**
 * 操作日志相关的常量。
 */
public final class OperateLogConstant {

    /** 操作成功。 */
    public static final int STATUS_SUCCESS = 0;

    /** 操作失败（方法抛了异常）。 */
    public static final int STATUS_FAILED = 1;

    /**
     * 入参 / 返回值写库前的截断长度（字符）。
     *
     * <p>这两列是 TEXT，理论上能放 64KB。但一条日志记录塞进几万个字符的 JSON，
     * 除了把日志表撑爆、让查询变慢之外没有任何价值 —— 排查问题需要的信息
     * 通常在前几百个字符里。
     */
    public static final int MAX_TEXT_LENGTH = 2000;

    /**
     * 异常信息写库前的截断长度。
     *
     * <p>这个值必须和 {@code t_operate_log.error_msg} 的列宽一致（VARCHAR(500)）。
     * 截断时不预留空间的话，超过列宽会直接抛
     * {@code Data too long for column} —— 记日志的动作反而把日志记崩了。
     */
    public static final int MAX_ERROR_LENGTH = 500;

    /** 内容被截断时追加的标记。 */
    public static final String TRUNCATED_SUFFIX = "...(已截断)";

    private OperateLogConstant() {
    }
}
