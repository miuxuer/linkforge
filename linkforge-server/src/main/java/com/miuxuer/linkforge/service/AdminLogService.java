package com.miuxuer.linkforge.service;

import com.miuxuer.linkforge.dto.OperateLogPageQueryDTO;
import com.miuxuer.linkforge.entity.OperateLog;
import com.miuxuer.linkforge.vo.PageResult;

/**
 * 管理端 - 操作日志查询。
 *
 * <p><b>为什么这里直接返回实体而不是 VO</b>：这张表的内容本来就是"给管理员看的"，
 * 没有需要隐藏的字段；而方法参数里的密码等敏感信息在<b>写入时</b>就已经脱敏了
 * （见 {@code OperateLogAspect} 的 mask）。再包一层字段一一对应的 VO，
 * 只是多一份要同步维护的代码，不提供任何额外保护。
 *
 * <p>反过来讲，如果真的往这张表加了敏感列，正确的做法是在写入时就不记，
 * 而不是指望读取时用 VO 挡住 —— 数据已经落库了，挡住接口也挡不住拖库。
 */
public interface AdminLogService {

    /** 操作日志分页。 */
    PageResult<OperateLog> page(OperateLogPageQueryDTO dto);
}
