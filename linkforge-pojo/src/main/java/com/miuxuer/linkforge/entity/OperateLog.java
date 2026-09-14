package com.miuxuer.linkforge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 操作日志实体，对应 {@code t_operate_log}。
 *
 * <p><b>主键为什么用 {@link IdType#ASSIGN_ID}（雪花算法）而不是号段模式</b>：
 *
 * <ul>
 *   <li>这张表只写不读（除了管理端翻页），不需要"id 转成短码"这种玩法，
 *       雪花生成的 19 位数字够用
 *   <li>号段模式要在 {@code t_id_segment} 里加一行、走一次 UPDATE 才拿到 id。
 *       日志写入本身就在业务主流程之外，再为它多一次数据库往返不划算
 * </ul>
 *
 * <p>必须显式写 {@code type}：application.yml 里全局配的是 {@code id-type: input}
 * （主键由号段模式自己给），不覆盖的话 MyBatis-Plus 会认为"调用方会传 id"，
 * 而这里没人传，插入时 id 为 null 直接报错。
 *
 * <p>只有操作日志不带逻辑删除：日志是审计材料，不该被业务代码删掉。
 */
@Data
@TableName("t_operate_log")
public class OperateLog {

    /** 主键，雪花算法生成。 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 操作人 id。未登录时（比如注册）为 null。 */
    private Long operateUser;

    private LocalDateTime operateTime;

    /** 目标类的全限定名。 */
    private String className;

    private String methodName;

    /** 入参 JSON，超长会被截断。 */
    private String methodParams;

    /** 返回值 JSON，超长会被截断。 */
    private String returnValue;

    /** 方法耗时（毫秒）。 */
    private Long costTime;

    /** 0=成功 1=失败，见 {@code OperateLogConstant}。 */
    private Integer status;

    /** 失败时的异常信息，超长会被截断到列宽以内。 */
    private String errorMsg;
}
